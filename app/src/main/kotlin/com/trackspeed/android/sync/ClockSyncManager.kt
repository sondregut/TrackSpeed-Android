package com.trackspeed.android.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Map BLE service state to high-level sync state
        bleClockSyncService.state
            .onEach { bleState ->
                _syncState.value = when (bleState) {
                    BleClockSyncService.State.Idle -> SyncState.NotSynced
                    BleClockSyncService.State.Scanning -> SyncState.WaitingForPeer
                    BleClockSyncService.State.Connecting -> SyncState.Connecting
                    BleClockSyncService.State.Connected -> {
                        if (_isServer.value) SyncState.WaitingForPeer
                        else SyncState.Connecting
                    }
                    is BleClockSyncService.State.Syncing -> SyncState.Syncing(bleState.progress)
                    is BleClockSyncService.State.Synced -> {
                        // Record sync timestamp and offset for drift tracking
                        val now = SystemClock.elapsedRealtimeNanos()
                        syncTimestampNanos = now
                        driftTracker.addMeasurement(now, bleState.result.offsetNanos)
                        SyncState.Synced(
                            offsetMs = bleState.result.offsetMs,
                            quality = bleState.result.quality,
                            uncertaintyMs = bleState.result.uncertaintyMs
                        )
                    }
                    is BleClockSyncService.State.Error -> SyncState.Error(bleState.message)
                }
            }
            .launchIn(scope)
    }

    /**
     * Start as the reference clock (server).
     * Other devices will sync their clocks to this device.
     */
    fun startAsServer() {
        Log.i(TAG, "Starting as sync server (reference clock)")
        _isServer.value = true
        driftTracker.reset()
        bleClockSyncService.startAsServer()
    }

    /**
     * Start as a client that syncs to the server.
     * Will scan for a server and perform clock sync.
     */
    fun startAsClient() {
        Log.i(TAG, "Starting as sync client")
        _isServer.value = false
        driftTracker.reset()
        bleClockSyncService.startAsClient()
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
     * Stop sync operations.
     */
    fun stop() {
        Log.i(TAG, "Stopping clock sync")
        stopPeriodicRefresh()
        bleClockSyncService.stop()
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
