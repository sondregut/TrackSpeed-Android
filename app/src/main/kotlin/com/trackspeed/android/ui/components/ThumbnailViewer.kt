package com.trackspeed.android.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Data class representing a thumbnail that can be expanded.
 */
data class ExpandedThumbnail(
    val bitmap: Bitmap,
    val gatePosition: Float
)

/**
 * Fullscreen overlay dialog for viewing a thumbnail at full size with a gate line overlay.
 *
 * @param thumbnail The thumbnail data to display, or null to hide the dialog.
 * @param onDismiss Callback when the dialog is dismissed (tap outside or back press).
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            // Consume click on the image so it doesn't dismiss
                        }
                ) {
                    Image(
                        bitmap = thumbnail.bitmap.asImageBitmap(),
                        contentDescription = "Expanded crossing frame",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                    )

                    // Gate line overlay
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                    ) {
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
    }
}
