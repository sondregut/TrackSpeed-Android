package com.trackspeed.android.detection

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * CPU ring buffer for photo-finish slit-scan composite.
 *
 * Accumulates 1px vertical columns at the gate line to create a time-based image
 * where X-axis = time and Y-axis = vertical position. This produces a "photo finish"
 * style visualization showing the runner passing through the gate over time.
 *
 * Ported from iOS CompositeBuffer.swift.
 *
 * Thread-safe: all public methods are synchronized via [lock].
 * Called from camera thread (addSlit) and UI thread (getComposite).
 *
 * Storage layout: row-major, i.e. buffer[y * maxColumns + col] = luma for row y, column col.
 * This matches the iOS layout and allows efficient Bitmap creation.
 */
class CompositeBuffer(
    /** Frame height for this composite (number of rows per column). */
    val frameHeight: Int,
    private val config: Config = Config.DEFAULT
) {

    // ---- Types ----

    data class Config(
        /** Maximum columns to store (ring buffer capacity). At 240fps: 5000 ~ 20s. */
        val maxColumns: Int,

        /** Pre-roll columns before crossing (recent history to keep). */
        val preRollColumns: Int,

        /** Post-roll columns after crossing. */
        val postRollColumns: Int
    ) {
        companion object {
            val DEFAULT = Config(
                maxColumns = 5000,
                preRollColumns = 200,   // ~0.8s at 240fps
                postRollColumns = 100   // ~0.4s at 240fps
            )
        }
    }

    // ---- Properties ----

    private val lock = ReentrantLock()

    /** Row-major luma buffer: buffer[y * maxColumns + col]. Pre-allocated. */
    private val buffer = ByteArray(frameHeight * config.maxColumns) { 128.toByte() }

    /** Per-column timestamps (nanoseconds). */
    private val timestamps = LongArray(config.maxColumns)

    /** Write cursor in the ring buffer (next column to write). */
    private var writeIndex = 0

    /** Number of columns currently written (saturates at maxColumns). */
    private var columnCount = 0

    /** Whether the buffer is actively recording incoming slits. */
    private var isRecording = false

    /** Column index where a crossing was marked (ring-buffer index, or null). */
    private var crossingColumnIndex: Int? = null

    // ---- Public accessors (thread-safe) ----

    /** Number of columns currently stored. */
    val currentColumnCount: Int
        get() = lock.withLock { columnCount }

    /** Whether currently recording. */
    val isCapturing: Boolean
        get() = lock.withLock { isRecording }

    // ---- Recording control ----

    /** Start recording (call when armed / session starts). */
    fun startRecording() = lock.withLock {
        isRecording = true
        crossingColumnIndex = null
    }

    /** Stop recording. */
    fun stopRecording() = lock.withLock {
        isRecording = false
    }

    /** Mark the most recently written column as the crossing point. */
    fun markCrossing() = lock.withLock {
        crossingColumnIndex = (writeIndex - 1 + config.maxColumns) % config.maxColumns
    }

    // ---- Slit ingestion ----

    /**
     * Extract a 1px vertical column from a YUV Y-plane at [gateX] and append it.
     *
     * @param yPlane     Y (luminance) plane byte array.
     * @param width      Full frame width.
     * @param height     Full frame height (should match [frameHeight]).
     * @param rowStride  Row stride of the Y plane (may be > width due to padding).
     * @param gateX      Normalized gate position 0..1.
     * @param timestampNanos Frame timestamp in nanoseconds.
     */
    fun addSlit(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        gateX: Float,
        timestampNanos: Long
    ) = lock.withLock {
        if (!isRecording) return@withLock

        val gateColumn = (gateX * width).toInt().coerceIn(0, width - 1)
        val rowsToCopy = min(height, frameHeight)

        val colIndex = writeIndex

        // Copy the vertical column at gateColumn into the ring buffer (row-major).
        for (y in 0 until rowsToCopy) {
            val srcOffset = y * rowStride + gateColumn
            val dstOffset = y * config.maxColumns + colIndex
            buffer[dstOffset] = yPlane[srcOffset]
        }

        timestamps[colIndex] = timestampNanos

        // Advance ring-buffer cursor.
        writeIndex = (writeIndex + 1) % config.maxColumns
        columnCount = min(columnCount + 1, config.maxColumns)
    }

    /**
     * Append a pre-extracted vertical slit (one luma byte per row).
     *
     * @param slit ByteArray of length [frameHeight].
     * @param timestampNanos Frame timestamp in nanoseconds.
     */
    fun appendSlit(slit: ByteArray, timestampNanos: Long) = lock.withLock {
        if (!isRecording) return@withLock
        if (slit.size != frameHeight) return@withLock

        val colIndex = writeIndex

        for (y in 0 until frameHeight) {
            buffer[y * config.maxColumns + colIndex] = slit[y]
        }

        timestamps[colIndex] = timestampNanos
        writeIndex = (writeIndex + 1) % config.maxColumns
        columnCount = min(columnCount + 1, config.maxColumns)
    }

    // ---- Export ----

    /**
     * Export the composite around a crossing as an ARGB_8888 [Bitmap].
     *
     * If a crossing was marked and [includePreRoll] is true, the export window is
     * centered around the crossing (preRoll + postRoll columns). Otherwise all
     * available columns are exported.
     *
     * Grayscale luma values are expanded to full ARGB (R=G=B=luma, A=0xFF).
     *
     * @return Bitmap or null if nothing has been recorded yet.
     */
    fun getComposite(includePreRoll: Boolean = true): Bitmap? = lock.withLock {
        if (columnCount == 0) return@withLock null

        val exportWidth: Int
        val startColumn: Int

        val crossingIdx = crossingColumnIndex
        if (crossingIdx != null && includePreRoll) {
            val preRoll = min(config.preRollColumns, columnCount)
            val postRoll = min(config.postRollColumns, columnCount - preRoll)
            exportWidth = preRoll + postRoll
            startColumn = (crossingIdx - preRoll + config.maxColumns) % config.maxColumns
        } else {
            exportWidth = columnCount
            startColumn = (writeIndex - columnCount + config.maxColumns) % config.maxColumns
        }

        if (exportWidth == 0) return@withLock null

        // Build ARGB pixel array (row-major, top-to-bottom, left-to-right).
        val pixels = IntArray(frameHeight * exportWidth)

        for (col in 0 until exportWidth) {
            val srcCol = (startColumn + col) % config.maxColumns
            for (y in 0 until frameHeight) {
                val luma = buffer[y * config.maxColumns + srcCol].toInt() and 0xFF
                // ARGB_8888: 0xAARRGGBB
                pixels[y * exportWidth + col] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
            }
        }

        Bitmap.createBitmap(pixels, exportWidth, frameHeight, Bitmap.Config.ARGB_8888)
    }

    /**
     * Export all available columns as a Bitmap (no pre-roll/post-roll windowing).
     */
    fun getFullComposite(): Bitmap? = getComposite(includePreRoll = false)

    // ---- Timestamp queries ----

    /**
     * Get the timestamp for a logical column index (0 = oldest available).
     *
     * @return Timestamp in nanoseconds, or null if out of range.
     */
    fun timestamp(column: Int): Long? = lock.withLock {
        if (column < 0 || column >= columnCount) return@withLock null

        val startColumn = (writeIndex - columnCount + config.maxColumns) % config.maxColumns
        val actualColumn = (startColumn + column) % config.maxColumns
        timestamps[actualColumn]
    }

    /**
     * Get the column index (in the exported image) where the crossing was marked.
     *
     * @return Column index relative to the full export, or null if no crossing marked.
     */
    fun getCrossingColumnInExport(): Int? = lock.withLock {
        val crossingIdx = crossingColumnIndex ?: return@withLock null

        val startColumn = (writeIndex - columnCount + config.maxColumns) % config.maxColumns
        val relativePos = (crossingIdx - startColumn + config.maxColumns) % config.maxColumns

        if (relativePos < columnCount) relativePos else null
    }

    // ---- Reset ----

    /** Clear all recorded data for a new session. */
    fun reset() = lock.withLock {
        writeIndex = 0
        columnCount = 0
        isRecording = false
        crossingColumnIndex = null
        // No need to zero the buffer; it will be overwritten.
    }

    companion object {
        private const val TAG = "CompositeBuffer"
    }
}
