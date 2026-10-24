package com.trackspeed.android.ui

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.R
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.ui.screens.profile.ProfilePersonalBestEditor
import com.trackspeed.android.ui.screens.profile.ProfilePersonalBestValues
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Draft-only native editor tests. No DataStore or cloud integration is created. */
@RunWith(AndroidJUnit4::class)
class ProfilePersonalBestEditorTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private val initial = ProfilePersonalBestValues(SportDiscipline.SPRINT_100M, 10.50123456789,
        FlyingDistance.METERS_10, 1.0123456789)

    @Test fun invalidInputBlocksSaveAndBlankFieldsExplicitlyClearRecords() {
        var saved: ProfilePersonalBestValues? = null
        compose.setContent { TrackSpeedTheme {
            ProfilePersonalBestEditor(initial, {}, { saved = it })
        } }
        compose.onNode(hasSetTextAction() and hasText("10.50123456789")).performTextReplacement("1:60")
        compose.onNodeWithText(label(R.string.run_detail_save)).assertIsNotEnabled()
        compose.runOnIdle { assertNull(saved) }
        compose.onNode(hasSetTextAction() and hasText("1:60")).performTextClearance()
        compose.onNode(hasSetTextAction() and hasText("1.0123456789")).performScrollTo().performTextClearance()
        compose.onNodeWithText(label(R.string.run_detail_save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ProfilePersonalBestValues(SportDiscipline.SPRINT_100M), saved) }
    }

    @Test fun savedDraftRestoresAndSystemBackCancelsWithoutWriting() {
        var saves = 0
        var dismisses = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TrackSpeedTheme {
            ProfilePersonalBestEditor(initial, { dismisses++ }, { saves++ })
        } }
        compose.onNode(hasSetTextAction() and hasText("10.50123456789")).performTextReplacement("1:58,20")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction() and hasText("1:58,20")).assertExists()
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(1, dismisses) }
    }

    @Test fun cancelDoesNotWriteDraft() {
        var saved: ProfilePersonalBestValues? = null
        var dismisses = 0
        compose.setContent { TrackSpeedTheme {
            ProfilePersonalBestEditor(initial, { dismisses++ }, { saved = it })
        } }
        compose.onNodeWithText(label(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertNull(saved); assertEquals(1, dismisses) }
    }

    @Test fun unchangedSavePreservesExactCanonicalValues() {
        var saved: ProfilePersonalBestValues? = null
        compose.setContent { TrackSpeedTheme {
            ProfilePersonalBestEditor(initial, {}, { saved = it })
        } }
        compose.onNodeWithText(label(R.string.run_detail_save)).performClick()
        compose.runOnIdle { assertEquals(initial, saved) }
    }

    @Test fun compactLargeTextKeepsActionsAndFlyingFieldReachable() {
        var saved: ProfilePersonalBestValues? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                TrackSpeedTheme {
                    ProfilePersonalBestEditor(initial, {}, { saved = it },
                        modifier = Modifier.size(width = 320.dp, height = 480.dp))
                }
            }
        }
        compose.onNodeWithText(label(R.string.common_cancel)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.run_detail_save)).assertIsDisplayed()
        compose.onNodeWithText(FlyingDistance.METERS_20.rawValue).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("1.0123456789"))
            .performScrollTo().performTextReplacement("2,123456789")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText(label(R.string.common_cancel)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.run_detail_save)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(FlyingDistance.METERS_20, saved?.flyingDistance)
            assertEquals(2.123456789, saved!!.flyingPrSeconds!!, 0.0)
            assertEquals(initial.personalRecordSeconds, saved?.personalRecordSeconds)
        }
    }
}
