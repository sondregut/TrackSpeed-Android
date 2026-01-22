package com.trackspeed.android.sync

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE-based clock synchronization service.
 *
 * Implements NTP-style ping-pong over BLE GATT for cross-platform
 * clock synchronization between Android and iOS devices.
 *
 * Uses compact binary format for low latency:
 * - PING: 10 bytes (type + seq + t1)
 * - PONG: 26 bytes (type + seq + t1 + t2 + t3)
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
        object Scanning : State()
        object Connecting : State()
        object Connected : State()
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
    private var connectedDevice: BluetoothDevice? = null

    // Advertising
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = AtomicBoolean(false)

    // Scanning
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = AtomicBoolean(false)

    // Sync state
    private var role: Role? = null
    private var syncCalculator: ClockSyncCalculator? = null
    private val sequenceNumber = AtomicInteger(0)
    private val pendingPings = mutableMapOf<Int, Long>()  // seqNo -> t1
    private var syncJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    // Characteristics
    private var pingCharacteristic: BluetoothGattCharacteristic? = null
    private var pongCharacteristic: BluetoothGattCharacteristic? = null

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
        connectedDevice = null

        _state.value = State.Idle
    }

    // ==================== Server Mode ====================

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)?.apply {
            // Create service
            val service = BluetoothGattService(
                ClockSyncConfig.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // Ping characteristic (client writes here)
            pingCharacteristic = BluetoothGattCharacteristic(
                ClockSyncConfig.PING_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(pingCharacteristic)

            // Pong characteristic (server writes/indicates here)
            pongCharacteristic = BluetoothGattCharacteristic(
                ClockSyncConfig.PONG_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE,
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
            service.addCharacteristic(pongCharacteristic)

            addService(service)
            Log.i(TAG, "GATT server started with clock sync service")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Server: Client connected: ${device.address}")
                    connectedDevice = device
                    _state.value = State.Connected
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Server: Client disconnected")
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
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
            if (characteristic.uuid == ClockSyncConfig.PING_CHARACTERISTIC_UUID) {
                // Record receive time immediately
                val t2 = SystemClock.elapsedRealtimeNanos()

                // Parse ping message
                val (seqNo, t1) = parsePingMessage(value)
                Log.d(TAG, "Server: Received ping #$seqNo")

                // Send response first
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }

                // Record send time and create pong
                val t3 = SystemClock.elapsedRealtimeNanos()
                val pongData = createPongMessage(seqNo, t1, t2, t3)

                // Send pong via notification
                pongCharacteristic?.let { char ->
                    char.value = pongData
                    gattServer?.notifyCharacteristicChanged(device, char, false)
                    Log.d(TAG, "Server: Sent pong #$seqNo")
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
            Log.d(TAG, "Server: Notifications enabled for ${descriptor.characteristic.uuid}")
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
        if (isAdvertising.getAndSet(false)) {
            advertiser?.stopAdvertising(advertiseCallback)
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
                    connectedDevice = gatt.device
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Client: Disconnected from server")
                    connectedDevice = null
                    _state.value = State.Error("Disconnected from server")
                }
            }
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

            pingCharacteristic = service.getCharacteristic(ClockSyncConfig.PING_CHARACTERISTIC_UUID)
            pongCharacteristic = service.getCharacteristic(ClockSyncConfig.PONG_CHARACTERISTIC_UUID)

            if (pingCharacteristic == null || pongCharacteristic == null) {
                _state.value = State.Error("Required characteristics not found")
                return
            }

            // Enable notifications on pong characteristic
            gatt.setCharacteristicNotification(pongCharacteristic, true)
            val descriptor = pongCharacteristic?.getDescriptor(
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
                Log.d(TAG, "Client: Notifications enabled")
                // Start sync after notifications are enabled
                startSync()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == ClockSyncConfig.PONG_CHARACTERISTIC_UUID) {
                handlePongReceived(characteristic.value)
            }
        }

        // For Android 13+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == ClockSyncConfig.PONG_CHARACTERISTIC_UUID) {
                handlePongReceived(value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSync() {
        syncCalculator = ClockSyncCalculator(isFullSync = true)
        pendingPings.clear()
        sequenceNumber.set(0)

        _state.value = State.Syncing(0f)
        Log.i(TAG, "Starting clock synchronization...")

        syncJob = CoroutineScope(Dispatchers.IO).launch {
            repeat(ClockSyncConfig.FULL_SYNC_SAMPLES) { i ->
                if (!isActive) return@launch

                sendPing()
                delay(ClockSyncConfig.FULL_SYNC_INTERVAL_MS)

                // Update progress
                val progress = (i + 1).toFloat() / ClockSyncConfig.FULL_SYNC_SAMPLES
                _state.value = State.Syncing(progress)
            }

            // Wait a bit for last responses
            delay(200)

            // Calculate result
            val result = syncCalculator?.calculateOffset()
            if (result != null && result.isAcceptable()) {
                _syncResult.value = result
                _state.value = State.Synced(result)
                Log.i(TAG, "Sync complete: offset=${result.offsetMs}ms, quality=${result.quality}")
            } else {
                _state.value = State.Error("Sync failed: insufficient quality")
                Log.e(TAG, "Sync failed: ${result?.quality ?: "no result"}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendPing() {
        val client = gattClient ?: return
        val char = pingCharacteristic ?: return

        val seqNo = sequenceNumber.incrementAndGet() and 0xFF
        val t1 = SystemClock.elapsedRealtimeNanos()

        pendingPings[seqNo] = t1
        char.value = createPingMessage(seqNo, t1)
        client.writeCharacteristic(char)

        Log.d(TAG, "Client: Sent ping #$seqNo")
    }

    private fun handlePongReceived(data: ByteArray) {
        val t4 = SystemClock.elapsedRealtimeNanos()
        val (seqNo, t1, t2, t3) = parsePongMessage(data)

        val expectedT1 = pendingPings.remove(seqNo)
        if (expectedT1 == null || expectedT1 != t1) {
            Log.w(TAG, "Unexpected or mismatched pong #$seqNo")
            return
        }

        val sample = ClockSyncCalculator.SyncSample(t1, t2, t3, t4)
        val accepted = syncCalculator?.addSample(sample) ?: false

        Log.d(TAG, "Client: Received pong #$seqNo, RTT=${sample.rtt / 1_000_000.0}ms, accepted=$accepted")
    }

    // ==================== Binary Message Format ====================

    /**
     * Create ping message: [type:1B][seqNo:1B][t1:8B]
     */
    private fun createPingMessage(seqNo: Int, t1: Long): ByteArray {
        return ByteBuffer.allocate(ClockSyncConfig.PING_MESSAGE_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(ClockSyncConfig.MSG_TYPE_PING)
            .put(seqNo.toByte())
            .putLong(t1)
            .array()
    }

    /**
     * Parse ping message: returns (seqNo, t1)
     */
    private fun parsePingMessage(data: ByteArray): Pair<Int, Long> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val type = buffer.get()
        val seqNo = buffer.get().toInt() and 0xFF
        val t1 = buffer.getLong()
        return Pair(seqNo, t1)
    }

    /**
     * Create pong message: [type:1B][seqNo:1B][t1:8B][t2:8B][t3:8B]
     */
    private fun createPongMessage(seqNo: Int, t1: Long, t2: Long, t3: Long): ByteArray {
        return ByteBuffer.allocate(ClockSyncConfig.PONG_MESSAGE_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(ClockSyncConfig.MSG_TYPE_PONG)
            .put(seqNo.toByte())
            .putLong(t1)
            .putLong(t2)
            .putLong(t3)
            .array()
    }

    /**
     * Parse pong message: returns (seqNo, t1, t2, t3)
     */
    private fun parsePongMessage(data: ByteArray): PongData {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val type = buffer.get()
        val seqNo = buffer.get().toInt() and 0xFF
        val t1 = buffer.getLong()
        val t2 = buffer.getLong()
        val t3 = buffer.getLong()
        return PongData(seqNo, t1, t2, t3)
    }

    private data class PongData(val seqNo: Int, val t1: Long, val t2: Long, val t3: Long)

    // ==================== Cleanup ====================

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gattServer?.close()
        gattServer = null

        gattClient?.close()
        gattClient = null

        pingCharacteristic = null
        pongCharacteristic = null
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
