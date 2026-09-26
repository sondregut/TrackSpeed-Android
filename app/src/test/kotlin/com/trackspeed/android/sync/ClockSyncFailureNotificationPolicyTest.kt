package com.trackspeed.android.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncFailureNotificationPolicyTest {
    @Test
    fun `only an active sync sends one terminal peer failure`() {
        assertTrue(
            ClockSyncFailureNotificationPolicy.shouldNotify(
                ClockSyncManager.ProtocolState.SYNCING,
                alreadyNotified = false
            )
        )
        assertFalse(
            ClockSyncFailureNotificationPolicy.shouldNotify(
                ClockSyncManager.ProtocolState.SYNCING,
                alreadyNotified = true
            )
        )
        assertFalse(
            ClockSyncFailureNotificationPolicy.shouldNotify(
                ClockSyncManager.ProtocolState.HANDSHAKE_COMPLETE,
                alreadyNotified = false
            )
        )
        assertFalse(
            ClockSyncFailureNotificationPolicy.shouldNotify(
                ClockSyncManager.ProtocolState.READY,
                alreadyNotified = false
            )
        )
    }

    @Test
    fun `failure reason states the unchanged accuracy requirement`() {
        val reason = ClockSyncFailureNotificationPolicy.userFacingReason()
        assertTrue(reason.contains("±10 ms"))
        assertTrue(reason.contains("Keep the phones close and retry"))
    }
}
