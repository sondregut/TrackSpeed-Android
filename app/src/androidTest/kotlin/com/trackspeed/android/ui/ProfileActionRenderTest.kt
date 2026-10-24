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
import com.trackspeed.android.ui.screens.profile.ProfileScreenContent
import com.trackspeed.android.ui.screens.profile.ProfileUiState
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production Profile UI with isolated account state; no auth, photo or backend operations. */
@RunWith(AndroidJUnit4::class)
class ProfileActionRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun guestSignInActionRemainsReachableAndSignedInStateRemovesIt() {
        var signedIn by mutableStateOf(false)
        var signInCount = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                TrackSpeedTheme {
                    Box(Modifier.requiredSize(320.dp, 600.dp)) {
                        ProfileScreenContent(uiState = ProfileUiState(isSignedIn = signedIn),
                            onSignInClick = { signInCount++ })
                    }
                }
            }
        }
        val signIn = compose.onNodeWithText("Sign in to sync data across devices")
        signIn.performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, signInCount) }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "overlay-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "parity-profile-guest-signin-large-text.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        compose.runOnIdle { signedIn = true }
        compose.onNodeWithText("Sign in to sync data across devices").assertDoesNotExist()
        compose.onNodeWithText("Sign Out").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Delete Account").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.runOnIdle { assertEquals(1, signInCount) }
    }
}
