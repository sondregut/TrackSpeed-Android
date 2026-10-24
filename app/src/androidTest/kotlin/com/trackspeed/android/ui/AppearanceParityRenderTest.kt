package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.ui.screens.settings.SettingsAppearanceChoices
import com.trackspeed.android.ui.theme.AppTheme
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production appearance controls, isolated preference state; no device settings or account writes. */
@RunWith(AndroidJUnit4::class)
class AppearanceParityRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun legacyDarkSelectionAndBothActionsWorkAtNormalText() = verifyChoices(1f, "normal")

    @Test fun legacyDarkSelectionAndBothActionsWorkAtLargeText() = verifyChoices(1.6f, "large-text")

    private fun verifyChoices(fontScale: Float, name: String) {
        var selected by mutableStateOf(AppTheme.MIDNIGHT)
        val changes = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TrackSpeedTheme(appTheme = selected) {
                    Box(Modifier.requiredWidth(320.dp).background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                        SettingsAppearanceChoices(selectedTheme = selected, onSelected = {
                            changes += it.key
                            selected = AppTheme.fromPreference(it.key)
                        })
                    }
                }
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(2)
        compose.onNodeWithText("Midnight").assertDoesNotExist()
        compose.onNodeWithText("Gold").assertDoesNotExist()
        compose.onNodeWithText("Dark").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Light").assertIsDisplayed().assertIsNotSelected()
        save("parity-appearance-dark-$name")

        compose.onNodeWithText("Light").performClick().assertIsSelected()
        compose.onNodeWithText("Dark").assertIsNotSelected()
        save("parity-appearance-light-$name")
        compose.onNodeWithText("Dark").performClick().assertIsSelected()
        compose.onNodeWithText("Light").assertIsNotSelected()
        compose.runOnIdle { assertEquals(listOf("light", "gold"), changes) }
    }

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "overlay-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
