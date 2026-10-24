package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.videooverlay.OverlayPreviewControls
import com.trackspeed.android.ui.screens.videooverlay.VideoExportPhase
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import com.trackspeed.android.videooverlay.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production editor controls, isolated from the native video player and account SDKs.
 * These are component interaction tests, not full navigation or physical-camera acceptance.
 */
@RunWith(AndroidJUnit4::class)
class VideoOverlayEditorInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private class EditorState(initial: OverlayPreferences = OverlayPreferences()) {
        var options by mutableStateOf(initial)
        var guides by mutableStateOf(false)
        var phase: VideoExportPhase by mutableStateOf(VideoExportPhase.Idle)
        var changes = 0
        var backs = 0
        var exports = 0
        var retries = 0
        var saves = 0
        var shares = 0
    }

    @Composable
    private fun Editor(state: EditorState, fontScale: Float = 1f) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            TrackSpeedTheme {
                Box(Modifier.widthIn(max = 360.dp).heightIn(max = 560.dp)) {
                    OverlayPreviewControls(
                        options = state.options,
                        exportPhase = state.phase,
                        showGuides = state.guides,
                        onShowGuidesChange = { state.guides = it },
                        onPreferencesChange = { state.options = it; state.changes++ },
                        onBack = { state.backs++ },
                        onExport = { state.exports++ },
                        onRetry = { state.retries++ },
                        onSave = { state.saves++ },
                        onShare = { state.shares++ }
                    )
                }
            }
        }
    }

    private fun click(id: Int) = compose.onNodeWithText(text(id)).performScrollTo().performClick()
    private fun openOptions() = click(R.string.overlay_edit_metrics)

    private fun save(name: String, dialog: Boolean = false) {
        val output = File(context.getExternalFilesDir(null), "editor-interactions").apply { mkdirs() }
        val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        node.captureToImage().asAndroidBitmap().let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(output, "source.txt").writeText(
            "source=${BuildConfig.SOURCE_FINGERPRINT}\nversion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "evidence=production editor controls; isolated synthetic state; not runtime navigation\n"
        )
    }

    @Test fun speedUnitsAndCanvasPreserveHiddenMetricChoices() {
        val initial = OverlayPreferences(showTime = false, showRunType = false,
            splitMode = OverlaySplitMode.SEGMENTS, position = OverlayPosition.BOTTOM_RIGHT)
        val state = EditorState(initial)
        compose.setContent { Editor(state) }
        OverlaySpeedUnit.entries.forEach { unit ->
            compose.onNodeWithText(unit.label).performScrollTo().performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(initial.copy(speedUnit = unit), state.options) }
        }
        click(R.string.overlay_canvas_original)
        compose.runOnIdle {
            assertEquals(initial.copy(speedUnit = OverlaySpeedUnit.KMH, canvas = OverlayCanvas.ORIGINAL), state.options)
        }
        click(R.string.overlay_canvas_social)
        compose.onNodeWithText(text(R.string.overlay_canvas_social)).assertIsSelected()
        compose.runOnIdle { assertEquals(0, state.exports); assertEquals(0, state.shares) }
        save("editor-units-and-canvas")
    }

    @Test fun labelledMetricRowsAndScrolledOptionsChangeOnlyTheirOwnValues() {
        val state = EditorState()
        compose.setContent { Editor(state, fontScale = 1.6f) }
        openOptions()
        listOf(R.string.overlay_show_time, R.string.overlay_average_speed, R.string.video_overlay_show_run_type).forEach { id ->
            compose.onNodeWithText(text(id)).performScrollTo().assertIsOn()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                .performClick().assertIsOff()
        }
        compose.runOnIdle {
            assertEquals(3, state.changes)
            assertEquals(OverlayPreferences(showTime = false, showSpeed = false, showRunType = false), state.options)
        }
        click(R.string.overlay_splits_segments)
        click(R.string.overlay_bottom_right)
        click(R.string.overlay_social_safe)
        compose.onNodeWithText(text(R.string.overlay_social_safe)).assertIsOff()
        compose.runOnIdle {
            assertEquals(OverlayPreferences(showTime = false, showSpeed = false, showRunType = false,
                splitMode = OverlaySplitMode.SEGMENTS, position = OverlayPosition.BOTTOM_RIGHT, socialSafe = false), state.options)
        }
        save("editor-options-large-text", dialog = true)
        compose.onNodeWithText(text(R.string.common_done)).assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        openOptions()
        compose.onNodeWithText(text(R.string.overlay_show_time)).assertIsOff()
        compose.onNodeWithText(text(R.string.overlay_splits_segments)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.overlay_bottom_right)).performScrollTo().assertIsSelected()
        // The reopened dialog owns window focus; inject system Back instead of
        // asking Espresso to select the now-unfocused base activity root.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, state.backs); assertEquals(0, state.exports) }
    }

    @Test fun openDialogRestoresAndBackDismissesBeforeMarkerNavigation() {
        val state = EditorState()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Editor(state) }
        openOptions()
        click(R.string.overlay_show_time)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText(text(R.string.overlay_show_time)).assertIsOff()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, state.backs) }
        repeat(2) {
            openOptions()
            compose.onNodeWithText(text(R.string.common_done)).performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
        }
        click(R.string.video_overlay_back_to_marker)
        compose.runOnIdle { assertEquals(1, state.backs); assertEquals(1, state.changes) }
    }

    @Test fun guidesArePreviewOnlyAndExportLocksMutableChoices() {
        val state = EditorState()
        compose.setContent { Editor(state) }
        click(R.string.overlay_safe_guides)
        compose.onNodeWithText(text(R.string.overlay_hide_guides)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.parity_overlay_guide_note)).assertExists()
        compose.runOnIdle { assertTrue(state.guides); assertEquals(0, state.changes) }
        save("editor-preview-guides")
        click(R.string.overlay_hide_guides)
        click(R.string.video_overlay_create)
        compose.runOnIdle { assertEquals(1, state.exports); state.phase = VideoExportPhase.Exporting(.25) }
        compose.onNodeWithText("mph").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.overlay_canvas_original)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.overlay_edit_metrics)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.video_overlay_back_to_marker)).performScrollTo().assertIsNotEnabled()
        click(R.string.overlay_safe_guides)
        compose.runOnIdle { assertTrue(state.guides); assertEquals(OverlayPreferences(), state.options) }
    }
}
