package com.trackspeed.android.sync

import com.trackspeed.android.protocol.TIMING_PROTOCOL_VERSION
import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalMessageRetryTrackerTest {
    @Test
    fun `retry preserves exact envelope and uses exponential schedule`() {
        var now = 0L
        val tracker = CriticalMessageRetryTracker(
            nowMillis = { now },
            jitterFraction = { 0.0 }
        )
        val message = criticalMessage("message-1")

        assertTrue(tracker.track(message))
        now = 999L
        assertTrue(tracker.poll().isEmpty())

        now = 1_000L
        val first = tracker.poll().single() as CriticalMessageRetryTracker.Action.Retry
        assertEquals(message, first.message)
        assertEquals(1, first.attempt)

        now = 2_999L
        assertTrue(tracker.poll().isEmpty())
        now = 3_000L
        val second = tracker.poll().single() as CriticalMessageRetryTracker.Action.Retry
        assertEquals(message, second.message)
        assertEquals(2, second.attempt)
    }

    @Test
    fun `broadcast remains pending until every recipient acknowledges`() {
        var now = 0L
        val tracker = CriticalMessageRetryTracker({ now }, { 0.0 })
        val firstAddress = CriticalMessageRetryTracker.addressKey("AA:01")
        val secondAddress = CriticalMessageRetryTracker.addressKey("AA:02")
        val message = criticalMessage("message-2")

        tracker.track(
            message,
            CriticalMessageRetryTracker.RetryTarget(
                acknowledgementKeys = setOf(firstAddress, secondAddress)
            )
        )

        assertFalse(tracker.acknowledge(message.messageId!!, setOf(firstAddress)))
        assertEquals(1, tracker.pendingCount())
        assertTrue(tracker.acknowledge(message.messageId, setOf(secondAddress)))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `device id and address keys from one ACK clear one targeted recipient`() {
        var now = 0L
        val tracker = CriticalMessageRetryTracker({ now }, { 0.0 })
        val message = criticalMessage("message-3")
        val expectedKeys = setOf(
            CriticalMessageRetryTracker.deviceIdKey("DEVICE-A"),
            CriticalMessageRetryTracker.addressKey("AA:03")
        )
        tracker.track(
            message,
            CriticalMessageRetryTracker.RetryTarget(acknowledgementKeys = expectedKeys)
        )

        assertTrue(tracker.acknowledge(message.messageId!!, expectedKeys))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `wildcard target clears on first matching message id`() {
        var now = 0L
        val tracker = CriticalMessageRetryTracker({ now }, { 0.0 })
        val message = criticalMessage("message-4")
        tracker.track(message)

        assertTrue(
            tracker.acknowledge(
                message.messageId!!,
                setOf(CriticalMessageRetryTracker.deviceIdKey("host"))
            )
        )
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `message fails only after configured retries`() {
        var now = 0L
        val tracker = CriticalMessageRetryTracker({ now }, { 0.0 })
        val message = criticalMessage("message-5")
        tracker.track(message, maximumRetries = 1, baseRetryIntervalMs = 100L)

        now = 100L
        assertTrue(tracker.poll().single() is CriticalMessageRetryTracker.Action.Retry)
        now = 299L
        assertTrue(tracker.poll().isEmpty())
        now = 300L
        assertTrue(tracker.poll().single() is CriticalMessageRetryTracker.Action.Failed)
        assertEquals(0, tracker.pendingCount())
    }

    private fun criticalMessage(messageId: String) = TimingMessage(
        protocolVersion = TIMING_PROTOCOL_VERSION,
        seq = 42,
        senderId = "sender",
        sessionId = "session",
        messageId = messageId,
        payload = TimingPayload.RoleRequest(
            preferredRole = null,
            deviceId = "sender"
        ),
        createdAtNanos = 123
    )
}
