package com.trackspeed.android.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Burns a gate line directly into a thumbnail bitmap (matching iOS behavior).
 * Two-layer vertical line: black shadow + accent color core.
 */
object ThumbnailUtils {
    private const val BURN_SHADOW_WIDTH = 6f
    private const val BURN_CORE_WIDTH = 4f

    fun burnGateLine(source: Bitmap, normalizedX: Float, accentColor: Int): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val w = mutable.width.toFloat()
        val h = mutable.height.toFloat()

        val halfShadow = BURN_SHADOW_WIDTH / 2f
        val lineX = normalizedX.coerceIn(0f, 1f) * w
        val clampedX = lineX.coerceIn(halfShadow, w - halfShadow)

        // Shadow layer (black @ 50%)
        val shadowPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            alpha = 128
            strokeWidth = BURN_SHADOW_WIDTH
        }
        canvas.drawLine(clampedX, 0f, clampedX, h, shadowPaint)

        // Core layer (accent @ 90%)
        val corePaint = Paint().apply {
            color = accentColor
            alpha = 230
            strokeWidth = BURN_CORE_WIDTH
        }
        canvas.drawLine(clampedX, 0f, clampedX, h, corePaint)

        return mutable
    }
}
