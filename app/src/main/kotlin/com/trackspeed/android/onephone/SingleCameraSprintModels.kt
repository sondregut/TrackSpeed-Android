package com.trackspeed.android.onephone

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Port of iOS `SingleCameraSprintModels.swift` (one-phone "Landscape video"
 * mode). One phone films the whole course side-on; the user drags a gate over
 * every measured cone, and body-pose root crossings of those gates are timed on
 * the camera timestamp domain.
 *
 * Coordinates are normalized to the displayed camera frame: x grows to the
 * right, y grows downward (Android convention; iOS Vision is y-up, which only
 * affects drawing, not timing).
 */
enum class SingleCameraSprintDirection(val rawValue: String) {
    LEFT_TO_RIGHT("leftToRight"),
    RIGHT_TO_LEFT("rightToLeft");

    fun directedPosition(normalizedX: Double): Double = when (this) {
        LEFT_TO_RIGHT -> normalizedX
        RIGHT_TO_LEFT -> 1 - normalizedX
    }

    val flipped: SingleCameraSprintDirection
        get() = if (this == LEFT_TO_RIGHT) RIGHT_TO_LEFT else LEFT_TO_RIGHT

    companion object {
        fun fromRaw(raw: String?): SingleCameraSprintDirection =
            entries.firstOrNull { it.rawValue == raw } ?: LEFT_TO_RIGHT
    }
}

enum class SingleCameraSprintStartStyle(val rawValue: String) {
    STANDING("standing"),
    FLYING("flying");

    companion object {
        fun fromRaw(raw: String?): SingleCameraSprintStartStyle =
            entries.firstOrNull { it.rawValue == raw } ?: STANDING
    }
}

/**
 * Timing boundaries for a straight sprint or a return-to-start drill. Camera
 * timing measures body-plane crossings; cone/line touches must be reviewed
 * separately before an agility result is compared.
 */
enum class SingleCameraSprintDrill(val rawValue: String) {
    SPRINT("sprint"),
    PRO_AGILITY("proAgility"),
    SHUTTLE("shuttle");

    val requiresTurnReview: Boolean get() = this != SPRINT

    fun startDistance(courseSpan: Double): Double =
        if (this == PRO_AGILITY) courseSpan / 2 else 0.0

    fun totalDistance(courseSpan: Double): Double =
        if (this == SPRINT) courseSpan else courseSpan * 2

    companion object {
        fun fromRaw(raw: String?): SingleCameraSprintDrill =
            entries.firstOrNull { it.rawValue == raw } ?: SPRINT
    }
}

/** Engine-level invalidation reasons. The UI maps each code to a string resource. */
enum class SingleCameraInvalidReason {
    GATE_LAYOUT,
    FLYING_FRAMING,
    PRO_AGILITY_LAYOUT,
    TRACKING_LOST,
    FLYING_LOCK_UNAVAILABLE,
    REACHED_START_BEFORE_READY,
    STANDING_BASELINE_UNAVAILABLE,
    START_TIMESTAMP_UNAVAILABLE,
    POSITION_JUMPED,
    START_NOT_BRACKETED,
    CAMERA_INTERRUPTED
}

sealed interface SingleCameraSprintPhase {
    data object Calibrating : SingleCameraSprintPhase
    data object Acquiring : SingleCameraSprintPhase
    data class Locking(val progress: Double) : SingleCameraSprintPhase
    data class Settling(val progress: Double) : SingleCameraSprintPhase
    data object Armed : SingleCameraSprintPhase
    data object Running : SingleCameraSprintPhase
    data object Completed : SingleCameraSprintPhase
    data class Invalid(val reason: SingleCameraInvalidReason) : SingleCameraSprintPhase

    val isRunInProgress: Boolean
        get() = this is Acquiring || this is Locking || this is Settling || this == Armed || this == Running
}

data class SingleCameraSprintGate(
    val distanceMeters: Double,
    val normalizedX: Double
)

private const val DISTANCE_EPSILON = 0.000_1

private fun sameDistance(lhs: Double, rhs: Double): Boolean = abs(lhs - rhs) < DISTANCE_EPSILON

/**
 * Keeps every gate independently draggable while preserving a valid course.
 * Gestures update one distance at a time; neighbouring gates do not move, and
 * the dragged gate is clamped before it can cross either one.
 */
object SingleCameraSprintGateLayout {
    const val EDGE_INSET = 0.035
    const val MINIMUM_DIRECTED_SPACING = 0.055

    fun moving(
        distanceMeters: Double,
        toNormalizedX: Double,
        gates: List<SingleCameraSprintGate>,
        direction: SingleCameraSprintDirection
    ): List<SingleCameraSprintGate> {
        val ordered = gates.sortedBy { it.distanceMeters }
        val index = ordered.indexOfFirst { sameDistance(it.distanceMeters, distanceMeters) }
        if (index < 0) return gates

        val requested = direction.directedPosition(toNormalizedX.coerceIn(EDGE_INSET, 1 - EDGE_INSET))
        val lowerBound = if (index == 0) {
            EDGE_INSET
        } else {
            direction.directedPosition(ordered[index - 1].normalizedX) + MINIMUM_DIRECTED_SPACING
        }
        val upperBound = if (index == ordered.lastIndex) {
            1 - EDGE_INSET
        } else {
            direction.directedPosition(ordered[index + 1].normalizedX) - MINIMUM_DIRECTED_SPACING
        }
        val directed = min(max(requested, lowerBound), upperBound)
        val updatedX = if (direction == SingleCameraSprintDirection.LEFT_TO_RIGHT) directed else 1 - directed

        return gates.map { gate ->
            if (sameDistance(gate.distanceMeters, distanceMeters)) gate.copy(normalizedX = updatedX) else gate
        }
    }

    fun flipped(gates: List<SingleCameraSprintGate>): List<SingleCameraSprintGate> =
        gates.map { it.copy(normalizedX = 1 - it.normalizedX) }

    /**
     * Keeps already aligned cone positions when a compatible start style or
     * shorter course is selected. Missing gates are projected from the current
     * calibration when possible; an invalid combination falls back to the new
     * configuration's safe default layout.
     */
    fun reconfigured(
        gates: List<SingleCameraSprintGate>,
        configuration: SingleCameraSprintConfiguration,
        direction: SingleCameraSprintDirection
    ): List<SingleCameraSprintGate> {
        val defaults = configuration.defaultGates(direction)
        if (gates.isEmpty()) return defaults
        val calibration = SingleCameraCourseCalibration.create(gates, direction)
        val candidate = defaults.map { defaultGate ->
            gates.firstOrNull { sameDistance(it.distanceMeters, defaultGate.distanceMeters) }
                ?: run {
                    if (calibration == null) return@run defaultGate
                    val projectedX = calibration.normalizedX(defaultGate.distanceMeters)
                    if (!projectedX.isFinite() || projectedX < EDGE_INSET || projectedX > 1 - EDGE_INSET) {
                        defaultGate
                    } else {
                        defaultGate.copy(normalizedX = projectedX)
                    }
                }
        }
        if (!keepsHandleSpacing(candidate, direction) ||
            SingleCameraCourseCalibration.create(candidate, direction) == null
        ) {
            return defaults
        }
        return candidate
    }

    internal fun keepsHandleSpacing(
        gates: List<SingleCameraSprintGate>,
        direction: SingleCameraSprintDirection
    ): Boolean {
        val directed = gates.sortedBy { it.distanceMeters }.map { direction.directedPosition(it.normalizedX) }
        return directed.zipWithNext().all { (left, right) -> right - left >= MINIMUM_DIRECTED_SPACING - 0.000_1 }
    }
}

@ConsistentCopyVisibility
data class SingleCameraSprintConfiguration private constructor(
    val distanceMeters: Double,
    val startStyle: SingleCameraSprintStartStyle,
    val drill: SingleCameraSprintDrill
) {
    fun changingStartStyle(startStyle: SingleCameraSprintStartStyle) = create(distanceMeters, startStyle, drill)
    fun changingDistance(distanceMeters: Double) = create(distanceMeters, startStyle, drill)
    fun changingDrill(drill: SingleCameraSprintDrill) = create(distanceMeters, startStyle, drill)

    val courseSpanMeters: Double
        get() = if (drill == SingleCameraSprintDrill.SPRINT) distanceMeters else distanceMeters / 2

    val gateDistances: List<Double>
        get() = when {
            drill == SingleCameraSprintDrill.PRO_AGILITY -> listOf(0.0, 4.572, 9.144)
            drill == SingleCameraSprintDrill.SHUTTLE -> listOf(0.0, courseSpanMeters)
            distanceMeters <= 5 -> listOf(0.0, 5.0)
            distanceMeters <= 10 -> listOf(0.0, 5.0, 10.0)
            distanceMeters <= 15 -> listOf(0.0, 5.0, 10.0, 15.0)
            startStyle == SingleCameraSprintStartStyle.FLYING -> listOf(0.0, 5.0, 10.0, 15.0, 20.0)
            else -> listOf(0.0, 5.0, 10.0, 20.0)
        }

    fun defaultGates(
        direction: SingleCameraSprintDirection = SingleCameraSprintDirection.LEFT_TO_RIGHT
    ): List<SingleCameraSprintGate> {
        val (lower, upper) = if (startStyle == SingleCameraSprintStartStyle.FLYING) {
            SingleCameraSprintFraming.defaultDirectedGateBounds(courseSpanMeters)
        } else {
            0.14 to 0.86
        }
        return gateDistances.map { distance ->
            val progress = distance / courseSpanMeters
            val directedX = lower + progress * (upper - lower)
            val normalizedX = if (direction == SingleCameraSprintDirection.LEFT_TO_RIGHT) directedX else 1 - directedX
            SingleCameraSprintGate(distance, normalizedX)
        }
    }

    companion object {
        val SPRINT_DISTANCES = listOf(5.0, 10.0, 15.0, 20.0)
        const val PRO_AGILITY_DISTANCE_METERS = 18.288

        fun create(
            distanceMeters: Double,
            startStyle: SingleCameraSprintStartStyle = SingleCameraSprintStartStyle.STANDING,
            drill: SingleCameraSprintDrill = SingleCameraSprintDrill.SPRINT
        ): SingleCameraSprintConfiguration {
            val resolvedStyle = if (drill == SingleCameraSprintDrill.PRO_AGILITY) {
                SingleCameraSprintStartStyle.STANDING
            } else {
                startStyle
            }
            val requested = if (distanceMeters.isFinite() && distanceMeters > 0) distanceMeters else 10.0
            val resolvedDistance = supportedDistances(startStyle, drill)
                .minByOrNull { abs(it - requested) } ?: 10.0
            return SingleCameraSprintConfiguration(resolvedDistance, resolvedStyle, drill)
        }

        fun supportedDistances(
            startStyle: SingleCameraSprintStartStyle,
            drill: SingleCameraSprintDrill = SingleCameraSprintDrill.SPRINT
        ): List<Double> = when (drill) {
            SingleCameraSprintDrill.PRO_AGILITY -> listOf(PRO_AGILITY_DISTANCE_METERS)
            SingleCameraSprintDrill.SHUTTLE -> listOf(10.0, 20.0, 30.0, 40.0)
            SingleCameraSprintDrill.SPRINT -> SPRINT_DISTANCES
        }
    }
}

data class SingleCameraSprintFramingAssessment(
    val acquisitionMeters: Double,
    val exitMeters: Double
) {
    val isReady: Boolean
        get() = acquisitionMeters + 0.000_001 >= SingleCameraSprintFraming.REQUIRED_ACQUISITION_METERS &&
            exitMeters + 0.000_001 >= SingleCameraSprintFraming.REQUIRED_EXIT_METERS
}

/**
 * Flying starts need room before the 0 m plane to establish one athlete track,
 * plus room after the finish for a pose observation that brackets the crossing.
 */
object SingleCameraSprintFraming {
    const val SAFE_DIRECTED_LOWER_BOUND = 0.08
    const val SAFE_DIRECTED_UPPER_BOUND = 0.92
    const val REQUIRED_ACQUISITION_METERS = 3.0
    const val REQUIRED_EXIT_METERS = 2.0

    fun defaultDirectedGateBounds(distanceMeters: Double): Pair<Double, Double> {
        val visibleMeters = REQUIRED_ACQUISITION_METERS + distanceMeters + REQUIRED_EXIT_METERS
        val safeSpan = SAFE_DIRECTED_UPPER_BOUND - SAFE_DIRECTED_LOWER_BOUND
        val lower = SAFE_DIRECTED_LOWER_BOUND + REQUIRED_ACQUISITION_METERS / visibleMeters * safeSpan
        val upper = lower + distanceMeters / visibleMeters * safeSpan
        return lower to upper
    }

    fun assessment(
        gates: List<SingleCameraSprintGate>,
        direction: SingleCameraSprintDirection
    ): SingleCameraSprintFramingAssessment? {
        val points = gates.sortedBy { it.distanceMeters }
            .map { direction.directedPosition(it.normalizedX) to it.distanceMeters }
        if (points.size < 2) return null
        val first = points.first()
        val second = points[1]
        val penultimate = points[points.size - 2]
        val last = points.last()
        val firstSpan = second.first - first.first
        val lastSpan = last.first - penultimate.first
        if (firstSpan <= 0.000_001 || lastSpan <= 0.000_001) return null

        val acquisitionScale = (second.second - first.second) / firstSpan
        val exitScale = (last.second - penultimate.second) / lastSpan
        val acquisition = max(0.0, (first.first - SAFE_DIRECTED_LOWER_BOUND) * acquisitionScale)
        val exit = max(0.0, (SAFE_DIRECTED_UPPER_BOUND - last.first) * exitScale)
        if (!acquisition.isFinite() || !exit.isFinite()) return null
        return SingleCameraSprintFramingAssessment(acquisition, exit)
    }

    fun acquisitionTargetX(
        gates: List<SingleCameraSprintGate>,
        direction: SingleCameraSprintDirection
    ): Double {
        val startDirectedX = gates.minByOrNull { it.distanceMeters }
            ?.let { direction.directedPosition(it.normalizedX) } ?: 0.25
        val targetDirectedX = SAFE_DIRECTED_LOWER_BOUND + (startDirectedX - SAFE_DIRECTED_LOWER_BOUND) * 0.55
        return if (direction == SingleCameraSprintDirection.LEFT_TO_RIGHT) targetDirectedX else 1 - targetDirectedX
    }
}

/**
 * One-dimensional projective calibration along the athlete's lane. The user
 * places every gate over a measured cone, which gives exact distance/image
 * correspondences without trusting monocular body-height scale estimates.
 */
class SingleCameraCourseCalibration private constructor(
    val gates: List<SingleCameraSprintGate>,
    val direction: SingleCameraSprintDirection,
    val maxResidualMeters: Double,
    private val projectiveCoefficients: DoubleArray?
) {
    val usesProjectiveMapping: Boolean get() = projectiveCoefficients != null
    val distanceMeters: Double get() = gates.lastOrNull()?.distanceMeters ?: 0.0

    fun directedGatePosition(distanceMeters: Double): Double? =
        gates.firstOrNull { sameDistance(it.distanceMeters, distanceMeters) }
            ?.let { direction.directedPosition(it.normalizedX) }

    fun meters(normalizedX: Double): Double {
        val directedX = direction.directedPosition(normalizedX)
        projectiveCoefficients?.let { coefficients ->
            val value = projectiveValue(directedX, coefficients)
            if (value.isFinite()) return value
        }
        return piecewiseLinearMeters(directedX)
    }

    /** Inverts the lane mapping so acquisition windows are expressed in metres. */
    fun directedPosition(distanceMeters: Double): Double {
        projectiveCoefficients?.let { c ->
            val denominator = distanceMeters * c[2] - c[0]
            if (abs(denominator) > 0.000_001) {
                val directedX = (c[1] - distanceMeters) / denominator
                if (directedX.isFinite()) return directedX
            }
        }
        return piecewiseLinearDirectedPosition(distanceMeters)
    }

    fun normalizedX(distanceMeters: Double): Double {
        val directedX = directedPosition(distanceMeters)
        return if (direction == SingleCameraSprintDirection.LEFT_TO_RIGHT) directedX else 1 - directedX
    }

    private fun points(): List<Pair<Double, Double>> =
        gates.map { direction.directedPosition(it.normalizedX) to it.distanceMeters }

    private fun piecewiseLinearMeters(directedX: Double): Double {
        val points = points()
        val first = points.firstOrNull() ?: return 0.0
        val last = points.last()
        if (directedX <= first.first) return linearValue(directedX, first, points[1])
        if (directedX >= last.first) return linearValue(directedX, points[points.size - 2], last)
        for ((left, right) in points.zipWithNext()) {
            if (directedX <= right.first) return linearValue(directedX, left, right)
        }
        return last.second
    }

    private fun piecewiseLinearDirectedPosition(distanceMeters: Double): Double {
        val points = points()
        val first = points.firstOrNull() ?: return 0.0
        val last = points.last()
        val (left, right) = when {
            distanceMeters <= first.second -> first to points[1]
            distanceMeters >= last.second -> points[points.size - 2] to last
            else -> points.zipWithNext().firstOrNull { distanceMeters <= it.second.second } ?: return last.first
        }
        val distanceSpan = right.second - left.second
        if (abs(distanceSpan) <= 0.000_001) return left.first
        val progress = (distanceMeters - left.second) / distanceSpan
        return left.first + progress * (right.first - left.first)
    }

    companion object {
        fun create(
            gates: List<SingleCameraSprintGate>,
            direction: SingleCameraSprintDirection
        ): SingleCameraCourseCalibration? {
            val ordered = gates.sortedBy { it.distanceMeters }
            if (ordered.size < 2 || ordered.first().distanceMeters != 0.0) return null
            if (!ordered.all { it.normalizedX.isFinite() && it.normalizedX in 0.0..1.0 }) return null
            val validSpacing = ordered.zipWithNext().all { (lhs, rhs) ->
                rhs.distanceMeters > lhs.distanceMeters &&
                    direction.directedPosition(rhs.normalizedX) - direction.directedPosition(lhs.normalizedX) >= 0.025
            }
            if (!validSpacing) return null

            val samples = ordered.map { direction.directedPosition(it.normalizedX) to it.distanceMeters }
            val coefficients = fitProjectiveMapping(samples)
            val residual = coefficients?.let { c ->
                samples.maxOfOrNull { abs(projectiveValue(it.first, c) - it.second) } ?: Double.POSITIVE_INFINITY
            } ?: Double.POSITIVE_INFINITY
            val maximumAllowedResidual = max(0.20, ordered.last().distanceMeters * 0.02)
            val usable = coefficients != null &&
                residual <= maximumAllowedResidual &&
                isMonotonic(coefficients, samples.first().first, samples.last().first)
            return SingleCameraCourseCalibration(
                gates = ordered,
                direction = direction,
                maxResidualMeters = if (usable) residual else 0.0,
                projectiveCoefficients = if (usable) coefficients else null
            )
        }

        private fun linearValue(x: Double, left: Pair<Double, Double>, right: Pair<Double, Double>): Double {
            val span = right.first - left.first
            if (abs(span) <= 0.000_001) return left.second
            val progress = (x - left.first) / span
            return left.second + progress * (right.second - left.second)
        }

        /** Fits distance = (a*x + b) / (c*x + 1) with least squares. */
        private fun fitProjectiveMapping(samples: List<Pair<Double, Double>>): DoubleArray? {
            if (samples.size < 3) return null
            val normal = Array(3) { DoubleArray(3) }
            val rhs = DoubleArray(3)
            for ((x, distance) in samples) {
                val row = doubleArrayOf(x, 1.0, -distance * x)
                for (r in 0 until 3) {
                    rhs[r] += row[r] * distance
                    for (c in 0 until 3) normal[r][c] += row[r] * row[c]
                }
            }
            return solve3x3(normal, rhs)
        }

        private fun solve3x3(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray? {
            val augmented = Array(3) { row -> DoubleArray(4) { col -> if (col < 3) matrix[row][col] else vector[row] } }
            for (pivot in 0 until 3) {
                val bestRow = (pivot until 3).maxByOrNull { abs(augmented[it][pivot]) } ?: return null
                if (abs(augmented[bestRow][pivot]) <= 0.000_000_001) return null
                if (bestRow != pivot) {
                    val tmp = augmented[bestRow]
                    augmented[bestRow] = augmented[pivot]
                    augmented[pivot] = tmp
                }
                val divisor = augmented[pivot][pivot]
                for (col in pivot until 4) augmented[pivot][col] /= divisor
                for (row in 0 until 3) {
                    if (row == pivot) continue
                    val factor = augmented[row][pivot]
                    for (col in pivot until 4) augmented[row][col] -= factor * augmented[pivot][col]
                }
            }
            return DoubleArray(3) { augmented[it][3] }
        }

        private fun projectiveValue(x: Double, c: DoubleArray): Double {
            val denominator = c[2] * x + 1
            if (abs(denominator) <= 0.000_001) return Double.NaN
            return (c[0] * x + c[1]) / denominator
        }

        private fun isMonotonic(c: DoubleArray, lowerBound: Double, upperBound: Double): Boolean {
            var previous = projectiveValue(lowerBound, c)
            if (!previous.isFinite()) return false
            for (step in 1..20) {
                val x = lowerBound + step / 20.0 * (upperBound - lowerBound)
                val value = projectiveValue(x, c)
                if (!value.isFinite() || value <= previous) return false
                previous = value
            }
            return true
        }
    }
}

/**
 * Defines where a new athlete identity may first be acquired. Standing starts
 * use a metre-calibrated region around the start line; flying starts remain
 * upstream of the 0 m timing plane.
 */
object SingleCameraSprintAcquisition {
    fun targetX(
        gates: List<SingleCameraSprintGate>,
        direction: SingleCameraSprintDirection,
        startStyle: SingleCameraSprintStartStyle,
        drill: SingleCameraSprintDrill = SingleCameraSprintDrill.SPRINT
    ): Double {
        if (drill == SingleCameraSprintDrill.PRO_AGILITY) {
            SingleCameraCourseCalibration.create(gates, direction)?.let { return it.normalizedX(4.572) }
        }
        return when (startStyle) {
            SingleCameraSprintStartStyle.STANDING ->
                gates.minByOrNull { it.distanceMeters }?.normalizedX
                    ?: if (direction == SingleCameraSprintDirection.LEFT_TO_RIGHT) 0.14 else 0.86
            SingleCameraSprintStartStyle.FLYING -> SingleCameraSprintFraming.acquisitionTargetX(gates, direction)
        }
    }

    fun initialDirectedRange(
        gates: List<SingleCameraSprintGate>,
        direction: SingleCameraSprintDirection,
        startStyle: SingleCameraSprintStartStyle,
        standingHalfWidthMeters: Double,
        drill: SingleCameraSprintDrill = SingleCameraSprintDrill.SPRINT
    ): ClosedFloatingPointRange<Double>? {
        val calibration = SingleCameraCourseCalibration.create(gates, direction) ?: return null
        return when (startStyle) {
            SingleCameraSprintStartStyle.STANDING -> {
                val halfWidth = max(0.10, standingHalfWidthMeters)
                val origin = drill.startDistance(calibration.distanceMeters)
                val first = calibration.directedPosition(origin - halfWidth)
                val second = calibration.directedPosition(origin + halfWidth)
                val lower = min(first, second).coerceIn(0.0, 1.0)
                val upper = max(first, second).coerceIn(0.0, 1.0)
                if (upper - lower <= 0.000_001) null else lower..upper
            }
            SingleCameraSprintStartStyle.FLYING -> {
                val startX = calibration.directedGatePosition(0.0) ?: return null
                val upper = (startX - 0.000_001).coerceIn(0.0, 1.0)
                if (upper <= 0) null else 0.0..upper
            }
        }
    }
}

data class SingleCameraPoseSample(
    val timestamp: Double,
    val rootX: Double,
    val rootY: Double,
    val confidence: Double
)

data class SingleCameraPositionSample(
    val timestamp: Double,
    val distanceMeters: Double,
    val confidence: Double
)

data class SingleCameraSprintSplit(
    val distanceMeters: Double,
    val crossingTimestamp: Double,
    val elapsedSeconds: Double
)

enum class SingleCameraSprintQuality(val rawValue: String) {
    HIGH("high"),
    USABLE("usable"),
    REVIEW("review");

    companion object {
        fun fromRaw(raw: String?): SingleCameraSprintQuality = entries.firstOrNull { it.rawValue == raw } ?: REVIEW
    }
}

data class SingleCameraSprintResult(
    val totalTimeSeconds: Double,
    val splits: List<SingleCameraSprintSplit>,
    val peakVelocityMetersPerSecond: Double,
    val peakVelocityDistanceMeters: Double,
    val trace: List<SingleCameraPositionSample>,
    val averagePoseConfidence: Double,
    val estimatedPoseFPS: Double,
    val calibrationResidualMeters: Double,
    val quality: SingleCameraSprintQuality,
    val algorithmVersion: String,
    val startStyle: SingleCameraSprintStartStyle,
    val drill: SingleCameraSprintDrill = SingleCameraSprintDrill.SPRINT
) {
    fun markedForPhoneMotionReview(): SingleCameraSprintResult =
        if (quality == SingleCameraSprintQuality.REVIEW) this else copy(quality = SingleCameraSprintQuality.REVIEW)
}

/** One completed effort retained by the open one-phone session. */
data class SingleCameraSprintRunRecord(
    val id: String,
    val runNumber: Int,
    val createdAtMillis: Long,
    val result: SingleCameraSprintResult,
    val gates: List<SingleCameraSprintGate>,
    val direction: SingleCameraSprintDirection,
    val phoneMotionWarning: Boolean
) {
    val distanceMeters: Double
        get() = result.drill.totalDistance(gates.maxOfOrNull { it.distanceMeters } ?: 0.0)
}

/**
 * Debounces motion sensors into one nonfatal warning per armed run. Once raised,
 * the warning remains attached to that run so a temporarily stable phone does
 * not make a potentially shifted gate calibration look trustworthy again.
 */
class SingleCameraPhoneMotionWarningState {
    var consecutiveMotionSamples = 0
        private set
    var didDetectMovement = false
        private set

    /** Returns true only when the warning is raised for the first time. */
    fun ingest(moved: Boolean): Boolean {
        consecutiveMotionSamples = if (moved) {
            min(consecutiveMotionSamples + 1, REQUIRED_CONSECUTIVE_MOTION_SAMPLES)
        } else {
            0
        }
        if (didDetectMovement || consecutiveMotionSamples < REQUIRED_CONSECUTIVE_MOTION_SAMPLES) return false
        didDetectMovement = true
        return true
    }

    fun endSampleSequence() {
        consecutiveMotionSamples = 0
    }

    fun reset() {
        consecutiveMotionSamples = 0
        didDetectMovement = false
    }

    companion object {
        const val REQUIRED_CONSECUTIVE_MOTION_SAMPLES = 3
    }
}

sealed interface SingleCameraSprintTimingEvent {
    data object AthleteAcquired : SingleCameraSprintTimingEvent
    data object Armed : SingleCameraSprintTimingEvent
    data class Started(val timestamp: Double) : SingleCameraSprintTimingEvent
    data class GateCrossed(val split: SingleCameraSprintSplit) : SingleCameraSprintTimingEvent
    data class Completed(val result: SingleCameraSprintResult) : SingleCameraSprintTimingEvent
    data class Invalidated(val reason: SingleCameraInvalidReason) : SingleCameraSprintTimingEvent
}

data class SingleCameraSprintTimingParameters(
    val minimumPoseConfidence: Double = 0.35,
    val standingStartZoneHalfWidthMeters: Double = 1.0,
    val settleDurationSeconds: Double = 0.60,
    /**
     * The settle window keeps slightly more than [settleDurationSeconds] of
     * samples so a uniform pose-rate grid can still reach the 95 % arming gate.
     */
    val settleWindowSlackSeconds: Double = 0.10,
    val maximumStandingSettledRangeMeters: Double = 0.15,
    val minimumStandingStartNoiseBandMeters: Double = 0.03,
    val minimumStandingForwardDisplacementMeters: Double = 0.06,
    val minimumStandingForwardVelocityMetersPerSecond: Double = 0.50,
    val movementConfirmationSamples: Int = 2,
    /** Three directional samples confirm the flying runner before the 0 m crossing. */
    val flyingAcquisitionSamples: Int = 3,
    val minimumFlyingAcquisitionDisplacement: Double = 0.006,
    val minimumFlyingAcquisitionVelocity: Double = 0.045,
    val maximumPoseGapSeconds: Double = 0.30,
    val maximumNormalizedJump: Double = 0.20,
    val velocityWindowSeconds: Double = 0.16
)

/**
 * Pure timing state machine. Times are seconds on the camera timestamp domain
 * (Android elapsed-realtime nanos converted to seconds by the caller).
 */
class SingleCameraSprintTimingEngine(
    val parameters: SingleCameraSprintTimingParameters = SingleCameraSprintTimingParameters()
) {
    var phase: SingleCameraSprintPhase = SingleCameraSprintPhase.Calibrating
        private set
    var splits: List<SingleCameraSprintSplit> = emptyList()
        private set
    var liveElapsedSeconds = 0.0
        private set
    var liveVelocityMetersPerSecond = 0.0
        private set
    var result: SingleCameraSprintResult? = null
        private set
    var startStyle = SingleCameraSprintStartStyle.STANDING
        private set
    var drill = SingleCameraSprintDrill.SPRINT
        private set

    private var calibration: SingleCameraCourseCalibration? = null
    private val settlingSamples = ArrayList<SingleCameraPoseSample>()
    private val armedSamples = ArrayList<SingleCameraPoseSample>()
    private val flyingAcquisitionSamples = ArrayList<SingleCameraPoseSample>()
    private val positionTrace = ArrayList<SingleCameraPositionSample>()
    private var lastAcceptedPose: SingleCameraPoseSample? = null
    private var lastInputTimestamp: Double? = null
    private var baselineDistanceMeters: Double? = null
    private var startNoiseBandMeters = 0.0
    private var movementConfirmationCount = 0
    private var startTimestamp: Double? = null
    private var nextGateIndex = 1
    private var returnExcursionReached = false
    private var confidenceTotal = 0.0
    private var confidenceSampleCount = 0

    fun arm(
        gates: List<SingleCameraSprintGate>,
        direction: SingleCameraSprintDirection,
        startStyle: SingleCameraSprintStartStyle = SingleCameraSprintStartStyle.STANDING,
        drill: SingleCameraSprintDrill = SingleCameraSprintDrill.SPRINT
    ): Boolean {
        val calibration = SingleCameraCourseCalibration.create(gates, direction) ?: run {
            phase = SingleCameraSprintPhase.Invalid(SingleCameraInvalidReason.GATE_LAYOUT)
            return false
        }
        if (startStyle == SingleCameraSprintStartStyle.FLYING) {
            val framing = SingleCameraSprintFraming.assessment(gates, direction)
            if (framing == null || !framing.isReady) {
                phase = SingleCameraSprintPhase.Invalid(SingleCameraInvalidReason.FLYING_FRAMING)
                return false
            }
        }
        if (drill == SingleCameraSprintDrill.PRO_AGILITY &&
            (startStyle != SingleCameraSprintStartStyle.STANDING || abs(calibration.distanceMeters - 9.144) >= 0.001)
        ) {
            phase = SingleCameraSprintPhase.Invalid(SingleCameraInvalidReason.PRO_AGILITY_LAYOUT)
            return false
        }
        clearRunState()
        this.calibration = calibration
        this.drill = drill
        this.startStyle = startStyle
        phase = SingleCameraSprintPhase.Acquiring
        nextGateIndex = if (startStyle == SingleCameraSprintStartStyle.FLYING) 0 else 1
        return true
    }

    fun reset() {
        clearRunState()
        calibration = null
        phase = SingleCameraSprintPhase.Calibrating
        startStyle = SingleCameraSprintStartStyle.STANDING
        drill = SingleCameraSprintDrill.SPRINT
        nextGateIndex = 1
    }

    private fun clearRunState() {
        returnExcursionReached = false
        splits = emptyList()
        liveElapsedSeconds = 0.0
        liveVelocityMetersPerSecond = 0.0
        result = null
        settlingSamples.clear()
        armedSamples.clear()
        flyingAcquisitionSamples.clear()
        positionTrace.clear()
        lastAcceptedPose = null
        lastInputTimestamp = null
        baselineDistanceMeters = null
        startNoiseBandMeters = 0.0
        movementConfirmationCount = 0
        startTimestamp = null
        confidenceTotal = 0.0
        confidenceSampleCount = 0
    }

    fun ingest(sample: SingleCameraPoseSample): List<SingleCameraSprintTimingEvent> {
        if (!sample.timestamp.isFinite() || !sample.rootX.isFinite() || !sample.rootY.isFinite() ||
            !sample.confidence.isFinite()
        ) return emptyList()
        lastInputTimestamp?.let { if (sample.timestamp <= it) return emptyList() }
        lastInputTimestamp = sample.timestamp

        if (sample.confidence < parameters.minimumPoseConfidence) {
            return ingestMissingPose(sample.timestamp)
        }

        var priorAcceptedPose = lastAcceptedPose
        if (priorAcceptedPose != null &&
            sample.timestamp - priorAcceptedPose.timestamp > parameters.maximumPoseGapSeconds
        ) {
            if (phase == SingleCameraSprintPhase.Running) {
                return invalidate(SingleCameraInvalidReason.TRACKING_LOST)
            }
            if (isPreStartPhase(phase)) {
                // Neither a quiet standing hold nor a flying identity can
                // bridge missing camera evidence. Require fresh acquisition.
                resetAcquisition()
                priorAcceptedPose = null
            }
        }

        lastAcceptedPose = sample
        confidenceTotal += sample.confidence
        confidenceSampleCount += 1

        val calibration = calibration ?: return emptyList()
        val directedX = calibration.direction.directedPosition(sample.rootX)
        val startDistance = drill.startDistance(calibration.distanceMeters)
        val startX = calibration.directedPosition(startDistance)
        val distanceMeters = calibration.meters(sample.rootX)

        return when (phase) {
            SingleCameraSprintPhase.Calibrating,
            SingleCameraSprintPhase.Completed,
            is SingleCameraSprintPhase.Invalid -> emptyList()

            SingleCameraSprintPhase.Acquiring -> when (startStyle) {
                SingleCameraSprintStartStyle.STANDING -> {
                    if (abs(distanceMeters - startDistance) > parameters.standingStartZoneHalfWidthMeters) {
                        emptyList()
                    } else {
                        settlingSamples.clear()
                        settlingSamples.add(sample)
                        confidenceTotal = sample.confidence
                        confidenceSampleCount = 1
                        phase = SingleCameraSprintPhase.Settling(0.0)
                        listOf(SingleCameraSprintTimingEvent.AthleteAcquired)
                    }
                }
                SingleCameraSprintStartStyle.FLYING -> {
                    if (directedX >= startX) {
                        emptyList()
                    } else {
                        flyingAcquisitionSamples.clear()
                        flyingAcquisitionSamples.add(sample)
                        confidenceTotal = sample.confidence
                        confidenceSampleCount = 1
                        phase = SingleCameraSprintPhase.Locking(1.0 / max(parameters.flyingAcquisitionSamples, 1))
                        listOf(SingleCameraSprintTimingEvent.AthleteAcquired)
                    }
                }
            }

            is SingleCameraSprintPhase.Locking -> ingestLocking(sample, directedX, startX, calibration)

            is SingleCameraSprintPhase.Settling -> ingestSettling(sample, distanceMeters, startDistance, calibration)

            SingleCameraSprintPhase.Armed -> when (startStyle) {
                SingleCameraSprintStartStyle.STANDING ->
                    ingestStandingStart(sample, priorAcceptedPose, distanceMeters, calibration)
                SingleCameraSprintStartStyle.FLYING ->
                    ingestFlyingStart(sample, priorAcceptedPose, directedX, startX, calibration)
            }

            SingleCameraSprintPhase.Running -> {
                val start = startTimestamp
                    ?: return invalidate(SingleCameraInvalidReason.START_TIMESTAMP_UNAVAILABLE)
                liveElapsedSeconds = max(liveElapsedSeconds, max(0.0, sample.timestamp - start))
                val prior = priorAcceptedPose ?: return emptyList()
                val previousDirectedX = calibration.direction.directedPosition(prior.rootX)
                if (abs(directedX - previousDirectedX) > parameters.maximumNormalizedJump) {
                    return invalidate(SingleCameraInvalidReason.POSITION_JUMPED)
                }
                appendPosition(sample, calibration)
                recordGateCrossings(prior, sample, start, calibration)
            }
        }
    }

    fun ingestMissingPose(timestamp: Double): List<SingleCameraSprintTimingEvent> {
        if (!timestamp.isFinite()) return emptyList()
        lastInputTimestamp = lastInputTimestamp?.let { max(it, timestamp) } ?: timestamp
        val start = startTimestamp
        if (phase == SingleCameraSprintPhase.Running && start != null) {
            liveElapsedSeconds = max(liveElapsedSeconds, max(0.0, timestamp - start))
        }
        val last = lastAcceptedPose ?: return emptyList()
        if (timestamp - last.timestamp <= parameters.maximumPoseGapSeconds) return emptyList()

        if (isPreStartPhase(phase)) {
            // Rebuild the still baseline/identity after a gap; missing frames
            // do not establish that the athlete stayed still.
            resetAcquisition()
            return emptyList()
        }
        if (phase == SingleCameraSprintPhase.Running) {
            return invalidate(SingleCameraInvalidReason.TRACKING_LOST)
        }
        return emptyList()
    }

    fun invalidateExternally(reason: SingleCameraInvalidReason): List<SingleCameraSprintTimingEvent> {
        if (phase == SingleCameraSprintPhase.Calibrating || phase == SingleCameraSprintPhase.Completed) {
            return emptyList()
        }
        return invalidate(reason)
    }

    private fun isPreStartPhase(phase: SingleCameraSprintPhase): Boolean =
        phase == SingleCameraSprintPhase.Acquiring || phase is SingleCameraSprintPhase.Locking ||
            phase is SingleCameraSprintPhase.Settling || phase == SingleCameraSprintPhase.Armed

    private fun resetAcquisition() {
        flyingAcquisitionSamples.clear()
        settlingSamples.clear()
        armedSamples.clear()
        baselineDistanceMeters = null
        movementConfirmationCount = 0
        lastAcceptedPose = null
        phase = SingleCameraSprintPhase.Acquiring
    }

    private fun ingestLocking(
        sample: SingleCameraPoseSample,
        directedX: Double,
        startX: Double,
        calibration: SingleCameraCourseCalibration
    ): List<SingleCameraSprintTimingEvent> {
        if (startStyle != SingleCameraSprintStartStyle.FLYING) {
            return invalidate(SingleCameraInvalidReason.FLYING_LOCK_UNAVAILABLE)
        }
        if (directedX >= startX) {
            return invalidate(SingleCameraInvalidReason.REACHED_START_BEFORE_READY)
        }
        flyingAcquisitionSamples.add(sample)
        val requiredCount = max(parameters.flyingAcquisitionSamples, 2)
        while (flyingAcquisitionSamples.size > requiredCount) flyingAcquisitionSamples.removeAt(0)
        phase = SingleCameraSprintPhase.Locking(min(flyingAcquisitionSamples.size.toDouble() / requiredCount, 1.0))
        if (flyingAcquisitionSamples.size != requiredCount) return emptyList()

        val first = flyingAcquisitionSamples.first()
        val last = flyingAcquisitionSamples.last()
        val firstX = calibration.direction.directedPosition(first.rootX)
        val lastX = calibration.direction.directedPosition(last.rootX)
        val duration = last.timestamp - first.timestamp
        val displacement = lastX - firstX
        val velocity = if (duration > 0) displacement / duration else 0.0
        val directedPositions = flyingAcquisitionSamples.map { calibration.direction.directedPosition(it.rootX) }
        val reversalCount = directedPositions.zipWithNext().count { (previous, current) -> current < previous - 0.003 }
        if (displacement < parameters.minimumFlyingAcquisitionDisplacement ||
            velocity < parameters.minimumFlyingAcquisitionVelocity ||
            reversalCount > 1
        ) return emptyList()

        phase = SingleCameraSprintPhase.Armed
        nextGateIndex = 0
        return listOf(SingleCameraSprintTimingEvent.Armed)
    }

    private fun ingestSettling(
        sample: SingleCameraPoseSample,
        distanceMeters: Double,
        startDistance: Double,
        calibration: SingleCameraCourseCalibration
    ): List<SingleCameraSprintTimingEvent> {
        if (startStyle != SingleCameraSprintStartStyle.STANDING) {
            return invalidate(SingleCameraInvalidReason.STANDING_BASELINE_UNAVAILABLE)
        }
        if (abs(distanceMeters - startDistance) > parameters.standingStartZoneHalfWidthMeters) {
            settlingSamples.clear()
            phase = SingleCameraSprintPhase.Acquiring
            return emptyList()
        }
        settlingSamples.add(sample)
        trim(settlingSamples, parameters.settleDurationSeconds + parameters.settleWindowSlackSeconds, sample.timestamp)

        val duration = (settlingSamples.lastOrNull()?.timestamp ?: sample.timestamp) -
            (settlingSamples.firstOrNull()?.timestamp ?: sample.timestamp)
        val distances = settlingSamples.map { calibration.meters(it.rootX) }
        val rangeMeters = (distances.maxOrNull() ?: distanceMeters) - (distances.minOrNull() ?: distanceMeters)
        val progress = (duration / parameters.settleDurationSeconds).coerceIn(0.0, 1.0)
        phase = SingleCameraSprintPhase.Settling(progress)

        if (duration < parameters.settleDurationSeconds * 0.95 ||
            rangeMeters > parameters.maximumStandingSettledRangeMeters
        ) return emptyList()

        val baseline = median(distances)
        val deviations = distances.map { abs(it - baseline) }
        baselineDistanceMeters = baseline
        startNoiseBandMeters = max(parameters.minimumStandingStartNoiseBandMeters, median(deviations) * 4)
        armedSamples.clear()
        armedSamples.addAll(settlingSamples)
        movementConfirmationCount = 0
        phase = SingleCameraSprintPhase.Armed
        return listOf(SingleCameraSprintTimingEvent.Armed)
    }

    private fun ingestStandingStart(
        sample: SingleCameraPoseSample,
        priorAcceptedPose: SingleCameraPoseSample?,
        distanceMeters: Double,
        calibration: SingleCameraCourseCalibration
    ): List<SingleCameraSprintTimingEvent> {
        val baseline = baselineDistanceMeters
            ?: return invalidate(SingleCameraInvalidReason.STANDING_BASELINE_UNAVAILABLE)
        armedSamples.add(sample)
        trim(armedSamples, 1.25, sample.timestamp)

        val priorDistance = priorAcceptedPose?.let { calibration.meters(it.rootX) } ?: distanceMeters
        val elapsed = sample.timestamp - (priorAcceptedPose?.timestamp ?: sample.timestamp)
        val forwardDisplacement = distanceMeters - baseline
        val forwardVelocity = if (elapsed > 0) (distanceMeters - priorDistance) / elapsed else 0.0

        if (forwardDisplacement >= max(parameters.minimumStandingForwardDisplacementMeters, startNoiseBandMeters * 2) &&
            forwardVelocity >= parameters.minimumStandingForwardVelocityMetersPerSecond
        ) {
            movementConfirmationCount += 1
        } else if (forwardVelocity <= 0 || forwardDisplacement < startNoiseBandMeters) {
            movementConfirmationCount = 0
        }
        if (movementConfirmationCount < parameters.movementConfirmationSamples) return emptyList()

        val onsetDistance = baseline + startNoiseBandMeters
        val onsetTimestamp = interpolatedThresholdTimestamp(
            armedSamples,
            { calibration.meters(it.rootX) },
            onsetDistance
        ) ?: sample.timestamp
        startTimestamp = onsetTimestamp
        liveElapsedSeconds = max(0.0, sample.timestamp - onsetTimestamp)
        phase = SingleCameraSprintPhase.Running
        positionTrace.clear()
        armedSamples.filter { it.timestamp >= onsetTimestamp }.mapTo(positionTrace) {
            SingleCameraPositionSample(it.timestamp, calibration.meters(it.rootX), it.confidence)
        }
        return listOf(SingleCameraSprintTimingEvent.Started(onsetTimestamp))
    }

    private fun ingestFlyingStart(
        sample: SingleCameraPoseSample,
        priorAcceptedPose: SingleCameraPoseSample?,
        directedX: Double,
        startX: Double,
        calibration: SingleCameraCourseCalibration
    ): List<SingleCameraSprintTimingEvent> {
        val prior = priorAcceptedPose ?: return emptyList()
        val previousDirectedX = calibration.direction.directedPosition(prior.rootX)
        if (abs(directedX - previousDirectedX) > parameters.maximumNormalizedJump) {
            return invalidate(SingleCameraInvalidReason.POSITION_JUMPED)
        }
        if (directedX < startX) return emptyList()
        if (previousDirectedX >= startX) return invalidate(SingleCameraInvalidReason.START_NOT_BRACKETED)

        val crossingTimestamp = interpolatedCrossingTimestamp(prior, sample, previousDirectedX, directedX, startX)
        startTimestamp = crossingTimestamp
        liveElapsedSeconds = max(0.0, sample.timestamp - crossingTimestamp)
        nextGateIndex = 1
        phase = SingleCameraSprintPhase.Running
        positionTrace.clear()
        positionTrace.add(SingleCameraPositionSample(crossingTimestamp, 0.0, min(prior.confidence, sample.confidence)))
        if (sample.timestamp > crossingTimestamp) appendPosition(sample, calibration)

        val events = mutableListOf<SingleCameraSprintTimingEvent>(
            SingleCameraSprintTimingEvent.Started(crossingTimestamp)
        )
        events += recordGateCrossings(prior, sample, crossingTimestamp, calibration)
        return events
    }

    private fun appendPosition(sample: SingleCameraPoseSample, calibration: SingleCameraCourseCalibration) {
        positionTrace.add(SingleCameraPositionSample(sample.timestamp, calibration.meters(sample.rootX), sample.confidence))
        val velocity = linearRegressionVelocity(positionTrace, sample.timestamp, parameters.velocityWindowSeconds)
        liveVelocityMetersPerSecond = if (drill == SingleCameraSprintDrill.SPRINT) velocity else abs(velocity)
    }

    private fun recordGateCrossings(
        previous: SingleCameraPoseSample,
        current: SingleCameraPoseSample,
        startTimestamp: Double,
        calibration: SingleCameraCourseCalibration
    ): List<SingleCameraSprintTimingEvent> {
        if (drill != SingleCameraSprintDrill.SPRINT) {
            return recordReturnCrossing(previous, current, startTimestamp, calibration)
        }
        val previousDirectedX = calibration.direction.directedPosition(previous.rootX)
        val currentDirectedX = calibration.direction.directedPosition(current.rootX)
        val events = mutableListOf<SingleCameraSprintTimingEvent>()

        while (nextGateIndex < calibration.gates.size) {
            val gate = calibration.gates[nextGateIndex]
            val gateX = calibration.direction.directedPosition(gate.normalizedX)
            if (!(previousDirectedX < gateX && currentDirectedX >= gateX)) break
            val crossing = interpolatedCrossingTimestamp(previous, current, previousDirectedX, currentDirectedX, gateX)
            val split = SingleCameraSprintSplit(gate.distanceMeters, crossing, max(0.0, crossing - startTimestamp))
            splits = splits + split
            events += SingleCameraSprintTimingEvent.GateCrossed(split)
            nextGateIndex += 1
        }

        val finalSplit = splits.lastOrNull()
        if (nextGateIndex == calibration.gates.size && finalSplit != null) {
            val completed = makeResult(finalSplit, calibration)
            result = completed
            liveElapsedSeconds = finalSplit.elapsedSeconds
            phase = SingleCameraSprintPhase.Completed
            events += SingleCameraSprintTimingEvent.Completed(completed)
        }
        return events
    }

    /**
     * Centre crossings are timed; legal touches remain a video-review
     * requirement. An excursion on each side prevents jitter at the starting
     * line from masquerading as a completed return. No human-speed cutoff.
     */
    private fun recordReturnCrossing(
        previous: SingleCameraPoseSample,
        current: SingleCameraPoseSample,
        startTimestamp: Double,
        calibration: SingleCameraCourseCalibration
    ): List<SingleCameraSprintTimingEvent> {
        val span = calibration.distanceMeters
        val line = drill.startDistance(span)
        val before = calibration.meters(previous.rootX)
        val after = calibration.meters(current.rootX)
        val firstReturn = splits.isEmpty()
        val returningLeft = drill == SingleCameraSprintDrill.SHUTTLE || firstReturn
        returnExcursionReached = if (returningLeft) {
            returnExcursionReached || max(before, after) >= line + (span - line) / 2
        } else {
            returnExcursionReached || min(before, after) <= line / 2
        }
        val crosses = if (returningLeft) before > line && after <= line else before < line && after >= line
        if (!returnExcursionReached || !crosses) return emptyList()
        val timestamp = interpolatedCrossingTimestamp(
            previous,
            current,
            calibration.direction.directedPosition(previous.rootX),
            calibration.direction.directedPosition(current.rootX),
            calibration.directedPosition(line)
        )
        val finishes = drill == SingleCameraSprintDrill.SHUTTLE || !firstReturn
        val split = SingleCameraSprintSplit(
            distanceMeters = if (finishes) span * 2 else span,
            crossingTimestamp = timestamp,
            elapsedSeconds = timestamp - startTimestamp
        )
        splits = splits + split
        returnExcursionReached = false
        if (!finishes) return listOf(SingleCameraSprintTimingEvent.GateCrossed(split))
        val completed = makeResult(split, calibration)
        result = completed
        phase = SingleCameraSprintPhase.Completed
        liveElapsedSeconds = split.elapsedSeconds
        return listOf(SingleCameraSprintTimingEvent.GateCrossed(split), SingleCameraSprintTimingEvent.Completed(completed))
    }

    private fun invalidate(reason: SingleCameraInvalidReason): List<SingleCameraSprintTimingEvent> {
        phase = SingleCameraSprintPhase.Invalid(reason)
        return listOf(SingleCameraSprintTimingEvent.Invalidated(reason))
    }

    private fun makeResult(
        finalSplit: SingleCameraSprintSplit,
        calibration: SingleCameraCourseCalibration
    ): SingleCameraSprintResult {
        val trace = positionTrace.toList()
        val velocityTrace = trace.map { sample ->
            val velocity = linearRegressionVelocity(trace, sample.timestamp, parameters.velocityWindowSeconds)
            sample to if (drill.requiresTurnReview) abs(velocity) else velocity
        }
        val peak = velocityTrace.maxByOrNull { it.second }
        val duration = (trace.lastOrNull()?.timestamp ?: 0.0) - (trace.firstOrNull()?.timestamp ?: 0.0)
        val estimatedFps = if (duration > 0) max(trace.size - 1, 0) / duration else 0.0
        val averageConfidence = if (confidenceSampleCount > 0) confidenceTotal / confidenceSampleCount else 0.0
        val quality = when {
            drill.requiresTurnReview -> SingleCameraSprintQuality.REVIEW
            estimatedFps >= 45 && averageConfidence >= 0.70 && calibration.maxResidualMeters <= 0.15 ->
                SingleCameraSprintQuality.HIGH
            estimatedFps >= 24 && averageConfidence >= 0.50 && calibration.maxResidualMeters <= 0.40 ->
                SingleCameraSprintQuality.USABLE
            else -> SingleCameraSprintQuality.REVIEW
        }
        return SingleCameraSprintResult(
            totalTimeSeconds = finalSplit.elapsedSeconds,
            splits = splits,
            peakVelocityMetersPerSecond = max(0.0, peak?.second ?: 0.0),
            peakVelocityDistanceMeters = peak?.first?.distanceMeters ?: 0.0,
            trace = trace,
            averagePoseConfidence = averageConfidence,
            estimatedPoseFPS = estimatedFps,
            calibrationResidualMeters = calibration.maxResidualMeters,
            quality = quality,
            algorithmVersion = "single-camera-v4-mlkit-2d-${startStyle.rawValue}-${drill.rawValue}",
            startStyle = startStyle,
            drill = drill
        )
    }

    companion object {
        private fun trim(samples: MutableList<SingleCameraPoseSample>, keepingSeconds: Double, now: Double) {
            val cutoff = now - keepingSeconds
            samples.removeAll { it.timestamp < cutoff }
        }

        internal fun interpolatedCrossingTimestamp(
            previous: SingleCameraPoseSample,
            current: SingleCameraPoseSample,
            previousDirectedX: Double,
            currentDirectedX: Double,
            threshold: Double
        ): Double {
            val span = currentDirectedX - previousDirectedX
            if (abs(span) <= 0.000_000_1) return current.timestamp
            val alpha = ((threshold - previousDirectedX) / span).coerceIn(0.0, 1.0)
            return previous.timestamp + alpha * (current.timestamp - previous.timestamp)
        }

        /**
         * Finds the most recent below-to-above threshold transition, so an
         * earlier unconfirmed twitch does not become the start onset.
         */
        private fun interpolatedThresholdTimestamp(
            samples: List<SingleCameraPoseSample>,
            position: (SingleCameraPoseSample) -> Double,
            threshold: Double
        ): Double? {
            if (samples.size < 2) return null
            for (index in samples.lastIndex downTo 1) {
                val previous = samples[index - 1]
                val current = samples[index]
                val previousX = position(previous)
                val currentX = position(current)
                if (previousX < threshold && currentX >= threshold) {
                    return interpolatedCrossingTimestamp(previous, current, previousX, currentX, threshold)
                }
            }
            return null
        }

        private fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
        }

        private fun linearRegressionVelocity(
            samples: List<SingleCameraPositionSample>,
            endTimestamp: Double,
            windowSeconds: Double
        ): Double {
            val window = samples.filter { it.timestamp <= endTimestamp && it.timestamp >= endTimestamp - windowSeconds }
            if (window.size < 2) return 0.0
            val meanTime = window.sumOf { it.timestamp } / window.size
            val meanDistance = window.sumOf { it.distanceMeters } / window.size
            var numerator = 0.0
            var denominator = 0.0
            for (sample in window) {
                val dt = sample.timestamp - meanTime
                numerator += dt * (sample.distanceMeters - meanDistance)
                denominator += dt * dt
            }
            return if (denominator > 0) numerator / denominator else 0.0
        }
    }
}

/**
 * Explains the engine's result quality. High-quality results show nothing;
 * the other tiers name the measurement that held them back, using the same
 * thresholds the engine applies.
 */
object SingleCameraSprintResultPresentation {
    enum class NoteReason {
        REVIEW_LINE_TOUCHES,
        USABLE_LOWER_TRACKING_RATE,
        USABLE_LOWER_POSE_CONFIDENCE,
        USABLE_LOOSE_CALIBRATION,
        USABLE_CHECK_SETUP,
        REVIEW_PHONE_MOVED,
        REVIEW_LOW_TRACKING_RATE,
        REVIEW_LOW_POSE_CONFIDENCE,
        REVIEW_CHECK_CALIBRATION
    }

    data class QualityNote(val reason: NoteReason, val needsReview: Boolean)

    fun qualityNote(
        quality: SingleCameraSprintQuality,
        phoneMoved: Boolean,
        averagePoseConfidence: Double,
        estimatedPoseFPS: Double,
        calibrationResidualMeters: Double = 0.0,
        drill: SingleCameraSprintDrill = SingleCameraSprintDrill.SPRINT
    ): QualityNote? {
        if (drill.requiresTurnReview) return QualityNote(NoteReason.REVIEW_LINE_TOUCHES, needsReview = true)
        return when (quality) {
            SingleCameraSprintQuality.HIGH -> null
            SingleCameraSprintQuality.USABLE -> QualityNote(
                when {
                    estimatedPoseFPS < 45 -> NoteReason.USABLE_LOWER_TRACKING_RATE
                    averagePoseConfidence < 0.70 -> NoteReason.USABLE_LOWER_POSE_CONFIDENCE
                    calibrationResidualMeters > 0.15 -> NoteReason.USABLE_LOOSE_CALIBRATION
                    else -> NoteReason.USABLE_CHECK_SETUP
                },
                needsReview = false
            )
            SingleCameraSprintQuality.REVIEW -> QualityNote(
                when {
                    phoneMoved -> NoteReason.REVIEW_PHONE_MOVED
                    estimatedPoseFPS < 24 -> NoteReason.REVIEW_LOW_TRACKING_RATE
                    averagePoseConfidence < 0.50 -> NoteReason.REVIEW_LOW_POSE_CONFIDENCE
                    else -> NoteReason.REVIEW_CHECK_CALIBRATION
                },
                needsReview = true
            )
        }
    }
}
