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
import com.trackspeed.android.diagnostics.*
import com.trackspeed.android.protocol.ClockSyncTimestampTemplate
import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingMessageCodec
import com.trackspeed.android.protocol.TimingBootstrapPolicy
import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.protocol.TimingProtocolCompatibility
import com.trackspeed.android.protocol.TimingProtocolSequence
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private val deviceIdProvider: DeviceIdProvider,
    private val connectionDiagnostics: ConnectionDiagnosticsService
) {
    companion object {
        private const val TAG = "BleClockSync"
        private const val SCAN_REFRESH_TIMEOUT_MS = 30_000L
        private const val GATT_READY_TIMEOUT_MS = 75_000L
        private const val PRE_GATT_BOND_TIMEOUT_MS = 60_000L
        private const val BOND_STATE_POLL_MS = 250L
        private const val DEFAULT_ATT_PAYLOAD_BYTES = 20
        private const val BOND_RETRY_DELAY_MS = 1_000L
        private const val MAX_BOND_RETRIES = 60
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @Volatile private var diagnosticAttempt: ConnectionDiagnosticAttempt? = null

    internal fun setDiagnosticAttempt(attempt: ConnectionDiagnosticAttempt?) {
        diagnosticAttempt = attempt
    }

    private fun recordDiagnosticSyncResult(
        attempt: ConnectionDiagnosticAttempt?, transport: ConnectionDiagnosticTransport,
        outcome: ConnectionDiagnosticOutcome, result: ClockSyncCalculator.SyncResult?, retry: Int = 0
    ) {
        val session = runCatching { UUID.fromString(sessionId) }.getOrNull()
        attempt?.record(ConnectionDiagnosticPhase.CLOCK_SYNC, outcome, transport, session) { event ->
            event.copy(role = ConnectionDiagnosticRole.REQUESTER, retry = retry,
                errorDomain = if (outcome == ConnectionDiagnosticOutcome.FAILED || outcome == ConnectionDiagnosticOutcome.FALLBACK) ConnectionDiagnosticErrorDomain.CLOCK_SYNC else null,
                errorCode = if (outcome == ConnectionDiagnosticOutcome.FAILED || outcome == ConnectionDiagnosticOutcome.FALLBACK) {
                    if (result == null) ConnectionDiagnosticErrorCode.UNKNOWN else ConnectionDiagnosticErrorCode.QUALITY_REJECTED
                } else null,
                uncertaintyMs = result?.uncertaintyMs, minRttMs = result?.minRttMs, jitterMs = result?.jitterMs,
                acceptedSamples = result?.samplesUsed, rejectedSamples = result?.let { it.totalSamples - it.samplesUsed },
                estimator = when {
                    transport == ConnectionDiagnosticTransport.WIFI_AWARE -> ConnectionDiagnosticEstimator.WIFI_AWARE
                    result?.estimator == ClockSyncOneWayBoundPolicy.ESTIMATOR_NAME -> ConnectionDiagnosticEstimator.ONE_WAY_BOUND
                    result?.estimator == ClockSyncOneWayBoundPolicy.BASELINE_ESTIMATOR_NAME -> ConnectionDiagnosticEstimator.NTP
                    else -> ConnectionDiagnosticEstimator.UNKNOWN
                })
        }
    }

    // States
    sealed class State {
        object Idle : State()
        object Pairing : State()       // Dual-mode: advertising + scanning simultaneously
        object Scanning : State()
        object Bonding : State()       // Waiting for the system Pair & connect confirmation
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
    @Volatile private var pendingGattService: BluetoothGattService? = null
    private val acceptingServerConnections = AtomicBoolean(false)
    // Multi-client: map of device address -> BluetoothDevice
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    // Legacy single-device accessor (for client mode or first server connection)
    private val connectedDevice: BluetoothDevice?
        get() = protocolReadyDevices.firstNotNullOfOrNull(connectedDevices::get)

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
    private var bondPreparationJob: Job? = null

    // A raw Bluetooth connection is not an application connection. A server
    // peer becomes protocol-ready only after it enables encrypted notifications;
    // the client becomes ready after its encrypted CCC write succeeds.
    private val clientNotificationsReady = ConcurrentHashMap.newKeySet<String>()
    private val protocolReadyDevices = ConcurrentHashMap.newKeySet<String>()
    private val wifiAwareCapableDevices = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeClientPeerAdvertisesAndroid = false
    @Volatile private var activeClientPeerWifiAwareCapable = false
    private val wifiAwareClockSyncService: WifiAwareClockSyncService? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            WifiAwareClockSyncService(context, connectionDiagnostics.samplingGate) { diagnosticAttempt }
        } else {
            null
        }
    }

    // Connection events flow for ClockSyncManager to track new clients
    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    // Per-client notification readiness events (emitted when a client writes CCC descriptor)
    private val _clientReadyDevices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val clientReadyDevices: SharedFlow<String> = _clientReadyDevices.asSharedFlow()

    // Map from TimingMessage senderId → BLE device address (populated on server receiving writes)
    private val senderDeviceMap = ConcurrentHashMap<String, String>()
    private val peerIdentityBindings = BlePeerIdentityBindings()

    data class ConnectionEvent(val device: BluetoothDevice, val connected: Boolean)

    fun connectedDeviceIds(): List<String> = protocolReadyDevices.toList()

    // Sync state
    @Volatile private var role: Role? = null
    @Volatile private var syncCalculator: ClockSyncCalculator? = null
    private val sequenceNumber = AtomicLong(0)
    private val pendingPings = ConcurrentHashMap<String, Long>()  // pingId (UUID) -> t1 nanos
    private val reverseProbes = ConcurrentLinkedQueue<ClockSyncReverseProbe>()
    @Volatile private var localIsClockReference = false
    @Volatile private var authorizedReferenceDeviceId: String? = null
    @Volatile private var activeSyncTargetAddress: String? = null
    @Volatile private var measurementStartedAtNanos: Long = 0L
    private val lastForwardPingReceivedAtNanos = AtomicLong(0L)
    private val referenceSyncPingCount = AtomicInteger(0)
    private val referenceSetupProgressOpen = AtomicBoolean(false)
    private var reverseProbeJob: Job? = null
    private val criticalMessageRetries = CriticalMessageRetryTracker(
        nowMillis = SystemClock::elapsedRealtime
    )
    private var syncJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val syncConnectionPriorityGeneration = AtomicLong(0L)
    private var lowLatencyPriorityReassertJob: Job? = null

    // BLE allows one acknowledged client write and one server notification at
    // a time. Queues contain already-framed ATT packets, not whole messages.
    private data class ClientWritePacket(
        val data: ByteArray,
        val writeType: Int,
        val retryAfterBond: Boolean = true
    )

    private val clientWriteQueue = ConcurrentLinkedDeque<ClientWritePacket>()
    private val isClientWritePending = AtomicBoolean(false)
    @Volatile private var clientWriteInFlight: ClientWritePacket? = null
    @Volatile private var clientMaximumPacketBytes = DEFAULT_ATT_PAYLOAD_BYTES

    private data class ServerPacket(val deviceAddress: String, val data: ByteArray)

    private val serverNotificationQueue = ConcurrentLinkedDeque<ServerPacket>()
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

        // Advertising starts only after onServiceAdded confirms that the GATT
        // database is ready. Advertising earlier lets a fast central connect
        // before the timing service is discoverable.
        startGattServer()
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

        // onServiceAdded starts advertising once the service is discoverable.
        startGattServer()

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

    @SuppressLint("NewApi")
    private fun cancelWifiAwareClockReference() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiAwareClockSyncService?.cancelReference()
        }
    }

    @SuppressLint("NewApi")
    private fun localWifiAwareClockSyncAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            wifiAwareClockSyncService?.isAvailable() == true
    }

    /**
     * Get the resolved role after dual-mode pairing completes.
     */
    fun getResolvedRole(): Role? = role

    /**
     * Timing role and BLE central/peripheral role are independent. The start
     * gate is the reference clock even when it connected as the GATT client.
     */
    @SuppressLint("MissingPermission")
    fun configureClockReference(isReference: Boolean, referenceDeviceId: String? = null) {
        val wasReference = localIsClockReference
        localIsClockReference = isReference
        authorizedReferenceDeviceId = if (isReference) null else referenceDeviceId
        if (isReference) {
            if (!wasReference) {
                referenceSyncPingCount.set(0)
                referenceSetupProgressOpen.set(true)
            }
            activeSyncTargetAddress = null
            pendingPings.clear()
            // When Android is the GATT client but the timing reference, the iPhone
            // drives the ping stream. Keep the central link in its low-latency
            // interval for that responder window as well; startSync() is not called
            // on a reference device, so its normal priority boost would never run.
            beginLowLatencyClientHold("reference_clock")
        } else {
            lowLatencyPriorityReassertJob?.cancel()
            lowLatencyPriorityReassertJob = null
            referenceSyncPingCount.set(0)
            referenceSetupProgressOpen.set(false)
        }
        Log.i(
            TAG,
            "Clock role configured: reference=$isReference, peer=${referenceDeviceId?.take(8) ?: "none"}"
        )
    }

    /**
     * Look up the BLE device address for a given TimingMessage senderId.
     * Only populated in server mode when messages are received from clients.
     */
    fun getDeviceAddress(senderId: String): String? = (senderDeviceMap[senderId]
        ?: senderDeviceMap.entries.firstOrNull { it.key.equals(senderId, true) }?.value)
        ?.takeIf(protocolReadyDevices::contains)

    fun stableDeviceIdForSender(senderId: String): String? = getDeviceAddress(senderId)
        ?.let(peerIdentityBindings::stableId)

    fun hasDirectGattRouteForSender(senderId: String): Boolean = getDeviceAddress(senderId) != null

    @SuppressLint("NewApi")
    fun prepareWifiAwareClockReference(requesterDeviceId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !localIsClockReference
        ) {
            return false
        }
        val peerSupportsAware = when (role) {
            Role.Client -> activeClientPeerWifiAwareCapable
            Role.Server -> getDeviceAddress(requesterDeviceId)
                ?.let(wifiAwareCapableDevices::contains) == true
            null -> false
        }
        val awareService = wifiAwareClockSyncService
        if (!peerSupportsAware || awareService?.isAvailable() != true) return false

        val started = awareService.startReference(
            sessionId = sessionId,
            requesterId = requesterDeviceId,
            referenceId = deviceId,
            onProgress = ::publishWifiAwareReferenceProgress
        )
        if (started) {
            Log.i(
                TAG,
                "Reference: prepared secured Wi-Fi Aware clock lane for " +
                    requesterDeviceId.take(8)
            )
        }
        return started
    }

    private fun publishWifiAwareReferenceProgress(progress: Float) {
        if (!referenceSetupProgressOpen.get() ||
            _state.value is State.Synced ||
            _state.value is State.Error
        ) {
            return
        }
        val current = (_state.value as? State.Syncing)?.progress ?: 0f
        _state.value = State.Syncing(maxOf(current, progress))
    }

    private fun activeSyncPeerSupportsWifiAware(): Boolean {
        return when (role) {
            Role.Client -> activeClientPeerWifiAwareCapable
            Role.Server -> activeSyncTargetAddress
                ?.let(wifiAwareCapableDevices::contains) == true
            null -> false
        }
    }

    /**
     * Known peer device IDs from TimingMessage.senderId values.
     * Used by the host to fan out relayed timing events to every peer except
     * the originator, matching iOS RaceSession.relayCritical.
     */
    fun connectedPeerDeviceIds(): List<String> = buildSet {
        addAll(
            senderDeviceMap
                .filterValues(protocolReadyDevices::contains)
                .keys
        )
    }.toList()

    /** Reset link-local state without rotating the logical session ID. */
    @SuppressLint("MissingPermission")
    private fun prepareForNewTransportStart() {
        transportGeneration.incrementAndGet()
        val previousWasServer = role == Role.Server
        role = null
        syncJob?.cancel()
        syncJob = null
        reverseProbeJob?.cancel()
        reverseProbeJob = null
        lowLatencyPriorityReassertJob?.cancel()
        lowLatencyPriorityReassertJob = null
        cancelWifiAwareClockReference()
        cancelBondPreparation()
        cancelScanTimeout()
        cancelGattReadyTimeout()
        stopAdvertising()
        stopScanning()
        closeGatt()
        cloudTimingRelay.stop(deviceId, isHost = previousWasServer)

        connectedDevices.clear()
        clientNotificationsReady.clear()
        protocolReadyDevices.clear()
        wifiAwareCapableDevices.clear()
        activeClientPeerAdvertisesAndroid = false
        activeClientPeerWifiAwareCapable = false
        senderDeviceMap.clear()
        peerIdentityBindings.clear()
        pendingPings.clear()
        reverseProbes.clear()
        localIsClockReference = false
        authorizedReferenceDeviceId = null
        activeSyncTargetAddress = null
        measurementStartedAtNanos = 0L
        lastForwardPingReceivedAtNanos.set(0L)
        referenceSyncPingCount.set(0)
        referenceSetupProgressOpen.set(false)
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
        reverseProbeJob?.cancel()
        reverseProbeJob = null
        lowLatencyPriorityReassertJob?.cancel()
        lowLatencyPriorityReassertJob = null
        cancelWifiAwareClockReference()
        cancelBondPreparation()
        cancelScanTimeout()
        cancelGattReadyTimeout()

        stopAdvertising()
        stopScanning()
        closeGatt()

        syncCalculator = null
        pendingPings.clear()
        reverseProbes.clear()
        localIsClockReference = false
        authorizedReferenceDeviceId = null
        activeSyncTargetAddress = null
        measurementStartedAtNanos = 0L
        lastForwardPingReceivedAtNanos.set(0L)
        referenceSyncPingCount.set(0)
        referenceSetupProgressOpen.set(false)
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
        protocolReadyDevices.clear()
        wifiAwareCapableDevices.clear()
        activeClientPeerAdvertisesAndroid = false
        activeClientPeerWifiAwareCapable = false
        connectedDevices.clear()
        senderDeviceMap.clear()
        peerIdentityBindings.clear()
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
        if (server == null) {
            acceptingServerConnections.set(false)
            _state.value = State.Error("This phone couldn't start Bluetooth timing.")
            return
        }

        acceptingServerConnections.set(true)
        gattServer = server

        // Create service with UUIDs matching iOS BluetoothTransport.
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
            addDescriptor(
                BluetoothGattDescriptor(
                    CLIENT_CHARACTERISTIC_CONFIG_UUID,
                    BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED or
                        BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED
                )
            )
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

        pendingGattService = service
        val queued = try {
            server.addService(service)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission while adding GATT service", e)
            _state.value = State.Error("Nearby devices permission is required for multi-phone timing.")
            false
        }
        if (!queued) {
            Log.e(TAG, "GATT server rejected the timing service registration")
            pendingGattService = null
            closeGattServer()
            _state.value = State.Error("This phone couldn't prepare Bluetooth timing. Try again.")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service !== pendingGattService) return
            pendingGattService = null
            if (!acceptingServerConnections.get() || gattServer == null || role == Role.Client) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT timing service registration failed: status=$status")
                closeGattServer()
                _state.value = State.Error("This phone couldn't prepare Bluetooth timing. Try again.")
                return
            }

            Log.i(TAG, "GATT timing service registered; starting discoverable advertising")
            startAdvertising()
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (!acceptingServerConnections.get()) {
                runCatching { gattServer?.cancelConnection(device) }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(
                        TAG,
                        "Server: Raw GATT link connected: ${device.address}; " +
                            "waiting for encrypted notification readiness"
                    )
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
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Server: Client disconnected: ${device.address}")
                    connectedDevices.remove(device.address)
                    clientNotificationsReady.remove(device.address)
                    val wasProtocolReady = protocolReadyDevices.remove(device.address)
                    wifiAwareCapableDevices.remove(device.address)
                    serverMaximumPacketBytes.remove(device.address)
                    serverInboundFramers.remove(device.address)
                    val senderIds = senderDeviceMap
                        .filterValues { it == device.address }
                        .keys
                        .toSet()
                    senderDeviceMap.entries.removeIf { it.value == device.address }
                    peerIdentityBindings.remove(device.address)
                    serverNotificationQueue.removeIf { it.deviceAddress == device.address }
                    criticalMessageRetries.forgetPeer(device.address, senderIds)
                    if (wasProtocolReady) {
                        _connectionEvents.tryEmit(ConnectionEvent(device, false))
                    }
                    if (protocolReadyDevices.isEmpty()) {
                        _state.value = if (acceptingServerConnections.get()) State.Pairing else State.Idle
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
            // Capture the transport boundary before framing, GATT responses,
            // JSON decoding, or coroutine dispatch can bias clock samples.
            val receivedAtNanos = SystemClock.elapsedRealtimeNanos()
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
                handleServerMessage(device, payload, receivedAtNanos)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val maximumPacketBytes = BleMessageFramer.maximumPacketBytesForMtu(mtu)
            serverMaximumPacketBytes[device.address] = maximumPacketBytes
            Log.i(
                TAG,
                "Server: MTU for ${device.address} changed to $mtu " +
                    "(GATT value cap=${maximumPacketBytes}B)"
            )
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
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                descriptor.characteristic.uuid == ClockSyncConfig.PING_CHARACTERISTIC_UUID &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            ) {
                if (clientNotificationsReady.add(device.address)) {
                    Log.i(TAG, "Server: Client ${device.address} enabled notifications (CCC written) — ClientReady")
                    if (protocolReadyDevices.add(device.address)) {
                        _connectionEvents.tryEmit(ConnectionEvent(device, true))
                    }
                    _clientReadyDevices.tryEmit(device.address)
                    _state.value = State.ClientReady
                } else {
                    Log.d(TAG, "Server: Ignoring duplicate ClientReady for ${device.address}")
                }
            } else if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                descriptor.characteristic.uuid == ClockSyncConfig.PING_CHARACTERISTIC_UUID &&
                value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            ) {
                clientNotificationsReady.remove(device.address)
                wifiAwareCapableDevices.remove(device.address)
                if (protocolReadyDevices.remove(device.address)) {
                    val senderIds = senderDeviceMap
                        .filterValues { it == device.address }
                        .keys
                        .toSet()
                    senderDeviceMap.entries.removeIf { it.value == device.address }
                    peerIdentityBindings.remove(device.address)
                    serverNotificationQueue.removeIf { it.deviceAddress == device.address }
                    criticalMessageRetries.forgetPeer(device.address, senderIds)
                    _connectionEvents.tryEmit(ConnectionEvent(device, false))
                }
                Log.i(TAG, "Server: Client ${device.address} disabled notifications")
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
        if (!acceptCompatibleProtocol(message, "BLE server")) return

        if (message.payload is TimingPayload.RoleRequest) {
            val request = message.payload
            val directRouteReady = protocolReadyDevices.contains(device.address)
            if (!TimingBootstrapPolicy.acceptsPhysicallyTargetedRoleRequest(
                    request, message.senderId, directRouteReady
                ) || !peerIdentityBindings.bind(device.address, request.deviceId, deviceId)
            ) {
                Log.w(TAG, "Server: Rejected conflicting or invalid peer identity announcement")
                return
            }
            senderDeviceMap[request.deviceId] = device.address
        }
        senderDeviceMap[message.senderId] = device.address

        if (!isEnvelopeForLocalDevice(message, allowDirectGattRoleRequest = true)) {
            Log.d(TAG, "Server: Ignoring ${message.payload::class.simpleName}: target mismatch")
            return
        }
        handleCriticalResponse(message, device.address)

        when (val payload = message.payload) {
            is TimingPayload.AndroidTransportCapabilities -> {
                handleAndroidTransportCapabilities(message, payload, device.address)
            }
            is TimingPayload.SyncPing -> {
                handleIncomingSyncPing(
                    message = message,
                    payload = payload,
                    receivedAtNanos = receivedAtNanos,
                    senderAddress = device.address
                )
            }
            is TimingPayload.SyncPong -> {
                handleIncomingSyncPong(
                    message = message,
                    payload = payload,
                    receivedAtNanos = receivedAtNanos
                )
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
                        targetDeviceId = stableDeviceIdForSender(message.senderId) ?: message.senderId
                    )
                }
                Log.d(TAG, "Server: Forwarding ${payload::class.simpleName} to app")
                _incomingMessages.tryEmit(message)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueServerPayload(device: BluetoothDevice, payload: ByteArray): Boolean {
        if (!protocolReadyDevices.contains(device.address) ||
            !clientNotificationsReady.contains(device.address)
        ) {
            Log.d(TAG, "Server: Refusing payload before ClientReady for ${device.address}")
            return false
        }
        val maximumPacketBytes = serverMaximumPacketBytes[device.address]
            ?: DEFAULT_ATT_PAYLOAD_BYTES
        val packets = try {
            BleMessageFramer.packets(payload, maximumPacketBytes)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Server: Failed to frame BLE payload: ${e.message}")
            return false
        }
        if (packets.size > 1) {
            Log.i(
                TAG,
                "Server: Framed ${payload.size}B payload into ${packets.size} GATT values " +
                    "(largest=${packets.maxOf(ByteArray::size)}B)"
            )
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

            val queued = try {
                notifyCharacteristicChangedCompat(
                    server = server,
                    device = device,
                    characteristic = characteristic,
                    value = packet.data
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Server: Missing permission while notifying ${device.address}", e)
                false
            } catch (e: IllegalArgumentException) {
                // A malformed or oversized attribute must never crash the app.
                // Drop this peer's already-framed queue because continuing
                // after a missing fragment would corrupt the next message.
                Log.e(
                    TAG,
                    "Server: Rejected ${packet.data.size}B notification for ${device.address}; " +
                        "clearing the peer queue",
                    e
                )
                serverNotificationQueue.removeIf { it.deviceAddress == packet.deviceAddress }
                isServerNotificationPending.set(false)
                _state.value = State.Error("Bluetooth couldn't send the session setup. Reconnect and try again.")
                return
            }
            if (!queued) {
                serverNotificationQueue.addFirst(packet)
                isServerNotificationPending.set(false)
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
            val remoteToken = result.scanRecord?.getServiceData(
                ParcelUuid(ClockSyncConfig.SERVICE_UUID)
            )
            val isAndroidPeer = BleRoleElection.isAndroidPeerToken(remoteToken)

            // In dual-mode: stop advertising and close GATT server since we're becoming client
            if (role == null) {
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

            prepareSecureLinkAndConnect(result.device, isAndroidPeer)
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
    private fun prepareSecureLinkAndConnect(device: BluetoothDevice, isAndroidPeer: Boolean) {
        activeClientPeerAdvertisesAndroid = isAndroidPeer
        activeClientPeerWifiAwareCapable = false
        if (!isAndroidPeer || device.bondState == BluetoothDevice.BOND_BONDED) {
            connectToServer(device)
            return
        }

        cancelBondPreparation()
        connectingDeviceAddress = device.address
        _state.value = State.Bonding
        val generation = transportGeneration.get()
        val initialBondState = device.bondState
        val bondStarted = when (initialBondState) {
            BluetoothDevice.BOND_BONDING -> true
            BluetoothDevice.BOND_NONE -> runCatching { device.createBond() }
                .onFailure { Log.w(TAG, "Client: Failed to start pre-GATT Android bond", it) }
                .getOrDefault(false)
            else -> true
        }
        if (!bondStarted) {
            connectingDeviceAddress = null
            _state.value = State.Error(
                "Bluetooth pairing couldn't start. Reconnect and confirm Pair & connect on both phones."
            )
            return
        }

        Log.i(
            TAG,
            "Client: Securing Android peer ${device.address} before GATT; " +
                "waiting for Pair & connect confirmation"
        )
        bondPreparationJob = serviceScope.launch {
            val deadline = SystemClock.elapsedRealtime() + PRE_GATT_BOND_TIMEOUT_MS
            var sawBonding = initialBondState == BluetoothDevice.BOND_BONDING
            while (isActive &&
                transportGeneration.get() == generation &&
                connectingDeviceAddress == device.address
            ) {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        Log.i(TAG, "Client: Android peer bonded; opening GATT")
                        handler.post {
                            if (transportGeneration.get() == generation &&
                                connectingDeviceAddress == device.address &&
                                role == Role.Client
                            ) {
                                connectToServer(device)
                            }
                        }
                        return@launch
                    }
                    BluetoothDevice.BOND_BONDING -> sawBonding = true
                    BluetoothDevice.BOND_NONE -> if (sawBonding) {
                        Log.w(TAG, "Client: Android peer pairing was rejected or cancelled")
                        connectingDeviceAddress = null
                        _state.value = State.Error(
                            "Bluetooth pairing was cancelled. Reconnect and confirm Pair & connect on both phones."
                        )
                        return@launch
                    }
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    Log.w(TAG, "Client: Timed out waiting for pre-GATT Android bond")
                    connectingDeviceAddress = null
                    _state.value = State.Error(
                        "Bluetooth pairing timed out. Confirm Pair & connect on both phones, then reconnect."
                    )
                    return@launch
                }
                delay(BOND_STATE_POLL_MS)
            }
        }
    }

    private fun cancelBondPreparation() {
        bondPreparationJob?.cancel()
        bondPreparationJob = null
    }

    @SuppressLint("MissingPermission")
    private fun connectToServer(device: BluetoothDevice) {
        cancelBondPreparation()
        _state.value = State.Connecting
        connectingDeviceAddress = device.address
        Log.i(TAG, "Connecting to server: ${device.address}")

        scheduleGattReadyTimeout(device.address)
        gattClient = try {
            device.connectGatt(
                context,
                false,
                gattClientCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK
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
        cancelBondPreparation()
        cancelGattReadyTimeout()
        connectingDeviceAddress = null
        try {
            gattClient?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect during restart failed: ${e.message}")
        }
        closeGattClient()
        connectedDevices.clear()
        protocolReadyDevices.clear()
        activeClientPeerAdvertisesAndroid = false
        activeClientPeerWifiAwareCapable = false
        txCharacteristic = null
        rxCharacteristic = null
        clientWriteQueue.clear()
        isClientWritePending.set(false)
        clientWriteInFlight = null
        clientMaximumPacketBytes = DEFAULT_ATT_PAYLOAD_BYTES
        clientInboundFramer.reset()
        notificationBondRetryCount = 0
        criticalMessageRetries.clear()

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
                    requestLowLatencyClientLink("connected", gatt)
                    // Request large MTU for JSON messages (~250-400 bytes)
                    if (!gatt.requestMtu(ClockSyncConfig.PREFERRED_MTU)) {
                        Log.w(TAG, "Client: MTU request was not queued; discovering services at default MTU")
                        if (!gatt.discoverServices()) {
                            restartClientDiscovery("service_discovery_not_queued")
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Client: Disconnected from server")
                    connectedDevices.remove(gatt.device.address)
                    protocolReadyDevices.remove(gatt.device.address)
                    activeClientPeerAdvertisesAndroid = false
                    activeClientPeerWifiAwareCapable = false
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
            if (gattClient !== gatt) {
                runCatching { gatt.close() }
                return
            }
            Log.i(TAG, "Client: MTU changed to $mtu (status=$status)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                clientMaximumPacketBytes = BleMessageFramer.maximumPacketBytesForMtu(mtu)
                Log.i(TAG, "Client: GATT value cap=${clientMaximumPacketBytes}B")
            }
            // Proceed with service discovery regardless of MTU result
            if (!gatt.discoverServices()) {
                restartClientDiscovery("service_discovery_not_queued")
            }
        }

        override fun onPhyUpdate(
            gatt: BluetoothGatt,
            txPhy: Int,
            rxPhy: Int,
            status: Int
        ) {
            if (gattClient !== gatt) return
            Log.i(
                TAG,
                "Client: BLE PHY update tx=${phyName(txPhy)} rx=${phyName(rxPhy)} status=$status"
            )
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gattClient !== gatt) {
                runCatching { gatt.close() }
                return
            }
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
            if (!gatt.setCharacteristicNotification(txCharacteristic, true)) {
                Log.w(TAG, "Client: Failed to enable local notification routing")
                restartClientDiscovery("notification_routing_failed")
                return
            }
            val descriptor = txCharacteristic?.getDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG_UUID
            )
            if (descriptor == null) {
                Log.w(TAG, "Client: Notification descriptor not found")
                restartClientDiscovery("notification_descriptor_not_found")
                return
            }
            if (!writeDescriptorCompat(
                    gatt,
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            ) {
                Log.w(TAG, "Client: Failed to start notification descriptor write")
                restartClientDiscovery("notification_descriptor_write_failed")
                return
            }

            Log.i(TAG, "Client: Services discovered, enabling encrypted notifications")
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (gattClient !== gatt) {
                runCatching { gatt.close() }
                return
            }
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID ||
                descriptor.characteristic.uuid != ClockSyncConfig.PING_CHARACTERISTIC_UUID
            ) {
                Log.w(TAG, "Client: Ignoring unexpected descriptor callback for ${descriptor.uuid}")
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                notificationBondRetryCount = 0
                cancelGattReadyTimeout()
                connectingDeviceAddress = null
                protocolReadyDevices.add(gatt.device.address)
                Log.i(TAG, "Client: Encrypted notifications enabled, ready for protocol handshake")
                _state.value = State.Connected
                if (activeClientPeerAdvertisesAndroid) {
                    sendMessage(
                        TimingPayload.AndroidTransportCapabilities(
                            wifiAwareClockSync = localWifiAwareClockSyncAvailable()
                        )
                    )
                    Log.i(
                        TAG,
                        "Client: Sent Android transport capabilities " +
                            "(wifiAware=${localWifiAwareClockSyncAvailable()})"
                    )
                }
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
            if (gattClient !== gatt) {
                runCatching { gatt.close() }
                return
            }
            isClientWritePending.set(false)
            val inFlight = clientWriteInFlight
            clientWriteInFlight = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                drainClientWriteQueue()
            } else if (isAuthenticationFailure(status) && inFlight != null) {
                Log.w(TAG, "Client: Encrypted write requires bonding; retrying after bond")
                // A clock packet's captured t1/t3 must never survive a bond
                // retry delay. The requester will sample again with a new ID.
                if (inFlight.retryAfterBond) clientWriteQueue.addFirst(inFlight)
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
            val receivedAtNanos = SystemClock.elapsedRealtimeNanos()
            if (gattClient !== gatt) return
            if (characteristic.uuid == ClockSyncConfig.PING_CHARACTERISTIC_UUID) {
                handleClientPacket(characteristic.value, receivedAtNanos)
            }
        }

        // For Android 13+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val receivedAtNanos = SystemClock.elapsedRealtimeNanos()
            if (gattClient !== gatt) return
            if (characteristic.uuid == ClockSyncConfig.PING_CHARACTERISTIC_UUID) {
                handleClientPacket(value, receivedAtNanos)
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
        _state.value = State.Bonding
        if (gatt.device.bondState == BluetoothDevice.BOND_NONE) {
            runCatching { gatt.device.createBond() }
                .onFailure { Log.w(TAG, "Client: Failed to start BLE bond", it) }
        }
        handler.postDelayed(
            {
                if (gattClient !== gatt) return@postDelayed
                if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                    if (!writeDescriptorCompat(
                            gatt,
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                    ) {
                        retryEncryptedNotificationSetup(gatt, descriptor)
                    }
                } else {
                    retryEncryptedNotificationSetup(gatt, descriptor)
                }
            },
            BOND_RETRY_DELAY_MS
        )
    }

    private fun handleClientPacket(packet: ByteArray, receivedAtNanos: Long) {
        val payloads = try {
            clientInboundFramer.receive(packet)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Client: Rejected malformed BLE frame: ${e.message}")
            return
        }
        payloads.forEach { handleMessageReceived(it, receivedAtNanos) }
    }

    /**
     * Start the NTP clock sync process. Called by ClockSyncManager after
     * the protocol handshake completes (not immediately on BLE connection).
     */
    @SuppressLint("MissingPermission", "NewApi")
    fun startSync(
        referenceDeviceId: String? = authorizedReferenceDeviceId,
        referenceDeviceAddress: String? = null
    ) {
        if (localIsClockReference) {
            Log.i(TAG, "Reference clock is waiting for peer pings; requester sync was not started")
            return
        }
        authorizedReferenceDeviceId = referenceDeviceId ?: authorizedReferenceDeviceId
        activeSyncTargetAddress = referenceDeviceAddress
            ?: activeSyncTargetAddress
            ?: connectedDevice?.address
        if (role == Role.Server && activeSyncTargetAddress == null) {
            _state.value = State.Error("Clock sync couldn't find the assigned start phone.")
            return
        }
        syncJob?.cancel()
        syncJob = null
        syncCalculator = null
        _syncResult.value = null
        pendingPings.clear()
        reverseProbes.clear()
        measurementStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        _state.value = State.Syncing(0f)
        Log.i(TAG, "Starting clock synchronization (${ClockSyncConfig.FULL_SYNC_SAMPLES} samples, JSON protocol)...")

        val priorityGeneration = beginSyncConnectionPriorityWindow()
        val diagnosticSamplingToken = connectionDiagnostics.samplingGate.begin()
        val diagnostic = diagnosticAttempt
        // Lane selection follows the capability exchange below; do not prematurely label it BLE.
        diagnostic?.record(ConnectionDiagnosticPhase.CLOCK_SYNC, ConnectionDiagnosticOutcome.STARTED,
            ConnectionDiagnosticTransport.UNKNOWN,
            sessionId = runCatching { UUID.fromString(sessionId) }.getOrNull()) { event ->
            event.copy(role = ConnectionDiagnosticRole.REQUESTER,
                capabilitySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                peerCapability = activeSyncPeerSupportsWifiAware())
        }
        syncJob = serviceScope.launch {
            var lastResult: ClockSyncCalculator.SyncResult? = null

            // A current Android client advertises its platform token before
            // connecting, then both apps exchange an explicit capability over
            // the encrypted GATT link. Give that reply one bounded moment to
            // arrive so an Android pair does not race straight into BLE sync.
            if (role == Role.Client &&
                activeClientPeerAdvertisesAndroid &&
                !activeClientPeerWifiAwareCapable
            ) {
                repeat(10) {
                    if (activeClientPeerWifiAwareCapable) return@repeat
                    delay(100L)
                }
            }

            val referenceId = authorizedReferenceDeviceId
            val awareService = wifiAwareClockSyncService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                referenceId != null &&
                activeSyncPeerSupportsWifiAware() &&
                awareService?.isAvailable() == true
            ) {
                Log.i(TAG, "Trying secured Wi-Fi Aware clock lane before BLE sampling")
                val awareResult = awareService.measureAsRequester(
                    sessionId = sessionId,
                    requesterId = deviceId,
                    referenceId = referenceId
                ) { progress ->
                    val current = (_state.value as? State.Syncing)?.progress ?: 0f
                    _state.value = State.Syncing(maxOf(current, progress))
                }
                lastResult = awareResult
                if (awareResult != null && awareResult.isSetupAcceptable()) {
                    _syncResult.value = awareResult
                    _state.value = State.Synced(awareResult)
                    Log.i(
                        TAG,
                        "Wi-Fi Aware sync complete: " +
                            "offset=${String.format(Locale.US, "%.2f", awareResult.offsetMs)}ms, " +
                            "uncertainty=${String.format(Locale.US, "%.2f", awareResult.uncertaintyMs)}ms, " +
                            "quality=${awareResult.quality}, " +
                            "samples=${awareResult.samplesUsed}/${awareResult.totalSamples}"
                    )
                    recordDiagnosticSyncResult(diagnostic, ConnectionDiagnosticTransport.WIFI_AWARE,
                        ConnectionDiagnosticOutcome.SUCCEEDED, awareResult)
                    return@launch
                }
                Log.w(
                    TAG,
                    "Wi-Fi Aware clock lane did not pass; falling back to BLE " +
                        "(uncertainty=${awareResult?.uncertaintyMs ?: "none"})"
                )
                recordDiagnosticSyncResult(diagnostic, ConnectionDiagnosticTransport.WIFI_AWARE,
                    ConnectionDiagnosticOutcome.FALLBACK, awareResult)
            }

            diagnostic?.record(ConnectionDiagnosticPhase.CLOCK_SYNC, ConnectionDiagnosticOutcome.STARTED,
                ConnectionDiagnosticTransport.BLE, runCatching { UUID.fromString(sessionId) }.getOrNull()) {
                it.copy(role = ConnectionDiagnosticRole.REQUESTER)
            }
            val measurementCalculator = ClockSyncCalculator(isFullSync = true)
            syncCalculator = measurementCalculator

            // Auto-retry up to MAX_SYNC_RETRIES times. Keep accepted samples
            // across attempts: both rounds share the same monotonic clock window,
            // and the one-way estimator's drift margin covers the full duration.
            // Pooling independent extrema tightens the safe bound instead of
            // discarding a useful first round when it narrowly misses the gate.
            for (attempt in 1..ClockSyncConfig.MAX_SYNC_RETRIES) {
                pendingPings.clear()

                Log.i(TAG, "Sync attempt $attempt/${ClockSyncConfig.MAX_SYNC_RETRIES}")

                repeat(ClockSyncConfig.FULL_SYNC_SAMPLES) { i ->
                    if (!isActive) return@launch

                    delay(ClockSyncLaunchPhasePolicy.delayMs(Math.random()))
                    sendPing()
                    delay(ClockSyncConfig.FULL_SYNC_INTERVAL_MS)

                    // Update progress
                    val completedSamples =
                        ((attempt - 1) * ClockSyncConfig.FULL_SYNC_SAMPLES) + i + 1
                    val sampleProgress = ClockSyncSetupProgress.fromCompletedSamples(completedSamples)
                    val currentProgress = (_state.value as? State.Syncing)?.progress ?: 0f
                    _state.value = State.Syncing(maxOf(currentProgress, sampleProgress))
                }

                // Keep the forward lane quiet until the reference's reverse-probe
                // burst has observed its idle timeout and exited. Those tail probes
                // avoid forward/pong contention and are the cleanest lower-bound
                // observations on Android links that stay at a 30 ms interval.
                delay(ClockSyncReverseProbeSchedulePolicy.RESULT_SETTLE_MS)

                // Calculate result
                val baseline = measurementCalculator.calculateOffset()
                val result = baseline?.let(::selectOneWayBoundIfTighter)
                lastResult = result

                if (result != null && result.isSetupAcceptable()) {
                    _syncResult.value = result
                    _state.value = State.Synced(result)
                    Log.i(TAG, "Sync complete (attempt $attempt): " +
                        "offset=${String.format(Locale.US, "%.2f", result.offsetMs)}ms, " +
                        "uncertainty=${String.format(Locale.US, "%.2f", result.uncertaintyMs)}ms, " +
                        "quality=${result.quality}, " +
                        "estimator=${result.estimator}, " +
                        "samples=${result.samplesUsed}/${result.totalSamples}, " +
                        "minRTT=${String.format(Locale.US, "%.2f", result.minRttMs)}ms, " +
                        "jitter=${String.format(Locale.US, "%.2f", result.jitterMs)}ms")
                    recordDiagnosticSyncResult(diagnostic, ConnectionDiagnosticTransport.BLE,
                        ConnectionDiagnosticOutcome.SUCCEEDED, result, attempt - 1)
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
            recordDiagnosticSyncResult(diagnostic, ConnectionDiagnosticTransport.BLE,
                ConnectionDiagnosticOutcome.FAILED, lastResult, ClockSyncConfig.MAX_SYNC_RETRIES - 1)
        }
        syncJob?.invokeOnCompletion { cause ->
            try { endSyncConnectionPriorityWindow(priorityGeneration) }
            finally { connectionDiagnostics.samplingGate.end(diagnosticSamplingToken) }
            if (cause != null) {
                diagnostic?.record(ConnectionDiagnosticPhase.CLOCK_SYNC,
                    if (cause is CancellationException) ConnectionDiagnosticOutcome.CANCELLED else ConnectionDiagnosticOutcome.FAILED) { event ->
                    event.copy(errorDomain = ConnectionDiagnosticErrorDomain.LIFECYCLE,
                        errorCode = if (cause is CancellationException) ConnectionDiagnosticErrorCode.CANCELLED else ConnectionDiagnosticErrorCode.UNKNOWN)
                }
            }
        }
    }

    /**
     * Perform a mini-sync to refresh the clock offset during an active race.
     * Uses fewer samples and wider RTT tolerance.
     * Can be called periodically (e.g. every 60 seconds).
     */
    @SuppressLint("MissingPermission")
    fun performMiniSync() {
        val hasRoute = when (role) {
            Role.Client -> gattClient != null
            Role.Server -> activeSyncTargetAddress?.let(protocolReadyDevices::contains) == true
            null -> false
        }
        if (!hasRoute || localIsClockReference) {
            Log.w(TAG, "Cannot mini-sync: no requester route to the start reference")
            return
        }

        val previousResult = _syncResult.value
        Log.i(TAG, "Starting mini-sync (${ClockSyncConfig.MINI_SYNC_SAMPLES} samples)...")

        syncJob?.cancel()
        val priorityGeneration = beginSyncConnectionPriorityWindow()
        val diagnosticSamplingToken = connectionDiagnostics.samplingGate.begin()
        syncJob = serviceScope.launch {
            val miniCalc = ClockSyncCalculator(
                isFullSync = false,
                baselineOffsetNanos = previousResult?.offsetNanos,
                baselineEstimator = previousResult?.estimator,
                baselineUncertaintyNanos = previousResult?.uncertaintyMs
                    ?.times(1_000_000.0)
                    ?.toLong()
                    ?: 0L
            )
            // Point syncCalculator to miniCalc so pong responses are added to the right calculator
            syncCalculator = miniCalc
            pendingPings.clear()
            reverseProbes.clear()
            measurementStartedAtNanos = SystemClock.elapsedRealtimeNanos()

            repeat(ClockSyncConfig.MINI_SYNC_SAMPLES) { i ->
                if (!isActive) return@launch

                delay(ClockSyncLaunchPhasePolicy.delayMs(Math.random()))
                sendPing()
                delay(ClockSyncConfig.MINI_SYNC_INTERVAL_MS)
            }

            delay(200)

            val result = miniCalc.calculateOffset()?.let(::selectOneWayBoundIfTighter)
            if (result != null && result.isSetupAcceptable()) {
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
        syncJob?.invokeOnCompletion {
            try { endSyncConnectionPriorityWindow(priorityGeneration) }
            finally { connectionDiagnostics.samplingGate.end(diagnosticSamplingToken) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendPing() {
        val pingId = UUID.randomUUID().toString().uppercase()
        val sent = when (role) {
            Role.Server -> sendTimestampedServerPingAtBoundary(
                pingId = pingId,
                targetDeviceAddress = activeSyncTargetAddress,
                trackResponse = true
            )
            Role.Client -> sendTimestampedRequesterPingAsClient(pingId)
            null -> false
        }
        if (!sent) pendingPings.remove(pingId)
        Log.d(TAG, "Requester: Sent syncPing (pingId=${pingId.take(8)}, sent=$sent)")
    }

    private fun sendTimestampedRequesterPingAsClient(pingId: String): Boolean {
        val message = TimingMessage.create(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = TimingPayload.SyncPing(
                pingId = pingId,
                t1Nanos = 0L,
                requesterId = deviceId
            )
        )
        val template = TimingMessageCodec.prepareSyncPingTimestampTemplate(message)
        return tryWriteTimestampedClientPayloadAtBoundary(
            template = template,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            onTimestampCaptured = { pendingPings[pingId] = it }
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendTimestampedServerPingAtBoundary(
        pingId: String,
        targetDeviceAddress: String?,
        trackResponse: Boolean
    ): Boolean {
        val message = TimingMessage.create(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = TimingPayload.SyncPing(
                pingId = pingId,
                t1Nanos = 0L,
                requesterId = deviceId
            )
        )
        return tryNotifyTimestampedServerPayloadAtBoundary(
            template = TimingMessageCodec.prepareSyncPingTimestampTemplate(message),
            targetDeviceAddress = targetDeviceAddress,
            onTimestampCaptured = { if (trackResponse) pendingPings[pingId] = it }
        )
    }

    @SuppressLint("MissingPermission")
    private fun tryNotifyTimestampedServerPayloadAtBoundary(
        template: ClockSyncTimestampTemplate,
        targetDeviceAddress: String?,
        pongReceivedAtNanos: Long? = null,
        onTimestampCaptured: (Long) -> Unit = {}
    ): Boolean {
        val address = targetDeviceAddress ?: return false
        if (!protocolReadyDevices.contains(address) ||
            !clientNotificationsReady.contains(address)
        ) {
            return false
        }
        val device = connectedDevices[address] ?: return false
        val server = gattServer ?: return false
        val characteristic = txCharacteristic ?: return false
        val maximumPacketBytes = serverMaximumPacketBytes[address]
            ?: DEFAULT_ATT_PAYLOAD_BYTES
        if (!ClockSyncTransmitBoundaryPolicy.canSubmitServerNotificationDirectly(
                encodedSize = template.encodedSize,
                maximumPacketBytes = maximumPacketBytes,
                queueIsEmpty = serverNotificationQueue.isEmpty(),
                notificationPending = isServerNotificationPending.get()
            ) || !isServerNotificationPending.compareAndSet(false, true)
        ) return false

        // Preserve the order of any ordinary writer that raced lane acquisition.
        if (serverNotificationQueue.isNotEmpty()) {
            isServerNotificationPending.set(false)
            drainServerNotificationQueue()
            return false
        }
        val sentAtNanos = SystemClock.elapsedRealtimeNanos()
        if (pongReceivedAtNanos != null &&
            !ClockSyncPongSubmissionPolicy.isFresh(pongReceivedAtNanos, sentAtNanos)
        ) {
            isServerNotificationPending.set(false)
            drainServerNotificationQueue()
            Log.d(TAG, "Server: Skipped stale pong before submission")
            return false
        }
        onTimestampCaptured(sentAtNanos)
        val packet = template.stamp(sentAtNanos)
        val queued = try {
            notifyCharacteristicChangedCompat(server, device, characteristic, packet)
        } catch (e: SecurityException) {
            Log.w(TAG, "Server: Missing permission while submitting boundary clock packet", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Server: Rejected boundary clock packet", e)
            false
        }
        if (!queued) {
            isServerNotificationPending.set(false)
            drainServerNotificationQueue()
            return false
        }
        Log.d(
            TAG,
            "Server: ${if (pongReceivedAtNanos == null) "ping" else "pong"} submitted " +
                "${(SystemClock.elapsedRealtimeNanos() - sentAtNanos) / 1_000L}us after capture"
        )
        return true
    }

    private fun selectOneWayBoundIfTighter(
        baseline: ClockSyncCalculator.SyncResult
    ): ClockSyncCalculator.SyncResult {
        val samples = syncCalculator?.acceptedSamples().orEmpty()
        val probes = reverseProbes.toList()
        val intervals = samples.map { ClockSyncForwardInterval(it) }
        val now = SystemClock.elapsedRealtimeNanos()
        val window = (now - measurementStartedAtNanos).coerceAtLeast(0L)
        val selected = ClockSyncOneWayBoundPolicy.selectIfTighter(
            baseline = baseline,
            forwardIntervals = intervals,
            reverseProbes = probes,
            windowNanos = window
        )
        if (intervals.isNotEmpty() && probes.isNotEmpty()) {
            val forwardLower = intervals.maxOf { it.lowerBoundNanos }
            val forwardUpper = intervals.minOf { it.upperBoundNanos }
            val reverseLower = probes.maxOf { it.offsetLowerBoundNanos }
            Log.i(
                TAG,
                "Clock bound extrema forwardLower=${formatClockBound(forwardLower)}ms " +
                    "reverseLower=${formatClockBound(reverseLower)}ms " +
                    "selectedLower=${formatClockBound(maxOf(forwardLower, reverseLower))}ms " +
                    "forwardUpper=${formatClockBound(forwardUpper)}ms"
            )
        }
        Log.i(
            TAG,
            "Clock estimator baseline=±${String.format(Locale.US, "%.2f", baseline.uncertaintyMs)}ms " +
                "selected=${selected.estimator} ±${String.format(Locale.US, "%.2f", selected.uncertaintyMs)}ms " +
                "forward=${samples.size} reverse=${probes.size}"
        )
        return selected
    }

    private fun formatClockBound(nanos: Long): String =
        String.format(Locale.US, "%.3f", nanos / 1_000_000.0)

    /**
     * Handle an incoming JSON message received via BLE notification (on TX characteristic).
     * Dispatches based on the payload type.
     */
    @SuppressLint("MissingPermission")
    private fun handleMessageReceived(data: ByteArray, receivedAtNanos: Long) {
        val message = try {
            TimingMessageCodec.decodeFromBytes(data)
        } catch (e: Exception) {
            Log.e(TAG, "Client: Failed to decode message (${data.size} bytes): ${e.message}")
            return
        }
        if (!acceptCompatibleProtocol(message, "BLE client")) return

        val hasDirectEncryptedGattRoute = role == Role.Client &&
            gattClient != null &&
            connectedDevice?.bondState == BluetoothDevice.BOND_BONDED
        if (!isEnvelopeForLocalDevice(
                message,
                allowDirectGattPreSessionRoleAssignment = hasDirectEncryptedGattRoute
            )
        ) {
            Log.d(TAG, "Client: Ignoring message targeted to ${message.targetDeviceId?.take(8)}")
            return
        }
        val routedMessage = TimingBootstrapPolicy.normalizePreSessionRoleAssignmentTarget(
            message = message,
            localDeviceId = deviceId,
            hasDirectEncryptedGattRoute = hasDirectEncryptedGattRoute
        )
        handleCriticalResponse(routedMessage, connectedDevice?.address)

        when (val payload = routedMessage.payload) {
            is TimingPayload.AndroidTransportCapabilities -> {
                handleAndroidTransportCapabilities(
                    routedMessage,
                    payload,
                    connectedDevice?.address
                )
            }
            is TimingPayload.SyncPing -> {
                handleIncomingSyncPing(
                    message = routedMessage,
                    payload = payload,
                    receivedAtNanos = receivedAtNanos,
                    senderAddress = connectedDevice?.address
                )
            }
            is TimingPayload.SyncPong -> {
                handleIncomingSyncPong(
                    message = routedMessage,
                    payload = payload,
                    receivedAtNanos = receivedAtNanos
                )
            }
            else -> {
                // Auto-ACK critical messages before forwarding
                if (routedMessage.requiresAck && routedMessage.messageId != null) {
                    Log.d(TAG, "Client: Auto-ACK for ${payload::class.simpleName} (msgId=${routedMessage.messageId.take(8)})")
                    sendMessage(TimingPayload.Ack(messageId = routedMessage.messageId))
                }
                // Forward all other messages to the app via incomingMessages flow
                Log.d(TAG, "Client: Forwarding ${payload::class.simpleName} to app")
                _incomingMessages.tryEmit(routedMessage)
            }
        }
    }

    private fun handleAndroidTransportCapabilities(
        message: TimingMessage,
        payload: TimingPayload.AndroidTransportCapabilities,
        senderAddress: String?
    ) {
        if (payload.version != 1 || senderAddress == null) return
        when (role) {
            Role.Server -> {
                if (payload.wifiAwareClockSync) {
                    wifiAwareCapableDevices.add(senderAddress)
                } else {
                    wifiAwareCapableDevices.remove(senderAddress)
                }
                sendMessageToDevice(
                    payload = TimingPayload.AndroidTransportCapabilities(
                        wifiAwareClockSync = localWifiAwareClockSyncAvailable()
                    ),
                    deviceAddress = senderAddress,
                    targetDeviceId = stableDeviceIdForSender(message.senderId) ?: message.senderId
                )
                Log.i(
                    TAG,
                    "Server: Android peer ${message.senderId.take(8)} capabilities " +
                        "wifiAware=${payload.wifiAwareClockSync}"
                )
            }
            Role.Client -> {
                activeClientPeerWifiAwareCapable =
                    activeClientPeerAdvertisesAndroid && payload.wifiAwareClockSync
                Log.i(
                    TAG,
                    "Client: Android reference capabilities " +
                        "wifiAware=$activeClientPeerWifiAwareCapable"
                )
            }
            null -> Unit
        }
    }

    private fun handleIncomingSyncPing(
        message: TimingMessage,
        payload: TimingPayload.SyncPing,
        receivedAtNanos: Long,
        senderAddress: String?
    ) {
        if (localIsClockReference) {
            val sent = sendPongAtTransmitBoundary(payload, receivedAtNanos, senderAddress)
            lastForwardPingReceivedAtNanos.set(receivedAtNanos)
            if (sent) {
                val receivedSamples = referenceSyncPingCount.incrementAndGet()
                val measuredProgress = ClockSyncSetupProgress.fromCompletedSamples(receivedSamples)
                val currentProgress = (_state.value as? State.Syncing)?.progress ?: 0f
                if (referenceSetupProgressOpen.get() &&
                    _state.value !is State.Synced &&
                    _state.value !is State.Error
                ) {
                    _state.value = State.Syncing(maxOf(currentProgress, measuredProgress))
                    if (receivedSamples == 1 ||
                        receivedSamples % (ClockSyncConfig.FULL_SYNC_SAMPLES / 4) == 0
                    ) {
                        Log.i(
                            TAG,
                            "Reference setup progress: samples=$receivedSamples " +
                                "progress=${(measuredProgress * 100).toInt()}%"
                        )
                    }
                }
            }
            // Independent reverse observations remain useful even when this
            // particular pong was busy/stale. They use their own idle-lane check.
            scheduleReverseProbeBurst(senderAddress)
            Log.d(
                TAG,
                "Reference: responded to syncPing ${payload.pingId.take(8)} from ${message.senderId.take(8)} sent=$sent"
            )
            return
        }

        val authorizedReference = authorizedReferenceDeviceId
        val isReferenceProbe = payload.requesterId.equals(message.senderId, ignoreCase = true) &&
            (authorizedReference == null || message.senderId.equals(authorizedReference, ignoreCase = true))
        if (!isReferenceProbe) {
            Log.d(TAG, "Ignoring syncPing from non-reference ${message.senderId.take(8)}")
            return
        }

        val probe = ClockSyncReverseProbe(
            probeId = payload.pingId,
            remoteSendNanos = payload.t1Nanos,
            localReceiveNanos = receivedAtNanos
        )
        val applied = _syncResult.value
        if (applied?.estimator == ClockSyncOneWayBoundPolicy.ESTIMATOR_NAME &&
            !ClockSyncBoundedSampleConsistencyPolicy.isConsistent(
                probe = probe,
                appliedOffsetNanos = applied.offsetNanos,
                toleranceNanos = ClockSyncConfig.MAX_OFFSET_JUMP_NANOS +
                    (applied.uncertaintyMs * 1_000_000.0).toLong()
            )
        ) {
            Log.w(TAG, "Rejected reverse probe outside the applied clock bound")
            return
        }
        if (reverseProbes.size < 400) reverseProbes.add(probe)
    }

    private fun handleIncomingSyncPong(
        message: TimingMessage,
        payload: TimingPayload.SyncPong,
        receivedAtNanos: Long
    ) {
        if (!payload.requesterId.equals(deviceId, ignoreCase = true)) return
        val authorizedReference = authorizedReferenceDeviceId
        if (authorizedReference != null &&
            !message.senderId.equals(authorizedReference, ignoreCase = true)
        ) {
            Log.w(TAG, "Rejected syncPong from unauthorized reference ${message.senderId.take(8)}")
            return
        }

        val expectedT1 = pendingPings.remove(payload.pingId)
        if (expectedT1 == null) {
            Log.d(TAG, "Ignoring late or unknown syncPong ${payload.pingId.take(8)}")
            return
        }
        if (expectedT1 != payload.t1Nanos) {
            Log.w(TAG, "Rejected syncPong with mismatched t1 ${payload.pingId.take(8)}")
            return
        }

        val sample = ClockSyncCalculator.SyncSample(
            payload.t1Nanos,
            payload.t2Nanos,
            payload.t3Nanos,
            receivedAtNanos
        )
        val accepted = syncCalculator?.addSample(sample) ?: false
        Log.d(
            TAG,
            "Received syncPong ${payload.pingId.take(8)} RTT=" +
                "${String.format(Locale.US, "%.2f", sample.rtt / 1_000_000.0)}ms accepted=$accepted"
        )
    }

    private fun scheduleReverseProbeBurst(targetDeviceAddress: String?) {
        if (reverseProbeJob?.isActive == true) return
        reverseProbeJob = serviceScope.launch {
            var sentCount = 0
            var busyLaneSkips = 0
            while (isActive) {
                val spacing = ClockSyncReverseProbeSchedulePolicy.nextSpacingMs(Math.random())
                delay(spacing)
                val lastForward = lastForwardPingReceivedAtNanos.get().takeIf { it > 0L }
                if (!ClockSyncReverseProbeSchedulePolicy.shouldContinue(
                        lastForwardPingNanos = lastForward,
                        nowNanos = SystemClock.elapsedRealtimeNanos(),
                        sentCount = sentCount
                    )
                ) {
                    break
                }

                val sent = sendReverseProbeAtTransmitBoundary(targetDeviceAddress)
                if (sent) sentCount++
                else if (role == Role.Client) busyLaneSkips++
            }
            Log.i(
                TAG,
                "Reference reverse-probe burst ended after $sentCount probes " +
                    "(busyLaneSkips=$busyLaneSkips)"
            )
        }
    }

    /**
     * A reverse probe's t1 is a one-way-bound endpoint, so it must describe the
     * ATT submission boundary rather than time spent behind an older write.
     * Android is the GATT client in the failing iPhone-host flow. Submit only
     * while that lane is idle; a busy interval is skipped and the randomized
     * burst tries again instead of publishing an artificially early timestamp.
     */
    private fun sendReverseProbeAtTransmitBoundary(targetDeviceAddress: String?): Boolean {
        val pingId = UUID.randomUUID().toString().uppercase()
        if (role != Role.Client) {
            return sendTimestampedServerPingAtBoundary(
                pingId = pingId,
                targetDeviceAddress = targetDeviceAddress,
                trackResponse = false
            )
        }

        val message = TimingMessage.create(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = TimingPayload.SyncPing(
                pingId = pingId,
                t1Nanos = 0L,
                requesterId = deviceId
            )
        )
        val timestampTemplate = TimingMessageCodec.prepareSyncPingTimestampTemplate(message)
        return tryWriteTimestampedClientPayloadAtBoundary(
            template = timestampTemplate,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        )
    }

    /**
     * Serialize before capturing t3, and never queue a timestamped pong behind
     * unrelated ATT traffic. Queued/fragmented pongs include local waiting in
     * the peer's radio-delay bound. A busy or >5 ms turnaround is skipped so
     * the requester can use its remaining samples, with the same safety gates.
     */
    private fun sendPongAtTransmitBoundary(
        ping: TimingPayload.SyncPing,
        receivedAtNanos: Long,
        targetDeviceAddress: String?
    ): Boolean {
        val message = TimingMessage.create(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = TimingPayload.SyncPong(
                pingId = ping.pingId,
                t1Nanos = ping.t1Nanos,
                t2Nanos = receivedAtNanos,
                t3Nanos = 0L,
                requesterId = ping.requesterId
            )
        )
        val template = TimingMessageCodec.prepareSyncPongTimestampTemplate(message)
        return when (role) {
            Role.Server -> tryNotifyTimestampedServerPayloadAtBoundary(
                template, targetDeviceAddress, receivedAtNanos
            )
            Role.Client -> tryWriteTimestampedClientPayloadAtBoundary(
                template = template,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                pongReceivedAtNanos = receivedAtNanos
            )
            null -> false
        }
    }

    private fun handleCloudMessageReceived(message: TimingMessage) {
        if (!message.sessionId.equals(sessionId, ignoreCase = true)) return
        if (!acceptCompatibleProtocol(message, "cloud relay")) return
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
                    targetDeviceId = stableDeviceIdForSender(message.senderId) ?: message.senderId
                )
            } else {
                sendMessage(
                    payload = TimingPayload.Ack(messageId = message.messageId),
                    targetDeviceId = stableDeviceIdForSender(message.senderId) ?: message.senderId
                )
            }
        }
        _incomingMessages.tryEmit(message)
    }

    private fun isEnvelopeForLocalDevice(
        message: TimingMessage,
        allowDirectGattRoleRequest: Boolean = false,
        allowDirectGattPreSessionRoleAssignment: Boolean = false
    ): Boolean {
        val target = message.targetDeviceId ?: return true
        if (target.equals(deviceId, ignoreCase = true)) return true

        // CoreBluetooth's peripheral identifier is local to the iPhone and is
        // not Android's stable DeviceIdentity. An encrypted write delivered to
        // this exact GATT server is sufficient physical targeting for the
        // joiner's identity announcement; all other payloads still fail closed.
        if (TimingBootstrapPolicy.acceptsPhysicallyTargetedRoleRequest(
            payload = message.payload,
            senderId = message.senderId,
            hasDirectEncryptedGattRoute = allowDirectGattRoleRequest
        )) {
            return true
        }

        // The iPhone initially knows this Android only by CoreBluetooth's
        // phone-local central identifier. Accept that physical target solely
        // for the reserved pre-session assignment delivered by the one bonded
        // GATT server. Android immediately answers with its signed stable
        // DeviceIdentity, after which every normal envelope is stable-ID
        // targeted again.
        return TimingBootstrapPolicy.acceptsPhysicallyTargetedPreSessionRoleAssignment(
            payload = message.payload,
            sequence = message.seq,
            hasDirectEncryptedGattRoute = allowDirectGattPreSessionRoleAssignment
        )
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
                    Log.i(
                        TAG,
                        "[TIMING_ACK] messageId=${payload.messageId} " +
                            "senderId=${message.senderId} sessionId=${message.sessionId}"
                    )
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

    private fun acceptCompatibleProtocol(message: TimingMessage, source: String): Boolean {
        if (TimingProtocolCompatibility.accepts(message.protocolVersion)) return true

        Log.e(
            TAG,
            "$source: Rejected protocol version ${message.protocolVersion}; update all phones"
        )
        _state.value = State.Error(TimingProtocolCompatibility.updateRequiredMessage)
        return false
    }

    @SuppressLint("MissingPermission")
    private fun beginSyncConnectionPriorityWindow(): Long {
        val generation = syncConnectionPriorityGeneration.incrementAndGet()
        beginLowLatencyClientHold("requester_clock_sync")
        return generation
    }

    /**
     * Pixel's initial preferred-parameter update can restore the default 30 ms
     * interval shortly after our first priority request. Reassert only during
     * the bounded setup window, after that startup update has had time to land.
     */
    @SuppressLint("MissingPermission")
    private fun beginLowLatencyClientHold(reason: String) {
        requestLowLatencyClientLink(reason)
        if (gattClient == null) return
        lowLatencyPriorityReassertJob?.cancel()
        lowLatencyPriorityReassertJob = serviceScope.launch {
            for (delayMs in listOf(600L, 900L, 1_500L)) {
                delay(delayMs)
                if (!isActive || gattClient == null) return@launch
                requestLowLatencyClientLink("${reason}_reassert")
            }
            delay(15_000L)
            val accepted = gattClient?.requestConnectionPriority(
                BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            ) ?: false
            Log.i(TAG, "Client: Low-latency setup window expired; restored balanced priority (accepted=$accepted)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLowLatencyClientLink(reason: String, client: BluetoothGatt? = gattClient) {
        val gatt = client ?: return
        // HIGH is Android's public low-latency request for ordinary GATT apps.
        // CONNECTION_PRIORITY_DCK is reserved for Digital Car Key and must not
        // be used as a general-purpose way to force shorter intervals.
        val requestedPriority = BluetoothGatt.CONNECTION_PRIORITY_HIGH
        val priorityAccepted = runCatching {
            gatt.requestConnectionPriority(requestedPriority)
        }.getOrElse {
            Log.w(TAG, "Client: High-priority BLE request failed ($reason): ${it.message}")
            false
        }
        runCatching {
            gatt.setPreferredPhy(
                // Timing control packets are small, while gate spacing can be
                // long. LE 1M is mandatory and has more range than optional 2M;
                // Wi-Fi Aware, not PHY throughput, is the precision clock lane.
                BluetoothDevice.PHY_LE_1M_MASK,
                BluetoothDevice.PHY_LE_1M_MASK,
                BluetoothDevice.PHY_OPTION_NO_PREFERRED
            )
        }.onFailure {
            Log.w(TAG, "Client: 1M PHY preference failed ($reason): ${it.message}")
        }
        Log.i(
            TAG,
            "Client: Requested low-latency BLE link reason=$reason " +
                "priority=HIGH priorityAccepted=$priorityAccepted phy=1M"
        )
    }

    private fun phyName(phy: Int): String = when (phy) {
        BluetoothDevice.PHY_LE_1M -> "1M"
        BluetoothDevice.PHY_LE_2M -> "2M"
        BluetoothDevice.PHY_LE_CODED -> "coded"
        else -> phy.toString()
    }

    @SuppressLint("MissingPermission")
    private fun endSyncConnectionPriorityWindow(generation: Long) {
        if (!syncConnectionPriorityGeneration.compareAndSet(generation, generation + 1L)) return
        lowLatencyPriorityReassertJob?.cancel()
        lowLatencyPriorityReassertJob = null
        val accepted = gattClient?.requestConnectionPriority(
            BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        ) ?: false
        Log.i(TAG, "Client: Restored balanced BLE priority after clock sync (accepted=$accepted)")
    }

    // ==================== Client Write Queue ====================

    /**
     * Enqueue data for writing to the server (client mode only).
     * BLE allows only one outstanding write at a time.
     */
    private fun writeToServer(
        data: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ) {
        val packets = try {
            BleMessageFramer.packets(data, clientMaximumPacketBytes)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Client: Failed to frame BLE payload: ${e.message}")
            return
        }
        clientWriteQueue.addAll(packets.map { ClientWritePacket(it, writeType) })
        drainClientWriteQueue()
    }

    /**
     * Starts a single-packet write immediately if no older ATT write is queued
     * or in flight. The sync payloads fit in one negotiated 517-byte MTU packet
     * on the physical iPhone/Pixel route. If that is not true, fail closed and
     * let the next sample/probe retry without exposing a stale t1 or t3.
     */
    @SuppressLint("MissingPermission")
    private fun tryWriteTimestampedClientPayloadAtBoundary(
        template: ClockSyncTimestampTemplate,
        writeType: Int,
        pongReceivedAtNanos: Long? = null,
        onTimestampCaptured: (Long) -> Unit = {}
    ): Boolean {
        if (!ClockSyncTransmitBoundaryPolicy.canSubmitServerNotificationDirectly(
                encodedSize = template.encodedSize,
                maximumPacketBytes = clientMaximumPacketBytes,
                queueIsEmpty = clientWriteQueue.isEmpty(),
                notificationPending = isClientWritePending.get()
            ) || !isClientWritePending.compareAndSet(false, true)
        ) {
            return false
        }

        // A normal writer may have won the race just before our CAS. Yield to
        // it so message framing can never be reordered or interleaved.
        if (clientWriteQueue.isNotEmpty()) {
            isClientWritePending.set(false)
            drainClientWriteQueue()
            return false
        }

        val client = gattClient
        val characteristic = rxCharacteristic
        if (client == null || characteristic == null) {
            isClientWritePending.set(false)
            return false
        }

        val sentAtNanos = SystemClock.elapsedRealtimeNanos()
        if (pongReceivedAtNanos != null &&
            !ClockSyncPongSubmissionPolicy.isFresh(pongReceivedAtNanos, sentAtNanos)
        ) {
            isClientWritePending.set(false)
            drainClientWriteQueue()
            Log.d(TAG, "Client: Skipped stale pong before submission")
            return false
        }
        onTimestampCaptured(sentAtNanos)
        // Size was checked before acquiring the lane: one raw JSON ATT value,
        // with no JSON serialization or fragmentation after the timestamp.
        val packet = ClientWritePacket(template.stamp(sentAtNanos), writeType, retryAfterBond = false)
        clientWriteInFlight = packet
        if (!writeCharacteristicCompat(
                client,
                characteristic,
                packet.data,
                packet.writeType
            )
        ) {
            clientWriteInFlight = null
            isClientWritePending.set(false)
            drainClientWriteQueue()
            return false
        }
        Log.d(
            TAG,
            "Client: ${if (pongReceivedAtNanos == null) "ping" else "pong"} submitted " +
                "${(SystemClock.elapsedRealtimeNanos() - sentAtNanos) / 1_000L}us after capture"
        )
        return true
    }

    @SuppressLint("MissingPermission")
    private fun drainClientWriteQueue() {
        if (isClientWritePending.compareAndSet(false, true)) {
            val packet = clientWriteQueue.poll()
            if (packet != null) {
                val client = gattClient
                val char = rxCharacteristic
                if (client == null || char == null) {
                    isClientWritePending.set(false)
                    return
                }
                clientWriteInFlight = packet
                if (!writeCharacteristicCompat(
                        client,
                        char,
                        packet.data,
                        packet.writeType
                    )
                ) {
                    clientWriteInFlight = null
                    clientWriteQueue.addFirst(packet)
                    isClientWritePending.set(false)
                    handler.postDelayed({ drainClientWriteQueue() }, 25L)
                }
            } else {
                isClientWritePending.set(false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        descriptor.value = value
        gatt.writeDescriptor(descriptor)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        characteristic.value = value
        characteristic.writeType = writeType
        gatt.writeCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun notifyCharacteristicChangedCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        server.notifyCharacteristicChanged(
            device,
            characteristic,
            false,
            value
        ) == BluetoothStatusCodes.SUCCESS
    } else {
        characteristic.value = value
        server.notifyCharacteristicChanged(device, characteristic, false)
    }

    // ==================== Cleanup ====================

    @SuppressLint("MissingPermission")
    private fun closeGattServer() {
        acceptingServerConnections.set(false)
        pendingGattService = null
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
        reverseProbes.clear()
        reverseProbeJob?.cancel()
        reverseProbeJob = null
        lastForwardPingReceivedAtNanos.set(0L)
        referenceSyncPingCount.set(0)
        referenceSetupProgressOpen.set(localIsClockReference)
        criticalMessageRetries.clear()
        // Bump seq so any in-flight retries from the old session don't collide
        // with new messages on the receiver. Matches iOS reset to 10000.
        sequenceNumber.set(10_000L)
        if (role == Role.Client) {
            cloudTimingRelay.start(sessionId, deviceId, isHost = false)
        }
    }

    /** Stop setup-progress emissions after the peer validates the reference clock. */
    @SuppressLint("MissingPermission")
    fun markReferenceSyncValidated() {
        referenceSetupProgressOpen.set(false)
        if (localIsClockReference) {
            lowLatencyPriorityReassertJob?.cancel()
            lowLatencyPriorityReassertJob = null
            val accepted = gattClient?.requestConnectionPriority(
                BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            ) ?: false
            Log.i(
                TAG,
                "Reference: Restored balanced BLE priority after validated sync " +
                    "(accepted=$accepted)"
            )
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
        runId: String? = null,
        timingContext: kotlinx.serialization.json.JsonObject? = null
    ): Boolean {
        if (protocolReadyDevices.isEmpty() && gattClient == null && !cloudTimingRelay.isAvailable) {
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
            runId = runId,
            timingContext = timingContext
        )
        return sendRawMessage(message)
    }

    /**
     * Mirror iOS's reserved terminal pre-session failure envelope so a
     * reference phone does not remain forever at 95% after the requester has
     * exhausted the strict clock-quality measurements.
     */
    @SuppressLint("MissingPermission")
    fun sendPreSessionSyncFailure(
        reason: String,
        deviceAddress: String? = null,
        targetDeviceId: String? = null
    ): Boolean {
        val message = TimingMessage.create(
            seq = TimingProtocolSequence.PRE_SESSION_SYNC_FAILED,
            senderId = deviceId,
            sessionId = sessionId,
            payload = TimingPayload.SessionEnded(reason = reason),
            targetDeviceId = targetDeviceId
        )
        val sent = if (deviceAddress != null) {
            sendRawMessageToDevice(
                message,
                connectedDevices[deviceAddress]
                    ?.takeIf { protocolReadyDevices.contains(deviceAddress) }
            )
        } else {
            sendRawMessage(message)
        }
        Log.i(
            TAG,
            "Sent terminal pre-session sync failure " +
                "(seq=${TimingProtocolSequence.PRE_SESSION_SYNC_FAILED}, sent=$sent)"
        )
        return sent
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
        val device = connectedDevices[deviceAddress]
            ?.takeIf { protocolReadyDevices.contains(deviceAddress) }
            ?: run {
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
        runId: String? = null,
        timingContext: kotlinx.serialization.json.JsonObject? = null
    ): Boolean {
        val message = TimingMessage.createCritical(
            seq = sequenceNumber.incrementAndGet(),
            senderId = deviceId,
            sessionId = sessionId,
            payload = payload,
            eventId = eventId,
            targetDeviceId = targetDeviceId,
            runId = runId,
            timingContext = timingContext
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
            ?.takeIf { protocolReadyDevices.contains(deviceAddress) }

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
            role == Role.Server && protocolReadyDevices.isNotEmpty() ->
                protocolReadyDevices
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
                    sendRawMessageToDevice(
                        action.message,
                        connectedDevices[targetAddress]
                            ?.takeIf { protocolReadyDevices.contains(targetAddress) }
                    )
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
        val criticalTag = message.timingDiagnosticTag()

        val cloudQueued = TimingTransportRoutePolicy
            .shouldRelayToCloud(message.payload)
            .let { shouldRelay -> shouldRelay && cloudTimingRelay.send(message) }
        val bleQueued = when (role) {
            Role.Server -> {
                val devices = protocolReadyDevices.mapNotNull(connectedDevices::get)
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
                    val writeType = when (message.payload) {
                        is TimingPayload.SyncPing, is TimingPayload.SyncPong ->
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                    writeToServer(messageData, writeType)
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
        val criticalTag = message.timingDiagnosticTag()

        val queued = device?.let { enqueueServerPayload(it, messageData) } ?: false
        val cloudQueued = TimingTransportRoutePolicy
            .shouldRelayToCloud(message.payload)
            .let { shouldRelay -> shouldRelay && cloudTimingRelay.send(message) }
        if (queued) {
            Log.d(TAG, "Server: Queued $payloadName to ${device?.address} (${messageData.size}B)$criticalTag")
        }
        return queued || cloudQueued
    }

    private fun TimingMessage.timingDiagnosticTag(): String = buildString {
        messageId?.let { append(" [CRITICAL messageId=$it]") }
        eventId?.let { append(" eventId=$it") }
        runId?.let { append(" runId=$it") }
        targetDeviceId?.let { append(" targetDeviceId=$it") }
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
