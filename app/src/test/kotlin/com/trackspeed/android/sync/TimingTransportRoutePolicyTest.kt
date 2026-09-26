package com.trackspeed.android.sync

import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.protocol.TimingRole
import com.trackspeed.android.protocol.TimingSessionConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingTransportRoutePolicyTest {
    @Test
    fun androidCapabilityAndClockPacketsStayLocal() {
        assertFalse(
            TimingTransportRoutePolicy.shouldRelayToCloud(
                TimingPayload.AndroidTransportCapabilities(wifiAwareClockSync = true)
            )
        )
        assertFalse(
            TimingTransportRoutePolicy.shouldRelayToCloud(
                TimingPayload.SyncPing("ping", 1L, "requester")
            )
        )
        assertFalse(
            TimingTransportRoutePolicy.shouldRelayToCloud(
                TimingPayload.SyncPong("ping", 1L, 2L, 3L, "requester")
            )
        )
    }

    @Test
    fun handshakeAndClockReadinessStayOnAuthenticatedLocalTransport() {
        assertFalse(
            TimingTransportRoutePolicy.shouldRelayToCloud(
                TimingPayload.SessionConfig(
                    TimingSessionConfig(
                        distance = 30.0,
                        startType = "flying",
                        numberOfGates = 2,
                        hostRole = TimingRole.START_LINE
                    )
                )
            )
        )
        assertFalse(
            TimingTransportRoutePolicy.shouldRelayToCloud(
                TimingPayload.RoleRequest(
                    preferredRole = TimingRole.FINISH_LINE,
                    deviceId = "joiner"
                )
            )
        )
        assertFalse(
            TimingTransportRoutePolicy.shouldRelayToCloud(
                TimingPayload.Ack(messageId = "message")
            )
        )
        assertFalse(
            TimingTransportRoutePolicy.shouldRelayToCloud(
                TimingPayload.SyncComplete(offsetNanos = 1L, uncertaintyMs = 4.0)
            )
        )
        assertFalse(
            TimingTransportRoutePolicy.shouldRelayToCloud(
                TimingPayload.SupabaseSession(sessionId = "session")
            )
        )
    }

    @Test
    fun timingEventsRetainCloudRedundancy() {
        assertTrue(
            TimingTransportRoutePolicy.shouldRelayToCloud(
                TimingPayload.CrossingEvent(
                    gateId = "finish",
                    role = TimingRole.FINISH_LINE,
                    gateIndex = 1,
                    timestampNanos = 10L,
                    confidence = 1.0
                )
            )
        )
        assertTrue(
            TimingTransportRoutePolicy.shouldRelayToCloud(TimingPayload.StartTiming())
        )
        assertTrue(
            TimingTransportRoutePolicy.shouldRelayToCloud(
                TimingPayload.TimingResultBroadcast(
                    splitNanos = 1_000_000_000L,
                    uncertaintyMs = 4.0,
                    startGateId = "start",
                    finishGateId = "finish"
                )
            )
        )
    }
}
