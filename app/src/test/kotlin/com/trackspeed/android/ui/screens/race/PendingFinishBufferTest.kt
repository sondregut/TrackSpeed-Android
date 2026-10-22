package com.trackspeed.android.ui.screens.race

import org.junit.Assert.*
import org.junit.Test

class PendingFinishBufferTest {
    @Test fun laterPostrollUpdateCannotOverwriteEarlierFinishEvidence() {
        val buffer=PendingFinishBuffer<String>()
        buffer.add(110,"first")
        buffer.updateLatestEvidence { "$it with postroll" }
        buffer.add(120,"later")
        buffer.updateLatestEvidence { "$it with postroll" }
        assertEquals("first with postroll",buffer.takeFirstAfter(100)?.evidence)
    }

    @Test fun delayedStartSelectsFirstEligibleFinishWithItsOwnEvidence() {
        val buffer = PendingFinishBuffer<String>()
        buffer.add(90, "before-start")
        buffer.add(130, "later-repeat")
        buffer.add(110, "first-finish")
        buffer.add(110, "duplicate-must-not-replace-photo")
        assertEquals(PendingFinishBuffer.Candidate(110L, "first-finish"), buffer.takeFirstAfter(100))
        assertNull(buffer.firstTimestamp)
    }
    @Test fun oldCrossingDoesNotStrandTheRunWhenStartArrives() {
        val buffer = PendingFinishBuffer<String>()
        buffer.add(90, "old")
        assertNull(buffer.takeFirstAfter(100))
        buffer.add(101, "tiny-valid-test")
        assertEquals(101L, buffer.takeFirstAfter(100)?.timestampNanos)
    }
    @Test fun repeatedFinishesCloseOnlyOneRunAndResetClearsEvidence() {
        val buffer = PendingFinishBuffer<String>()
        listOf(110L, 120L, 130L).forEach { buffer.add(it, "$it") }
        assertEquals(110L, buffer.takeFirstAfter(100)?.timestampNanos)
        assertNull(buffer.takeFirstAfter(125))
        buffer.add(140, "next-run")
        buffer.clear()
        assertNull(buffer.takeFirstAfter(135))
    }
    @Test fun boundedBufferRetainsRecentCandidatesAndRejectsInvalidTimes() {
        val buffer = PendingFinishBuffer<String>(2)
        buffer.add(-1, "invalid")
        buffer.add(0, "invalid")
        listOf(10L, 20L, 30L).forEach { buffer.add(it, "$it") }
        assertEquals(20L, buffer.firstTimestamp)
        assertNull(buffer.takeFirstAfter(0))
        assertEquals(30L, buffer.takeFirstAfter(20)?.timestampNanos)
    }
    @Test fun resultTimestampNeverUsesReceiptTimeOrOverflows() {
        assertNull(finishTimestampForResult(null, 10))
        assertNull(finishTimestampForResult(0, 10))
        assertNull(finishTimestampForResult(100, -1))
        assertNull(finishTimestampForResult(Long.MAX_VALUE, 1))
        assertEquals(101L, finishTimestampForResult(100, 1))
    }
}
