package com.trackspeed.android.detection

import kotlin.math.sqrt

/**
 * Crossing detector state machine.
 *
 * Manages the detection lifecycle:
 * 1. WAITING_FOR_CLEAR - Gate must be empty for 0.2s
 * 2. ARMED - Ready to detect crossing
 * 3. POSTROLL - Capturing frames after trigger (0.2s)
 * 4. COOLDOWN - Brief pause before next detection
 * 5. PAUSED - Detection suspended
 *
 * Reference: docs/architecture/DETECTION_ALGORITHM.md Section 4
 */
class CrossingDetector(
    private val fps: Int = DetectionConfig.TARGET_FPS
) {
    enum class State {
        WAITING_FOR_CLEAR,
        ARMED,
        POSTROLL,
        COOLDOWN,
        PAUSED
    }

    var state: State = State.WAITING_FOR_CLEAR
        private set

    // Frame counters
    private var clearFrameCount = 0
    private var postrollFrameCount = 0
    private var cooldownFrameCount = 0

    // Persistence tracking
    private var framesAboveConfirm = 0

    // History for interpolation
    private val occupancyHistory = mutableListOf<OccupancySample>()

    // Frame duration in milliseconds
    private val frameDurationMs: Double = 1000.0 / fps

    // Frames needed for each duration
    private val clearFramesNeeded = (DetectionConfig.GATE_CLEAR_DURATION_MS / frameDurationMs).toInt()
    private val postrollFramesNeeded = (DetectionConfig.POSTROLL_DURATION_MS / frameDurationMs).toInt()
    private val cooldownFramesNeeded = (DetectionConfig.COOLDOWN_DURATION_MS / frameDurationMs).toInt()

    data class OccupancySample(
        val occupancy: Float,
        val timestamp: Long,  // Nanoseconds
        val frameIndex: Long
    )

    /**
     * Result of a detected crossing.
     */
    data class CrossingResult(
        val interpolatedTimestamp: Long,  // Nanoseconds
        val triggerFrameIndex: Long,
        val occupancyAtTrigger: Float,
        val interpolationOffsetMs: Double,
        val crossingRow: Int  // For rolling shutter correction
    )

    /**
     * Process a frame and check for crossing.
     *
     * @param occupancy Center strip occupancy (0.0 - 1.0)
     * @param timestamp Frame timestamp in nanoseconds
     * @param frameIndex Sequential frame number
     * @param torsoBounds Optional torso bounds from pose detection
     * @param threeStripResult Three-strip validation result
     * @return CrossingResult if crossing detected, null otherwise
     */
    fun processFrame(
        occupancy: Float,
        timestamp: Long,
        frameIndex: Long,
        torsoBounds: TorsoBounds?,
        threeStripResult: ThreeStripResult
    ): CrossingResult? {
        // Store history for interpolation
        occupancyHistory.add(OccupancySample(occupancy, timestamp, frameIndex))
        if (occupancyHistory.size > 10) {
            occupancyHistory.removeAt(0)
        }

        return when (state) {
            State.WAITING_FOR_CLEAR -> {
                processWaitingForClear(occupancy)
                null
            }

            State.ARMED -> {
                processArmed(occupancy, timestamp, frameIndex, torsoBounds, threeStripResult)
            }

            State.POSTROLL -> {
                processPostroll()
                null
            }

            State.COOLDOWN -> {
                processCooldown()
                null
            }

            State.PAUSED -> {
                // Do nothing
                null
            }
        }
    }

    private fun processWaitingForClear(occupancy: Float) {
        if (occupancy < DetectionConfig.GATE_CLEAR_BELOW) {
            clearFrameCount++
            if (clearFrameCount >= clearFramesNeeded) {
                state = State.ARMED
                clearFrameCount = 0
            }
        } else if (occupancy > DetectionConfig.GATE_UNCLEAR_ABOVE) {
            clearFrameCount = 0  // Reset timer
        }
    }

    private fun processArmed(
        occupancy: Float,
        timestamp: Long,
        frameIndex: Long,
        torsoBounds: TorsoBounds?,
        threeStripResult: ThreeStripResult
    ): CrossingResult? {
        if (occupancy >= DetectionConfig.ENTER_THRESHOLD) {
            // Check persistence
            if (occupancy >= DetectionConfig.CONFIRM_THRESHOLD) {
                framesAboveConfirm++
            } else {
                framesAboveConfirm = 0
            }

            // Validate crossing
            if (framesAboveConfirm >= DetectionConfig.PERSISTENCE_FRAMES &&
                validateCrossing(occupancy, torsoBounds, threeStripResult)) {

                // TRIGGER!
                state = State.POSTROLL
                postrollFrameCount = 0
                framesAboveConfirm = 0

                return interpolateCrossingTime(
                    frameIndex,
                    occupancy,
                    threeStripResult.centerRunResult.runStartRow
                )
            }
        } else {
            framesAboveConfirm = 0
        }

        return null
    }

    private fun processPostroll() {
        postrollFrameCount++
        if (postrollFrameCount >= postrollFramesNeeded) {
            state = State.COOLDOWN
            cooldownFrameCount = 0
        }
    }

    private fun processCooldown() {
        cooldownFrameCount++
        if (cooldownFrameCount >= cooldownFramesNeeded) {
            state = State.WAITING_FOR_CLEAR
        }
    }

    /**
     * Validate that this is a real crossing, not noise or arm/leg.
     */
    private fun validateCrossing(
        occupancy: Float,
        torsoBounds: TorsoBounds?,
        threeStripResult: ThreeStripResult
    ): Boolean {
        // 1. Distance filter - reject far-away objects
        if (torsoBounds != null) {
            val torsoHeightFraction = torsoBounds.yBottom - torsoBounds.yTop
            if (torsoHeightFraction < DetectionConfig.MIN_TORSO_HEIGHT_FRACTION) {
                return false  // Too far away
            }
        }

        // 2. Three-strip validation - must be torso-like (wide)
        if (!threeStripResult.isTorsoLike && occupancy < DetectionConfig.STRONG_EVIDENCE_THRESHOLD) {
            return false  // Likely arm/leg, not torso
        }

        // 3. Proximity check (if pose available)
        if (torsoBounds != null && occupancy < DetectionConfig.STRONG_EVIDENCE_THRESHOLD) {
            val maxDistance = if (torsoBounds.hasFullPose) {
                DetectionConfig.FULL_POSE_MAX_DISTANCE
            } else {
                DetectionConfig.PARTIAL_POSE_MAX_DISTANCE
            }
            // Note: Full proximity check would compare crossing position to pose position
            // For now, we rely on 3-strip validation
        }

        return true
    }

    /**
     * Interpolate the exact crossing time using recent occupancy samples.
     * Uses quadratic interpolation when possible, linear fallback otherwise.
     */
    private fun interpolateCrossingTime(
        triggerFrameIndex: Long,
        occupancyAtTrigger: Float,
        crossingRow: Int
    ): CrossingResult {
        val samples = occupancyHistory.toList()

        // Find samples below threshold (for curve fitting)
        val belowThreshold = samples.filter { it.occupancy < DetectionConfig.ENTER_THRESHOLD }
        val aboveThreshold = samples.lastOrNull { it.occupancy >= DetectionConfig.ENTER_THRESHOLD }
            ?: return defaultResult(triggerFrameIndex, occupancyAtTrigger, crossingRow)

        if (belowThreshold.size >= 3) {
            // Quadratic interpolation
            return quadraticInterpolation(
                belowThreshold.takeLast(3),
                aboveThreshold,
                triggerFrameIndex,
                occupancyAtTrigger,
                crossingRow
            )
        } else if (belowThreshold.isNotEmpty()) {
            // Linear interpolation
            return linearInterpolation(
                belowThreshold.last(),
                aboveThreshold,
                triggerFrameIndex,
                occupancyAtTrigger,
                crossingRow
            )
        }

        return defaultResult(triggerFrameIndex, occupancyAtTrigger, crossingRow)
    }

    private fun quadraticInterpolation(
        belowSamples: List<OccupancySample>,
        aboveSample: OccupancySample,
        triggerFrameIndex: Long,
        occupancyAtTrigger: Float,
        crossingRow: Int
    ): CrossingResult {
        val t0 = belowSamples[0].timestamp
        val tRange = (aboveSample.timestamp - t0).toDouble()
        if (tRange <= 0) return defaultResult(triggerFrameIndex, occupancyAtTrigger, crossingRow)

        // Normalize times to [0, 1]
        val points = belowSamples.map { sample ->
            val u = (sample.timestamp - t0).toDouble() / tRange
            Pair(u, sample.occupancy.toDouble())
        }

        // Fit quadratic: r(u) = au² + bu + c
        val (a, b, c) = fitQuadratic(points)

        // Solve au² + bu + (c - threshold) = 0
        val threshold = DetectionConfig.ENTER_THRESHOLD.toDouble()
        val discriminant = b * b - 4 * a * (c - threshold)

        if (discriminant < 0) {
            return linearInterpolation(
                belowSamples.last(),
                aboveSample,
                triggerFrameIndex,
                occupancyAtTrigger,
                crossingRow
            )
        }

        val u = if (a != 0.0) {
            (-b + sqrt(discriminant)) / (2 * a)
        } else if (b != 0.0) {
            (threshold - c) / b
        } else {
            0.5
        }

        val interpolatedTime = t0 + (u.coerceIn(0.0, 1.0) * tRange).toLong()
        val offsetNanos = aboveSample.timestamp - interpolatedTime
        val offsetMs = offsetNanos / 1_000_000.0

        return CrossingResult(
            interpolatedTimestamp = interpolatedTime,
            triggerFrameIndex = triggerFrameIndex,
            occupancyAtTrigger = occupancyAtTrigger,
            interpolationOffsetMs = offsetMs,
            crossingRow = crossingRow
        )
    }

    private fun fitQuadratic(points: List<Pair<Double, Double>>): Triple<Double, Double, Double> {
        // Simple 3-point quadratic fit
        // For points (x0,y0), (x1,y1), (x2,y2)
        if (points.size < 3) return Triple(0.0, 0.0, points.lastOrNull()?.second ?: 0.0)

        val (x0, y0) = points[0]
        val (x1, y1) = points[1]
        val (x2, y2) = points[2]

        // Lagrange interpolation coefficients
        val denom01 = (x0 - x1) * (x0 - x2)
        val denom12 = (x1 - x0) * (x1 - x2)
        val denom20 = (x2 - x0) * (x2 - x1)

        if (denom01 == 0.0 || denom12 == 0.0 || denom20 == 0.0) {
            return Triple(0.0, 0.0, y1)
        }

        // Coefficients of polynomial
        val a = y0 / denom01 + y1 / denom12 + y2 / denom20
        val b = -y0 * (x1 + x2) / denom01 - y1 * (x0 + x2) / denom12 - y2 * (x0 + x1) / denom20
        val c = y0 * x1 * x2 / denom01 + y1 * x0 * x2 / denom12 + y2 * x0 * x1 / denom20

        return Triple(a, b, c)
    }

    private fun linearInterpolation(
        below: OccupancySample,
        above: OccupancySample,
        triggerFrameIndex: Long,
        occupancyAtTrigger: Float,
        crossingRow: Int
    ): CrossingResult {
        val r0 = below.occupancy
        val r1 = above.occupancy
        val t0 = below.timestamp
        val t1 = above.timestamp

        if (r1 == r0) return defaultResult(triggerFrameIndex, occupancyAtTrigger, crossingRow)

        // Solve: r0 + (r1-r0) * alpha = threshold
        val alpha = (DetectionConfig.ENTER_THRESHOLD - r0) / (r1 - r0)
        val interpolatedTime = t0 + ((t1 - t0) * alpha.coerceIn(0f, 1f)).toLong()
        val offsetMs = (above.timestamp - interpolatedTime) / 1_000_000.0

        return CrossingResult(
            interpolatedTimestamp = interpolatedTime,
            triggerFrameIndex = triggerFrameIndex,
            occupancyAtTrigger = occupancyAtTrigger,
            interpolationOffsetMs = offsetMs,
            crossingRow = crossingRow
        )
    }

    private fun defaultResult(
        frameIndex: Long,
        occupancy: Float,
        crossingRow: Int
    ): CrossingResult {
        val sample = occupancyHistory.lastOrNull()
        return CrossingResult(
            interpolatedTimestamp = sample?.timestamp ?: 0L,
            triggerFrameIndex = frameIndex,
            occupancyAtTrigger = occupancy,
            interpolationOffsetMs = 0.0,
            crossingRow = crossingRow
        )
    }

    // Control methods

    fun pause() {
        state = State.PAUSED
    }

    fun resume() {
        state = State.WAITING_FOR_CLEAR
        clearFrameCount = 0
    }

    fun reset() {
        state = State.WAITING_FOR_CLEAR
        clearFrameCount = 0
        postrollFrameCount = 0
        cooldownFrameCount = 0
        framesAboveConfirm = 0
        occupancyHistory.clear()
    }

    fun isArmed(): Boolean = state == State.ARMED
    fun isCapturing(): Boolean = state == State.POSTROLL
}
