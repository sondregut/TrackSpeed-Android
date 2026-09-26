package com.trackspeed.android.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncHandshakeReplayPolicyTest {
    @Test
    fun `initial config requests a role but replay does not restart handshake`() {
        assertTrue(
            ClockSyncHandshakeReplayPolicy.shouldRequestRoleForConfig(
                ClockSyncManager.ProtocolState.AWAITING_CONFIG
            )
        )
        assertFalse(
            ClockSyncHandshakeReplayPolicy.shouldRequestRoleForConfig(
                ClockSyncManager.ProtocolState.AWAITING_ASSIGNMENT
            )
        )
        assertFalse(
            ClockSyncHandshakeReplayPolicy.shouldRequestRoleForConfig(
                ClockSyncManager.ProtocolState.HANDSHAKE_COMPLETE
            )
        )
        assertFalse(
            ClockSyncHandshakeReplayPolicy.shouldRequestRoleForConfig(
                ClockSyncManager.ProtocolState.SYNCING
            )
        )
        assertFalse(
            ClockSyncHandshakeReplayPolicy.shouldRequestRoleForConfig(
                ClockSyncManager.ProtocolState.READY
            )
        )
    }

    @Test
    fun `identity config is replayed only before sender mapping exists`() {
        assertTrue(ClockSyncHandshakeReplayPolicy.shouldReplayConfigForIdentity(null))
        assertFalse(ClockSyncHandshakeReplayPolicy.shouldReplayConfigForIdentity("peer-id"))
    }

    @Test
    fun `completed role request duplicate requires the same mapped sender`() {
        assertTrue(
            ClockSyncHandshakeReplayPolicy.isCompletedDuplicateRoleRequest(
                handshakeComplete = true,
                existingSenderId = "PEER-ID",
                incomingSenderId = "peer-id"
            )
        )
        assertFalse(
            ClockSyncHandshakeReplayPolicy.isCompletedDuplicateRoleRequest(
                handshakeComplete = false,
                existingSenderId = "peer-id",
                incomingSenderId = "peer-id"
            )
        )
        assertFalse(
            ClockSyncHandshakeReplayPolicy.isCompletedDuplicateRoleRequest(
                handshakeComplete = true,
                existingSenderId = null,
                incomingSenderId = "peer-id"
            )
        )
    }
}
