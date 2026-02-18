package com.trackspeed.android.ui.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.trackspeed.android.detection.PhotoFinishDetector
import android.util.Log

/**
 * Camera preview with gate line overlay using TextureView for proper portrait rotation.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    gatePosition: Float,
    onGatePositionChanged: (Float) -> Unit,
    fps: Int,
    detectionState: PhotoFinishDetector.State,
    sensorOrientation: Int = 90,
    isFrontCamera: Boolean = false,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    // Keep latest values accessible from TextureView callbacks
    val currentSensorOrientation by rememberUpdatedState(sensorOrientation)
    val currentIsFrontCamera by rememberUpdatedState(isFrontCamera)
    val currentOnSurfaceReady by rememberUpdatedState(onSurfaceReady)
    val currentOnSurfaceDestroyed by rememberUpdatedState(onSurfaceDestroyed)

    Box(modifier = modifier) {
        // Camera TextureView with rotation
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            configureTransform(this@apply, width, height, currentSensorOrientation, currentIsFrontCamera)
                            val surface = Surface(surfaceTexture)
                            currentOnSurfaceReady(surface)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            configureTransform(this@apply, width, height, currentSensorOrientation, currentIsFrontCamera)
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            currentOnSurfaceDestroyed()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                    }
                }
            },
            update = { textureView ->
                // Re-apply transform when sensor orientation or camera changes
                if (textureView.width > 0 && textureView.height > 0) {
                    configureTransform(textureView, textureView.width, textureView.height, currentSensorOrientation, currentIsFrontCamera)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gate line overlay
        GateLineOverlay(
            gatePosition = gatePosition,
            onGatePositionChanged = onGatePositionChanged,
            detectionState = detectionState,
            modifier = Modifier.fillMaxSize()
        )

        // FPS indicator
        FpsIndicator(
            fps = fps,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        )

        // Status indicator
        StatusIndicator(
            detectionState = detectionState,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
    }
}

/**
 * Configure the TextureView transform matrix to display the camera preview
 * in the correct portrait orientation.
 *
 * Camera2 delivers frames in the sensor's native (landscape) orientation.
 * SENSOR_ORIENTATION is the CW angle through which the output image needs to be
 * rotated to be upright on the device screen in its natural (portrait) orientation.
 *
 * Order matters: rotate first, then scale to fix aspect ratio.
 */
private fun configureTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    sensorOrientation: Int,
    isFrontCamera: Boolean
) {
    if (viewWidth == 0 || viewHeight == 0) return

    val matrix = Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    // Step 1: Rotate by sensorOrientation to make the image upright.
    // Per Android docs, sensorOrientation is the CW rotation needed to display upright.
    // Back camera: typically 90° CW, Front camera: typically 270° CW.
    if (sensorOrientation != 0) {
        matrix.postRotate(sensorOrientation.toFloat(), centerX, centerY)
    }

    // Step 2: Fix aspect ratio. TextureView stretches the camera buffer (landscape)
    // to fill the view (portrait). After rotation, we need to scale to compensate.
    if (sensorOrientation == 90 || sensorOrientation == 270) {
        val scaleX = viewHeight.toFloat() / viewWidth
        val scaleY = viewWidth.toFloat() / viewHeight
        matrix.postScale(scaleX, scaleY, centerX, centerY)
    }

    // Step 3: Mirror for front camera (selfie mirror effect)
    if (isFrontCamera) {
        matrix.postScale(-1f, 1f, centerX, centerY)
    }

    Log.d("CameraPreview", "Transform: sensor=$sensorOrientation, view=${viewWidth}x${viewHeight}, front=$isFrontCamera")
    textureView.setTransform(matrix)
}

@Composable
private fun GateLineOverlay(
    gatePosition: Float,
    onGatePositionChanged: (Float) -> Unit,
    detectionState: PhotoFinishDetector.State,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }

    val lineColor = when (detectionState) {
        PhotoFinishDetector.State.UNSTABLE -> Color.Yellow
        PhotoFinishDetector.State.NO_ATHLETE -> Color.White
        PhotoFinishDetector.State.ATHLETE_TOO_FAR -> Color(0xFFFF9800)
        PhotoFinishDetector.State.READY -> Color.Green
        PhotoFinishDetector.State.TRIGGERED -> Color.Red
        PhotoFinishDetector.State.COOLDOWN -> Color.Red
    }

    val lineAlpha = if (isDragging) 1f else 0.8f

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newPosition = (offset.x / size.width).coerceIn(0.05f, 0.95f)
                    onGatePositionChanged(newPosition)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onHorizontalDrag = { change, _ ->
                        val newPosition = (change.position.x / size.width).coerceIn(0.05f, 0.95f)
                        onGatePositionChanged(newPosition)
                    }
                )
            }
    ) {
        val x = size.width * gatePosition

        // Main gate line
        drawLine(
            color = lineColor.copy(alpha = lineAlpha),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = if (isDragging) 6f else 4f
        )

        // Handle indicator
        val handleY = size.height / 2
        val handleRadius = if (isDragging) 24f else 16f

        drawCircle(
            color = lineColor.copy(alpha = lineAlpha),
            radius = handleRadius,
            center = Offset(x, handleY),
            style = Stroke(width = 3f)
        )

        drawCircle(
            color = lineColor.copy(alpha = 0.3f),
            radius = handleRadius - 3f,
            center = Offset(x, handleY)
        )

        if (isDragging) {
            val arrowSize = 12f
            drawLine(
                color = Color.White.copy(alpha = 0.6f),
                start = Offset(x - 40f, handleY),
                end = Offset(x - 40f - arrowSize, handleY - arrowSize),
                strokeWidth = 2f
            )
            drawLine(
                color = Color.White.copy(alpha = 0.6f),
                start = Offset(x - 40f, handleY),
                end = Offset(x - 40f - arrowSize, handleY + arrowSize),
                strokeWidth = 2f
            )
            drawLine(
                color = Color.White.copy(alpha = 0.6f),
                start = Offset(x + 40f, handleY),
                end = Offset(x + 40f + arrowSize, handleY - arrowSize),
                strokeWidth = 2f
            )
            drawLine(
                color = Color.White.copy(alpha = 0.6f),
                start = Offset(x + 40f, handleY),
                end = Offset(x + 40f + arrowSize, handleY + arrowSize),
                strokeWidth = 2f
            )
        }
    }
}

@Composable
private fun FpsIndicator(
    fps: Int,
    modifier: Modifier = Modifier
) {
    val fpsColor = when {
        fps >= 100 -> Color(0xFF4CAF50)
        fps >= 50 -> Color(0xFFFFEB3B)
        fps >= 25 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Box(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "$fps FPS",
            style = MaterialTheme.typography.labelMedium,
            color = fpsColor
        )
    }
}

@Composable
private fun StatusIndicator(
    detectionState: PhotoFinishDetector.State,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (detectionState) {
        PhotoFinishDetector.State.UNSTABLE -> "HOLD STEADY" to Color.Yellow
        PhotoFinishDetector.State.NO_ATHLETE -> "READY" to Color.Green
        PhotoFinishDetector.State.ATHLETE_TOO_FAR -> "TOO FAR" to Color(0xFFFF9800)
        PhotoFinishDetector.State.READY -> "READY" to Color.Green
        PhotoFinishDetector.State.TRIGGERED -> "TRIGGERED" to Color.Red
        PhotoFinishDetector.State.COOLDOWN -> "COOLDOWN" to Color.Red
    }

    Box(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = color)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

/**
 * Placeholder preview for when camera is not available.
 */
@Composable
fun CameraPreviewPlaceholder(
    modifier: Modifier = Modifier,
    message: String = "Camera not available"
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}
