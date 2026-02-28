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
import javax.inject.Inject
import javax.inject.Singleton

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

    // Drift tracker for long sessions
    private val driftTracker = DriftTracker()

    // Sync age tracking
    private var syncTimestampNanos: Long = 0L

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

    // Safety timeout job: force-sends SessionConfig if ClientReady never arrives
    private var clientReadyTimeoutJob: Job? = null
    private val CLIENT_READY_TIMEOUT_MS = 5000L

    init {
        // Map BLE service state to high-level sync state and drive protocol
        bleClockSyncService.state
            .onEach { bleState ->
                when (bleState) {
                    BleClockSyncService.State.Idle -> {
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
                        _protocolState.value = ProtocolState.CONNECTED
                        _syncState.value = SyncState.Connecting
                        // Resolve role from dual-mode if needed
                        bleClockSyncService.getResolvedRole()?.let { resolvedRole ->
                            _isServer.value = resolvedRole is BleClockSyncService.Role.Server
                        }
                        onBleConnected()
                    }
                    BleClockSyncService.State.ClientReady -> {
                        // Client has enabled notifications — safe to send SessionConfig now
                        onClientReady()
                    }
                    is BleClockSyncService.State.Syncing -> {
                        _protocolState.value = ProtocolState.SYNCING
                        _syncState.value = SyncState.Syncing(bleState.progress)
                    }
                    is BleClockSyncService.State.Synced -> {
                        _protocolState.value = ProtocolState.READY
                        val now = SystemClock.elapsedRealtimeNanos()
                        syncTimestampNanos = now
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
    }

    /**
     * Called when BLE connection is established. Drives the protocol handshake.
     *
     * Host: waits for ClientReady (CCC descriptor write) before sending SessionConfig.
     *       A safety timeout force-sends after 5s if ClientReady never arrives.
     * Joiner: waits for SessionConfig from host.
     */
    private fun onBleConnected() {
        if (_isServer.value) {
            // Host: defer SessionConfig until client enables notifications (ClientReady)
            // This fixes the race condition where the host sends SessionConfig before
            // the client has subscribed to notifications, causing the message to be lost.
            Log.i(TAG, "Host: Connected, waiting for client to enable notifications (ClientReady)...")

            // Safety timeout: force-send SessionConfig if ClientReady never arrives
            // (some BLE stacks don't reliably deliver onDescriptorWriteRequest)
            clientReadyTimeoutJob?.cancel()
            clientReadyTimeoutJob = scope.launch {
                delay(CLIENT_READY_TIMEOUT_MS)
                if (_protocolState.value == ProtocolState.CONNECTED) {
                    Log.w(TAG, "Host: ClientReady timeout (${CLIENT_READY_TIMEOUT_MS}ms) — force-sending SessionConfig")
                    sendSessionConfig()
                }
            }
        } else {
            // Joiner: wait for SessionConfig before sending anything
            Log.i(TAG, "Joiner: Waiting for SessionConfig from host...")
            _protocolState.value = ProtocolState.AWAITING_CONFIG
        }
    }

    /**
     * Called when the client has enabled notifications (CCC descriptor written).
     * Now it's safe to send SessionConfig — the client will receive the notification.
     */
    private fun onClientReady() {
        if (!_isServer.value) return  // Only relevant for host/server

        clientReadyTimeoutJob?.cancel()
        clientReadyTimeoutJob = null

        if (_protocolState.value == ProtocolState.CONNECTED) {
            Log.i(TAG, "Host: ClientReady received — sending SessionConfig now")
            sendSessionConfig()
        } else {
            Log.d(TAG, "Host: ClientReady received but protocol already past CONNECTED (state=${_protocolState.value})")
        }
    }

    /**
     * Send SessionConfig to the connected client. Called either from onClientReady()
     * or from the safety timeout.
     */
    private fun sendSessionConfig() {
        Log.i(TAG, "Host: Sending SessionConfig (distance=${sessionConfig.distance}, " +
            "startType=${sessionConfig.startType}, gates=${sessionConfig.numberOfGates})")
        bleClockSyncService.sendCriticalMessage(
            TimingPayload.SessionConfig(config = sessionConfig)
        )
        _protocolState.value = ProtocolState.AWAITING_ROLE
    }

    /**
     * Handle protocol messages forwarded from BLE transport.
     * Implements the iOS-compatible handshake state machine.
     */
    private fun handleIncomingMessage(message: com.trackspeed.android.protocol.TimingMessage) {
        when (val payload = message.payload) {

            // ── Joiner receives SessionConfig from host ──
            is TimingPayload.SessionConfig -> {
                Log.i(TAG, "Joiner: Received SessionConfig: distance=${payload.config.distance}, " +
                    "startType=${payload.config.startType}, gates=${payload.config.numberOfGates}")
                sessionConfig = payload.config

                // Send SessionConfigAck (non-critical)
                bleClockSyncService.sendMessage(TimingPayload.SessionConfigAck())
                Log.i(TAG, "Joiner: Sent SessionConfigAck")

                // Send RoleRequest (critical — host needs to ACK)
                bleClockSyncService.sendCriticalMessage(
                    TimingPayload.RoleRequest(
                        preferredRole = TimingRole.FINISH_LINE,
                        deviceId = android.os.Build.MODEL
                    )
                )
                Log.i(TAG, "Joiner: Sent RoleRequest (preferred=FINISH_LINE)")
                _protocolState.value = ProtocolState.AWAITING_ASSIGNMENT
                _syncState.value = SyncState.Connecting
            }

            // ── Host receives RoleRequest from joiner ──
            is TimingPayload.RoleRequest -> {
                Log.i(TAG, "Host: Received RoleRequest from ${payload.deviceId}" +
                    (payload.preferredRole?.let { ", preferred=$it" } ?: ""))

                val assignedRole = payload.preferredRole ?: TimingRole.FINISH_LINE

                // Send GateAssigned (critical)
                bleClockSyncService.sendCriticalMessage(
                    TimingPayload.GateAssigned(
                        assignment = GateAssignment(
                            role = assignedRole,
                            gateIndex = if (assignedRole == TimingRole.START_LINE) 0 else 1,
                            distanceFromStart = if (assignedRole == TimingRole.FINISH_LINE) sessionConfig.distance else 0.0,
                            targetDeviceId = payload.deviceId
                        )
                    )
                )
                Log.i(TAG, "Host: Sent GateAssigned (role=${assignedRole.displayName})")

                // Send RoleAssigned (critical)
                bleClockSyncService.sendCriticalMessage(
                    TimingPayload.RoleAssigned(
                        role = assignedRole,
                        targetDeviceId = payload.deviceId
                    )
                )
                Log.i(TAG, "Host: Sent RoleAssigned (role=$assignedRole to ${payload.deviceId})")

                _protocolState.value = ProtocolState.HANDSHAKE_COMPLETE
                _syncState.value = SyncState.Syncing(0f)
                Log.i(TAG, "Host: Handshake complete, waiting for joiner to start clock sync")
            }

            // ── Joiner receives GateAssigned from host ──
            is TimingPayload.GateAssigned -> {
                Log.i(TAG, "Joiner: Received GateAssigned: role=${payload.assignment.role.displayName}, " +
                    "gateIndex=${payload.assignment.gateIndex}")

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
                Log.i(TAG, "Host: Received SyncComplete from peer: " +
                    "offset=${payload.offsetNanos}ns, uncertainty=${payload.uncertaintyMs}ms")

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
                Log.i(TAG, "Host: Received SyncRequest — peer will start clock sync pings")
                _syncState.value = SyncState.Syncing(0f)
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

            else -> {
                Log.d(TAG, "Received unhandled message: ${payload::class.simpleName}")
            }
        }
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
        sessionConfig = config
        _isServer.value = true
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
        _isServer.value = false
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
        sessionConfig = config
        _isServer.value = false  // Will be resolved on connection
        _protocolState.value = ProtocolState.IDLE
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
                if (isSynced()) {
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
        clientReadyTimeoutJob?.cancel()
        clientReadyTimeoutJob = null
        stopPeriodicRefresh()
        stopHeartbeat()
        bleClockSyncService.stop()
        _protocolState.value = ProtocolState.IDLE
        _syncState.value = SyncState.NotSynced
    }

    /**
     * Check if currently synced.
     */
    fun isSynced(): Boolean = _syncState.value is SyncState.Synced

    /**
     * Get the current clock offset in nanoseconds.
     * Returns 0 if not synced.
     */
    fun getOffsetNanos(): Long = bleClockSyncService.getOffsetNanos()

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
        return (bleClockSyncService.syncResult.value)?.quality
    }

    /**
     * Get sync age in seconds.
     * Returns 0 if not synced.
     */
    fun getSyncAgeSeconds(): Long {
        if (syncTimestampNanos == 0L) return 0L
        val now = SystemClock.elapsedRealtimeNanos()
        return (now - syncTimestampNanos) / 1_000_000_000L
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
                "RTT too high (${String.format("%.1f", result.minRttMs)}ms > ${ClockSyncConfig.PRECISION_MODE_MIN_RTT_MS.toInt()}ms)"
            result.jitterMs >= ClockSyncConfig.PRECISION_MODE_MAX_JITTER_MS ->
                "Jitter too high (${String.format("%.1f", result.jitterMs)}ms > ${ClockSyncConfig.PRECISION_MODE_MAX_JITTER_MS.toInt()}ms)"
            result.quality < ClockSyncConfig.PRECISION_MODE_MIN_QUALITY ->
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
        return bleClockSyncService.toRemoteTime(localNanos)
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
        return bleClockSyncService.toLocalTime(remoteNanos)
    }

    /**
     * Convert a local timestamp to remote time with drift correction.
     *
     * Use this for long sessions (> 30 seconds) where clock drift
     * may become significant.
     */
    fun toRemoteTimeWithDrift(localNanos: Long): Long {
        val predictedOffset = driftTracker.predictOffset(localNanos)
        return localNanos + predictedOffset
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
