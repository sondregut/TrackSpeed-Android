package com.trackspeed.android.detection.experimental

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State machine states for experimental detector.
 */
enum class ExperimentalState {
    /** Phone is being moved/repositioned */
    MOVING,
    /** Phone stabilized, building background model */
    STABILIZING,
    /** Background ready, waiting for blob acquisition */
    ACQUIRE,
    /** Actively tracking a blob */
    TRACKING,
    /** Crossing just triggered */
    TRIGGERED,
    /** Brief pause after trigger */
    COOLDOWN
}

/**
 * Crossing event from experimental detector.
 */
data class ExperimentalCrossingEvent(
    val timestamp: Long,           // Interpolated timestamp (nanos)
    val frameIndex: Long,
    val rawTimestamp: Long,        // Raw frame timestamp (nanos)
    val interpolationOffsetMs: Double
)

/**
 * Debug info for experimental detector.
 */
data class ExperimentalDebugInfo(
    val state: ExperimentalState,
    val blob: ConnectedComponentLabeler.Blob?,
    val velocityNorm: Float,
    val direction: BlobTracker.Direction,
    val distanceToGate: Float,
    val threshold: Int,
    val motionCoverage: Float,
    val backgroundFrames: Int,
    val quietSceneFrames: Int,
    val gyroMagnitude: Float,
    val accelMagnitude: Float,
    val roiWidth: Int,
    val roiHeight: Int,
    val gateX: Float
)

/**
 * Frame data for experimental mode (60fps).
 */
data class ExperimentalFrameData(
    val luminanceBuffer: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val timestampNanos: Long
)

/**
 * Main experimental crossing detector.
 *
 * Uses 60fps blob tracking with:
 * - IMU-based auto-recovery
 * - Adaptive threshold motion detection
 * - Direction-aware crossing trigger
 * - Linear sub-frame interpolation
 */
@Singleton
class ExperimentalCrossingDetector @Inject constructor(
    private val imuDetector: IMUShakeDetector
) {
    companion object {
        private const val TAG = "ExperimentalDetector"
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC STATE
    // ═══════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow(ExperimentalState.MOVING)
    val state: StateFlow<ExperimentalState> = _state.asStateFlow()

    private val _crossingEvents = MutableSharedFlow<ExperimentalCrossingEvent>(extraBufferCapacity = 10)
    val crossingEvents: SharedFlow<ExperimentalCrossingEvent> = _crossingEvents.asSharedFlow()

    private val _debugInfo = MutableStateFlow<ExperimentalDebugInfo?>(null)
    val debugInfo: StateFlow<ExperimentalDebugInfo?> = _debugInfo.asStateFlow()

    // ═══════════════════════════════════════════════════════════════
    // COMPONENTS
    // ═══════════════════════════════════════════════════════════════

    private val motionMaskEngine = MotionMaskEngine()
    private var ccl: ConnectedComponentLabeler? = null
    private val blobTracker = BlobTracker()

    // ═══════════════════════════════════════════════════════════════
    // PRE-ALLOCATED BUFFERS
    // ═══════════════════════════════════════════════════════════════

    private lateinit var roiBuffer: ByteArray
    private lateinit var backgroundAccumulator: FloatArray
    private lateinit var backgroundReference: FloatArray

    // ═══════════════════════════════════════════════════════════════
    // DIMENSIONS
    // ═══════════════════════════════════════════════════════════════

    private var frameWidth = 0
    private var frameHeight = 0
    private var roiWidth = 0
    private var roiHeight = 0
    private var roiStartX = 0
    private var gateX = 0f
    private var buffersAllocated = false

    // ═══════════════════════════════════════════════════════════════
    // STATE TRACKING
    // ═══════════════════════════════════════════════════════════════

    private var backgroundFrameCount = 0
    private var quietSceneFrameCount = 0
    private var lastTriggerTimeNanos = 0L
    private var cooldownEndTimeNanos = 0L

    // Distance history for interpolation
    private var prevDistance = 0f
    private var prevTimestamp = 0L
    private var currDistance = 0f
    private var currTimestamp = 0L

    // Frame counter
    private var frameIndex = 0L

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start detection.
     */
    fun start() {
        imuDetector.start(
            onShake = { handleShakeDetected() },
            onStable = { handleStabilized() }
        )
        _state.value = ExperimentalState.MOVING
        Log.i(TAG, "Experimental detector started")
    }

    /**
     * Stop detection.
     */
    fun stop() {
        imuDetector.stop()
        Log.i(TAG, "Experimental detector stopped")
    }

    /**
     * Reset to initial state.
     */
    fun reset() {
        imuDetector.reset()
        motionMaskEngine.reset()
        blobTracker.reset()

        _state.value = ExperimentalState.MOVING
        backgroundFrameCount = 0
        quietSceneFrameCount = 0
        frameIndex = 0
        prevDistance = 0f
        prevTimestamp = 0L
        currDistance = 0f
        currTimestamp = 0L

        if (buffersAllocated) {
            Arrays.fill(backgroundAccumulator, 0f)
            Arrays.fill(backgroundReference, 0f)
        }

        Log.i(TAG, "Experimental detector reset")
    }

    // ═══════════════════════════════════════════════════════════════
    // FRAME PROCESSING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Process a camera frame.
     * Call this for every frame at 60fps.
     *
     * IMPORTANT: This method handles Android Camera2 stride quirks internally.
     */
    fun processFrame(frameData: ExperimentalFrameData) {
        frameIndex++

        // Ensure buffers allocated
        if (!buffersAllocated || frameWidth != frameData.width || frameHeight != frameData.height) {
            allocateBuffers(frameData.width, frameData.height)
        }

        val state = _state.value

        // In MOVING state, skip all processing
        if (state == ExperimentalState.MOVING) {
            updateDebugInfo(state, null, null, 0f, 0)
            return
        }

        // Extract ROI slab with stride handling
        extractROIWithStride(
            frameData.luminanceBuffer,
            frameData.rowStride,
            frameData.width,
            frameData.height
        )

        // Compute motion mask
        val maskResult = motionMaskEngine.computeMask(roiBuffer, roiWidth, roiHeight)

        when (state) {
            ExperimentalState.STABILIZING -> {
                processStabilizing(maskResult)
            }

            ExperimentalState.ACQUIRE,
            ExperimentalState.TRACKING -> {
                processDetection(maskResult, frameData.timestampNanos)
            }

            ExperimentalState.TRIGGERED -> {
                // Immediate transition to cooldown
                _state.value = ExperimentalState.COOLDOWN
            }

            ExperimentalState.COOLDOWN -> {
                val now = SystemClock.elapsedRealtimeNanos()
                if (now >= cooldownEndTimeNanos) {
                    _state.value = ExperimentalState.ACQUIRE
                    blobTracker.reset()
                }
                updateDebugInfo(ExperimentalState.COOLDOWN, null, maskResult, 0f, 0)
            }

            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STATE HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private fun handleShakeDetected() {
        Log.d(TAG, "Shake detected → MOVING")
        _state.value = ExperimentalState.MOVING

        // Clear all tracking state
        blobTracker.reset()
        motionMaskEngine.reset()
        backgroundFrameCount = 0
        quietSceneFrameCount = 0
        prevDistance = 0f
        currDistance = 0f

        if (buffersAllocated) {
            Arrays.fill(backgroundAccumulator, 0f)
            Arrays.fill(backgroundReference, 0f)
        }
    }

    private fun handleStabilized() {
        Log.d(TAG, "Phone stabilized → STABILIZING")
        _state.value = ExperimentalState.STABILIZING
        backgroundFrameCount = 0
        quietSceneFrameCount = 0
        motionMaskEngine.reset()
    }

    private fun processStabilizing(maskResult: MotionMaskEngine.MaskResult) {
        val coverage = maskResult.coverage

        // Only accumulate quiet frames (avoid learning moving people)
        if (coverage <= ExperimentalConfig.MAX_MOTION_FOR_LEARNING) {
            if (backgroundFrameCount == 0) {
                // First quiet frame - initialize
                for (i in roiBuffer.indices) {
                    backgroundAccumulator[i] = (roiBuffer[i].toInt() and 0xFF).toFloat()
                }
                backgroundFrameCount = 1
            } else if (backgroundFrameCount < ExperimentalConfig.BACKGROUND_FRAMES_NEEDED) {
                // Accumulate
                for (i in roiBuffer.indices) {
                    backgroundAccumulator[i] += (roiBuffer[i].toInt() and 0xFF).toFloat()
                }
                backgroundFrameCount++
            }

            // Check if background ready
            if (backgroundFrameCount >= ExperimentalConfig.BACKGROUND_FRAMES_NEEDED &&
                backgroundReference[0] == 0f) {
                // Compute average
                for (i in backgroundAccumulator.indices) {
                    backgroundReference[i] = backgroundAccumulator[i] / backgroundFrameCount
                }
                Log.i(TAG, "Background reference built from $backgroundFrameCount frames")
            }
        }

        // Check for quiet scene
        if (backgroundFrameCount >= ExperimentalConfig.BACKGROUND_FRAMES_NEEDED) {
            if (coverage < ExperimentalConfig.QUIET_SCENE_THRESHOLD) {
                quietSceneFrameCount++
            } else {
                quietSceneFrameCount = 0
            }

            if (quietSceneFrameCount >= ExperimentalConfig.QUIET_SCENE_FRAMES_NEEDED) {
                Log.i(TAG, "Scene quiet → ACQUIRE")
                _state.value = ExperimentalState.ACQUIRE
                blobTracker.reset()
            }
        }

        updateDebugInfo(
            ExperimentalState.STABILIZING,
            null,
            maskResult,
            0f,
            backgroundFrameCount
        )
    }

    private fun processDetection(
        maskResult: MotionMaskEngine.MaskResult,
        timestampNanos: Long
    ) {
        // Find blobs (ZERO ALLOCATION - uses internal pool)
        if (ccl == null) {
            ccl = ConnectedComponentLabeler(maskResult.width, maskResult.height)
        }
        ccl!!.findBlobs(maskResult.mask, maskResult.width, maskResult.height)

        // Scale blobs IN PLACE to full ROI resolution (no allocations)
        val scaleFactor = ExperimentalConfig.MORPHOLOGY_DOWNSAMPLE.toFloat()
        val blobs = ccl!!.getActiveBlobs()
        for (blob in blobs) {
            blob.scaleInPlace(scaleFactor)
        }

        // Track blob
        val dt = if (prevTimestamp > 0) {
            (timestampNanos - prevTimestamp) / 1_000_000_000f
        } else {
            1f / 60f
        }

        val trackResult = blobTracker.update(
            blobs = blobs,
            roiWidth = roiWidth.toFloat(),
            gateX = gateX,
            dt = dt
        )

        // Update distance history
        prevDistance = currDistance
        prevTimestamp = currTimestamp
        currDistance = trackResult.distanceToGate
        currTimestamp = timestampNanos

        // State transitions
        if (trackResult.blob != null) {
            if (_state.value == ExperimentalState.ACQUIRE) {
                _state.value = ExperimentalState.TRACKING
            }

            // Check for crossing
            if (_state.value == ExperimentalState.TRACKING && trackResult.isValid) {
                if (checkCrossing(trackResult)) {
                    triggerCrossing(timestampNanos)
                }
            }
        } else {
            // Lost blob
            if (_state.value == ExperimentalState.TRACKING) {
                _state.value = ExperimentalState.ACQUIRE
            }
        }

        updateDebugInfo(
            _state.value,
            trackResult,
            maskResult,
            trackResult.distanceToGate,
            backgroundFrameCount
        )
    }

    /**
     * Check if crossing conditions are met.
     */
    private fun checkCrossing(trackResult: BlobTracker.TrackResult): Boolean {
        // Need locked direction
        if (trackResult.direction == BlobTracker.Direction.UNKNOWN) return false

        // Check sign flip based on direction
        val signFlipped = when (trackResult.direction) {
            BlobTracker.Direction.LEFT_TO_RIGHT -> prevDistance < 0 && currDistance >= 0
            BlobTracker.Direction.RIGHT_TO_LEFT -> prevDistance > 0 && currDistance <= 0
            else -> false
        }

        return signFlipped
    }

    /**
     * Trigger a crossing event.
     */
    private fun triggerCrossing(timestampNanos: Long) {
        // Linear interpolation for sub-frame time
        val interpolatedTime = if (prevDistance != currDistance) {
            val alpha = prevDistance / (prevDistance - currDistance)
            prevTimestamp + ((timestampNanos - prevTimestamp) * alpha).toLong()
        } else {
            timestampNanos
        }

        val event = ExperimentalCrossingEvent(
            timestamp = interpolatedTime,
            frameIndex = frameIndex,
            rawTimestamp = timestampNanos,
            interpolationOffsetMs = (timestampNanos - interpolatedTime) / 1_000_000.0
        )

        _crossingEvents.tryEmit(event)
        Log.i(TAG, "Crossing detected! Interpolated time: $interpolatedTime")

        // Transition to cooldown
        _state.value = ExperimentalState.COOLDOWN
        cooldownEndTimeNanos = timestampNanos +
            ExperimentalConfig.COOLDOWN_DURATION_MS * 1_000_000L
        lastTriggerTimeNanos = timestampNanos
    }

    // ═══════════════════════════════════════════════════════════════
    // BUFFER MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    private fun allocateBuffers(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height

        // ROI is ±12% = 24% of frame width
        roiWidth = (width * ExperimentalConfig.ROI_SLAB_HALF_WIDTH * 2).toInt()
        roiHeight = height
        roiStartX = (width - roiWidth) / 2
        gateX = roiWidth / 2f  // Gate at center of ROI

        val roiSize = roiWidth * roiHeight

        roiBuffer = ByteArray(roiSize)
        backgroundAccumulator = FloatArray(roiSize)
        backgroundReference = FloatArray(roiSize)

        motionMaskEngine.allocateBuffers(roiWidth, roiHeight)

        buffersAllocated = true
        Log.i(TAG, "Buffers allocated: frame ${width}x${height}, ROI ${roiWidth}x${roiHeight}")
    }

    /**
     * Extract ROI slab from full frame with stride handling.
     *
     * CRITICAL: Android Camera2 YUV_420_888 buffers often have rowStride > width.
     */
    private fun extractROIWithStride(
        luminance: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int
    ) {
        var dstIdx = 0

        // If rowStride == width, we can use fast path
        if (rowStride == width) {
            for (y in 0 until roiHeight) {
                val srcOffset = y * width + roiStartX
                System.arraycopy(luminance, srcOffset, roiBuffer, dstIdx, roiWidth)
                dstIdx += roiWidth
            }
        } else {
            // Slow path: handle stride padding
            for (y in 0 until roiHeight) {
                val srcRowStart = y * rowStride  // Use rowStride, not width!
                val srcOffset = srcRowStart + roiStartX
                System.arraycopy(luminance, srcOffset, roiBuffer, dstIdx, roiWidth)
                dstIdx += roiWidth
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DEBUG INFO
    // ═══════════════════════════════════════════════════════════════

    private fun updateDebugInfo(
        state: ExperimentalState,
        trackResult: BlobTracker.TrackResult?,
        maskResult: MotionMaskEngine.MaskResult?,
        distanceToGate: Float,
        bgFrameCount: Int
    ) {
        _debugInfo.value = ExperimentalDebugInfo(
            state = state,
            blob = trackResult?.blob,
            velocityNorm = trackResult?.velocityNorm ?: 0f,
            direction = trackResult?.direction ?: BlobTracker.Direction.UNKNOWN,
            distanceToGate = distanceToGate,
            threshold = maskResult?.threshold ?: 0,
            motionCoverage = maskResult?.coverage ?: 0f,
            backgroundFrames = bgFrameCount,
            quietSceneFrames = quietSceneFrameCount,
            gyroMagnitude = imuDetector.gyroMagnitude.value,
            accelMagnitude = imuDetector.accelMagnitude.value,
            roiWidth = roiWidth,
            roiHeight = roiHeight,
            gateX = gateX
        )
    }
}
