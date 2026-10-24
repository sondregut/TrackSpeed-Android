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
import com.trackspeed.android.ui.screens.help.*
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HelpFeedbackRenderTest {
    @get:Rule val compose = createComposeRule()
    private val metadata = mapOf("appVersion" to "1.0.10", "buildNumber" to "11", "device" to "Offline emulator", "osVersion" to "16")
    private fun save(name: String) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "overlay-review").apply { mkdirs() }
        File(root, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun nativeHubHasSeparatePrivateAndBoardActionsWithoutSubmitting() {
        val actions = mutableListOf<String>()
        compose.setContent { TrackSpeedTheme { HelpFeedbackContent(HelpFeedbackUiState(), metadata,
            onBack = {}, onKind = { actions += it.name }, onEdit = {}, onAddImages = {},
            onSend = { actions += "send" }, onDiscard = {}, onBrowseIdeas = { actions += "board" }, onEmail = {}) } }
        compose.onNodeWithText("Report a problem").performScrollTo().performClick()
        compose.onNodeWithText("Suggest an improvement").performScrollTo().performClick()
        compose.onNodeWithText("Browse ideas & updates").performScrollTo().performClick()
        compose.onNodeWithText("Ask a question").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("BUG", "IMPROVEMENT", "board", "QUESTION"), actions) }
        save("parity-help-hub")
    }
    @Test fun largeTextComposerRetainsSendActionAndIndependentConsent() {
        var draft by mutableStateOf(PrivateFeedbackDraft(kind = PrivateFeedbackKind.IMPROVEMENT, details = "Synthetic review idea"))
        var sends = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) { TrackSpeedTheme {
                Box(Modifier.requiredSize(320.dp, 560.dp)) { HelpFeedbackContent(HelpFeedbackUiState(kind = draft.kind, draft = draft), metadata,
                    onBack = {}, onKind = {}, onEdit = { change -> draft = draft.edited(change(draft), System.currentTimeMillis()) },
                    onAddImages = {}, onSend = { sends++ }, onDiscard = {}, onEmail = {}) }
            } }
        }
        compose.onNodeWithText("Send report").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("You may share this idea on the board").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Include technical details").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("What’s included").assertDoesNotExist()
        compose.onNodeWithText("Send report").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, sends); assertTrue(draft.publicationRequested); assertFalse(draft.includeDiagnostics) }
        save("parity-help-composer-large-text")
    }
    @Test fun errorRetainsDraftAndBackDoesNotSendOrDiscard() {
        val draft = PrivateFeedbackDraft(kind = PrivateFeedbackKind.BUG, details = "Synthetic report", attempted = true)
        var back = 0; var sends = 0; var discards = 0
        compose.setContent { TrackSpeedTheme { HelpFeedbackContent(
            HelpFeedbackUiState(kind = draft.kind, draft = draft, failure = PrivateFeedbackFailure.UNAVAILABLE), metadata,
            onBack = { back++ }, onKind = {}, onEdit = {}, onAddImages = {}, onSend = { sends++ },
            onDiscard = { discards++ }, onEmail = {}) } }
        compose.onNodeWithText("Contact support by email").performScrollTo().assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, back); assertEquals(0, sends); assertEquals(0, discards) }
        save("parity-help-failed-report-retained")
    }
    @Test fun discardRequiresExplicitConfirmationAndSendingLocksPayload() {
        val draft = PrivateFeedbackDraft(kind = PrivateFeedbackKind.QUESTION, details = "Synthetic question")
        var sending by mutableStateOf(false); var discards = 0
        compose.setContent { TrackSpeedTheme { HelpFeedbackContent(
            HelpFeedbackUiState(kind = draft.kind, draft = draft, sending = sending), metadata,
            onBack = {}, onKind = {}, onEdit = {}, onAddImages = {}, onSend = {}, onDiscard = { discards++ }, onEmail = {}) } }
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { assertEquals(0, discards) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Delete draft").performClick()
        compose.runOnIdle { assertEquals(1, discards); sending = true }
        compose.onNodeWithText("Sending…").assertIsNotEnabled()
        compose.onNodeWithText("Discard").assertIsNotEnabled()
    }
}
