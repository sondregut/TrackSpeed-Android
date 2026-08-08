package com.trackspeed.android.ui.screens.tools

import androidx.lifecycle.ViewModel
import com.trackspeed.android.data.model.FlyingDistance as ProfileFlyingDistance
import com.trackspeed.android.data.model.SportDiscipline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.sqrt

enum class ConverterMode(val label: String) {
    DISTANCE("Distance"),
    FLYING("Flying"),
    PREDICTOR("Predictor"),
    LANES("Lanes")
}

enum class SprintGender(val displayName: String) {
    MEN("Men"),
    WOMEN("Women")
}

enum class SprintDistance(val displayName: String, val meters: Int) {
    M60("60m", 60),
    M100("100m", 100),
    M200("200m", 200)
}

object SprintDistanceConverter {
    private data class WaCoefficients(
        val a: Double,
        val b: Double,
        val c: Double
    ) {
        fun points(timeSeconds: Double): Double {
            return (a * timeSeconds * timeSeconds + b * timeSeconds + c).coerceAtLeast(0.0)
        }

        fun time(points: Double): Double {
            if (points <= 0.0) return 0.0
            val discriminant = b * b - 4 * a * (c - points)
            if (discriminant < 0.0) return 0.0
            return (-b - sqrt(discriminant)) / (2 * a)
        }
    }

    fun convert(
        timeSeconds: Double,
        from: SprintDistance,
        to: SprintDistance,
        gender: SprintGender
    ): Double {
        if (from == to) return timeSeconds
        val points = coefficients(from, gender).points(timeSeconds)
        return coefficients(to, gender).time(points)
    }

    fun convertToAll(
        timeSeconds: Double,
        from: SprintDistance,
        gender: SprintGender
    ): Map<SprintDistance, Double> {
        return SprintDistance.entries
            .filterNot { it == from }
            .associateWith { convert(timeSeconds, from, it, gender) }
    }

    private fun coefficients(distance: SprintDistance, gender: SprintGender): WaCoefficients {
        val female = gender == SprintGender.WOMEN
        return when (distance to female) {
            SprintDistance.M60 to false -> WaCoefficients(a = 68.6203, b = -1468.376, c = 7854.924)
            SprintDistance.M60 to true -> WaCoefficients(a = 24.9118, b = -697.413, c = 4880.841)
            SprintDistance.M100 to false -> WaCoefficients(a = 24.6422, b = -837.714, c = 7119.313)
            SprintDistance.M100 to true -> WaCoefficients(a = 9.9274, b = -436.675, c = 4802.021)
            SprintDistance.M200 to false -> WaCoefficients(a = 5.0833, b = -360.826, c = 6403.154)
            else -> WaCoefficients(a = 2.2422, b = -204.015, c = 4640.727)
        }
    }
}

object FlyingSprintConverter {
    const val DEFAULT_ACCELERATION_PENALTY: Double = 1.00

    enum class FlyingDistance(val displayName: String, val meters: Double, val caveat: String) {
        FLY10(
            displayName = "Flying 10m",
            meters = 10.0,
            caveat = "Converted to equivalent 30m fly via max velocity. Short distance amplifies individual variation."
        ),
        FLY20(
            displayName = "Flying 20m",
            meters = 20.0,
            caveat = "Converted to equivalent 30m fly via max velocity. Good balance of reliability and practicality."
        ),
        FLY30(
            displayName = "Flying 30m",
            meters = 30.0,
            caveat = "Most reliable for 100m prediction. Widely used by coaches."
        )
    }

    data class FlyingResult(
        val time100m: Double,
        val conversions: Map<SprintDistance, Double>,
        val velocityMs: Double,
        val velocityKmh: Double
    )

    fun predict100m(flyTime: Double, flyDistance: FlyingDistance): Double {
        val fly30 = flyTime * (30.0 / flyDistance.meters)
        return (10.0 / 3.0) * fly30 + DEFAULT_ACCELERATION_PENALTY
    }

    fun maxVelocity(flyTime: Double, flyDistance: FlyingDistance): Pair<Double, Double> {
        val ms = flyDistance.meters / flyTime
        return ms to ms * 3.6
    }

    fun predictAll(
        flyTime: Double,
        flyDistance: FlyingDistance,
        gender: SprintGender
    ): FlyingResult {
        val predicted100m = predict100m(flyTime, flyDistance)
        val (ms, kmh) = maxVelocity(flyTime, flyDistance)
        val conversions = SprintDistance.entries.associateWith { distance ->
            if (distance == SprintDistance.M100) {
                predicted100m
            } else {
                SprintDistanceConverter.convert(
                    timeSeconds = predicted100m,
                    from = SprintDistance.M100,
                    to = distance,
                    gender = gender
                )
            }
        }
        return FlyingResult(
            time100m = predicted100m,
            conversions = conversions,
            velocityMs = ms,
            velocityKmh = kmh
        )
    }
}

object LaneDrawConverter {
    const val EFFECT_PER_LANE: Double = 0.018
    val laneRange: IntRange = 1..9

    fun convert(timeSeconds: Double, fromLane: Int, toLane: Int): Double {
        return timeSeconds + EFFECT_PER_LANE * (fromLane - toLane).toDouble()
    }

    fun convertToAllLanes(timeSeconds: Double, fromLane: Int): Map<Int, Double> {
        return laneRange.associateWith { lane -> convert(timeSeconds, fromLane, lane) }
    }
}

object FlyingTimeEstimator {
    val supportedEventDisciplines: List<SportDiscipline> = listOf(
        SportDiscipline.SPRINT_60M,
        SportDiscipline.SPRINT_100M,
        SportDiscipline.SPRINT_200M
    )

    fun estimateFlyingTime(
        eventTime: Double,
        event: SportDiscipline,
        targetDistance: ProfileFlyingDistance
    ): Double? {
        val equivalent100m = equivalent100mTime(eventTime, event) ?: return null
        if (equivalent100m <= FlyingSprintConverter.DEFAULT_ACCELERATION_PENALTY) return null
        return (equivalent100m - FlyingSprintConverter.DEFAULT_ACCELERATION_PENALTY) *
            targetDistance.meters.toDouble() / 100.0
    }

    private fun equivalent100mTime(eventTime: Double, event: SportDiscipline): Double? {
        if (eventTime <= 0.0 || !eventTime.isFinite()) return null
        return when (event) {
            SportDiscipline.SPRINT_100M -> eventTime
            SportDiscipline.SPRINT_60M -> neutralConverted100mTime(eventTime, SprintDistance.M60)
            SportDiscipline.SPRINT_200M -> neutralConverted100mTime(eventTime, SprintDistance.M200)
            else -> null
        }
    }

    private fun neutralConverted100mTime(
        timeSeconds: Double,
        from: SprintDistance
    ): Double? {
        val estimates = SprintGender.entries
            .map {
                SprintDistanceConverter.convert(
                    timeSeconds = timeSeconds,
                    from = from,
                    to = SprintDistance.M100,
                    gender = it
                )
            }
            .filter { it.isFinite() && it > 0.0 }

        if (estimates.isEmpty()) return null
        return estimates.sum() / estimates.size.toDouble()
    }
}

object SprintPredictor {
    const val DEFAULT_REACTION_TIME: Double = 0.149
    const val MINIMUM_REACTION_TIME: Double = 0.100

    private const val V0: Double = 2.2441850962837124
    private const val V1: Double = 0.14735859276138946
    private const val V2: Double = -0.042918544171317706

    data class PredictionResult(
        val predicted100m: Double,
        val conversions: Map<SprintDistance, Double>,
        val velocityMs: Double,
        val velocityKmh: Double
    )

    fun predict100m(
        block30: Double,
        fly10: Double,
        wind: Double = 0.0,
        reactionTime: Double = DEFAULT_REACTION_TIME
    ): Double {
        val rt = reactionTime.coerceAtLeast(MINIMUM_REACTION_TIME)
        val maxVelocity = 10.0 / fly10
        val race = exp(V0 + V1 * block30 + V2 * maxVelocity)
        val delta = WindAdjustmentCalculator.windEffect(
            wind = wind,
            event = WindAdjustmentCalculator.Event.SPRINT_100M,
            performanceTime = race
        )
        return race - delta + rt
    }

    fun predictAll(
        block30: Double,
        fly10: Double,
        wind: Double = 0.0,
        reactionTime: Double = DEFAULT_REACTION_TIME,
        gender: SprintGender
    ): PredictionResult {
        val predicted100m = predict100m(block30, fly10, wind, reactionTime)
        val ms = 10.0 / fly10
        val conversions = SprintDistance.entries.associateWith { distance ->
            if (distance == SprintDistance.M100) {
                predicted100m
            } else {
                SprintDistanceConverter.convert(
                    timeSeconds = predicted100m,
                    from = SprintDistance.M100,
                    to = distance,
                    gender = gender
                )
            }
        }
        return PredictionResult(
            predicted100m = predicted100m,
            conversions = conversions,
            velocityMs = ms,
            velocityKmh = ms * 3.6
        )
    }
}

data class DistanceConverterUiState(
    val mode: ConverterMode = ConverterMode.DISTANCE,
    val gender: SprintGender = SprintGender.MEN,
    val selectedDistance: SprintDistance = SprintDistance.M100,
    val timeInput: String = "",
    val distanceResults: Map<SprintDistance, Double> = emptyMap(),
    val flyingDistance: FlyingSprintConverter.FlyingDistance = FlyingSprintConverter.FlyingDistance.FLY30,
    val flyingTimeInput: String = "",
    val flyingResult: FlyingSprintConverter.FlyingResult? = null,
    val block30Input: String = "",
    val fly10Input: String = "",
    val predictorWindInput: String = "0.0",
    val reactionTimeInput: String = SprintPredictor.DEFAULT_REACTION_TIME.toString(),
    val predictorResult: SprintPredictor.PredictionResult? = null,
    val laneTimeInput: String = "",
    val selectedLane: Int = 5,
    val laneResults: Map<Int, Double> = emptyMap()
)

@HiltViewModel
class DistanceConverterViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(DistanceConverterUiState())
    val uiState: StateFlow<DistanceConverterUiState> = _uiState.asStateFlow()

    fun setMode(mode: ConverterMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    fun setGender(gender: SprintGender) {
        _uiState.update { it.copy(gender = gender) }
        recalculate()
    }

    fun setSelectedDistance(distance: SprintDistance) {
        _uiState.update { it.copy(selectedDistance = distance) }
        recalculate()
    }

    fun setTimeInput(input: String) {
        _uiState.update { it.copy(timeInput = input) }
        recalculate()
    }

    fun setFlyingDistance(distance: FlyingSprintConverter.FlyingDistance) {
        _uiState.update { it.copy(flyingDistance = distance) }
        recalculate()
    }

    fun setFlyingTimeInput(input: String) {
        _uiState.update { it.copy(flyingTimeInput = input) }
        recalculate()
    }

    fun setBlock30Input(input: String) {
        _uiState.update { it.copy(block30Input = input) }
        recalculate()
    }

    fun setFly10Input(input: String) {
        _uiState.update { it.copy(fly10Input = input) }
        recalculate()
    }

    fun setPredictorWindInput(input: String) {
        _uiState.update { it.copy(predictorWindInput = input) }
        recalculate()
    }

    fun setReactionTimeInput(input: String) {
        _uiState.update { it.copy(reactionTimeInput = input) }
        recalculate()
    }

    fun setLaneTimeInput(input: String) {
        _uiState.update { it.copy(laneTimeInput = input) }
        recalculate()
    }

    fun setSelectedLane(lane: Int) {
        _uiState.update { it.copy(selectedLane = lane.coerceIn(LaneDrawConverter.laneRange)) }
        recalculate()
    }

    private fun recalculate() {
        val state = _uiState.value
        val inputTime = state.timeInput.positiveDoubleOrNull()
        val flyingTime = state.flyingTimeInput.positiveDoubleOrNull()
        val block30 = state.block30Input.positiveDoubleOrNull()
        val fly10 = state.fly10Input.positiveDoubleOrNull()
        val reactionTime = state.reactionTimeInput.toDoubleOrNull() ?: SprintPredictor.DEFAULT_REACTION_TIME
        val wind = (state.predictorWindInput.toDoubleOrNull() ?: 0.0)
            .coerceIn(-WindAdjustmentCalculator.MAX_WIND_SPEED, WindAdjustmentCalculator.MAX_WIND_SPEED)
        val laneTime = state.laneTimeInput.positiveDoubleOrNull()

        _uiState.update {
            it.copy(
                distanceResults = inputTime?.let { time ->
                    SprintDistanceConverter.convertToAll(time, state.selectedDistance, state.gender)
                } ?: emptyMap(),
                flyingResult = flyingTime?.let { time ->
                    FlyingSprintConverter.predictAll(time, state.flyingDistance, state.gender)
                },
                predictorResult = if (block30 != null && fly10 != null) {
                    SprintPredictor.predictAll(
                        block30 = block30,
                        fly10 = fly10,
                        wind = wind,
                        reactionTime = reactionTime,
                        gender = state.gender
                    )
                } else {
                    null
                },
                laneResults = laneTime?.let { time ->
                    LaneDrawConverter.convertToAllLanes(time, state.selectedLane)
                } ?: emptyMap()
            )
        }
    }

    private fun String.positiveDoubleOrNull(): Double? {
        return toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
    }
}
