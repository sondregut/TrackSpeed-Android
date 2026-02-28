package com.trackspeed.android.sync

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingMessageCodec
import com.trackspeed.android.protocol.TimingPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
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
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BleClockSync"
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
    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null
    // Multi-client: map of device address -> BluetoothDevice
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()
    // Legacy single-device accessor (for client mode or first server connection)
    private val connectedDevice: BluetoothDevice?
        get() = connectedDevices.values.firstOrNull()

    // Advertising
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = AtomicBoolean(false)

    // Scanning
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = AtomicBoolean(false)

    // Per-device notification readiness: tracks which clients have enabled CCC notifications
    private val clientNotificationsReady = mutableSetOf<String>()

    // Connection events flow for ClockSyncManager to track new clients
    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    // Per-client notification readiness events (emitted when a client writes CCC descriptor)
    private val _clientReadyDevices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val clientReadyDevices: SharedFlow<String> = _clientReadyDevices.asSharedFlow()

    // Map from TimingMessage senderId → BLE device address (populated on server receiving writes)
    private val senderDeviceMap = mutableMapOf<String, String>()

    data class ConnectionEvent(val device: BluetoothDevice, val connected: Boolean)

    // Sync state
    private var role: Role? = null
    private var syncCalculator: ClockSyncCalculator? = null
    private val sequenceNumber = AtomicLong(0)
    private val pendingPings = mutableMapOf<String, Long>()  // pingId (UUID) -> t1 nanos
    private var syncJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    // Client-side write queue: BLE allows only one write at a time
    private val clientWriteQueue = ConcurrentLinkedQueue<ByteArray>()
    private val isClientWritePending = AtomicBoolean(false)

    // General message receiving (for non-sync messages like crossing events)
    private val _incomingMessages = MutableSharedFlow<TimingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<TimingMessage> = _incomingMessages.asSharedFlow()

    // Device and session identity (stable across app runs)
    private val deviceId: String by lazy { getOrCreateDeviceId() }
    private var sessionId: String = UUID.randomUUID().toString().uppercase()

    // Characteristics - names match iOS BluetoothTransport:
    //   TX = host->joiner (NOTIFY+READ), RX = joiner->host (WRITE+WRITE_WITHOUT_RESPONSE)
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * Start as server (advertises, responds to pings).
     * Use this on the device that should be the "reference" clock.
     */
    @SuppressLint("MissingPermission")
    fun startAsServer() {
        if (bluetoothAdapter?.isEnabled != true) {
            _state.value = State.Error("Bluetooth is not enabled")
            return
        }

        // Stop any previous state to avoid ADVERTISE_FAILED_ALREADY_STARTED (error 3)
        stopAdvertising()
        closeGatt()

        role = Role.Server
        _state.value = State.Idle

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
        if (bluetoothAdapter?.isEnabled != true) {
            _state.value = State.Error("Bluetooth is not enabled")
            return
        }

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
        if (bluetoothAdapter?.isEnabled != true) {
            _state.value = State.Error("Bluetooth is not enabled")
            return
        }

        // Stop any previous state
        stopAdvertising()
        stopScanning()
        closeGatt()

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

    /**
     * Get the resolved role after dual-mode pairing completes.
     */
    fun getResolvedRole(): Role? = role

    /**
     * Look up the BLE device address for a given TimingMessage senderId.
     * Only populated in server mode when messages are received from clients.
     */
    fun getDeviceAddress(senderId: String): String? = senderDeviceMap[senderId]

    /**
     * Stop all BLE operations.
     */
    @SuppressLint("MissingPermission")
    fun stop() {
        syncJob?.cancel()
        syncJob = null

        stopAdvertising()
        stopScanning()
        closeGatt()

        role = null
        syncCalculator = null
        pendingPings.clear()
        clientWriteQueue.clear()
        isClientWritePending.set(false)
        clientNotificationsReady.clear()
        connectedDevices.clear()
        senderDeviceMap.clear()
        sessionId = UUID.randomUUID().toString().uppercase()

        _state.value = State.Idle
    }

    // ==================== Server Mode ====================

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)?.apply {
            // Create service with UUIDs matching iOS BluetoothTransport
            val service = BluetoothGattService(
                ClockSyncConfig.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // TX characteristic (host -> joiner): NOTIFY + READ
            // iOS: properties: [.notify, .read], permissions: [.readable]
            txCharacteristic = BluetoothGattCharacteristic(
                ClockSyncConfig.PING_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                // Add CCC descriptor for notifications
                val descriptor = BluetoothGattDescriptor(
                    java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                    BluetoothGattDescriptor.PERMISSION_WRITE or
                            BluetoothGattDescriptor.PERMISSION_READ
                )
                addDescriptor(descriptor)
            }
            service.addCharacteristic(txCharacteristic)

            // RX characteristic (joiner -> host): WRITE + WRITE_WITHOUT_RESPONSE
            // iOS: properties: [.write, .writeWithoutResponse], permissions: [.writeable]
            rxCharacteristic = BluetoothGattCharacteristic(
                ClockSyncConfig.PONG_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(rxCharacteristic)

            addService(service)
            Log.i(TAG, "GATT server started with clock sync service (JSON protocol v3)")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Server: Client connected: ${device.address} (total: ${connectedDevices.size + 1})")
                    connectedDevices[device.address] = device
                    // In dual-mode: stop scanning since we've been chosen as server
                    if (role == null) {
                        stopScanning()
                        role = Role.Server
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
            if (characteristic.uuid == ClockSyncConfig.PONG_CHARACTERISTIC_UUID) {
                // Record T2 (receive time) immediately on arrival
                val t2 = SystemClock.elapsedRealtimeNanos()

                // Ack the write request first for minimum latency
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }

                // Parse the incoming JSON TimingMessage
                val message = try {
                    TimingMessageCodec.decodeFromBytes(value)
                } catch (e: Exception) {
                    Log.e(TAG, "Server: Failed to decode message: ${e.message}")
                    return
                }

                // Track senderId → BLE device address for per-client routing
                senderDeviceMap[message.senderId] = device.address

                // Handle message based on type
                val payload = message.payload
                when (payload) {
                    is TimingPayload.SyncPing -> {
                        // Record T3 (send time) right before creating pong
                        val t3 = SystemClock.elapsedRealtimeNanos()

                        val pongPayload = TimingPayload.SyncPong(
                            pingId = payload.pingId,
                            t1Nanos = payload.t1Nanos,
                            t2Nanos = t2,
                            t3Nanos = t3,
                            requesterId = payload.requesterId
                        )
                        val pongMessage = TimingMessage.create(
                            seq = sequenceNumber.incrementAndGet(),
                            senderId = deviceId,
                            sessionId = sessionId,
                            payload = pongPayload
                        )
                        val pongData = TimingMessageCodec.encodeToBytes(pongMessage)

                        // Send pong via notification on TX characteristic
                        txCharacteristic?.let { char ->
                            char.value = pongData
                            gattServer?.notifyCharacteristicChanged(device, char, false)
                        }

                        Log.d(TAG, "Server: Responded to syncPing (pingId=${payload.pingId.take(8)})")
                    }
                    else -> {
                        // Auto-ACK critical messages before forwarding
                        if (message.requiresAck && message.messageId != null) {
                            Log.d(TAG, "Server: Auto-ACK for ${payload::class.simpleName} (msgId=${message.messageId.take(8)})")
                            sendMessage(TimingPayload.Ack(messageId = message.messageId))
                        }
                        // Forward all other messages to the app via incomingMessages flow
                        Log.d(TAG, "Server: Forwarding ${payload::class.simpleName} to app")
                        _incomingMessages.tryEmit(message)
                    }
                }
            }
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
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(ClockSyncConfig.SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
        Log.i(TAG, "Started advertising clock sync service")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising.set(true)
            Log.i(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising.set(false)
            _state.value = State.Error("Advertising failed: $errorCode")
            Log.e(TAG, "Advertising failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val wasAdvertising = isAdvertising.getAndSet(false)
        try {
            advertiser?.stopAdvertising(advertiseCallback)
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

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(ClockSyncConfig.SERVICE_UUID))
                .build()
        )

        scanner?.startScan(filters, settings, scanCallback)
        isScanning.set(true)
        Log.i(TAG, "Started scanning for clock sync servers")

        // Timeout after 30 seconds
        handler.postDelayed({
            if (isScanning.get() && _state.value is State.Scanning) {
                stopScanning()
                _state.value = State.Error("No server found")
            }
        }, 30_000L)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isScanning.get()) return

            Log.i(TAG, "Found clock sync server: ${result.device.address}")
            stopScanning()

            // In dual-mode: stop advertising and close GATT server since we're becoming client
            if (role == null) {
                stopAdvertising()
                closeGattServer()
                role = Role.Client
                Log.i(TAG, "Dual-mode resolved: this device is Client (found another server)")
            }

            connectToServer(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning.set(false)
            _state.value = State.Error("Scan failed: $errorCode")
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (isScanning.getAndSet(false)) {
            scanner?.stopScan(scanCallback)
            Log.i(TAG, "Stopped scanning")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToServer(device: BluetoothDevice) {
        _state.value = State.Connecting
        Log.i(TAG, "Connecting to server: ${device.address}")

        gattClient = device.connectGatt(
            context,
            false,
            gattClientCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
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
                    _state.value = State.Error("Disconnected from server")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "Client: MTU changed to $mtu (status=$status)")
            // Proceed with service discovery regardless of MTU result
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = State.Error("Service discovery failed")
                return
            }

            val service = gatt.getService(ClockSyncConfig.SERVICE_UUID)
            if (service == null) {
                _state.value = State.Error("Clock sync service not found")
                return
            }

            // TX characteristic (host -> joiner): we subscribe to notifications on this
            // RX characteristic (joiner -> host): we write ping messages to this
            txCharacteristic = service.getCharacteristic(ClockSyncConfig.PING_CHARACTERISTIC_UUID)
            rxCharacteristic = service.getCharacteristic(ClockSyncConfig.PONG_CHARACTERISTIC_UUID)

            if (txCharacteristic == null || rxCharacteristic == null) {
                _state.value = State.Error("Required characteristics not found")
                return
            }

            // Enable notifications on TX characteristic (host -> joiner)
            // This is where we receive pong responses
            gatt.setCharacteristicNotification(txCharacteristic, true)
            val descriptor = txCharacteristic?.getDescriptor(
                java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }

            Log.i(TAG, "Client: Services discovered, ready for sync")
            _state.value = State.Connected
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Client: Notifications enabled, ready for protocol handshake")
                // Don't start sync here — let ClockSyncManager drive the
                // handshake protocol. Sync starts after handshake completes.
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isClientWritePending.set(false)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Client: Write failed: status=$status")
            }
            // Drain the next queued write
            drainClientWriteQueue()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == ClockSyncConfig.PING_CHARACTERISTIC_UUID) {
                handleMessageReceived(characteristic.value)
            }
        }

        // For Android 13+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == ClockSyncConfig.PING_CHARACTERISTIC_UUID) {
                handleMessageReceived(value)
            }
        }
    }

    /**
     * Start the NTP clock sync process. Called by ClockSyncManager after
     * the protocol handshake completes (not immediately on BLE connection).
     */
    @SuppressLint("MissingPermission")
    fun startSync() {
        _state.value = State.Syncing(0f)
        Log.i(TAG, "Starting clock synchronization (${ClockSyncConfig.FULL_SYNC_SAMPLES} samples, JSON protocol)...")

        syncJob = CoroutineScope(Dispatchers.IO).launch {
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
                        "offset=${String.format("%.2f", result.offsetMs)}ms, " +
                        "uncertainty=${String.format("%.2f", result.uncertaintyMs)}ms, " +
                        "quality=${result.quality}, " +
                        "samples=${result.samplesUsed}/${result.totalSamples}, " +
                        "minRTT=${String.format("%.2f", result.minRttMs)}ms, " +
                        "jitter=${String.format("%.2f", result.jitterMs)}ms")
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
                "uncertainty: ${lastResult?.let { String.format("%.1f", it.uncertaintyMs) } ?: "?"}ms)"
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
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            val miniCalc = ClockSyncCalculator(isFullSync = false)
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
                Log.i(TAG, "Mini-sync complete: offset=${String.format("%.2f", result.offsetMs)}ms, " +
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
                    "RTT=${String.format("%.2f", sample.rtt / 1_000_000.0)}ms, accepted=$accepted")
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

    // ==================== Client Write Queue ====================

    /**
     * Enqueue data for writing to the server (client mode only).
     * BLE allows only one outstanding write at a time.
     */
    private fun writeToServer(data: ByteArray) {
        clientWriteQueue.add(data)
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
                char.value = data
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                client.writeCharacteristic(char)
            } else {
                isClientWritePending.set(false)
            }
        }
    }

    // ==================== Cleanup ====================

    @SuppressLint("MissingPermission")
    private fun closeGattServer() {
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

    // ==================== Device Identity ====================

    /**
     * Get or create a stable device ID persisted in SharedPreferences.
     * Matches iOS BluetoothTransport.localDeviceId pattern.
     */
    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences("trackspeed_ble", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: run {
            val id = UUID.randomUUID().toString().uppercase()
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }

    /**
     * Send a non-critical TimingMessage to all connected devices (broadcast).
     */
    @SuppressLint("MissingPermission")
    fun sendMessage(payload: TimingPayload): Boolean {
        if (connectedDevices.isEmpty() && gattClient == null) {
            Log.w(TAG, "Cannot send message: not connected")
            return false
        }

        val message = TimingMessage.create(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = payload
        )
        return sendRawMessage(message)
    }

    /**
     * Send a non-critical TimingMessage to a specific device (server mode only).
     */
    @SuppressLint("MissingPermission")
    fun sendMessageToDevice(payload: TimingPayload, deviceAddress: String): Boolean {
        val device = connectedDevices[deviceAddress] ?: run {
            Log.w(TAG, "Cannot send to $deviceAddress: not connected")
            return false
        }

        val message = TimingMessage.create(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = payload
        )
        return sendRawMessageToDevice(message, device)
    }

    /**
     * Send a critical TimingMessage to all connected devices (broadcast).
     * Used for handshake messages like SessionConfig, RoleAssigned, GateAssigned.
     */
    @SuppressLint("MissingPermission")
    fun sendCriticalMessage(payload: TimingPayload): Boolean {
        if (connectedDevices.isEmpty() && gattClient == null) {
            Log.w(TAG, "Cannot send critical message: not connected")
            return false
        }

        val message = TimingMessage.createCritical(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = payload
        )
        return sendRawMessage(message)
    }

    /**
     * Send a critical TimingMessage to a specific device (server mode only).
     */
    @SuppressLint("MissingPermission")
    fun sendCriticalMessageToDevice(payload: TimingPayload, deviceAddress: String): Boolean {
        val device = connectedDevices[deviceAddress] ?: run {
            Log.w(TAG, "Cannot send critical to $deviceAddress: not connected")
            return false
        }

        val message = TimingMessage.createCritical(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = payload
        )
        return sendRawMessageToDevice(message, device)
    }

    /**
     * Send a message to all connected devices (broadcast in server mode).
     */
    @SuppressLint("MissingPermission")
    private fun sendRawMessage(message: TimingMessage): Boolean {
        val messageData = TimingMessageCodec.encodeToBytes(message)
        val payloadName = message.payload::class.simpleName
        val criticalTag = if (message.messageId != null) " [CRITICAL msgId=${message.messageId.take(8)}]" else ""

        return when (role) {
            Role.Server -> {
                val devices = connectedDevices.values.toList()
                if (devices.isEmpty()) return false
                txCharacteristic?.let { char ->
                    char.value = messageData
                    for (device in devices) {
                        gattServer?.notifyCharacteristicChanged(device, char, false)
                    }
                    Log.d(TAG, "Server: Broadcast $payloadName to ${devices.size} device(s) (${messageData.size}B)$criticalTag")
                    true
                } ?: false
            }
            Role.Client -> {
                writeToServer(messageData)
                Log.d(TAG, "Client: Queued $payloadName (${messageData.size}B)$criticalTag")
                true
            }
            null -> false
        }
    }

    /**
     * Send a message to a specific connected device (server mode only).
     */
    @SuppressLint("MissingPermission")
    private fun sendRawMessageToDevice(message: TimingMessage, device: BluetoothDevice): Boolean {
        val messageData = TimingMessageCodec.encodeToBytes(message)
        val payloadName = message.payload::class.simpleName
        val criticalTag = if (message.messageId != null) " [CRITICAL msgId=${message.messageId.take(8)}]" else ""

        return txCharacteristic?.let { char ->
            char.value = messageData
            gattServer?.notifyCharacteristicChanged(device, char, false)
            Log.d(TAG, "Server: Sent $payloadName to ${device.address} (${messageData.size}B)$criticalTag")
            true
        } ?: false
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
