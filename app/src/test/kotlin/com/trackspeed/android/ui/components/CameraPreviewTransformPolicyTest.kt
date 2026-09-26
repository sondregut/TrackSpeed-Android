package com.trackspeed.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPreviewTransformPolicyTest {
    @Test
    fun `portrait back camera does not double apply sensor rotation`() {
        val transform = CameraPreviewTransformPolicy.resolve(
            viewWidth = 440,
            viewHeight = 627,
            previewWidth = 1280,
            previewHeight = 720,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
            isFrontCamera = false
        )

        assertEquals(90, transform.relativeRotationDegrees)
        assertEquals(0f, transform.displayCompensationDegrees, 0.001f)
        assertFalse(transform.mirrorHorizontally)
    }

    @Test
    fun `portrait front camera stays upright and mirrors preview`() {
        val transform = CameraPreviewTransformPolicy.resolve(
            viewWidth = 440,
            viewHeight = 627,
            previewWidth = 1280,
            previewHeight = 720,
            sensorOrientationDegrees = 270,
            displayRotationDegrees = 0,
            isFrontCamera = true
        )

        assertEquals(270, transform.relativeRotationDegrees)
        assertEquals(0f, transform.displayCompensationDegrees, 0.001f)
        assertTrue(transform.mirrorHorizontally)
    }

    @Test
    fun `display rotation is compensated counterclockwise`() {
        val transform = CameraPreviewTransformPolicy.resolve(
            viewWidth = 627,
            viewHeight = 440,
            previewWidth = 1280,
            previewHeight = 720,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 90,
            isFrontCamera = false
        )

        assertEquals(180, transform.relativeRotationDegrees)
        assertEquals(-90f, transform.displayCompensationDegrees, 0.001f)
    }

    @Test
    fun `aspect fill scales are finite and positive`() {
        val transform = CameraPreviewTransformPolicy.resolve(
            viewWidth = 440,
            viewHeight = 627,
            previewWidth = 1280,
            previewHeight = 720,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
            isFrontCamera = false
        )

        assertTrue(transform.scaleX.isFinite() && transform.scaleX > 0f)
        assertTrue(transform.scaleY.isFinite() && transform.scaleY > 0f)
    }
}
