package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.protocol.TimingRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceTimingEventPolicyTest {

    @Test
    fun `canonical crossing keeps detector timestamp role and stable identity`() {
        val first = canonicalCrossingEvent(
            runId = "RUN-12345678",
            gateId = "GATE-12345678",
            role = TimingRole.START_LINE,
            gateIndex = 0,
            timestampNanos = 42_000_000L
        )
        val replay = canonicalCrossingEvent(
            runId = "RUN-12345678",
            gateId = "GATE-12345678",
            role = TimingRole.START_LINE,
            gateIndex = 0,
            timestampNanos = 42_000_000L
        )

        assertEquals(first, replay)
        assertEquals(42_000_000L, first.payload.timestampNanos)
        assertEquals(TimingRole.START_LINE, first.payload.role)
        assertEquals(0, first.payload.gateIndex)
    }

    @Test
    fun `crossing and result are separate acked events`() {
        val crossing = canonicalCrossingEvent(
            runId = "RUN-87654321",
            gateId = "GATE-87654321",
            role = TimingRole.FINISH_LINE,
            gateIndex = 1,
            timestampNanos = 84_000_000L
        )
        val resultEventId = canonicalTimingResultEventId(crossing.eventId)

        assertNotEquals(crossing.eventId, resultEventId)

        val crossingMessage = TimingMessage.createCritical(
            seq = 1,
            senderId = "sender",
            sessionId = "session",
            payload = crossing.payload,
            eventId = crossing.eventId,
            runId = "RUN-87654321",
            createdAtNanos = 84_000_000L
        )
        val resultMessage = TimingMessage.createCritical(
            seq = 2,
            senderId = "sender",
            sessionId = "session",
            payload = TimingPayload.TimingResultBroadcast(
                splitNanos = 1_000_000_000L,
                uncertaintyMs = 4.0,
                startGateId = "start",
                finishGateId = "finish"
            ),
            eventId = resultEventId,
            runId = "RUN-87654321",
            createdAtNanos = 1_084_000_000L
        )

        assertTrue(crossingMessage.requiresAck)
        assertTrue(resultMessage.requiresAck)
    }
}
