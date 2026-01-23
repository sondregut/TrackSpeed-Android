package com.trackspeed.android.detection.experimental

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tracks a single blob across frames with dynamic gating.
 *
 * Features:
 * - Dynamic gate: baseGate + velocity * multiplier
 * - Normalized scoring (area, aspect, height, gate proximity)
 * - Direction locking after consistent velocity
 * - Lost-frame tolerance (2 frames)
 *
 * ZERO ALLOCATIONS: Does not create lists or store blob references across frames.
 */
class BlobTracker {

    /**
     * Approach direction (runner coming from left or right).
     */
    enum class Direction {
        UNKNOWN,
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT
    }

    /**
     * Tracking result.
     *
     * NOTE: The blob reference is only valid within the current frame.
     * Do NOT store this reference - the blob pool is reused next frame.
     */
    data class TrackResult(
        val blob: ConnectedComponentLabeler.Blob?,
        val velocityNorm: Float,         // ROI-widths per second
        val direction: Direction,
        val distanceToGate: Float,       // Signed distance in pixels
        val isValid: Boolean             // Blob passes all filters
    )

    // ═══════════════════════════════════════════════════════════════
    // TRACKING STATE (PRIMITIVES ONLY - NO BLOB REFERENCES!)
    // ═══════════════════════════════════════════════════════════════

    private var hasActiveTarget = false
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f
    private var velocityNorm = 0f
    private var direction = Direction.UNKNOWN
    private var dirLockCount = 0
    private var lostCount = 0
    private var consecutiveValidFrames = 0

    /**
     * Update tracker with new frame.
     *
     * ZERO ALLOCATIONS: Iterates blobs in-place, no list creation.
     *
     * @param blobs Candidate blobs from CCL (from pool, do NOT store!)
     * @param roiWidth ROI width in pixels
     * @param gateX Gate line X position in pixels
     * @param dt Time since last frame in seconds
     * @return Tracking result
     */
    fun update(
        blobs: List<ConnectedComponentLabeler.Blob>,
        roiWidth: Float,
        gateX: Float,
        dt: Float
    ): TrackResult {
        // Acquire if no active target
        if (!hasActiveTarget) {
            // Find best valid blob to start tracking (no list allocation)
            var best: ConnectedComponentLabeler.Blob? = null
            var bestScore = Float.NEGATIVE_INFINITY

            for (blob in blobs) {
                if (!isValidBlob(blob)) continue
                val score = scoreBlob(blob, roiWidth, gateX, preferGate = false)
                if (score > bestScore) {
                    bestScore = score
                    best = blob
                }
            }

            if (best != null) {
                hasActiveTarget = true
                lastCentroidX = best.centroid.x
                lastCentroidY = best.centroid.y
                lostCount = 0
                direction = Direction.UNKNOWN
                dirLockCount = 0
                velocityNorm = 0f
                consecutiveValidFrames = 1

                val distanceToGate = best.centroid.x - gateX
                return TrackResult(best, 0f, Direction.UNKNOWN, distanceToGate, true)
            }
            return TrackResult(null, 0f, Direction.UNKNOWN, 0f, false)
        }

        // Calculate dynamic gate based on velocity
        val dynamicGate = ExperimentalConfig.TRACKING_BASE_GATE_PIXELS +
            ExperimentalConfig.TRACKING_GATE_VEL_MULTIPLIER * abs(velocityNorm) * roiWidth * dt

        // Find best match within gate (no list allocation - iterate in-place)
        var bestMatch: ConnectedComponentLabeler.Blob? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (blob in blobs) {
            if (!isValidBlob(blob)) continue

            // Distance from last known position (using stored primitives)
            val dx = blob.centroid.x - lastCentroidX
            val dy = blob.centroid.y - lastCentroidY
            val dist = sqrt(dx * dx + dy * dy)

            if (dist > dynamicGate) continue

            val score = scoreBlob(blob, roiWidth, gateX, preferGate = true)
            if (score > bestScore) {
                bestScore = score
                bestMatch = blob
            }
        }

        if (bestMatch != null) {
            lostCount = 0
            consecutiveValidFrames++

            // Update velocity using stored position (NOT blob reference!)
            val currX = bestMatch.centroid.x
            val vPixelsPerSec = (currX - lastCentroidX) / dt.coerceAtLeast(1e-6f)
            velocityNorm = vPixelsPerSec / roiWidth

            // Store current position for next frame
            lastCentroidX = bestMatch.centroid.x
            lastCentroidY = bestMatch.centroid.y

            // Direction locking
            if (abs(velocityNorm) > ExperimentalConfig.MIN_VELOCITY_NORM) {
                val newDir = if (velocityNorm > 0) Direction.LEFT_TO_RIGHT else Direction.RIGHT_TO_LEFT
                if (direction == Direction.UNKNOWN || direction == newDir) {
                    direction = newDir
                    dirLockCount++
                } else {
                    dirLockCount = 0
                    direction = Direction.UNKNOWN
                }
            } else {
                direction = Direction.UNKNOWN
                dirLockCount = 0
            }

            val reportedDir = if (dirLockCount >= ExperimentalConfig.DIRECTION_LOCK_FRAMES) {
                direction
            } else {
                Direction.UNKNOWN
            }

            val distanceToGate = bestMatch.centroid.x - gateX
            val isValid = consecutiveValidFrames >= ExperimentalConfig.MIN_VALID_FRAMES

            return TrackResult(bestMatch, velocityNorm, reportedDir, distanceToGate, isValid)
        } else {
            // Lost tracking
            lostCount++
            if (lostCount > ExperimentalConfig.TRACKING_LOST_FRAMES_ALLOWED) {
                hasActiveTarget = false
                direction = Direction.UNKNOWN
                dirLockCount = 0
                consecutiveValidFrames = 0
            }
            return TrackResult(null, velocityNorm, direction, 0f, false)
        }
    }

    /**
     * Reset tracker state.
     */
    fun reset() {
        hasActiveTarget = false
        lastCentroidX = 0f
        lastCentroidY = 0f
        velocityNorm = 0f
        direction = Direction.UNKNOWN
        dirLockCount = 0
        lostCount = 0
        consecutiveValidFrames = 0
    }

    /**
     * Check if blob passes basic filters.
     */
    private fun isValidBlob(blob: ConnectedComponentLabeler.Blob): Boolean {
        return blob.aspectRatio in ExperimentalConfig.MIN_ASPECT_RATIO..ExperimentalConfig.MAX_ASPECT_RATIO &&
               blob.heightFraction in ExperimentalConfig.MIN_HEIGHT_FRACTION..ExperimentalConfig.MAX_HEIGHT_FRACTION &&
               blob.area >= ExperimentalConfig.MIN_BLOB_AREA
    }

    /**
     * Score blob for selection.
     */
    private fun scoreBlob(
        blob: ConnectedComponentLabeler.Blob,
        roiWidth: Float,
        gateX: Float,
        preferGate: Boolean
    ): Float {
        // Area score (normalized)
        val areaScore = (blob.area.toFloat() / 2000f).coerceAtMost(1f)

        // Aspect ratio score (prefer 1.6-4.0)
        val aspectScore = if (blob.aspectRatio in 1.6f..4.0f) 1f else 0f

        // Height score (prefer larger = closer)
        val heightScore = (blob.heightFraction / 0.4f).coerceAtMost(1f)

        // Gate proximity score (only when preferring gate)
        val gateScore = if (preferGate) {
            val dx = abs(blob.centroid.x - gateX)
            1f - (dx / (0.5f * roiWidth)).coerceAtMost(1f)
        } else 0f

        return 0.35f * areaScore + 0.35f * aspectScore + 0.20f * heightScore + 0.10f * gateScore
    }
}
