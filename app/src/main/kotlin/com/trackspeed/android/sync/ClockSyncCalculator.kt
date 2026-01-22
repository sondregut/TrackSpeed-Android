package com.trackspeed.android.sync

import android.os.SystemClock
import kotlin.math.abs

/**
 * NTP-style clock synchronization calculator.
 *
 * Offset convention: t_remote = t_local + offset
 * - Positive offset: local clock is BEHIND remote
 * - Negative offset: local clock is AHEAD of remote
 *
 * Reference: docs/protocols/CLOCK_SYNC_DETAILS.md
 */
class ClockSyncCalculator(
    private val isFullSync: Boolean = true
) {
    private val samples = mutableListOf<SyncSample>()

    /**
     * Single sync sample from NTP-style ping-pong.
     *
     * T1 = Client send time (local)
     * T2 = Server receive time (remote)
     * T3 = Server send time (remote)
     * T4 = Client receive time (local)
     */
    data class SyncSample(
        val t1: Long,  // Nanos - client send
        val t2: Long,  // Nanos - server receive
        val t3: Long,  // Nanos - server send
        val t4: Long   // Nanos - client receive
    ) {
        /**
         * Round-trip time (excluding server processing).
         */
        val rtt: Long get() = (t4 - t1) - (t3 - t2)

        /**
         * Clock offset: positive = client behind server.
         */
        val offset: Long get() = ((t2 - t1) + (t3 - t4)) / 2

        /**
         * Uncertainty is half RTT (worst case).
         */
        val uncertaintyNanos: Long get() = rtt / 2
        val uncertaintyMs: Double get() = uncertaintyNanos / 1_000_000.0
    }

    /**
     * Result of clock synchronization.
     */
    data class SyncResult(
        val offsetNanos: Long,
        val uncertaintyMs: Double,
        val samplesUsed: Int,
        val totalSamples: Int,
        val quality: SyncQuality,
        val minRttMs: Double,
        val maxRttMs: Double,
        val medianRttMs: Double,
        val p50RttMs: Double,
        val p95RttMs: Double
    ) {
        val offsetMs: Double get() = offsetNanos / 1_000_000.0

        /**
         * Jitter = p95 - p50 RTT (measures consistency).
         */
        val jitterMs: Double get() = p95RttMs - p50RttMs

        /**
         * Check if sync quality is acceptable for timing.
         */
        fun isAcceptable(): Boolean = quality >= SyncQuality.FAIR

        /**
         * Check if sync passes validation gate for precision mode.
         * Uses Photo Finish playbook thresholds.
         */
        fun isPrecisionModeValid(): Boolean {
            return minRttMs < ClockSyncConfig.PRECISION_MODE_MIN_RTT_MS &&
                   jitterMs < ClockSyncConfig.PRECISION_MODE_MAX_JITTER_MS &&
                   quality >= ClockSyncConfig.PRECISION_MODE_MIN_QUALITY
        }
    }

    /**
     * Add a sample to the calculator.
     * Samples with RTT above threshold are rejected.
     */
    fun addSample(sample: SyncSample): Boolean {
        val maxRttMs = if (isFullSync) {
            ClockSyncConfig.FULL_SYNC_MAX_RTT_MS
        } else {
            ClockSyncConfig.MINI_SYNC_MAX_RTT_MS
        }

        val rttMs = sample.rtt / 1_000_000.0
        if (rttMs > maxRttMs || rttMs < 0) {
            return false  // Reject high-latency or invalid sample
        }

        samples.add(sample)
        return true
    }

    /**
     * Create sample from timestamps.
     */
    fun createSample(t1: Long, t2: Long, t3: Long): SyncSample {
        val t4 = SystemClock.elapsedRealtimeNanos()
        return SyncSample(t1, t2, t3, t4)
    }

    /**
     * Calculate offset using RTT filtering and median.
     *
     * Algorithm:
     * 1. Sort samples by RTT (lowest = most accurate)
     * 2. Keep lowest 15-20% RTT samples
     * 3. Calculate median offset from filtered samples
     */
    fun calculateOffset(): SyncResult? {
        val minSamples = ClockSyncConfig.FULL_SYNC_MIN_VALID_SAMPLES
        if (samples.size < minSamples) {
            return null
        }

        // Sort by RTT (lowest first)
        val sortedByRtt = samples.sortedBy { it.rtt }

        // Keep lowest 15-20% RTT samples
        val filterPercentile = if (isFullSync) {
            ClockSyncConfig.FULL_SYNC_RTT_FILTER_PERCENTILE
        } else {
            ClockSyncConfig.FULL_SYNC_RTT_FILTER_PERCENTILE
        }

        val filterCount = (samples.size * filterPercentile).toInt()
            .coerceAtLeast(minSamples)
            .coerceAtMost(samples.size)

        val filtered = sortedByRtt.take(filterCount)

        // Calculate median offset (robust to outliers)
        val offsets = filtered.map { it.offset }.sorted()
        val medianOffset = offsets[offsets.size / 2]

        // Calculate RTT statistics from ALL samples (not just filtered)
        // This gives us true jitter across the connection
        val allRtts = samples.map { it.rtt / 1_000_000.0 }.sorted()
        val minRtt = allRtts.minOrNull() ?: 0.0
        val maxRtt = allRtts.maxOrNull() ?: 0.0

        // Calculate percentiles from all samples
        val p50Rtt = allRtts[allRtts.size / 2]
        val p95Index = (allRtts.size * 0.95).toInt().coerceAtMost(allRtts.size - 1)
        val p95Rtt = allRtts[p95Index]

        // Median RTT from filtered samples (used for offset)
        val filteredRtts = filtered.map { it.rtt / 1_000_000.0 }.sorted()
        val medianRtt = filteredRtts[filteredRtts.size / 2]

        // Uncertainty is max half-RTT from filtered samples
        val maxUncertainty = filtered.maxOf { it.uncertaintyMs }

        return SyncResult(
            offsetNanos = medianOffset,
            uncertaintyMs = maxUncertainty,
            samplesUsed = filtered.size,
            totalSamples = samples.size,
            quality = SyncQuality.fromUncertainty(maxUncertainty),
            minRttMs = minRtt,
            maxRttMs = maxRtt,
            medianRttMs = medianRtt,
            p50RttMs = p50Rtt,
            p95RttMs = p95Rtt
        )
    }

    /**
     * Reset calculator for new sync session.
     */
    fun reset() {
        samples.clear()
    }

    /**
     * Get current sample count.
     */
    fun getSampleCount(): Int = samples.size

    /**
     * Get progress (0.0 - 1.0) for full sync.
     */
    fun getProgress(): Float {
        val target = if (isFullSync) {
            ClockSyncConfig.FULL_SYNC_SAMPLES
        } else {
            ClockSyncConfig.MINI_SYNC_SAMPLES
        }
        return (samples.size.toFloat() / target).coerceAtMost(1f)
    }
}

/**
 * Drift tracker for long timing sessions.
 * Tracks clock drift over time and provides prediction.
 */
class DriftTracker {
    private data class OffsetSample(
        val timestamp: Long,    // Local time when measured
        val offset: Long        // Measured offset at that time
    )

    private val history = mutableListOf<OffsetSample>()
    private val maxHistoryDurationNanos = 10L * 60 * 1_000_000_000L  // 10 minutes

    /**
     * Add a new offset measurement.
     */
    fun addMeasurement(localTime: Long, offset: Long) {
        // Prune old samples
        val cutoff = localTime - maxHistoryDurationNanos
        history.removeAll { it.timestamp < cutoff }

        history.add(OffsetSample(localTime, offset))
    }

    /**
     * Calculate drift rate in nanoseconds per second.
     * Positive = remote getting further ahead.
     * Requires at least 30 seconds of data.
     */
    fun calculateDriftRate(): Double? {
        if (history.size < 2) return null

        val duration = history.last().timestamp - history.first().timestamp
        if (duration < 30_000_000_000L) return null  // Need 30+ seconds

        // Linear regression: offset = baseOffset + driftRate * time
        val n = history.size
        val sumX = history.sumOf { it.timestamp.toDouble() }
        val sumY = history.sumOf { it.offset.toDouble() }
        val sumXY = history.sumOf { it.timestamp.toDouble() * it.offset }
        val sumX2 = history.sumOf { it.timestamp.toDouble() * it.timestamp }

        val denominator = n * sumX2 - sumX * sumX
        if (abs(denominator) < 1e-10) return null

        val driftRate = (n * sumXY - sumX * sumY) / denominator

        // Convert from nanos/nano to nanos/second
        return driftRate * 1_000_000_000.0
    }

    /**
     * Predict offset at a future time, accounting for drift.
     */
    fun predictOffset(atTime: Long): Long {
        val lastSample = history.lastOrNull() ?: return 0
        val driftRate = calculateDriftRate() ?: return lastSample.offset

        val elapsed = atTime - lastSample.timestamp
        val driftCorrection = (driftRate * elapsed / 1_000_000_000.0).toLong()

        return lastSample.offset + driftCorrection
    }

    /**
     * Get drift rate in parts per million (ppm).
     * Typical values: 1-50 ppm for modern devices.
     */
    fun getDriftPpm(): Double? {
        val rate = calculateDriftRate() ?: return null
        return rate / 1000.0  // nanos/sec / 1000 = ppm
    }

    fun reset() {
        history.clear()
    }
}
