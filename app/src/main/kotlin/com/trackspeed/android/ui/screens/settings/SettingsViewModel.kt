package com.trackspeed.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val defaultDistance: Double = SettingsRepository.Defaults.DISTANCE,
    val startType: String = SettingsRepository.Defaults.START_TYPE,
    val detectionSensitivity: Float = SettingsRepository.Defaults.SENSITIVITY,
    val speedUnit: String = SettingsRepository.Defaults.SPEED_UNIT,
    val darkMode: Boolean = SettingsRepository.Defaults.DARK_MODE,
    val onboardingCompleted: Boolean = SettingsRepository.Defaults.ONBOARDING_COMPLETED,
    val preferredFps: Int = SettingsRepository.Defaults.PREFERRED_FPS,
    val isProUser: Boolean = false
) {
    /**
     * Returns a display-friendly distance label (e.g. "60m", "100m", "40yd").
     */
    val distanceLabel: String
        get() = when (defaultDistance) {
            36.576 -> "40yd"
            60.0 -> "60m"
            100.0 -> "100m"
            200.0 -> "200m"
            else -> "${defaultDistance.toInt()}m"
        }

    /**
     * Returns a display-friendly start type label (e.g. "Standing", "Flying").
     */
    val startTypeLabel: String
        get() = startType.replaceFirstChar { it.uppercase() }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.defaultDistance,
        settingsRepository.startType,
        settingsRepository.detectionSensitivity,
        settingsRepository.speedUnit,
        settingsRepository.darkMode,
        settingsRepository.onboardingCompleted,
        settingsRepository.preferredFps,
        subscriptionManager.isProUser
    ) { values ->
        SettingsUiState(
            defaultDistance = values[0] as Double,
            startType = values[1] as String,
            detectionSensitivity = values[2] as Float,
            speedUnit = values[3] as String,
            darkMode = values[4] as Boolean,
            onboardingCompleted = values[5] as Boolean,
            preferredFps = values[6] as Int,
            isProUser = values[7] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    fun setDefaultDistance(distance: Double) {
        viewModelScope.launch {
            settingsRepository.setDefaultDistance(distance)
        }
    }

    fun setStartType(startType: String) {
        viewModelScope.launch {
            settingsRepository.setStartType(startType)
        }
    }

    fun setDetectionSensitivity(sensitivity: Float) {
        viewModelScope.launch {
            settingsRepository.setDetectionSensitivity(sensitivity)
        }
    }

    fun setSpeedUnit(unit: String) {
        viewModelScope.launch {
            settingsRepository.setSpeedUnit(unit)
        }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDarkMode(enabled)
        }
    }

    fun setPreferredFps(fps: Int) {
        viewModelScope.launch {
            settingsRepository.setPreferredFps(fps)
        }
    }
}
