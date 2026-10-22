package com.trackspeed.android.camerastart

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToLong

/** Coordinates use frame height on both axes; timestamps are mapped camera monotonic nanoseconds. */
enum class CameraStartJoint {
    NOSE, NECK, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW,
    LEFT_WRIST, RIGHT_WRIST, LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE;
    val isCore: Boolean get() = this in setOf(NECK, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP)
}
data class CameraStartPoint(val x: Double, val y: Double, val confidence: Double = 1.0)
data class CameraStartPoseSample(val timestampNanos: Long, val joints: Map<CameraStartJoint, CameraStartPoint>)
data class CameraStartOnsetParameters(
    val settleSeconds: Double = 0.8,
    val minimumJointConfidence: Double = 0.30,
    val minimumVisibleJoints: Int = 8,
    val settleWindowSlackSeconds: Double = 0.10,
    val settleCoverageFraction: Double = 0.95,
    val minimumBodyScale: Double = 0.04,
    val maximumSettledAggregate: Double = 0.06,
    val minimumBaselineJointCoverage: Double = 0.5,
    val minimumNoiseBand: Double = 0.025,
    val noiseBandMedianMultiplier: Double = 4.0,
    val minimumMovementThreshold: Double = 0.08,
    val movementThresholdNoiseBandMultiplier: Double = 2.0,
    val confirmationSamples: Int = 3,
    val maximumPoseGapSeconds: Double = 0.30
)
data class CameraStartOnset(
    val onsetNanos: Long,
    val confirmedAtNanos: Long,
    val noiseBand: Double,
    val threshold: Double,
    val peakAggregate: Double,
    val frameIntervalNanos: Long,
    val sampleCount: Int
)
sealed interface CameraStartOnsetEvent {
    data object AthleteVisible : CameraStartOnsetEvent
    data object AthleteLost : CameraStartOnsetEvent
    data class Settling(val progress: Double) : CameraStartOnsetEvent
    data object Settled : CameraStartOnsetEvent
    data class Onset(val value: CameraStartOnset) : CameraStartOnsetEvent
}

/** Pure counterpart of iPhone CameraStartOnsetDetector. Single-joint glitches and hand-only motion cannot fire. */
class CameraStartOnsetDetector(val parameters: CameraStartOnsetParameters = CameraStartOnsetParameters()) {
    enum class Phase { ACQUIRING, SETTLING, SETTLED, ARMED, FIRED }
    var phase = Phase.ACQUIRING; private set
    private var lastAccepted: Long? = null
    private var lastVisible: Long? = null
    private var visible = false
    private val window = ArrayDeque<WindowSample>()
    private val intervals = ArrayDeque<Long>()
    private var baseline: Baseline? = null
    private var armedAt = 0L
    private val motionRun = ArrayDeque<Boolean>()
    private var peak = 0.0
    private var lastQuiet: Reading? = null
    private var firstAfterQuiet: Reading? = null

    fun reset() {
        phase = Phase.ACQUIRING
        lastAccepted = null; lastVisible = null; visible = false
        window.clear(); intervals.clear(); baseline = null
        armedAt = 0; lastQuiet = null; firstAfterQuiet = null; clearRun()
    }

    fun arm(): Boolean = when (phase) {
        Phase.ARMED -> true
        Phase.SETTLED -> {
            phase = Phase.ARMED; armedAt = lastAccepted ?: 0
            clearRun(); lastQuiet = null; firstAfterQuiet = null; true
        }
        else -> false
    }

    fun ingest(sample: CameraStartPoseSample): List<CameraStartOnsetEvent> {
        if (phase == Phase.FIRED || sample.timestampNanos <= 0) return emptyList()
        val now = sample.timestampNanos
        lastAccepted?.let { previous ->
            if (now <= previous) return emptyList()
            intervals.addLast(now - previous)
            if (intervals.size > 120) intervals.removeFirst()
        }
        lastAccepted = now
        val events = mutableListOf<CameraStartOnsetEvent>()
        if (visible && now - (lastVisible ?: now) > nanos(parameters.maximumPoseGapSeconds)) {
            events += CameraStartOnsetEvent.AthleteLost
            phase = Phase.ACQUIRING; visible = false; lastVisible = null
            window.clear(); baseline = null; lastQuiet = null; firstAfterQuiet = null; clearRun()
        }
        val joints = confident(sample, parameters.minimumJointConfidence)
        if (joints.size < parameters.minimumVisibleJoints ||
            (CameraStartJoint.LEFT_HIP !in joints && CameraStartJoint.RIGHT_HIP !in joints) ||
            (CameraStartJoint.LEFT_SHOULDER !in joints && CameraStartJoint.RIGHT_SHOULDER !in joints)) return events
        lastVisible = now
        if (!visible) { visible = true; events += CameraStartOnsetEvent.AthleteVisible }
        if (phase == Phase.ARMED) {
            ingestArmed(joints, now)?.let { events += CameraStartOnsetEvent.Onset(it) }
        } else {
            events += ingestUnarmed(joints, now)
        }
        return events
    }

    private fun ingestUnarmed(joints: Map<CameraStartJoint, CameraStartPoint>, now: Long): List<CameraStartOnsetEvent> {
        window.addLast(WindowSample(now, joints, torsoLength(joints)))
        val keep = nanos(parameters.settleSeconds + parameters.settleWindowSlackSeconds)
        while (window.isNotEmpty() && now - window.first().timestamp > keep) window.removeFirst()
        val bodyScale = median(window.map { it.torso })
        if (bodyScale < parameters.minimumBodyScale || bodyScale <= 0) {
            phase = Phase.ACQUIRING; baseline = null; clearRun(); return emptyList()
        }
        val minimumCount = maxOf(1, ceil(window.size * parameters.minimumBaselineJointCoverage).toInt())
        val medians = CameraStartJoint.entries.mapNotNull { joint ->
            val points = window.mapNotNull { it.joints[joint] }
            if (points.size < minimumCount) null else joint to medianPoint(points)
        }.toMap()
        val aggregates = window.map { motion(it.joints, medians, bodyScale, Double.POSITIVE_INFINITY).aggregate }
        val settled = now - window.first().timestamp >= nanos(parameters.settleSeconds) * parameters.settleCoverageFraction &&
            aggregates.all { it <= parameters.maximumSettledAggregate }
        val previous = baseline
        if (phase == Phase.SETTLED && previous != null) {
            if (advance(motion(joints, previous.positions, previous.bodyScale, previous.threshold), previous.threshold)) {
                baseline = null; clearRun()
                window.clear(); window.addLast(WindowSample(now, joints, torsoLength(joints)))
                phase = Phase.SETTLING
                return listOf(CameraStartOnsetEvent.Settling(0.0))
            }
            if (settled) baseline = makeBaseline(medians, bodyScale, aggregates)
            return emptyList()
        }
        if (settled) {
            baseline = makeBaseline(medians, bodyScale, aggregates)
            phase = Phase.SETTLED; clearRun()
            return listOf(CameraStartOnsetEvent.Settled)
        }
        var firstStill = aggregates.size
        while (firstStill > 0 && aggregates[firstStill - 1] <= parameters.maximumSettledAggregate) firstStill--
        val span = if (firstStill < window.size) now - window.elementAt(firstStill).timestamp else 0L
        phase = Phase.SETTLING
        return listOf(CameraStartOnsetEvent.Settling((span.toDouble() / nanos(parameters.settleSeconds).coerceAtLeast(1)).coerceIn(0.0, 1.0)))
    }

    private fun makeBaseline(positions: Map<CameraStartJoint, CameraStartPoint>, scale: Double, aggregates: List<Double>): Baseline {
        val noise = maxOf(parameters.minimumNoiseBand, parameters.noiseBandMedianMultiplier * median(aggregates))
        return Baseline(positions, scale, noise, maxOf(parameters.minimumMovementThreshold,
            parameters.movementThresholdNoiseBandMultiplier * noise), window.size)
    }

    private fun ingestArmed(joints: Map<CameraStartJoint, CameraStartPoint>, now: Long): CameraStartOnset? {
        val base = baseline ?: return null
        val motion = motion(joints, base.positions, base.bodyScale, base.threshold)
        val reading = Reading(now, motion.aggregate)
        if (motion.aggregate <= base.noiseBand) { lastQuiet = reading; firstAfterQuiet = null }
        else if (firstAfterQuiet == null) firstAfterQuiet = reading
        if (!advance(motion, base.threshold)) return null
        phase = Phase.FIRED
        val quiet = lastQuiet
        val after = firstAfterQuiet
        val onset = when {
            quiet == null -> armedAt
            after == null -> quiet.timestamp
            else -> {
                val rise = after.aggregate - quiet.aggregate
                val alpha = if (rise > 0) ((base.noiseBand - quiet.aggregate) / rise).coerceIn(0.0, 1.0) else 0.0
                quiet.timestamp + (alpha * (after.timestamp - quiet.timestamp)).roundToLong()
            }
        }
        val sorted = intervals.sorted()
        val mid = sorted.size / 2
        val interval = if (sorted.isEmpty()) 0 else if (sorted.size % 2 == 0) sorted[mid - 1] / 2 + sorted[mid] / 2 else sorted[mid]
        return CameraStartOnset(onset, now, base.noiseBand, base.threshold, peak, interval, base.count)
    }

    private fun advance(motion: Motion, threshold: Double): Boolean {
        if (motion.aggregate < threshold) { clearRun(); return false }
        val required = parameters.confirmationSamples.coerceAtLeast(1)
        motionRun.addLast(motion.coreMoved)
        if (motionRun.size > required) motionRun.removeFirst()
        peak = maxOf(peak, motion.aggregate)
        return motionRun.size >= required && motionRun.any { it }
    }
    private fun clearRun() { motionRun.clear(); peak = 0.0 }
    private fun motion(joints: Map<CameraStartJoint, CameraStartPoint>, reference: Map<CameraStartJoint, CameraStartPoint>, scale: Double, threshold: Double): Motion {
        var core = false
        val displacements = joints.mapNotNull { (joint, point) ->
            reference[joint]?.let { anchor ->
                val d = hypot(point.x - anchor.x, point.y - anchor.y) / scale
                if (joint.isCore && d >= threshold) core = true
                d
            }
        }.sortedDescending()
        return Motion(displacements.getOrElse(1) { displacements.firstOrNull() ?: 0.0 }, core)
    }
    private data class WindowSample(val timestamp: Long, val joints: Map<CameraStartJoint, CameraStartPoint>, val torso: Double)
    private data class Baseline(val positions: Map<CameraStartJoint, CameraStartPoint>, val bodyScale: Double, val noiseBand: Double, val threshold: Double, val count: Int)
    private data class Motion(val aggregate: Double, val coreMoved: Boolean)
    private data class Reading(val timestamp: Long, val aggregate: Double)
}

internal fun nanos(seconds: Double) = (seconds.coerceAtLeast(0.0) * 1e9).roundToLong()
internal fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted(); val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
}
internal fun medianPoint(points: List<CameraStartPoint>) = CameraStartPoint(median(points.map { it.x }), median(points.map { it.y }))
internal fun confident(sample: CameraStartPoseSample, minimumConfidence: Double) = sample.joints.filterValues {
    it.confidence >= minimumConfidence && it.x.isFinite() && it.y.isFinite()
}
internal fun torsoLength(joints: Map<CameraStartJoint, CameraStartPoint>): Double {
    fun midpoint(a: CameraStartPoint?, b: CameraStartPoint?): CameraStartPoint? = when {
        a != null && b != null -> CameraStartPoint((a.x + b.x) / 2, (a.y + b.y) / 2)
        else -> a ?: b
    }
    val shoulder = midpoint(joints[CameraStartJoint.LEFT_SHOULDER], joints[CameraStartJoint.RIGHT_SHOULDER]) ?: return 0.0
    val hip = midpoint(joints[CameraStartJoint.LEFT_HIP], joints[CameraStartJoint.RIGHT_HIP]) ?: return 0.0
    return hypot(shoulder.x - hip.x, shoulder.y - hip.y)
}
