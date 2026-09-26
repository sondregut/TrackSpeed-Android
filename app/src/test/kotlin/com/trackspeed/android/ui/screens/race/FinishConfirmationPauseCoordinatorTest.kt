package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishConfirmationPauseCoordinatorTest {
    private val eventId = "${FinishConfirmationPauseCoordinator.FINISH_CONFIRMATION_PAUSE_EVENT_PREFIX}test-id"

    @Test
    fun openingUnpausedDetectionCreatesOwnedProtocolEvent() {
        val coordinator = FinishConfirmationPauseCoordinator { eventId }

        val result = coordinator.open(detectionAlreadyPaused = false)

        assertTrue(result.introducedPause)
        assertEquals(eventId, result.eventId)
        assertTrue(coordinator.blocksNormalResume)
    }

    @Test
    fun openingWhileAlreadyPausedPreservesExistingOwner() {
        val coordinator = FinishConfirmationPauseCoordinator { eventId }

        val result = coordinator.open(detectionAlreadyPaused = true)
        val cancellation = coordinator.cancelLocal(automaticPauseRemains = false)

        assertFalse(result.introducedPause)
        assertNull(result.eventId)
        assertNull(cancellation.eventId)
        assertFalse(cancellation.shouldResumeLocally)
    }

    @Test
    fun localCancelReturnsExactEventIdAndResumesOnlyWithoutAutomaticPause() {
        val coordinator = FinishConfirmationPauseCoordinator { eventId }
        coordinator.open(detectionAlreadyPaused = false)

        val result = coordinator.cancelLocal(automaticPauseRemains = false)

        assertEquals(eventId, result.eventId)
        assertTrue(result.shouldResumeLocally)
        assertFalse(coordinator.blocksNormalResume)
    }

    @Test
    fun automaticPauseRemainsStrongerThanConfirmationCancel() {
        val coordinator = FinishConfirmationPauseCoordinator { eventId }
        coordinator.open(detectionAlreadyPaused = false)

        val result = coordinator.cancelLocal(automaticPauseRemains = true)

        assertEquals(eventId, result.eventId)
        assertFalse(result.shouldResumeLocally)
        assertFalse(coordinator.blocksNormalResume)
    }

    @Test
    fun peerResumeOnlyClearsExactMatchingConfirmationEvent() {
        val coordinator = FinishConfirmationPauseCoordinator()
        assertTrue(coordinator.receivePause(eventId))

        val wrong = coordinator.receiveResume(
            "${FinishConfirmationPauseCoordinator.FINISH_CONFIRMATION_PAUSE_EVENT_PREFIX}other",
            automaticPauseRemains = false
        )
        assertNull(wrong.eventId)
        assertTrue(coordinator.blocksNormalResume)

        val matching = coordinator.receiveResume(eventId, automaticPauseRemains = false)
        assertEquals(eventId, matching.eventId)
        assertTrue(matching.shouldResumeLocally)
        assertFalse(coordinator.blocksNormalResume)
    }

    @Test
    fun pauseAndResumeSharingEventIdBypassEventLevelDeduplication() {
        assertFalse(shouldDeduplicateRaceEventById(TimingPayload.PauseDetection()))
        assertFalse(shouldDeduplicateRaceEventById(TimingPayload.ResumeDetection()))
        assertTrue(
            shouldDeduplicateRaceEventById(
                TimingPayload.SessionEnded(reason = "hostLeft")
            )
        )
    }
}
