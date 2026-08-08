package com.trackspeed.android.ui.screens.race

import android.graphics.Bitmap
import android.graphics.Matrix
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.data.repository.LocalGateFrameSnapshot
import kotlin.math.roundToInt

private const val PREBUFFER_NANOS = 350_000_000L
private const val POSTROLL_NANOS = 500_000_000L
private const val MAX_RETAINED_NANOS = PREBUFFER_NANOS + 200_000_000L
private const val PORTRAIT_THUMB_W = 90
private const val PORTRAIT_THUMB_H = 160

class LocalGateFrameBuffer {
    private val lock = Any()
    private val prebuffer = ArrayDeque<LocalGateFrameSnapshot>()
    private val eventPrebuffer = mutableListOf<LocalGateFrameSnapshot>()
    private val postroll = mutableListOf<LocalGateFrameSnapshot>()
    private var eventTimestampNanos: Long? = null
    private var capturingPostroll = false

    val isPostrollActive: Boolean
        get() = synchronized(lock) { capturingPostroll }

    fun reset() = synchronized(lock) {
        prebuffer.clear()
        eventPrebuffer.clear()
        postroll.clear()
        eventTimestampNanos = null
        capturingPostroll = false
    }

    fun markEvent(timestampNanos: Long) = synchronized(lock) {
        eventTimestampNanos = timestampNanos
        eventPrebuffer.clear()
        eventPrebuffer += prebuffer.filter { it.timestampNanos <= timestampNanos }
        if (eventPrebuffer.isEmpty()) {
            eventPrebuffer += prebuffer
        }
        postroll.clear()
        capturingPostroll = true
    }

    fun appendFrame(
        frame: CameraManager.FrameData,
        occupancy: Float,
        orientationDegrees: Int,
        isFrontCamera: Boolean
    ) {
        val bitmap = frame.toScrubberBitmap(orientationDegrees, isFrontCamera) ?: return
        val snapshot = buildSnapshot(frame, bitmap, occupancy.coerceIn(0f, 1f))

        synchronized(lock) {
            val eventTs = eventTimestampNanos
            if (eventTs != null && capturingPostroll) {
                if (snapshot.timestampNanos <= eventTs) {
                    if (eventPrebuffer.none { it.frameNumber == snapshot.frameNumber }) {
                        eventPrebuffer += snapshot
                    }
                } else {
                    postroll += snapshot
                    if (snapshot.timestampNanos - eventTs >= POSTROLL_NANOS) {
                        capturingPostroll = false
                    }
                }
                return
            }

            prebuffer.addLast(snapshot)
            val cutoff = snapshot.timestampNanos - MAX_RETAINED_NANOS
            while (prebuffer.size > 1 && prebuffer.first().timestampNanos < cutoff) {
                prebuffer.removeFirst()
            }
        }
    }

    fun snapshotEventFrames(): List<LocalGateFrameSnapshot> = synchronized(lock) {
        val frames = if (eventPrebuffer.isNotEmpty() || postroll.isNotEmpty()) {
            eventPrebuffer + postroll
        } else {
            prebuffer.toList()
        }
        frames.distinctBy { it.frameNumber }.sortedBy { it.timestampNanos }
    }
}

private fun buildSnapshot(
    frame: CameraManager.FrameData,
    bitmap: Bitmap,
    occupancy: Float
): LocalGateFrameSnapshot {
    val frameHeight = bitmap.height.coerceAtLeast(1)
    val runHeight = (frameHeight * occupancy).roundToInt().coerceIn(0, frameHeight)
    val runStart = ((frameHeight - runHeight) / 2).coerceAtLeast(0)
    val runEnd = (runStart + runHeight).coerceIn(runStart, frameHeight)
    val torsoTop = if (runHeight > 0) runStart else 0
    val torsoBottom = if (runHeight > 0) runEnd else 0

    return LocalGateFrameSnapshot(
        bitmap = bitmap,
        frameNumber = frame.frameIndex,
        timestampNanos = frame.timestampNanos,
        occupancy = occupancy,
        longestRun = runHeight,
        isTracking = occupancy >= 0.15f,
        torsoTop = torsoTop,
        torsoBottom = torsoBottom,
        frameHeight = frameHeight,
        runStartY = runStart,
        runEndY = runEnd
    )
}

private fun CameraManager.FrameData.toScrubberBitmap(
    orientationDegrees: Int,
    isFrontCamera: Boolean
): Bitmap? {
    return try {
        val isLandscape = width > height
        val rawW = if (isLandscape) PORTRAIT_THUMB_H else PORTRAIT_THUMB_W
        val rawH = if (isLandscape) PORTRAIT_THUMB_W else PORTRAIT_THUMB_H
        if (rawW <= 0 || rawH <= 0) return null

        val scaleX = width.toFloat() / rawW.toFloat()
        val scaleY = height.toFloat() / rawH.toFloat()
        val pixels = IntArray(rawW * rawH)
        for (dy in 0 until rawH) {
            val srcY = (dy * scaleY).toInt().coerceIn(0, height - 1)
            for (dx in 0 until rawW) {
                val srcX = (dx * scaleX).toInt().coerceIn(0, width - 1)
                val yIndex = srcY * rowStride + srcX
                val y = if (yIndex < yPlane.size) yPlane[yIndex].toInt() and 0xFF else 0

                val uvRow = srcY / 2
                val uvCol = srcX / 2
                val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride
                val u = if (uvIndex < uPlane.size) (uPlane[uvIndex].toInt() and 0xFF) - 128 else 0
                val v = if (uvIndex < vPlane.size) (vPlane[uvIndex].toInt() and 0xFF) - 128 else 0

                val r = (y + 1.370705f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.337633f * u - 0.698001f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.732446f * u).toInt().coerceIn(0, 255)
                pixels[dy * rawW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val raw = Bitmap.createBitmap(pixels, rawW, rawH, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        if (orientationDegrees != 0) {
            matrix.postRotate(orientationDegrees.toFloat())
        }
        if (isFrontCamera) {
            matrix.postScale(-1f, 1f)
        }
        if (orientationDegrees != 0 || isFrontCamera) {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } else {
            raw
        }
    } catch (_: Exception) {
        null
    }
}
