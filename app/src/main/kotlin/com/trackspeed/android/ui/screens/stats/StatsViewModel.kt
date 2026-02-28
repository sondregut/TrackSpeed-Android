package com.trackspeed.android.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class TestType(
    val distance: Double,
    val startType: String
) {
    val label: String
        get() {
            val distLabel = when (distance) {
                36.576 -> "40yd"
                else -> "${distance.toInt()}m"
            }
            val startLabel = startType.replaceFirstChar { it.uppercase() }
            return "$distLabel $startLabel"
        }
}

data class ProgressPoint(
    val sessionIndex: Int,
    val dateMillis: Long,
    val bestTime: Double
)

data class StatsUiState(
    val testTypes: List<TestType> = emptyList(),
    val selectedTestType: TestType? = null,
    val bestTime: Double? = null,
    val averageTime: Double? = null,
    val totalRuns: Int = 0,
    val totalSessions: Int = 0,
    val progressPoints: List<ProgressPoint> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val selectedTestTypeFlow = MutableStateFlow<TestType?>(null)

    val uiState: StateFlow<StatsUiState> = combine(
        sessionRepository.getAllRunsSortedByTime(),
        sessionRepository.getAllSessions(),
        sessionRepository.getBestTimePerSession(),
        selectedTestTypeFlow
    ) { allRuns, allSessions, _, selectedType ->

        // Build distinct test types from runs
        val testTypes = allRuns
            .map { TestType(it.distance, it.startType) }
            .distinct()
            .sortedWith(compareBy({ it.distance }, { it.startType }))

        // Auto-select first type if nothing selected or selection is invalid
        val effectiveSelected = if (selectedType != null && selectedType in testTypes) {
            selectedType
        } else {
            testTypes.firstOrNull()
        }

        if (effectiveSelected == null) {
            return@combine StatsUiState(
                testTypes = emptyList(),
                selectedTestType = null,
                isLoading = false
            )
        }

        // Filter runs for the selected test type
        val filteredRuns = allRuns.filter {
            it.distance == effectiveSelected.distance && it.startType == effectiveSelected.startType
        }

        // Compute summary stats
        val bestTime = filteredRuns.minOfOrNull { it.timeSeconds }
        val averageTime = if (filteredRuns.isNotEmpty()) {
            filteredRuns.sumOf { it.timeSeconds } / filteredRuns.size
        } else null
        val totalRuns = filteredRuns.size

        // Count sessions that have runs matching the test type
        val matchingSessionIds = filteredRuns.map { it.sessionId }.toSet()
        val totalSessions = matchingSessionIds.size

        // Build session-to-date lookup
        val sessionDateMap = allSessions.associate { it.id to it.date }

        // Build progress points: best time per session for the selected type,
        // ordered by session date
        val sessionBestTimes = filteredRuns
            .groupBy { it.sessionId }
            .mapValues { (_, runs) -> runs.minOf { it.timeSeconds } }

        // Map bestTimePerSession to only matching sessions, attach date
        val progressPoints = sessionBestTimes
            .mapNotNull { (sessionId, best) ->
                val date = sessionDateMap[sessionId] ?: return@mapNotNull null
                Triple(sessionId, date, best)
            }
            .sortedBy { it.second }
            .mapIndexed { index, (_, date, best) ->
                ProgressPoint(
                    sessionIndex = index + 1,
                    dateMillis = date,
                    bestTime = best
                )
            }

        StatsUiState(
            testTypes = testTypes,
            selectedTestType = effectiveSelected,
            bestTime = bestTime,
            averageTime = averageTime,
            totalRuns = totalRuns,
            totalSessions = totalSessions,
            progressPoints = progressPoints,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState()
    )

    fun selectTestType(testType: TestType) {
        selectedTestTypeFlow.update { testType }
    }
}
