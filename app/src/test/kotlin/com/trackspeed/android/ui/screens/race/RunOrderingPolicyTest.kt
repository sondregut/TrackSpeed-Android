package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunOrderingPolicyTest {
    @Test
    fun latestStartCrossingWins() {
        assertTrue(crossingCandidateWins(TimingRole.START_LINE, 100L, 110L))
        assertFalse(crossingCandidateWins(TimingRole.START_LINE, 100L, 90L))
    }

    @Test
    fun earliestFinishAndLapCrossingsWin() {
        assertTrue(crossingCandidateWins(TimingRole.FINISH_LINE, 110L, 100L))
        assertFalse(crossingCandidateWins(TimingRole.FINISH_LINE, 100L, 110L))
        assertTrue(crossingCandidateWins(TimingRole.LAP_GATE, 110L, 100L))
    }
}
