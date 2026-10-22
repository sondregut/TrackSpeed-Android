package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.ui.screens.onboarding.steps.GettingStartedGuide
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnboardingGuideRenderTest {
    @get:Rule val compose=createComposeRule()
    private fun save(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=File(context.getExternalFilesDir(null),"overlay-review").apply{mkdirs()}
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(output,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test fun fourPageGuideStartsSessionAndSupportsCompactScreen() {
        var started=false
        compose.setContent { MaterialTheme { GettingStartedGuide({}, {started=true}) } }
        compose.onNodeWithText("Continue").assertIsDisplayed()
        save("onboarding-guide-1")
        repeat(3) { index ->
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
            save("onboarding-guide-${index+2}")
        }
        compose.onNodeWithText("Start a session").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(started) }
    }
    @Test fun largeTextKeepsSkipAndContinueReachable() {
        var skipped=false
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)) {
                MaterialTheme { GettingStartedGuide({skipped=true},{}) }
            }
        }
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
        save("onboarding-guide-large-text")
        compose.onNodeWithText("Skip").performClick()
        compose.runOnIdle { assertTrue(skipped) }
    }
}
