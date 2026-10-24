package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.ui.screens.history.HistoryFilterChip
import com.trackspeed.android.ui.screens.history.HistoryTimeAndFilterControls
import com.trackspeed.android.ui.screens.history.HistoryTimeFilter
import com.trackspeed.android.ui.screens.setup.SessionAthleteChip
import com.trackspeed.android.ui.screens.setup.SessionStartOptionChip
import com.trackspeed.android.ui.screens.setup.SessionTextChoiceChip
import com.trackspeed.android.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated production action controls; no account, permission or device operations. */
@RunWith(AndroidJUnit4::class)
class NeutralActionChromeRenderTest {
    @get:Rule val compose = createComposeRule()
    @Test fun neutralActionsRemainInteractiveAndReadableInLight() = verify(AppTheme.LIGHT)
    @Test fun neutralActionsRemainInteractiveAndReadableInDark() = verify(AppTheme.DARKGOLD)

    private fun verify(theme: AppTheme) {
        var mode by mutableStateOf(false)
        var distance by mutableStateOf(false)
        var athlete by mutableStateOf(false)
        var filter by mutableStateOf(false)
        var range by mutableStateOf(HistoryTimeFilter.ALL)
        var filtersOpened = 0
        val colors = if (theme == AppTheme.LIGHT) lightColors else darkGoldColors
        compose.setContent { TrackSpeedTheme(theme) {
            Column(Modifier.requiredWidth(320.dp).background(MaterialTheme.colorScheme.background).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SessionStartOptionChip("Connected gates", "Use each phone as a gate", Icons.Outlined.Flag,
                    mode, { mode = !mode }, Modifier.fillMaxWidth().testTag("mode"))
                Box(Modifier.testTag("distance")) { SessionTextChoiceChip("30 m", distance, { distance = !distance }) }
                Box(Modifier.testTag("athlete")) { SessionAthleteChip(
                    AthleteEntity(id = "synthetic-chrome-test", name = "Synthetic athlete", color = "blue"),
                    athlete, { athlete = !athlete }) }
                HistoryFilterChip("Flying", filter, { filter = !filter }, Modifier.fillMaxWidth().testTag("filter"))
                HistoryTimeAndFilterControls(range, { range = it }, false, { filtersOpened++ })
            }
        } }
        compose.onNodeWithText("Connected gates").performClick()
        compose.onNodeWithText("Connected gates").assertIsSelected()
        compose.onNodeWithText("30 m").performClick()
        compose.onNodeWithText("Synthetic athlete").performClick()
        compose.onNodeWithText("Flying").performClick()
        for (tag in listOf("mode", "distance", "athlete", "filter")) {
            assertColorPresent(tag, colors.primaryAction, minimumPixels = 150)
            assertColorPresent(tag, colors.onPrimaryAction, minimumPixels = 10)
        }
        // The avatar carries athlete identity, not action chrome, and keeps its blue.
        assertColorPresent("athlete", AthleteBlue, minimumPixels = 20)
        compose.onNodeWithText("This Week").performClick()
        compose.runOnIdle { assertEquals(HistoryTimeFilter.THIS_WEEK, range) }
        compose.onNodeWithText("All").performClick()
        compose.runOnIdle { assertEquals(HistoryTimeFilter.ALL, range) }
        compose.onNodeWithContentDescription("Open history filters").performClick()
        compose.runOnIdle { assertEquals(1, filtersOpened) }
        compose.onNodeWithText("Flying").performClick()
        compose.runOnIdle { assertFalse(filter) }
        compose.onNodeWithText("Flying").performClick()
        compose.runOnIdle { assertTrue(filter) }
        save("neutral-action-chrome-${theme.key}")
    }

    private fun assertColorPresent(tag: String, expected: Color, minimumPixels: Int) {
        fun matchedPixels(): Int {
            val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            var matched = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y].toArgb() == expected.toArgb()) matched++
            }
            return matched
        }
        val initial = matchedPixels()
        if (initial < minimumPixels) {
            save("neutral-transient-$tag-${expected.toArgb()}")
            // RenderThread ripple completion can follow Compose idleness. Keep the
            // exact colour/area assertion, but wait for the settled control pixels.
            compose.waitUntil(timeoutMillis = 3_000) { matchedPixels() >= minimumPixels }
        }
        val matched = matchedPixels()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "neutral-action-chrome/settling.txt").apply {
            parentFile?.mkdirs()
            appendText("$tag colour=${expected.toArgb()} before=$initial after=$matched minimum=$minimumPixels\n")
        }
        assertTrue("$tag is missing paired color $expected ($matched pixels)", matched >= minimumPixels)
    }
    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "neutral-action-chrome").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
