package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.ui.components.ShareCardData
import com.trackspeed.android.ui.components.ShareSessionCardData
import com.trackspeed.android.ui.share.runImageSpeed
import com.trackspeed.android.ui.util.RetainedLoadState
import com.trackspeed.android.videooverlay.OverlaySpeedUnit

/** A successful missing-record read is distinct from a read that has never succeeded. */
internal data class ShareCardLoadSnapshot<T>(val cardData: T?)

/** Merge only loaded content; theme and visibility choices belong to the current editor. */
internal fun mergeShareResultLoadState(
    previous: ShareResultUiState,
    loaded: RetainedLoadState<ShareCardLoadSnapshot<ShareCardData>>,
    latestSpeedUnit: String? = null
): ShareResultUiState {
    val snapshot = loaded.data
    val card = if (snapshot != null) snapshot.cardData else previous.cardData
    val unit = latestSpeedUnit?.let(OverlaySpeedUnit::fromRaw)
    // Photo decoding may suspend after reading a unit. Never restore that stale choice.
    val selectedCard = if (card != null && unit != null) card.copy(
        speedValue = runImageSpeed(card.distance, card.timeSeconds, unit),
        speedUnit = unit.label
    ) else card
    return previous.copy(
        cardData = selectedCard,
        isLoading = loaded.isLoading,
        loadFailed = loaded.failed
    )
}

internal fun mergeShareSessionLoadState(
    previous: ShareSessionUiState,
    loaded: RetainedLoadState<ShareCardLoadSnapshot<ShareSessionCardData>>
): ShareSessionUiState {
    val snapshot = loaded.data
    return previous.copy(
        cardData = if (snapshot != null) snapshot.cardData else previous.cardData,
        isLoading = loaded.isLoading,
        loadFailed = loaded.failed
    )
}
