package com.trackspeed.android.sync

import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingMessageCodec
import com.trackspeed.android.protocol.TimingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncPongSubmissionTest {
    @Test
    fun `t3 at submission excludes real processing while retaining transport uncertainty`() {
        val before = ClockSyncCalculator()
        val after = ClockSyncCalculator()
        repeat(20) { index ->
            val t1 = 1_000_000_000L + index * 100_000_000L
            val remoteOffset = 40_000_000L
            val t2 = t1 + remoteOffset + 9_000_000L
            val actualT3 = t2 + 4_000_000L
            val t4 = actualT3 - remoteOffset + 9_000_000L
            val message = pong(t1, t2)
            val template = TimingMessageCodec.prepareSyncPongTimestampTemplate(message)
            assertTrue(ClockSyncPongSubmissionPolicy.isFresh(t2, actualT3))
            val decoded = TimingMessageCodec.decodeFromBytes(template.stamp(actualT3))
            val payload = decoded.payload as TimingPayload.SyncPong
            assertEquals(message.copy(payload = (message.payload as TimingPayload.SyncPong)
                .copy(t3Nanos = actualT3)), decoded)
            assertTrue(before.addSample(ClockSyncCalculator.SyncSample(t1, t2, t2 + 100_000L, t4)))
            assertTrue(after.addSample(ClockSyncCalculator.SyncSample(payload.t1Nanos,
                payload.t2Nanos, payload.t3Nanos, t4)))
        }
        val old = requireNotNull(before.calculateOffset())
        val corrected = requireNotNull(after.calculateOffset())
        assertEquals(10.95, old.uncertaintyMs, 0.000001)
        assertFalse(old.isSetupAcceptable())
        assertEquals(18.0, corrected.minRttMs, 0.000001)
        assertEquals(9.0, corrected.uncertaintyMs, 0.000001)
        assertEquals(40_000_000L, corrected.offsetNanos)
        assertTrue(corrected.isSetupAcceptable())
    }

    @Test
    fun `processing guard rejects stale or nonmonotonic pongs without widening tolerance`() {
        val received = 1_000_000_000L
        assertTrue(ClockSyncPongSubmissionPolicy.isFresh(received, received + 1L))
        assertTrue(ClockSyncPongSubmissionPolicy.isFresh(received, received + 5_000_000L))
        assertFalse(ClockSyncPongSubmissionPolicy.isFresh(received, received + 5_000_001L))
        assertFalse(ClockSyncPongSubmissionPolicy.isFresh(received, received))
        assertFalse(ClockSyncPongSubmissionPolicy.isFresh(received, received - 1L))
        assertFalse(ClockSyncPongSubmissionPolicy.isFresh(-1L, 1L))
    }

    @Test
    fun `real UUID pong fits one negotiated ATT value without changing its wire envelope`() {
        val message = pong(123_456_789_012_345L, 234_567_890_123_456L)
        val template = TimingMessageCodec.prepareSyncPongTimestampTemplate(message)
        assertTrue(template.encodedSize <= 512)
        assertTrue(canSubmit(template.encodedSize, 512, true, false))
        assertFalse(canSubmit(template.encodedSize, 20, true, false))
        assertFalse(canSubmit(template.encodedSize, 512, false, false))
        assertFalse(canSubmit(template.encodedSize, 512, true, true))
        val bytes = template.stamp(234_567_890_223_456L)
        assertEquals(1, BleMessageFramer.packets(bytes, 512).size)
    }

    @Test
    fun `observed iPhone completion remains outside the strict Android setup gate`() {
        assertFalse(ClockSyncConfig.isSetupUncertaintyAcceptable(11.859443))
        assertFalse(ClockSyncConfig.isSetupUncertaintyAcceptable(10.0))
        assertTrue(ClockSyncConfig.isSetupUncertaintyAcceptable(9.999999))
        assertTrue(ClockSyncConfig.isSetupUncertaintyAcceptable(0.0))
    }

    @Test
    fun `invalid uncertainty never marks a clock ready`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.001)
            .forEach { assertFalse(ClockSyncConfig.isSetupUncertaintyAcceptable(it)) }
    }

    private fun canSubmit(size: Int, capacity: Int, empty: Boolean, pending: Boolean) =
        ClockSyncTransmitBoundaryPolicy.canSubmitServerNotificationDirectly(size, capacity, empty, pending)

    private fun pong(t1: Long, t2: Long): TimingMessage = TimingMessage(
        seq = 10_123,
        senderId = "99999999-9999-4999-8999-999999999999",
        sessionId = "88888888-8888-4888-8888-888888888888",
        payload = TimingPayload.SyncPong(
            pingId = "77777777-7777-4777-8777-777777777777",
            t1Nanos = t1,
            t2Nanos = t2,
            t3Nanos = 0L,
            requesterId = "66666666-6666-4666-8666-666666666666"
        ),
        createdAtNanos = t2
    )
}
