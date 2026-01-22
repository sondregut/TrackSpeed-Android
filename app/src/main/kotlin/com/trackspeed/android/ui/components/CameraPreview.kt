package com.trackspeed.android.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Camera preview with gate line overlay.
 *
 * Features:
 * - Live camera preview via SurfaceView
 * - Draggable gate line
 * - FPS display
 * - Detection state indicator
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    gatePosition: Float,
    onGatePositionChanged: (Float) -> Unit,
    fps: Int,
    isCalibrating: Boolean,
    isArmed: Boolean,
    onSurfaceReady: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    Box(modifier = modifier) {
        // Camera SurfaceView
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onSurfaceReady(holder)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            // Handle size changes if needed
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            onSurfaceDestroyed()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gate line overlay
        GateLineOverlay(
            gatePosition = gatePosition,
            onGatePositionChanged = onGatePositionChanged,
            isCalibrating = isCalibrating,
            isArmed = isArmed,
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
            isCalibrating = isCalibrating,
            isArmed = isArmed,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
    }
}

@Composable
private fun GateLineOverlay(
    gatePosition: Float,
    onGatePositionChanged: (Float) -> Unit,
    isCalibrating: Boolean,
    isArmed: Boolean,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }

    val lineColor = when {
        isCalibrating -> Color.Yellow
        isArmed -> Color.Green
        else -> Color.Red
    }

    val lineAlpha = if (isDragging) 1f else 0.8f

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Tap to set gate position
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

        // Handle indicator (circle in center)
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

        // Direction arrows when dragging
        if (isDragging) {
            val arrowSize = 12f
            // Left arrow
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
            // Right arrow
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
        fps >= 200 -> Color(0xFF4CAF50) // Green
        fps >= 100 -> Color(0xFFFFEB3B) // Yellow
        fps >= 60 -> Color(0xFFFF9800)  // Orange
        else -> Color(0xFFF44336)        // Red
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
    isCalibrating: Boolean,
    isArmed: Boolean,
    modifier: Modifier = Modifier
) {
    val (text, color) = when {
        isCalibrating -> "CALIBRATING" to Color.Yellow
        isArmed -> "ARMED" to Color.Green
        else -> "NOT READY" to Color.Red
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
            // Status dot
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
