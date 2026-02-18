package com.trackspeed.android.ui.screens.athletes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.entities.AthleteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AthleteFormUiState(
    val name: String = "",
    val nickname: String = "",
    val selectedColor: String = "blue",
    val isEditMode: Boolean = false,
    val isLoaded: Boolean = false
)

@HiltViewModel
class AthleteFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val athleteDao: AthleteDao
) : ViewModel() {

    private val athleteId: String? = savedStateHandle.get<String>("athleteId")?.ifBlank { null }
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
                    isEditMode = true,
                    isLoaded = true
                )
            } else {
                _uiState.value = AthleteFormUiState(isLoaded = true)
            }
        }
    }

    fun onNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun onNicknameChanged(nickname: String) {
        _uiState.value = _uiState.value.copy(nickname = nickname)
    }

    fun onColorSelected(color: String) {
        _uiState.value = _uiState.value.copy(selectedColor = color)
    }

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.name.isBlank()) return

        viewModelScope.launch {
            val existing = existingAthlete
            if (existing != null) {
                athleteDao.update(
                    existing.copy(
                        name = state.name.trim(),
                        nickname = state.nickname.trim().ifBlank { null },
                        color = state.selectedColor
                    )
                )
            } else {
                athleteDao.insert(
                    AthleteEntity(
                        name = state.name.trim(),
                        nickname = state.nickname.trim().ifBlank { null },
                        color = state.selectedColor
                    )
                )
            }
            onComplete()
        }
    }

    fun delete(onComplete: () -> Unit) {
        val existing = existingAthlete ?: return
        viewModelScope.launch {
            athleteDao.delete(existing)
            onComplete()
        }
    }
}
