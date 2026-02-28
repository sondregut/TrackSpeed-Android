package com.trackspeed.android.ui.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.trackspeed.android.R
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
 * Configure the TextureView transform matrix.
 *
 * TextureView's internal SurfaceTexture transform already handles
 * sensor-to-display rotation. We only need to apply front camera mirror.
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
    val cx = viewWidth / 2f
    val cy = viewHeight / 2f

    // TextureView's internal SurfaceTexture transform already handles
    // sensor-to-display rotation. Only apply front camera mirror.
    if (isFrontCamera) {
        matrix.postScale(-1f, 1f, cx, cy)
    }

    Log.d("CameraPreview", "Transform: sensor=$sensorOrientation view=${viewWidth}x${viewHeight} front=$isFrontCamera")
    textureView.setTransform(matrix)
}

@Composable
private fun GateLineOverlay(
    gatePosition: Float,
    onGatePositionChanged: (Float) -> Unit,
    detectionState: PhotoFinishDetector.State,
    modifier: Modifier = Modifier
) {
    val lineColor = when (detectionState) {
        PhotoFinishDetector.State.UNSTABLE -> Color.Yellow
        PhotoFinishDetector.State.NO_ATHLETE -> Color.White
        PhotoFinishDetector.State.ATHLETE_TOO_FAR -> Color(0xFFFF9800)
        PhotoFinishDetector.State.READY -> Color.Green
        PhotoFinishDetector.State.TRIGGERED -> Color.Red
        PhotoFinishDetector.State.COOLDOWN -> Color.Red
    }

    Canvas(modifier = modifier) {
        val x = size.width * gatePosition

        // Gate line (fixed at center)
        drawLine(
            color = lineColor.copy(alpha = 0.8f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 4f
        )
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
            text = stringResource(R.string.camera_fps_format, fps),
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
        PhotoFinishDetector.State.UNSTABLE -> stringResource(R.string.camera_status_hold_steady) to Color.Yellow
        PhotoFinishDetector.State.NO_ATHLETE -> stringResource(R.string.camera_status_ready) to Color.Green
        PhotoFinishDetector.State.ATHLETE_TOO_FAR -> stringResource(R.string.camera_status_too_far) to Color(0xFFFF9800)
        PhotoFinishDetector.State.READY -> stringResource(R.string.camera_status_ready) to Color.Green
        PhotoFinishDetector.State.TRIGGERED -> stringResource(R.string.camera_status_triggered) to Color.Red
        PhotoFinishDetector.State.COOLDOWN -> stringResource(R.string.camera_status_cooldown) to Color.Red
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
    message: String = stringResource(R.string.camera_not_available)
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
