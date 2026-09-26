package com.trackspeed.android.onephone

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Port of iOS `SingleCameraBodyJointName`; joint set shared with the pose overlay. */
enum class SingleCameraBodyJointName {
    ROOT,
    NECK,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_WRIST,
    RIGHT_WRIST,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE
}

data class SingleCameraPoint(val x: Double, val y: Double)

/** Normalized rectangle in the displayed frame (y grows downward). */
data class SingleCameraRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height

    fun intersectionOverUnion(other: SingleCameraRect): Double {
        val left = max(x, other.x)
        val top = max(y, other.y)
        val right = min(maxX, other.maxX)
        val bottom = min(maxY, other.maxY)
        if (right <= left || bottom <= top) return 0.0
        val intersection = (right - left) * (bottom - top)
        val union = width * height + other.width * other.height - intersection
        return if (union > 0) intersection / union else 0.0
    }
}

data class SingleCameraBodyJoint(
    val name: SingleCameraBodyJointName,
    val location: SingleCameraPoint,
    val confidence: Double
)

data class SingleCameraBodyObservation(
    val timestamp: Double,
    val root: SingleCameraPoint,
    val confidence: Double,
    val boundingBox: SingleCameraRect,
    val joints: Map<SingleCameraBodyJointName, SingleCameraBodyJoint>
) {
    companion object {
        /**
         * Builds an observation from normalized joints. The root is the pose
         * root when confident, otherwise the hip midpoint; at least five
         * confident joints are required (matches iOS).
         */
        fun fromJoints(
            joints: Map<SingleCameraBodyJointName, SingleCameraBodyJoint>,
            timestamp: Double
        ): SingleCameraBodyObservation? {
            val resolved = joints.toMutableMap()
            val existingRoot = resolved[SingleCameraBodyJointName.ROOT]
            val rootJoint = if (existingRoot != null && existingRoot.confidence >= 0.30) {
                existingRoot
            } else {
                val leftHip = resolved[SingleCameraBodyJointName.LEFT_HIP] ?: return null
                val rightHip = resolved[SingleCameraBodyJointName.RIGHT_HIP] ?: return null
                SingleCameraBodyJoint(
                    name = SingleCameraBodyJointName.ROOT,
                    location = SingleCameraPoint(
                        (leftHip.location.x + rightHip.location.x) / 2,
                        (leftHip.location.y + rightHip.location.y) / 2
                    ),
                    confidence = min(leftHip.confidence, rightHip.confidence)
                )
            }
            resolved[SingleCameraBodyJointName.ROOT] = rootJoint
            val visible = resolved.values.filter { it.confidence >= 0.20 }
            if (visible.size < 5) return null
            val minX = visible.minOf { it.location.x }
            val maxX = visible.maxOf { it.location.x }
            val minY = visible.minOf { it.location.y }
            val maxY = visible.maxOf { it.location.y }
            return SingleCameraBodyObservation(
                timestamp = timestamp,
                root = rootJoint.location,
                confidence = rootJoint.confidence,
                boundingBox = SingleCameraRect(minX, minY, max(0.001, maxX - minX), max(0.001, maxY - minY)),
                joints = resolved
            )
        }
    }
}

/**
 * Associates per-frame poses with one athlete. Once acquired, the lock follows
 * the predicted pelvis position and body scale instead of selecting whichever
 * person happens to be nearest the start gate.
 */
class SingleCameraAthleteTracker {
    var lockedObservation: SingleCameraBodyObservation? = null
        private set
    private var velocityX = 0.0
    private var velocityY = 0.0
    private var lastTimestamp: Double? = null

    fun reset() {
        lockedObservation = null
        velocityX = 0.0
        velocityY = 0.0
        lastTimestamp = null
    }

    fun select(
        candidates: List<SingleCameraBodyObservation>,
        targetStartX: Double,
        timestamp: Double,
        direction: SingleCameraSprintDirection = SingleCameraSprintDirection.LEFT_TO_RIGHT,
        initialDirectedXRange: ClosedFloatingPointRange<Double>? = null
    ): SingleCameraBodyObservation? {
        val locked = lockedObservation
        val last = lastTimestamp
        if (locked == null || last == null) {
            val eligible = candidates.filter { observation ->
                initialDirectedXRange == null ||
                    direction.directedPosition(observation.root.x) in initialDirectedXRange
            }
            val acquired = eligible.minByOrNull { initialScore(it, targetStartX) } ?: return null
            lock(acquired, timestamp)
            return acquired
        }

        val elapsed = max(0.0, timestamp - last)
        if (candidates.isEmpty()) {
            if (elapsed > 0.65) reset()
            return null
        }
        if (elapsed > 0.65) {
            reset()
            return select(candidates, targetStartX, timestamp, direction, initialDirectedXRange)
        }

        val predictionWindow = min(elapsed, 0.12)
        val predicted = SingleCameraPoint(
            (locked.root.x + velocityX * predictionWindow).coerceIn(0.0, 1.0),
            (locked.root.y + velocityY * predictionWindow).coerceIn(0.0, 1.0)
        )
        val best = candidates.minByOrNull { continuationScore(it, predicted, locked) } ?: return null

        val rootDistance = sqrt(squaredDistance(best.root, predicted))
        val maximumRootDistance = min(0.27, max(0.12, locked.boundingBox.width * 2.2 + elapsed * 0.65))
        val heightRatio = best.boundingBox.height / max(locked.boundingBox.height, 0.001)
        if (rootDistance > maximumRootDistance || heightRatio !in 0.40..2.50) return null

        val delta = max(timestamp - last, 1.0 / 120.0)
        val measuredX = (best.root.x - locked.root.x) / delta
        val measuredY = (best.root.y - locked.root.y) / delta
        velocityX = (velocityX * 0.45 + measuredX * 0.55).coerceIn(-3.0, 3.0)
        velocityY = (velocityY * 0.45 + measuredY * 0.55).coerceIn(-3.0, 3.0)
        lock(best, timestamp)
        return best
    }

    private fun lock(observation: SingleCameraBodyObservation, timestamp: Double) {
        lockedObservation = observation
        lastTimestamp = timestamp
    }

    private fun initialScore(observation: SingleCameraBodyObservation, targetStartX: Double): Double =
        squaredDistance(observation.root, SingleCameraPoint(targetStartX, observation.root.y)) -
            observation.confidence * 0.002

    private fun continuationScore(
        observation: SingleCameraBodyObservation,
        predicted: SingleCameraPoint,
        previous: SingleCameraBodyObservation
    ): Double {
        val overlapPenalty = (1 - observation.boundingBox.intersectionOverUnion(previous.boundingBox)) * 0.006
        val previousHeight = max(previous.boundingBox.height, 0.001)
        val scalePenalty = abs(ln(max(observation.boundingBox.height, 0.001) / previousHeight)) * 0.010
        return squaredDistance(observation.root, predicted) + overlapPenalty + scalePenalty -
            observation.confidence * 0.002
    }

    private fun squaredDistance(lhs: SingleCameraPoint, rhs: SingleCameraPoint): Double {
        val dx = lhs.x - rhs.x
        val dy = lhs.y - rhs.y
        return dx * dx + dy * dy
    }
}

data class SingleCameraAthleteSelection(
    val visibleObservation: SingleCameraBodyObservation?,
    val timingObservation: SingleCameraBodyObservation?
) {
    val isVisibleOutsideTimingZone: Boolean
        get() = visibleObservation != null && timingObservation == null
}

/**
 * Keeps the always-visible pose overlay separate from the stricter timing
 * identity: a person gets immediate skeleton feedback anywhere in frame
 * without letting a bystander away from the start zone arm or start the timer.
 */
class SingleCameraAthleteSelectionCoordinator {
    private val timingTracker = SingleCameraAthleteTracker()
    private val previewTracker = SingleCameraAthleteTracker()

    fun reset() {
        timingTracker.reset()
        previewTracker.reset()
    }

    fun select(
        candidates: List<SingleCameraBodyObservation>,
        targetStartX: Double,
        timestamp: Double,
        direction: SingleCameraSprintDirection,
        initialDirectedXRange: ClosedFloatingPointRange<Double>?
    ): SingleCameraAthleteSelection {
        val timing = timingTracker.select(candidates, targetStartX, timestamp, direction, initialDirectedXRange)
        if (timing != null) {
            previewTracker.reset()
            return SingleCameraAthleteSelection(timing, timing)
        }
        // While an established timing identity is briefly missing, do not
        // jump to a different visible person.
        if (timingTracker.lockedObservation != null) return SingleCameraAthleteSelection(null, null)
        val visible = previewTracker.select(candidates, targetStartX, timestamp, direction, null)
        return SingleCameraAthleteSelection(visible, null)
    }
}
