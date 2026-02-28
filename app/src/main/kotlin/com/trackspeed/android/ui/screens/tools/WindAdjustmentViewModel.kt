package com.trackspeed.android.ui.screens.tools

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import kotlin.math.abs

/**
 * Wind adjustment calculator based on Moinat, Fabius & Emanuel (2018).
 * "Data-driven quantification of the effect of wind on athletics performances."
 * European Journal of Sport Science, 18(9), 1185-1190.
 * Derived from 150,169 competition results.
 *
 * Formula:
 * - 100m: deltaP = (a + cP*P)*w + b*w^2
 * - 200m / hurdles: deltaP = a*w + b*w^2
 * Where w = wind speed (m/s, positive = tailwind) and P is performance time in seconds.
 * Convention: deltaP = time_0 - time_w.
 * Positive deltaP means wind-aided time is faster than zero-wind baseline.
 */
object WindAdjustmentCalculator {

    enum class Event(val displayName: String) {
        SPRINT_100M("100m"),
        SPRINT_200M("200m"),
        HURDLES("100/110m Hurdles");

        /** Linear coefficient (a). */
        val linearCoefficient: Double
            get() = when (this) {
                SPRINT_100M -> -0.0449
                SPRINT_200M -> 0.090
                HURDLES -> 0.093
            }

        /** Quadratic coefficient (b) from Moinat et al. Table 2. */
        val quadraticCoefficient: Double
            get() = when (this) {
                SPRINT_100M -> -0.0042
                SPRINT_200M -> -0.010
                HURDLES -> -0.010
            }

        /**
         * Performance coefficient cP for the 100m supplementary model.
         * Zero for events without performance-dependent correction.
         */
        val performanceCoefficient: Double
            get() = when (this) {
                SPRINT_100M -> 0.009_459
                SPRINT_200M, HURDLES -> 0.0
            }
    }

    /** Maximum wind speed to accept (beyond +/-10 m/s is unrealistic for athletics). */
    const val MAX_WIND_SPEED: Double = 10.0

    /** World Athletics legal wind limit for record eligibility. */
    const val LEGAL_WIND_LIMIT: Double = 2.0

    /**
     * Calculate the wind effect (deltaP) on performance time.
     *
     * @param wind Wind speed in m/s (positive = tailwind, negative = headwind)
     * @param event The sprint event
     * @param performanceTime Athlete's approximate zero-wind time (used for 100m correction)
     * @return deltaP in seconds where deltaP = time_0 - time_w.
     *         Positive = wind-aided time is faster than zero-wind baseline.
     */
    fun windEffect(wind: Double, event: Event, performanceTime: Double? = null): Double {
        val w = wind.coerceIn(-MAX_WIND_SPEED, MAX_WIND_SPEED)
        val a = event.linearCoefficient
        val b = event.quadraticCoefficient

        // 100m supplementary model: deltaP = (a + cP*P)*w + b*w^2
        if (event == Event.SPRINT_100M && performanceTime != null && performanceTime > 0) {
            return (a + event.performanceCoefficient * performanceTime) * w + b * w * w
        }

        // 200m / hurdles model: deltaP = a*w + b*w^2
        return a * w + b * w * w
    }

    /**
     * Adjust a measured time to what it would be with zero wind.
     *
     * @param measuredTime The time recorded in the race (seconds)
     * @param wind Wind speed during the race (m/s, positive = tailwind)
     * @param event The sprint event
     * @return Estimated zero-wind time
     */
    fun adjustToZeroWind(measuredTime: Double, wind: Double, event: Event): Double {
        val delta = windEffect(wind, event, performanceTime = measuredTime)
        // Paper convention: deltaP = time_0 - time_w, so time_0 = time_w + deltaP
        return measuredTime + delta
    }

    /**
     * Estimate what a time would be at a given wind speed.
     *
     * @param zeroWindTime The athlete's zero-wind baseline time (seconds)
     * @param targetWind The wind speed to estimate for (m/s, positive = tailwind)
     * @param event The sprint event
     * @return Estimated time at the target wind speed
     */
    fun estimateAtWind(zeroWindTime: Double, targetWind: Double, event: Event): Double {
        val delta = windEffect(targetWind, event, performanceTime = zeroWindTime)
        // time_w = time_0 - deltaP
        return zeroWindTime - delta
    }

    /** Whether a wind reading is legal for World Athletics records. */
    fun isLegalWind(wind: Double): Boolean = wind <= LEGAL_WIND_LIMIT
}

data class WindAdjustmentUiState(
    val selectedEvent: WindAdjustmentCalculator.Event = WindAdjustmentCalculator.Event.SPRINT_100M,
    val timeInput: String = "",
    val windInput: String = "",
    val hasValidInput: Boolean = false,
    val zeroWindTime: Double? = null,
    val tailwindTime: Double? = null,
    val headwindTime: Double? = null,
    val isLegalWind: Boolean = true,
    val parsedWind: Double? = null
)

@HiltViewModel
class WindAdjustmentViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(WindAdjustmentUiState())
    val uiState: StateFlow<WindAdjustmentUiState> = _uiState.asStateFlow()

    fun setEvent(event: WindAdjustmentCalculator.Event) {
        _uiState.update { it.copy(selectedEvent = event) }
        recalculate()
    }

    fun setTimeInput(input: String) {
        _uiState.update { it.copy(timeInput = input) }
        recalculate()
    }

    fun setWindInput(input: String) {
        _uiState.update { it.copy(windInput = input) }
        recalculate()
    }

    private fun recalculate() {
        val state = _uiState.value
        val time = state.timeInput.toDoubleOrNull()
        val wind = state.windInput.toDoubleOrNull()

        if (time == null || time <= 0 || wind == null || abs(wind) > WindAdjustmentCalculator.MAX_WIND_SPEED) {
            _uiState.update {
                it.copy(
                    hasValidInput = false,
                    zeroWindTime = null,
                    tailwindTime = null,
                    headwindTime = null,
                    isLegalWind = wind?.let { w -> WindAdjustmentCalculator.isLegalWind(w) } ?: true,
                    parsedWind = wind
                )
            }
            return
        }

        val zeroWind = WindAdjustmentCalculator.adjustToZeroWind(time, wind, state.selectedEvent)
        val tailwind = WindAdjustmentCalculator.estimateAtWind(zeroWind, 2.0, state.selectedEvent)
        val headwind = WindAdjustmentCalculator.estimateAtWind(zeroWind, -2.0, state.selectedEvent)

        _uiState.update {
            it.copy(
                hasValidInput = true,
                zeroWindTime = zeroWind,
                tailwindTime = tailwind,
                headwindTime = headwind,
                isLegalWind = WindAdjustmentCalculator.isLegalWind(wind),
                parsedWind = wind
            )
        }
    }
}
