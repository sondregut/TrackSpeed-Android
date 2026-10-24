package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.ui.screens.race.ConnectionHelpDialog
import com.trackspeed.android.ui.screens.race.JoinSessionStatusCard
import com.trackspeed.android.ui.screens.race.RacePairingActivity
import com.trackspeed.android.ui.screens.race.RefreshPairingPermissionOnResume
import org.junit.Assert.assertEquals
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated production UI; does not request permissions, scan or access devices. */
@RunWith(AndroidJUnit4::class)
class PairingPermissionRenderTest {
    @get:Rule val compose = createComposeRule()
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun save(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null),
            "pairing-permission-review").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        File(directory, "source.txt").writeText(
            "source=${BuildConfig.SOURCE_FINGERPRINT}\n" +
                "evidence=isolated native production UI; no permission or Bluetooth operation\n")
    }

    @Test fun deniedPermissionNeverLooksLikeScanningAndRecompositionUpdatesStatus() {
        val granted = mutableStateOf(false)
        val activity = mutableStateOf(RacePairingActivity.IDLE)
        compose.setContent { TrackSpeedTheme {
            JoinSessionStatusCard(activity.value, granted.value, sdkInt = 36)
        } }
        compose.onNodeWithText("Permission required").assertIsDisplayed()
        compose.onNodeWithText("Scanning with Bluetooth").assertDoesNotExist()
        compose.onNodeWithText("SEARCHING").assertDoesNotExist()
        compose.onAllNodes(progress).assertCountEquals(0)
        save("join-permission-denied")

        // The route remains open when the permission result starts discovery.
        compose.runOnIdle {
            granted.value = true
            activity.value = RacePairingActivity.SEARCHING
        }
        compose.onNodeWithText("Scanning with Bluetooth").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(1)
        compose.runOnIdle {
            granted.value = false
            activity.value = RacePairingActivity.IDLE
        }
        compose.onNodeWithText("Permission required").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun connectionFailureAndIdleStopClaimingDiscovery() {
        val activity = mutableStateOf(RacePairingActivity.SEARCHING)
        compose.setContent { TrackSpeedTheme {
            JoinSessionStatusCard(activity.value, true, sdkInt = 36)
        } }
        compose.onNodeWithText("Scanning with Bluetooth").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(1)
        compose.runOnIdle { activity.value = RacePairingActivity.ERROR }
        compose.onNodeWithText("Connection error").assertIsDisplayed()
        compose.onNodeWithText("Scanning with Bluetooth").assertDoesNotExist()
        compose.onAllNodes(progress).assertCountEquals(0)
        save("join-connection-error")
        compose.runOnIdle { activity.value = RacePairingActivity.IDLE }
        compose.onNodeWithText("Ready to connect").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun permissionRefreshWaitsForResumeAndDoesNotRepeatOrSurviveDisposal() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val granted = mutableStateOf(false)
        val mounted = mutableStateOf(true)
        var systemGrant = false
        var readCount = 0
        val changes = mutableListOf<Boolean>()
        compose.setContent { TrackSpeedTheme {
            if (mounted.value) {
                RefreshPairingPermissionOnResume(
                    currentlyGranted = granted.value,
                    readPermission = { readCount++; systemGrant },
                    onPermissionChanged = { changes.add(it); granted.value = it },
                    lifecycleOwner = owner
                )
            }
            Text(if (granted.value) "Granted" else "Denied")
        } }
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(0, readCount)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            systemGrant = true
            assertEquals(emptyList<Boolean>(), changes)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.onNodeWithText("Granted").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(true), changes)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(listOf(true), changes)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            systemGrant = false
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.onNodeWithText("Denied").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(true, false), changes); mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            val before = readCount
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            systemGrant = true
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals(before, readCount)
            assertEquals(listOf(true, false), changes)
        }
    }

    @Test fun legacyDeniedPermissionDoesNotAskForAnUnavailableNearbyDevicesGrant() {
        compose.setContent { TrackSpeedTheme {
            JoinSessionStatusCard(RacePairingActivity.IDLE, false, sdkInt = 30)
        } }
        compose.onNodeWithText("Bluetooth permissions required for device pairing").assertIsDisplayed()
        compose.onNodeWithText("Nearby devices permission is required to join or host a timing session.")
            .assertDoesNotExist()
        compose.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun android12AndLaterHelpRequestsNearbyDevicesWithoutLocation() {
        compose.setContent { TrackSpeedTheme { ConnectionHelpDialog({}, sdkInt = 31) } }
        compose.onNodeWithText("Nearby devices permission is required to join or host a timing session.")
            .assertIsDisplayed()
        compose.onNodeWithText("Make sure Location is enabled (required for Bluetooth scanning on Android)")
            .assertDoesNotExist()
        save("connection-help-nearby-devices")
    }

    @Test fun android11HelpPreservesRequiredLocationGuidance() {
        compose.setContent { TrackSpeedTheme { ConnectionHelpDialog({}, sdkInt = 30) } }
        compose.onNodeWithText("Make sure Location is enabled (required for Bluetooth scanning on Android)")
            .assertIsDisplayed()
        compose.onNodeWithText("Nearby devices permission is required to join or host a timing session.")
            .assertDoesNotExist()
        save("connection-help-legacy-location")
    }
}
