package com.trackspeed.android.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PersonalBest(
    val distance: Double,
    val timeSeconds: Double
) {
    val distanceLabel: String
        get() = when {
            distance == 40.0 * 0.9144 -> "40yd"
            distance % 1.0 == 0.0 -> "${distance.toInt()}m"
            else -> "${distance}m"
        }
}

data class ProfileUiState(
    val totalSessions: Int = 0,
    val totalRuns: Int = 0,
    val bestTimeSeconds: Double? = null,
    val personalBests: List<PersonalBest> = emptyList(),
    val isProUser: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {

    private val totalSessions: StateFlow<Int> =
        sessionRepository.getTotalSessionCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    private val totalRuns: StateFlow<Int> =
        sessionRepository.getTotalRunCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    private val bestOverallTime: StateFlow<Double?> =
        sessionRepository.getAllRunsSortedByTime()
            .map { runs -> runs.firstOrNull()?.timeSeconds }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )

    private val personalBests: StateFlow<List<PersonalBest>> =
        sessionRepository.getDistinctDistances()
            .flatMapLatest { distances ->
                if (distances.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val flows = distances.map { distance ->
                        sessionRepository.getPersonalBestFlow(distance)
                            .map { time ->
                                time?.let { PersonalBest(distance, it) }
                            }
                    }
                    combine(flows) { results ->
                        results.filterNotNull().sortedBy { it.distance }
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val uiState: StateFlow<ProfileUiState> =
        combine(
            totalSessions,
            totalRuns,
            bestOverallTime,
            personalBests,
            subscriptionManager.isProUser
        ) { values ->
            ProfileUiState(
                totalSessions = values[0] as Int,
                totalRuns = values[1] as Int,
                bestTimeSeconds = values[2] as Double?,
                personalBests = @Suppress("UNCHECKED_CAST") (values[3] as List<PersonalBest>),
                isProUser = values[4] as Boolean
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProfileUiState()
        )
}
