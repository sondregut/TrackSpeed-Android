package com.trackspeed.android.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackspeed.android.ui.screens.history.DetailLoadFeedback
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated production load feedback, no ViewModels, data writes or navigation. */
@RunWith(AndroidJUnit4::class)
class DetailLoadRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstLoadFailureOffersRetryAndReturnsToLoading() {
        var loading by mutableStateOf(true)
        var retries = 0
        compose.setContent { TrackSpeedTheme {
            DetailLoadFeedback(loading, !loading, false, { retries++; loading = true })
        } }
        compose.onNodeWithText("Loading results…").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.runOnIdle { loading = false }
        compose.onNodeWithText("Could not load these results.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Loading results…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun refreshErrorClearlyLabelsRetainedResults() {
        compose.setContent { TrackSpeedTheme { DetailLoadFeedback(false, true, true, {}) } }
        compose.onNodeWithText("Could not refresh these results. Showing the last loaded results.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed().assertHasClickAction()
    }
}
