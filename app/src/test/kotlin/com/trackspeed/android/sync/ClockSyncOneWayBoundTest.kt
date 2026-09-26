package com.trackspeed.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncOneWayBoundTest {
    @Test
    fun `bidirectional one way observations remove asymmetric BLE RTT floor`() {
        val calculator = ClockSyncCalculator(isFullSync = true)
        val trueOffset = -15_000_000L
        repeat(20) { index ->
            val outbound = (2L + index % 6) * 1_000_000L
            calculator.addSample(
                sample(
                    baseNanos = 1_000_000_000L + index * 100_000_000L,
                    remoteOffsetNanos = trueOffset,
                    outboundNanos = outbound,
                    inboundNanos = 35_000_000L
                )
            )
        }
        val baseline = requireNotNull(calculator.calculateOffset())
        assertFalse(baseline.isSetupAcceptable())

        val reverse = (0 until 10).map { index ->
            val remoteSend = 5_000_000_000L + index * 90_000_000L
            val reverseDelay = (1L + index % 5) * 1_000_000L
            ClockSyncReverseProbe(
                probeId = "probe-$index",
                remoteSendNanos = remoteSend,
                localReceiveNanos = remoteSend - trueOffset + reverseDelay
            )
        }
        val selected = ClockSyncOneWayBoundPolicy.selectIfTighter(
            baseline = baseline,
            forwardIntervals = calculator.acceptedSamples().map { ClockSyncForwardInterval(it) },
            reverseProbes = reverse,
            windowNanos = 5_000_000_000L
        )

        assertEquals(ClockSyncOneWayBoundPolicy.ESTIMATOR_NAME, selected.estimator)
        assertTrue(selected.isSetupAcceptable())
        assertTrue(kotlin.math.abs(selected.offsetNanos - trueOffset) <= selected.uncertaintyMs * 1_000_000.0)
        assertTrue(requireNotNull(selected.offsetBoundWidthMs) < baseline.uncertaintyMs * 2.0)
    }

    @Test
    fun `bound fails closed when either direction is undersampled or contradictory`() {
        val forward = List(5) { ClockSyncForwardInterval(-2_000_000L, 2_000_000L) }
        val reverse = List(5) { index ->
            ClockSyncReverseProbe("$index", 10_000_000L, 11_000_000L)
        }
        assertNull(ClockSyncOneWayBoundPolicy.estimate(forward.take(4), reverse, 0L))
        assertNull(ClockSyncOneWayBoundPolicy.estimate(forward, reverse.take(4), 0L))

        val contradictory = List(5) { index ->
            ClockSyncReverseProbe("bad-$index", 20_000_000L, 10_000_000L)
        }
        assertNull(ClockSyncOneWayBoundPolicy.estimate(forward, contradictory, 0L))
    }

    @Test
    fun `pooling retry extrema can pass when each independent round narrowly misses`() {
        val firstForward = List(5) { ClockSyncForwardInterval(-40_000_000L, 8_000_000L) }
        val firstReverse = List(5) { index ->
            ClockSyncReverseProbe("first-$index", 100_000_000L, 113_000_000L)
        }
        val secondForward = List(5) { ClockSyncForwardInterval(-40_000_000L, 12_000_000L) }
        val secondReverse = List(5) { index ->
            ClockSyncReverseProbe("second-$index", 200_000_000L, 210_000_000L)
        }

        val first = requireNotNull(
            ClockSyncOneWayBoundPolicy.estimate(firstForward, firstReverse, windowNanos = 0L)
        )
        val second = requireNotNull(
            ClockSyncOneWayBoundPolicy.estimate(secondForward, secondReverse, windowNanos = 0L)
        )
        val pooled = requireNotNull(
            ClockSyncOneWayBoundPolicy.estimate(
                forwardIntervals = firstForward + secondForward,
                reverseProbes = firstReverse + secondReverse,
                windowNanos = 20_000_000_000L
            )
        )

        assertTrue(first.uncertaintyMs >= ClockSyncConfig.SETUP_MAX_UNCERTAINTY_MS)
        assertTrue(second.uncertaintyMs >= ClockSyncConfig.SETUP_MAX_UNCERTAINTY_MS)
        assertTrue(pooled.uncertaintyMs < ClockSyncConfig.SETUP_MAX_UNCERTAINTY_MS)
    }

    @Test
    fun `reverse and launch schedules stay within iOS Bluetooth policy`() {
        assertEquals(60L, ClockSyncReverseProbeSchedulePolicy.nextSpacingMs(0.0))
        assertEquals(100L, ClockSyncReverseProbeSchedulePolicy.nextSpacingMs(0.5))
        assertEquals(140L, ClockSyncReverseProbeSchedulePolicy.nextSpacingMs(1.0))
        assertTrue(
            ClockSyncReverseProbeSchedulePolicy.RESULT_SETTLE_MS >=
                ClockSyncReverseProbeSchedulePolicy.IDLE_TIMEOUT_NANOS / 1_000_000L +
                ClockSyncReverseProbeSchedulePolicy.MAXIMUM_SPACING_MS
        )
        assertEquals(0L, ClockSyncLaunchPhasePolicy.delayMs(0.0))
        assertEquals(30L, ClockSyncLaunchPhasePolicy.delayMs(1.0))
        assertTrue(
            ClockSyncReverseProbeSchedulePolicy.shouldContinue(
                lastForwardPingNanos = 1_000_000_000L,
                nowNanos = 2_000_000_000L,
                sentCount = 0
            )
        )
        assertFalse(
            ClockSyncReverseProbeSchedulePolicy.shouldContinue(
                lastForwardPingNanos = 1_000_000_000L,
                nowNanos = 2_500_000_000L,
                sentCount = 0
            )
        )
    }

    @Test
    fun `reverse probe timestamp is published only for an idle client write lane`() {
        assertTrue(
            ClockSyncTransmitBoundaryPolicy.canSubmitDirectly(
                queueIsEmpty = true,
                writePending = false
            )
        )
        assertFalse(
            ClockSyncTransmitBoundaryPolicy.canSubmitDirectly(
                queueIsEmpty = false,
                writePending = false
            )
        )
        assertFalse(
            ClockSyncTransmitBoundaryPolicy.canSubmitDirectly(
                queueIsEmpty = true,
                writePending = true
            )
        )
        assertTrue(
            ClockSyncTransmitBoundaryPolicy.canSubmitServerNotificationDirectly(
                encodedSize = 300,
                maximumPacketBytes = 512,
                queueIsEmpty = true,
                notificationPending = false
            )
        )
        assertFalse(
            ClockSyncTransmitBoundaryPolicy.canSubmitServerNotificationDirectly(
                encodedSize = 513,
                maximumPacketBytes = 512,
                queueIsEmpty = true,
                notificationPending = false
            )
        )
        assertFalse(
            ClockSyncTransmitBoundaryPolicy.canSubmitServerNotificationDirectly(
                encodedSize = 300,
                maximumPacketBytes = 512,
                queueIsEmpty = false,
                notificationPending = false
            )
        )
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
}
