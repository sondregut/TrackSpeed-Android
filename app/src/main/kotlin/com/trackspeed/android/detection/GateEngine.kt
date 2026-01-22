package com.trackspeed.android.detection

import android.os.Build
import com.trackspeed.android.camera.FrameProcessor
import com.trackspeed.android.camera.HighSpeedCameraManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gate engine coordinator.
 *
 * Orchestrates the complete detection pipeline:
 * 1. Frame processing (extract strips)
 * 2. Background subtraction
 * 3. Contiguous run analysis
 * 4. Crossing detection
 * 5. Sub-frame interpolation
 * 6. Rolling shutter correction
 *
 * Reference: docs/architecture/DETECTION_ALGORITHM.md Section 9
 */
@Singleton
class GateEngine @Inject constructor(
    private val frameProcessor: FrameProcessor
) {
    // Detection components
    private val backgroundModel = BackgroundModel()
    private var crossingDetector = CrossingDetector()

    // Pose bounds (updated externally by PoseService at 30Hz)
    @Volatile
    private var torsoBounds: TorsoBounds? = null

    // State
    private val _engineState = MutableStateFlow(EngineState.NOT_CALIBRATED)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _calibrationProgress = MutableStateFlow(0f)
    val calibrationProgress: StateFlow<Float> = _calibrationProgress.asStateFlow()

    // Crossing events
    private val _crossingEvents = MutableSharedFlow<CrossingEvent>(extraBufferCapacity = 10)
    val crossingEvents: SharedFlow<CrossingEvent> = _crossingEvents.asSharedFlow()

    // Debug info
    private val _debugInfo = MutableStateFlow<DetectionDebugInfo?>(null)
    val debugInfo: StateFlow<DetectionDebugInfo?> = _debugInfo.asStateFlow()

    // Frame counter
    private var frameCount = 0L

    enum class EngineState {
        NOT_CALIBRATED,
        CALIBRATING,
        WAITING_FOR_CLEAR,
        ARMED,
        CAPTURING,
        PAUSED
    }

    /**
     * Start calibration process.
     * Call this when the gate is clear and stable.
     */
    fun startCalibration() {
        backgroundModel.startCalibration()
        crossingDetector.reset()
        frameCount = 0
        _engineState.value = EngineState.CALIBRATING
        _calibrationProgress.value = 0f
    }

    /**
     * Process a camera frame through the detection pipeline.
     * Should be called for every frame at 240fps.
     */
    fun processFrame(frameData: HighSpeedCameraManager.FrameData) {
        frameCount++

        // Process frame to extract strips
        val processed = frameProcessor.processFrame(frameData)

        when (_engineState.value) {
            EngineState.NOT_CALIBRATED -> {
                // Waiting for calibration to start
            }

            EngineState.CALIBRATING -> {
                processCalibrationFrame(processed)
            }

            EngineState.WAITING_FOR_CLEAR,
            EngineState.ARMED,
            EngineState.CAPTURING -> {
                processDetectionFrame(processed)
            }

            EngineState.PAUSED -> {
                // Detection suspended
            }
        }
    }

    private fun processCalibrationFrame(frame: com.trackspeed.android.camera.ProcessedFrame) {
        val complete = backgroundModel.addCalibrationFrame(frame.samplingBand)
        _calibrationProgress.value = backgroundModel.getCalibrationProgress()

        if (complete) {
            _engineState.value = EngineState.WAITING_FOR_CLEAR
            crossingDetector.reset()
        }
    }

    private fun processDetectionFrame(frame: com.trackspeed.android.camera.ProcessedFrame) {
        // 1. Get foreground masks via background subtraction
        val leftMask = backgroundModel.getForegroundMask(frame.strips.left)
        val centerMask = backgroundModel.getForegroundMask(frame.strips.center)
        val rightMask = backgroundModel.getForegroundMask(frame.strips.right)

        // 2. Calculate chest band
        val band = ContiguousRunFilter.calculateChestBand(torsoBounds, frame.height)

        // 3. Analyze three strips for torso-like shape
        val threeStripResult = ContiguousRunFilter.analyzeThreeStrips(
            leftMask, centerMask, rightMask, band
        )

        // 4. Get center strip occupancy (primary detection value)
        val occupancy = threeStripResult.centerOccupancy

        // 5. Run crossing detector state machine
        val crossingResult = crossingDetector.processFrame(
            occupancy = occupancy,
            timestamp = frame.timestamp,
            frameIndex = frame.frameIndex,
            torsoBounds = torsoBounds,
            threeStripResult = threeStripResult
        )

        // 6. Update engine state from detector state
        _engineState.value = when (crossingDetector.state) {
            CrossingDetector.State.WAITING_FOR_CLEAR -> EngineState.WAITING_FOR_CLEAR
            CrossingDetector.State.ARMED -> EngineState.ARMED
            CrossingDetector.State.POSTROLL,
            CrossingDetector.State.COOLDOWN -> EngineState.CAPTURING
            CrossingDetector.State.PAUSED -> EngineState.PAUSED
        }

        // 7. Handle crossing if detected
        if (crossingResult != null) {
            val correctedTimestamp = correctForRollingShutter(
                timestamp = crossingResult.interpolatedTimestamp,
                crossingRow = crossingResult.crossingRow,
                frameHeight = frame.height
            )

            val event = CrossingEvent(
                timestamp = correctedTimestamp,
                frameIndex = crossingResult.triggerFrameIndex,
                occupancy = crossingResult.occupancyAtTrigger,
                interpolationOffsetMs = crossingResult.interpolationOffsetMs,
                isTorsoLike = threeStripResult.isTorsoLike
            )

            _crossingEvents.tryEmit(event)
        }

        // 8. Slow background adaptation (if armed and gate clear)
        if (crossingDetector.isArmed() && occupancy < DetectionConfig.GATE_CLEAR_BELOW) {
            backgroundModel.adaptBackground(frame.samplingBand)
        }

        // 9. Update debug info
        _debugInfo.value = DetectionDebugInfo(
            rLeft = threeStripResult.leftOccupancy,
            rCenter = threeStripResult.centerOccupancy,
            rRight = threeStripResult.rightOccupancy,
            runLeft = threeStripResult.leftRun,
            runCenter = threeStripResult.centerRun,
            runRight = threeStripResult.rightRun,
            torsoTop = torsoBounds?.yTop,
            torsoBottom = torsoBounds?.yBottom,
            chestBandTop = band.top,
            chestBandBottom = band.bottom,
            state = crossingDetector.state,
            isTorsoLike = threeStripResult.isTorsoLike
        )
    }

    /**
     * Apply rolling shutter correction.
     * Rolling shutter cameras read rows sequentially, causing timing offset.
     */
    private fun correctForRollingShutter(
        timestamp: Long,
        crossingRow: Int,
        frameHeight: Int
    ): Long {
        val readoutTimeMs = getReadoutTime()
        val rowFraction = crossingRow.toDouble() / frameHeight
        val correctionNanos = (rowFraction * readoutTimeMs * 1_000_000).toLong()
        return timestamp + correctionNanos
    }

    private fun getReadoutTime(): Double {
        val model = Build.MODEL.lowercase()
        return when {
            model.contains("pixel 8") -> 4.5
            model.contains("pixel 7") -> 4.8
            model.contains("pixel 6") -> 5.0
            model.contains("samsung") && model.contains("s24") -> 4.3
            model.contains("samsung") && model.contains("s23") -> 4.5
            else -> DetectionConfig.DEFAULT_READOUT_TIME_MS
        }
    }

    /**
     * Update torso bounds from pose detection.
     * Call this from PoseService at 30Hz.
     */
    fun updateTorsoBounds(bounds: TorsoBounds?) {
        torsoBounds = bounds
    }

    /**
     * Pause detection.
     */
    fun pause() {
        crossingDetector.pause()
        _engineState.value = EngineState.PAUSED
    }

    /**
     * Resume detection.
     */
    fun resume() {
        if (backgroundModel.state == BackgroundModel.State.CALIBRATED) {
            crossingDetector.resume()
            _engineState.value = EngineState.WAITING_FOR_CLEAR
        }
    }

    /**
     * Reset everything and require new calibration.
     */
    fun reset() {
        backgroundModel.reset()
        crossingDetector.reset()
        torsoBounds = null
        frameCount = 0
        _engineState.value = EngineState.NOT_CALIBRATED
        _calibrationProgress.value = 0f
        _debugInfo.value = null
    }

    fun isCalibrated(): Boolean = backgroundModel.state == BackgroundModel.State.CALIBRATED
    fun isArmed(): Boolean = crossingDetector.isArmed()
}

/**
 * A detected crossing event.
 */
data class CrossingEvent(
    val timestamp: Long,           // Nanoseconds (monotonic clock)
    val frameIndex: Long,
    val occupancy: Float,
    val interpolationOffsetMs: Double,
    val isTorsoLike: Boolean
)

/**
 * Debug info for visualization.
 */
data class DetectionDebugInfo(
    val rLeft: Float,
    val rCenter: Float,
    val rRight: Float,
    val runLeft: Int,
    val runCenter: Int,
    val runRight: Int,
    val torsoTop: Float?,
    val torsoBottom: Float?,
    val chestBandTop: Int,
    val chestBandBottom: Int,
    val state: CrossingDetector.State,
    val isTorsoLike: Boolean
)
