package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.ui.util.RetainedLoadState

/** Read refreshes must never clear a completed delete or discard the last good snapshot. */
internal fun mergeRunDetailLoadState(
    previous: RunDetailUiState,
    loaded: RetainedLoadState<RunDetailUiState>
): RunDetailUiState = (loaded.data ?: previous).copy(
    isLoading = loaded.isLoading,
    hasLoadedData = loaded.data != null,
    loadFailed = loaded.failed,
    deleted = previous.deleted
)

internal fun mergeSessionDetailLoadState(
    previous: SessionDetailUiState,
    loaded: RetainedLoadState<SessionDetailUiState>
): SessionDetailUiState = (loaded.data ?: previous).copy(
    isLoading = loaded.isLoading,
    hasLoadedData = loaded.data != null,
    loadFailed = loaded.failed,
    deleted = previous.deleted
)
