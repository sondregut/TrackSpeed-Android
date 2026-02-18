package com.trackspeed.android.ui.screens.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionDetailUiState(
    val session: TrainingSessionEntity? = null,
    val runs: List<RunEntity> = emptyList(),
    val bestTime: Double? = null,
    val averageTime: Double? = null
)

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = sessionRepository.getSession(sessionId)
            _uiState.update { it.copy(session = session) }
        }

        viewModelScope.launch {
            sessionRepository.getRunsForSession(sessionId).collect { runs ->
                val times = runs.map { it.timeSeconds }
                _uiState.update {
                    it.copy(
                        runs = runs,
                        bestTime = times.minOrNull(),
                        averageTime = if (times.isNotEmpty()) times.average() else null
                    )
                }
            }
        }
    }

    fun deleteSession() {
        viewModelScope.launch {
            sessionRepository.deleteSession(sessionId)
        }
    }
}
