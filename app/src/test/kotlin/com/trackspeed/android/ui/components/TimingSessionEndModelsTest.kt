package com.trackspeed.android.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingSessionEndModelsTest {
    @Test
    fun savedNonGuestSessionWithRunsCanBeOpened() {
        val summary = TimingSessionEndSummary(
            origin = TimingSessionEndOrigin.LOCAL,
            runCount = 3,
            bestTime = 1.273,
            savedSessionId = "session-id",
            isGuest = false
        )

        assertTrue(summary.canViewSession)
    }

    @Test
    fun emptyGuestOrUnsavedSessionCannotBeOpened() {
        assertFalse(
            TimingSessionEndSummary(
                origin = TimingSessionEndOrigin.HOST,
                runCount = 0,
                bestTime = null,
                savedSessionId = "session-id",
                isGuest = false
            ).canViewSession
        )
        assertFalse(
            TimingSessionEndSummary(
                origin = TimingSessionEndOrigin.PARTNER,
                runCount = 2,
                bestTime = 1.5,
                savedSessionId = null,
                isGuest = false
            ).canViewSession
        )
        assertFalse(
            TimingSessionEndSummary(
                origin = TimingSessionEndOrigin.HOST,
                runCount = 2,
                bestTime = 1.5,
                savedSessionId = "session-id",
                isGuest = true
            ).canViewSession
        )
    }
}
