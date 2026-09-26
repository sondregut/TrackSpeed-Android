package com.trackspeed.android.ui.components

import kotlin.math.max

/**
 * Camera2 TextureView transform values derived from Android's documented
 * resizable-preview algorithm.
 *
 * TextureView already rotates a camera buffer from sensor orientation into the
 * device's natural orientation. The view transform therefore compensates only
 * for display rotation; sensor orientation is used solely to calculate the
 * aspect-fill scale.
 */
internal data class CameraPreviewTransform(
    val scaleX: Float,
    val scaleY: Float,
    val displayCompensationDegrees: Float,
    val relativeRotationDegrees: Int,
    val mirrorHorizontally: Boolean
)

internal object CameraPreviewTransformPolicy {
    fun resolve(
        viewWidth: Int,
        viewHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        isFrontCamera: Boolean
    ): CameraPreviewTransform {
        require(viewWidth > 0 && viewHeight > 0)
        require(previewWidth > 0 && previewHeight > 0)

        val sensorOrientation = sensorOrientationDegrees.normalizedCardinalDegrees()
        val displayRotation = displayRotationDegrees.normalizedCardinalDegrees()
        val facingSign = if (isFrontCamera) 1 else -1
        val relativeRotation =
            (sensorOrientation - (displayRotation * facingSign) + 360) % 360
        val swapsDimensions = relativeRotation % 180 != 0

        val viewWidthFloat = viewWidth.toFloat()
        val viewHeightFloat = viewHeight.toFloat()
        val previewWidthFloat = previewWidth.toFloat()
        val previewHeightFloat = previewHeight.toFloat()

        val naturalSensorIsLandscape = sensorOrientation % 180 == 0
        val scaleToOriginalX: Float
        val scaleToOriginalY: Float
        if (naturalSensorIsLandscape) {
            scaleToOriginalX = if (!swapsDimensions) {
                viewWidthFloat / previewHeightFloat
            } else {
                viewWidthFloat / previewWidthFloat
            }
            scaleToOriginalY = if (!swapsDimensions) {
                viewHeightFloat / previewWidthFloat
            } else {
                viewHeightFloat / previewHeightFloat
            }
        } else {
            scaleToOriginalX = if (swapsDimensions) {
                viewWidthFloat / previewHeightFloat
            } else {
                viewWidthFloat / previewWidthFloat
            }
            scaleToOriginalY = if (swapsDimensions) {
                viewHeightFloat / previewWidthFloat
            } else {
                viewHeightFloat / previewHeightFloat
            }
        }

        val aspectFillScale = max(scaleToOriginalX, scaleToOriginalY)
        val matrixScaleX: Float
        val matrixScaleY: Float
        if (swapsDimensions) {
            matrixScaleX = aspectFillScale / scaleToOriginalX
            matrixScaleY = aspectFillScale / scaleToOriginalY
        } else {
            matrixScaleX =
                viewHeightFloat / viewWidthFloat / scaleToOriginalY * aspectFillScale
            matrixScaleY =
                viewWidthFloat / viewHeightFloat / scaleToOriginalX * aspectFillScale
        }

        return CameraPreviewTransform(
            scaleX = matrixScaleX,
            scaleY = matrixScaleY,
            displayCompensationDegrees = -displayRotation.toFloat(),
            relativeRotationDegrees = relativeRotation,
            mirrorHorizontally = isFrontCamera
        )
    }
}

private fun Int.normalizedCardinalDegrees(): Int = ((this % 360) + 360) % 360
