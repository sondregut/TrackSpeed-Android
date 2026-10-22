package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.ui.screens.home.HomeContent
import com.trackspeed.android.ui.screens.settings.SettingsScreenContent
import com.trackspeed.android.ui.screens.settings.SettingsUiState
import com.trackspeed.android.ui.theme.AppTheme
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic UI only: no ViewModels, account, saved runs, hardware or external navigation. */
@RunWith(AndroidJUnit4::class)
class DashboardParityRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "overlay-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun compactHomeKeepsNativeNavigationActionsReachable() {
        val actions = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                TrackSpeedTheme {
                    Box(Modifier.requiredSize(320.dp, 600.dp)) {
                        HomeContent(onPresetClick = { actions += "solo" },
                            onCustomSessionClick = { actions += "setup" },
                            onJoinSessionClick = { actions += "join" },
                            onProfileClick = { actions += "profile" },
                            onSeeAllClick = { actions += "history" })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Profile").performClick()
        compose.onNodeWithText("Join Session").performClick()
        compose.onNodeWithText("Solo Laps").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Start Session").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Your training, saved here").performScrollTo().assertIsDisplayed()
        save("parity-home-compact-empty")
        compose.onNodeWithText("Your training, saved here").performClick()
        compose.runOnIdle { assertEquals(listOf("profile", "join", "solo", "setup", "history"), actions) }
    }

    @Test fun settingsUnitsAndAppearanceStayReachableAtLargeText() {
        var state by mutableStateOf(SettingsUiState(appTheme = AppTheme.LIGHT))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                TrackSpeedTheme(appTheme = state.appTheme) {
                    Box(Modifier.requiredSize(320.dp, 600.dp)) {
                        SettingsScreenContent(state = state, onDistanceSelected = {}, onStartTypeSelected = {},
                            onSpeedUnitSelected = { state = state.copy(speedUnit = it) },
                            onThemeSelected = { state = state.copy(appTheme = it) }, onSensitivityChanged = {})
                    }
                }
            }
        }
        for (unit in listOf("mph", "km/h", "m/s")) {
            compose.onNodeWithText(unit, useUnmergedTree = true).performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(unit, state.speedUnit) }
        }
        compose.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        save("parity-settings-compact-light")
        compose.onNodeWithText("Privacy Policy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Terms of Service").performScrollTo().assertIsDisplayed()
    }
}
