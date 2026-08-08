package com.trackspeed.android.ui.screens.race

import java.util.UUID

/**
 * Owns the synchronized pause used while a shared-session finish confirmation
 * is visible. The same event ID must be used for pause and resume so a late or
 * unrelated resume cannot accidentally re-arm a timing gate.
 */
internal class FinishConfirmationPauseCoordinator(
    private val eventIdFactory: () -> String = {
        FINISH_CONFIRMATION_PAUSE_EVENT_PREFIX + UUID.randomUUID().toString()
    }
) {
    data class OpenResult(
        val eventId: String?,
        val introducedPause: Boolean
    )

    data class CancelResult(
        val eventId: String?,
        val shouldResumeLocally: Boolean
    )

    private var activeEventId: String? = null
    private var locallyOwnedEventId: String? = null

    val blocksNormalResume: Boolean
        get() = activeEventId != null

    fun open(detectionAlreadyPaused: Boolean): OpenResult {
        if (detectionAlreadyPaused || activeEventId != null) {
            return OpenResult(eventId = null, introducedPause = false)
        }

        val eventId = eventIdFactory()
        require(isFinishConfirmationEvent(eventId)) {
            "Finish-confirmation event IDs must use the shared protocol prefix"
        }
        activeEventId = eventId
        locallyOwnedEventId = eventId
        return OpenResult(eventId = eventId, introducedPause = true)
    }

    fun receivePause(eventId: String?): Boolean {
        val finishEventId = finishConfirmationEventId(eventId) ?: return false
        if (activeEventId != finishEventId) {
            activeEventId = finishEventId
            locallyOwnedEventId = null
        }
        return true
    }

    fun cancelLocal(automaticPauseRemains: Boolean): CancelResult {
        val eventId = locallyOwnedEventId
        if (eventId == null || activeEventId != eventId) {
            return CancelResult(eventId = null, shouldResumeLocally = false)
        }

        activeEventId = null
        locallyOwnedEventId = null
        return CancelResult(
            eventId = eventId,
            shouldResumeLocally = !automaticPauseRemains
        )
    }

    fun receiveResume(eventId: String?, automaticPauseRemains: Boolean): CancelResult {
        val finishEventId = finishConfirmationEventId(eventId)
            ?: return CancelResult(eventId = null, shouldResumeLocally = false)
        if (activeEventId != finishEventId) {
            return CancelResult(eventId = null, shouldResumeLocally = false)
        }

        activeEventId = null
        locallyOwnedEventId = null
        return CancelResult(
            eventId = finishEventId,
            shouldResumeLocally = !automaticPauseRemains
        )
    }

    fun reset() {
        activeEventId = null
        locallyOwnedEventId = null
    }

    companion object {
        const val FINISH_CONFIRMATION_PAUSE_EVENT_PREFIX = "finish-confirmation:"

        fun finishConfirmationEventId(eventId: String?): String? =
            eventId?.takeIf(::isFinishConfirmationEvent)

        fun isFinishConfirmationEvent(eventId: String): Boolean =
            eventId.startsWith(FINISH_CONFIRMATION_PAUSE_EVENT_PREFIX)
    }
}
