package com.trackspeed.android.detection

import kotlin.math.sqrt

/**
 * Cross-platform movement thresholds used to pause Replica while a timing
 * phone is being lifted or aimed. Both linear acceleration and rotation are
 * considered because a smooth rotation can invalidate the fixed-camera
 * background without exceeding the acceleration threshold.
 */
internal object PhoneMotionStabilityPolicy {
    const val UPDATES_PER_SECOND = 50
    const val SAMPLE_PERIOD_MICROS = 1_000_000 / UPDATES_PER_SECOND
    const val ACCELERATION_THRESHOLD_G = 0.15
    const val ROTATION_THRESHOLD_RADIANS_PER_SECOND = 0.35
    const val STABLE_DEBOUNCE_NANOS = 750_000_000L
    const val STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED = 9.80665

    fun detectsMovement(
        accelerationMagnitudeG: Double,
        rotationMagnitudeRadiansPerSecond: Double
    ): Boolean = accelerationMagnitudeG > ACCELERATION_THRESHOLD_G ||
        rotationMagnitudeRadiansPerSecond > ROTATION_THRESHOLD_RADIANS_PER_SECOND

    fun magnitude(x: Float, y: Float, z: Float): Double =
        sqrt((x * x + y * y + z * z).toDouble())
}
