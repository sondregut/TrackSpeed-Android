package com.trackspeed.android.ui.screens.athletes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.entities.AthleteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AthleteListUiState(
    val athletes: List<AthleteEntity> = emptyList(),
    val searchQuery: String = ""
)

@HiltViewModel
class AthleteListViewModel @Inject constructor(
    private val athleteDao: AthleteDao
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    val uiState: StateFlow<AthleteListUiState> = combine(
        athleteDao.getAllAthletes(),
        searchQuery
    ) { athletes, query ->
        val filtered = if (query.isBlank()) {
            athletes
        } else {
            athletes.filter { athlete ->
                athlete.name.contains(query, ignoreCase = true) ||
                    (athlete.nickname?.contains(query, ignoreCase = true) == true)
            }
        }
        AthleteListUiState(athletes = filtered, searchQuery = query)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AthleteListUiState()
    )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun deleteAthlete(athlete: AthleteEntity) {
        viewModelScope.launch {
            athleteDao.delete(athlete)
        }
    }
}
