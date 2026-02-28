package com.trackspeed.android.ui.screens.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.ui.components.ShareCardData
import com.trackspeed.android.ui.components.ShareCardTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class ShareResultUiState(
    val cardData: ShareCardData? = null,
    val selectedTheme: ShareCardTheme = ShareCardTheme.MIDNIGHT,
    val isLoading: Boolean = true,
    val isSavedToGallery: Boolean = false,
    val saveError: String? = null
)

@HiltViewModel
class ShareResultViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val runId: String = checkNotNull(savedStateHandle["runId"])
    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    private val _uiState = MutableStateFlow(ShareResultUiState())
    val uiState: StateFlow<ShareResultUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val run = sessionRepository.getRunById(runId)
            val session = sessionRepository.getSession(sessionId)
            val speedUnit = settingsRepository.speedUnit.first()

            if (run != null && session != null) {
                val cardData = buildCardData(run, session, speedUnit)
                _uiState.update {
                    it.copy(
                        cardData = cardData,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun selectTheme(theme: ShareCardTheme) {
        _uiState.update { it.copy(selectedTheme = theme) }
    }

    fun onSavedToGallery() {
        _uiState.update { it.copy(isSavedToGallery = true) }
    }

    fun onSaveError(message: String) {
        _uiState.update { it.copy(saveError = message) }
    }

    fun clearSaveError() {
        _uiState.update { it.copy(saveError = null) }
    }

    private fun buildCardData(
        run: RunEntity,
        session: TrainingSessionEntity,
        speedUnit: String
    ): ShareCardData {
        val speedMs = if (run.timeSeconds > 0) run.distance / run.timeSeconds else null
        val speedValue = when (speedUnit) {
            "km/h" -> speedMs?.let { it * 3.6 }
            "mph" -> speedMs?.let { it * 2.23694 }
            else -> speedMs  // "m/s"
        }

        val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val dateFormatted = dateFormat.format(Date(session.date))

        return ShareCardData(
            timeSeconds = run.timeSeconds,
            distance = run.distance,
            startType = run.startType,
            dateFormatted = dateFormatted,
            athleteName = run.athleteName,
            isPersonalBest = run.isPersonalBest,
            isSeasonBest = run.isSeasonBest,
            speedValue = speedValue,
            speedUnit = speedUnit
        )
    }
}
