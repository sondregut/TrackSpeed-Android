package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.ui.screens.onboarding.OnboardingPageChrome
import com.trackspeed.android.ui.screens.onboarding.OnboardingStep
import com.trackspeed.android.ui.screens.onboarding.steps.*
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs only on the dedicated review emulator; never drives a live timing session. */
@RunWith(AndroidJUnit4::class)
class OnboardingParityRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "overlay-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun featureTourUsesCurrentCopyAndKeepsActionReachable() {
        var step by mutableStateOf(OnboardingStep.VALUE_PROPOSITION)
        var continued = 0
        compose.setContent {
            TrackSpeedTheme {
                OnboardingPageChrome(step, 0.4f, {}) {
                    key(step) { FeaturePhotoStep(step) { continued++ } }
                }
            }
        }
        val pages = listOf(
            OnboardingStep.VALUE_PROPOSITION to "Build a timing system.",
            OnboardingStep.MULTI_DEVICE to "Sync the phones.",
            OnboardingStep.CAMERA_ALIGNMENT to "Line up the camera.",
            OnboardingStep.AUTO_TIMING to "Just run.",
            OnboardingStep.PROOF_PROGRESS to "See the proof."
        )
        pages.forEach { (page, title) ->
            compose.runOnIdle { step = page }
            compose.onNodeWithText(title).assertIsDisplayed()
            val action = if (page == OnboardingStep.PROOF_PROGRESS) "Personalize TrackSpeed" else "Continue"
            compose.onNodeWithText(action).assertIsDisplayed()
            save("parity-tour-${page.name.lowercase()}")
            compose.onNodeWithText(action).performClick()
        }
        compose.runOnIdle { assertEquals(pages.size, continued) }
    }

    @Test fun compactLargeTextKeepsProofAndContinueInSeparateScrollAndActionAreas() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                TrackSpeedTheme {
                    Box(Modifier.requiredSize(320.dp, 600.dp)) {
                        OnboardingPageChrome(OnboardingStep.PROOF_PROGRESS, 0.5f, {}) {
                            FeaturePhotoStep(OnboardingStep.PROOF_PROGRESS) {}
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Personalize TrackSpeed").assertIsDisplayed()
        compose.onNodeWithContentDescription("Example flying 10 metre result with start and finish frames")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Personalize TrackSpeed").assertIsDisplayed()
        save("parity-proof-compact-large-text")
    }

    @Test fun supportedStartMethodsDoNotAdvertiseReactionMeasurement() {
        compose.setContent {
            TrackSpeedTheme {
                OnboardingPageChrome(OnboardingStep.START_TYPES, 0.3f, {}) { StartTypesStep {} }
            }
        }
        compose.onNodeWithText("Start your way.").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Reaction Start").assertDoesNotExist()
        save("parity-start-methods")
    }

    @Test fun tutorialRestoresPageAndBackReturnsToPreviousPhoto() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TrackSpeedTheme { GettingStartedGuide({}, {}) } }
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithContentDescription("Page 2 of 4").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Page 2 of 4").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithContentDescription("Page 1 of 4").assertExists()
        compose.onNodeWithText("Continue").assertIsDisplayed()
    }
}
