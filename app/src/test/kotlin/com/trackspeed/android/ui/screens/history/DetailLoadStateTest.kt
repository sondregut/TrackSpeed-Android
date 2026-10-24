package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.ui.util.RetainedFlowLoader
import com.trackspeed.android.ui.util.RetainedLoadState
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class DetailLoadStateTest {
    private val session = TrainingSessionEntity(id = "session", date = 0L, distance = 20.0, startType = "flying")
    private val run = RunEntity(id = "run", sessionId = "session", runNumber = 1,
        timeSeconds = 2.35, distance = 20.0, startType = "flying")

    @Test fun pendingAndFailedSessionReadsNeverClaimTheSessionIsEmpty() {
        val initial = SessionDetailUiState()
        assertTrue(initial.isLoading)
        assertFalse(initial.hasLoadedData)
        assertFalse(initial.showEmptyResults)
        val failed = mergeSessionDetailLoadState(initial, RetainedLoadState(isLoading = false, failed = true))
        assertFalse(failed.isLoading)
        assertTrue(failed.loadFailed)
        assertFalse(failed.hasLoadedData)
        assertFalse(failed.showEmptyResults)
    }

    @Test fun onlyASuccessfullyReadEmptySessionGetsTheEmptyMessage() {
        val snapshot = SessionDetailUiState(session = session)
        val loaded = mergeSessionDetailLoadState(SessionDetailUiState(), RetainedLoadState(snapshot, isLoading = false))
        assertTrue(loaded.hasLoadedData)
        assertTrue(loaded.showEmptyResults)
        val refreshing = mergeSessionDetailLoadState(loaded, RetainedLoadState(snapshot, isLoading = true))
        assertFalse(refreshing.showEmptyResults)
        val failedRefresh = mergeSessionDetailLoadState(refreshing, RetainedLoadState(snapshot, isLoading = false, failed = true))
        assertEquals(session, failedRefresh.session)
        assertFalse(failedRefresh.showEmptyResults)
    }

    @Test fun successfulMissingRecordsAreNeitherLoadingNorReadErrors() {
        val missingRun = mergeRunDetailLoadState(RunDetailUiState(), RetainedLoadState(RunDetailUiState(), isLoading = false))
        assertNull(missingRun.run)
        assertTrue(missingRun.hasLoadedData)
        assertFalse(missingRun.isLoading)
        assertFalse(missingRun.loadFailed)
        val missingSession = mergeSessionDetailLoadState(SessionDetailUiState(), RetainedLoadState(SessionDetailUiState(), isLoading = false))
        assertNull(missingSession.session)
        assertTrue(missingSession.hasLoadedData)
        assertFalse(missingSession.loadFailed)
        assertFalse(missingSession.showEmptyResults)
    }

    @Test fun failedRunRefreshAndRetryRetainActualResultUntilReplacementArrives() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val replacement = MutableSharedFlow<RunDetailUiState>()
        var reads = 0
        try {
            val snapshot = RunDetailUiState(run = run, session = session, speedFormatted = "8.5")
            val loader = RetainedFlowLoader(owner) {
                if (++reads == 1) flow { emit(snapshot); throw IOException("private-database-payload") }
                else replacement
            }
            var state = mergeRunDetailLoadState(RunDetailUiState(), loader.state.value)
            assertTrue(state.loadFailed)
            assertFalse(state.isLoading)
            assertEquals(run, state.run)
            assertEquals("8.5", state.speedFormatted)
            assertFalse(state.toString().contains("private-database-payload"))
            loader.retry()
            state = mergeRunDetailLoadState(state, loader.state.value)
            assertTrue(state.isLoading)
            assertFalse(state.loadFailed)
            assertEquals(run, state.run)
            val updated = run.copy(distance = 30.0)
            replacement.emit(snapshot.copy(run = updated, speedFormatted = "12.8"))
            yield()
            state = mergeRunDetailLoadState(state, loader.state.value)
            assertEquals(updated, state.run)
            assertEquals("12.8", state.speedFormatted)
            assertFalse(state.isLoading)
            assertFalse(state.loadFailed)
        } finally { owner.cancel() }
    }

    @Test fun sessionRefreshRetainsRowsAndAReadCannotUndoCompletedDeletion() {
        val previous = SessionDetailUiState(session = session, allRuns = listOf(run), runs = listOf(run), deleted = true)
        val snapshot = previous.copy(deleted = false)
        val failed = mergeSessionDetailLoadState(previous, RetainedLoadState(snapshot, isLoading = false, failed = true))
        assertEquals(listOf(run), failed.runs)
        assertTrue(failed.deleted)
        assertTrue(failed.loadFailed)
        val previousRun = RunDetailUiState(run = run, deleted = true)
        val refreshedRun = mergeRunDetailLoadState(previousRun, RetainedLoadState(previousRun.copy(deleted = false), isLoading = false))
        assertTrue(refreshedRun.deleted)
    }
}
