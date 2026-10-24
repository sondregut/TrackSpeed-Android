package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.ui.util.RetainedLoadState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun mergeFrameScrubberLoadState(
    previous: RunFramesScrubberUiState,
    loaded: RetainedLoadState<RunFramesScrubberUiState>
): RunFramesScrubberUiState = (loaded.data ?: previous).copy(
    isLoading = loaded.isLoading,
    hasLoadedData = loaded.data != null,
    loadFailed = loaded.failed
)

internal fun loadedFrameScrubberState(run: RunEntity?): RunFramesScrubberUiState {
    val payload = parseLocalGateFramePayload(run?.localGateFramesDataJson)
    return RunFramesScrubberUiState(run, payload.frames, payload.calibrationSnapshots,
        isLoading = false, hasLoadedData = true)
}

internal data class LocalGateFramePayload(
    val frames: List<LocalGateFrameData> = emptyList(),
    val calibrationSnapshots: List<LocalGateCalibrationSnapshot> = emptyList()
)

private val localGateJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Both existing writers store homogeneous arrays. Decode their known shape
 * rather than interpreting unknown objects as default-valued calibration data.
 * Empty payloads remain valid; corrupt data is a recoverable read failure.
 */
internal fun parseLocalGateFramePayload(raw: String?): LocalGateFramePayload {
    if (raw.isNullOrBlank()) return LocalGateFramePayload()
    try {
        val array = localGateJson.parseToJsonElement(raw) as? JsonArray
            ?: throw IllegalArgumentException()
        if (array.isEmpty()) return LocalGateFramePayload()
        val objects = array.map { it as? JsonObject ?: throw IllegalArgumentException() }
        if (objects.all { "imagePath" in it }) {
            val frames = localGateJson.decodeFromString<List<LocalGateFrameData>>(raw)
            require(frames.all { it.imagePath.isNotBlank() })
            return LocalGateFramePayload(frames = frames)
        }
        val calibrationKeys = setOf("gateIndex", "role", "gatePosition", "velocityPxPerSec")
        if (objects.all { it.keys.containsAll(calibrationKeys) }) {
            return LocalGateFramePayload(
                calibrationSnapshots = localGateJson.decodeFromString<List<LocalGateCalibrationSnapshot>>(raw)
            )
        }
        throw IllegalArgumentException()
    } catch (_: Exception) {
        // No JSON, file path, or user data is attached to the UI-facing failure.
        throw IllegalArgumentException("Invalid frame scrubber payload")
    }
}
