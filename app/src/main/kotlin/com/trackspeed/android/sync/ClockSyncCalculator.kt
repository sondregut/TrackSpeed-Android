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
    private val isFullSync: Boolean = true,
    private val baselineOffsetNanos: Long? = null
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
        fun isAcceptable(): Boolean = quality != SyncQuality.BAD

        /**
         * Check if sync passes validation gate for precision mode.
         * Uses Photo Finish playbook thresholds.
         */
        fun isPrecisionModeValid(): Boolean {
            return minRttMs < ClockSyncConfig.PRECISION_MODE_MIN_RTT_MS &&
                   jitterMs < ClockSyncConfig.PRECISION_MODE_MAX_JITTER_MS &&
                   quality.isAtLeast(ClockSyncConfig.PRECISION_MODE_MIN_QUALITY)
        }
    }

    /**
     * Add a sample to the calculator.
     * Samples with RTT above threshold are rejected.
     */
    @Synchronized
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

        val processingLatency = sample.t3 - sample.t2
        if (processingLatency < 0 || processingLatency > ClockSyncConfig.MAX_PROCESSING_LATENCY_NANOS) {
            return false
        }

        if (baselineOffsetNanos != null &&
            abs(sample.offset - baselineOffsetNanos) > ClockSyncConfig.MAX_OFFSET_JUMP_NANOS
        ) {
            return false
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
    @Synchronized
    fun calculateOffset(): SyncResult? {
        val minSamples = if (isFullSync) {
            ClockSyncConfig.FULL_SYNC_MIN_VALID_SAMPLES
        } else {
            ClockSyncConfig.MINI_SYNC_MIN_VALID_SAMPLES
        }
        if (samples.size < minSamples) {
            return null
        }

        // Match the current iOS estimator: adaptive min-RTT filtering, BLE
        // outlier removal, then the lowest 15% RTT samples.
        val sortedByRtt = samples.sortedBy { it.rtt }
        val minimumRtt = sortedByRtt.first().rtt
        val adaptiveThreshold = maxOf(minimumRtt * 2L, 5_000_000L)
        var workingSamples = samples.filter { it.rtt <= adaptiveThreshold }

        if (workingSamples.size >= 10) {
            val rtts = workingSamples.map { it.rtt.toDouble() }
            val sortedRtts = rtts.sorted()
            val medianRtt = sortedRtts[sortedRtts.size / 2]
            val mean = rtts.average()
            val variance = rtts.sumOf { value ->
                val delta = value - mean
                delta * delta
            } / rtts.size
            val standardDeviation = kotlin.math.sqrt(variance)
            val outlierThreshold = medianRtt + (2.0 * standardDeviation)
            workingSamples = workingSamples.filter { it.rtt.toDouble() <= outlierThreshold }
        }

        if (workingSamples.size < 3) {
            workingSamples = sortedByRtt.take(ClockSyncConfig.FULL_SYNC_MIN_VALID_SAMPLES)
            if (workingSamples.size < 3) return null
        }

        val sortedWorking = workingSamples.sortedBy { it.rtt }

        val filterPercentile = if (isFullSync) {
            ClockSyncConfig.FULL_SYNC_RTT_FILTER_PERCENTILE
        } else {
            ClockSyncConfig.MINI_SYNC_RTT_FILTER_PERCENTILE
        }

        val filterCount = (sortedWorking.size * filterPercentile).toInt()
            .coerceAtLeast(3)
            .coerceAtMost(sortedWorking.size)
        val filtered = sortedWorking.take(filterCount)

        val minRttSample = filtered.first()
        val minRttOffset = minRttSample.offset
        var weightedSum = 0.0
        var totalWeight = 0.0
        filtered.forEach { sample ->
            val weight = 1.0 / maxOf(sample.rtt, 1L).toDouble()
            weightedSum += sample.offset.toDouble() * weight
            totalWeight += weight
        }
        val weightedOffset = (weightedSum / totalWeight).toLong()
        val finalOffset = if (abs(minRttOffset - weightedOffset) > minRttSample.rtt / 2L) {
            weightedOffset
        } else {
            minRttOffset
        }

        // Calculate RTT statistics from ALL samples (not just filtered)
        // This gives us true jitter across the connection
        val allRtts = samples.map { it.rtt / 1_000_000.0 }.sorted()
        val minRtt = allRtts.minOrNull() ?: 0.0
        val maxRtt = allRtts.maxOrNull() ?: 0.0

        // Calculate percentiles from all samples
        val p50Rtt = allRtts[allRtts.size / 2]
        val p95Index = (allRtts.size * 0.95).toInt().coerceAtMost(allRtts.size - 1)
        val p95Rtt = allRtts[p95Index]

        val filteredRttsNanos = filtered.map { it.rtt }.sorted()
        val medianRttNanos = medianOfLongs(filteredRttsNanos)
        val absoluteOffsetDeviations = filtered
            .map { abs(it.offset - finalOffset) }
            .sorted()
        val medianAbsoluteDeviation = medianOfLongs(absoluteOffsetDeviations)
        val uncertaintyMs =
            (minRttSample.rtt / 2L + medianAbsoluteDeviation) / 1_000_000.0

        return SyncResult(
            offsetNanos = finalOffset,
            uncertaintyMs = uncertaintyMs,
            samplesUsed = filtered.size,
            totalSamples = samples.size,
            quality = SyncQuality.fromUncertaintyAndRtt(uncertaintyMs, minRtt),
            minRttMs = minRtt,
            maxRttMs = maxRtt,
            medianRttMs = medianRttNanos / 1_000_000.0,
            p50RttMs = p50Rtt,
            p95RttMs = p95Rtt
        )
    }

    private fun medianOfLongs(sortedValues: List<Long>): Long {
        val middle = sortedValues.size / 2
        return if (sortedValues.size % 2 == 0) {
            (sortedValues[middle - 1] + sortedValues[middle]) / 2L
        } else {
            sortedValues[middle]
        }
    }

    /**
     * Reset calculator for new sync session.
     */
    @Synchronized
    fun reset() {
        samples.clear()
    }

    /**
     * Get current sample count.
     */
    @Synchronized
    fun getSampleCount(): Int = samples.size

    /**
     * Get progress (0.0 - 1.0) for full sync.
     */
    @Synchronized
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
    @Synchronized
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
    @Synchronized
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
    @Synchronized
    fun predictOffset(atTime: Long): Long {
        return predictOffsetOrNull(atTime) ?: 0L
    }

    /** Returns null when no accepted sync measurement exists yet. */
    @Synchronized
    fun predictOffsetOrNull(atTime: Long): Long? {
        val lastSample = history.lastOrNull() ?: return null
        val driftRate = calculateDriftRate() ?: return lastSample.offset

        val elapsed = atTime - lastSample.timestamp
        val driftCorrection = (driftRate * elapsed / 1_000_000_000.0).toLong()

        return lastSample.offset + driftCorrection
    }

    /**
     * Get drift rate in parts per million (ppm).
     * Typical values: 1-50 ppm for modern devices.
     */
    @Synchronized
    fun getDriftPpm(): Double? {
        val rate = calculateDriftRate() ?: return null
        return rate / 1000.0  // nanos/sec / 1000 = ppm
    }

    @Synchronized
    fun reset() {
        history.clear()
    }
}
