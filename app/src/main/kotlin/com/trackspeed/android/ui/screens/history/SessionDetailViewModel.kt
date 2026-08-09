package com.trackspeed.android.ui.screens.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.content.Context
import com.trackspeed.android.R
import com.trackspeed.android.cloud.RaceEventService
import com.trackspeed.android.data.export.CsvExporter
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.diagnostics.DetectionReviewLogStore
import com.trackspeed.android.ui.components.DetectionReviewSubmission
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AthleteChip(
    val id: String?,
    val name: String,
    val color: String?,
    val runCount: Int = 0
)

enum class SessionRunSort {
    RUN_NUMBER,
    FASTEST_FIRST,
    SLOWEST_FIRST
}

private data class SessionRunSelection(
    val athleteId: String?,
    val sort: SessionRunSort
)

data class SessionDetailUiState(
    val session: TrainingSessionEntity? = null,
    val allRuns: List<RunEntity> = emptyList(),
    val runs: List<RunEntity> = emptyList(),
    val bestTime: Double? = null,
    val athletes: List<AthleteChip> = emptyList(),
    val selectedAthleteId: String? = null,
    val runSort: SessionRunSort = SessionRunSort.RUN_NUMBER,
    val showAthleteColumn: Boolean = false,
    val speedUnit: String = "m/s",
    val showSpeedInResults: Boolean = SettingsRepository.Defaults.SHOW_SPEED_IN_RESULTS,
    val detectionDiagnosticsEnabled: Boolean = SettingsRepository.Defaults.DETECTION_DIAGNOSTICS_ENABLED,
    val deleted: Boolean = false
)

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val csvExporter: CsvExporter,
    private val settingsRepository: SettingsRepository,
    private val raceEventService: RaceEventService,
    private val detectionReviewLogStore: DetectionReviewLogStore
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    private val _selectedAthleteId = MutableStateFlow<String?>(null)
    private val _runSort = MutableStateFlow(SessionRunSort.RUN_NUMBER)

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = sessionRepository.getSession(sessionId)
            _uiState.update { it.copy(session = session) }
        }

        viewModelScope.launch {
            combine(
                sessionRepository.getRunsForSession(sessionId),
                combine(_selectedAthleteId, _runSort) { athleteId, sort ->
                    SessionRunSelection(athleteId, sort)
                },
                settingsRepository.speedUnit,
                settingsRepository.showSpeedInResults,
                settingsRepository.detectionDiagnosticsEnabled
            ) { runs, selection, unit, showSpeedInResults, detectionDiagnosticsEnabled ->
                // Build athlete list with run counts
                val athleteMap = linkedMapOf<String?, AthleteChipBuilder>()
                for (run in runs) {
                    if (run.athleteId != null) {
                        val existing = athleteMap[run.athleteId]
                        if (existing != null) {
                            existing.count++
                        } else {
                            athleteMap[run.athleteId] = AthleteChipBuilder(
                                id = run.athleteId,
                                name = run.athleteName ?: context.getString(R.string.stats_unknown_athlete),
                                color = run.athleteColor,
                                count = 1
                            )
                        }
                    }
                }
                val athletes = athleteMap.values.map {
                    AthleteChip(id = it.id, name = it.name, color = it.color, runCount = it.count)
                }.sortedBy { it.name.lowercase() }

                // iOS keeps the athlete-filtered baseline in run-number order, then
                // applies the selected display sort on top.
                val selectedAthleteId = selection.athleteId.takeIf { selectedId ->
                    athletes.any { it.id == selectedId }
                }

                val filtered = if (selectedAthleteId != null) {
                    runs.filter { it.athleteId == selectedAthleteId }
                } else {
                    runs
                }.sortedBy { it.runNumber }

                val sorted = when (selection.sort) {
                    SessionRunSort.RUN_NUMBER -> filtered
                    SessionRunSort.FASTEST_FIRST -> filtered.sortedBy { it.timeSeconds }
                    SessionRunSort.SLOWEST_FIRST -> filtered.sortedByDescending { it.timeSeconds }
                }
                val bestTime = filtered.minOfOrNull { it.timeSeconds }

                SessionDetailUiState(
                    session = _uiState.value.session,
                    allRuns = runs,
                    runs = sorted,
                    bestTime = bestTime,
                    athletes = athletes,
                    selectedAthleteId = selectedAthleteId,
                    runSort = selection.sort,
                    showAthleteColumn = athletes.size > 1,
                    speedUnit = unit,
                    showSpeedInResults = showSpeedInResults,
                    detectionDiagnosticsEnabled = detectionDiagnosticsEnabled,
                    deleted = _uiState.value.deleted
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun setAthleteFilter(athleteId: String?) {
        _selectedAthleteId.value = athleteId
    }

    fun setRunSort(sort: SessionRunSort) {
        _runSort.value = sort
    }

    fun deleteSession() {
        viewModelScope.launch {
            sessionRepository.deleteSession(sessionId)
            _uiState.update { it.copy(deleted = true) }
        }
    }

    fun deleteRun(runId: String) {
        viewModelScope.launch {
            sessionRepository.deleteRun(runId)
        }
    }

    suspend fun exportSessionCsv(): Uri? {
        return csvExporter.exportSession(sessionId)
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

private data class AthleteChipBuilder(
    val id: String?,
    val name: String,
    val color: String?,
    var count: Int
)
