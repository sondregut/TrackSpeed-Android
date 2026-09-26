package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingPayload

/**
 * Pause and resume may deliberately share the same finish-confirmation event
 * ID. Envelope/message IDs still suppress retransmits of each individual
 * message, while this policy allows the paired resume through.
 */
internal fun shouldDeduplicateRaceEventById(payload: TimingPayload): Boolean = when (payload) {
    is TimingPayload.ThumbnailMetadata,
    is TimingPayload.ThumbnailUpdate,
    is TimingPayload.PauseDetection,
    is TimingPayload.ResumeDetection -> false
    else -> true
}
