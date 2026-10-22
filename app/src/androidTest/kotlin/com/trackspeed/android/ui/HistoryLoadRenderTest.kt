package com.trackspeed.android.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackspeed.android.ui.screens.history.HistoryLoadFeedback
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryLoadRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingDoesNotOfferRetryAndFailureCanRequestOneRetry() {
        var loading by mutableStateOf(true)
        var retries = 0
        compose.setContent { TrackSpeedTheme {
            HistoryLoadFeedback(loading, !loading, false, { retries++ })
        } }
        compose.onNodeWithText("Loading sessions…").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.runOnIdle { loading = false }
        compose.onNodeWithText("Could not load sessions.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun retainedResultsAreExplicitlyLabelledWhenRefreshFails() {
        compose.setContent { TrackSpeedTheme {
            HistoryLoadFeedback(false, true, true, {})
        } }
        compose.onNodeWithText("Could not refresh sessions. Showing the last loaded results.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }
}
