package com.trackspeed.android.ui.screens.athletes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.cloud.CloudSyncService
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.entities.AthleteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

data class AthleteFormUiState(
    val name: String = "",
    val nickname: String = "",
    val selectedColor: String = "blue",
    val photoPath: String? = null,
    val birthdateText: String = "",
    val gender: String? = null,
    val personalBests: Map<String, Double> = emptyMap(),
    val isEditMode: Boolean = false,
    val isLoaded: Boolean = false,
    val hasBirthdateError: Boolean = false
)

@HiltViewModel
class AthleteFormViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val athleteDao: AthleteDao,
    private val cloudSyncService: CloudSyncService
) : ViewModel() {

    private val athleteId: String? = savedStateHandle.get<String>("athleteId")?.ifBlank { null }
    private val draftAthleteId: String = athleteId ?: UUID.randomUUID().toString()
    private var existingAthlete: AthleteEntity? = null

    private val _uiState = MutableStateFlow(AthleteFormUiState())
    val uiState: StateFlow<AthleteFormUiState> = _uiState.asStateFlow()

    init {
        if (athleteId != null) {
            loadAthlete(athleteId)
        } else {
            _uiState.value = AthleteFormUiState(isLoaded = true)
        }
    }

    private fun loadAthlete(id: String) {
        viewModelScope.launch {
            val athlete = athleteDao.getAthleteById(id)
            if (athlete != null) {
                existingAthlete = athlete
                _uiState.value = AthleteFormUiState(
                    name = athlete.name,
                    nickname = athlete.nickname ?: "",
                    selectedColor = athlete.color,
                    photoPath = athlete.photoPath,
                    birthdateText = formatBirthdate(athlete.birthdate),
                    gender = athlete.gender,
                    personalBests = athlete.personalBests(),
                    isEditMode = true,
                    isLoaded = true
                )
            } else {
                _uiState.value = AthleteFormUiState(isLoaded = true)
            }
        }
    }

    fun onNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(name = name, hasBirthdateError = false)
    }

    fun onNicknameChanged(nickname: String) {
        _uiState.value = _uiState.value.copy(nickname = nickname)
    }

    fun onColorSelected(color: String) {
        _uiState.value = _uiState.value.copy(selectedColor = color)
    }

    fun onBirthdateChanged(birthdate: String) {
        _uiState.value = _uiState.value.copy(birthdateText = birthdate, hasBirthdateError = false)
    }

    fun onGenderSelected(gender: String?) {
        _uiState.value = _uiState.value.copy(gender = gender)
    }

    fun onPhotoSelected(uri: Uri) {
        viewModelScope.launch {
            val savedPath = saveAthletePhoto(uri)
            if (savedPath != null) {
                _uiState.value = _uiState.value.copy(photoPath = savedPath)
            }
        }
    }

    fun removePhoto() {
        val currentPath = _uiState.value.photoPath
        _uiState.value = _uiState.value.copy(photoPath = null)
        viewModelScope.launch {
            deleteLocalPhoto(currentPath)
        }
    }

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.name.isBlank()) return
        val birthdate = parseBirthdate(state.birthdateText)
        if (state.birthdateText.isNotBlank() && birthdate == null) {
            _uiState.value = state.copy(hasBirthdateError = true)
            return
        }

        viewModelScope.launch {
            val existing = existingAthlete
            val now = System.currentTimeMillis()
            val athlete = if (existing != null) {
                if (existing.photoPath != state.photoPath) deleteLocalPhoto(existing.photoPath)
                existing.copy(
                    name = state.name.trim(),
                    nickname = state.nickname.trim().ifBlank { null },
                    color = state.selectedColor,
                    photoPath = state.photoPath,
                    birthdate = birthdate,
                    gender = state.gender,
                    updatedAt = now
                )
            } else {
                AthleteEntity(
                    id = draftAthleteId,
                    name = state.name.trim(),
                    nickname = state.nickname.trim().ifBlank { null },
                    color = state.selectedColor,
                    photoPath = state.photoPath,
                    birthdate = birthdate,
                    gender = state.gender,
                    createdAt = now,
                    updatedAt = now
                )
            }

            if (existing != null) {
                athleteDao.update(athlete)
            } else {
                athleteDao.insert(athlete)
            }
            try { cloudSyncService.syncAthlete(athlete) } catch (_: Exception) { }
            onComplete()
        }
    }

    fun delete(onComplete: () -> Unit) {
        val existing = existingAthlete ?: return
        viewModelScope.launch {
            athleteDao.delete(existing)
            deleteLocalPhoto(existing.photoPath)
            try { cloudSyncService.deleteAthlete(existing.id) } catch (_: Exception) { }
            onComplete()
        }
    }

    private suspend fun saveAthletePhoto(uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val original = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (original == null) return@withContext null

                val resized = resizeBitmap(original, 400)
                val dir = File(context.filesDir, "athletes")
                dir.mkdirs()
                val file = File(dir, "$draftAthleteId.jpg")
                FileOutputStream(file).use { out ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }

                if (resized !== original) resized.recycle()
                original.recycle()

                file.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun deleteLocalPhoto(path: String?) {
        path ?: return
        if (path.startsWith("http://") || path.startsWith("https://")) return
        withContext(Dispatchers.IO) {
            try { File(path).delete() } catch (_: Exception) { }
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap

        val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun parseBirthdate(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching {
            LocalDate.parse(value.trim())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private fun formatBirthdate(epochMillis: Long?): String {
        epochMillis ?: return ""
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }
}
