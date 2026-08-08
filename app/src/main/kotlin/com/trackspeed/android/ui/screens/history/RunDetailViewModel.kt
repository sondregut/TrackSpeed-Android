package com.trackspeed.android.ui.screens.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.cloud.RaceEventService
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.diagnostics.DetectionReviewLogStore
import com.trackspeed.android.ui.components.DetectionReviewSubmission
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RunDetailUiState(
    val run: RunEntity? = null,
    val session: TrainingSessionEntity? = null,
    val speedFormatted: String = "--",
    val speedUnit: String = "m/s",
    val showSpeedInResults: Boolean = SettingsRepository.Defaults.SHOW_SPEED_IN_RESULTS,
    val isLoading: Boolean = true,
    val deleted: Boolean = false,
    val detectionDiagnosticsEnabled: Boolean = SettingsRepository.Defaults.DETECTION_DIAGNOSTICS_ENABLED
)

@HiltViewModel
class RunDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val raceEventService: RaceEventService,
    private val detectionReviewLogStore: DetectionReviewLogStore
) : ViewModel() {

    private val runId: String = checkNotNull(savedStateHandle["runId"])
    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    private val _uiState = MutableStateFlow(RunDetailUiState())
    val uiState: StateFlow<RunDetailUiState> = _uiState.asStateFlow()

    init {
        loadRun()
    }

    private fun loadRun() {
        viewModelScope.launch {
            val run = sessionRepository.getRunById(runId)
            val session = sessionRepository.getSession(sessionId)
            val unit = settingsRepository.speedUnit.first()
            val showSpeedInResults = settingsRepository.showSpeedInResults.first()
            val detectionDiagnosticsEnabled = settingsRepository.detectionDiagnosticsEnabled.first()

            val speedFormatted = if (run != null && run.timeSeconds > 0 && run.distance > 0) {
                val speedMs = run.distance / run.timeSeconds
                when (unit) {
                    "km/h" -> String.format(java.util.Locale.getDefault(), "%.1f", speedMs * 3.6)
                    "mph" -> String.format(java.util.Locale.getDefault(), "%.1f", speedMs * 2.23694)
                    else -> String.format(java.util.Locale.getDefault(), "%.1f", speedMs)
                }
            } else {
                "--"
            }

            _uiState.update {
                it.copy(
                    run = run,
                    session = session,
                    speedFormatted = speedFormatted,
                    speedUnit = unit,
                    showSpeedInResults = showSpeedInResults,
                    detectionDiagnosticsEnabled = detectionDiagnosticsEnabled,
                    isLoading = false
                )
            }
        }
    }

    fun deleteRun() {
        viewModelScope.launch {
            sessionRepository.deleteRun(runId)
            _uiState.update { it.copy(deleted = true) }
        }
    }

    fun updateRunDistance(newDistance: Double) {
        viewModelScope.launch {
            sessionRepository.updateRunDistance(runId, newDistance)
            loadRun()
        }
    }

    fun submitCrossingReview(submission: DetectionReviewSubmission) {
        viewModelScope.launch {
            val target = submission.target
            detectionReviewLogStore.appendForContext(
                sessionId = target.sessionId,
                mode = target.mode,
                role = target.gateLabel,
                gateIndex = 0,
                message = submission.rawMessage
            )
            val uploaded = raceEventService.insertCrossingReviewMark(
                sessionId = target.sessionId,
                runNumber = target.runNumber,
                gateLabel = target.gateLabel,
                target = target.target,
                mode = target.mode,
                crossingDirection = target.crossingDirection,
                issue = submission.issue,
                actualX = submission.actualX?.toDouble(),
                actualY = submission.actualY?.toDouble(),
                detectorX = target.detectorX.toDouble(),
                detectorY = target.detectorY?.toDouble(),
                deltaX = submission.actualX?.let { it.toDouble() - target.detectorX.toDouble() },
                deltaY = submission.actualY?.let { actualY ->
                    target.detectorY?.let { actualY.toDouble() - it.toDouble() }
                },
                interpolationAlpha = target.interpolationAlpha,
                framePick = target.framePick,
                s0 = target.s0,
                s1 = target.s1,
                isFrontCamera = target.isFrontCamera,
                detectionDistance = target.detectionDistance,
                workWidth = target.workWidth,
                exposureMs = target.exposureMs,
                iso = target.iso,
                detectorTriggerFramePts = target.detectorTriggerFramePts,
                chosenThumbnailFramePts = target.chosenThumbnailFramePts,
                savedThumbnailFramePts = target.savedThumbnailFramePts,
                note = submission.note.ifBlank { null },
                rawMessage = submission.rawMessage,
                rawImageData = submission.rawImageData,
                reviewImageData = submission.reviewImageData
            )
            val uploadStatus = if (uploaded) "complete" else "failed"
            val uploadTag = if (submission.rawMessage.startsWith("[DETECTION-NOTE]")) {
                "DETECTION-NOTE-UPLOAD"
            } else {
                "DETECTION-MARK-UPLOAD"
            }
            detectionReviewLogStore.appendForContext(
                sessionId = target.sessionId,
                mode = target.mode,
                role = target.gateLabel,
                gateIndex = 0,
                message = "[$uploadTag] event=$uploadStatus schema=4 markerKey=${target.runId}:${target.gateLabel}"
            )
        }
    }
}
