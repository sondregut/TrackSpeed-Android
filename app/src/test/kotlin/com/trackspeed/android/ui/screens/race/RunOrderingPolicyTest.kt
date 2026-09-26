package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingRole
import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.protocol.SegmentSplit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    @Test
    fun tinyIntentionalSplitsAndLongOrderedRunsRemainValid() {
        assertEquals(1L, orderedTimingSplit(100L, 101L))
        assertEquals(600_000_000_000L, orderedTimingSplit(100L, 600_000_000_100L))
        assertEquals(null, orderedTimingSplit(100L, 100L))
        assertEquals(null, orderedTimingSplit(100L, 99L))
        assertEquals(null, orderedTimingSplit(-1L, 100L))
    }

    @Test
    fun repeatedStartsUseLatestTimestampBeforeFirstFinish() {
        var start: Long? = null
        for (candidate in listOf(100L, 120L, 110L, 130L)) {
            if (acceptsReplacementStart(candidate, start, null, true)) start = candidate
        }
        assertEquals(130L, start)
        assertEquals(10L, orderedTimingSplit(start!!, 140L))
        assertTrue(acceptsReplacementStart(135L, start, 140L, true))
        assertFalse(acceptsReplacementStart(145L, start, 140L, true))
        assertFalse(acceptsReplacementStart(135L, start, null, false))
    }

    @Test fun multiGateResultMustMatchItsOrderedSegments() {
        val first = SegmentSplit(0, 1, "s", "lap", 1L, 10.0, 1L, 10.0)
        val second = SegmentSplit(1, 2, "lap", "f", 2L, 10.0, 3L, 20.0)
        val result = TimingPayload.MultiGateResult(3L, listOf(first, second), 4.0)
        assertTrue(validMultiGateResult(result))
        assertFalse(validMultiGateResult(result.copy(totalSplitNanos = 4L)))
        assertFalse(validMultiGateResult(result.copy(segments = listOf(first, second.copy(splitNanos = -1L)))))
        assertFalse(validMultiGateResult(result.copy(segments = listOf(first, second.copy(fromGateIndex = 0)))))
        assertFalse(validMultiGateResult(result.copy(uncertaintyMs = Double.NaN)))
    }
}
