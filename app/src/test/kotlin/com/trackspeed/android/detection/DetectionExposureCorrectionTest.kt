package com.trackspeed.android.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionExposureCorrectionTest {
    @Test
    fun `exposure at or below two milliseconds has no correction`() {
        assertEquals(0.0, DetectionEngine.exposureCorrectionSeconds(null), 0.0)
        assertEquals(0.0, DetectionEngine.exposureCorrectionSeconds(2_000_000L), 0.0)
    }

    @Test
    fun `low light exposure uses current iOS three quarter correction`() {
        assertEquals(
            0.0075,
            DetectionEngine.exposureCorrectionSeconds(10_000_000L),
            0.000000001
        )
    }
}
