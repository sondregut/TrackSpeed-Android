package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.ui.components.ShareCardData
import com.trackspeed.android.ui.components.ShareCardTheme
import com.trackspeed.android.ui.components.ShareSessionCardData
import com.trackspeed.android.ui.components.ShareSessionRunData
import com.trackspeed.android.ui.share.RunImageFormat
import com.trackspeed.android.ui.share.RunImageOptions
import com.trackspeed.android.ui.share.SessionImageOptions
import com.trackspeed.android.ui.util.RetainedFlowLoader
import com.trackspeed.android.ui.util.RetainedLoadState
import com.trackspeed.android.videooverlay.OverlaySpeedUnit
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ShareCardLoadRecoveryTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun result(time: Double = 2.123456789) = ShareCardData(
        timeSeconds = time, distance = 20.0, startType = "flying", dateFormatted = "24 Oct 2026",
        athleteName = "Synthetic athlete", isPersonalBest = true, isSeasonBest = true,
        speedValue = 20.0 / time * 3.6, speedUnit = "km/h",
        startGatePosition = .3123456789, finishGatePosition = .7123456789, timeDigits = 2
    )
    private fun session(runs: List<ShareSessionRunData> = listOf(
        ShareSessionRunData(1, 2.123456789, athleteName = "Synthetic athlete", timeDigits = 2)
    )) = ShareSessionCardData(20.0, "24 Oct 2026", runs)

    @Test fun resultReadFailureStopsSpinnerWithoutInventingMissingDataAndRetryRecovers() {
        val owner = scope()
        var attempts = 0
        try {
            val loader = RetainedFlowLoader(owner) {
                flow {
                    if (++attempts == 1) throw IOException("private-database-payload")
                    emit(ShareCardLoadSnapshot(result()))
                }
            }
            var state = mergeShareResultLoadState(ShareResultUiState(), loader.state.value)
            assertTrue(state.loadFailed)
            assertFalse(state.isLoading)
            assertNull(state.cardData)
            assertFalse(state.toString().contains("private-database-payload"))
            loader.retry()
            state = mergeShareResultLoadState(state, loader.state.value)
            assertFalse(state.loadFailed)
            assertFalse(state.isLoading)
            assertEquals(result(), state.cardData)
        } finally { owner.cancel() }
    }

    @Test fun sessionReadFailureCanRetryWithoutResettingHiddenPhotosNamesOrSplits() {
        val owner = scope()
        var attempts = 0
        val options = SessionImageOptions(showPhotos = false, showName = false, showSplits = false)
        try {
            val loader = RetainedFlowLoader(owner) {
                flow {
                    if (++attempts == 1) throw IOException("private-session-payload")
                    emit(ShareCardLoadSnapshot(session()))
                }
            }
            var state = mergeShareSessionLoadState(ShareSessionUiState(
                selectedTheme = ShareCardTheme.OCEAN, imageOptions = options), loader.state.value)
            assertTrue(state.loadFailed)
            assertFalse(state.isLoading)
            assertNull(state.cardData)
            assertFalse(state.toString().contains("private-session-payload"))
            loader.retry()
            state = mergeShareSessionLoadState(state, loader.state.value)
            assertFalse(state.loadFailed)
            assertEquals(session(), state.cardData)
            assertEquals(options, state.imageOptions)
            assertEquals(ShareCardTheme.OCEAN, state.selectedTheme)
        } finally { owner.cancel() }
    }

    @Test fun successfulMissingRecordsClearStaleCardsWithoutBecomingReadErrors() {
        val missingRun = mergeShareResultLoadState(ShareResultUiState(cardData = result()),
            RetainedLoadState(ShareCardLoadSnapshot<ShareCardData>(null), isLoading = false))
        assertNull(missingRun.cardData)
        assertFalse(missingRun.isLoading)
        assertFalse(missingRun.loadFailed)
        val missingSession = mergeShareSessionLoadState(ShareSessionUiState(cardData = session()),
            RetainedLoadState(ShareCardLoadSnapshot<ShareSessionCardData>(null), isLoading = false))
        assertNull(missingSession.cardData)
        assertFalse(missingSession.isLoading)
        assertFalse(missingSession.loadFailed)
    }

    @Test fun existingSessionWithZeroRunsIsAValidCardDistinctFromMissingSession() {
        val emptySession = session(emptyList())
        val state = mergeShareSessionLoadState(ShareSessionUiState(),
            RetainedLoadState(ShareCardLoadSnapshot(emptySession), isLoading = false))
        assertSame(emptySession, state.cardData)
        assertTrue(state.cardData!!.runs.isEmpty())
        assertFalse(state.loadFailed)
        assertFalse(state.isLoading)
    }

    @Test fun resultRefreshFailureRetainsCanonicalContentUntilSuccessfulReplacement() {
        val owner = scope()
        var attempts = 0
        try {
            val loader = RetainedFlowLoader(owner) {
                flow {
                    when (++attempts) {
                        1 -> emit(ShareCardLoadSnapshot(result()))
                        2 -> throw IOException()
                        else -> emit(ShareCardLoadSnapshot(result(1.987654321)))
                    }
                }
            }
            var state = mergeShareResultLoadState(ShareResultUiState(), loader.state.value)
            val good = state.cardData
            loader.retry()
            state = mergeShareResultLoadState(state, loader.state.value)
            assertTrue(state.loadFailed)
            assertFalse(state.isLoading)
            assertSame(good, state.cardData)
            loader.retry()
            state = mergeShareResultLoadState(state, loader.state.value)
            assertFalse(state.loadFailed)
            assertEquals(1.987654321, state.cardData!!.timeSeconds, 0.0)
            assertEquals(2, state.cardData!!.timeDigits)
        } finally { owner.cancel() }
    }

    @Test fun sessionRefreshFailureRetainsRunsAndRetryCanConfirmDeletedSession() {
        val owner = scope()
        var attempts = 0
        try {
            val loader = RetainedFlowLoader<ShareCardLoadSnapshot<ShareSessionCardData>>(owner) {
                flow {
                    when (++attempts) {
                        1 -> emit(ShareCardLoadSnapshot(session()))
                        2 -> throw IOException()
                        else -> emit(ShareCardLoadSnapshot(null))
                    }
                }
            }
            var state = mergeShareSessionLoadState(ShareSessionUiState(), loader.state.value)
            val good = state.cardData
            loader.retry()
            state = mergeShareSessionLoadState(state, loader.state.value)
            assertTrue(state.loadFailed)
            assertSame(good, state.cardData)
            assertEquals(2.123456789, state.cardData!!.runs.single().timeSeconds, 0.0)
            loader.retry()
            state = mergeShareSessionLoadState(state, loader.state.value)
            assertFalse(state.loadFailed)
            assertFalse(state.isLoading)
            assertNull(state.cardData)
        } finally { owner.cancel() }
    }

    @Test fun everyRunOptionAndThemeSurvivesLoadingFailureAndSuccessfulReplacement() {
        val old = result()
        val replacement = result(1.987654321)
        for (format in RunImageFormat.entries) for (mask in 0..15) {
            val options = RunImageOptions(format, mask and 1 != 0, mask and 2 != 0,
                mask and 4 != 0, mask and 8 != 0)
            var state = ShareResultUiState(old, ShareCardTheme.SLATE, options, isLoading = false)
            for (read in listOf(
                RetainedLoadState(ShareCardLoadSnapshot(old), isLoading = true),
                RetainedLoadState(ShareCardLoadSnapshot(old), isLoading = false, failed = true),
                RetainedLoadState(ShareCardLoadSnapshot(replacement), isLoading = false)
            )) {
                state = mergeShareResultLoadState(state, read)
                assertEquals(options, state.imageOptions)
                assertEquals(ShareCardTheme.SLATE, state.selectedTheme)
            }
            assertEquals(replacement, state.cardData)
        }
    }

    @Test fun everySessionVisibilityChoiceSurvivesLoadingFailureAndSuccessfulReplacement() {
        val old = session()
        for (mask in 0..7) {
            val options = SessionImageOptions(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
            var state = ShareSessionUiState(old, ShareCardTheme.EMBER, options, isLoading = false)
            for (read in listOf(
                RetainedLoadState(ShareCardLoadSnapshot(old), isLoading = true),
                RetainedLoadState(ShareCardLoadSnapshot(old), isLoading = false, failed = true),
                RetainedLoadState(ShareCardLoadSnapshot(session(emptyList())), isLoading = false)
            )) {
                state = mergeShareSessionLoadState(state, read)
                assertEquals(options, state.imageOptions)
                assertEquals(ShareCardTheme.EMBER, state.selectedTheme)
            }
            assertTrue(state.cardData!!.runs.isEmpty())
        }
    }

    @Test fun latestSpeedUnitWinsOverSuspendedReadWithoutRoundingCanonicalTiming() {
        val loadedCard = result()
        for (unit in OverlaySpeedUnit.entries) {
            val state = mergeShareResultLoadState(ShareResultUiState(),
                RetainedLoadState(ShareCardLoadSnapshot(loadedCard), isLoading = false), unit.label)
            val card = state.cardData!!
            assertEquals(unit.label, card.speedUnit)
            assertEquals(20.0 / 2.123456789 * unit.factor, card.speedValue!!, 0.0)
            assertEquals(loadedCard.copy(speedValue = card.speedValue, speedUnit = unit.label), card)
            assertEquals(2.123456789, card.timeSeconds, 0.0)
            assertEquals(.3123456789, card.startGatePosition!!, 0.0)
            assertEquals(.7123456789, card.finishGatePosition!!, 0.0)
        }
    }

    @Test fun unitSelectedDuringRefreshSurvivesBothRetainedFailureAndReplacement() = runBlocking {
        withTimeout(2_000) {
            val owner = scope()
            val finishDecode = CompletableDeferred<Unit>()
            var attempts = 0
            try {
                val loader = RetainedFlowLoader(owner) {
                    flow {
                        if (++attempts > 1) finishDecode.await()
                        emit(ShareCardLoadSnapshot(result()))
                    }
                }
                var state = mergeShareResultLoadState(ShareResultUiState(), loader.state.value)
                loader.retry()
                // This is the latest SavedStateHandle unit passed by the production ViewModel.
                val latestUnit = OverlaySpeedUnit.MPH.label
                state = state.copy(selectedTheme = ShareCardTheme.OCEAN,
                    imageOptions = RunImageOptions(format = RunImageFormat.FEED, showName = false, showPhotos = false))
                state = mergeShareResultLoadState(state, loader.state.value, latestUnit)
                assertTrue(state.isLoading)
                assertNotNull(state.cardData)
                assertEquals(latestUnit, state.cardData!!.speedUnit)
                finishDecode.complete(Unit)
                yield()
                state = mergeShareResultLoadState(state, loader.state.value, latestUnit)
                assertFalse(state.isLoading)
                assertEquals(latestUnit, state.cardData!!.speedUnit)
                assertEquals(ShareCardTheme.OCEAN, state.selectedTheme)
                assertEquals(RunImageFormat.FEED, state.imageOptions.format)
                assertFalse(state.imageOptions.showName)
                assertFalse(state.imageOptions.showPhotos)
                state = mergeShareResultLoadState(state, loader.state.value.copy(failed = true), latestUnit)
                assertTrue(state.loadFailed)
                assertEquals(latestUnit, state.cardData!!.speedUnit)
            } finally { finishDecode.complete(Unit); owner.cancel() }
        }
    }

    @Test fun noEmissionIsRetryableInsteadOfFalselyReportingAMissingRecord() {
        val owner = scope()
        try {
            val loader = RetainedFlowLoader<ShareCardLoadSnapshot<ShareCardData>>(owner) { emptyFlow() }
            val state = mergeShareResultLoadState(ShareResultUiState(), loader.state.value)
            assertNull(state.cardData)
            assertFalse(state.isLoading)
            assertTrue(state.loadFailed)
        } finally { owner.cancel() }
    }

    @Test fun lateCancelledResultReadCannotReplaceNewerRetry() = runBlocking {
        withTimeout(2_000) {
            val owner = scope()
            val releaseOld = CompletableDeferred<Unit>()
            var attempts = 0
            try {
                val loader = RetainedFlowLoader(owner) {
                    flow {
                        val card = if (++attempts == 1) {
                            withContext(NonCancellable) { releaseOld.await() }
                            result(9.0)
                        } else result(1.987654321)
                        emit(ShareCardLoadSnapshot(card))
                    }
                }
                loader.retry()
                var state = mergeShareResultLoadState(ShareResultUiState(), loader.state.value)
                assertEquals(1.987654321, state.cardData!!.timeSeconds, 0.0)
                releaseOld.complete(Unit)
                yield()
                state = mergeShareResultLoadState(state, loader.state.value)
                assertEquals(1.987654321, state.cardData!!.timeSeconds, 0.0)
                assertFalse(state.loadFailed)
            } finally { releaseOld.complete(Unit); owner.cancel() }
        }
    }

    @Test fun lateCancelledSessionFailureCannotPoisonSuccessfulRetry() = runBlocking {
        withTimeout(2_000) {
            val owner = scope()
            val releaseOld = CompletableDeferred<Unit>()
            var attempts = 0
            try {
                val loader = RetainedFlowLoader(owner) {
                    flow {
                        if (++attempts == 1) {
                            withContext(NonCancellable) { releaseOld.await() }
                            throw IOException("cancelled-read-private-payload")
                        }
                        emit(ShareCardLoadSnapshot(session()))
                    }
                }
                loader.retry()
                var state = mergeShareSessionLoadState(ShareSessionUiState(), loader.state.value)
                releaseOld.complete(Unit)
                yield()
                state = mergeShareSessionLoadState(state, loader.state.value)
                assertEquals(session(), state.cardData)
                assertFalse(state.loadFailed)
                assertFalse(state.isLoading)
            } finally { releaseOld.complete(Unit); owner.cancel() }
        }
    }

    @Test fun repeatedRetryAndScreenCancellationDoNotLeaveReadersOrInventAnError() = runBlocking {
        val owner = scope()
        val reads = MutableSharedFlow<ShareCardLoadSnapshot<ShareSessionCardData>>()
        val loader = RetainedFlowLoader(owner) { reads }
        try {
            repeat(3) { loader.retry() }
            yield()
            assertEquals(1, reads.subscriptionCount.value)
            reads.emit(ShareCardLoadSnapshot(session()))
            yield()
            owner.cancel()
            val state = mergeShareSessionLoadState(ShareSessionUiState(), loader.state.value)
            assertEquals(session(), state.cardData)
            assertFalse(state.loadFailed)
            assertFalse(state.isLoading)
            assertEquals(0, reads.subscriptionCount.value)
        } finally { owner.cancel() }
    }
}
