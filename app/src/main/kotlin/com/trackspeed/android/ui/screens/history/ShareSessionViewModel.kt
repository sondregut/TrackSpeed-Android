package com.trackspeed.android.ui.screens.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.ui.components.ShareCardTheme
import com.trackspeed.android.ui.components.ShareSessionCardData
import com.trackspeed.android.ui.components.ShareSessionRunData
import com.trackspeed.android.ui.util.parseSegmentSplits
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class ShareSessionUiState(
    val cardData: ShareSessionCardData? = null,
    val selectedTheme: ShareCardTheme = ShareCardTheme.MIDNIGHT,
    val isLoading: Boolean = true,
    val isSavedToGallery: Boolean = false
)

@HiltViewModel
class ShareSessionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    private val _uiState = MutableStateFlow(ShareSessionUiState())
    val uiState: StateFlow<ShareSessionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = sessionRepository.getSession(sessionId)
            val runs = sessionRepository.getRunsForSession(sessionId).first().sortedBy { it.runNumber }

            if (session != null) {
                val cardData = buildCardData(session, runs)
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

    private suspend fun buildCardData(
        session: TrainingSessionEntity,
        runs: List<RunEntity>
    ): ShareSessionCardData {
        val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val dateFormatted = dateFormat.format(Date(session.date))
        val displayDistance = when {
            session.distance > 0.0 -> session.distance
            else -> runs.firstOrNull { it.distance > 0.0 }?.distance ?: 0.0
        }
        val athleteName = runs.firstOrNull { !it.athleteName.isNullOrBlank() }?.athleteName

        val shareRuns = withContext(Dispatchers.IO) {
            runs.mapIndexed { index, run ->
                ShareSessionRunData(
                    runNumber = run.runNumber,
                    timeSeconds = run.timeSeconds,
                    thumbnail = if (index < MAX_THUMBNAILS_TO_DECODE) {
                        decodeThumbnail(run.thumbnailPath)
                    } else {
                        null
                    },
                    segments = parseSegmentSplits(run.splitsJson)
                )
            }
        }

        return ShareSessionCardData(
            distance = displayDistance,
            dateFormatted = dateFormatted,
            athleteName = athleteName,
            runs = shareRuns
        )
    }

    private fun decodeThumbnail(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val file = File(path)
            if (file.exists()) BitmapFactory.decodeFile(path) else null
        }.getOrNull()
    }

    private companion object {
        const val MAX_THUMBNAILS_TO_DECODE = 8
    }
}
