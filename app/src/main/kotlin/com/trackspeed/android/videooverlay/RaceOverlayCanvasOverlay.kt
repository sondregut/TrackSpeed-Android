package com.trackspeed.android.videooverlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay

@UnstableApi
class RaceOverlayCanvasOverlay(private val snapshot: VideoOverlaySnapshot) : CanvasOverlay(true) {
    private val renderer = RaceOverlayCanvasRenderer()
    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        // Media3 reuses this transparent bitmap between frames. Clear the overlay
        // layer only; clearing the shared renderer would erase preview footage.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        renderer.draw(canvas, snapshot, presentationTimeUs / 1_000_000.0)
    }
}

/** One renderer for the in-app preview, Media3 export, and native render tests. */
class RaceOverlayCanvasRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val mono = Typeface.create("monospace", Typeface.BOLD)

    fun draw(canvas: Canvas, snapshot: VideoOverlaySnapshot, currentTimeSeconds: Double, guides: Boolean = false,
        width: Float = canvas.width.toFloat(), height: Float = canvas.height.toFloat()) {
        if (width <= 0 || height <= 0) return
        val state = snapshot.frameState(currentTimeSeconds)
        val prefs = snapshot.preferences
        val rows = state.visibleSplits
        // Reserve speed height before the finish to avoid jumping over the athlete.
        val speedRow = snapshot.showSpeed && snapshot.speedDisplay != null
        val layout = overlayCardLayout(width, height, prefs,
            speedRow, rows.size, !state.runTypeLabel.isNullOrBlank(), snapshot.sourceAspectRatio)
        val r = layout.card
        val scale = layout.scale
        if (guides) drawGuides(canvas, overlaySafeRect(width,height,prefs.socialSafe), width, height)
        canvas.save()
        canvas.translate(r.left, r.top)
        canvas.scale(scale, scale)
        val h = r.height / scale
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(224, 12, 18, 29)
        canvas.drawRoundRect(RectF(0f, 0f, r.width / scale, h), 16f, 16f, paint)
        paint.color = Color.rgb(66, 168, 255)
        canvas.drawRoundRect(RectF(0f, 16f, 3f, h - 16f), 1.5f, 1.5f, paint)
        if (layout.compactHeader) {
            drawCompactHeader(canvas, snapshot, state, speedRow)
            canvas.restore()
            return
        }
        text(canvas, "TRACKSPEED", 16f, 23f, 10f, Color.rgb(160, 200, 237), maxWidth = 218f)
        var y = 34f
        if (prefs.showTime) {
            val time = if (state.phase == RaceOverlayPhase.READY) snapshot.readyLabel
                else formatOverlayTime(state.displayedTimeSeconds) + " s"
            text(canvas, time, 16f, y + 36f, 36f, Color.WHITE, typeface = mono, maxWidth = 218f)
            y += 51f
        }
        state.runTypeLabel?.takeIf { it.isNotBlank() }?.let {
            text(canvas, it, 16f, y + 13f, 12f, Color.rgb(204, 216, 230), maxWidth = 218f)
            y += 20f
        }
        if (speedRow) {
            text(canvas, snapshot.averageSpeedLabel, 16f, y + 17f, 9f, Color.rgb(160, 177, 197), maxWidth = 86f)
            text(canvas, state.speedDisplay ?: "—", 234f, y + 19f, 19f, Color.WHITE,
                align = Paint.Align.RIGHT, typeface = mono, maxWidth = 120f)
            y += 30f
        }
        rows.forEach { split ->
            val segment = prefs.splitMode == OverlaySplitMode.SEGMENTS
            val label = if (segment) split.segmentLabel else split.label
            val seconds = if (segment) split.segmentTimeSeconds else split.raceTimeSeconds
            text(canvas, label, 16f, y + 15f, 10f, Color.rgb(160, 177, 197), maxWidth = 112f)
            text(canvas, formatOverlayTime(seconds) + " s", 234f, y + 15f, 13f, Color.WHITE,
                align = Paint.Align.RIGHT, typeface = mono, maxWidth = 98f)
            y += 21f
        }
        canvas.restore()
    }

    private fun drawCompactHeader(canvas: Canvas, snapshot: VideoOverlaySnapshot, state: RaceOverlayFrameState, speedRow: Boolean) {
        text(canvas, "TRACKSPEED", 16f, 21f, 9f, Color.rgb(160,200,237), maxWidth=298f)
        var y=28f
        if (snapshot.preferences.showTime) {
            val time=if(state.phase==RaceOverlayPhase.READY) snapshot.readyLabel else formatOverlayTime(state.displayedTimeSeconds)+" s"
            text(canvas,time,16f,y+34f,34f,Color.WHITE,typeface=mono,maxWidth=if(speedRow) 172f else 298f)
        }
        if(speedRow) {
            text(canvas,snapshot.averageSpeedLabel,314f,y+11f,9f,Color.rgb(160,177,197),align=Paint.Align.RIGHT,maxWidth=116f)
            text(canvas,state.speedDisplay ?: "—",314f,y+33f,18f,Color.WHITE,align=Paint.Align.RIGHT,typeface=mono,maxWidth=116f)
        }
        if(snapshot.preferences.showTime || speedRow) y+=45f
        state.runTypeLabel?.takeIf{it.isNotBlank()}?.let {
            text(canvas,it,16f,y+12f,11f,Color.rgb(204,216,230),maxWidth=298f);y+=17f
        }
        val rows=state.visibleSplits
        val cellWidth=298f/rows.size.coerceAtLeast(1)
        rows.forEachIndexed { index, split ->
            val segment=snapshot.preferences.splitMode==OverlaySplitMode.SEGMENTS
            val label=if(segment) split.segmentLabel else split.label
            val seconds=if(segment) split.segmentTimeSeconds else split.raceTimeSeconds
            val x=16f+index*cellWidth
            text(canvas,label,x,y+10f,9f,Color.rgb(160,177,197),maxWidth=cellWidth-6)
            text(canvas,formatOverlayTime(seconds)+" s",x,y+29f,13f,Color.WHITE,typeface=mono,maxWidth=cellWidth-6)
        }
    }

    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, typeface: Typeface = bold, maxWidth: Float) {
        paint.color = color; paint.style = Paint.Style.FILL; paint.textAlign = align
        paint.typeface = typeface; paint.textSize = size
        val width = paint.measureText(value)
        if (width > maxWidth) paint.textSize *= maxWidth / width
        canvas.drawText(value, x, baseline, paint)
    }

    private fun drawGuides(canvas: Canvas, safe: OverlayRect, width: Float, height: Float) {
        paint.style = Paint.Style.FILL; paint.color = Color.argb(70, 255, 40, 80)
        canvas.drawRect(0f, 0f, width, safe.top, paint)
        canvas.drawRect(0f, safe.bottom, width, height, paint)
        canvas.drawRect(0f, safe.top, safe.left, safe.bottom, paint)
        canvas.drawRect(safe.right, safe.top, width, safe.bottom, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.rgb(77, 232, 171)
        canvas.drawRect(safe.left, safe.top, safe.right, safe.bottom, paint)
        paint.style = Paint.Style.FILL
    }
}
