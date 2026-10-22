package com.trackspeed.android.camerastart

import kotlin.math.hypot

/** Motion starts at confirmed onset. In-Frame additionally requires a real departure, dated back to that onset. */
class CameraStartArming(
    val requiresCommitment: Boolean,
    val parameters: CameraStartOnsetParameters = CameraStartOnsetParameters()
) {
    enum class Phase { STEP_INTO_FRAME, HOLD_STILL, WAITING_FOR_SESSION, ARMED, CONFIRMING, STARTED }
    enum class Commit { MOTION, TRUNK_TRAVEL, LEFT_FRAME }
    data class Start(val onset: CameraStartOnset, val committedAtNanos: Long, val commit: Commit, val peakTrunkTravel: Double)
    var phase = Phase.STEP_INTO_FRAME; private set
    var progress = 0.0; private set
    private val detector = CameraStartOnsetDetector(parameters)
    private var ready = false
    private var visible = false
    private var lastSample: Long? = null
    private var reacquireDeadline: Long? = null
    private val trunkHistory = ArrayDeque<TrunkSample>()
    private var candidate: Candidate? = null

    fun setSessionReady(value: Boolean) {
        val wasReady = ready; ready = value
        when {
            phase == Phase.WAITING_FOR_SESSION && ready -> arm()
            (phase == Phase.ARMED || phase == Phase.CONFIRMING) && !ready -> restart()
            phase == Phase.STARTED && ready && !wasReady -> restart()
        }
    }

    fun startNotSent() { if (phase == Phase.STARTED) restart() }

    /** A capture failure is not evidence that an athlete left the frame. */
    fun cameraInterrupted() {
        visible = false; lastSample = null; reacquireDeadline = null
        if (phase != Phase.STARTED) restart()
        else { detector.reset(); trunkHistory.clear(); candidate = null }
    }

    fun ingest(sample: CameraStartPoseSample): Start? {
        if (sample.timestampNanos <= 0 || sample.timestampNanos <= (lastSample ?: 0)) return null
        lastSample = sample.timestampNanos
        if (phase == Phase.STARTED) return null
        if (phase == Phase.CONFIRMING) return confirm(sample)
        recordTrunk(sample)
        for (event in detector.ingest(sample)) {
            when (event) {
                CameraStartOnsetEvent.AthleteVisible -> {
                    visible = true; reacquireDeadline = null
                    if (phase == Phase.STEP_INTO_FRAME) { phase = Phase.HOLD_STILL; progress = 0.0 }
                }
                CameraStartOnsetEvent.AthleteLost -> {
                    visible = false; trunkHistory.clear(); phase = Phase.STEP_INTO_FRAME
                }
                is CameraStartOnsetEvent.Settling -> { phase = Phase.HOLD_STILL; progress = event.progress }
                CameraStartOnsetEvent.Settled -> if (ready) arm() else { phase = Phase.WAITING_FOR_SESSION }
                is CameraStartOnsetEvent.Onset -> {
                    if (!requiresCommitment) return fire(event.value, sample.timestampNanos, Commit.MOTION, 0.0)
                    val baseline = trunkBaseline(event.value.onsetNanos)
                    if (baseline == null) { restart(); return null }
                    candidate = Candidate(event.value, baseline, sample.timestampNanos)
                    trunkHistory.clear(); phase = Phase.CONFIRMING
                    return confirm(sample)
                }
            }
        }
        reacquireDeadline?.let { if (sample.timestampNanos > it) {
            reacquireDeadline = null; visible = false; phase = Phase.STEP_INTO_FRAME
        } }
        return null
    }

    private fun arm() { if (detector.arm()) phase = Phase.ARMED }
    private fun restart() {
        detector.reset(); trunkHistory.clear(); candidate = null; progress = 0.0
        reacquireDeadline = if (visible) lastSample?.let { it + nanos(parameters.maximumPoseGapSeconds) } else null
        phase = if (visible) Phase.HOLD_STILL else Phase.STEP_INTO_FRAME
    }
    private fun recordTrunk(sample: CameraStartPoseSample) {
        val joints = confident(sample, parameters.minimumJointConfidence)
        val core = joints.filterKeys { it.isCore }
        val torso = torsoLength(joints)
        if (core.size >= 2 && torso > 0) trunkHistory.addLast(TrunkSample(sample.timestampNanos, core, torso))
        val keep = nanos(parameters.settleSeconds + parameters.settleWindowSlackSeconds)
        while (trunkHistory.isNotEmpty() && sample.timestampNanos - trunkHistory.first().timestamp > keep) trunkHistory.removeFirst()
    }
    private fun trunkBaseline(onset: Long): Baseline? {
        val still = trunkHistory.filter { it.timestamp <= onset }.ifEmpty { trunkHistory.take(1) }
        if (still.isEmpty()) return null
        val scale = median(still.map { it.torso })
        if (scale < parameters.minimumBodyScale) return null
        val positions = CameraStartJoint.entries.filter { it.isCore }.mapNotNull { joint ->
            val points = still.mapNotNull { it.joints[joint] }
            if (points.isEmpty()) null else joint to medianPoint(points)
        }.toMap()
        return if (positions.size >= 2) Baseline(positions, scale) else null
    }
    private fun confirm(sample: CameraStartPoseSample): Start? {
        val current = candidate ?: return null
        val now = sample.timestampNanos
        val travel = confident(sample, parameters.minimumJointConfidence).filterKeys { it.isCore }.mapNotNull { (joint, p) ->
            current.baseline.positions[joint]?.let { hypot(p.x - it.x, p.y - it.y) / current.baseline.scale }
        }
        if (travel.size >= 2) {
            val distance = median(travel)
            current.lastTrunk = now; current.peak = maxOf(current.peak, distance)
            if (distance >= 0.5) return fire(current.onset, now, Commit.TRUNK_TRAVEL, current.peak)
            if (now - current.onset.onsetNanos > nanos(parameters.settleSeconds)) restart()
        } else if (now - current.lastTrunk > nanos(parameters.maximumPoseGapSeconds)) {
            return fire(current.onset, now, Commit.LEFT_FRAME, current.peak)
        }
        return null
    }
    private fun fire(onset: CameraStartOnset, now: Long, commit: Commit, peak: Double): Start {
        candidate = null; visible = false; phase = Phase.STARTED
        return Start(onset, now, commit, peak)
    }
    private data class TrunkSample(val timestamp: Long, val joints: Map<CameraStartJoint, CameraStartPoint>, val torso: Double)
    private data class Baseline(val positions: Map<CameraStartJoint, CameraStartPoint>, val scale: Double)
    private data class Candidate(val onset: CameraStartOnset, val baseline: Baseline, var lastTrunk: Long, var peak: Double = 0.0)
}
