package com.trackspeed.android.sync

import org.junit.Assert.assertEquals
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

    @Test
    fun `provisional reference pings do not suppress the first session config`() {
        val state = ClockSyncHandshakeReplayPolicy.stateDuringClockMeasurement(
            ClockSyncManager.ProtocolState.AWAITING_CONFIG
        )

        assertEquals(ClockSyncManager.ProtocolState.AWAITING_CONFIG, state)
        assertTrue(ClockSyncHandshakeReplayPolicy.shouldRequestRoleForConfig(state))
    }

    @Test
    fun `pings between config and assignment preserve the pending role handshake`() {
        val state = ClockSyncHandshakeReplayPolicy.stateDuringClockMeasurement(
            ClockSyncManager.ProtocolState.AWAITING_ASSIGNMENT
        )

        assertEquals(ClockSyncManager.ProtocolState.AWAITING_ASSIGNMENT, state)
        assertFalse(ClockSyncHandshakeReplayPolicy.shouldRequestRoleForConfig(state))
    }

    @Test
    fun `early reference validation waits for config and assignment before protocol readiness`() {
        val beforeConfig = ClockSyncHandshakeReplayPolicy.stateAfterReferenceValidation(
            ClockSyncManager.ProtocolState.AWAITING_CONFIG
        )
        assertEquals(ClockSyncManager.ProtocolState.AWAITING_CONFIG, beforeConfig)
        assertTrue(ClockSyncHandshakeReplayPolicy.shouldRequestRoleForConfig(beforeConfig))

        val beforeAssignment = ClockSyncHandshakeReplayPolicy.stateAfterReferenceValidation(
            ClockSyncManager.ProtocolState.AWAITING_ASSIGNMENT
        )
        assertEquals(ClockSyncManager.ProtocolState.AWAITING_ASSIGNMENT, beforeAssignment)

        val afterAssignment = ClockSyncHandshakeReplayPolicy.stateAfterReferenceValidation(
            ClockSyncManager.ProtocolState.HANDSHAKE_COMPLETE
        )
        assertEquals(ClockSyncManager.ProtocolState.READY, afterAssignment)
    }

    @Test
    fun `completed handshake can measure and then validate its reference clock`() {
        val measuring = ClockSyncHandshakeReplayPolicy.stateDuringClockMeasurement(
            ClockSyncManager.ProtocolState.HANDSHAKE_COMPLETE
        )
        assertEquals(ClockSyncManager.ProtocolState.SYNCING, measuring)
        assertEquals(
            ClockSyncManager.ProtocolState.READY,
            ClockSyncHandshakeReplayPolicy.stateAfterReferenceValidation(measuring)
        )
    }

    @Test
    fun `clock events do not complete an idle or unfinished host handshake`() {
        listOf(
            ClockSyncManager.ProtocolState.IDLE,
            ClockSyncManager.ProtocolState.CONNECTED,
            ClockSyncManager.ProtocolState.AWAITING_ROLE
        ).forEach { state ->
            assertEquals(state, ClockSyncHandshakeReplayPolicy.stateDuringClockMeasurement(state))
            assertEquals(state, ClockSyncHandshakeReplayPolicy.stateAfterReferenceValidation(state))
        }
    }

    @Test
    fun `same stable reference reconnect starts fresh sync without restarting on duplicate announcements`() {
        assertTrue(ClockSyncHandshakeReplayPolicy.shouldStartReferenceSync(
            handshakeCompleteOnCurrentLink = false,
            previousReferenceDeviceId = null,
            incomingReferenceDeviceId = "peer-id"
        ))
        assertFalse(ClockSyncHandshakeReplayPolicy.shouldStartReferenceSync(
            handshakeCompleteOnCurrentLink = true,
            previousReferenceDeviceId = "PEER-ID",
            incomingReferenceDeviceId = "peer-id"
        ))
        // A disconnect removes ClientState. Re-admission starts a new handshake
        // even though the same stable device identity remains the clock reference.
        assertTrue(ClockSyncHandshakeReplayPolicy.shouldStartReferenceSync(
            handshakeCompleteOnCurrentLink = false,
            previousReferenceDeviceId = "PEER-ID",
            incomingReferenceDeviceId = "peer-id"
        ))
        assertTrue(ClockSyncHandshakeReplayPolicy.shouldStartReferenceSync(
            handshakeCompleteOnCurrentLink = true,
            previousReferenceDeviceId = "old-reference",
            incomingReferenceDeviceId = "peer-id"
        ))
    }
}
