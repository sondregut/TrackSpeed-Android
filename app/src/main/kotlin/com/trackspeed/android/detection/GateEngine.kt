package com.trackspeed.android.detection

import android.os.Build
import android.util.Log
import com.trackspeed.android.camera.FrameProcessor
import com.trackspeed.android.camera.HighSpeedCameraManager
import com.trackspeed.android.detection.experimental.ExperimentalCrossingDetector
import com.trackspeed.android.detection.experimental.ExperimentalCrossingEvent
import com.trackspeed.android.detection.experimental.ExperimentalFrameData
import com.trackspeed.android.detection.experimental.ExperimentalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detection mode selection.
 */
enum class DetectionMode {
    /** 240fps precision mode with pose detection and background model */
    PRECISION,
    /** 60fps experimental mode with blob tracking and IMU recovery */
    EXPERIMENTAL
}

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
 * Supports two detection modes:
 * - PRECISION: 240fps with pose detection (original algorithm)
 * - EXPERIMENTAL: 60fps blob tracking with IMU auto-recovery
 *
 * Reference: docs/architecture/DETECTION_ALGORITHM.md Section 9
 * Reference: docs/architecture/EXPERIMENTAL_MODE_KOTLIN.md
 */
@Singleton
class GateEngine @Inject constructor(
    private val frameProcessor: FrameProcessor,
    private val experimentalDetector: ExperimentalCrossingDetector
) {
    companion object {
        private const val TAG = "GateEngine"
        private const val DEBUG_UPDATE_INTERVAL_MS = 50L // Max 20Hz for debug info
    }

    // ═══════════════════════════════════════════════════════════════
    // MODE SELECTION
    // ═══════════════════════════════════════════════════════════════

    private val _detectionMode = MutableStateFlow(DetectionMode.EXPERIMENTAL) // Default to experimental
    val detectionMode: StateFlow<DetectionMode> = _detectionMode.asStateFlow()

    // Coroutine scope for collecting experimental detector events
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Forward experimental detector crossing events to unified crossing events
        scope.launch {
            experimentalDetector.crossingEvents.collect { experimentalEvent ->
                val event = CrossingEvent(
                    timestamp = experimentalEvent.timestamp,
                    frameIndex = experimentalEvent.frameIndex,
                    occupancy = 0f, // Not used in experimental mode
                    interpolationOffsetMs = experimentalEvent.interpolationOffsetMs,
                    isTorsoLike = true // Blob tracking implies torso-like
                )
                _crossingEvents.tryEmit(event)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PRECISION MODE COMPONENTS (240fps)
    // ═══════════════════════════════════════════════════════════════

    // Detection components
    private val backgroundModel = BackgroundModel()
    private var crossingDetector = CrossingDetector()

    // Pose bounds (updated externally by PoseService at 30Hz)
    // Using AtomicReference for thread-safe access between pose thread (30Hz) and camera thread (240Hz)
    private val torsoBoundsRef = AtomicReference<TorsoBounds?>(null)

    // ═══════════════════════════════════════════════════════════════
    // SHARED STATE
    // ═══════════════════════════════════════════════════════════════

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

    // Throttle debug info updates
    private var lastDebugUpdateTime = 0L

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
     *
     * THREAD SAFETY: This method is called from the camera's image processing thread.
     * All internal state access is either thread-safe or confined to this thread.
     */
    fun processFrame(frameData: HighSpeedCameraManager.FrameData) {
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error at frame $frameCount", e)
            // Don't crash - skip this frame and continue
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
        // Get thread-safe copy of torso bounds
        val currentTorsoBounds = torsoBoundsRef.get()

        // 1. Get foreground masks via background subtraction
        val leftMask = backgroundModel.getForegroundMask(frame.strips.left)
        val centerMask = backgroundModel.getForegroundMask(frame.strips.center)
        val rightMask = backgroundModel.getForegroundMask(frame.strips.right)

        // 2. Calculate chest band
        val band = ContiguousRunFilter.calculateChestBand(currentTorsoBounds, frame.height)

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
            torsoBounds = currentTorsoBounds,
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

        // 9. Update debug info (throttled to 20Hz to reduce UI load)
        val now = System.currentTimeMillis()
        if (now - lastDebugUpdateTime > DEBUG_UPDATE_INTERVAL_MS) {
            lastDebugUpdateTime = now
            _debugInfo.value = DetectionDebugInfo(
                rLeft = threeStripResult.leftOccupancy,
                rCenter = threeStripResult.centerOccupancy,
                rRight = threeStripResult.rightOccupancy,
                runLeft = threeStripResult.leftRun,
                runCenter = threeStripResult.centerRun,
                runRight = threeStripResult.rightRun,
                torsoTop = currentTorsoBounds?.yTop,
                torsoBottom = currentTorsoBounds?.yBottom,
                chestBandTop = band.top,
                chestBandBottom = band.bottom,
                state = crossingDetector.state,
                isTorsoLike = threeStripResult.isTorsoLike
            )
        }
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
     *
     * THREAD SAFETY: This method can be called from any thread (pose detection thread).
     * Uses AtomicReference for safe publication to the camera processing thread.
     */
    fun updateTorsoBounds(bounds: TorsoBounds?) {
        torsoBoundsRef.set(bounds)
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
        torsoBoundsRef.set(null)
        frameCount = 0
        lastDebugUpdateTime = 0
        _engineState.value = EngineState.NOT_CALIBRATED
        _calibrationProgress.value = 0f
        _debugInfo.value = null
    }

    fun isCalibrated(): Boolean {
        return when (_detectionMode.value) {
            DetectionMode.PRECISION -> backgroundModel.state == BackgroundModel.State.CALIBRATED
            DetectionMode.EXPERIMENTAL -> experimentalDetector.state.value != ExperimentalState.MOVING
        }
    }

    fun isArmed(): Boolean {
        return when (_detectionMode.value) {
            DetectionMode.PRECISION -> crossingDetector.isArmed()
            DetectionMode.EXPERIMENTAL -> {
                val state = experimentalDetector.state.value
                state == ExperimentalState.ACQUIRE || state == ExperimentalState.TRACKING
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MODE SWITCHING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Set the detection mode.
     *
     * @param mode PRECISION (240fps) or EXPERIMENTAL (60fps)
     */
    fun setDetectionMode(mode: DetectionMode) {
        if (_detectionMode.value == mode) return

        Log.i(TAG, "Switching detection mode: ${_detectionMode.value} → $mode")

        // Stop current mode
        when (_detectionMode.value) {
            DetectionMode.PRECISION -> {
                // Nothing special to stop
            }
            DetectionMode.EXPERIMENTAL -> {
                experimentalDetector.stop()
            }
        }

        // Reset state
        _detectionMode.value = mode
        _engineState.value = EngineState.NOT_CALIBRATED
        _calibrationProgress.value = 0f

        // Start new mode
        when (mode) {
            DetectionMode.PRECISION -> {
                backgroundModel.reset()
                crossingDetector.reset()
            }
            DetectionMode.EXPERIMENTAL -> {
                experimentalDetector.start()
            }
        }
    }

    /**
     * Process a frame for experimental mode (60fps).
     * This is a separate entry point for experimental mode frames.
     */
    fun processExperimentalFrame(frameData: HighSpeedCameraManager.FrameData) {
        if (_detectionMode.value != DetectionMode.EXPERIMENTAL) {
            Log.w(TAG, "processExperimentalFrame called but mode is ${_detectionMode.value}")
            return
        }

        // Convert to experimental frame data format
        val experimentalFrameData = ExperimentalFrameData(
            luminanceBuffer = frameData.luminanceBuffer,
            width = frameData.width,
            height = frameData.height,
            rowStride = frameData.rowStride,
            timestampNanos = frameData.timestampNanos
        )

        experimentalDetector.processFrame(experimentalFrameData)

        // Sync engine state from experimental detector
        _engineState.value = when (experimentalDetector.state.value) {
            ExperimentalState.MOVING -> EngineState.NOT_CALIBRATED
            ExperimentalState.STABILIZING -> EngineState.CALIBRATING
            ExperimentalState.ACQUIRE -> EngineState.WAITING_FOR_CLEAR
            ExperimentalState.TRACKING -> EngineState.ARMED
            ExperimentalState.TRIGGERED,
            ExperimentalState.COOLDOWN -> EngineState.CAPTURING
        }
    }

    /**
     * Get debug info for experimental mode.
     */
    fun getExperimentalDebugInfo() = experimentalDetector.debugInfo

    /**
     * Get experimental detector state.
     */
    fun getExperimentalState() = experimentalDetector.state
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
