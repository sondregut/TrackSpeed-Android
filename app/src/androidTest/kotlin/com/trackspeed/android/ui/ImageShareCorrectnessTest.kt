package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.ui.components.*
import com.trackspeed.android.ui.share.*
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic production-component renders/callbacks only; never invokes MediaStore or an external chooser. */
@RunWith(AndroidJUnit4::class)
class ImageShareCorrectnessTest {
    @get:Rule val compose = createComposeRule()
    private fun session(vararg names: String?) = ShareSessionCardData(30.0, "24 Oct 2026",
        names.mapIndexed { index, name -> ShareSessionRunData(index + 1, 4.0 + index, athleteName = name) })

    @Test fun mixedAthletesHaveOwnRowsAndNoMisleadingFooter() {
        compose.setContent { TrackSpeedTheme {
            Box(Modifier.requiredSize(360.dp, 640.dp)) { ShareableSessionCard(session("Alex", "Sam"), ShareCardTheme.MIDNIGHT) }
        } }
        compose.onNodeWithTag("session-share-row-name-1").assertTextEquals("Alex").assertIsDisplayed()
        compose.onNodeWithTag("session-share-row-name-2").assertTextEquals("Sam").assertIsDisplayed()
        compose.onNodeWithTag("session-share-footer-name").assertDoesNotExist()
        save("parity-session-share-mixed-names")
    }

    @Test fun sameAthleteUsesOneFooterAndPartiallyUnnamedRunsDoNotInheritIt() {
        var data by mutableStateOf(session(" Alex ", "Alex"))
        compose.setContent { TrackSpeedTheme {
            Box(Modifier.requiredSize(360.dp, 640.dp)) { ShareableSessionCard(data, ShareCardTheme.MIDNIGHT) }
        } }
        compose.onNodeWithTag("session-share-footer-name").assertTextEquals("Alex")
        compose.onNodeWithTag("session-share-row-name-1").assertDoesNotExist()
        compose.runOnIdle { data = session("Alex", null) }
        compose.onNodeWithTag("session-share-footer-name").assertDoesNotExist()
        compose.onNodeWithTag("session-share-row-name-1").assertTextEquals("Alex")
        compose.onNodeWithTag("session-share-row-name-2").assertDoesNotExist()
        save("parity-session-share-partial-names")
    }

    @Test fun busyButtonsBlockDuplicatesAndCancellationAndRetryAreExplicitActions() {
        var state by mutableStateOf(ImageShareState(outputKey = "a"))
        val actions = mutableListOf<ImageShareAction>(); var cancelled = 0
        compose.setContent { TrackSpeedTheme {
            ImageShareActions("a", state, onAction = { action ->
                actions += action
                state = state.copy(pending = ImageSharePending("a", action), failure = null)
            }, onCancel = { cancelled++; state = state.copy(pending = null, failure = ImageShareFailure("a", ImageShareAction.SAVE)) })
        } }
        compose.onNodeWithText("Save to Gallery").performClick().assertIsNotEnabled()
        compose.onNodeWithText("Share…").assertIsNotEnabled()
        compose.onNodeWithText("Preparing image…").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Couldn’t confirm the save. Check your gallery before trying again.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(ImageShareAction.SAVE), actions); assertEquals(1, cancelled) }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(listOf(ImageShareAction.SAVE, ImageShareAction.SAVE), actions) }
    }

    @Test fun newOutputDoesNotReuseSavedLabelFromAnotherStyle() {
        var key by mutableStateOf("saved-style")
        compose.setContent { TrackSpeedTheme {
            ImageShareActions(key, ImageShareState(savedKeys = setOf("saved-style")), {}, {})
        } }
        compose.onNodeWithText("Saved").assertIsNotEnabled()
        compose.runOnIdle { key = "new-style" }
        compose.onNodeWithText("Saved").assertDoesNotExist()
        compose.onNodeWithText("Save to Gallery").assertIsEnabled()
    }

    @Test fun actualOutputKeysIncludeUnitsNamesThemeAndChangedThumbnailPixels() {
        val run = ShareCardData(4.123456, 30.0, "flying", "24 Oct 2026", athleteName = "Alex", speedValue = 7.275, speedUnit = "m/s")
        val key = run.outputKey(ShareCardTheme.MIDNIGHT, "en-US:420:1.0")
        assertNotEquals(key, run.copy(speedUnit = "mph", speedValue = 16.274).outputKey(ShareCardTheme.MIDNIGHT, "en-US:420:1.0"))
        assertNotEquals(key, run.copy(athleteName = "Sam").outputKey(ShareCardTheme.MIDNIGHT, "en-US:420:1.0"))
        assertNotEquals(key, run.outputKey(ShareCardTheme.OCEAN, "en-US:420:1.0"))
        assertNotEquals(key, run.outputKey(ShareCardTheme.MIDNIGHT, "nb-NO:420:1.0"))
        val thumbnail = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            val data = session("Alex").let { it.copy(runs = listOf(it.runs.single().copy(thumbnail = thumbnail))) }
            val before = data.outputKey(ShareCardTheme.MIDNIGHT, "en-US")
            thumbnail.setPixel(0, 0, android.graphics.Color.RED)
            assertNotEquals(before, data.outputKey(ShareCardTheme.MIDNIGHT, "en-US"))
            assertNotEquals(before, session("Sam").outputKey(ShareCardTheme.MIDNIGHT, "en-US"))
        } finally { thumbnail.recycle() }
    }

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "overlay-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
