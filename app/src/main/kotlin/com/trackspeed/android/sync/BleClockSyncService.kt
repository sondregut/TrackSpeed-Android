package com.trackspeed.android.sync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.trackspeed.android.cloud.DeviceIdProvider
import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingMessageCodec
import com.trackspeed.android.protocol.TimingPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE-based clock synchronization service.
 *
 * Implements NTP-style ping-pong over BLE GATT for cross-platform
 * clock synchronization between Android and iOS devices.
 *
 * Uses JSON-encoded TimingMessage objects that match the iOS Speed Swift
 * app's TimingMessage protocol exactly, enabling cross-platform BLE
 * communication.
 *
 * Characteristic layout matches iOS BluetoothTransport:
 * - TX (host -> joiner): NOTIFY + READ  (UUID ...7891)
 * - RX (joiner -> host): WRITE + WRITE_WITHOUT_RESPONSE  (UUID ...7892)
 */
@Singleton
class BleClockSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudTimingRelay: CloudTimingRelay,
    private val deviceIdProvider: DeviceIdProvider
) {
    companion object {
        private const val TAG = "BleClockSync"
        private const val SCAN_REFRESH_TIMEOUT_MS = 30_000L
        private const val GATT_READY_TIMEOUT_MS = 15_000L
        private const val DEFAULT_ATT_PAYLOAD_BYTES = 20
        private const val ATT_PROTOCOL_OVERHEAD_BYTES = 3
        private const val BOND_RETRY_DELAY_MS = 1_000L
        private const val MAX_BOND_RETRIES = 12
    }

    // States
    sealed class State {
        object Idle : State()
        object Pairing : State()       // Dual-mode: advertising + scanning simultaneously
        object Scanning : State()
        object Connecting : State()
        object Connected : State()
        object ClientReady : State()   // Client has enabled notifications (CCC written)
        data class Syncing(val progress: Float) : State()
        data class Synced(val result: ClockSyncCalculator.SyncResult) : State()
        data class Error(val message: String) : State()
    }

    sealed class Role {
        object Server : Role()  // Advertises, responds to pings
        object Client : Role()  // Scans, sends pings
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _syncResult = MutableStateFlow<ClockSyncCalculator.SyncResult?>(null)
    val syncResult: StateFlow<ClockSyncCalculator.SyncResult?> = _syncResult.asStateFlow()

    // Bluetooth components
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    // GATT components
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var gattClient: BluetoothGatt? = null
    private val acceptingServerConnections = AtomicBoolean(false)
    // Multi-client: map of device address -> BluetoothDevice
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    // Legacy single-device accessor (for client mode or first server connection)
    private val connectedDevice: BluetoothDevice?
        get() = connectedDevices.values.firstOrNull()

    // Advertising
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = AtomicBoolean(false)
    @Volatile private var advertiseCallback: AdvertiseCallback? = null
    private val transportGeneration = AtomicLong(0L)

    // Scanning
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = AtomicBoolean(false)
    private var scanTimeoutRunnable: Runnable? = null
    private var gattReadyTimeoutRunnable: Runnable? = null
    private var connectingDeviceAddress: String? = null

    // Per-device notification readiness: tracks which clients have enabled CCC notifications
    private val clientNotificationsReady = ConcurrentHashMap.newKeySet<String>()

    // Connection events flow for ClockSyncManager to track new clients
    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    // Per-client notification readiness events (emitted when a client writes CCC descriptor)
    private val _clientReadyDevices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val clientReadyDevices: SharedFlow<String> = _clientReadyDevices.asSharedFlow()

    // Map from TimingMessage senderId → BLE device address (populated on server receiving writes)
    private val senderDeviceMap = ConcurrentHashMap<String, String>()

    data class ConnectionEvent(val device: BluetoothDevice, val connected: Boolean)

    fun connectedDeviceIds(): List<String> = connectedDevices.keys.toList()

    // Sync state
    @Volatile private var role: Role? = null
    @Volatile private var syncCalculator: ClockSyncCalculator? = null
    private val sequenceNumber = AtomicLong(0)
    private val pendingPings = ConcurrentHashMap<String, Long>()  // pingId (UUID) -> t1 nanos
    private val criticalMessageRetries = CriticalMessageRetryTracker(
        nowMillis = SystemClock::elapsedRealtime
    )
    private var syncJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    // BLE allows one acknowledged client write and one server notification at
    // a time. Queues contain already-framed ATT packets, not whole messages.
    private val clientWriteQueue = ConcurrentLinkedDeque<ByteArray>()
    private val isClientWritePending = AtomicBoolean(false)
    @Volatile private var clientWriteInFlight: ByteArray? = null
    @Volatile private var clientMaximumPacketBytes = DEFAULT_ATT_PAYLOAD_BYTES

    private data class ServerPacket(val deviceAddress: String, val data: ByteArray)

    private val serverNotificationQueue = ConcurrentLinkedQueue<ServerPacket>()
    private val isServerNotificationPending = AtomicBoolean(false)
    private val serverMaximumPacketBytes = ConcurrentHashMap<String, Int>()
    private val serverInboundFramers = ConcurrentHashMap<String, BleMessageFramer>()
    private val clientInboundFramer = BleMessageFramer()
    @Volatile private var notificationBondRetryCount = 0

    // General message receiving (for non-sync messages like crossing events)
    private val _incomingMessages = MutableSharedFlow<TimingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<TimingMessage> = _incomingMessages.asSharedFlow()

    // Device and session identity (stable across app runs)
    private val deviceId: String by lazy { deviceIdProvider.deviceId }
    val localDeviceId: String get() = deviceId
    @Volatile private var sessionId: String = UUID.randomUUID().toString().uppercase()
    val currentSessionId: String get() = sessionId

    // Characteristics - names match iOS BluetoothTransport:
    //   TX = host->joiner (NOTIFY+READ), RX = joiner->host (WRITE+WRITE_WITHOUT_RESPONSE)
    @Volatile private var txCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var rxCharacteristic: BluetoothGattCharacteristic? = null

    init {
        cloudTimingRelay.incomingMessages
            .onEach(::handleCloudMessageReceived)
            .launchIn(serviceScope)
        serviceScope.launch {
            while (isActive) {
                delay(CriticalMessageRetryTracker.CHECK_INTERVAL_MS)
                criticalMessageRetries.poll().forEach(::processCriticalRetryAction)
            }
        }
    }

    /**
     * Start as server (advertises, responds to pings).
     * Use this on the device that should be the "reference" clock.
     */
    @SuppressLint("MissingPermission")
    fun startAsServer() {
        if (!hasServerPermissions()) {
            publishMissingBluetoothPermission()
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            _state.value = State.Error("Bluetooth is not enabled")
            return
        }

        prepareForNewTransportStart()

        role = Role.Server
        _state.value = State.Idle
        cloudTimingRelay.start(sessionId, deviceId, isHost = true)

        // Start GATT server
        startGattServer()

        // Start advertising
        startAdvertising()
    }

    /**
     * Start as client (scans for server, initiates sync).
     * Use this on the device that needs to sync its clock.
     */
    @SuppressLint("MissingPermission")
    fun startAsClient() {
        if (!hasClientPermissions()) {
            publishMissingBluetoothPermission()
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            _state.value = State.Error("Bluetooth is not enabled")
            return
        }

        prepareForNewTransportStart()
        role = Role.Client
        _state.value = State.Scanning

        // Start scanning for server
        startScanning()
    }

    /**
     * Start in dual mode: advertise AND scan simultaneously.
     * The first device to connect determines roles:
     * - If a client connects to our GATT server → we become Server
     * - If we find another device's advertisement → we become Client
     */
    @SuppressLint("MissingPermission")
    fun startDual() {
        if (!hasClientPermissions() || !hasServerPermissions()) {
            publishMissingBluetoothPermission()
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            _state.value = State.Error("Bluetooth is not enabled")
            return
        }

        prepareForNewTransportStart()

        role = null  // Undecided until connection resolves
        _state.value = State.Pairing

        // Start GATT server (so others can connect to us)
        startGattServer()

        // Start advertising (so others can find us)
        startAdvertising()

        // Start scanning (so we can find others)
        startScanning()

        Log.i(TAG, "Dual-mode pairing started: advertising + scanning")
    }

    private fun hasClientPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasServerPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun publishMissingBluetoothPermission() {
        _state.value = State.Error("Nearby devices permission is required for multi-phone timing.")
    }

    /**
     * Get the resolved role after dual-mode pairing completes.
     */
    fun getResolvedRole(): Role? = role

    /**
     * Look up the BLE device address for a given TimingMessage senderId.
     * Only populated in server mode when messages are received from clients.
     */
    fun getDeviceAddress(senderId: String): String? = senderDeviceMap[senderId]
        ?.takeIf(connectedDevices::containsKey)

    /**
     * Known peer device IDs from TimingMessage.senderId values.
     * Used by the host to fan out relayed timing events to every peer except
     * the originator, matching iOS RaceSession.relayCritical.
     */
    fun connectedPeerDeviceIds(): List<String> = senderDeviceMap
        .filterValues(connectedDevices::containsKey)
        .keys
        .toList()

    /** Reset link-local state without rotating the logical session ID. */
    @SuppressLint("MissingPermission")
    private fun prepareForNewTransportStart() {
        transportGeneration.incrementAndGet()
        val previousWasServer = role == Role.Server
        role = null
        syncJob?.cancel()
        syncJob = null
        cancelScanTimeout()
        cancelGattReadyTimeout()
        stopAdvertising()
        stopScanning()
        closeGatt()
        cloudTimingRelay.stop(deviceId, isHost = previousWasServer)

        connectedDevices.clear()
        clientNotificationsReady.clear()
        senderDeviceMap.clear()
        pendingPings.clear()
        criticalMessageRetries.clear()
        clientWriteQueue.clear()
        isClientWritePending.set(false)
        clientWriteInFlight = null
        clientMaximumPacketBytes = DEFAULT_ATT_PAYLOAD_BYTES
        serverNotificationQueue.clear()
        isServerNotificationPending.set(false)
        serverMaximumPacketBytes.clear()
        serverInboundFramers.clear()
        clientInboundFramer.reset()
        notificationBondRetryCount = 0
        connectingDeviceAddress = null
        syncCalculator = null
        _syncResult.value = null
    }

    /**
     * Stop all BLE operations.
     */
    @SuppressLint("MissingPermission")
    fun stop() {
        transportGeneration.incrementAndGet()
        val wasServer = role == Role.Server
        role = null
        syncJob?.cancel()
        syncJob = null
        cancelScanTimeout()
        cancelGattReadyTimeout()

        stopAdvertising()
        stopScanning()
        closeGatt()

        syncCalculator = null
        pendingPings.clear()
        criticalMessageRetries.clear()
        clientWriteQueue.clear()
        isClientWritePending.set(false)
        clientWriteInFlight = null
        clientMaximumPacketBytes = DEFAULT_ATT_PAYLOAD_BYTES
        serverNotificationQueue.clear()
        isServerNotificationPending.set(false)
        serverMaximumPacketBytes.clear()
        serverInboundFramers.clear()
        clientInboundFramer.reset()
        notificationBondRetryCount = 0
        clientNotificationsReady.clear()
        connectedDevices.clear()
        senderDeviceMap.clear()
        cloudTimingRelay.stop(deviceId, isHost = wasServer)
        sessionId = UUID.randomUUID().toString().uppercase()

        _state.value = State.Idle
    }

    // ==================== Server Mode ====================

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        val server = try {
            bluetoothManager.openGattServer(context, gattServerCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission while opening GATT server", e)
            _state.value = State.Error("Nearby devices permission is required for multi-phone timing.")
            null
        }
        acceptingServerConnections.set(server != null)
        gattServer = server?.apply {
            // Create service with UUIDs matching iOS BluetoothTransport
            val service = BluetoothGattService(
                ClockSyncConfig.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // TX characteristic (host -> joiner): NOTIFY + READ, encrypted.
            // Matches iOS properties [.notifyEncryptionRequired] and
            // permissions [.readEncryptionRequired].
            txCharacteristic = BluetoothGattCharacteristic(
                ClockSyncConfig.PING_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
            ).apply {
                // Add CCC descriptor for notifications
                val descriptor = BluetoothGattDescriptor(
                    java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                    BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED or
                            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED
                )
                addDescriptor(descriptor)
            }
            service.addCharacteristic(txCharacteristic)

            // RX characteristic (joiner -> host): encrypted WRITE.
            rxCharacteristic = BluetoothGattCharacteristic(
                ClockSyncConfig.PONG_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
            )
            service.addCharacteristic(rxCharacteristic)

            try {
                addService(service)
                Log.i(TAG, "GATT server started with framed JSON protocol v7")
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing Bluetooth permission while adding GATT service", e)
                _state.value = State.Error("Nearby devices permission is required for multi-phone timing.")
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (!acceptingServerConnections.get()) {
                runCatching { gattServer?.cancelConnection(device) }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Server: Client connected: ${device.address} (total: ${connectedDevices.size + 1})")
                    connectedDevices[device.address] = device
                    serverMaximumPacketBytes[device.address] = DEFAULT_ATT_PAYLOAD_BYTES
                    serverInboundFramers[device.address] = BleMessageFramer()
                    // In dual-mode: stop scanning since we've been chosen as server
                    if (role == null) {
                        stopScanning()
                        role = Role.Server
                        cloudTimingRelay.start(sessionId, deviceId, isHost = true)
                        Log.i(TAG, "Dual-mode resolved: this device is Server (client connected to us)")
                    }
                    // Keep advertising so additional clients can connect
                    // (don't stop advertising after first client)
                    _connectionEvents.tryEmit(ConnectionEvent(device, true))
                    _state.value = State.Connected
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Server: Client disconnected: ${device.address}")
                    connectedDevices.remove(device.address)
                    clientNotificationsReady.remove(device.address)
                    serverMaximumPacketBytes.remove(device.address)
                    serverInboundFramers.remove(device.address)
                    senderDeviceMap.entries.removeIf { it.value == device.address }
                    serverNotificationQueue.removeIf { it.deviceAddress == device.address }
                    _connectionEvents.tryEmit(ConnectionEvent(device, false))
                    if (connectedDevices.isEmpty()) {
                        _state.value = State.Idle
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid != ClockSyncConfig.PONG_CHARACTERISTIC_UUID || preparedWrite || offset != 0) {
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        0,
                        null
                    )
                }
                return
            }

            val payloads = try {
                serverInboundFramers
                    .getOrPut(device.address) { BleMessageFramer() }
                    .receive(value)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Server: Rejected malformed BLE frame from ${device.address}: ${e.message}")
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                        0,
                        null
                    )
                }
                return
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            payloads.forEach { payload ->
                handleServerMessage(device, payload, SystemClock.elapsedRealtimeNanos())
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            serverMaximumPacketBytes[device.address] =
                (mtu - ATT_PROTOCOL_OVERHEAD_BYTES).coerceAtLeast(DEFAULT_ATT_PAYLOAD_BYTES)
            Log.i(TAG, "Server: MTU for ${device.address} changed to $mtu")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            isServerNotificationPending.set(false)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Server: Notification failed for ${device.address}: status=$status")
            }
            drainServerNotificationQueue()
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            // Handle CCC descriptor (enable notifications)
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            // Detect client enabling notifications on TX characteristic (CCC descriptor)
            val cccUuid = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            if (descriptor.uuid == cccUuid &&
                descriptor.characteristic.uuid == ClockSyncConfig.PING_CHARACTERISTIC_UUID &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            ) {
                Log.i(TAG, "Server: Client ${device.address} enabled notifications (CCC written) — ClientReady")
                clientNotificationsReady.add(device.address)
                _clientReadyDevices.tryEmit(device.address)
                _state.value = State.ClientReady
            } else {
                Log.d(TAG, "Server: Descriptor write for ${descriptor.characteristic.uuid}")
            }
        }
    }

    private fun handleServerMessage(
        device: BluetoothDevice,
        data: ByteArray,
        receivedAtNanos: Long
    ) {
        if (data.size > BleMessageFramer.MAXIMUM_PAYLOAD_BYTES) {
            Log.w(TAG, "Server: Rejected oversized message (${data.size} bytes)")
            return
        }
        val message = try {
            TimingMessageCodec.decodeFromBytes(data)
        } catch (e: Exception) {
            Log.e(TAG, "Server: Failed to decode message: ${e.message}")
            return
        }

        senderDeviceMap[message.senderId] = device.address

        if (!isEnvelopeForLocalDevice(message)) {
            Log.d(TAG, "Server: Ignoring message targeted to ${message.targetDeviceId?.take(8)}")
            return
        }
        handleCriticalResponse(message, device.address)

        when (val payload = message.payload) {
            is TimingPayload.SyncPing -> {
                val pongMessage = TimingMessage.create(
                    seq = sequenceNumber.incrementAndGet(),
                    senderId = deviceId,
                    sessionId = sessionId,
                    payload = TimingPayload.SyncPong(
                        pingId = payload.pingId,
                        t1Nanos = payload.t1Nanos,
                        t2Nanos = receivedAtNanos,
                        t3Nanos = SystemClock.elapsedRealtimeNanos(),
                        requesterId = payload.requesterId
                    )
                )
                enqueueServerPayload(device, TimingMessageCodec.encodeToBytes(pongMessage))
                Log.d(TAG, "Server: Responded to syncPing (pingId=${payload.pingId.take(8)})")
            }
            else -> {
                // ACK the sender specifically. Broadcasting an ACK in a
                // three-phone session can incorrectly clear another peer's
                // retry window when message IDs happen to be replayed.
                if (message.requiresAck && message.messageId != null) {
                    Log.d(
                        TAG,
                        "Server: Auto-ACK for ${payload::class.simpleName} " +
                            "(msgId=${message.messageId.take(8)})"
                    )
                    sendMessageToDevice(
                        payload = TimingPayload.Ack(messageId = message.messageId),
                        deviceAddress = device.address,
                        targetDeviceId = message.senderId
                    )
                }
                Log.d(TAG, "Server: Forwarding ${payload::class.simpleName} to app")
                _incomingMessages.tryEmit(message)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueServerPayload(device: BluetoothDevice, payload: ByteArray): Boolean {
        val maximumPacketBytes = serverMaximumPacketBytes[device.address]
            ?: DEFAULT_ATT_PAYLOAD_BYTES
        val packets = try {
            BleMessageFramer.packets(payload, maximumPacketBytes)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Server: Failed to frame BLE payload: ${e.message}")
            return false
        }
        packets.forEach { serverNotificationQueue.add(ServerPacket(device.address, it)) }
        drainServerNotificationQueue()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun drainServerNotificationQueue() {
        while (isServerNotificationPending.compareAndSet(false, true)) {
            val packet = serverNotificationQueue.poll()
            if (packet == null) {
                isServerNotificationPending.set(false)
                return
            }
            val device = connectedDevices[packet.deviceAddress]
            val characteristic = txCharacteristic
            val server = gattServer
            if (device == null || characteristic == null || server == null) {
                // A disconnected peer may leave several already-framed
                // packets behind. Iteration avoids recursive stack growth.
                isServerNotificationPending.set(false)
                continue
            }

            characteristic.value = packet.data
            val queued = try {
                server.notifyCharacteristicChanged(device, characteristic, false)
            } catch (e: SecurityException) {
                Log.w(TAG, "Server: Missing permission while notifying ${device.address}", e)
                false
            }
            if (!queued) {
                isServerNotificationPending.set(false)
                serverNotificationQueue.add(packet)
                handler.postDelayed({ drainServerNotificationQueue() }, 25L)
            }
            return
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            _state.value = State.Error("BLE advertising not supported")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            // A 128-bit service UUID nearly fills the 31-byte legacy
            // advertisement. Including an arbitrary Android device name can
            // make startAdvertising fail with ADVERTISE_FAILED_DATA_TOO_LARGE.
            // Both platforms discover by service UUID, so the name is not
            // required on the wire.
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(ClockSyncConfig.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(
                ParcelUuid(ClockSyncConfig.SERVICE_UUID),
                BleRoleElection.tokenForDeviceId(deviceId)
            )
            .build()

        val generation = transportGeneration.get()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (generation != transportGeneration.get()) return
                isAdvertising.set(true)
                Log.i(TAG, "Advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                if (generation != transportGeneration.get()) return
                isAdvertising.set(false)
                _state.value = State.Error("Advertising failed: $errorCode")
                Log.e(TAG, "Advertising failed: $errorCode")
            }
        }
        advertiseCallback = callback
        try {
            advertiser?.startAdvertising(settings, data, scanResponse, callback)
            Log.i(TAG, "Started advertising clock sync service")
        } catch (e: SecurityException) {
            isAdvertising.set(false)
            Log.e(TAG, "Missing Bluetooth permission while starting advertising", e)
            _state.value = State.Error("Nearby devices permission is required for multi-phone timing.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val wasAdvertising = isAdvertising.getAndSet(false)
        val callback = advertiseCallback
        advertiseCallback = null
        try {
            if (callback != null) advertiser?.stopAdvertising(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising error (was=$wasAdvertising): ${e.message}")
        }
        if (wasAdvertising) {
            Log.i(TAG, "Stopped advertising")
        }
    }

    // ==================== Client Mode ====================

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.value = State.Error("BLE scanning not supported")
            return
        }
        if (isScanning.get()) {
            Log.d(TAG, "startScanning ignored - already scanning")
            scheduleScanRefreshTimeout()
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(ClockSyncConfig.SERVICE_UUID))
                .build()
        )

        try {
            scanner?.startScan(filters, settings, scanCallback)
            isScanning.set(true)
            Log.i(TAG, "Started scanning for clock sync servers")
        } catch (e: SecurityException) {
            isScanning.set(false)
            Log.e(TAG, "Missing Bluetooth permission while starting scan", e)
            _state.value = State.Error("Nearby devices permission is required for multi-phone timing.")
            return
        }

        scheduleScanRefreshTimeout()
    }

    private fun scheduleScanRefreshTimeout() {
        cancelScanTimeout()
        val runnable = Runnable {
            val waitingForPeer = _state.value is State.Scanning ||
                (_state.value is State.Pairing && role == null)
            if (isScanning.get() && waitingForPeer) {
                Log.w(TAG, "Scan refresh timeout - still waiting for host, restarting BLE scan")
                refreshClientScan("scan_timeout_keepalive")
            }
        }
        scanTimeoutRunnable = runnable
        handler.postDelayed(runnable, SCAN_REFRESH_TIMEOUT_MS)
    }

    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun refreshClientScan(reason: String) {
        if (bluetoothAdapter?.isEnabled != true) {
            _state.value = State.Error("Bluetooth is not enabled")
            return
        }

        Log.i(TAG, "Refreshing BLE scan: $reason")
        cancelScanTimeout()
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan during refresh failed: ${e.message}")
        }
        isScanning.set(false)
        _state.value = if (role == null && acceptingServerConnections.get()) {
            State.Pairing
        } else {
            State.Scanning
        }
        startScanning()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isScanning.get()) return

            Log.i(TAG, "Found clock sync server: ${result.device.address}")

            // In dual-mode: stop advertising and close GATT server since we're becoming client
            if (role == null) {
                val remoteToken = result.scanRecord?.getServiceData(
                    ParcelUuid(ClockSyncConfig.SERVICE_UUID)
                )
                val election = remoteToken?.let {
                    BleRoleElection.shouldBecomeClient(
                        localToken = BleRoleElection.tokenForDeviceId(deviceId),
                        remoteToken = it
                    )
                }
                if (election == false) {
                    // The lower deterministic token remains the peripheral.
                    // The peer sees the inverse ordering and connects to us.
                    stopScanning()
                    role = Role.Server
                    cloudTimingRelay.start(sessionId, deviceId, isHost = true)
                    _state.value = State.Pairing
                    Log.i(TAG, "Dual-mode election resolved: remaining Server")
                    return
                }
                if (election == null && remoteToken != null) {
                    // Identical tokens indicate the same restored device ID (or
                    // an effectively impossible hash collision). Ignore this
                    // advertisement instead of making both phones clients.
                    Log.w(TAG, "Ignoring dual-mode peer with identical election token")
                    return
                }

                stopScanning()
                stopAdvertising()
                closeGattServer()
                role = Role.Client
                Log.i(TAG, "Dual-mode resolved: this device is Client (found another server)")
            } else {
                stopScanning()
            }

            connectToServer(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning.set(false)
            cancelScanTimeout()
            _state.value = State.Error("Scan failed: $errorCode")
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        cancelScanTimeout()
        if (isScanning.getAndSet(false)) {
            try {
                scanner?.stopScan(scanCallback)
                Log.i(TAG, "Stopped scanning")
            } catch (e: SecurityException) {
                Log.w(TAG, "stopScan missing Bluetooth permission: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToServer(device: BluetoothDevice) {
        _state.value = State.Connecting
        connectingDeviceAddress = device.address
        Log.i(TAG, "Connecting to server: ${device.address}")

        scheduleGattReadyTimeout(device.address)
        gattClient = try {
            device.connectGatt(
                context,
                false,
                gattClientCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission while connecting GATT client", e)
            _state.value = State.Error("Nearby devices permission is required for multi-phone timing.")
            null
        }
    }

    private fun scheduleGattReadyTimeout(deviceAddress: String) {
        cancelGattReadyTimeout()
        val runnable = Runnable {
            if (_state.value is State.Connecting && connectingDeviceAddress == deviceAddress) {
                Log.w(TAG, "GATT ready timeout for $deviceAddress - restarting scan")
                restartClientDiscovery("gatt_ready_timeout")
            }
        }
        gattReadyTimeoutRunnable = runnable
        handler.postDelayed(runnable, GATT_READY_TIMEOUT_MS)
    }

    private fun cancelGattReadyTimeout() {
        gattReadyTimeoutRunnable?.let { handler.removeCallbacks(it) }
        gattReadyTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun restartClientDiscovery(reason: String) {
        Log.i(TAG, "Restarting BLE client discovery: $reason")
        cancelGattReadyTimeout()
        connectingDeviceAddress = null
        try {
            gattClient?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect during restart failed: ${e.message}")
        }
        closeGattClient()
        connectedDevices.clear()
        txCharacteristic = null
        rxCharacteristic = null
        clientWriteQueue.clear()
        isClientWritePending.set(false)
        clientWriteInFlight = null
        clientMaximumPacketBytes = DEFAULT_ATT_PAYLOAD_BYTES
        clientInboundFramer.reset()
        notificationBondRetryCount = 0

        if (bluetoothAdapter?.isEnabled == true && role == Role.Client) {
            _state.value = State.Scanning
            startScanning()
        } else if (bluetoothAdapter?.isEnabled != true) {
            _state.value = State.Error("Bluetooth is not enabled")
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gattClient !== gatt) {
                runCatching { gatt.close() }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Client: Connected to server")
                    connectedDevices[gatt.device.address] = gatt.device
                    // Request large MTU for JSON messages (~250-400 bytes)
                    gatt.requestMtu(ClockSyncConfig.PREFERRED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Client: Disconnected from server")
                    connectedDevices.remove(gatt.device.address)
                    if (role == Role.Client) {
                        restartClientDiscovery("client_disconnected")
                    } else {
                        closeGattClient()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "Client: MTU changed to $mtu (status=$status)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                clientMaximumPacketBytes =
                    (mtu - ATT_PROTOCOL_OVERHEAD_BYTES).coerceAtLeast(DEFAULT_ATT_PAYLOAD_BYTES)
            }
            // Proceed with service discovery regardless of MTU result
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: status=$status")
                restartClientDiscovery("service_discovery_failed")
                return
            }

            val service = gatt.getService(ClockSyncConfig.SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "Clock sync service not found")
                restartClientDiscovery("service_not_found")
                return
            }

            // TX characteristic (host -> joiner): we subscribe to notifications on this
            // RX characteristic (joiner -> host): we write ping messages to this
            txCharacteristic = service.getCharacteristic(ClockSyncConfig.PING_CHARACTERISTIC_UUID)
            rxCharacteristic = service.getCharacteristic(ClockSyncConfig.PONG_CHARACTERISTIC_UUID)

            if (txCharacteristic == null || rxCharacteristic == null) {
                Log.w(TAG, "Required characteristics not found")
                restartClientDiscovery("characteristics_not_found")
                return
            }
            // Enable notifications on TX characteristic (host -> joiner)
            // This is where we receive pong responses
            gatt.setCharacteristicNotification(txCharacteristic, true)
            val descriptor = txCharacteristic?.getDescriptor(
                java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            if (descriptor == null) {
                Log.w(TAG, "Client: Notification descriptor not found")
                restartClientDiscovery("notification_descriptor_not_found")
                return
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) {
                Log.w(TAG, "Client: Failed to start notification descriptor write")
                restartClientDiscovery("notification_descriptor_write_failed")
                return
            }

            Log.i(TAG, "Client: Services discovered, enabling encrypted notifications")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                notificationBondRetryCount = 0
                cancelGattReadyTimeout()
                connectingDeviceAddress = null
                Log.i(TAG, "Client: Encrypted notifications enabled, ready for protocol handshake")
                _state.value = State.Connected
            } else if (isAuthenticationFailure(status)) {
                retryEncryptedNotificationSetup(gatt, descriptor)
            } else {
                Log.w(TAG, "Client: Notification descriptor write failed: status=$status")
                restartClientDiscovery("notification_descriptor_status_$status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isClientWritePending.set(false)
            val inFlight = clientWriteInFlight
            clientWriteInFlight = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                drainClientWriteQueue()
            } else if (isAuthenticationFailure(status) && inFlight != null) {
                Log.w(TAG, "Client: Encrypted write requires bonding; retrying after bond")
                clientWriteQueue.addFirst(inFlight)
                runCatching { gatt.device.createBond() }
                handler.postDelayed({ drainClientWriteQueue() }, BOND_RETRY_DELAY_MS)
            } else {
                Log.w(TAG, "Client: Write failed: status=$status; clearing queued packets")
                clientWriteQueue.clear()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == ClockSyncConfig.PING_CHARACTERISTIC_UUID) {
                handleClientPacket(characteristic.value)
            }
        }

        // For Android 13+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == ClockSyncConfig.PING_CHARACTERISTIC_UUID) {
                handleClientPacket(value)
            }
        }
    }

    private fun isAuthenticationFailure(status: Int): Boolean {
        return status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
            status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
    }

    @SuppressLint("MissingPermission")
    private fun retryEncryptedNotificationSetup(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor
    ) {
        if (notificationBondRetryCount >= MAX_BOND_RETRIES) {
            Log.w(TAG, "Client: Timed out waiting for encrypted BLE bond")
            restartClientDiscovery("encrypted_notification_bond_timeout")
            return
        }
        notificationBondRetryCount++
        if (gatt.device.bondState == BluetoothDevice.BOND_NONE) {
            runCatching { gatt.device.createBond() }
                .onFailure { Log.w(TAG, "Client: Failed to start BLE bond", it) }
        }
        handler.postDelayed(
            {
                if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (!gatt.writeDescriptor(descriptor)) {
                        retryEncryptedNotificationSetup(gatt, descriptor)
                    }
                } else {
                    retryEncryptedNotificationSetup(gatt, descriptor)
                }
            },
            BOND_RETRY_DELAY_MS
        )
    }

    private fun handleClientPacket(packet: ByteArray) {
        val payloads = try {
            clientInboundFramer.receive(packet)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Client: Rejected malformed BLE frame: ${e.message}")
            return
        }
        payloads.forEach(::handleMessageReceived)
    }

    /**
     * Start the NTP clock sync process. Called by ClockSyncManager after
     * the protocol handshake completes (not immediately on BLE connection).
     */
    @SuppressLint("MissingPermission")
    fun startSync() {
        syncJob?.cancel()
        syncJob = null
        syncCalculator = null
        _syncResult.value = null
        pendingPings.clear()
        _state.value = State.Syncing(0f)
        Log.i(TAG, "Starting clock synchronization (${ClockSyncConfig.FULL_SYNC_SAMPLES} samples, JSON protocol)...")

        syncJob = serviceScope.launch {
            var lastResult: ClockSyncCalculator.SyncResult? = null

            // Auto-retry up to MAX_SYNC_RETRIES times
            for (attempt in 1..ClockSyncConfig.MAX_SYNC_RETRIES) {
                syncCalculator = ClockSyncCalculator(isFullSync = true)
                pendingPings.clear()

                Log.i(TAG, "Sync attempt $attempt/${ClockSyncConfig.MAX_SYNC_RETRIES}")

                repeat(ClockSyncConfig.FULL_SYNC_SAMPLES) { i ->
                    if (!isActive) return@launch

                    sendPing()
                    // Add 0-10ms random jitter to avoid BLE connection interval aliasing
                    delay(ClockSyncConfig.FULL_SYNC_INTERVAL_MS + (0L..10L).random())

                    // Update progress
                    val sampleProgress = (i + 1).toFloat() / ClockSyncConfig.FULL_SYNC_SAMPLES
                    _state.value = State.Syncing(sampleProgress)
                }

                // Wait for last responses
                delay(300)

                // Calculate result
                val result = syncCalculator?.calculateOffset()
                lastResult = result

                if (result != null && result.isAcceptable()) {
                    _syncResult.value = result
                    _state.value = State.Synced(result)
                    Log.i(TAG, "Sync complete (attempt $attempt): " +
                        "offset=${String.format(Locale.US, "%.2f", result.offsetMs)}ms, " +
                        "uncertainty=${String.format(Locale.US, "%.2f", result.uncertaintyMs)}ms, " +
                        "quality=${result.quality}, " +
                        "samples=${result.samplesUsed}/${result.totalSamples}, " +
                        "minRTT=${String.format(Locale.US, "%.2f", result.minRttMs)}ms, " +
                        "jitter=${String.format(Locale.US, "%.2f", result.jitterMs)}ms")
                    return@launch
                }

                Log.w(TAG, "Sync attempt $attempt failed: ${result?.quality ?: "no result"}, " +
                    "samples=${result?.samplesUsed ?: 0}/${result?.totalSamples ?: 0}")

                if (attempt < ClockSyncConfig.MAX_SYNC_RETRIES) {
                    delay(ClockSyncConfig.RETRY_DELAY_MS)
                }
            }

            _state.value = State.Error(
                "Sync failed after ${ClockSyncConfig.MAX_SYNC_RETRIES} attempts " +
                "(quality: ${lastResult?.quality ?: "none"}, " +
                "uncertainty: ${lastResult?.let { String.format(Locale.US, "%.1f", it.uncertaintyMs) } ?: "?"}ms)"
            )
        }
    }

    /**
     * Perform a mini-sync to refresh the clock offset during an active race.
     * Uses fewer samples and wider RTT tolerance.
     * Can be called periodically (e.g. every 60 seconds).
     */
    @SuppressLint("MissingPermission")
    fun performMiniSync() {
        if (connectedDevice == null || role != Role.Client) {
            Log.w(TAG, "Cannot mini-sync: not connected as client")
            return
        }

        val previousResult = _syncResult.value
        Log.i(TAG, "Starting mini-sync (${ClockSyncConfig.MINI_SYNC_SAMPLES} samples)...")

        syncJob?.cancel()
        syncJob = serviceScope.launch {
            val miniCalc = ClockSyncCalculator(
                isFullSync = false,
                baselineOffsetNanos = previousResult?.offsetNanos
            )
            // Point syncCalculator to miniCalc so pong responses are added to the right calculator
            syncCalculator = miniCalc
            pendingPings.clear()

            repeat(ClockSyncConfig.MINI_SYNC_SAMPLES) { i ->
                if (!isActive) return@launch

                sendPing()
                delay(ClockSyncConfig.MINI_SYNC_INTERVAL_MS + (0L..10L).random())
            }

            delay(200)

            val result = miniCalc.calculateOffset()
            if (result != null && result.isAcceptable()) {
                _syncResult.value = result
                _state.value = State.Synced(result)
                Log.i(TAG, "Mini-sync complete: offset=${String.format(Locale.US, "%.2f", result.offsetMs)}ms, " +
                    "quality=${result.quality}")
            } else {
                // Keep previous result if mini-sync fails
                Log.w(TAG, "Mini-sync failed, keeping previous offset")
                if (previousResult != null) {
                    _syncResult.value = previousResult
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendPing() {
        if (gattClient == null || rxCharacteristic == null) return

        val pingId = UUID.randomUUID().toString().uppercase()
        val t1 = SystemClock.elapsedRealtimeNanos()

        pendingPings[pingId] = t1

        val pingPayload = TimingPayload.SyncPing(
            pingId = pingId,
            t1Nanos = t1,
            requesterId = deviceId
        )
        val message = TimingMessage.create(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = pingPayload
        )
        val messageData = TimingMessageCodec.encodeToBytes(message)

        writeToServer(messageData)

        Log.d(TAG, "Client: Sent syncPing (pingId=${pingId.take(8)})")
    }

    /**
     * Handle an incoming JSON message received via BLE notification (on TX characteristic).
     * Dispatches based on the payload type.
     */
    private fun handleMessageReceived(data: ByteArray) {
        val t4 = SystemClock.elapsedRealtimeNanos()

        val message = try {
            TimingMessageCodec.decodeFromBytes(data)
        } catch (e: Exception) {
            Log.e(TAG, "Client: Failed to decode message (${data.size} bytes): ${e.message}")
            return
        }

        if (!isEnvelopeForLocalDevice(message)) {
            Log.d(TAG, "Client: Ignoring message targeted to ${message.targetDeviceId?.take(8)}")
            return
        }
        handleCriticalResponse(message, connectedDevice?.address)

        when (val payload = message.payload) {
            is TimingPayload.SyncPong -> {
                val expectedT1 = pendingPings.remove(payload.pingId)
                if (expectedT1 == null) {
                    Log.w(TAG, "Client: Unexpected pong (pingId=${payload.pingId.take(8)})")
                    return
                }
                if (expectedT1 != payload.t1Nanos) {
                    Log.w(TAG, "Client: Mismatched t1 in pong (pingId=${payload.pingId.take(8)})")
                    return
                }

                val sample = ClockSyncCalculator.SyncSample(
                    payload.t1Nanos, payload.t2Nanos, payload.t3Nanos, t4
                )
                val accepted = syncCalculator?.addSample(sample) ?: false

                Log.d(TAG, "Client: Received syncPong (pingId=${payload.pingId.take(8)}), " +
                    "RTT=${String.format(Locale.US, "%.2f", sample.rtt / 1_000_000.0)}ms, accepted=$accepted")
            }
            else -> {
                // Auto-ACK critical messages before forwarding
                if (message.requiresAck && message.messageId != null) {
                    Log.d(TAG, "Client: Auto-ACK for ${payload::class.simpleName} (msgId=${message.messageId.take(8)})")
                    sendMessage(TimingPayload.Ack(messageId = message.messageId))
                }
                // Forward all other messages to the app via incomingMessages flow
                Log.d(TAG, "Client: Forwarding ${payload::class.simpleName} to app")
                _incomingMessages.tryEmit(message)
            }
        }
    }

    private fun handleCloudMessageReceived(message: TimingMessage) {
        if (!message.sessionId.equals(sessionId, ignoreCase = true)) return
        if (!isEnvelopeForLocalDevice(message)) return
        when (message.payload) {
            is TimingPayload.SyncPing, is TimingPayload.SyncPong -> {
                // Internet RTT is not a valid substitute for BLE clock sync.
                return
            }
            else -> Unit
        }
        handleCriticalResponse(message, senderDeviceMap[message.senderId])
        if (message.requiresAck && message.messageId != null) {
            val senderAddress = senderDeviceMap[message.senderId]
            if (role == Role.Server && senderAddress != null) {
                sendMessageToDevice(
                    payload = TimingPayload.Ack(messageId = message.messageId),
                    deviceAddress = senderAddress,
                    targetDeviceId = message.senderId
                )
            } else {
                sendMessage(
                    payload = TimingPayload.Ack(messageId = message.messageId),
                    targetDeviceId = message.senderId
                )
            }
        }
        _incomingMessages.tryEmit(message)
    }

    private fun isEnvelopeForLocalDevice(message: TimingMessage): Boolean {
        val target = message.targetDeviceId ?: return true
        return target.equals(deviceId, ignoreCase = true)
    }

    private fun handleCriticalResponse(message: TimingMessage, senderAddress: String?) {
        when (val payload = message.payload) {
            is TimingPayload.Ack -> {
                val senderKeys = buildSet {
                    add(CriticalMessageRetryTracker.deviceIdKey(message.senderId))
                    senderAddress?.let {
                        add(CriticalMessageRetryTracker.addressKey(it))
                    }
                }
                if (criticalMessageRetries.acknowledge(payload.messageId, senderKeys)) {
                    Log.d(TAG, "Critical message ${payload.messageId.take(8)} acknowledged")
                }
            }
            is TimingPayload.Nack -> {
                criticalMessageRetries.reject(payload.messageId)?.let { rejected ->
                    Log.w(
                        TAG,
                        "Critical message ${rejected.messageId?.take(8)} rejected: ${payload.reason}"
                    )
                }
            }
            else -> Unit
        }
    }

    // ==================== Client Write Queue ====================

    /**
     * Enqueue data for writing to the server (client mode only).
     * BLE allows only one outstanding write at a time.
     */
    private fun writeToServer(data: ByteArray) {
        val packets = try {
            BleMessageFramer.packets(data, clientMaximumPacketBytes)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Client: Failed to frame BLE payload: ${e.message}")
            return
        }
        clientWriteQueue.addAll(packets)
        drainClientWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainClientWriteQueue() {
        if (isClientWritePending.compareAndSet(false, true)) {
            val data = clientWriteQueue.poll()
            if (data != null) {
                val client = gattClient
                val char = rxCharacteristic
                if (client == null || char == null) {
                    isClientWritePending.set(false)
                    return
                }
                clientWriteInFlight = data
                char.value = data
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (!client.writeCharacteristic(char)) {
                    clientWriteInFlight = null
                    clientWriteQueue.addFirst(data)
                    isClientWritePending.set(false)
                    handler.postDelayed({ drainClientWriteQueue() }, 25L)
                }
            } else {
                isClientWritePending.set(false)
            }
        }
    }

    // ==================== Cleanup ====================

    @SuppressLint("MissingPermission")
    private fun closeGattServer() {
        acceptingServerConnections.set(false)
        gattServer?.close()
        gattServer = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGattClient() {
        gattClient?.close()
        gattClient = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        closeGattServer()
        closeGattClient()

        txCharacteristic = null
        rxCharacteristic = null
    }

    // ==================== Session Identity ====================

    /**
     * Adopt a TimingMessage envelope sessionId from a peer (typically the host
     * during handshake) and reset any in-flight clock-sync state that was
     * stamped with the previous sessionId.
     *
     * Mirrors iOS `ClockSyncService.setSessionId(_:)` — the iOS app calls this
     * when a joiner adopts the host's session UUID so subsequent ping/pong
     * messages carry the correct envelope sessionId. No-op if unchanged.
     */
    fun setSessionId(newSessionId: String) {
        val normalized = newSessionId.uppercase()
        if (sessionId == normalized) return
        Log.i(TAG, "Adopting sessionId ${normalized.take(8)}… (was ${sessionId.take(8)}…)")
        sessionId = normalized
        pendingPings.clear()
        criticalMessageRetries.clear()
        // Bump seq so any in-flight retries from the old session don't collide
        // with new messages on the receiver. Matches iOS reset to 10000.
        sequenceNumber.set(10_000L)
        if (role == Role.Client) {
            cloudTimingRelay.start(sessionId, deviceId, isHost = false)
        }
    }

    /**
     * Send a non-critical TimingMessage to all connected devices (broadcast).
     */
    @SuppressLint("MissingPermission")
    fun sendMessage(
        payload: TimingPayload,
        eventId: String? = null,
        targetDeviceId: String? = null,
        runId: String? = null
    ): Boolean {
        if (connectedDevices.isEmpty() && gattClient == null && !cloudTimingRelay.isAvailable) {
            Log.w(TAG, "Cannot send message: not connected")
            return false
        }

        val message = TimingMessage.create(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = payload,
            eventId = eventId,
            targetDeviceId = targetDeviceId,
            runId = runId
        )
        return sendRawMessage(message)
    }

    /**
     * Send a non-critical TimingMessage to a specific device (server mode only).
     */
    @SuppressLint("MissingPermission")
    fun sendMessageToDevice(
        payload: TimingPayload,
        deviceAddress: String,
        eventId: String? = null,
        targetDeviceId: String? = null,
        runId: String? = null
    ): Boolean {
        val device = connectedDevices[deviceAddress] ?: run {
            Log.w(TAG, "Cannot send to $deviceAddress: not connected")
            return false
        }

        val message = TimingMessage.create(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = payload,
            eventId = eventId,
            targetDeviceId = targetDeviceId,
            runId = runId
        )
        return sendRawMessageToDevice(message, device)
    }

    /**
     * Send a critical TimingMessage to all connected devices (broadcast).
     * Used for handshake messages like SessionConfig, RoleAssigned, GateAssigned.
     */
    @SuppressLint("MissingPermission")
    fun sendCriticalMessage(
        payload: TimingPayload,
        eventId: String? = null,
        targetDeviceId: String? = null,
        runId: String? = null
    ): Boolean {
        val message = TimingMessage.createCritical(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = payload,
            eventId = eventId,
            targetDeviceId = targetDeviceId,
            runId = runId
        )
        val sent = sendRawMessage(message)
        trackCriticalMessage(message)
        if (!sent) {
            Log.w(TAG, "Critical message has no live path; retained for retry")
        }
        return sent
    }

    /**
     * Send a critical TimingMessage to a specific device (server mode only).
     */
    @SuppressLint("MissingPermission")
    fun sendCriticalMessageToDevice(
        payload: TimingPayload,
        deviceAddress: String,
        eventId: String? = null,
        targetDeviceId: String? = null,
        runId: String? = null
    ): Boolean {
        val device = connectedDevices[deviceAddress]

        val message = TimingMessage.createCritical(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = payload,
            eventId = eventId,
            targetDeviceId = targetDeviceId,
            runId = runId
        )
        val sent = sendRawMessageToDevice(message, device)
        trackCriticalMessage(message, targetDeviceAddress = deviceAddress)
        if (!sent) {
            Log.w(TAG, "Critical message to $deviceAddress has no live path; retained for retry")
        }
        return sent
    }

    private fun trackCriticalMessage(
        message: TimingMessage,
        targetDeviceAddress: String? = null
    ) {
        if (!message.requiresAck) return
        val acknowledgementKeys = when {
            targetDeviceAddress != null -> buildSet {
                add(CriticalMessageRetryTracker.addressKey(targetDeviceAddress))
                message.targetDeviceId?.let {
                    add(CriticalMessageRetryTracker.deviceIdKey(it))
                }
            }
            message.targetDeviceId != null -> buildSet {
                add(CriticalMessageRetryTracker.deviceIdKey(message.targetDeviceId))
                senderDeviceMap[message.targetDeviceId]?.let {
                    add(CriticalMessageRetryTracker.addressKey(it))
                }
            }
            role == Role.Server && connectedDevices.isNotEmpty() ->
                connectedDevices.keys
                    .mapTo(linkedSetOf(), CriticalMessageRetryTracker::addressKey)
            else -> emptySet()
        }
        criticalMessageRetries.track(
            message = message,
            target = CriticalMessageRetryTracker.RetryTarget(
                deviceAddress = targetDeviceAddress,
                acknowledgementKeys = acknowledgementKeys
            )
        )
    }

    private fun processCriticalRetryAction(action: CriticalMessageRetryTracker.Action) {
        when (action) {
            is CriticalMessageRetryTracker.Action.Retry -> {
                val targetAddress = action.target.deviceAddress
                val sent = if (targetAddress != null) {
                    sendRawMessageToDevice(action.message, connectedDevices[targetAddress])
                } else {
                    sendRawMessage(action.message)
                }
                Log.i(
                    TAG,
                    "Retrying critical ${action.message.messageId?.take(8)} " +
                        "(${action.attempt}/${action.maximumAttempts}, queued=$sent)"
                )
            }
            is CriticalMessageRetryTracker.Action.Failed -> {
                Log.e(
                    TAG,
                    "Critical message ${action.message.messageId?.take(8)} failed after " +
                        "${CriticalMessageRetryTracker.DEFAULT_MAXIMUM_RETRIES} retries"
                )
            }
        }
    }

    /**
     * Send a message to all connected devices (broadcast in server mode).
     */
    @SuppressLint("MissingPermission")
    private fun sendRawMessage(message: TimingMessage): Boolean {
        val messageData = TimingMessageCodec.encodeToBytes(message)
        val payloadName = message.payload::class.simpleName
        val criticalTag = if (message.messageId != null) " [CRITICAL msgId=${message.messageId.take(8)}]" else ""

        val cloudQueued = when (message.payload) {
            is TimingPayload.SyncPing, is TimingPayload.SyncPong -> false
            else -> cloudTimingRelay.send(message)
        }
        val bleQueued = when (role) {
            Role.Server -> {
                val devices = connectedDevices.values.toList()
                if (devices.isEmpty()) {
                    false
                } else {
                    val queued = devices.map { enqueueServerPayload(it, messageData) }.all { it }
                    Log.d(
                        TAG,
                        "Server: Queued $payloadName for ${devices.size} device(s) " +
                            "(${messageData.size}B)$criticalTag"
                    )
                    queued
                }
            }
            Role.Client -> {
                if (gattClient == null) {
                    false
                } else {
                    writeToServer(messageData)
                    Log.d(TAG, "Client: Queued $payloadName (${messageData.size}B)$criticalTag")
                    true
                }
            }
            null -> false
        }
        return bleQueued || cloudQueued
    }

    /**
     * Send a message to a specific connected device (server mode only).
     */
    @SuppressLint("MissingPermission")
    private fun sendRawMessageToDevice(message: TimingMessage, device: BluetoothDevice?): Boolean {
        val messageData = TimingMessageCodec.encodeToBytes(message)
        val payloadName = message.payload::class.simpleName
        val criticalTag = if (message.messageId != null) " [CRITICAL msgId=${message.messageId.take(8)}]" else ""

        val queued = device?.let { enqueueServerPayload(it, messageData) } ?: false
        val cloudQueued = when (message.payload) {
            is TimingPayload.SyncPing, is TimingPayload.SyncPong -> false
            else -> cloudTimingRelay.send(message)
        }
        if (queued) {
            Log.d(TAG, "Server: Queued $payloadName to ${device?.address} (${messageData.size}B)$criticalTag")
        }
        return queued || cloudQueued
    }

    /**
     * Get the current sync offset in nanoseconds.
     * Returns 0 if not synced.
     */
    fun getOffsetNanos(): Long = _syncResult.value?.offsetNanos ?: 0L

    /**
     * Convert a local timestamp to remote timestamp.
     */
    fun toRemoteTime(localNanos: Long): Long {
        return localNanos + getOffsetNanos()
    }

    /**
     * Convert a remote timestamp to local timestamp.
     */
    fun toLocalTime(remoteNanos: Long): Long {
        return remoteNanos - getOffsetNanos()
    }
}
