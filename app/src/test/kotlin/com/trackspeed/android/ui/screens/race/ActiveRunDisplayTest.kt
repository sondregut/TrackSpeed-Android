package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.data.local.entities.RunEntity
import org.junit.Assert.*
import org.junit.Test

class ActiveRunDisplayTest {
    private val last = RunEntity(sessionId = "fixture", runNumber = 3, timeSeconds = 1.234567,
        distance = 10.0, startType = "flying")

    @Test fun newlyStartedRunDoesNotFlashPreviousResultBeforeFirstTick() {
        val display = activeRunDisplay("started", 0.0, last)
        assertEquals(0.0, display.timeSeconds, 0.0)
        assertEquals(4, display.runNumber)
        assertTrue(display.isRunning)
        assertEquals(1.234567, last.timeSeconds, 0.0)
    }

    @Test fun pendingGateAndHostResultsKeepCurrentRunNumberAndTime() {
        for (status in listOf("collecting_gates", "waiting_for_result")) {
            val display = activeRunDisplay(status, 0.987654321, last)
            assertEquals(0.987654321, display.timeSeconds, 0.0)
            assertEquals(4, display.runNumber)
            assertTrue(display.isRunning)
        }
    }

    @Test fun completedResultKeepsItsRunNumberBetweenRepetitions() {
        for (status in listOf("finished", "waiting", "paused")) {
            val display = activeRunDisplay(status, 0.0, last)
            assertEquals(last.timeSeconds, display.timeSeconds, 0.0)
            assertEquals(3, display.runNumber)
            assertFalse(display.isRunning)
        }
    }

    @Test fun authoritativeElapsedValueIsNotRoundedOrReplacedByStoredResult() {
        val display = activeRunDisplay("finished", 1.23456789, last)
        assertEquals(1.23456789, display.timeSeconds, 0.0)
        assertEquals(3, display.runNumber)
    }

    @Test fun emptySessionStartsAtFirstRunAndZero() {
        assertEquals(ActiveRunDisplay(0.0, 1, false), activeRunDisplay("waiting", 0.0, null))
        assertEquals(ActiveRunDisplay(0.0, 1, true), activeRunDisplay("started", 0.0, null))
    }
}
