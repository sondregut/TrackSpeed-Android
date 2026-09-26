package com.trackspeed.android.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionDirectionInferenceTest {

    @Test
    fun temporalMotionWinsAfterRunnerCenterPassesGate() {
        assertTrue(
            DetectionEngine.inferMovingLeftToRight(
                currentCenterX = 112f,
                gateColumn = 90f,
                strongestHistoricalDelta = 18f
            )
        )
        assertFalse(
            DetectionEngine.inferMovingLeftToRight(
                currentCenterX = 68f,
                gateColumn = 90f,
                strongestHistoricalDelta = -18f
            )
        )
    }

    @Test
    fun weakTemporalEvidenceFallsBackToCurrentSide() {
        assertTrue(DetectionEngine.inferMovingLeftToRight(70f, 90f, 3.9f))
        assertFalse(DetectionEngine.inferMovingLeftToRight(110f, 90f, -3.9f))
    }
}
