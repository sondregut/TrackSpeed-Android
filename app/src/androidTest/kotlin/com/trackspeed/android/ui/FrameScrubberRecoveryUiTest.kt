package com.trackspeed.android.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.R
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.ui.screens.history.*
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production screen with synthetic read-only state; no ViewModel or repository. */
@RunWith(AndroidJUnit4::class)
class FrameScrubberRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int, vararg args: Any): String = context.getString(id, *args)
    private val run = RunEntity(id = "synthetic-frame-run", sessionId = "synthetic-session",
        runNumber = 1, timeSeconds = 2.123456789, distance = 20.0, startType = "flying")

    @Test fun initialFailureIsNotMissingDataAndRetryReturnsToLoading() {
        var state by mutableStateOf(RunFramesScrubberUiState())
        var retries = 0
        compose.setContent { TrackSpeedTheme {
            RunFramesScrubberScreenContent(state, {}, {
                retries++
                state = state.copy(isLoading = true, loadFailed = false)
            })
        } }
        compose.onNodeWithText(label(R.string.detail_loading)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.frame_scrubber_run_missing_title)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.frame_scrubber_no_data_title)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isLoading = false, loadFailed = true) }
        compose.onNodeWithText(label(R.string.detail_load_error)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.paywall_retry)).performClick()
        compose.onNodeWithText(label(R.string.detail_loading)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun successfulMissingRunUsesTheExistingMissingMessageWithoutRetryError() {
        compose.setContent { TrackSpeedTheme {
            RunFramesScrubberScreenContent(loadedFrameScrubberState(null), {}, {})
        } }
        compose.onNodeWithText(label(R.string.frame_scrubber_run_missing_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.detail_load_error)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.paywall_retry)).assertDoesNotExist()
    }

    @Test fun failedRefreshKeepsCalibrationSnapshotVisibleWhileRetryRuns() {
        var state by mutableStateOf(RunFramesScrubberUiState(
            run = run,
            calibrationSnapshots = listOf(LocalGateCalibrationSnapshot(gateIndex = 1, role = "finish",
                gatePosition = 0.5, velocityPxPerSec = 120.0)),
            isLoading = false, hasLoadedData = true, loadFailed = true
        ))
        compose.setContent { TrackSpeedTheme {
            RunFramesScrubberScreenContent(state, {}, { state = state.copy(isLoading = true, loadFailed = false) })
        } }
        compose.onNodeWithText(label(R.string.detail_load_error_saved)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.frame_scrubber_calibration_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.paywall_retry)).performClick()
        compose.onNodeWithText(label(R.string.detail_loading)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.frame_scrubber_calibration_title)).assertIsDisplayed()
    }

    @Test fun missingImageOffersRetryWithoutChangingFrameOrTiming() {
        val frame = LocalGateFrameData(context.cacheDir.resolve("synthetic-missing-retry-frame").path,
            frameNumber = 19, timestampInterval = 123.456789)
        val state = RunFramesScrubberUiState(run = run, frames = listOf(frame),
            isLoading = false, hasLoadedData = true)
        compose.setContent { TrackSpeedTheme { RunFramesScrubberScreenContent(state, {}, {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(label(R.string.paywall_retry)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.paywall_retry)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(label(R.string.parity_replay_frame_position, 1, 1)).assertExists()
        compose.runOnIdle {
            assertEquals(2.123456789, state.run!!.timeSeconds, 0.0)
            assertEquals(123.456789, state.frames.single().timestampInterval, 0.0)
        }
    }

    @Test fun shorterReplacementClampsSelectionAndPreservesOverlayPreference() {
        // Nonexistent task-owned paths exercise image-unavailable state, never user media.
        val first = LocalGateFrameData(context.cacheDir.resolve("synthetic-missing-frame-a").path, frameNumber = 19)
        val second = LocalGateFrameData(context.cacheDir.resolve("synthetic-missing-frame-b").path, frameNumber = 20)
        var state by mutableStateOf(RunFramesScrubberUiState(run = run, frames = listOf(first, second),
            isLoading = false, hasLoadedData = true))
        compose.setContent { TrackSpeedTheme { RunFramesScrubberScreenContent(state, {}, {}) } }
        compose.onNodeWithContentDescription(label(R.string.parity_replay_next_frame)).performClick()
        compose.onNodeWithContentDescription(label(R.string.parity_replay_frame_position, 2, 2)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.frame_scrubber_hide_overlays_cd)).performClick()
        compose.runOnIdle { state = state.copy(frames = listOf(first)) }
        compose.onNodeWithContentDescription(label(R.string.parity_replay_frame_position, 1, 1)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.frame_scrubber_show_overlays_cd)).assertExists()
        compose.runOnIdle {
            assertEquals(2.123456789, state.run!!.timeSeconds, 0.0)
            assertEquals(19L, state.frames.single().frameNumber)
        }
    }
}
