package com.trackspeed.android.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.model.StartType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import javax.inject.Inject

enum class TestType(
    val displayName: String,
    val shortName: String
) {
    FLYING_10M("Flying 10m", "F10"),
    FLYING_20M("Flying 20m", "F20"),
    FLYING_30M("Flying 30m", "F30"),
    SPRINT_10M("10m Sprint", "10m"),
    SPRINT_20M("20m Sprint", "20m"),
    SPRINT_30M("30m Sprint", "30m"),
    SPRINT_60M("60m Sprint", "60m"),
    SPRINT_100M("100m Sprint", "100m"),
    FORTY_YARD_DASH("40 Yard Dash", "40yd"),
    PRO_AGILITY("Pro Agility", "5-10-5"),
    L_DRILL("L-Drill", "3-Cone"),
    TAKE_OFF_VELOCITY("TOV 5m", "TOV"),
    PRACTICE("Practice", "Laps"),
    OTHER("Other", "Other");

    val label: String get() = shortName
}

object TestTypeClassifier {
    fun classify(distance: Double, startTypeRaw: String, presetId: String? = null): TestType {
        when (presetId) {
            "40yd" -> return TestType.FORTY_YARD_DASH
            "30m" -> return TestType.SPRINT_30M
            "5-10-5" -> return TestType.PRO_AGILITY
            "l-drill" -> return TestType.L_DRILL
            "takeoff-velocity" -> return TestType.TAKE_OFF_VELOCITY
            "flying-10m" -> return TestType.FLYING_10M
            "flying-30m" -> return TestType.FLYING_30M
            "practice" -> return TestType.PRACTICE
        }

        return when (StartType.fromRawValue(startTypeRaw)) {
            StartType.FLYING -> classifyFlying(distance)
            StartType.IN_FRAME -> classifyInFrame(distance)
            StartType.TOUCH_RELEASE,
            StartType.COUNTDOWN,
            StartType.VOICE_COMMAND -> classifySprint(distance)
        }
    }

    fun classify(run: RunEntity): TestType {
        return classify(
            distance = run.distance,
            startTypeRaw = run.startType
        )
    }

    fun availableTypes(runs: List<RunEntity>): List<TestType> {
        val types = runs.mapTo(mutableSetOf()) { classify(it) }
        return TestType.entries.filter { it in types }
    }

    private fun classifyFlying(distance: Double): TestType {
        return when {
            distance == 0.0 -> TestType.PRACTICE
            distance == 5.0 -> TestType.TAKE_OFF_VELOCITY
            distance <= 12.0 -> TestType.FLYING_10M
            distance < 25.0 -> TestType.FLYING_20M
            distance >= 25.0 -> TestType.FLYING_30M
            else -> TestType.OTHER
        }
    }

    private fun classifyInFrame(distance: Double): TestType {
        return when {
            distance > 15.0 && distance < 25.0 -> TestType.PRO_AGILITY
            distance > 25.0 && distance < 30.0 -> TestType.L_DRILL
            else -> TestType.OTHER
        }
    }

    private fun classifySprint(distance: Double): TestType {
        if (abs(distance - 36.576) < 0.5) return TestType.FORTY_YARD_DASH

        return when {
            distance <= 12.0 -> TestType.SPRINT_10M
            distance < 25.0 -> TestType.SPRINT_20M
            distance < 55.0 -> TestType.SPRINT_30M
            distance < 65.0 -> TestType.SPRINT_60M
            distance >= 95.0 && distance < 105.0 -> TestType.SPRINT_100M
            else -> TestType.OTHER
        }
    }
}

data class ProgressPoint(
    val sessionIndex: Int,
    val dateMillis: Long,
    val bestTime: Double
)

enum class StatsTimeRange(
    val displayName: String,
    val daysBack: Long?
) {
    RECENT("Recent", null),
    DAYS_90("90d", 90),
    DAYS_30("30d", 30);

    fun contextText(sessionCount: Int): String {
        return when (this) {
            RECENT -> "Latest $sessionCount sessions"
            DAYS_90 -> "$sessionCount sessions in the last 90 days"
            DAYS_30 -> "$sessionCount sessions in the last 30 days"
        }
    }

    val emptyStateTitle: String
        get() = when (this) {
            RECENT -> "No Data Yet"
            DAYS_90, DAYS_30 -> "No Data in Range"
        }

    val emptyStateMessage: String
        get() = when (this) {
            RECENT -> "Complete some sessions to see your progress"
            DAYS_90 -> "No matching sessions in the last 90 days"
            DAYS_30 -> "No matching sessions in the last 30 days"
        }
}

data class AthleteFilter(
    val id: String,
    val name: String,
    val color: String?,
    val runCount: Int
)

data class StatsUiState(
    val timeRanges: List<StatsTimeRange> = StatsTimeRange.entries,
    val selectedTimeRange: StatsTimeRange = StatsTimeRange.RECENT,
    val rangeContextText: String = "",
    val testTypes: List<TestType> = emptyList(),
    val selectedTestType: TestType? = null,
    val athleteFilters: List<AthleteFilter> = emptyList(),
    val selectedAthleteId: String? = null,
    val rangeBestTime: Double? = null,
    val rangeBestDateMillis: Long? = null,
    val bestTime: Double? = null,
    val recentAverageTime: Double? = null,
    val averageTime: Double? = null,
    val bestSpeed: Double? = null,
    val performanceDelta: Double? = null,
    val consistency: Double? = null,
    val averageReactionTime: Double? = null,
    val totalRuns: Int = 0,
    val totalSessions: Int = 0,
    val speedUnit: String = "m/s",
    val progressPoints: List<ProgressPoint> = emptyList(),
    val emptyStateTitle: String = "No Data Yet",
    val emptyStateMessage: String = "Complete some sessions to see your progress",
    val isLoading: Boolean = true
)

private data class StatsSelection(
    val timeRange: StatsTimeRange,
    val testType: TestType?,
    val athleteId: String?
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val selectedTestTypeFlow = MutableStateFlow<TestType?>(null)
    private val selectedTimeRangeFlow = MutableStateFlow(StatsTimeRange.RECENT)
    private val selectedAthleteIdFlow = MutableStateFlow<String?>(null)
    private val selectionFlow = combine(
        selectedTimeRangeFlow,
        selectedTestTypeFlow,
        selectedAthleteIdFlow
    ) { timeRange, testType, athleteId ->
        StatsSelection(timeRange, testType, athleteId)
    }

    val uiState: StateFlow<StatsUiState> = combine(
        sessionRepository.getAllRunsSortedByTime(),
        sessionRepository.getAllSessions(),
        settingsRepository.speedUnit,
        selectionFlow
    ) { allRuns, allSessions, speedUnit, selection ->
        val selectedRange = selection.timeRange
        val selectedType = selection.testType
        val selectedAthleteId = selection.athleteId
        val now = System.currentTimeMillis()
        val scopedSessions = when (val daysBack = selectedRange.daysBack) {
            null -> allSessions.sortedByDescending { it.date }.take(RECENT_SESSION_LIMIT)
            else -> {
                val startMillis = now - daysBack * 24L * 60L * 60L * 1000L
                allSessions.filter { it.date >= startMillis }
            }
        }
        val scopedSessionIds = scopedSessions.map { it.id }.toSet()
        val sessionDateMap = scopedSessions.associate { it.id to it.date }
        val scopedRuns = allRuns.filter { it.sessionId in scopedSessionIds && it.timeSeconds > 0 }

        val testTypes = TestTypeClassifier.availableTypes(scopedRuns)

        // Auto-select first type if nothing selected or selection is invalid
        val effectiveSelected = if (selectedType != null && selectedType in testTypes) {
            selectedType
        } else {
            testTypes.firstOrNull()
        }

        if (effectiveSelected == null) {
            return@combine StatsUiState(
                selectedTimeRange = selectedRange,
                rangeContextText = selectedRange.contextText(scopedSessions.size),
                testTypes = emptyList(),
                selectedTestType = null,
                speedUnit = speedUnit,
                emptyStateTitle = selectedRange.emptyStateTitle,
                emptyStateMessage = selectedRange.emptyStateMessage,
                isLoading = false
            )
        }

        // Filter runs for the selected test type
        val typeMatchedRuns = scopedRuns.filter {
            TestTypeClassifier.classify(it) == effectiveSelected
        }
        val athleteFilters = typeMatchedRuns
            .filter { !it.athleteId.isNullOrBlank() }
            .groupBy { it.athleteId.orEmpty() }
            .map { (athleteId, runs) ->
                val first = runs.first()
                AthleteFilter(
                    id = athleteId,
                    name = first.athleteName ?: "Unknown",
                    color = first.athleteColor,
                    runCount = runs.size
                )
            }
            .sortedBy { it.name.lowercase() }

        val effectiveAthleteId = selectedAthleteId
            ?.takeIf { id -> athleteFilters.any { it.id == id } }
        val filteredRuns = effectiveAthleteId?.let { athleteId ->
            typeMatchedRuns.filter { it.athleteId == athleteId }
        } ?: typeMatchedRuns

        // Compute summary stats
        val times = filteredRuns.map { it.timeSeconds }
        val bestRun = filteredRuns.minByOrNull { it.timeSeconds }
        val bestTime = bestRun?.timeSeconds
        val averageTime = if (filteredRuns.isNotEmpty()) {
            filteredRuns.sumOf { it.timeSeconds } / filteredRuns.size
        } else null
        val chronologicalRuns = filteredRuns.sortedBy { it.createdAt }
        val recentRuns = chronologicalRuns.takeLast(5)
        val recentAverageTime = if (recentRuns.isNotEmpty()) {
            recentRuns.sumOf { it.timeSeconds } / recentRuns.size
        } else null
        val bestSpeed = filteredRuns
            .filter { it.distance > 0 && it.timeSeconds > 0 }
            .maxOfOrNull { it.distance / it.timeSeconds }
        val consistency = if (times.size > 1) {
            val mean = times.sum() / times.size
            sqrt(times.sumOf { (it - mean).pow(2) } / times.size)
        } else null
        val averageReactionTime = filteredRuns
            .mapNotNull { it.reactionTime }
            .takeIf { it.isNotEmpty() }
            ?.let { it.sum() / it.size }
        val totalRuns = filteredRuns.size

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
        val totalSessions = progressPoints.size
        val performanceDelta = if (progressPoints.size > 1) {
            progressPoints.last().bestTime - progressPoints.first().bestTime
        } else null

        StatsUiState(
            selectedTimeRange = selectedRange,
            rangeContextText = selectedRange.contextText(scopedSessions.size),
            testTypes = testTypes,
            selectedTestType = effectiveSelected,
            athleteFilters = athleteFilters,
            selectedAthleteId = effectiveAthleteId,
            rangeBestTime = bestRun?.timeSeconds,
            rangeBestDateMillis = bestRun?.let { run -> sessionDateMap[run.sessionId] ?: run.createdAt },
            bestTime = bestTime,
            recentAverageTime = recentAverageTime,
            averageTime = averageTime,
            bestSpeed = bestSpeed,
            performanceDelta = performanceDelta,
            consistency = consistency,
            averageReactionTime = averageReactionTime,
            totalRuns = totalRuns,
            totalSessions = totalSessions,
            speedUnit = speedUnit,
            progressPoints = progressPoints,
            emptyStateTitle = selectedRange.emptyStateTitle,
            emptyStateMessage = selectedRange.emptyStateMessage,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState()
    )

    fun selectTestType(testType: TestType) {
        selectedTestTypeFlow.update { testType }
        selectedAthleteIdFlow.update { null }
    }

    fun selectTimeRange(timeRange: StatsTimeRange) {
        selectedTimeRangeFlow.update { timeRange }
    }

    fun selectAthlete(athleteId: String?) {
        selectedAthleteIdFlow.update { athleteId }
    }

    private companion object {
        const val RECENT_SESSION_LIMIT = 200
    }
}
