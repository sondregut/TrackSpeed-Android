package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.data.model.UserRole
import com.trackspeed.android.ui.screens.home.HomeContent
import com.trackspeed.android.ui.screens.onboarding.OnboardingPageChrome
import com.trackspeed.android.ui.screens.onboarding.OnboardingStep
import com.trackspeed.android.ui.screens.onboarding.steps.*
import com.trackspeed.android.ui.screens.paywall.*
import com.trackspeed.android.ui.screens.settings.SettingsScreenContent
import com.trackspeed.android.ui.screens.settings.SettingsUiState
import com.trackspeed.android.ui.screens.tools.*
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Native production Composable renders with synthetic, offline state. These are
 * component-review evidence, NOT navigation, billing, camera or BLE acceptance.
 * Run with UiParityTestRunner on a dedicated emulator only: no app SDK bootstrap.
 */
@RunWith(AndroidJUnit4::class)
class ScreenWalkthroughRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "screen-walkthrough").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(directory, "source.txt").writeText(
            "source=${BuildConfig.SOURCE_FINGERPRINT}\nversion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "evidence=native production Composables; synthetic offline state; not runtime navigation\n"
        )
    }

    @Test fun onboardingPagesRenderWithoutAccountOrPermissionMutation() {
        var page by mutableIntStateOf(0)
        var joinCount = 0
        compose.setContent { TrackSpeedTheme {
            key(page) {
                when (page) {
                    0 -> WelcomeStep("en", {}, { joinCount++ }, {})
                    1 -> OnboardingPageChrome(OnboardingStep.ROLE_SELECTION, .5f, {}) {
                        RoleSelectionStep(UserRole.ATHLETE, {}, {})
                    }
                    2 -> OnboardingPageChrome(OnboardingStep.SPORT_SELECTION, .55f, {}) {
                        SportSelectionStep(SportDiscipline.SPRINT_100M, {}, {})
                    }
                    3 -> OnboardingPageChrome(OnboardingStep.HOW_IT_WORKS, .6f, {}) {
                        HowItWorksStep(emptySet(), {})
                    }
                    4 -> OnboardingPageChrome(OnboardingStep.ATTRIBUTION, .7f, {}) {
                        AttributionStep({}, "", {}, { _, _ -> }, onContinue = {})
                    }
                    5 -> OnboardingPageChrome(OnboardingStep.CAMERA_PERMISSION, .8f, {}) {
                        CameraPermissionStep({}, {})
                    }
                    6 -> EarlyReleaseWelcomeStep {}
                }
            }
        } }
        compose.onNodeWithText("Join Session").assertIsDisplayed()
        save("onboarding-welcome")
        compose.onNodeWithText("Join Session").performClick()
        compose.runOnIdle { assertEquals(1, joinCount) }
        listOf("role", "sport", "how-it-works", "attribution", "camera-permission", "early-release").forEachIndexed { index, name ->
            compose.runOnIdle { page = index + 1 }
            compose.onRoot().assertIsDisplayed()
            save("onboarding-$name")
        }
    }

    @Test fun homeAndSettingsRenderAtNativePhoneSize() {
        var settings by mutableStateOf(false)
        compose.setContent { TrackSpeedTheme {
            if (settings) SettingsScreenContent(SettingsUiState(), onDistanceSelected = {},
                onStartTypeSelected = {}, onSpeedUnitSelected = {}, onThemeSelected = {}, onSensitivityChanged = {})
            else HomeContent(onPresetClick = {}, onCustomSessionClick = {}, onJoinSessionClick = {}, onSeeAllClick = {})
        } }
        compose.onNodeWithText("Join Session").assertIsDisplayed()
        save("home-empty-top")
        compose.onNodeWithText("Your training, saved here").performScrollTo().assertIsDisplayed()
        save("home-empty-history")
        compose.runOnIdle { settings = true }
        save("settings-top")
        compose.onNodeWithText("mph", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        save("settings-units")
        compose.onNodeWithText("Privacy Policy").performScrollTo().assertIsDisplayed()
        save("settings-bottom")
    }

    @Test fun localToolsRenderWithoutServices() {
        var page by mutableIntStateOf(0)
        val wind = WindAdjustmentViewModel()
        val converter = DistanceConverterViewModel()
        compose.setContent { TrackSpeedTheme {
            key(page) { when (page) {
                0 -> ToolsScreen({}, {}, {})
                1 -> WindAdjustmentScreen({}, wind)
                2 -> DistanceConverterScreen({}, converter)
            } }
        } }
        listOf("tools", "wind-adjustment", "distance-converter").forEachIndexed { index, name ->
            compose.runOnIdle { page = index }
            compose.onRoot().assertIsDisplayed()
            save(name)
        }
    }

    @Test fun paywallShowsLoadingAndUnavailablePricingWithoutInventedPrices() {
        var loading by mutableStateOf(true)
        compose.setContent { TrackSpeedTheme {
            TrackSpeedProPaywallContent(
                selectedPlan = PlanType.YEARLY, purchaseState = PurchaseState.Idle,
                isLoadingOfferings = loading, offeringsError = if (loading) null else "Unable to load pricing. Please retry.",
                weeklyPlan = PlanInfo(PlanType.WEEKLY, priceDisplay = "", periodDisplay = ""),
                yearlyPlan = PlanInfo(PlanType.YEARLY, priceDisplay = "", periodDisplay = ""),
                onClose = {}, onSelectPlan = {}, onPurchase = {}, onRestore = {}, onRetry = {}, onClearError = {},
                closeRevealDelayMillis = 0
            )
        } }
        save("paywall-loading")
        compose.runOnIdle { loading = false }
        compose.onNodeWithText("Load pricing").assertIsDisplayed()
        save("paywall-pricing-unavailable")
    }
}
