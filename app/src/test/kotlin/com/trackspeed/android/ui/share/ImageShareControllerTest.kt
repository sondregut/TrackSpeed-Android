package com.trackspeed.android.ui.share

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ImageShareControllerTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun savedStateBelongsToExactOutputAndReturningToSavedStyleRestoresIt() {
        val scope = scope(); val state = ImageShareController(scope)
        try {
            state.perform("midnight-output", ImageShareAction.SAVE) {}
            assertTrue(state.state.value.isSaved("midnight-output"))
            state.selectOutput("ocean-output")
            assertFalse(state.state.value.isSaved("ocean-output"))
            state.selectOutput("midnight-output")
            assertTrue(state.state.value.isSaved("midnight-output"))
        } finally { scope.cancel() }
    }

    @Test fun pendingAndAlreadySavedOutputsCannotStartDuplicateJobs() = runBlocking {
        val scope = scope(); val state = ImageShareController(scope); val result = CompletableDeferred<Unit>(); var calls = 0
        try {
            state.perform("output", ImageShareAction.SAVE) { calls++; result.await() }
            state.perform("output", ImageShareAction.SAVE) { calls++ }
            state.perform("output", ImageShareAction.SHARE) { calls++ }
            assertEquals(1, calls); assertNotNull(state.state.value.pending)
            result.complete(Unit); yield()
            state.perform("output", ImageShareAction.SAVE) { calls++ }
            assertEquals(1, calls); assertNull(state.state.value.pending)
            state.perform("output", ImageShareAction.SHARE) { calls++ }
            assertEquals(2, calls) // Sharing a saved image is still an explicit supported action.
        } finally { scope.cancel() }
    }

    @Test fun captureCacheOrChooserExceptionStopsBusyAndAllowsExplicitRetryWithoutSuccess() {
        val scope = scope(); val state = ImageShareController(scope); var calls = 0
        try {
            state.perform("output", ImageShareAction.SHARE) { calls++; throw IOException("sensitive synthetic diagnostic") }
            assertNull(state.state.value.pending); assertFalse(state.state.value.isSaved("output"))
            assertEquals(ImageShareFailure("output", ImageShareAction.SHARE), state.state.value.failure)
            assertFalse(state.state.value.toString().contains("sensitive")); assertEquals(1, calls)
            state.perform("output", ImageShareAction.SHARE) { calls++ }
            assertEquals(2, calls); assertNull(state.state.value.failure)
        } finally { scope.cancel() }
    }

    @Test fun failedSaveNeverMarksSavedAndOnlyExplicitRetryCanSave() {
        val scope = scope(); val state = ImageShareController(scope)
        try {
            state.perform("output", ImageShareAction.SAVE) { throw IOException("failed write") }
            assertFalse(state.state.value.isSaved("output")); assertNotNull(state.state.value.failure)
            state.perform("output", ImageShareAction.SAVE) {}
            assertTrue(state.state.value.isSaved("output")); assertNull(state.state.value.failure)
        } finally { scope.cancel() }
    }

    @Test fun changingStyleDuringSlowSaveCannotMarkNewStyleSaved() = runBlocking {
        val scope = scope(); val state = ImageShareController(scope); val write = CompletableDeferred<Unit>()
        try {
            state.perform("old", ImageShareAction.SAVE) { withContext(NonCancellable) { write.await() } }
            state.selectOutput("new")
            state.perform("new", ImageShareAction.SHARE) {}
            write.complete(Unit); yield()
            assertEquals("new", state.state.value.outputKey)
            assertFalse(state.state.value.isSaved("new")); assertFalse(state.state.value.isSaved("old"))
            assertNull(state.state.value.pending); assertNull(state.state.value.failure)
        } finally { scope.cancel() }
    }

    @Test fun backOrBackgroundCancelsBeforeChooserAndDoesNotAutoRetry() = runBlocking {
        val scope = scope(); val state = ImageShareController(scope); val render = CompletableDeferred<Unit>(); var opened = 0
        try {
            state.perform("output", ImageShareAction.SHARE) { render.await(); currentCoroutineContext().ensureActive(); opened++ }
            state.cancel(); render.complete(Unit); yield()
            assertEquals(0, opened); assertNull(state.state.value.pending)
            assertFalse(state.state.value.isSaved("output"))
        } finally { scope.cancel() }
    }

    @Test fun cancelledSaveExplainsUnconfirmedOutcomeWithoutClaimingUndoOrSuccess() = runBlocking {
        val scope = scope(); val state = ImageShareController(scope); val write = CompletableDeferred<Unit>()
        try {
            state.perform("output", ImageShareAction.SAVE) { write.await() }
            state.cancel(); write.complete(Unit); yield()
            assertEquals(ImageShareFailure("output", ImageShareAction.SAVE), state.state.value.failure)
            assertFalse(state.state.value.isSaved("output")); assertNull(state.state.value.pending)
        } finally { scope.cancel() }
    }

    @Test fun fingerprintSeparatesStyleMetadataAndNullsWithoutKeepingNamesInState() {
        val base = listOf("run-story", "midnight", "en-US", "1.234", "30.0", "Alex")
        val key = imageShareFingerprint(base)
        assertEquals(key, imageShareFingerprint(base.toList()))
        assertFalse(key.contains("Alex")); assertEquals(64, key.length)
        assertNotEquals(key, imageShareFingerprint(base.map { if (it == "midnight") "ocean" else it }))
        assertNotEquals(key, imageShareFingerprint(base.dropLast(1) + "Sam"))
        assertNotEquals(imageShareFingerprint(listOf("ab", "c")), imageShareFingerprint(listOf("a", "bc")))
        assertNotEquals(imageShareFingerprint(listOf(null)), imageShareFingerprint(listOf("")))
    }
}
