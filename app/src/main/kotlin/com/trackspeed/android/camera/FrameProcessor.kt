package com.trackspeed.android.camera

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes camera frames and extracts vertical strips for detection.
 *
 * This is the bridge between camera frames and the detection engine.
 * It extracts the gate line slit and surrounding strips for analysis.
 */
@Singleton
class FrameProcessor @Inject constructor() {

    // Gate position (0.0 = left edge, 1.0 = right edge)
    private val _gatePosition = MutableStateFlow(0.5f)
    val gatePosition: StateFlow<Float> = _gatePosition.asStateFlow()

    // Processing stats
    private val _processedFrames = MutableStateFlow(0L)
    val processedFrames: StateFlow<Long> = _processedFrames.asStateFlow()

    // Strip configuration
    private var stripDelta = 3 // Pixels between strips

    /**
     * Set the gate line position.
     * @param position Normalized position (0.0 - 1.0)
     */
    fun setGatePosition(position: Float) {
        _gatePosition.value = position.coerceIn(0.05f, 0.95f)
    }

    /**
     * Extract three vertical strips at the gate position.
     * Used for torso-width validation (3-strip check).
     */
    fun extractThreeStrips(frame: HighSpeedCameraManager.FrameData): ThreeStrips {
        val centerX = (gatePosition.value * frame.width).toInt()
        val delta = maxOf(3, frame.width / 100) // At least 3 pixels, or 1% of width

        val leftX = (centerX - delta).coerceIn(0, frame.width - 1)
        val rightX = (centerX + delta).coerceIn(0, frame.width - 1)
        val centerXClamped = centerX.coerceIn(0, frame.width - 1)

        return ThreeStrips(
            left = extractColumn(frame.luminanceBuffer, frame.width, frame.height, leftX),
            center = extractColumn(frame.luminanceBuffer, frame.width, frame.height, centerXClamped),
            right = extractColumn(frame.luminanceBuffer, frame.width, frame.height, rightX),
            timestamp = frame.timestampNanos,
            frameIndex = frame.frameIndex
        )
    }

    /**
     * Extract a single vertical column from the luminance buffer.
     */
    fun extractColumn(
        luminance: ByteArray,
        width: Int,
        height: Int,
        x: Int
    ): ByteArray {
        val column = ByteArray(height)
        for (y in 0 until height) {
            column[y] = luminance[y * width + x]
        }
        return column
    }

    /**
     * Extract a sampling band (multiple columns averaged) for background calibration.
     * More robust than single column.
     */
    fun extractSamplingBand(
        frame: HighSpeedCameraManager.FrameData,
        bandWidth: Int = 5
    ): ByteArray {
        val centerX = (gatePosition.value * frame.width).toInt()
        val halfBand = bandWidth / 2
        val result = ByteArray(frame.height)

        for (y in 0 until frame.height) {
            var sum = 0
            var count = 0
            for (dx in -halfBand..halfBand) {
                val x = (centerX + dx).coerceIn(0, frame.width - 1)
                sum += frame.luminanceBuffer[y * frame.width + x].toInt() and 0xFF
                count++
            }
            result[y] = (sum / count).toByte()
        }

        return result
    }

    /**
     * Process a frame and extract all needed data for detection.
     */
    fun processFrame(frame: HighSpeedCameraManager.FrameData): ProcessedFrame {
        _processedFrames.value++

        val strips = extractThreeStrips(frame)
        val samplingBand = extractSamplingBand(frame)

        return ProcessedFrame(
            strips = strips,
            samplingBand = samplingBand,
            width = frame.width,
            height = frame.height,
            timestamp = frame.timestampNanos,
            frameIndex = frame.frameIndex
        )
    }

    fun reset() {
        _processedFrames.value = 0
    }
}

/**
 * Three vertical strips extracted from a frame.
 * Used for 3-strip torso validation.
 */
data class ThreeStrips(
    val left: ByteArray,
    val center: ByteArray,
    val right: ByteArray,
    val timestamp: Long,
    val frameIndex: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ThreeStrips
        return frameIndex == other.frameIndex
    }

    override fun hashCode(): Int = frameIndex.hashCode()
}

/**
 * Fully processed frame ready for detection engine.
 */
data class ProcessedFrame(
    val strips: ThreeStrips,
    val samplingBand: ByteArray,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val frameIndex: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProcessedFrame
        return frameIndex == other.frameIndex
    }

    override fun hashCode(): Int = frameIndex.hashCode()
}
