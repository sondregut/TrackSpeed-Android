package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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

    private fun saveFeedback(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "share-load-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

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
        saveFeedback("initial-read-failure")
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Loading results…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun refreshErrorClearlyLabelsRetainedResults() {
        compose.setContent { TrackSpeedTheme { DetailLoadFeedback(false, true, true, {}) } }
        compose.onNodeWithText("Could not refresh these results. Showing the last loaded results.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed().assertHasClickAction()
        saveFeedback("retained-preview-failure")
    }
}
