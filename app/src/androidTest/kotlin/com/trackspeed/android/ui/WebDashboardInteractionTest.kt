package com.trackspeed.android.ui

import android.content.Intent
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
import com.trackspeed.android.cloud.dashboard.DashboardError
import com.trackspeed.android.cloud.dashboard.DashboardLink
import com.trackspeed.android.ui.screens.dashboard.*
import com.trackspeed.android.ui.screens.settings.SettingsDashboardEntry
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated production UI and Intent construction only: no account, HTTP or startActivity calls. */
@RunWith(AndroidJUnit4::class)
class WebDashboardInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsEntryRequiresNamedAccountBeforeNavigation() {
        var available by mutableStateOf(false); var opened = 0
        compose.setContent { TrackSpeedTheme { SettingsDashboardEntry(available) { opened++ } } }
        compose.onNodeWithText("Web Dashboard").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { available = true }
        compose.onNodeWithText("Web Dashboard").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun signedOutScreenOffersNoRequestActionsAndBackRemainsAvailable() {
        var backs = 0
        compose.setContent { TrackSpeedTheme {
            WebDashboardContent(WebDashboardUiState(), { backs++ }, { fail("No open request") },
                { fail("No share request") }, { fail("No retry request") })
        } }
        compose.onNodeWithText("Sign in to use the web dashboard").assertIsDisplayed()
        compose.onNodeWithText("Open here").assertDoesNotExist()
        compose.onNodeWithText("Send sign-in link to my computer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
        save("parity-dashboard-signed-out")
    }

    @Test fun compactLargeTextActionsLoadingErrorAndRetryRemainReachable() {
        var state by mutableStateOf(WebDashboardUiState(signedIn = true))
        val actions = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                TrackSpeedTheme {
                    Box(Modifier.requiredSize(320.dp, 600.dp)) {
                        WebDashboardContent(state, { actions += "back" },
                            { actions += "open"; state = state.copy(pending = DashboardAction.OPEN) },
                            { actions += "share"; state = state.copy(pending = DashboardAction.SHARE) },
                            { actions += "retry"; state = state.copy(pending = DashboardAction.OPEN, error = null) })
                    }
                }
            }
        }
        compose.onNodeWithText("Open here").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Open here").assertIsNotEnabled()
        compose.onNodeWithText("Send sign-in link to my computer").assertIsNotEnabled()
        compose.onNodeWithText("Creating sign-in link…").performScrollTo().assertIsDisplayed()
        save("parity-dashboard-loading-large-text")
        compose.runOnIdle { state = state.copy(pending = null, error = DashboardError.OFFLINE) }
        compose.onNodeWithText("You are offline. Connect to the internet and try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.runOnIdle { state = state.copy(pending = null, error = null) }
        compose.onNodeWithText("Send sign-in link to my computer").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf("open", "retry", "share", "back"), actions) }
    }

    @Suppress("DEPRECATION")
    @Test fun externalActionsConstructBrowserAndChooserIntentsWithoutLaunchingThem() {
        val url = "https://mytrackspeed.com/dashboard#synthetic-native-test-token"
        val link = checkNotNull(DashboardLink.validated(url))
        val open = dashboardExternalIntent(DashboardLaunch(DashboardAction.OPEN, link), "Send to your own device")
        assertEquals(Intent.ACTION_VIEW, open.action)
        assertTrue(open.categories?.contains(Intent.CATEGORY_BROWSABLE) == true)
        assertEquals(url, open.dataString)
        val chooser = dashboardExternalIntent(DashboardLaunch(DashboardAction.SHARE, link), "Send to your own device")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(url, send.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(send.component)
        assertNull(send.`package`)
    }

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "overlay-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
