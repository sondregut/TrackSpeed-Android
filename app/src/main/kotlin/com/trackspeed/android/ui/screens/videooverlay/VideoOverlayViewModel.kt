package com.trackspeed.android.ui.screens.videooverlay

import android.net.Uri
import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.R
import com.trackspeed.android.model.StartType
import com.trackspeed.android.ui.util.formatDistance
import com.trackspeed.android.ui.util.shortNameResource
import com.trackspeed.android.videooverlay.ImportedVideo
import com.trackspeed.android.videooverlay.VideoOverlayExportService
import com.trackspeed.android.videooverlay.VideoOverlaySnapshot
import com.trackspeed.android.videooverlay.toVideoOverlaySnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class VideoOverlayStep {
    IMPORT,
    MARK_START,
    PREVIEW
}

sealed interface VideoExportPhase {
    data object Idle : VideoExportPhase
    data class Exporting(val progress: Double) : VideoExportPhase
    data class Ready(val file: File) : VideoExportPhase
    data class Error(@StringRes val messageRes: Int) : VideoExportPhase
}

data class VideoOverlayUiState(
    val isLoading: Boolean = true,
    val run: RunEntity? = null,
    val speedUnit: String = "m/s",
    val step: VideoOverlayStep = VideoOverlayStep.IMPORT,
    val importedVideo: ImportedVideo? = null,
    val startMarkerTimeSeconds: Double = 0.0,
    val showSpeed: Boolean = true,
    val showRunType: Boolean = true,
    @StringRes val importErrorRes: Int? = null,
    val exportPhase: VideoExportPhase = VideoExportPhase.Idle,
    @StringRes val savedMessageRes: Int? = null
) {
    val snapshot: VideoOverlaySnapshot?
        get() {
            val run = run ?: return null
            val video = importedVideo ?: return null
            return run.toVideoOverlaySnapshot(
                sourceUri = video.uri,
                speedUnit = speedUnit,
                startMarkerTimeSeconds = startMarkerTimeSeconds,
                showSpeed = showSpeed,
                showRunType = showRunType
            )
        }
}

@HiltViewModel
@UnstableApi
class VideoOverlayViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val exportService: VideoOverlayExportService
) : ViewModel() {

    private val runId: String = checkNotNull(savedStateHandle["runId"])

    private val _uiState = MutableStateFlow(VideoOverlayUiState())
    val uiState: StateFlow<VideoOverlayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val run = sessionRepository.getRunById(runId)
            val speedUnit = settingsRepository.speedUnit.first()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    run = run,
                    speedUnit = speedUnit
                )
            }
        }
    }

    fun importVideo(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    importErrorRes = null,
                    savedMessageRes = null,
                    exportPhase = VideoExportPhase.Idle
                )
            }

            runCatching { exportService.importVideo(uri) }
                .onSuccess { imported ->
                    _uiState.update {
                        it.copy(
                            importedVideo = imported,
                            step = VideoOverlayStep.MARK_START,
                            startMarkerTimeSeconds = 0.0,
                            importErrorRes = null
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(importErrorRes = R.string.video_overlay_import_failed)
                    }
                }
        }
    }

    fun markStart(seconds: Double) {
        _uiState.update {
            it.copy(
                startMarkerTimeSeconds = seconds.coerceIn(0.0, it.importedVideo?.durationSeconds ?: seconds),
                step = VideoOverlayStep.PREVIEW,
                exportPhase = VideoExportPhase.Idle,
                savedMessageRes = null
            )
        }
    }

    fun setStep(step: VideoOverlayStep) {
        _uiState.update { it.copy(step = step) }
    }

    fun setShowSpeed(show: Boolean) {
        _uiState.update {
            it.copy(
                showSpeed = show,
                exportPhase = VideoExportPhase.Idle,
                savedMessageRes = null
            )
        }
    }

    fun setShowRunType(show: Boolean) {
        _uiState.update {
            it.copy(
                showRunType = show,
                exportPhase = VideoExportPhase.Idle,
                savedMessageRes = null
            )
        }
    }

    fun exportVideo() {
        val snapshot = _uiState.value.snapshot ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(exportPhase = VideoExportPhase.Exporting(0.0), savedMessageRes = null) }
            runCatching {
                exportService.export(localizeSnapshot(snapshot)) { progress ->
                    _uiState.update { current ->
                        if (current.exportPhase is VideoExportPhase.Exporting) {
                            current.copy(exportPhase = VideoExportPhase.Exporting(progress))
                        } else {
                            current
                        }
                    }
                }
            }.onSuccess { file ->
                _uiState.update { it.copy(exportPhase = VideoExportPhase.Ready(file)) }
            }.onFailure {
                _uiState.update {
                    it.copy(exportPhase = VideoExportPhase.Error(R.string.video_overlay_export_failed))
                }
            }
        }
    }

    fun retryExport() {
        exportVideo()
    }

    fun saveExportedVideo() {
        val file = (_uiState.value.exportPhase as? VideoExportPhase.Ready)?.file ?: return
        viewModelScope.launch {
            runCatching { exportService.saveToMediaStore(file) }
                .onSuccess {
                    _uiState.update { it.copy(savedMessageRes = R.string.video_overlay_saved) }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(exportPhase = VideoExportPhase.Error(R.string.video_overlay_save_failed))
                    }
                }
        }
    }

    fun shareUri(file: File): Uri = exportService.shareUri(file)

    fun clearSavedMessage() {
        _uiState.update { it.copy(savedMessageRes = null) }
    }

    fun localizeSnapshot(snapshot: VideoOverlaySnapshot): VideoOverlaySnapshot {
        val run = _uiState.value.run
        val localizedRunType = run?.let {
            val startType = context.getString(StartType.fromRawValue(it.startType).shortNameResource())
            if (it.distance > 0.0) {
                context.getString(R.string.video_overlay_run_type, startType, formatDistance(it.distance))
            } else {
                startType
            }
        }
        return snapshot.copy(
            splits = snapshot.splits.map { split ->
                if (split.label == "FINISH") {
                    split.copy(label = context.getString(R.string.video_overlay_finish))
                } else {
                    split
                }
            },
            runTypeLabel = localizedRunType,
            readyLabel = context.getString(R.string.video_overlay_ready)
        )
    }
}
