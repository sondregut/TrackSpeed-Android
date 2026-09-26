package com.trackspeed.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraConfigurationSelectorTest {
    @Test
    fun `selects 1080p when it sustains thirty fps`() {
        val selected = CameraConfigurationSelector.selectStream(
            listOf(
                CameraStreamCandidate(1920, 1080, 33_333_333L),
                CameraStreamCandidate(1280, 720, 16_666_666L),
                CameraStreamCandidate(3840, 2160, 50_000_000L)
            ),
            targetFps = 30
        )

        assertEquals(CameraStreamCandidate(1920, 1080, 33_333_333L), selected)
    }

    @Test
    fun `rejects slow preferred size when another preferred size sustains target`() {
        val selected = CameraConfigurationSelector.selectStream(
            listOf(
                CameraStreamCandidate(1920, 1080, 40_000_000L),
                CameraStreamCandidate(1280, 720, 33_333_333L)
            ),
            targetFps = 30
        )

        assertEquals(1280, selected?.width)
    }

    @Test
    fun `prefers exact fixed fps range`() {
        val selected = CameraConfigurationSelector.selectFpsRange(
            listOf(
                CameraFpsCandidate(15, 30),
                CameraFpsCandidate(30, 30),
                CameraFpsCandidate(30, 60)
            ),
            targetFps = 30
        )

        assertEquals(CameraFpsCandidate(30, 30), selected)
    }

    @Test
    fun `uses closest containing range when fixed range is unavailable`() {
        val selected = CameraConfigurationSelector.selectFpsRange(
            listOf(CameraFpsCandidate(7, 30), CameraFpsCandidate(15, 30), CameraFpsCandidate(30, 60)),
            targetFps = 30
        )

        assertEquals(CameraFpsCandidate(15, 30), selected)
    }

    @Test
    fun `returns null without usable sizes or fps ranges`() {
        assertNull(CameraConfigurationSelector.selectStream(emptyList(), 30))
        assertNull(CameraConfigurationSelector.selectFpsRange(emptyList(), 30))
    }
}
