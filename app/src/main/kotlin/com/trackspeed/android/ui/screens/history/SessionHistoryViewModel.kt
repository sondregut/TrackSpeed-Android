package com.trackspeed.android.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.dao.SessionSummary
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import com.trackspeed.android.ui.util.formatDistance
import javax.inject.Inject

enum class SortOrder {
    NEWEST,
    OLDEST,
    FASTEST,
    SLOWEST
}

data class DistanceFilter(
    val label: String,
    val distance: Double?
)

data class SessionCardData(
    val session: TrainingSessionEntity,
    val runCount: Int,
    val bestTime: Double?
)

enum class DateGroupKey {
    TODAY, YESTERDAY, THIS_WEEK, EARLIER
}

data class DateGroup(
    val key: DateGroupKey,
    val sessions: List<SessionCardData>
)

data class HistoryStats(
    val totalSessions: Int = 0,
    val totalRuns: Int = 0,
    val bestTime: Double? = null,
    val weeklySessionCount: Int = 0
)

@HiltViewModel
class SessionHistoryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _filterDistance = MutableStateFlow<Double?>(null)
    val filterDistance: StateFlow<Double?> = _filterDistance.asStateFlow()

    private val _filterStartType = MutableStateFlow<String?>(null)
    val filterStartType: StateFlow<String?> = _filterStartType.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.NEWEST)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val allSessions = sessionRepository.getAllSessions()
    private val sessionSummaries = sessionRepository.getSessionSummaries()

    // Dynamic distance filters from actual data
    val distanceFilters: StateFlow<List<DistanceFilter>> =
        sessionRepository.getDistinctDistances()
            .map { distances ->
                val filters = mutableListOf(DistanceFilter("All", null))
                distances.forEach { d ->
                    val label = formatDistanceLabel(d)
                    filters.add(DistanceFilter(label, d))
                }
                filters
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = listOf(DistanceFilter("All", null))
            )

    // Dynamic start type filters
    val startTypeFilters: StateFlow<List<String>> =
        sessionRepository.getDistinctStartTypes()
            .map { types -> listOf("All") + types.map { it.replaceFirstChar { c -> c.uppercase() } } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = listOf("All")
            )

    // Header stats
    val historyStats: StateFlow<HistoryStats> = run {
        val weekStart = getWeekStartMillis()
        combine(
            sessionRepository.getTotalSessionCount(),
            sessionRepository.getTotalRunCount(),
            sessionRepository.getGlobalBestTime(),
            sessionRepository.getSessionCountSince(weekStart)
        ) { totalSessions, totalRuns, bestTime, weeklySessions ->
            HistoryStats(
                totalSessions = totalSessions,
                totalRuns = totalRuns,
                bestTime = bestTime,
                weeklySessionCount = weeklySessions
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryStats()
        )
    }

    // Grouped sessions with card data
    val dateGroups: StateFlow<List<DateGroup>> =
        combine(
            allSessions,
            sessionSummaries,
            _filterDistance,
            _filterStartType,
            combine(_sortOrder, _searchQuery) { sort, query -> sort to query }
        ) { sessions, summaries, distance, startType, sortAndQuery ->
            val (sort, query) = sortAndQuery

            val summaryMap = summaries.associateBy { it.sessionId }

            // Filter
            var filtered = sessions.toList()
            if (distance != null) {
                filtered = filtered.filter { it.distance == distance }
            }
            if (startType != null) {
                filtered = filtered.filter { it.startType.equals(startType, ignoreCase = true) }
            }
            if (query.isNotBlank()) {
                val q = query.lowercase()
                filtered = filtered.filter { session ->
                    (session.name?.lowercase()?.contains(q) == true) ||
                        session.distance.toInt().toString().contains(q) ||
                        session.startType.lowercase().contains(q) ||
                        (session.location?.lowercase()?.contains(q) == true) ||
                        (session.notes?.lowercase()?.contains(q) == true)
                }
            }

            // Sort
            val sorted = when (sort) {
                SortOrder.NEWEST -> filtered.sortedByDescending { it.date }
                SortOrder.OLDEST -> filtered.sortedBy { it.date }
                SortOrder.FASTEST -> filtered.sortedBy { summaryMap[it.id]?.bestTime ?: Double.MAX_VALUE }
                SortOrder.SLOWEST -> filtered.sortedByDescending { summaryMap[it.id]?.bestTime ?: 0.0 }
            }

            // Build card data
            val cardData = sorted.map { session ->
                val summary = summaryMap[session.id]
                SessionCardData(
                    session = session,
                    runCount = summary?.runCount ?: 0,
                    bestTime = summary?.bestTime
                )
            }

            // Group by date
            groupByDate(cardData)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // Flat session list (for checking if there are any sessions at all)
    val hasAnySessions: StateFlow<Boolean> =
        allSessions.map { it.isNotEmpty() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false
            )

    val hasActiveFilters: StateFlow<Boolean> =
        combine(_filterDistance, _filterStartType, _searchQuery) { d, s, q ->
            d != null || s != null || q.isNotBlank()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    fun setFilterDistance(distance: Double?) {
        _filterDistance.value = distance
    }

    fun setFilterStartType(startType: String?) {
        _filterStartType.value = startType
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearFilters() {
        _filterDistance.value = null
        _filterStartType.value = null
        _sortOrder.value = SortOrder.NEWEST
        _searchQuery.value = ""
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            sessionRepository.deleteSession(id)
        }
    }

    private fun groupByDate(sessions: List<SessionCardData>): List<DateGroup> {
        if (sessions.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val yesterdayStart = todayStart - 86_400_000L
        val weekStart = todayStart - 7 * 86_400_000L

        val today = mutableListOf<SessionCardData>()
        val yesterday = mutableListOf<SessionCardData>()
        val thisWeek = mutableListOf<SessionCardData>()
        val earlier = mutableListOf<SessionCardData>()

        for (card in sessions) {
            when {
                card.session.date >= todayStart -> today.add(card)
                card.session.date >= yesterdayStart -> yesterday.add(card)
                card.session.date >= weekStart -> thisWeek.add(card)
                else -> earlier.add(card)
            }
        }

        return buildList {
            if (today.isNotEmpty()) add(DateGroup(DateGroupKey.TODAY, today))
            if (yesterday.isNotEmpty()) add(DateGroup(DateGroupKey.YESTERDAY, yesterday))
            if (thisWeek.isNotEmpty()) add(DateGroup(DateGroupKey.THIS_WEEK, thisWeek))
            if (earlier.isNotEmpty()) add(DateGroup(DateGroupKey.EARLIER, earlier))
        }
    }

    private fun getWeekStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        fun formatDistanceLabel(distance: Double): String = formatDistance(distance)
    }
}
