package com.trackspeed.android.ui.screens.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.ui.share.ImageShareController
import com.trackspeed.android.ui.share.ImageShareSavedState
import com.trackspeed.android.ui.share.RunImageOptions
import com.trackspeed.android.ui.share.runImageSpeed
import com.trackspeed.android.videooverlay.OverlaySpeedUnit
import com.trackspeed.android.onephone.SingleCameraSprintPersistence
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.ui.components.ShareCardData
import com.trackspeed.android.ui.components.ShareCardTheme
import com.trackspeed.android.ui.util.parseSegmentSplits
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
    val imageOptions: RunImageOptions = RunImageOptions(),
    val isLoading: Boolean = true
)

@HiltViewModel
class ShareResultViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val runId: String = checkNotNull(savedStateHandle["runId"])
    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    private val retainedOptions = ImageShareSavedState(savedStateHandle)
    private val _uiState = MutableStateFlow(ShareResultUiState(
        selectedTheme = retainedOptions.theme(), imageOptions = retainedOptions.runOptions()))
    val uiState: StateFlow<ShareResultUiState> = _uiState.asStateFlow()
    internal val imageShare = ImageShareController(viewModelScope)

    init {
        viewModelScope.launch {
            val run = sessionRepository.getRunById(runId)
            val session = sessionRepository.getSession(sessionId)
            val speedUnit = savedStateHandle.get<String>("imageShareSpeedUnit") ?: settingsRepository.speedUnit.first()

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

    fun selectImageSpeedUnit(unit: OverlaySpeedUnit) {
        val card = _uiState.value.cardData ?: return
        imageShare.cancel()
        savedStateHandle["imageShareSpeedUnit"] = unit.label
        _uiState.update { it.copy(cardData = card.copy(
            speedValue = runImageSpeed(card.distance, card.timeSeconds, unit), speedUnit = unit.label)) }
    }

    fun selectImageOptions(options: RunImageOptions) {
        imageShare.cancel()
        retainedOptions.save(options)
        _uiState.update { it.copy(imageOptions = options) }
    }

    fun selectTheme(theme: ShareCardTheme) {
        imageShare.cancel()
        retainedOptions.save(theme)
        _uiState.update { it.copy(selectedTheme = theme) }
    }

    private fun decodeSharePhoto(path: String?): Bitmap? {
        if (path.isNullOrBlank() || !File(path).isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) { null }
    }

    private suspend fun buildCardData(
        run: RunEntity,
        session: TrainingSessionEntity,
        speedUnit: String
    ): ShareCardData {
        val unit = OverlaySpeedUnit.fromRaw(speedUnit)
        val speedValue = runImageSpeed(run.distance, run.timeSeconds, unit)

        val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val dateFormatted = dateFormat.format(Date(session.date))

        val photos = withContext(Dispatchers.IO) { decodeSharePhoto(run.startImagePath) to decodeSharePhoto(run.finishImagePath) }
        return ShareCardData(
            timeSeconds = run.timeSeconds,
            timeDigits = if (run.cloudSyncPolicy == SingleCameraSprintPersistence.CLOUD_SYNC_POLICY) 2 else 3,
            distance = run.distance,
            startType = run.startType,
            dateFormatted = dateFormatted,
            athleteName = run.athleteName,
            isPersonalBest = run.isPersonalBest,
            isSeasonBest = run.isSeasonBest,
            speedValue = speedValue,
            speedUnit = unit.label,
            segments = parseSegmentSplits(run.splitsJson),
            startPhoto = photos.first,
            finishPhoto = photos.second,
            startGatePosition = run.startGatePosition,
            finishGatePosition = run.finishGatePosition ?: run.gatePosition
        )
    }
}
