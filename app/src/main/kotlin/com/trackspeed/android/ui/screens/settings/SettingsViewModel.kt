package com.trackspeed.android.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.audio.ElevenLabsService
import com.trackspeed.android.audio.ElevenLabsVoiceId
import com.trackspeed.android.audio.VoiceProvider
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val defaultDistance: Double = SettingsRepository.Defaults.DISTANCE,
    val startType: String = SettingsRepository.Defaults.START_TYPE,
    val detectionSensitivity: Float = SettingsRepository.Defaults.SENSITIVITY,
    val speedUnit: String = SettingsRepository.Defaults.SPEED_UNIT,
    val darkMode: Boolean = SettingsRepository.Defaults.DARK_MODE,
    val onboardingCompleted: Boolean = SettingsRepository.Defaults.ONBOARDING_COMPLETED,
    val preferredFps: Int = SettingsRepository.Defaults.PREFERRED_FPS,
    val isProUser: Boolean = false,
    val voiceProvider: String = SettingsRepository.Defaults.VOICE_PROVIDER,
    val elevenLabsVoice: String = SettingsRepository.Defaults.ELEVEN_LABS_VOICE,
    val announceTimesEnabled: Boolean = SettingsRepository.Defaults.ANNOUNCE_TIMES_ENABLED,
    val startMode: String = SettingsRepository.Defaults.START_MODE
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
    private val subscriptionManager: SubscriptionManager,
    private val elevenLabsService: ElevenLabsService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _thumbnailStorageSize = MutableStateFlow("0 KB")
    val thumbnailStorageSize: StateFlow<String> = _thumbnailStorageSize.asStateFlow()

    init {
        viewModelScope.launch {
            _thumbnailStorageSize.value = withContext(Dispatchers.IO) {
                calculateThumbnailStorageSize()
            }
        }
    }

    private fun calculateThumbnailStorageSize(): String {
        val thumbnailDir = File(context.filesDir, "thumbnails")
        if (!thumbnailDir.exists()) return "0 KB"

        val totalBytes = thumbnailDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

        return when {
            totalBytes < 1024 -> "$totalBytes B"
            totalBytes < 1024 * 1024 -> "${totalBytes / 1024} KB"
            else -> String.format("%.1f MB", totalBytes / (1024.0 * 1024.0))
        }
    }

    private val coreState = combine(
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
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        coreState,
        settingsRepository.voiceProvider,
        settingsRepository.elevenLabsVoice,
        settingsRepository.announceTimesEnabled,
        settingsRepository.startMode
    ) { core, voiceProvider, elevenLabsVoice, announceTimesEnabled, startMode ->
        core.copy(
            voiceProvider = voiceProvider,
            elevenLabsVoice = elevenLabsVoice,
            announceTimesEnabled = announceTimesEnabled,
            startMode = startMode
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

    fun setVoiceProvider(provider: String) {
        viewModelScope.launch {
            settingsRepository.setVoiceProvider(provider)
        }
    }

    fun setElevenLabsVoice(voice: String) {
        viewModelScope.launch {
            settingsRepository.setElevenLabsVoice(voice)
        }
    }

    fun setAnnounceTimesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAnnounceTimesEnabled(enabled)
        }
    }

    fun setStartMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.setStartMode(mode)
        }
    }

    fun previewVoice() {
        viewModelScope.launch {
            val state = uiState.value
            val voiceProvider = VoiceProvider.fromString(state.voiceProvider)
            if (voiceProvider == VoiceProvider.ELEVEN_LABS) {
                val voiceId = ElevenLabsVoiceId.fromString(state.elevenLabsVoice)
                val audioData = elevenLabsService.generateSpeech(
                    text = "On your marks. Set. Go!",
                    voiceId = voiceId
                )
                if (audioData != null) {
                    elevenLabsService.playAudio(audioData)
                }
            }
        }
    }

    fun resetOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(false)
        }
    }

    /**
     * Clear all local data: delete cached thumbnails directory.
     * Note: Room database clearing would require the database instance;
     * for now this clears the thumbnail cache.
     */
    fun clearAllData() {
        viewModelScope.launch {
            // Clear thumbnail cache
            val thumbnailDir = File(context.filesDir, "thumbnails")
            if (thumbnailDir.exists()) {
                thumbnailDir.deleteRecursively()
            }
        }
    }
}
