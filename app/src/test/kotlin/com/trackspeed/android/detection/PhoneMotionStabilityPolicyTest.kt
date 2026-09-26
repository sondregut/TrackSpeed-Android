package com.trackspeed.android.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMotionStabilityPolicyTest {
    @Test
    fun `smooth rotation is movement even without linear acceleration`() {
        assertTrue(
            PhoneMotionStabilityPolicy.detectsMovement(
                accelerationMagnitudeG = 0.01,
                rotationMagnitudeRadiansPerSecond = 0.36
            )
        )
    }

    @Test
    fun `values at thresholds remain stable`() {
        assertFalse(
            PhoneMotionStabilityPolicy.detectsMovement(
                accelerationMagnitudeG = PhoneMotionStabilityPolicy.ACCELERATION_THRESHOLD_G,
                rotationMagnitudeRadiansPerSecond =
                    PhoneMotionStabilityPolicy.ROTATION_THRESHOLD_RADIANS_PER_SECOND
            )
        )
    }
}
