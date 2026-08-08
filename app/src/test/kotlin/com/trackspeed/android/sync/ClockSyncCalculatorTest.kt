package com.trackspeed.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncCalculatorTest {
    @Test
    fun `positive offset means local client is behind remote clock`() {
        val calculator = ClockSyncCalculator(isFullSync = true)
        repeat(20) { index ->
            assertTrue(
                calculator.addSample(
                    sample(
                        baseNanos = index * 20_000_000L,
                        remoteOffsetNanos = 4_000_000L,
                        outboundNanos = 1_000_000L,
                        inboundNanos = 1_000_000L
                    )
                )
            )
        }

        val result = requireNotNull(calculator.calculateOffset())
        assertEquals(4_000_000L, result.offsetNanos)
        assertEquals(2.0, result.minRttMs, 0.0001)
        assertEquals(1.0, result.uncertaintyMs, 0.0001)
        assertEquals(SyncQuality.EXCELLENT, result.quality)
    }

    @Test
    fun `adaptive min RTT filter rejects asymmetric high latency offsets`() {
        val calculator = ClockSyncCalculator(isFullSync = true)
        repeat(10) { index ->
            calculator.addSample(
                sample(index * 200_000_000L, 3_000_000L, 1_000_000L, 1_000_000L)
            )
        }
        repeat(10) { index ->
            calculator.addSample(
                sample(
                    baseNanos = 3_000_000_000L + index * 200_000_000L,
                    remoteOffsetNanos = 3_000_000L,
                    outboundNanos = 90_000_000L,
                    inboundNanos = 10_000_000L
                )
            )
        }

        val result = requireNotNull(calculator.calculateOffset())
        assertEquals(3_000_000L, result.offsetNanos)
        assertEquals(3, result.samplesUsed)
    }

    @Test
    fun `rejects long reference processing stalls and established offset jumps`() {
        val processingGuard = ClockSyncCalculator()
        assertFalse(
            processingGuard.addSample(
                sample(
                    baseNanos = 0L,
                    remoteOffsetNanos = 0L,
                    outboundNanos = 1_000_000L,
                    inboundNanos = 1_000_000L,
                    processingNanos = 5_000_001L
                )
            )
        )

        val jumpGuard = ClockSyncCalculator(baselineOffsetNanos = 1_000_000L)
        assertFalse(
            jumpGuard.addSample(
                sample(0L, 3_000_001L, 1_000_000L, 1_000_000L)
            )
        )
        assertTrue(
            jumpGuard.addSample(
                sample(0L, 3_000_000L, 1_000_000L, 1_000_000L)
            )
        )
    }

    @Test
    fun `mini sync requires ten valid samples`() {
        val calculator = ClockSyncCalculator(isFullSync = false)
        repeat(9) { index ->
            calculator.addSample(sample(index * 10_000_000L, 0L, 1_000_000L, 1_000_000L))
        }
        assertNull(calculator.calculateOffset())

        calculator.addSample(sample(100_000_000L, 0L, 1_000_000L, 1_000_000L))
        assertTrue(calculator.calculateOffset() != null)
    }

    @Test
    fun `quality ordering never accepts bad as usable or precision`() {
        val poor = resultWithQuality(SyncQuality.POOR)
        val bad = resultWithQuality(SyncQuality.BAD)

        assertTrue(poor.isAcceptable())
        assertFalse(bad.isAcceptable())
        assertFalse(bad.isPrecisionModeValid())
    }

    @Test
    fun `quality minimum comparison follows best to worst enum order`() {
        assertTrue(SyncQuality.EXCELLENT.isAtLeast(SyncQuality.FAIR))
        assertTrue(SyncQuality.FAIR.isAtLeast(SyncQuality.FAIR))
        assertFalse(SyncQuality.POOR.isAtLeast(SyncQuality.FAIR))
        assertFalse(SyncQuality.BAD.isAtLeast(SyncQuality.FAIR))
    }

    @Test
    fun `drift tracker exposes no prediction before a measurement`() {
        val tracker = DriftTracker()

        assertNull(tracker.predictOffsetOrNull(1_000_000_000L))
    }

    @Test
    fun `drift prediction at arm time can be frozen as one exact offset`() {
        val tracker = DriftTracker()
        tracker.addMeasurement(localTime = 0L, offset = 100L)
        tracker.addMeasurement(localTime = 40_000_000_000L, offset = 140L)

        val frozen = tracker.predictOffsetOrNull(50_000_000_000L)
        val laterPrediction = tracker.predictOffsetOrNull(60_000_000_000L)

        assertEquals(150L, frozen)
        assertEquals(160L, laterPrediction)
    }

    private fun sample(
        baseNanos: Long,
        remoteOffsetNanos: Long,
        outboundNanos: Long,
        inboundNanos: Long,
        processingNanos: Long = 100_000L
    ): ClockSyncCalculator.SyncSample {
        val t1 = baseNanos
        val t2 = t1 + outboundNanos + remoteOffsetNanos
        val t3 = t2 + processingNanos
        val t4 = t3 - remoteOffsetNanos + inboundNanos
        return ClockSyncCalculator.SyncSample(t1, t2, t3, t4)
    }

    private fun resultWithQuality(quality: SyncQuality) = ClockSyncCalculator.SyncResult(
        offsetNanos = 0L,
        uncertaintyMs = quality.maxUncertaintyMs,
        samplesUsed = 10,
        totalSamples = 10,
        quality = quality,
        minRttMs = 1.0,
        maxRttMs = 1.0,
        medianRttMs = 1.0,
        p50RttMs = 1.0,
        p95RttMs = 1.0
    )
}
