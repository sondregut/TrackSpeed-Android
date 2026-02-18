package com.trackspeed.android.ui.screens.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.entities.AthleteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class SetupStep(val index: Int) {
    ATHLETES(0),
    DISTANCE(1),
    START_TYPE(2);

    fun next(): SetupStep? = entries.getOrNull(index + 1)
    fun previous(): SetupStep? = entries.getOrNull(index - 1)
}

data class PresetDistance(
    val label: String,
    val meters: Double
)

val PRESET_DISTANCES = listOf(
    PresetDistance("10m", 10.0),
    PresetDistance("20m", 20.0),
    PresetDistance("30m", 30.0),
    PresetDistance("40yd", 36.576),
    PresetDistance("60m", 60.0),
    PresetDistance("100m", 100.0),
    PresetDistance("200m", 200.0)
)

data class SessionSetupUiState(
    val currentStep: SetupStep = SetupStep.ATHLETES,
    val athletes: List<AthleteEntity> = emptyList(),
    val selectedAthleteIds: Set<String> = emptySet(),
    val selectedDistance: Double = 60.0,
    val customDistanceText: String = "",
    val selectedStartType: String = "standing",
    val isReady: Boolean = false
)

@HiltViewModel
class SessionSetupViewModel @Inject constructor(
    private val athleteDao: AthleteDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialDistance = savedStateHandle.get<Float>("distance")
        ?.toDouble()?.takeIf { it > 0 }
    private val initialStartType = savedStateHandle.get<String>("startType")?.ifBlank { null }

    private val _currentStep = MutableStateFlow(SetupStep.ATHLETES)
    private val _selectedAthleteIds = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedDistance = MutableStateFlow(initialDistance ?: 60.0)
    private val _customDistanceText = MutableStateFlow("")
    private val _selectedStartType = MutableStateFlow(initialStartType ?: "standing")

    val uiState: StateFlow<SessionSetupUiState> = combine(
        _currentStep,
        athleteDao.getAllAthletes(),
        _selectedAthleteIds,
        _selectedDistance,
        combine(_customDistanceText, _selectedStartType) { custom, start -> Pair(custom, start) }
    ) { step, athletes, selectedIds, distance, (customText, startType) ->
        SessionSetupUiState(
            currentStep = step,
            athletes = athletes,
            selectedAthleteIds = selectedIds,
            selectedDistance = distance,
            customDistanceText = customText,
            selectedStartType = startType,
            isReady = distance > 0
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SessionSetupUiState(
            selectedDistance = initialDistance ?: 60.0,
            selectedStartType = initialStartType ?: "standing"
        )
    )

    val currentStep: StateFlow<SetupStep> = _currentStep.asStateFlow()

    fun goToNextStep() {
        _currentStep.value.next()?.let { _currentStep.value = it }
    }

    fun goToPreviousStep() {
        _currentStep.value.previous()?.let { _currentStep.value = it }
    }

    fun toggleAthlete(athleteId: String) {
        val current = _selectedAthleteIds.value
        _selectedAthleteIds.value = if (athleteId in current) {
            current - athleteId
        } else {
            current + athleteId
        }
    }

    fun selectDistance(meters: Double) {
        _selectedDistance.value = meters
        _customDistanceText.value = ""
    }

    fun setCustomDistance(text: String) {
        _customDistanceText.value = text
        text.toDoubleOrNull()?.let { value ->
            if (value > 0) _selectedDistance.value = value
        }
    }

    fun selectStartType(startType: String) {
        _selectedStartType.value = startType
    }
}
