package com.trackspeed.android.ui.screens.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.TrackSpeedDatabase
import com.trackspeed.android.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class DebugToolsUiState(
    val sessionCount: Int = 0,
    val runCount: Int = 0,
    val dbSizeFormatted: String = "---",
    val thumbnailSizeFormatted: String = "---",
    val clockSyncStatus: String = "Not connected",
    val clockSyncQuality: String = "N/A",
    val clockSyncOffset: String = "N/A",
    val detectionTestMode: Boolean = false
)

@HiltViewModel
class DebugToolsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val database: TrackSpeedDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DebugToolsUiState())
    val uiState: StateFlow<DebugToolsUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    private fun loadStats() {
        // Session count
        viewModelScope.launch {
            sessionRepository.getTotalSessionCount().collect { count ->
                _uiState.update { it.copy(sessionCount = count) }
            }
        }

        // Run count
        viewModelScope.launch {
            sessionRepository.getTotalRunCount().collect { count ->
                _uiState.update { it.copy(runCount = count) }
            }
        }

        // DB size
        viewModelScope.launch {
            val dbSize = withContext(Dispatchers.IO) {
                val dbFile = context.getDatabasePath("trackspeed_database")
                if (dbFile.exists()) formatFileSize(dbFile.length()) else "0 B"
            }
            _uiState.update { it.copy(dbSizeFormatted = dbSize) }
        }

        // Thumbnail size
        viewModelScope.launch {
            val thumbnailSize = withContext(Dispatchers.IO) {
                val thumbnailDir = File(context.filesDir, "thumbnails")
                if (thumbnailDir.exists()) {
                    val totalSize = calculateDirSize(thumbnailDir)
                    formatFileSize(totalSize)
                } else {
                    "0 B"
                }
            }
            _uiState.update { it.copy(thumbnailSizeFormatted = thumbnailSize) }
        }
    }

    fun resetDatabase() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                database.clearAllTables()
            }
            loadStats()
        }
    }

    fun clearThumbnails() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val thumbnailDir = File(context.filesDir, "thumbnails")
                if (thumbnailDir.exists()) {
                    thumbnailDir.deleteRecursively()
                }
            }
            loadStats()
        }
    }

    fun toggleDetectionTestMode() {
        _uiState.update { it.copy(detectionTestMode = !it.detectionTestMode) }
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
