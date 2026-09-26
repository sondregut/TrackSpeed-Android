package com.trackspeed.android.detection

/**
 * Calculates rolling shutter timing compensation based on camera position,
 * frame rate, and vertical position of the detection in the frame.
 *
 * Physics (portrait):
 * - Top rows are captured near frame timestamp T
 * - Bottom rows are captured near T + readoutTime
 *
 * So if the detected chest is at yNormalized in [0, 1],
 * offset = readoutTime * yNormalized
 *
 * Ported from iOS RollingShutterCalculator.swift.
 */
object RollingShutterCalculator {

    /**
     * Estimated sensor readout time (seconds) based on camera and FPS mode.
     * These are intentionally conservative approximations (device-agnostic).
     */
    fun getReadoutDuration(isFrontCamera: Boolean, fps: Double): Double {
        val safeFps = maxOf(0.0, fps)

        return if (isFrontCamera) {
            if (safeFps >= 100) 0.008 else 0.018
        } else {
            when {
                safeFps >= 200 -> 0.003
                safeFps >= 100 -> 0.005
                else -> 0.012
            }
        }
    }

    /**
     * Calculates rolling shutter compensation (nanoseconds) to ADD to a frame timestamp.
     * @param isFrontCamera true if selfie camera
     * @param fps current FPS (30/60/120/240)
     * @param chestYNormalized 0.0 at top, 1.0 at bottom (clamped)
     * @return compensation in nanoseconds
     */
    fun calculateCompensationNanos(
        isFrontCamera: Boolean,
        fps: Double,
        chestYNormalized: Float
    ): Long {
        val readoutSeconds = getReadoutDuration(isFrontCamera, fps)

        var y = chestYNormalized
        if (!y.isFinite()) y = 0f
        y = y.coerceIn(0f, 1f)

        val offsetSeconds = readoutSeconds * y.toDouble()
        val nanos = offsetSeconds * 1_000_000_000.0

        return maxOf(0L, nanos.toLong())
    }
}
