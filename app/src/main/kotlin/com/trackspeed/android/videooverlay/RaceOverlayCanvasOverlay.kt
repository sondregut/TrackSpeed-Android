package com.trackspeed.android.videooverlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@UnstableApi
class RaceOverlayCanvasOverlay(
    private val snapshot: VideoOverlaySnapshot
) : CanvasOverlay(true) {

    private val renderer = RaceOverlayCanvasRenderer()

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val timeSeconds = presentationTimeUs / 1_000_000.0
        renderer.draw(canvas, snapshot, timeSeconds)
    }
}

private class RaceOverlayCanvasRenderer {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val rect = RectF()

    fun draw(canvas: Canvas, snapshot: VideoOverlaySnapshot, currentTimeSeconds: Double) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return

        val scale = min(width, height) / 390f
        val margin = max(16f * scale, 10f)
        val state = snapshot.frameState(currentTimeSeconds)

        drawTopStack(canvas, width, margin, scale, state, snapshot.readyLabel)
        drawBottomLeft(canvas, height, margin, scale, state)
        drawWatermark(canvas, width, height, margin, scale)
    }

    private fun drawTopStack(
        canvas: Canvas,
        width: Float,
        margin: Float,
        scale: Float,
        state: RaceOverlayFrameState,
        readyLabel: String
    ) {
        val timerText = when (state.phase) {
            RaceOverlayPhase.READY -> readyLabel
            RaceOverlayPhase.RUNNING,
            RaceOverlayPhase.FINISHED -> formatOverlayTime(state.displayedTimeSeconds)
        }
        val timerColor = when (state.phase) {
            RaceOverlayPhase.READY -> Color.argb(132, 20, 24, 32)
            RaceOverlayPhase.RUNNING -> Color.argb(216, 10, 132, 255)
            RaceOverlayPhase.FINISHED -> Color.argb(216, 34, 197, 94)
        }

        val timerHeight = drawPill(
            canvas = canvas,
            text = timerText,
            centerX = width / 2f,
            top = margin,
            textSize = if (state.phase == RaceOverlayPhase.READY) 18f * scale else 28f * scale,
            backgroundColor = timerColor,
            textColor = Color.WHITE,
            horizontalPadding = 18f * scale,
            verticalPadding = 7f * scale,
            uppercase = false
        )

        val speed = state.speedDisplay
        if (state.phase != RaceOverlayPhase.READY && !speed.isNullOrBlank()) {
            drawPill(
                canvas = canvas,
                text = speed,
                centerX = width / 2f,
                top = margin + timerHeight + 8f * scale,
                textSize = 16f * scale,
                backgroundColor = Color.argb(132, 20, 24, 32),
                textColor = Color.WHITE,
                horizontalPadding = 14f * scale,
                verticalPadding = 5f * scale,
                uppercase = false
            )
        }
    }

    private fun drawBottomLeft(
        canvas: Canvas,
        height: Float,
        margin: Float,
        scale: Float,
        state: RaceOverlayFrameState
    ) {
        var bottom = height - margin
        state.runTypeLabel?.takeIf { it.isNotBlank() }?.let { label ->
            bottom -= drawPillFromBottom(
                canvas = canvas,
                text = label,
                left = margin,
                bottom = bottom,
                textSize = 12f * scale,
                backgroundColor = Color.argb(132, 20, 24, 32),
                textColor = Color.WHITE,
                horizontalPadding = 10f * scale,
                verticalPadding = 5f * scale
            ) + 7f * scale
        }

        state.visibleSplits.asReversed().forEach { split ->
            bottom -= drawPillFromBottom(
                canvas = canvas,
                text = "${split.label}: ${formatOverlayTime(split.raceTimeSeconds)}",
                left = margin,
                bottom = bottom,
                textSize = 12f * scale,
                backgroundColor = Color.argb(216, 10, 132, 255),
                textColor = Color.WHITE,
                horizontalPadding = 10f * scale,
                verticalPadding = 5f * scale
            ) + 7f * scale
        }
    }

    private fun drawWatermark(canvas: Canvas, width: Float, height: Float, margin: Float, scale: Float) {
        val text = "TrackSpeed"
        val icon = max(22f * scale, 16f)
        val textSize = max(14f * scale, 10f)
        textPaint.textSize = textSize
        textPaint.color = Color.argb(242, 255, 255, 255)
        textPaint.textAlign = Paint.Align.LEFT
        val textWidth = textPaint.measureText(text)
        val gap = 7f * scale
        val totalWidth = icon + gap + textWidth
        val left = width - margin - totalWidth
        val top = height - margin - icon

        fillPaint.color = Color.argb(220, 10, 132, 255)
        rect.set(left, top, left + icon, top + icon)
        canvas.drawRoundRect(rect, 5f * scale, 5f * scale, fillPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = icon * 0.55f
        canvas.drawText("T", left + icon / 2f, top + icon * 0.68f, textPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = textSize
        canvas.drawText(text, left + icon + gap, top + icon * 0.68f, textPaint)
    }

    private fun drawPill(
        canvas: Canvas,
        text: String,
        centerX: Float,
        top: Float,
        textSize: Float,
        backgroundColor: Int,
        textColor: Int,
        horizontalPadding: Float,
        verticalPadding: Float,
        uppercase: Boolean
    ): Float {
        textPaint.textSize = max(textSize, 8f)
        textPaint.color = textColor
        textPaint.textAlign = Paint.Align.CENTER
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val pillWidth = textPaint.measureText(text) + horizontalPadding * 2f
        val pillHeight = textHeight + verticalPadding * 2f
        val left = centerX - pillWidth / 2f

        fillPaint.color = backgroundColor
        rect.set(left, top, left + pillWidth, top + pillHeight)
        canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, fillPaint)
        strokePaint.color = Color.argb(44, 255, 255, 255)
        strokePaint.strokeWidth = max(1f, textSize * 0.04f)
        canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, strokePaint)
        canvas.drawText(
            if (uppercase) text.uppercase(Locale.US) else text,
            centerX,
            top + verticalPadding - metrics.ascent,
            textPaint
        )
        return pillHeight
    }

    private fun drawPillFromBottom(
        canvas: Canvas,
        text: String,
        left: Float,
        bottom: Float,
        textSize: Float,
        backgroundColor: Int,
        textColor: Int,
        horizontalPadding: Float,
        verticalPadding: Float
    ): Float {
        textPaint.textSize = max(textSize, 8f)
        textPaint.color = textColor
        textPaint.textAlign = Paint.Align.LEFT
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val pillWidth = textPaint.measureText(text) + horizontalPadding * 2f
        val pillHeight = textHeight + verticalPadding * 2f
        val top = bottom - pillHeight

        fillPaint.color = backgroundColor
        rect.set(left, top, left + pillWidth, bottom)
        canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, fillPaint)
        strokePaint.color = Color.argb(44, 255, 255, 255)
        strokePaint.strokeWidth = max(1f, textSize * 0.04f)
        canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, strokePaint)
        canvas.drawText(text, left + horizontalPadding, top + verticalPadding - metrics.ascent, textPaint)
        return pillHeight
    }
}
