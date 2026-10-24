package com.trackspeed.android.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Density

/**
 * A single 360px logical canvas scales as one layer for preview AND bitmap export.
 * Export type remains fixed, while surrounding native controls retain system font scaling.
 * This does not attach, capture, write or recycle a bitmap; capture ownership stays unchanged.
 */
@Composable
internal fun ShareImageCanvas(
    format: RunImageFormat,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(modifier = modifier.aspectRatio(format.aspectRatio), content = {
        CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) { Box { content() } }
    }) { measurables, constraints ->
        val width = constraints.constrainWidth(360)
        val height = constraints.constrainHeight((width / format.aspectRatio).toInt())
        val canvas = measurables.single().measure(Constraints.fixed(360, format.designHeight))
        layout(width, height) {
            canvas.placeWithLayer(0, 0) {
                scaleX = width / 360f
                scaleY = height.toFloat() / format.designHeight
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
