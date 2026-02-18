package com.trackspeed.android.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortOrder(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    FASTEST("Fastest"),
    SLOWEST("Slowest")
}

data class DistanceFilter(
    val label: String,
    val distance: Double?
)

val DISTANCE_FILTERS = listOf(
    DistanceFilter("All", null),
    DistanceFilter("40yd", 36.576),
    DistanceFilter("60m", 60.0),
    DistanceFilter("100m", 100.0),
    DistanceFilter("200m", 200.0)
)

@HiltViewModel
class SessionHistoryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _filterDistance = MutableStateFlow<Double?>(null)
    val filterDistance: StateFlow<Double?> = _filterDistance.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.NEWEST)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val allSessions = sessionRepository.getAllSessions()
    private val bestTimes = sessionRepository.getBestTimePerSession()

    val sessions: StateFlow<List<TrainingSessionEntity>> =
        combine(allSessions, bestTimes, _filterDistance, _sortOrder) { sessions, bestTimes, distance, sort ->
            val bestTimeMap = bestTimes.associate { it.sessionId to it.timeSeconds }

            val filtered = if (distance != null) {
                sessions.filter { it.distance == distance }
            } else {
                sessions
            }

            when (sort) {
                SortOrder.NEWEST -> filtered.sortedByDescending { it.date }
                SortOrder.OLDEST -> filtered.sortedBy { it.date }
                SortOrder.FASTEST -> filtered.sortedBy { bestTimeMap[it.id] ?: Double.MAX_VALUE }
                SortOrder.SLOWEST -> filtered.sortedByDescending { bestTimeMap[it.id] ?: 0.0 }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * True when sessions exist in the database but the current distance filter
     * hides them all, meaning the empty list is caused by filtering rather than
     * a genuinely empty history.
     */
    val hasUnfilteredSessions: StateFlow<Boolean> =
        allSessions.combine(_filterDistance) { sessions, distance ->
            sessions.isNotEmpty() && distance != null && sessions.none { it.distance == distance }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    fun setFilterDistance(distance: Double?) {
        _filterDistance.value = distance
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun clearFilters() {
        _filterDistance.value = null
        _sortOrder.value = SortOrder.NEWEST
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            sessionRepository.deleteSession(id)
        }
    }
}
