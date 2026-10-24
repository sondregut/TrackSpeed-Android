package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.ui.util.RetainedFlowLoader
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class FrameScrubberRecoveryTest {
    private fun run(raw: String? = null) = RunEntity(id = "synthetic-run", sessionId = "synthetic-session",
        runNumber = 1, timeSeconds = 2.123456789, distance = 20.0, startType = "flying",
        localGateRole = "finish", localGateFramesDataJson = raw)
    private val frameJson = """[{"imagePath":"synthetic-a.jpg","frameNumber":19,"timestampInterval":123.456789012,"occupancy":0.25,"futureField":true}]"""

    @Test fun absentPayloadAndEmptyArrayAreSuccessfulEmptyReadsButMissingRunIsDistinct() {
        for (raw in listOf(null, "", "   ", "[]")) {
            val loaded = loadedFrameScrubberState(run(raw))
            assertTrue(loaded.hasLoadedData)
            assertFalse(loaded.isLoading)
            assertFalse(loaded.loadFailed)
            assertNotNull(loaded.run)
            assertTrue(loaded.frames.isEmpty())
            assertTrue(loaded.calibrationSnapshots.isEmpty())
        }
        val missing = loadedFrameScrubberState(null)
        assertTrue(missing.hasLoadedData)
        assertNull(missing.run)
        assertFalse(missing.loadFailed)
    }

    @Test fun malformedAndUnrecognizedPayloadsDoNotInventEmptyDataOrCalibrationDefaults() {
        val invalid = listOf("{broken-private-content", "null", "{}", "[{}]", "[12]",
            """[{"imagePath":""}]""", """[{"imagePath":"a"},{"role":"finish","gateIndex":1}]""",
            """[{"imagePath":"a","frameNumber":"not-a-number"}]""")
        for (raw in invalid) {
            try { parseLocalGateFramePayload(raw); fail("Malformed data was accepted") }
            catch (error: IllegalArgumentException) {
                assertEquals("Invalid frame scrubber payload", error.message)
                assertNull(error.cause)
            }
        }
    }

    @Test fun frameMetadataOrderAndCanonicalTimingPrecisionRemainUnchanged() {
        val original = run(frameJson)
        val loaded = loadedFrameScrubberState(original)
        assertEquals(original, loaded.run)
        assertEquals(2.123456789, loaded.run!!.timeSeconds, 0.0)
        assertEquals(19L, loaded.frames.single().frameNumber)
        assertEquals(123.456789012, loaded.frames.single().timestampInterval, 0.0)
        assertEquals(0.25f, loaded.frames.single().occupancy, 0.0f)
        assertEquals("synthetic-a.jpg", loaded.frames.single().imagePath)
        assertTrue(loaded.calibrationSnapshots.isEmpty())
        val ordered = parseLocalGateFramePayload("""[{"imagePath":"later","frameNumber":21},{"imagePath":"earlier","frameNumber":19}]""")
        assertEquals(listOf(21L, 19L), ordered.frames.map { it.frameNumber })
    }

    @Test fun existingCalibrationWriterShapeStillLoadsItsActualValues() {
        val raw = """[{"gateIndex":2,"role":"finish","gatePosition":0.723456789,"velocityPxPerSec":123.456789,"crossingDirection":"right","workWidth":640,"thumbnailDebugJson":null,"futureField":1}]"""
        val payload = parseLocalGateFramePayload(raw)
        assertTrue(payload.frames.isEmpty())
        val calibration = payload.calibrationSnapshots.single()
        assertEquals(2, calibration.gateIndex)
        assertEquals("finish", calibration.role)
        assertEquals(0.723456789, calibration.gatePosition, 0.0)
        assertEquals(123.456789, calibration.velocityPxPerSec, 0.0)
        assertEquals(640, calibration.workWidth)
    }

    @Test fun databaseFailureStopsLoadingAndRetryCanLoadTheSavedFrames() {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        try {
            val loader = RetainedFlowLoader(owner) {
                flow {
                    if (++attempts == 1) throw IOException("private-database-payload")
                    emit(loadedFrameScrubberState(run(frameJson)))
                }
            }
            var state = mergeFrameScrubberLoadState(RunFramesScrubberUiState(), loader.state.value)
            assertTrue(state.loadFailed)
            assertFalse(state.isLoading)
            assertFalse(state.hasLoadedData)
            assertFalse(state.toString().contains("private-database-payload"))
            loader.retry()
            state = mergeFrameScrubberLoadState(state, loader.state.value)
            assertTrue(state.hasLoadedData)
            assertFalse(state.loadFailed)
            assertFalse(state.isLoading)
            assertEquals(19L, state.frames.single().frameNumber)
        } finally { owner.cancel() }
    }

    @Test fun corruptRefreshRetainsGoodFramesUntilSuccessfulRetryReplacesThem() {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        try {
            val loader = RetainedFlowLoader(owner) {
                flow { emit(loadedFrameScrubberState(run(when (++attempts) {
                    1 -> frameJson
                    2 -> "[{}]"
                    else -> """[{"imagePath":"replacement.jpg","frameNumber":20}]"""
                }))) }
            }
            var state = mergeFrameScrubberLoadState(RunFramesScrubberUiState(), loader.state.value)
            loader.retry()
            state = mergeFrameScrubberLoadState(state, loader.state.value)
            assertTrue(state.loadFailed)
            assertTrue(state.hasLoadedData)
            assertEquals(19L, state.frames.single().frameNumber)
            loader.retry()
            state = mergeFrameScrubberLoadState(state, loader.state.value)
            assertFalse(state.loadFailed)
            assertEquals(20L, state.frames.single().frameNumber)
        } finally { owner.cancel() }
    }

    @Test fun lateCancelledRepositoryReadCannotReplaceNewerRetry() = runBlocking {
        withTimeout(2_000) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val releaseOld = CompletableDeferred<Unit>()
            var attempts = 0
            try {
                val loader = RetainedFlowLoader(owner) {
                    flow {
                        val raw = if (++attempts == 1) {
                            withContext(NonCancellable) { releaseOld.await() }
                            frameJson
                        } else """[{"imagePath":"newer.jpg","frameNumber":22}]"""
                        emit(loadedFrameScrubberState(run(raw)))
                    }
                }
                loader.retry()
                assertEquals(22L, loader.state.value.data!!.frames.single().frameNumber)
                releaseOld.complete(Unit)
                yield()
                assertEquals(22L, loader.state.value.data!!.frames.single().frameNumber)
                assertFalse(loader.state.value.failed)
            } finally { releaseOld.complete(Unit); owner.cancel() }
        }
    }
}
