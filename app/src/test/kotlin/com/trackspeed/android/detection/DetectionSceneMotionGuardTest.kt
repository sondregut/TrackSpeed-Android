package com.trackspeed.android.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionSceneMotionGuardTest {

    private data class Crossing(
        val width: Int,
        val noTorso: Boolean = true,
        val validBody: Boolean = false,
        val gateWidth: Int,
        val strip: Float,
        val hRun: Int,
        val fragments: Int
    )

    @Test
    fun rejectsPositionIndependentSceneMotionFromIosJuly16Dataset() {
        val falseCrossings = listOf(
            Crossing(width = 170, gateWidth = 0, strip = 0f, hRun = 1, fragments = 13),
            Crossing(width = 144, gateWidth = 8, strip = 7f, hRun = 8, fragments = 27),
            Crossing(width = 90, gateWidth = 1, strip = 0f, hRun = 1, fragments = 31),
            Crossing(width = 166, gateWidth = 14, strip = 13f, hRun = 14, fragments = 34),
            Crossing(width = 147, gateWidth = 0, strip = 2f, hRun = 3, fragments = 33)
        )

        falseCrossings.forEach { crossing ->
            assertTrue(shouldReject(crossing))
        }
    }

    @Test
    fun keepsFastRealCrossingsFromIosRegressionControls() {
        val realCrossings = listOf(
            Crossing(width = 170, noTorso = false, gateWidth = 2, strip = 2f, hRun = 3, fragments = 30),
            Crossing(width = 111, gateWidth = 4, strip = 3f, hRun = 4, fragments = 17),
            Crossing(width = 158, gateWidth = 16, strip = 15f, hRun = 16, fragments = 33),
            Crossing(width = 163, gateWidth = 0, strip = 13f, hRun = 14, fragments = 21),
            Crossing(width = 112, gateWidth = 11, strip = 10f, hRun = 11, fragments = 16),
            Crossing(width = 165, validBody = true, gateWidth = 5, strip = 4f, hRun = 5, fragments = 32),
            Crossing(width = 169, validBody = true, gateWidth = 0, strip = 0f, hRun = 1, fragments = 27)
        )

        realCrossings.forEach { crossing ->
            assertFalse(shouldReject(crossing))
        }
    }

    private fun shouldReject(crossing: Crossing): Boolean {
        return DetectionEngine.shouldRejectIncoherentSceneMotion(
            blobWidth = crossing.width,
            processWidth = DetectionEngine.PROCESS_WIDTH,
            noTorsoSupport = crossing.noTorso,
            hasValidBodySupport = crossing.validBody,
            gateRowWidth = crossing.gateWidth,
            stripWidth = crossing.strip,
            horizontalRun = crossing.hRun,
            torsoFragmentCount = crossing.fragments
        )
    }
}
