package com.trackspeed.android.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.trackspeed.android.protocol.GateAssignment
import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.protocol.TimingRole
import com.trackspeed.android.protocol.TimingSessionConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * High-level manager for clock synchronization.
 *
 * Provides a simplified interface for clock sync operations and
 * maintains the current sync state and offset for use throughout the app.
 *
 * Usage:
 * 1. Call startAsServer() on the reference device
 * 2. Call startAsClient() on other devices
 * 3. Wait for sync to complete (observe syncState)
 * 4. Use toRemoteTime() to convert local timestamps
 */
@Singleton
class ClockSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleClockSyncService: BleClockSyncService
) {
    companion object {
        private const val TAG = "ClockSyncManager"
        private const val MAX_SEQUENCES_PER_SENDER = 500
        private const val MAX_PROCESSED_MESSAGE_IDS = 500
    }

    /**
     * High-level sync state for UI.
     */
    sealed class SyncState {
        object NotSynced : SyncState()
        object WaitingForPeer : SyncState()
        object Connecting : SyncState()
        data class Syncing(val progress: Float) : SyncState()
        data class Synced(
            val offsetMs: Double,
            val quality: SyncQuality,
            val uncertaintyMs: Double
        ) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.NotSynced)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _isServer = MutableStateFlow(false)
    val isServer: StateFlow<Boolean> = _isServer.asStateFlow()

    private val _connectedGateCount = MutableStateFlow(0)
    val connectedGateCount: StateFlow<Int> = _connectedGateCount.asStateFlow()

    private val _syncedGateCount = MutableStateFlow(0)
    val syncedGateCount: StateFlow<Int> = _syncedGateCount.asStateFlow()

    private val _localGateAssignment = MutableStateFlow<GateAssignment?>(null)
    val localGateAssignment: StateFlow<GateAssignment?> = _localGateAssignment.asStateFlow()

    // Per-client handshake state tracking (server mode only)
    data class ClientState(
        val deviceAddress: String,
        val senderId: String? = null,
        val gateIndex: Int,
        val handshakeComplete: Boolean = false,
        val syncComplete: Boolean = false
    )
    private val connectedClients = ConcurrentHashMap<String, ClientState>()
    private val clientClockOffsetsBySender = ConcurrentHashMap<String, Long>()
    private val gateIndexBySenderId = ConcurrentHashMap<String, Int>()
    private val processedMessageIds = linkedSetOf<String>()
    private val receivedSequencesBySender = mutableMapOf<String, MutableSet<Long>>()
    private val lastReceivedSessionIdBySender = mutableMapOf<String, String>()
    // Per-client timeout jobs for sending SessionConfig
    private val clientReadyTimeoutJobs = ConcurrentHashMap<String, Job>()

    // Drift tracker for long sessions
    private val driftTracker = DriftTracker()

    // Sync age tracking
    private var syncTimestampNanos: Long = 0L
    private var hybridOffsetNanos: Long? = null
    private data class FrozenSync(
        val offsetNanos: Long,
        val quality: SyncQuality,
        val uncertaintyMs: Double,
        val capturedAtNanos: Long
    )
    private var activeSessionSyncFrozen = false
    private var frozenSync: FrozenSync? = null

    // Supabase session ID for cross-platform thumbnail/crossing sync
    private val _supabaseSessionId = MutableStateFlow<String?>(null)
    val supabaseSessionId: StateFlow<String?> = _supabaseSessionId.asStateFlow()

    // Mini-sync refresh job
    private var miniSyncJob: Job? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Protocol handshake state machine.
     *
     * iOS protocol sequence:
     *   1. HOST  → SessionConfig         (critical, needs ACK)
     *   2. JOINER → SessionConfigAck     (non-critical)
     *   3. JOINER → RoleRequest          (critical, needs ACK)
     *   4. HOST  → ACK(roleRequest)      (auto, for messageId)
     *   5. HOST  → GateAssigned          (critical, needs ACK)
     *   6. HOST  → RoleAssigned          (critical, needs ACK)
     *   7. JOINER → GateAssignedAck      (non-critical)
     *   8. JOINER → ACK(gateAssigned)    (auto, for messageId)
     *   ── Handshake complete ──
     *   9. FINISH phone → startFullSync()
     *  10. START phone responds with pongs
     */
    enum class ProtocolState {
        IDLE,
        CONNECTED,           // BLE link up, waiting to start handshake
        AWAITING_CONFIG,     // Joiner: waiting for SessionConfig from host
        AWAITING_ROLE,       // Host: sent config, waiting for RoleRequest
        AWAITING_ASSIGNMENT, // Joiner: sent RoleRequest, waiting for assignment
        HANDSHAKE_COMPLETE,  // Both: assignment done, ready for sync
        SYNCING,             // Clock sync in progress
        READY                // Fully synced, ready for timing
    }

    private val _protocolState = MutableStateFlow(ProtocolState.IDLE)
    val protocolState: StateFlow<ProtocolState> = _protocolState.asStateFlow()

    // Session configuration for host mode (set via startAsServer overload)
    private var sessionConfig: TimingSessionConfig = TimingSessionConfig(
        distance = ClockSyncConfig.DEFAULT_SESSION_DISTANCE,
        startType = ClockSyncConfig.DEFAULT_SESSION_START_TYPE,
        numberOfGates = ClockSyncConfig.DEFAULT_SESSION_NUMBER_OF_GATES,
        hostRole = TimingRole.START_LINE
    )

    private val CLIENT_READY_TIMEOUT_MS = 5000L

    init {
        // Map BLE service state to high-level sync state and drive protocol
        bleClockSyncService.state
            .onEach { bleState ->
                when (bleState) {
                    BleClockSyncService.State.Idle -> {
                        hybridOffsetNanos = null
                        _protocolState.value = ProtocolState.IDLE
                        _syncState.value = SyncState.NotSynced
                    }
                    BleClockSyncService.State.Pairing -> {
                        _syncState.value = SyncState.WaitingForPeer
                    }
                    BleClockSyncService.State.Scanning -> {
                        _syncState.value = SyncState.WaitingForPeer
                    }
                    BleClockSyncService.State.Connecting -> {
                        _syncState.value = SyncState.Connecting
                    }
                    BleClockSyncService.State.Connected -> {
                        _syncState.value = SyncState.Connecting
                        // Resolve role from dual-mode if needed
                        bleClockSyncService.getResolvedRole()?.let { resolvedRole ->
                            _isServer.value = resolvedRole is BleClockSyncService.Role.Server
                            _localGateAssignment.value = if (_isServer.value) {
                                hostGateAssignment(sessionConfig)
                            } else {
                                null
                            }
                        }
                        // For client mode, drive protocol from state (single server)
                        if (!_isServer.value) {
                            _protocolState.value = ProtocolState.CONNECTED
                            onBleConnectedAsClient()
                        }
                        // Server mode: per-client handling via connectionEvents below
                    }
                    BleClockSyncService.State.ClientReady -> {
                        // Handled per-device via clientReadyDevices flow below
                    }
                    is BleClockSyncService.State.Syncing -> {
                        _protocolState.value = ProtocolState.SYNCING
                        _syncState.value = SyncState.Syncing(bleState.progress)
                    }
                    is BleClockSyncService.State.Synced -> {
                        val frozen = frozenSync
                        if (activeSessionSyncFrozen && frozen != null) {
                            Log.i(TAG, "Ignoring active-session sync candidate; keeping frozen pre-session offset")
                            _protocolState.value = ProtocolState.READY
                            _syncState.value = frozen.toSyncState()
                        } else {
                            _protocolState.value = ProtocolState.READY
                            val now = SystemClock.elapsedRealtimeNanos()
                            syncTimestampNanos = now
                            hybridOffsetNanos = null
                            driftTracker.addMeasurement(now, bleState.result.offsetNanos)
                            _syncState.value = SyncState.Synced(
                                offsetMs = bleState.result.offsetMs,
                                quality = bleState.result.quality,
                                uncertaintyMs = bleState.result.uncertaintyMs
                            )
                            // Client: notify server that sync is complete
                            if (!_isServer.value) {
                                bleClockSyncService.sendMessage(
                                    TimingPayload.SyncComplete(
                                        offsetNanos = bleState.result.offsetNanos,
                                        uncertaintyMs = bleState.result.uncertaintyMs
                                    )
                                )
                                Log.i(TAG, "Client: Sent SyncComplete to server")
                            }
                        }
                        // Start heartbeat so iOS peer doesn't mark us as stale
                        startHeartbeat()
                    }
                    is BleClockSyncService.State.Error -> {
                        _syncState.value = SyncState.Error(bleState.message)
                    }
                }
            }
            .launchIn(scope)

        // Handle incoming protocol messages (handshake, sync status)
        bleClockSyncService.incomingMessages
            .onEach { message -> handleIncomingMessage(message) }
            .launchIn(scope)

        // Server mode: track per-client connections
        bleClockSyncService.connectionEvents
            .onEach { event ->
                val resolvedAsServer = _isServer.value ||
                    bleClockSyncService.getResolvedRole() is BleClockSyncService.Role.Server
                if (!resolvedAsServer) return@onEach
                _isServer.value = true
                if (event.connected) {
                    onClientConnected(event.device.address)
                } else {
                    onClientDisconnected(event.device.address)
                }
            }
            .launchIn(scope)

        // Server mode: track per-client notification readiness
        bleClockSyncService.clientReadyDevices
            .onEach { deviceAddress ->
                val resolvedAsServer = _isServer.value ||
                    bleClockSyncService.getResolvedRole() is BleClockSyncService.Role.Server
                if (!resolvedAsServer) return@onEach
                _isServer.value = true
                onClientReady(deviceAddress)
            }
            .launchIn(scope)
    }

    /**
     * Called when this device connects as a client (joiner).
     */
    private fun onBleConnectedAsClient() {
        Log.i(TAG, "Joiner: Waiting for SessionConfig from host...")
        _protocolState.value = ProtocolState.AWAITING_CONFIG
    }

    /**
     * Server mode: called when a new client connects via BLE.
     * Registers the client and starts a timeout to send SessionConfig.
     */
    private fun onClientConnected(deviceAddress: String) {
        val gateIndex = allocateNextClientGateIndex()
        connectedClients[deviceAddress] = ClientState(
            deviceAddress = deviceAddress,
            gateIndex = gateIndex
        )
        updateHostGateCounts()
        Log.i(TAG, "Host: Client $deviceAddress registered as gate $gateIndex " +
            "(${connectedClients.size} client(s), ${_connectedGateCount.value} total gates)")

        // Safety timeout: force-send SessionConfig if ClientReady never arrives
        clientReadyTimeoutJobs[deviceAddress]?.cancel()
        clientReadyTimeoutJobs[deviceAddress] = scope.launch {
            delay(CLIENT_READY_TIMEOUT_MS)
            if (connectedClients[deviceAddress]?.handshakeComplete == false) {
                Log.w(TAG, "Host: ClientReady timeout for $deviceAddress — force-sending SessionConfig")
                sendSessionConfigToDevice(deviceAddress)
            }
        }
    }

    /**
     * Server mode: called when a client disconnects.
     */
    private fun onClientDisconnected(deviceAddress: String) {
        val disconnectedSenderId = connectedClients[deviceAddress]?.senderId
        connectedClients.remove(deviceAddress)
        if (disconnectedSenderId != null) {
            clientClockOffsetsBySender.remove(disconnectedSenderId)
        }
        clientReadyTimeoutJobs.remove(deviceAddress)?.cancel()
        updateHostGateCounts()
        Log.i(TAG, "Host: Client $deviceAddress disconnected (${connectedClients.size} client(s) remaining)")
    }

    /**
     * Server mode: called when a specific client enables notifications (CCC written).
     * Now it's safe to send SessionConfig to that client.
     */
    private fun onClientReady(deviceAddress: String) {
        clientReadyTimeoutJobs.remove(deviceAddress)?.cancel()

        if (connectedClients[deviceAddress]?.handshakeComplete == false) {
            Log.i(TAG, "Host: Client $deviceAddress ready — sending SessionConfig")
            sendSessionConfigToDevice(deviceAddress)
        } else {
            Log.d(TAG, "Host: Client $deviceAddress ready but handshake already complete")
        }
    }

    /**
     * Send SessionConfig to a specific client device.
     */
    private fun sendSessionConfigToDevice(deviceAddress: String) {
        Log.i(TAG, "Host: Sending SessionConfig to $deviceAddress (distance=${sessionConfig.distance}, " +
            "startType=${sessionConfig.startType}, gates=${sessionConfig.numberOfGates}, " +
            "hostPro=${sessionConfig.hostIsProUser})")
        bleClockSyncService.sendCriticalMessageToDevice(
            TimingPayload.SessionConfig(config = sessionConfig),
            deviceAddress
        )
    }

    /**
     * Handle protocol messages forwarded from BLE transport.
     * Implements the iOS-compatible handshake state machine.
     */
    private fun handleIncomingMessage(message: com.trackspeed.android.protocol.TimingMessage) {
        if (!isMessageTargetedToLocalDevice(message)) {
            Log.d(TAG, "Ignoring targeted handshake message for ${targetDescription(message)}")
            return
        }
        if (shouldDropStaleSessionEnvelope(message)) {
            return
        }
        if (shouldDropDuplicateEnvelope(message)) {
            return
        }

        when (val payload = message.payload) {

            // ── Joiner receives SessionConfig from host ──
            is TimingPayload.SessionConfig -> {
                Log.i(TAG, "Joiner: Received SessionConfig: distance=${payload.config.distance}, " +
                    "startType=${payload.config.startType}, gates=${payload.config.numberOfGates}, " +
                    "hostPro=${payload.config.hostIsProUser}")
                sessionConfig = payload.config

                // Send SessionConfigAck (non-critical)
                bleClockSyncService.sendMessage(TimingPayload.SessionConfigAck())
                Log.i(TAG, "Joiner: Sent SessionConfigAck")

                // Send RoleRequest (critical — host needs to ACK)
                bleClockSyncService.sendCriticalMessage(
                    TimingPayload.RoleRequest(
                        preferredRole = TimingRole.FINISH_LINE,
                        deviceId = bleClockSyncService.localDeviceId
                    )
                )
                Log.i(TAG, "Joiner: Sent RoleRequest (preferred=FINISH_LINE)")
                _protocolState.value = ProtocolState.AWAITING_ASSIGNMENT
                _syncState.value = SyncState.Connecting
            }

            // ── Host receives RoleRequest from joiner ──
            is TimingPayload.RoleRequest -> {
                if (!payload.deviceId.equals(message.senderId, ignoreCase = true)) {
                    Log.w(
                        TAG,
                        "Ignoring RoleRequest with sender/device mismatch: " +
                            "sender=${message.senderId.take(8)} device=${payload.deviceId.take(8)}"
                    )
                    return
                }
                Log.i(TAG, "Host: Received RoleRequest from ${payload.deviceId}" +
                    (payload.preferredRole?.let { ", preferred=$it" } ?: ""))

                // Look up the BLE device address for this sender to route per-client
                val senderAddress = bleClockSyncService.getDeviceAddress(message.senderId)
                val clientState = senderAddress?.let { connectedClients[it] }
                val gateIndex = gateIndexBySenderId[message.senderId]
                    ?: clientState?.gateIndex
                    ?: firstAvailableClientGateIndex()
                gateIndexBySenderId[message.senderId] = gateIndex

                // Update client state with senderId mapping
                if (senderAddress != null && clientState != null) {
                    connectedClients[senderAddress] = clientState.copy(
                        senderId = message.senderId,
                        gateIndex = gateIndex
                    )
                }

                val gateAssignment = assignmentForGateIndex(gateIndex)
                val assignedRole = gateAssignment.role

                if (senderAddress != null) {
                    // Send GateAssigned to specific device (critical)
                    bleClockSyncService.sendCriticalMessageToDevice(
                        TimingPayload.GateAssigned(
                            assignment = gateAssignment.copy(targetDeviceId = payload.deviceId)
                        ),
                        senderAddress,
                        targetDeviceId = payload.deviceId
                    )
                    Log.i(TAG, "Host: Sent GateAssigned to $senderAddress " +
                        "(gate=${gateAssignment.gateIndex}, role=$assignedRole, " +
                        "distance=${gateAssignment.distanceFromStart})")

                    // Send RoleAssigned to specific device (critical)
                    bleClockSyncService.sendCriticalMessageToDevice(
                        TimingPayload.RoleAssigned(
                            role = assignedRole,
                            targetDeviceId = payload.deviceId
                        ),
                        senderAddress,
                        targetDeviceId = payload.deviceId
                    )
                    Log.i(TAG, "Host: Sent RoleAssigned to $senderAddress")

                    // Mark this client's handshake complete
                    connectedClients[senderAddress] = connectedClients[senderAddress]!!.copy(
                        handshakeComplete = true
                    )
                    updateHostGateCounts()

                    // Generate and send Supabase session ID (once, shared across all clients)
                    if (_supabaseSessionId.value == null) {
                        val supabaseId = UUID.randomUUID().toString()
                        _supabaseSessionId.value = supabaseId
                    }
                    bleClockSyncService.sendCriticalMessageToDevice(
                        TimingPayload.SupabaseSession(sessionId = _supabaseSessionId.value!!),
                        senderAddress,
                        targetDeviceId = payload.deviceId
                    )
                    Log.i(TAG, "Host: Sent SupabaseSession to $senderAddress")
                } else {
                    // Fallback: broadcast (legacy single-client path)
                    bleClockSyncService.sendCriticalMessage(
                        TimingPayload.GateAssigned(
                            assignment = gateAssignment.copy(targetDeviceId = payload.deviceId)
                        ),
                        targetDeviceId = payload.deviceId
                    )
                    bleClockSyncService.sendCriticalMessage(
                        TimingPayload.RoleAssigned(
                            role = assignedRole,
                            targetDeviceId = payload.deviceId
                        ),
                        targetDeviceId = payload.deviceId
                    )
                    if (_supabaseSessionId.value == null) {
                        _supabaseSessionId.value = UUID.randomUUID().toString()
                    }
                    bleClockSyncService.sendCriticalMessage(
                        TimingPayload.SupabaseSession(sessionId = _supabaseSessionId.value!!),
                        targetDeviceId = payload.deviceId
                    )
                    Log.i(TAG, "Host: Sent handshake via broadcast (no sender address)")
                }

                _protocolState.value = ProtocolState.HANDSHAKE_COMPLETE
                _syncState.value = SyncState.Syncing(0f)
                Log.i(TAG, "Host: Handshake complete for gate $gateIndex, waiting for sync")
            }

            // ── Joiner receives GateAssigned from host ──
            is TimingPayload.GateAssigned -> {
                Log.i(TAG, "Joiner: Received GateAssigned: role=${payload.assignment.role.displayName}, " +
                    "gateIndex=${payload.assignment.gateIndex}")
                _localGateAssignment.value = payload.assignment

                // Send GateAssignedAck (non-critical)
                bleClockSyncService.sendMessage(
                    TimingPayload.GateAssignedAck(gateIndex = payload.assignment.gateIndex)
                )

                // Complete handshake on first of GateAssigned or RoleAssigned
                if (_protocolState.value == ProtocolState.AWAITING_ASSIGNMENT) {
                    completeJoinerHandshake()
                }
            }

            // ── Joiner receives RoleAssigned from host ──
            is TimingPayload.RoleAssigned -> {
                Log.i(TAG, "Joiner: Received RoleAssigned: role=${payload.role}")
                if (_localGateAssignment.value == null) {
                    _localGateAssignment.value = fallbackAssignmentForRole(payload.role)
                }

                // Send RoleAssignedAck (non-critical)
                bleClockSyncService.sendMessage(
                    TimingPayload.RoleAssignedAck(role = payload.role)
                )

                // Complete handshake on first of GateAssigned or RoleAssigned
                if (_protocolState.value == ProtocolState.AWAITING_ASSIGNMENT) {
                    completeJoinerHandshake()
                }
            }

            // ── Host receives SyncComplete from joiner ──
            is TimingPayload.SyncComplete -> {
                val senderAddress = bleClockSyncService.getDeviceAddress(message.senderId)
                Log.i(TAG, "Host: Received SyncComplete from ${senderAddress ?: "unknown"}: " +
                    "offset=${payload.offsetNanos}ns, uncertainty=${payload.uncertaintyMs}ms")

                // Mark this client as sync-complete
                if (senderAddress != null) {
                    connectedClients[senderAddress]?.let {
                        connectedClients[senderAddress] = it.copy(
                            senderId = message.senderId,
                            syncComplete = true
                        )
                    }
                    clientClockOffsetsBySender[message.senderId] = payload.offsetNanos
                    updateHostGateCounts()
                    val syncedCount = connectedClients.values.count { it.syncComplete }
                    Log.i(TAG, "Host: $syncedCount/${connectedClients.size} clients synced")
                }

                val now = SystemClock.elapsedRealtimeNanos()
                syncTimestampNanos = now
                _protocolState.value = ProtocolState.READY
                _syncState.value = SyncState.Synced(
                    offsetMs = 0.0,
                    quality = SyncQuality.fromUncertainty(payload.uncertaintyMs),
                    uncertaintyMs = payload.uncertaintyMs
                )
                Log.i(TAG, "Host: Sync complete (reference clock, offset=0)")
                // Start heartbeat so iOS peer doesn't mark us as stale
                startHeartbeat()
            }

            // ── Host receives SyncRequest ──
            is TimingPayload.SyncRequest -> {
                if (_isServer.value) {
                    Log.i(TAG, "Host: Received SyncRequest — peer will start clock sync pings")
                    markServerPeersAwaitingResync()
                } else {
                    Log.i(TAG, "Peer requested full clock re-sync")
                    invalidateAndResync(reason = "peer request", announceRequest = false)
                }
            }

            // ── Heartbeat ──
            is TimingPayload.HeartbeatPing -> {
                bleClockSyncService.sendMessage(
                    TimingPayload.HeartbeatPong(pingSeq = message.seq)
                )
            }

            // ── ACK messages (logged for debugging) ──
            is TimingPayload.Ack -> {
                Log.d(TAG, "Received ACK for messageId=${payload.messageId.take(8)}")
            }

            is TimingPayload.SessionConfigAck -> {
                Log.i(TAG, "Host: Received SessionConfigAck")
            }

            is TimingPayload.RoleAssignedAck -> {
                Log.i(TAG, "Received RoleAssignedAck: role=${payload.role}")
            }

            is TimingPayload.GateAssignedAck -> {
                Log.i(TAG, "Received GateAssignedAck: gateIndex=${payload.gateIndex}")
            }

            // ── Supabase session ID for cross-platform sync ──
            is TimingPayload.SupabaseSession -> {
                Log.i(TAG, "Received Supabase session ID: ${payload.sessionId}")
                _supabaseSessionId.value = payload.sessionId
            }

            is TimingPayload.HybridSessionInfo -> {
                Log.i(
                    TAG,
                    "Received hybrid session info: ${payload.sessionId}, " +
                        "offset=${payload.clockOffsetNanos}ns, uncertainty=${payload.uncertaintyMs}ms"
                )
                _supabaseSessionId.value = payload.sessionId
                hybridOffsetNanos = payload.clockOffsetNanos
                syncTimestampNanos = SystemClock.elapsedRealtimeNanos()
                driftTracker.addMeasurement(syncTimestampNanos, payload.clockOffsetNanos)
                _protocolState.value = ProtocolState.READY
                _syncState.value = SyncState.Synced(
                    offsetMs = payload.clockOffsetNanos / 1_000_000.0,
                    quality = SyncQuality.fromUncertainty(payload.uncertaintyMs),
                    uncertaintyMs = payload.uncertaintyMs
                )
                startHeartbeat()
            }

            else -> {
                Log.d(TAG, "Received unhandled message: ${payload::class.simpleName}")
            }
        }
    }

    /**
     * Mirror iOS `RaceSession.shouldAdoptIncomingSessionId`: only handshake
     * payloads from the host can re-stamp our envelope sessionId. Anything
     * else stays scoped to whatever sessionId we're currently using.
     */
    private fun shouldAdoptIncomingSessionId(message: com.trackspeed.android.protocol.TimingMessage): Boolean {
        return when (message.payload) {
            is TimingPayload.SessionConfig,
            is TimingPayload.RoleAssigned,
            is TimingPayload.GateAssigned,
            is TimingPayload.SupabaseSession,
            is TimingPayload.HybridSessionInfo -> true
            else -> false
        }
    }

    private fun shouldDropStaleSessionEnvelope(message: com.trackspeed.android.protocol.TimingMessage): Boolean {
        val currentSessionId = bleClockSyncService.currentSessionId
        if (message.sessionId.equals(currentSessionId, ignoreCase = true)) {
            return false
        }

        // Mirror iOS RaceSession: joiners may adopt the host's envelope session
        // from the handshake/bootstrap messages only. Every other foreign
        // session message is stale and must not mutate sync state.
        if (!_isServer.value && shouldAdoptIncomingSessionId(message)) {
            Log.i(
                TAG,
                "Adopting host envelope session ${message.sessionId.take(8)} from ${message.senderId.take(8)}"
            )
            bleClockSyncService.setSessionId(message.sessionId)
            clearIncomingMessageDedupe()
            return false
        }

        Log.w(
            TAG,
            "Ignoring message for different session from ${message.senderId.take(8)}; " +
                "expected=${currentSessionId.take(8)}, got=${message.sessionId.take(8)}"
        )
        return true
    }

    private fun shouldDropDuplicateEnvelope(message: com.trackspeed.android.protocol.TimingMessage): Boolean {
        val previousSessionId = lastReceivedSessionIdBySender[message.senderId]
        if (previousSessionId != null && previousSessionId != message.sessionId) {
            Log.d(TAG, "New handshake message session from ${message.senderId.take(8)}: ${message.sessionId.take(8)}")
            receivedSequencesBySender[message.senderId]?.clear()
        }
        lastReceivedSessionIdBySender[message.senderId] = message.sessionId

        val senderSequences = receivedSequencesBySender.getOrPut(message.senderId) { mutableSetOf() }
        if (!senderSequences.add(message.seq)) {
            Log.d(TAG, "Ignoring duplicate handshake seq=${message.seq} from ${message.senderId.take(8)}")
            return true
        }
        if (senderSequences.size > MAX_SEQUENCES_PER_SENDER) {
            val retained = senderSequences.sorted().takeLast(MAX_SEQUENCES_PER_SENDER)
            senderSequences.clear()
            senderSequences.addAll(retained)
        }

        val messageId = message.messageId
        if (messageId != null) {
            if (!processedMessageIds.add(messageId)) {
                Log.d(TAG, "Ignoring duplicate handshake messageId=${messageId.take(8)}")
                return true
            }
            cleanupProcessedMessageIdsIfNeeded()
        }
        return false
    }

    private fun cleanupProcessedMessageIdsIfNeeded() {
        if (processedMessageIds.size <= MAX_PROCESSED_MESSAGE_IDS) return
        val removeCount = processedMessageIds.size - (MAX_PROCESSED_MESSAGE_IDS / 2)
        val toRemove = processedMessageIds.take(removeCount).toSet()
        processedMessageIds.removeAll(toRemove)
    }

    private fun clearIncomingMessageDedupe() {
        processedMessageIds.clear()
        receivedSequencesBySender.clear()
        lastReceivedSessionIdBySender.clear()
    }

    private fun isMessageTargetedToLocalDevice(message: com.trackspeed.android.protocol.TimingMessage): Boolean {
        val localDeviceId = bleClockSyncService.localDeviceId
        val envelopeTarget = message.targetDeviceId
        if (envelopeTarget != null && envelopeTarget != localDeviceId) {
            return false
        }

        return when (val payload = message.payload) {
            is TimingPayload.RoleAssigned ->
                payload.targetDeviceId == null || payload.targetDeviceId == localDeviceId
            is TimingPayload.GateAssigned ->
                payload.assignment.targetDeviceId == null || payload.assignment.targetDeviceId == localDeviceId
            else -> true
        }
    }

    private fun targetDescription(message: com.trackspeed.android.protocol.TimingMessage): String {
        val payloadTarget = when (val payload = message.payload) {
            is TimingPayload.RoleAssigned -> payload.targetDeviceId
            is TimingPayload.GateAssigned -> payload.assignment.targetDeviceId
            else -> null
        }
        return (message.targetDeviceId ?: payloadTarget ?: "unknown").take(8)
    }

    /**
     * Joiner handshake completion: start NTP clock sync.
     */
    private fun completeJoinerHandshake() {
        Log.i(TAG, "Joiner: Handshake complete, starting NTP clock sync...")
        _protocolState.value = ProtocolState.HANDSHAKE_COMPLETE
        _syncState.value = SyncState.Syncing(0f)

        // Notify server that sync is starting
        bleClockSyncService.sendMessage(TimingPayload.SyncRequest())

        // Start the NTP sync process
        bleClockSyncService.startSync()
    }

    /**
     * Start as the reference clock (server/host).
     * Uses default session configuration.
     */
    fun startAsServer() {
        startAsServer(
            TimingSessionConfig(
                distance = ClockSyncConfig.DEFAULT_SESSION_DISTANCE,
                startType = ClockSyncConfig.DEFAULT_SESSION_START_TYPE,
                numberOfGates = ClockSyncConfig.DEFAULT_SESSION_NUMBER_OF_GATES,
                hostRole = TimingRole.START_LINE
            )
        )
    }

    /**
     * Start as the reference clock (server/host) with specific session config.
     * The config is sent to joiners as the first message after BLE connection.
     */
    fun startAsServer(config: TimingSessionConfig) {
        Log.i(TAG, "Starting as sync server (reference clock): distance=${config.distance}, startType=${config.startType}")
        clearHostClientState()
        clearIncomingMessageDedupe()
        clearFrozenActiveSessionSync()
        gateIndexBySenderId.clear()
        sessionConfig = config
        _isServer.value = true
        _localGateAssignment.value = hostGateAssignment(config)
        _connectedGateCount.value = 1
        _syncedGateCount.value = 1
        _protocolState.value = ProtocolState.IDLE
        driftTracker.reset()
        bleClockSyncService.startAsServer()
    }

    /**
     * Start as a client (joiner) that syncs to the server.
     * Will scan for a server, perform handshake, then clock sync.
     */
    fun startAsClient() {
        Log.i(TAG, "Starting as sync client (joiner)")
        clearHostClientState()
        clearIncomingMessageDedupe()
        clearFrozenActiveSessionSync()
        gateIndexBySenderId.clear()
        _isServer.value = false
        _localGateAssignment.value = null
        _connectedGateCount.value = 0
        _syncedGateCount.value = 0
        _protocolState.value = ProtocolState.IDLE
        driftTracker.reset()
        bleClockSyncService.startAsClient()
    }

    /**
     * Start dual-mode auto-sync: advertise + scan simultaneously.
     * Role is resolved automatically when a peer connects.
     * Uses default session configuration.
     */
    fun startAutoSync() {
        startAutoSync(
            TimingSessionConfig(
                distance = ClockSyncConfig.DEFAULT_SESSION_DISTANCE,
                startType = ClockSyncConfig.DEFAULT_SESSION_START_TYPE,
                numberOfGates = ClockSyncConfig.DEFAULT_SESSION_NUMBER_OF_GATES,
                hostRole = TimingRole.START_LINE
            )
        )
    }

    /**
     * Start dual-mode auto-sync with specific session config.
     * Role is resolved automatically when a peer connects.
     */
    fun startAutoSync(config: TimingSessionConfig) {
        Log.i(TAG, "Starting auto-sync (dual-mode): distance=${config.distance}, startType=${config.startType}")
        clearIncomingMessageDedupe()
        clearFrozenActiveSessionSync()
        sessionConfig = config
        _isServer.value = false  // Will be resolved on connection
        _localGateAssignment.value = null
        _protocolState.value = ProtocolState.IDLE
        connectedClients.clear()
        clientClockOffsetsBySender.clear()
        gateIndexBySenderId.clear()
        _connectedGateCount.value = 0
        _syncedGateCount.value = 0
        driftTracker.reset()
        bleClockSyncService.startDual()
    }

    /**
     * Start periodic mini-sync to maintain accuracy during a race.
     * Refreshes offset every 60 seconds using 30 quick pings.
     */
    fun startPeriodicRefresh() {
        miniSyncJob?.cancel()
        miniSyncJob = scope.launch {
            while (isActive) {
                delay(ClockSyncConfig.MINI_SYNC_REFRESH_INTERVAL_S * 1000)
                if (isSynced() && !activeSessionSyncFrozen) {
                    Log.i(TAG, "Performing periodic mini-sync refresh...")
                    bleClockSyncService.performMiniSync()
                }
            }
        }
    }

    /**
     * Stop periodic refresh.
     */
    fun stopPeriodicRefresh() {
        miniSyncJob?.cancel()
        miniSyncJob = null
    }

    /**
     * Active timing uses one pre-session offset, mirroring iOS RaceSession's
     * frozen clock sync. Later sync attempts may run for diagnostics, but timing
     * conversion keeps using this captured offset until the session resets.
     */
    fun freezeActiveSessionSync(reason: String): Boolean {
        if (activeSessionSyncFrozen) return true

        val currentSynced = _syncState.value as? SyncState.Synced
        val result = bleClockSyncService.syncResult.value
        val measuredOffsetNanos = result?.offsetNanos
            ?: hybridOffsetNanos
            ?: currentSynced?.offsetMs?.times(1_000_000.0)?.roundToLong()
            ?: if (_isServer.value) 0L else null
            ?: return false
        val uncertaintyMs = result?.uncertaintyMs
            ?: currentSynced?.uncertaintyMs
            ?: if (_isServer.value) 0.0 else return false
        val quality = result?.quality
            ?: currentSynced?.quality
            ?: SyncQuality.fromUncertainty(uncertaintyMs)

        val now = SystemClock.elapsedRealtimeNanos()
        // Freeze the effective drift-predicted conversion offset, not only the
        // last raw measurement. Otherwise the pre-arm and active-session time
        // domains can jump by the prediction that was already in effect.
        val offsetNanos = driftTracker.predictOffsetOrNull(now) ?: measuredOffsetNanos
        frozenSync = FrozenSync(
            offsetNanos = offsetNanos,
            quality = quality,
            uncertaintyMs = uncertaintyMs,
            capturedAtNanos = now
        )
        activeSessionSyncFrozen = true
        syncTimestampNanos = now
        stopPeriodicRefresh()
        _protocolState.value = ProtocolState.READY
        _syncState.value = frozenSync!!.toSyncState()
        Log.i(
            TAG,
            "Frozen active-session sync after $reason: " +
                "offset=${String.format(Locale.US, "%.2f", offsetNanos / 1_000_000.0)}ms, " +
                "uncertainty=${String.format(Locale.US, "%.1f", uncertaintyMs)}ms"
        )
        return true
    }

    fun restoreFrozenSyncIfNeeded(reason: String): Boolean {
        val frozen = frozenSync ?: return false
        _protocolState.value = ProtocolState.READY
        _syncState.value = frozen.toSyncState()
        Log.i(TAG, "Restored frozen active-session sync after $reason")
        return true
    }

    fun hasFrozenActiveSessionSync(): Boolean = activeSessionSyncFrozen && frozenSync != null

    fun clearFrozenActiveSessionSync() {
        activeSessionSyncFrozen = false
        frozenSync = null
    }

    fun invalidateAndResync(reason: String, announceRequest: Boolean = true): Boolean {
        if (activeSessionSyncFrozen) {
            restoreFrozenSyncIfNeeded(reason)
            Log.i(TAG, "Ignoring re-sync after $reason; active session uses frozen sync")
            return false
        }

        driftTracker.reset()
        hybridOffsetNanos = null
        syncTimestampNanos = 0L
        _protocolState.value = ProtocolState.SYNCING
        _syncState.value = SyncState.Syncing(0f)

        if (_isServer.value) {
            markServerPeersAwaitingResync()
            if (announceRequest) {
                bleClockSyncService.sendCriticalMessage(TimingPayload.SyncRequest())
            }
            Log.i(TAG, "Reference clock requested peer re-sync after $reason")
            return true
        }

        if (announceRequest) {
            bleClockSyncService.sendMessage(TimingPayload.SyncRequest())
        }
        bleClockSyncService.startSync()
        Log.i(TAG, "Started full clock re-sync after $reason")
        return true
    }

    /**
     * Host-side reconnect replay. Re-sends the authoritative session config and
     * the peer's previous gate/role assignment using the stable protocol senderId.
     */
    fun resendSessionStateToPeer(
        senderId: String,
        supabaseSessionId: String?
    ): Boolean {
        if (!_isServer.value) {
            Log.w(TAG, "Ignoring session-state resend on non-server device")
            return false
        }

        val address = bleClockSyncService.getDeviceAddress(senderId) ?: run {
            Log.w(TAG, "Cannot resend session state: no BLE address for ${senderId.take(8)}")
            return false
        }
        val clientState = connectedClients[address]
        val gateAssignment = clientState?.let { assignmentForGateIndex(it.gateIndex) }

        sendSessionConfigToDevice(address)

        if (gateAssignment != null) {
            bleClockSyncService.sendCriticalMessageToDevice(
                TimingPayload.GateAssigned(
                    assignment = gateAssignment.copy(targetDeviceId = senderId)
                ),
                address,
                targetDeviceId = senderId
            )
            bleClockSyncService.sendCriticalMessageToDevice(
                TimingPayload.RoleAssigned(
                    role = gateAssignment.role,
                    targetDeviceId = senderId
                ),
                address,
                targetDeviceId = senderId
            )
        }

        val effectiveSupabaseSessionId = supabaseSessionId ?: _supabaseSessionId.value
        if (effectiveSupabaseSessionId != null) {
            bleClockSyncService.sendCriticalMessageToDevice(
                TimingPayload.SupabaseSession(sessionId = effectiveSupabaseSessionId),
                address,
                targetDeviceId = senderId
            )
        }

        Log.i(TAG, "Resent session state to ${senderId.take(8)} via $address")
        return true
    }

    /**
     * Start periodic heartbeat so iOS peer doesn't mark us as stale/disconnected.
     * iOS expects heartbeats from connected peers and will degrade the connection
     * health if they stop arriving.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2000)  // Every 2 seconds
                bleClockSyncService.sendMessage(TimingPayload.HeartbeatPing())
            }
        }
        Log.i(TAG, "Heartbeat started (2s interval)")
    }

    /**
     * Stop heartbeat.
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Stop sync operations.
     */
    fun stop() {
        Log.i(TAG, "Stopping clock sync")
        clientReadyTimeoutJobs.values.forEach { it.cancel() }
        clientReadyTimeoutJobs.clear()
        connectedClients.clear()
        clientClockOffsetsBySender.clear()
        gateIndexBySenderId.clear()
        _connectedGateCount.value = 0
        _syncedGateCount.value = 0
        _localGateAssignment.value = null
        stopPeriodicRefresh()
        stopHeartbeat()
        bleClockSyncService.stop()
        _protocolState.value = ProtocolState.IDLE
        _syncState.value = SyncState.NotSynced
        _supabaseSessionId.value = null
        hybridOffsetNanos = null
        clearFrozenActiveSessionSync()
    }

    /**
     * Check if currently synced.
     */
    fun isSynced(): Boolean = _syncState.value is SyncState.Synced

    /**
     * Get the current clock offset in nanoseconds.
     * Returns 0 if not synced.
     */
    fun getOffsetNanos(): Long {
        return frozenSync?.takeIf { activeSessionSyncFrozen }?.offsetNanos
            ?: bleClockSyncService.syncResult.value?.offsetNanos
            ?: hybridOffsetNanos
            ?: 0L
    }

    /**
     * Get the current clock offset in milliseconds.
     * Returns 0 if not synced.
     */
    fun getOffsetMs(): Double = getOffsetNanos() / 1_000_000.0

    /**
     * Get the current sync quality.
     * Returns null if not synced.
     */
    fun getSyncQuality(): SyncQuality? {
        return frozenSync?.takeIf { activeSessionSyncFrozen }?.quality
            ?: (bleClockSyncService.syncResult.value)?.quality
            ?: (_syncState.value as? SyncState.Synced)?.quality
    }

    /**
     * Get sync age in seconds.
     * Returns 0 if not synced.
     */
    fun getSyncAgeSeconds(): Long {
        val timestamp = frozenSync?.takeIf { activeSessionSyncFrozen }?.capturedAtNanos
            ?: syncTimestampNanos
        if (timestamp == 0L) return 0L
        val now = SystemClock.elapsedRealtimeNanos()
        return (now - timestamp) / 1_000_000_000L
    }

    /**
     * Check if sync is stale (older than threshold).
     */
    fun isSyncStale(): Boolean {
        return getSyncAgeSeconds() > ClockSyncConfig.SYNC_STALE_WARNING_SECONDS
    }

    /**
     * Check if precision mode is allowed based on sync validation.
     *
     * Uses Photo Finish playbook thresholds:
     * - Min RTT < 30ms
     * - Jitter (p95-p50) < 10ms
     * - Quality >= FAIR
     *
     * @return true if sync quality is good enough for precision timing
     */
    fun isPrecisionModeAllowed(): Boolean {
        val result = bleClockSyncService.syncResult.value ?: return false
        return result.isPrecisionModeValid()
    }

    /**
     * Get validation failure reason if precision mode not allowed.
     */
    fun getPrecisionModeBlockReason(): String? {
        val result = bleClockSyncService.syncResult.value ?: return "Not synced"

        return when {
            result.minRttMs >= ClockSyncConfig.PRECISION_MODE_MIN_RTT_MS ->
                "RTT too high (${String.format(Locale.US, "%.1f", result.minRttMs)}ms > ${ClockSyncConfig.PRECISION_MODE_MIN_RTT_MS.toInt()}ms)"
            result.jitterMs >= ClockSyncConfig.PRECISION_MODE_MAX_JITTER_MS ->
                "Jitter too high (${String.format(Locale.US, "%.1f", result.jitterMs)}ms > ${ClockSyncConfig.PRECISION_MODE_MAX_JITTER_MS.toInt()}ms)"
            !result.quality.isAtLeast(ClockSyncConfig.PRECISION_MODE_MIN_QUALITY) ->
                "Quality too low (${result.quality} < ${ClockSyncConfig.PRECISION_MODE_MIN_QUALITY})"
            else -> null
        }
    }

    /**
     * Convert a local timestamp to the remote device's time.
     *
     * Use this when sending timestamps to the other device.
     *
     * @param localNanos Local timestamp from SystemClock.elapsedRealtimeNanos()
     * @return Timestamp in remote device's clock reference
     */
    fun toRemoteTime(localNanos: Long): Long {
        return localNanos + getOffsetNanos()
    }

    /**
     * Convert a remote timestamp to local time.
     *
     * Use this when receiving timestamps from the other device.
     *
     * @param remoteNanos Timestamp from the remote device
     * @return Timestamp in local clock reference
     */
    fun toLocalTime(remoteNanos: Long): Long {
        return remoteNanos - getOffsetNanos()
    }

    /**
     * Convert a local timestamp to remote time with drift correction.
     *
     * Use this for long sessions (> 30 seconds) where clock drift
     * may become significant.
     */
    fun toRemoteTimeWithDrift(localNanos: Long): Long {
        frozenSync?.takeIf { activeSessionSyncFrozen }?.let {
            return localNanos + it.offsetNanos
        }
        val predictedOffset = driftTracker.predictOffset(localNanos)
        return localNanos + predictedOffset
    }

    /**
     * Convert a remote timestamp to local time with drift correction.
     *
     * Use this for long sessions where clock drift may be significant.
     * Uses the drift-predicted offset at the current time.
     */
    fun toLocalTimeWithDrift(remoteNanos: Long): Long {
        frozenSync?.takeIf { activeSessionSyncFrozen }?.let {
            return remoteNanos - it.offsetNanos
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val predictedOffset = driftTracker.predictOffset(now)
        return remoteNanos - predictedOffset
    }

    /**
     * Convert a raw client timestamp into the host/reference clock domain.
     *
     * Client sync offsets use the convention:
     * reference_time = client_local_time + offset.
     */
    fun toReferenceTime(senderId: String, senderLocalNanos: Long): Long {
        return senderLocalNanos + (clientClockOffsetsBySender[senderId] ?: 0L)
    }

    private fun hostGateAssignment(config: TimingSessionConfig): GateAssignment {
        return when (config.hostRole) {
            TimingRole.START_LINE -> GateAssignment.start()
            TimingRole.FINISH_LINE -> GateAssignment.finish(
                gateIndex = (config.numberOfGates - 1).coerceAtLeast(1),
                distance = config.distance
            )
            TimingRole.LAP_GATE -> GateAssignment.intermediate(
                gateIndex = 1,
                distanceFromStart = distanceForGateIndex(1, config)
            )
            TimingRole.CONTROL_ONLY -> GateAssignment(
                role = TimingRole.CONTROL_ONLY,
                gateIndex = -1,
                distanceFromStart = 0.0
            )
        }
    }

    private fun allocateNextClientGateIndex(): Int {
        return firstAvailableClientGateIndex()
    }

    private fun firstAvailableClientGateIndex(): Int {
        val gateCount = sessionConfig.numberOfGates.coerceAtLeast(2)
        val hostGateIndex = when (sessionConfig.hostRole) {
            TimingRole.START_LINE -> 0
            TimingRole.FINISH_LINE -> gateCount - 1
            TimingRole.LAP_GATE -> 1
            TimingRole.CONTROL_ONLY -> -1
        }
        val reserved = gateIndexBySenderId.values.toSet()
        return (0 until gateCount).firstOrNull { gateIndex ->
            gateIndex != hostGateIndex && gateIndex !in reserved
        } ?: (0 until gateCount).first { it != hostGateIndex }
    }

    private fun updateHostGateCounts() {
        if (!_isServer.value) return
        _connectedGateCount.value = connectedClients.size + 1
        _syncedGateCount.value = connectedClients.values.count { client ->
            client.handshakeComplete && client.syncComplete
        } + 1
    }

    private fun clearHostClientState() {
        clientReadyTimeoutJobs.values.forEach { it.cancel() }
        clientReadyTimeoutJobs.clear()
        connectedClients.clear()
        clientClockOffsetsBySender.clear()
        gateIndexBySenderId.clear()
    }

    private fun assignmentForGateIndex(rawGateIndex: Int): GateAssignment {
        val gateCount = sessionConfig.numberOfGates.coerceAtLeast(2)
        val gateIndex = rawGateIndex.coerceIn(0, gateCount - 1)
        return when (gateIndex) {
            0 -> GateAssignment.start()
            gateCount - 1 -> GateAssignment.finish(gateIndex, sessionConfig.distance)
            else -> GateAssignment.intermediate(
                gateIndex = gateIndex,
                distanceFromStart = distanceForGateIndex(gateIndex, sessionConfig)
            )
        }
    }

    private fun fallbackAssignmentForRole(role: TimingRole): GateAssignment {
        val gateCount = sessionConfig.numberOfGates.coerceAtLeast(2)
        return when (role) {
            TimingRole.START_LINE -> GateAssignment.start()
            TimingRole.FINISH_LINE -> GateAssignment.finish(gateCount - 1, sessionConfig.distance)
            TimingRole.LAP_GATE -> GateAssignment.intermediate(
                gateIndex = 1,
                distanceFromStart = distanceForGateIndex(1, sessionConfig)
            )
            TimingRole.CONTROL_ONLY -> GateAssignment(
                role = TimingRole.CONTROL_ONLY,
                gateIndex = -1,
                distanceFromStart = 0.0
            )
        }
    }

    private fun distanceForGateIndex(gateIndex: Int, config: TimingSessionConfig): Double {
        val finishIndex = (config.numberOfGates - 1).coerceAtLeast(1)
        return config.distance * gateIndex.toDouble() / finishIndex.toDouble()
    }

    private fun markServerPeersAwaitingResync() {
        clientClockOffsetsBySender.clear()
        connectedClients.keys.toList().forEach { address ->
            connectedClients[address]?.let { state ->
                connectedClients[address] = state.copy(syncComplete = false)
            }
        }
        updateHostGateCounts()
        _protocolState.value = ProtocolState.SYNCING
        _syncState.value = SyncState.Syncing(0f)
    }

    private fun FrozenSync.toSyncState(): SyncState.Synced {
        return SyncState.Synced(
            offsetMs = offsetNanos / 1_000_000.0,
            quality = quality,
            uncertaintyMs = uncertaintyMs
        )
    }

    /**
     * Get the current clock drift rate in parts per million (ppm).
     * Typical values: 1-50 ppm.
     * Returns null if insufficient data (need 30+ seconds).
     */
    fun getDriftPpm(): Double? = driftTracker.getDriftPpm()

    /**
     * Get sync details for display.
     */
    fun getSyncDetails(): SyncDetails? {
        val result = bleClockSyncService.syncResult.value ?: return null
        return SyncDetails(
            offsetMs = result.offsetMs,
            uncertaintyMs = result.uncertaintyMs,
            quality = result.quality,
            samplesUsed = result.samplesUsed,
            totalSamples = result.totalSamples,
            minRttMs = result.minRttMs,
            maxRttMs = result.maxRttMs,
            medianRttMs = result.medianRttMs,
            p50RttMs = result.p50RttMs,
            p95RttMs = result.p95RttMs,
            jitterMs = result.jitterMs,
            isPrecisionModeValid = result.isPrecisionModeValid(),
            syncAgeSeconds = getSyncAgeSeconds(),
            driftPpm = driftTracker.getDriftPpm()
        )
    }

    /**
     * Detailed sync information for debugging/display.
     */
    data class SyncDetails(
        val offsetMs: Double,
        val uncertaintyMs: Double,
        val quality: SyncQuality,
        val samplesUsed: Int,
        val totalSamples: Int,
        val minRttMs: Double,
        val maxRttMs: Double,
        val medianRttMs: Double,
        val p50RttMs: Double,
        val p95RttMs: Double,
        val jitterMs: Double,
        val isPrecisionModeValid: Boolean,
        val syncAgeSeconds: Long,
        val driftPpm: Double?
    )
}

/**
 * Extension function for calculating split times with clock sync.
 *
 * @param startTimeNanos Start crossing time from start device (in remote clock)
 * @param finishTimeNanos Finish crossing time from this device (in local clock)
 * @param clockSyncManager The sync manager to get offset from
 * @return Split time in seconds, or null if not synced
 */
fun calculateSplitTime(
    startTimeNanos: Long,
    finishTimeNanos: Long,
    clockSyncManager: ClockSyncManager
): Double? {
    if (!clockSyncManager.isSynced()) return null

    // Convert start time to local reference
    val localStartTime = clockSyncManager.toLocalTime(startTimeNanos)

    // Calculate split in seconds
    return (finishTimeNanos - localStartTime) / 1_000_000_000.0
}
