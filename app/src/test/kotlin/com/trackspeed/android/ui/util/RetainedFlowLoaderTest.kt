package com.trackspeed.android.ui.util

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class RetainedFlowLoaderTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun noDatabaseEmissionIsLoadingNotAnEmptyHistory() {
        val owner = scope()
        try {
            val loader = RetainedFlowLoader(owner) { MutableSharedFlow<List<Int>>() }
            assertNull(loader.state.value.data)
            assertTrue(loader.state.value.isLoading)
            assertFalse(loader.state.value.failed)
        } finally { owner.cancel() }
    }

    @Test fun successfulEmptyReadIsDifferentFromLoading() {
        val owner = scope()
        try {
            val loader = RetainedFlowLoader(owner) { flowOf(emptyList<Int>()) }
            assertEquals(emptyList<Int>(), loader.state.value.data)
            assertFalse(loader.state.value.isLoading)
            assertFalse(loader.state.value.failed)
        } finally { owner.cancel() }
    }

    @Test fun firstReadFailureDoesNotInventAnEmptyDatasetOrExposeErrorPayload() {
        val owner = scope()
        try {
            val loader = RetainedFlowLoader<List<Int>>(owner) { flow { throw IOException("private-database-detail") } }
            assertNull(loader.state.value.data)
            assertTrue(loader.state.value.failed)
            assertFalse(loader.state.value.isLoading)
            assertFalse(loader.state.value.toString().contains("private-database-detail"))
        } finally { owner.cancel() }
    }

    @Test fun failedRefreshRetainsTheLastSuccessfulResults() {
        val owner = scope()
        try {
            val loader = RetainedFlowLoader(owner) { flow { emit(listOf(1, 2)); throw IOException() } }
            assertEquals(listOf(1, 2), loader.state.value.data)
            assertTrue(loader.state.value.failed)
            assertFalse(loader.state.value.isLoading)
        } finally { owner.cancel() }
    }

    @Test fun retryKeepsResultsUntilReplacementArrivesThenClearsFailure() = runBlocking {
        val owner = scope()
        val replacement = MutableSharedFlow<List<Int>>()
        var attempts = 0
        try {
            val loader = RetainedFlowLoader(owner) {
                if (++attempts == 1) flow { emit(listOf(1)); throw IOException() } else replacement
            }
            loader.retry()
            assertEquals(listOf(1), loader.state.value.data)
            assertTrue(loader.state.value.isLoading)
            assertFalse(loader.state.value.failed)
            replacement.emit(listOf(2, 3))
            yield()
            assertEquals(listOf(2, 3), loader.state.value.data)
            assertFalse(loader.state.value.isLoading)
            assertFalse(loader.state.value.failed)
        } finally { owner.cancel() }
    }

    @Test fun repeatedRetryCancelsPreviousSubscriptionWithoutDuplicatingReaders() = runBlocking {
        val owner = scope()
        val source = MutableSharedFlow<List<Int>>()
        try {
            val loader = RetainedFlowLoader(owner) { source }
            repeat(3) { loader.retry() }
            yield()
            assertEquals(1, source.subscriptionCount.value)
            source.emit(listOf(4))
            yield()
            assertEquals(listOf(4), loader.state.value.data)
            assertFalse(loader.state.value.failed)
        } finally { owner.cancel() }
        assertEquals(0, source.subscriptionCount.value)
    }

    @Test fun cancellingScreenOwnerDoesNotBecomeAReadError() = runBlocking {
        val owner = scope()
        val source = MutableSharedFlow<List<Int>>()
        val loader = RetainedFlowLoader(owner) { source }
        source.emit(listOf(7))
        yield()
        owner.cancel()
        assertEquals(listOf(7), loader.state.value.data)
        assertFalse(loader.state.value.failed)
    }

    @Test fun synchronousFactoryFailureCanBeRetried() {
        val owner = scope()
        var attempts = 0
        try {
            val loader = RetainedFlowLoader(owner) {
                if (++attempts == 1) throw IOException()
                flowOf(listOf(9))
            }
            assertTrue(loader.state.value.failed)
            loader.retry()
            assertEquals(listOf(9), loader.state.value.data)
            assertFalse(loader.state.value.failed)
        } finally { owner.cancel() }
    }

    @Test fun completionWithoutValueIsRetryableNotAnEmptyHistory() {
        val owner = scope()
        try {
            val loader = RetainedFlowLoader(owner) { emptyFlow<List<Int>>() }
            assertNull(loader.state.value.data)
            assertTrue(loader.state.value.failed)
            assertFalse(loader.state.value.isLoading)
        } finally { owner.cancel() }
    }
}
