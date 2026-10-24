package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.R
import com.trackspeed.android.billing.EarlyReleaseAccess
import com.trackspeed.android.ui.screens.profile.ProfileScreenContent
import com.trackspeed.android.ui.screens.profile.ProfileUiState
import com.trackspeed.android.ui.screens.profile.profileAccessColors
import com.trackspeed.android.ui.theme.AppTheme
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The production included-access label, rendered without account or billing calls. */
@RunWith(AndroidJUnit4::class)
class ProfileAccessReadabilityRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun includedAccessUsesReadableLightSuccessInk() = verify(AppTheme.LIGHT)
    @Test fun includedAccessKeepsDarkSuccessInk() = verify(AppTheme.DARKGOLD)

    private fun verify(theme: AppTheme) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(if (EarlyReleaseAccess.isEnabled)
            R.string.early_release_all_features else R.string.profile_trackspeed_pro)
        compose.setContent {
            TrackSpeedTheme(theme) {
                Box(Modifier.requiredSize(320.dp, 600.dp)) {
                    ProfileScreenContent(uiState = ProfileUiState(isProUser = true))
                }
            }
        }
        val node = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        val pixels = node.captureToImage().toPixelMap()
        val expected = profileAccessColors(theme == AppTheme.DARKGOLD).content.toArgb()
        var inkPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y].toArgb() == expected) inkPixels++
        }
        assertTrue("The rendered access label must use its paired success ink", inkPixels >= 20)
        val directory = File(context.getExternalFilesDir(null), "neutral-action-chrome").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "profile-access-${theme.key}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
