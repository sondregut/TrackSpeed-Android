package com.trackspeed.android.ui.screens.history

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScrubberImageLoaderTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun newFrameMetadataNeverReceivesThePreviousImageEvenBeforeSelectionEffectRuns() {
        val previous = ScrubberImageState(key = "frame-a", image = "image-a")
        val next = previous.forSelection("frame-b")
        assertNull(next.image)
        assertTrue(next.isLoading)
        assertFalse(next.failed)
        assertEquals("frame-b", next.key)
        assertEquals("image-a", previous.forSelection("frame-a").image)
    }

    @Test fun missingImageIsRetryableAndSuccessfulRetryClearsFailure() {
        val owner = scope()
        var attempts = 0
        try {
            val loader = ScrubberImageLoader(owner) { _: String -> if (++attempts == 1) null else "decoded" }
            loader.select("frame")
            assertTrue(loader.state.value.failed)
            assertFalse(loader.state.value.isLoading)
            loader.retry()
            assertEquals("decoded", loader.state.value.image)
            assertFalse(loader.state.value.failed)
            assertFalse(loader.state.value.isLoading)
        } finally { owner.cancel() }
    }

    @Test fun decodingExceptionDoesNotEscapeOrExposeItsPayload() {
        val owner = scope()
        try {
            val loader = ScrubberImageLoader<String>(owner) { throw IOException("private-decoder-detail") }
            loader.select("synthetic-frame")
            assertTrue(loader.state.value.failed)
            assertNull(loader.state.value.image)
            assertFalse(loader.state.value.toString().contains("private-decoder-detail"))
        } finally { owner.cancel() }
    }

    @Test fun lateNonCooperativeOldDecodeCannotOverwriteTheNewSelection() = runBlocking {
        withTimeout(2_000) {
            val owner = scope()
            val releaseOld = CompletableDeferred<Unit>()
            try {
                val loader = ScrubberImageLoader(owner) { key: String ->
                    if (key == "old") withContext(NonCancellable) { releaseOld.await() }
                    "decoded-$key"
                }
                loader.select("old")
                assertTrue(loader.state.value.isLoading)
                loader.select("new")
                assertEquals("decoded-new", loader.state.value.image)
                releaseOld.complete(Unit)
                yield()
                assertEquals("new", loader.state.value.key)
                assertEquals("decoded-new", loader.state.value.image)
                assertFalse(loader.state.value.failed)
            } finally { releaseOld.complete(Unit); owner.cancel() }
        }
    }

    @Test fun samePathRetryRejectsAnOlderDecodeGeneration() = runBlocking {
        withTimeout(2_000) {
            val owner = scope()
            val releaseOld = CompletableDeferred<Unit>()
            var attempts = 0
            try {
                val loader = ScrubberImageLoader(owner) { _: String ->
                    if (++attempts == 1) {
                        withContext(NonCancellable) { releaseOld.await() }
                        "old-image"
                    } else "replacement-image"
                }
                loader.select("same-path")
                loader.retry()
                releaseOld.complete(Unit)
                yield()
                assertEquals("replacement-image", loader.state.value.image)
                assertFalse(loader.state.value.isLoading)
                assertFalse(loader.state.value.failed)
            } finally { releaseOld.complete(Unit); owner.cancel() }
        }
    }

    @Test fun cancelledScreenDoesNotTurnThePendingReadIntoAnError() = runBlocking {
        val owner = scope()
        val suspended = CompletableDeferred<Unit>()
        val loader = ScrubberImageLoader(owner) { _: String -> suspended.await(); "unused" }
        loader.select("frame")
        owner.cancel()
        yield()
        assertFalse(loader.state.value.failed)
        assertNull(loader.state.value.image)
    }
}
