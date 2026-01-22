package com.trackspeed.android.detection

import kotlin.math.abs

/**
 * Background model using per-row statistics for foreground detection.
 *
 * Uses median and MAD (Median Absolute Deviation) for robust thresholding
 * that handles uneven lighting across the frame.
 *
 * Reference: docs/architecture/DETECTION_ALGORITHM.md Section 3
 */
class BackgroundModel {

    enum class State {
        NOT_CALIBRATED,
        CALIBRATING,
        CALIBRATED
    }

    var state: State = State.NOT_CALIBRATED
        private set

    // Per-row statistics
    private lateinit var medians: FloatArray
    private lateinit var mads: FloatArray
    private lateinit var thresholds: FloatArray

    // Calibration buffer
    private val calibrationFrames = mutableListOf<ByteArray>()

    // Height of the calibrated frame
    var frameHeight: Int = 0
        private set

    /**
     * Start calibration process.
     */
    fun startCalibration() {
        state = State.CALIBRATING
        calibrationFrames.clear()
    }

    /**
     * Add a calibration frame (vertical column of luminance values).
     * @return true if calibration is complete
     */
    fun addCalibrationFrame(luminanceColumn: ByteArray): Boolean {
        if (state != State.CALIBRATING) return false

        calibrationFrames.add(luminanceColumn.copyOf())

        if (calibrationFrames.size >= DetectionConfig.CALIBRATION_FRAMES) {
            finishCalibration()
            return true
        }
        return false
    }

    /**
     * Get current calibration progress (0.0 - 1.0)
     */
    fun getCalibrationProgress(): Float {
        if (state != State.CALIBRATING) return if (state == State.CALIBRATED) 1f else 0f
        return calibrationFrames.size.toFloat() / DetectionConfig.CALIBRATION_FRAMES
    }

    /**
     * Complete calibration and calculate per-row statistics.
     */
    private fun finishCalibration() {
        if (calibrationFrames.isEmpty()) {
            state = State.NOT_CALIBRATED
            return
        }

        frameHeight = calibrationFrames[0].size
        medians = FloatArray(frameHeight)
        mads = FloatArray(frameHeight)
        thresholds = FloatArray(frameHeight)

        for (row in 0 until frameHeight) {
            // Collect all values for this row across frames
            val values = calibrationFrames.map {
                (it[row].toInt() and 0xFF).toFloat()
            }.sorted()

            // Calculate median
            medians[row] = values[values.size / 2]

            // Calculate MAD (Median Absolute Deviation)
            val deviations = values.map { abs(it - medians[row]) }.sorted()
            mads[row] = deviations[deviations.size / 2]

            // Calculate adaptive threshold
            // Use the maximum of: MIN_MAD, MAD * multiplier, or default threshold
            thresholds[row] = maxOf(
                DetectionConfig.MIN_MAD,
                DetectionConfig.MAD_MULTIPLIER * mads[row],
                DetectionConfig.DEFAULT_THRESHOLD
            )
        }

        calibrationFrames.clear()
        state = State.CALIBRATED
    }

    /**
     * Get foreground mask for a luminance column.
     * @param luminanceColumn Vertical strip of luminance values
     * @return Boolean array where true = foreground (different from background)
     */
    fun getForegroundMask(luminanceColumn: ByteArray): BooleanArray {
        if (state != State.CALIBRATED) {
            return BooleanArray(luminanceColumn.size) { false }
        }

        return BooleanArray(luminanceColumn.size) { row ->
            if (row >= frameHeight) return@BooleanArray false

            val pixel = (luminanceColumn[row].toInt() and 0xFF).toFloat()
            abs(pixel - medians[row]) > thresholds[row]
        }
    }

    /**
     * Slow adaptation for gradual lighting changes.
     * Only call when gate is clear (no athlete present).
     */
    fun adaptBackground(luminanceColumn: ByteArray) {
        if (state != State.CALIBRATED) return

        for (row in luminanceColumn.indices) {
            if (row >= frameHeight) break

            val pixel = (luminanceColumn[row].toInt() and 0xFF).toFloat()
            medians[row] = medians[row] * (1 - DetectionConfig.ADAPTATION_RATE) +
                          pixel * DetectionConfig.ADAPTATION_RATE
        }
    }

    /**
     * Reset the model to uncalibrated state.
     */
    fun reset() {
        state = State.NOT_CALIBRATED
        calibrationFrames.clear()
    }

    /**
     * Get debug info for visualization.
     */
    fun getDebugInfo(): BackgroundModelDebug? {
        if (state != State.CALIBRATED) return null

        return BackgroundModelDebug(
            medians = medians.copyOf(),
            thresholds = thresholds.copyOf(),
            frameHeight = frameHeight
        )
    }
}

data class BackgroundModelDebug(
    val medians: FloatArray,
    val thresholds: FloatArray,
    val frameHeight: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BackgroundModelDebug
        return frameHeight == other.frameHeight
    }

    override fun hashCode(): Int = frameHeight
}
