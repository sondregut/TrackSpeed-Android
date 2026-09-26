package com.trackspeed.android.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.R
import com.trackspeed.android.audio.ElevenLabsService
import com.trackspeed.android.audio.ElevenLabsVoiceId
import com.trackspeed.android.audio.VoiceProvider
import com.trackspeed.android.audio.VoiceStartService
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.diagnostics.DetectionReviewLogStore
import com.trackspeed.android.diagnostics.LogExporter
import com.trackspeed.android.ui.theme.AppTheme
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
    val appTheme: AppTheme = AppTheme.MIDNIGHT,
    val onboardingCompleted: Boolean = SettingsRepository.Defaults.ONBOARDING_COMPLETED,
    val preferredFps: Int = SettingsRepository.Defaults.PREFERRED_FPS,
    val isProUser: Boolean = false,
    val voiceProvider: String = SettingsRepository.Defaults.VOICE_PROVIDER,
    val elevenLabsVoice: String = SettingsRepository.Defaults.ELEVEN_LABS_VOICE,
    val appLanguage: String = SettingsRepository.Defaults.APP_LANGUAGE,
    val announceTimesEnabled: Boolean = SettingsRepository.Defaults.ANNOUNCE_TIMES_ENABLED,
    val preStartDelayMin: Float = SettingsRepository.Defaults.PRE_START_DELAY_MIN,
    val marksSetDelayMin: Float = SettingsRepository.Defaults.MARKS_SET_DELAY_MIN,
    val setGoHoldMin: Float = SettingsRepository.Defaults.SET_GO_HOLD_MIN,
    val includeReadyCommand: Boolean = SettingsRepository.Defaults.INCLUDE_READY_COMMAND,
    val saveCrossingFrames: Boolean = SettingsRepository.Defaults.SAVE_CROSSING_FRAMES,
    val enableFrameScrubbing: Boolean = SettingsRepository.Defaults.ENABLE_FRAME_SCRUBBING,
    val detectionDiagnosticsEnabled: Boolean = SettingsRepository.Defaults.DETECTION_DIAGNOSTICS_ENABLED,
    val detectionReviewAutoUploadEnabled: Boolean = SettingsRepository.Defaults.DETECTION_REVIEW_AUTO_UPLOAD_ENABLED,
    val cameraPerformanceDiagnosticsEnabled: Boolean = SettingsRepository.Defaults.CAMERA_PERFORMANCE_DIAGNOSTICS_ENABLED
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

}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val subscriptionManager: SubscriptionManager,
    private val elevenLabsService: ElevenLabsService,
    private val voiceStartService: VoiceStartService,
    private val detectionReviewLogStore: DetectionReviewLogStore,
    private val logExporter: LogExporter,
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
            else -> String.format(java.util.Locale.getDefault(), "%.1f MB", totalBytes / (1024.0 * 1024.0))
        }
    }

    private val coreState = combine(
        settingsRepository.defaultDistance,
        settingsRepository.startType,
        settingsRepository.detectionSensitivity,
        settingsRepository.speedUnit,
        settingsRepository.appearanceMode,
        settingsRepository.onboardingCompleted,
        settingsRepository.preferredFps,
        settingsRepository.detectionDiagnosticsEnabled,
        settingsRepository.cameraPerformanceDiagnosticsEnabled,
        settingsRepository.detectionReviewAutoUploadEnabled,
        subscriptionManager.isProUser,
        settingsRepository.preStartDelayMin,
        settingsRepository.marksSetDelayMin,
        settingsRepository.setGoHoldMin,
        settingsRepository.includeReadyCommand,
        settingsRepository.saveCrossingFrames,
        settingsRepository.enableFrameScrubbing
    ) { values ->
        SettingsUiState(
            defaultDistance = values[0] as Double,
            startType = values[1] as String,
            detectionSensitivity = values[2] as Float,
            speedUnit = values[3] as String,
            appTheme = appearanceModeToTheme(values[4] as String),
            onboardingCompleted = values[5] as Boolean,
            preferredFps = values[6] as Int,
            detectionDiagnosticsEnabled = values[7] as Boolean,
            cameraPerformanceDiagnosticsEnabled = values[8] as Boolean,
            detectionReviewAutoUploadEnabled = values[9] as Boolean,
            isProUser = values[10] as Boolean,
            preStartDelayMin = values[11] as Float,
            marksSetDelayMin = values[12] as Float,
            setGoHoldMin = values[13] as Float,
            includeReadyCommand = values[14] as Boolean,
            saveCrossingFrames = values[15] as Boolean,
            enableFrameScrubbing = values[16] as Boolean
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        coreState,
        settingsRepository.voiceProvider,
        settingsRepository.elevenLabsVoice,
        settingsRepository.announceTimesEnabled,
        settingsRepository.appLanguage
    ) { core, voiceProvider, elevenLabsVoice, announceTimesEnabled, appLanguage ->
        core.copy(
            voiceProvider = voiceProvider,
            elevenLabsVoice = elevenLabsVoice,
            appLanguage = appLanguage,
            announceTimesEnabled = announceTimesEnabled
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

    fun setAppTheme(theme: AppTheme) {
        viewModelScope.launch {
            settingsRepository.setAppearanceMode(theme.key)
        }
    }

    fun setPreferredFps(fps: Int) {
        viewModelScope.launch {
            settingsRepository.setPreferredFps(SettingsRepository.Defaults.PREFERRED_FPS)
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

    fun setAppLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.setAppLanguage(language)
        }
    }

    fun setAnnounceTimesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAnnounceTimesEnabled(enabled)
        }
    }

    fun setPreStartDelayMin(value: Float) {
        viewModelScope.launch {
            settingsRepository.setPreStartDelayMin(value)
            settingsRepository.setPreStartDelayMax(value + 2f)
        }
    }

    fun setMarksSetDelayMin(value: Float) {
        viewModelScope.launch {
            settingsRepository.setMarksSetDelayMin(value)
            settingsRepository.setMarksSetDelayMax(value + 4f)
        }
    }

    fun setSetGoHoldMin(value: Float) {
        viewModelScope.launch {
            settingsRepository.setSetGoHoldMin(value)
            settingsRepository.setSetGoHoldMax(value + 0.8f)
        }
    }

    fun setIncludeReadyCommand(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setIncludeReadyCommand(enabled)
        }
    }

    fun setSaveCrossingFrames(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSaveCrossingFrames(enabled)
        }
    }

    fun setEnableFrameScrubbing(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setEnableFrameScrubbing(enabled)
        }
    }

    fun setDetectionDiagnosticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDetectionDiagnosticsEnabled(enabled)
        }
    }

    fun setDetectionReviewAutoUploadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDetectionReviewAutoUploadEnabled(enabled)
        }
    }

    fun setCameraPerformanceDiagnosticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCameraPerformanceDiagnosticsEnabled(enabled)
        }
    }

    suspend fun exportDetectionReviewLog(): Uri {
        return detectionReviewLogStore.exportCurrentLog()
    }

    suspend fun uploadDetectionReviewLog(): String {
        return detectionReviewLogStore.uploadCurrentLog()
    }

    suspend fun clearDetectionReviewLogs() {
        detectionReviewLogStore.clear()
    }

    suspend fun exportRecentLogs(window: LogExporter.TimeWindow): String {
        return logExporter.exportRecent(window)
    }

    suspend fun replayFirstSessionTutorial() {
        settingsRepository.setForceShowFirstSessionTutorial(true)
        settingsRepository.setHasDismissedFirstSessionTutorial(false)
    }

    fun previewVoice() {
        viewModelScope.launch {
            val state = uiState.value
            val voiceProvider = VoiceProvider.fromString(state.voiceProvider)
            if (voiceProvider == VoiceProvider.ELEVEN_LABS) {
                val voiceId = ElevenLabsVoiceId.fromString(state.elevenLabsVoice)
                val audioData = elevenLabsService.generateSpeech(
                    text = context.getString(R.string.voice_preview_script),
                    voiceId = voiceId
                )
                if (audioData != null) {
                    elevenLabsService.playAudio(audioData)
                }
            } else {
                voiceStartService.previewVoice()
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
     */
    fun clearAllData() {
        viewModelScope.launch {
            val thumbnailDir = File(context.filesDir, "thumbnails")
            if (thumbnailDir.exists()) {
                thumbnailDir.deleteRecursively()
            }
        }
    }

    companion object {
        fun appearanceModeToTheme(mode: String): AppTheme = when (mode) {
            "midnight", "dark" -> AppTheme.MIDNIGHT
            "light" -> AppTheme.LIGHT
            "gold" -> AppTheme.DARKGOLD
            else -> AppTheme.MIDNIGHT
        }
    }
}
