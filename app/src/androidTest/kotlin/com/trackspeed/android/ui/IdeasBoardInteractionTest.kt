package com.trackspeed.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.cloud.ideas.*
import com.trackspeed.android.ui.screens.ideas.*
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production composables + synthetic state only: no Supabase, account, network or public mutations. */
@RunWith(AndroidJUnit4::class)
class IdeasBoardInteractionTest {
    @get:Rule val compose = createComposeRule()
    private fun post(id: String = "first", category: IdeaCategory = IdeaCategory.FEATURE) =
        IdeaPost(id, "A synthetic $id idea", "Make the session summary clearer", category, votes = 3,
            adminResponse = "We are considering this improvement.")

    @Test fun firstLoadAndFailureNeverClaimEmptyBoardAndRetryBackWork() {
        var state by mutableStateOf(IdeasBoardState(loading = true))
        var retries = 0; var backs = 0
        compose.setContent { TrackSpeedTheme {
            IdeasBoardContent(state, IdeasBoardActions(back = { backs++ }, refresh = { retries++ }))
        } }
        compose.onNodeWithText("Loading feedback…").assertIsDisplayed()
        compose.onNodeWithText("Start the conversation").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, error = IdeasError.LOAD_FAILED) }
        compose.onNodeWithTag("ideas-list").performScrollToNode(hasText("Try again"))
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Start the conversation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, backs) }
        save("parity-ideas-load-error")
    }

    @Test fun categoryAndSortActionsKeepPostsAndVoteUsesExplicitTap() {
        var state by mutableStateOf(IdeasBoardState(posts = listOf(post(), post("second", IdeaCategory.BUG)), loaded = true, votesKnown = true))
        val voted = mutableListOf<String>()
        compose.setContent { TrackSpeedTheme {
            IdeasBoardContent(state, IdeasBoardActions(category = { state = state.copy(category = it) },
                sort = { state = state.copy(sort = it) }, vote = { id ->
                    voted += id
                    state = state.copy(votedIds = setOf(id), posts = state.posts.map { if (it.id == id) it.copy(votes = 4) else it })
                }))
        } }
        compose.onNodeWithText("Bugs", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Newest", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ideas-list").performScrollToNode(hasTestTag("idea-vote-second"))
        compose.onNodeWithTag("idea-vote-second").assertIsEnabled().performClick().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("second"), voted)
            assertEquals(IdeaSort.NEWEST, state.sort)
            assertEquals(IdeaCategory.BUG, state.category)
        }
        save("parity-ideas-voted")
    }

    @Test fun composerExplainsPublicVisibilityStartsBlankAndRequiresValidInput() {
        var state by mutableStateOf(IdeasBoardState(draft = IdeaDraft()))
        var submissions = 0; var cancelled = 0
        compose.setContent { TrackSpeedTheme {
            IdeasBoardContent(state, IdeasBoardActions(edit = { state = state.copy(draft = it) },
                submit = { submissions++ }, cancelComposer = { cancelled++ }))
        } }
        compose.onNodeWithText("Other athletes can vote on your post. Don’t include private or sensitive information.").assertIsDisplayed()
        compose.onNodeWithTag("idea-name").performScrollTo()
        assertEquals("", compose.onNodeWithTag("idea-name").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Submit feedback").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("idea-title").performScrollTo().performTextInput("A synthetic idea")
        compose.onNodeWithTag("idea-details").performScrollTo().performTextInput("Make timing setup clearer")
        compose.onNodeWithText("Submit feedback").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, submissions); assertEquals(1, cancelled); assertEquals("", state.draft!!.publicName) }
        save("parity-ideas-public-composer")
    }

    @Test fun unconfirmedSubmissionOffersReadReconciliationWithoutAWriteRetry() {
        var checked = 0; var backs = 0
        compose.setContent { TrackSpeedTheme {
            IdeasBoardContent(IdeasBoardState(draft = IdeaDraft("Already attempted", "Details"),
                composerError = IdeasError.SUBMIT_UNCONFIRMED),
                IdeasBoardActions(submit = { fail("Must not automatically repeat public submission") },
                    checkBoard = { checked++ }, cancelComposer = { backs++ }))
        } }
        compose.onNodeWithText("Submit feedback").assertDoesNotExist()
        compose.onNodeWithText("Close and check board").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, checked); assertEquals(1, backs) }
    }

    @Test fun compactLargeTextComposerKeepsSubmitCancelAndErrorReachable() {
        var cancels = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                TrackSpeedTheme {
                    Box(Modifier.requiredSize(320.dp, 600.dp)) {
                        IdeasBoardContent(IdeasBoardState(draft = IdeaDraft("Synthetic", "Details"), submitting = true),
                            IdeasBoardActions(cancelComposer = { cancels++ }))
                    }
                }
            }
        }
        compose.onNodeWithText("Submitting…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
        save("parity-ideas-composer-large-text")
    }

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "overlay-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
