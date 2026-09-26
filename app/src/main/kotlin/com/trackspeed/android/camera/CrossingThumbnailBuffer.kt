package com.trackspeed.android.camera

import android.graphics.Bitmap
import android.graphics.Matrix

private const val DEFAULT_MAX_FRAMES = 8
private const val THUMBNAIL_WIDTH = 160
private const val THUMBNAIL_HEIGHT = 120

internal fun closestTimestampIndex(timestamps: List<Long>, targetTimestampNanos: Long): Int? {
    if (timestamps.isEmpty()) return null
    return timestamps.indices.minByOrNull { index ->
        val timestamp = timestamps[index]
        if (timestamp >= targetTimestampNanos) {
            timestamp - targetTimestampNanos
        } else {
            targetTimestampNanos - timestamp
        }
    }
}

/**
 * Android currently has no live person-segmentation selector for crossing
 * review images. Match iOS's unsupported-device policy by saving the detector
 * trigger frame instead of reusing the interpolated timing frame, which can be
 * one frame early and show only an arm beyond the gate.
 */
internal fun reviewThumbnailTargetTimestamp(
    detectorTriggerFramePtsNanos: Long,
    detectorSelectedFramePtsNanos: Long,
    supportsLivePersonSelector: Boolean
): Long = if (supportsLivePersonSelector) {
    detectorSelectedFramePtsNanos
} else {
    detectorTriggerFramePtsNanos
}

/**
 * Keeps a tiny color rendering of the most recent camera frames. Detection can
 * select the previous sensor frame for a sub-frame crossing; this buffer lets
 * the saved result use that exact frame instead of whichever frame happened
 * to arrive by the time the UI collector handled the event.
 */
class CrossingThumbnailBuffer(
    private val maxFrames: Int = DEFAULT_MAX_FRAMES
) {
    private data class Entry(
        val timestampNanos: Long,
        val bitmap: Bitmap
    )

    private val lock = Any()
    private val frames = ArrayDeque<Entry>()

    fun reset() = synchronized(lock) {
        frames.clear()
    }

    fun appendFrame(
        frame: CameraManager.FrameData,
        orientationDegrees: Int,
        isFrontCamera: Boolean
    ) {
        val bitmap = frame.toColorThumbnail(orientationDegrees, isFrontCamera) ?: return
        synchronized(lock) {
            frames.addLast(Entry(frame.timestampNanos, bitmap))
            while (frames.size > maxFrames.coerceAtLeast(1)) {
                frames.removeFirst()
            }
        }
    }

    fun bitmapClosestTo(targetTimestampNanos: Long?): Bitmap? = synchronized(lock) {
        if (frames.isEmpty()) return@synchronized null
        if (targetTimestampNanos == null) return@synchronized frames.last().bitmap
        val entries = frames.toList()
        val index = closestTimestampIndex(entries.map(Entry::timestampNanos), targetTimestampNanos)
            ?: return@synchronized entries.last().bitmap
        entries[index].bitmap
    }
}

private fun CameraManager.FrameData.toColorThumbnail(
    orientationDegrees: Int,
    isFrontCamera: Boolean
): Bitmap? = runCatching {
    val scaleX = width.toFloat() / THUMBNAIL_WIDTH.toFloat()
    val scaleY = height.toFloat() / THUMBNAIL_HEIGHT.toFloat()
    val pixels = IntArray(THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT)

    for (sampleY in 0 until THUMBNAIL_HEIGHT) {
        val sourceY = (sampleY * scaleY).toInt().coerceIn(0, height - 1)
        for (sampleX in 0 until THUMBNAIL_WIDTH) {
            val sourceX = (sampleX * scaleX).toInt().coerceIn(0, width - 1)
            val yIndex = sourceY * rowStride + sourceX
            val luminance = yPlane.getOrNull(yIndex)?.toInt()?.and(0xFF) ?: 0

            val uvIndex = (sourceY / 2) * uvRowStride + (sourceX / 2) * uvPixelStride
            val u = (uPlane.getOrNull(uvIndex)?.toInt()?.and(0xFF) ?: 128) - 128
            val v = (vPlane.getOrNull(uvIndex)?.toInt()?.and(0xFF) ?: 128) - 128

            val red = (luminance + 1.370705f * v).toInt().coerceIn(0, 255)
            val green = (luminance - 0.337633f * u - 0.698001f * v).toInt().coerceIn(0, 255)
            val blue = (luminance + 1.732446f * u).toInt().coerceIn(0, 255)
            pixels[sampleY * THUMBNAIL_WIDTH + sampleX] =
                (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    val raw = Bitmap.createBitmap(
        pixels,
        THUMBNAIL_WIDTH,
        THUMBNAIL_HEIGHT,
        Bitmap.Config.ARGB_8888
    )
    if (orientationDegrees == 0 && !isFrontCamera) return@runCatching raw

    val matrix = Matrix().apply {
        if (orientationDegrees != 0) postRotate(orientationDegrees.toFloat())
        if (isFrontCamera) postScale(-1f, 1f)
    }
    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}.getOrNull()
