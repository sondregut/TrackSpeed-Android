package com.trackspeed.android.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.trackspeed.android.R
import com.trackspeed.android.ui.util.formatTime
import androidx.compose.ui.window.DialogProperties

/**
 * Data class representing a thumbnail that can be expanded.
 */
data class ExpandedThumbnail(
    val bitmap: Bitmap,
    val gatePosition: Float? = null,
    val crossingVelocity: Float? = null,
    val originalTimeSeconds: Double? = null,
    val onTimeAdjusted: ((Double) -> Unit)? = null
)

private const val WORK_W = 160

/**
 * Fullscreen overlay dialog for viewing a thumbnail at full size with a gate line overlay.
 * When crossingVelocity is provided (Replica detection), the gate line is draggable
 * and shows a corrected time estimate.
 */
@Composable
fun ThumbnailViewerDialog(
    thumbnail: ExpandedThumbnail?,
    onDismiss: () -> Unit
) {
    if (thumbnail != null) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                val isDraggable = thumbnail.crossingVelocity != null &&
                    thumbnail.originalTimeSeconds != null &&
                    thumbnail.gatePosition != null

                if (isDraggable) {
                    DraggableGateViewer(
                        thumbnail = thumbnail,
                        onDismiss = onDismiss
                    )
                } else {
                    StaticGateViewer(
                        thumbnail = thumbnail
                    )
                }
            }
        }
    }
}

@Composable
private fun StaticGateViewer(thumbnail: ExpandedThumbnail) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                // Consume click
            }
    ) {
        Image(
            bitmap = thumbnail.bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.thumbnail_viewer_cd),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        )

        if (thumbnail.gatePosition != null) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val x = size.width * thumbnail.gatePosition
                drawLine(
                    color = Color.Red.copy(alpha = 0.8f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 3f
                )
            }
        }
    }
}

@Composable
private fun DraggableGateViewer(
    thumbnail: ExpandedThumbnail,
    onDismiss: () -> Unit
) {
    val originalGatePos = thumbnail.gatePosition ?: 0.5f
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var imageWidthPx by remember { mutableFloatStateOf(1f) }
    var committed by remember { mutableStateOf(false) }

    val currentGatePos = (originalGatePos + dragOffsetPx / imageWidthPx).coerceIn(0f, 1f)
    val offsetFraction = currentGatePos - originalGatePos
    val offsetPercent = offsetFraction * 100f

    // Calculate corrected time
    val velocity = thumbnail.crossingVelocity!!
    val originalTime = thumbnail.originalTimeSeconds!!
    val deltaPixels = offsetFraction * WORK_W
    val timeDelta = if (velocity != 0f) deltaPixels / velocity else 0f
    val correctedTime = originalTime + timeDelta

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                // Consume click
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Image with draggable gate line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .onSizeChanged { imageWidthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        dragOffsetPx += dragAmount
                    }
                }
        ) {
            Image(
                bitmap = thumbnail.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.thumbnail_viewer_cd),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            )

            Canvas(modifier = Modifier.matchParentSize()) {
                // Original gate line (dimmed)
                val origX = size.width * originalGatePos
                drawLine(
                    color = Color.Red.copy(alpha = 0.3f),
                    start = Offset(origX, 0f),
                    end = Offset(origX, size.height),
                    strokeWidth = 2f
                )

                // Dragged gate line
                val x = size.width * currentGatePos
                drawLine(
                    color = Color.Red.copy(alpha = 0.9f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 3f
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Offset and corrected time display
        Text(
            text = "Gate offset: %+.1f%%".format(offsetPercent),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )

        Text(
            text = formatTime(correctedTime) + "s",
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = Color.White
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Reset and Done buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = {
                dragOffsetPx = 0f
            }) {
                Text("Reset", color = Color.White.copy(alpha = 0.7f))
            }

            TextButton(onClick = {
                thumbnail.onTimeAdjusted?.invoke(correctedTime)
                onDismiss()
            }) {
                Text("Done", color = Color(0xFF0A84FF))
            }
        }
    }
}
