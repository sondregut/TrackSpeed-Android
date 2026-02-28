package com.trackspeed.android.ui.screens.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.trackspeed.android.data.export.CsvExporter
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AthleteChip(
    val id: String?,
    val name: String,
    val color: String?,
    val runCount: Int = 0
)

data class SessionDetailUiState(
    val session: TrainingSessionEntity? = null,
    val allRuns: List<RunEntity> = emptyList(),
    val runs: List<RunEntity> = emptyList(),
    val bestTime: Double? = null,
    val athletes: List<AthleteChip> = emptyList(),
    val selectedAthleteId: String? = null,
    val showAthleteColumn: Boolean = false,
    val speedUnit: String = "m/s"
)

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val csvExporter: CsvExporter,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    private val _selectedAthleteId = MutableStateFlow<String?>(null)

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
                _selectedAthleteId,
                settingsRepository.speedUnit
            ) { runs, selectedId, unit ->
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
                                name = run.athleteName ?: "Unknown",
                                color = run.athleteColor,
                                count = 1
                            )
                        }
                    }
                }
                val athletes = athleteMap.values.map {
                    AthleteChip(id = it.id, name = it.name, color = it.color, runCount = it.count)
                }

                // Filter by selected athlete
                val filtered = if (selectedId != null) {
                    runs.filter { it.athleteId == selectedId }
                } else {
                    runs
                }

                // Sort by time ascending (fastest first, matching iOS)
                val sorted = filtered.sortedBy { it.timeSeconds }

                SessionDetailUiState(
                    session = _uiState.value.session,
                    allRuns = runs,
                    runs = sorted,
                    bestTime = sorted.firstOrNull()?.timeSeconds,
                    athletes = athletes,
                    selectedAthleteId = selectedId,
                    showAthleteColumn = athletes.size > 1,
                    speedUnit = unit
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun setAthleteFilter(athleteId: String?) {
        _selectedAthleteId.value = athleteId
    }

    fun deleteSession() {
        viewModelScope.launch {
            sessionRepository.deleteSession(sessionId)
        }
    }

    suspend fun exportSessionCsv(): Uri? {
        return csvExporter.exportSession(sessionId)
    }
}

private data class AthleteChipBuilder(
    val id: String?,
    val name: String,
    val color: String?,
    var count: Int
)
