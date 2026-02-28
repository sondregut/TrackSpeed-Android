package com.trackspeed.android.ui.screens.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
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
    val isLoading: Boolean = true,
    val deleted: Boolean = false
)

@HiltViewModel
class RunDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository
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

            val speedFormatted = if (run != null && run.timeSeconds > 0 && run.distance > 0) {
                val speedMs = run.distance / run.timeSeconds
                when (unit) {
                    "km/h" -> String.format("%.1f", speedMs * 3.6)
                    "mph" -> String.format("%.1f", speedMs * 2.23694)
                    else -> String.format("%.1f", speedMs)
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
}
