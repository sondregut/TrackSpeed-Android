package com.trackspeed.android.sync

import com.trackspeed.android.protocol.TimingPayload

/** Payloads that must remain on the authenticated local transport. */
internal object TimingTransportRoutePolicy {
    fun shouldRelayToCloud(payload: TimingPayload): Boolean = when (payload) {
        // Pairing, role assignment, acknowledgements, and clock setup already
        // have an authenticated encrypted GATT route. Sending them through the
        // backup relay during Aware setup creates needless network contention
        // and cannot rescue a peer that has not adopted the session yet.
        is TimingPayload.SessionConfig,
        is TimingPayload.SessionConfigAck,
        is TimingPayload.RoleRequest,
        is TimingPayload.RoleAssigned,
        is TimingPayload.GateAssigned,
        is TimingPayload.RoleAssignedAck,
        is TimingPayload.GateAssignedAck,
        is TimingPayload.Ack,
        is TimingPayload.Nack,
        is TimingPayload.RoleConfirmed,
        is TimingPayload.SyncRequest,
        is TimingPayload.SyncComplete,
        is TimingPayload.SyncPing,
        is TimingPayload.SyncPong,
        is TimingPayload.AndroidTransportCapabilities,
        is TimingPayload.SupabaseSession,
        is TimingPayload.HybridSessionInfo -> false
        else -> true
    }
}
