package com.trackspeed.android.sync

import kotlin.math.abs
import kotlin.math.floor

/**
 * Offset interval implied by one NTP-style exchange when one-way delays are
 * non-negative: t3 - t4 <= remote-minus-local offset <= t2 - t1.
 */
data class ClockSyncForwardInterval(
    val lowerBoundNanos: Long,
    val upperBoundNanos: Long
) {
    constructor(sample: ClockSyncCalculator.SyncSample) : this(
        lowerBoundNanos = sample.t3 - sample.t4,
        upperBoundNanos = sample.t2 - sample.t1
    )
}

/** A reference-originated ping observed by the clock-sync requester. */
data class ClockSyncReverseProbe(
    val probeId: String,
    val remoteSendNanos: Long,
    val localReceiveNanos: Long
) {
    val offsetLowerBoundNanos: Long
        get() = remoteSendNanos - localReceiveNanos
}

data class ClockSyncOneWayBoundEstimate(
    val offsetNanos: Long,
    val uncertaintyNanos: Long,
    val lowerBoundNanos: Long,
    val upperBoundNanos: Long,
    val forwardSampleCount: Int,
    val reverseProbeCount: Int
) {
    val widthNanos: Long get() = upperBoundNanos - lowerBoundNanos
    val widthMs: Double get() = widthNanos / 1_000_000.0
    val uncertaintyMs: Double get() = uncertaintyNanos / 1_000_000.0
}

/**
 * Mirrors iOS ClockSyncOneWayBoundPolicy. BLE response traffic is commonly
 * pinned to the next connection event, so RTT/2 alone overstates uncertainty.
 * Independent pings in both directions provide a safe offset bracket without
 * assuming symmetric one-way delay.
 */
object ClockSyncOneWayBoundPolicy {
    const val ESTIMATOR_NAME = "one_way_bounds_bidirectional"
    const val BASELINE_ESTIMATOR_NAME = "rtt_half_plus_mad"
    const val MINIMUM_FORWARD_SAMPLES = 5
    const val MINIMUM_REVERSE_PROBES = 5
    const val FIXED_MARGIN_NANOS = 500_000L
    const val DRIFT_PARTS_PER_MILLION = 20L

    fun driftMarginNanos(windowNanos: Long): Long =
        (windowNanos.coerceAtLeast(0L) / 1_000_000L) * DRIFT_PARTS_PER_MILLION

    fun estimate(
        forwardIntervals: List<ClockSyncForwardInterval>,
        reverseProbes: List<ClockSyncReverseProbe>,
        windowNanos: Long
    ): ClockSyncOneWayBoundEstimate? {
        if (forwardIntervals.size < MINIMUM_FORWARD_SAMPLES ||
            reverseProbes.size < MINIMUM_REVERSE_PROBES ||
            forwardIntervals.any { it.lowerBoundNanos > it.upperBoundNanos }
        ) {
            return null
        }

        val forwardLower = forwardIntervals.maxOf { it.lowerBoundNanos }
        val forwardUpper = forwardIntervals.minOf { it.upperBoundNanos }
        val reverseLower = reverseProbes.maxOf { it.offsetLowerBoundNanos }
        val lowerBound = maxOf(forwardLower, reverseLower)
        if (lowerBound > forwardUpper) return null

        val width = forwardUpper - lowerBound
        val halfWidth = (width + 1L) / 2L
        val midpoint = lowerBound + width / 2L
        val margin = FIXED_MARGIN_NANOS + driftMarginNanos(windowNanos)
        return ClockSyncOneWayBoundEstimate(
            offsetNanos = midpoint,
            uncertaintyNanos = halfWidth + margin,
            lowerBoundNanos = lowerBound,
            upperBoundNanos = forwardUpper,
            forwardSampleCount = forwardIntervals.size,
            reverseProbeCount = reverseProbes.size
        )
    }

    fun isConsistent(
        estimate: ClockSyncOneWayBoundEstimate,
        baselineOffsetNanos: Long,
        baselineUncertaintyNanos: Long
    ): Boolean = abs(estimate.offsetNanos - baselineOffsetNanos) <=
        estimate.uncertaintyNanos + baselineUncertaintyNanos

    fun shouldSelect(
        estimate: ClockSyncOneWayBoundEstimate,
        baselineUncertaintyNanos: Long
    ): Boolean = estimate.uncertaintyNanos < baselineUncertaintyNanos

    fun selectIfTighter(
        baseline: ClockSyncCalculator.SyncResult,
        forwardIntervals: List<ClockSyncForwardInterval>,
        reverseProbes: List<ClockSyncReverseProbe>,
        windowNanos: Long
    ): ClockSyncCalculator.SyncResult {
        val estimate = estimate(forwardIntervals, reverseProbes, windowNanos) ?: return baseline
        val baselineUncertaintyNanos = (baseline.uncertaintyMs * 1_000_000.0).toLong()
        if (!isConsistent(estimate, baseline.offsetNanos, baselineUncertaintyNanos) ||
            !shouldSelect(estimate, baselineUncertaintyNanos)
        ) {
            return baseline
        }

        return baseline.copy(
            offsetNanos = estimate.offsetNanos,
            uncertaintyMs = estimate.uncertaintyMs,
            quality = SyncQuality.fromUncertaintyAndRtt(
                estimate.uncertaintyMs,
                baseline.minRttMs
            ),
            estimator = ESTIMATOR_NAME,
            offsetBoundWidthMs = estimate.widthMs
        )
    }
}

object ClockSyncBoundedSampleConsistencyPolicy {
    fun isConsistent(
        interval: ClockSyncForwardInterval,
        appliedOffsetNanos: Long,
        toleranceNanos: Long
    ): Boolean = interval.lowerBoundNanos - toleranceNanos <= appliedOffsetNanos &&
        appliedOffsetNanos <= interval.upperBoundNanos + toleranceNanos

    fun isConsistent(
        probe: ClockSyncReverseProbe,
        appliedOffsetNanos: Long,
        toleranceNanos: Long
    ): Boolean = probe.offsetLowerBoundNanos <= appliedOffsetNanos + toleranceNanos
}

object ClockSyncReverseProbeSchedulePolicy {
    const val MINIMUM_SPACING_MS = 60L
    const val JITTER_MS = 80L
    const val MAXIMUM_SPACING_MS = MINIMUM_SPACING_MS + JITTER_MS
    const val IDLE_TIMEOUT_NANOS = 1_500_000_000L
    const val RESULT_SETTLE_MS =
        IDLE_TIMEOUT_NANOS / 1_000_000L + MAXIMUM_SPACING_MS + 100L
    const val MAXIMUM_PROBES_PER_BURST = 160

    fun nextSpacingMs(unitRandom: Double): Long {
        val clamped = unitRandom.coerceIn(0.0, 1.0)
        return MINIMUM_SPACING_MS + floor(JITTER_MS * clamped).toLong()
    }

    fun shouldContinue(lastForwardPingNanos: Long?, nowNanos: Long, sentCount: Int): Boolean {
        if (lastForwardPingNanos == null || sentCount >= MAXIMUM_PROBES_PER_BURST) return false
        return (nowNanos - lastForwardPingNanos).coerceAtLeast(0L) < IDLE_TIMEOUT_NANOS
    }
}

/**
 * Reverse one-way probes may only publish a send timestamp when Android can
 * hand the matching packet straight to GATT. Queue residence would widen or
 * invalidate the safe clock-offset bracket.
 */
object ClockSyncTransmitBoundaryPolicy {
    fun canSubmitDirectly(queueIsEmpty: Boolean, writePending: Boolean): Boolean =
        queueIsEmpty && !writePending

    fun canSubmitServerNotificationDirectly(
        encodedSize: Int,
        maximumPacketBytes: Int,
        queueIsEmpty: Boolean,
        notificationPending: Boolean
    ): Boolean = encodedSize <= maximumPacketBytes &&
        queueIsEmpty &&
        !notificationPending
}

object ClockSyncLaunchPhasePolicy {
    const val MAXIMUM_DELAY_MS = 30L

    fun delayMs(unitRandom: Double): Long =
        floor(MAXIMUM_DELAY_MS * unitRandom.coerceIn(0.0, 1.0)).toLong()
}
