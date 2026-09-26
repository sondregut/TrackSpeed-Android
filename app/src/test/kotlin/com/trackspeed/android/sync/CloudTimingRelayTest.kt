package com.trackspeed.android.sync

import com.trackspeed.android.protocol.TIMING_PROTOCOL_VERSION
import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.protocol.TimingRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudTimingRelayTest {
    @Test
    fun `broadcast envelope matches iOS payload wrapper and round trips`() {
        val message = TimingMessage(
            protocolVersion = TIMING_PROTOCOL_VERSION,
            seq = 99,
            senderId = "android-device",
            sessionId = "shared-session",
            messageId = "stable-message",
            eventId = "stable-event",
            targetDeviceId = "ios-device",
            runId = "run-id",
            payload = TimingPayload.CrossingEvent(
                gateId = "android-device",
                role = TimingRole.START_LINE,
                gateIndex = 0,
                timestampNanos = 123_456_789L,
                confidence = 0.96,
                thumbnailData = null
            ),
            createdAtNanos = 123_000_000L
        )

        val wrapper = CloudTimingRelay.encodeBroadcastPayload(message)

        assertEquals(setOf("payload"), wrapper.keys)
        assertEquals(message, CloudTimingRelay.decodeBroadcastPayload(wrapper))
    }

    @Test
    fun `malformed broadcast is ignored`() {
        assertNull(
            CloudTimingRelay.decodeBroadcastPayload(
                kotlinx.serialization.json.buildJsonObject {
                    put("unexpected", kotlinx.serialization.json.JsonPrimitive(true))
                }
            )
        )
    }
}
