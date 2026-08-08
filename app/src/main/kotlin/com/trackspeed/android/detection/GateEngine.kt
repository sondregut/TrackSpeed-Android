package com.trackspeed.android.detection

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.trackspeed.android.camera.reviewThumbnailTargetTimestamp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gate engine coordinator wrapping the new geometry-based [DetectionEngine]
 * (port of iOS DetectionEngine.swift). Exposes the same reactive surface
 * the existing UI consumes — `engineState`, `crossingEvents`,
 * `detectionState`, `currentFps`, `gatePosition`, `lastCrossingThumbnail`,
 * `getComposite()` — so callers don't need to change.
 *
 * The legacy [PhotoFinishDetector.State] enum is reused for `detectionState`
 * to preserve UI compatibility; values are synthesised from engine activity.
 * Motion stability is gated separately, matching iOS CameraManager's
 * CMMotion `userAcceleration` check: detection is suspended while the phone
 * is moving, then rearmed after a short stillness window.
 */
@Singleton
class GateEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {
    companion object {
        private const val TAG = "GateEngine"
        // After firing, hold detectionState in COOLDOWN this many frames so
        // the UI can render the red ring/feedback. Engine's own cooldown
        // (0.5 s) governs when the next fire can occur.
        private const val COOLDOWN_FRAMES_FOR_UI = 8
        private const val PREBUFFER_READY_NANOS = 350_000_000L
        private const val PREBUFFER_RETENTION_NANOS = 550_000_000L
        private const val GATE_CLEAR_BELOW_THRESHOLD = 0.15
        private const val GATE_UNCLEAR_ABOVE_THRESHOLD = 0.25
    }

    private val engine: DetectionEngine = DetectionEngine()
    private var compositeBuffer: CompositeBuffer? = null
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _engineState = MutableStateFlow(EngineState.READY)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _crossingEvents = MutableSharedFlow<CrossingEvent>(extraBufferCapacity = 10)
    val crossingEvents: SharedFlow<CrossingEvent> = _crossingEvents.asSharedFlow()

    private val _detectionState = MutableStateFlow(PhotoFinishDetector.State.NO_ATHLETE)
    val detectionState: StateFlow<PhotoFinishDetector.State> = _detectionState.asStateFlow()

    private val _currentFps = MutableStateFlow(0)
    val currentFps: StateFlow<Int> = _currentFps.asStateFlow()

    private val _gatePosition = MutableStateFlow(0.5f)
    val gatePosition: StateFlow<Float> = _gatePosition.asStateFlow()

    private val _gateReadinessStatus = MutableStateFlow(GateReadinessStatus())
    val gateReadinessStatus: StateFlow<GateReadinessStatus> = _gateReadinessStatus.asStateFlow()

    private var configuredFps: Double = 30.0
    private var coolingDownFramesLeft: Int = 0
    private var hasStarted = false
    private var isPaused = false
    private val prebufferFrameTimes = ArrayDeque<Long>()
    @Volatile private var motionTrackingActive = false
    @Volatile private var isPhoneStable = true
    @Volatile private var unstableUntilNanos = 0L
    @Volatile private var motionMagnitude = 0.0
    @Volatile private var accelerationMagnitudeG = 0.0
    @Volatile private var rotationMagnitudeRadiansPerSecond = 0.0

    @Volatile var lastCrossingThumbnail: Bitmap? = null
        private set

    // Frame data references retained only across the synchronous
    // crossing emission so we can grab a thumbnail from current bytes.
    private var currentYPlane: ByteArray? = null
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentRowStride: Int = 0
    private var previousYPlane: ByteArray? = null
    private var previousWidth: Int = 0
    private var previousHeight: Int = 0
    private var previousRowStride: Int = 0
    private var previousPtsNanos: Long? = null

    enum class EngineState {
        READY,
        ARMED,
        CAPTURING,
        PAUSED
    }

    /** Configure the engine for the given FPS / camera. */
    fun configure(
        fps: Double,
        isFrontCamera: Boolean = false,
        cooldownSeconds: Double? = null
    ) {
        configuredFps = fps
        engine.isFrontCamera = isFrontCamera
        if (cooldownSeconds == null) {
            engine.clearCooldownOverride()
        } else {
            engine.setCooldown(cooldownSeconds)
        }
        _currentFps.value = fps.toInt()
    }

    /** Start motion monitoring used to block detection while the phone moves. */
    fun startMotionUpdates() {
        if (motionTrackingActive) return

        if (linearAccelerationSensor == null && gyroscope == null) {
            Log.w(TAG, "No motion sensor available; assuming phone is stable")
            setPhoneStable(true)
            return
        }

        val accelerationRegistered = linearAccelerationSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                PhoneMotionStabilityPolicy.SAMPLE_PERIOD_MICROS
            )
        } ?: false
        val gyroscopeRegistered = gyroscope?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                PhoneMotionStabilityPolicy.SAMPLE_PERIOD_MICROS
            )
        } ?: false
        motionTrackingActive = accelerationRegistered || gyroscopeRegistered

        if (!motionTrackingActive) {
            Log.w(TAG, "Failed to register motion sensor; assuming phone is stable")
            setPhoneStable(true)
        } else {
            Log.i(
                TAG,
                "Motion tracking started at ${PhoneMotionStabilityPolicy.UPDATES_PER_SECOND}Hz " +
                    "(accel=${PhoneMotionStabilityPolicy.ACCELERATION_THRESHOLD_G}g, " +
                    "rotation=${PhoneMotionStabilityPolicy.ROTATION_THRESHOLD_RADIANS_PER_SECOND}rad/s)"
            )
        }
    }

    /** Stop motion monitoring and clear any pending unstable state. */
    fun stopMotionUpdates() {
        if (motionTrackingActive) {
            sensorManager.unregisterListener(this)
        }
        motionTrackingActive = false
        unstableUntilNanos = 0L
        motionMagnitude = 0.0
        accelerationMagnitudeG = 0.0
        rotationMagnitudeRadiansPerSecond = 0.0
        setPhoneStable(true)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensorType = event.sensor?.type ?: return
        val x = event.values.getOrNull(0) ?: return
        val y = event.values.getOrNull(1) ?: return
        val z = event.values.getOrNull(2) ?: return

        val magnitude = PhoneMotionStabilityPolicy.magnitude(x, y, z)
        when (sensorType) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                accelerationMagnitudeG = magnitude /
                    PhoneMotionStabilityPolicy.STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED
            }
            Sensor.TYPE_GYROSCOPE -> {
                rotationMagnitudeRadiansPerSecond = magnitude
            }
            else -> return
        }
        motionMagnitude = maxOf(accelerationMagnitudeG, rotationMagnitudeRadiansPerSecond)

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (PhoneMotionStabilityPolicy.detectsMovement(
                accelerationMagnitudeG = accelerationMagnitudeG,
                rotationMagnitudeRadiansPerSecond = rotationMagnitudeRadiansPerSecond
            )
        ) {
            unstableUntilNanos = nowNanos + PhoneMotionStabilityPolicy.STABLE_DEBOUNCE_NANOS
            setPhoneStable(false)
        } else if (!isPhoneStable && nowNanos >= unstableUntilNanos) {
            setPhoneStable(true)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Process a camera frame. Drives DetectionEngine and emits a
     * [CrossingEvent] whenever the engine fires, then routes the same
     * activity into [engineState] / [detectionState] for the UI.
     */
    fun processFrame(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        frameNumber: Long,
        ptsNanos: Long,
        exposureNanos: Long? = null
    ) {
        if (isPaused) {
            if (_engineState.value != EngineState.PAUSED) {
                _engineState.value = EngineState.PAUSED
            }
            return
        }

        if (!isPhoneStable) {
            return
        }

        engine.gatePositionFraction = _gatePosition.value

        if (!hasStarted) {
            engine.start(ptsNanos)
            hasStarted = true
        }

        // Slit-scan composite buffer (photo-finish image), unrelated to
        // the new DetectionEngine — keep feeding it so the existing
        // composite UI continues to work.
        val composite = compositeBuffer ?: CompositeBuffer(height).also {
            compositeBuffer = it
            it.startRecording()
        }
        composite.addSlit(yPlane, width, height, rowStride, _gatePosition.value, ptsNanos)

        currentYPlane = yPlane
        currentWidth = width
        currentHeight = height
        currentRowStride = rowStride

        val result = engine.processFrame(
            yPlane = yPlane,
            fullWidth = width,
            fullHeight = height,
            rowStride = rowStride,
            timestampNanos = ptsNanos,
            exposureNanos = exposureNanos
        )
        updateGateReadiness(ptsNanos)

        currentYPlane = null

        if (result != null) {
            val choosePrevious = result.interpolationFraction < 0.5 &&
                previousYPlane != null &&
                previousWidth == width &&
                previousHeight == height &&
                previousRowStride == rowStride
            val detectorSelectedPts = if (choosePrevious) previousPtsNanos ?: ptsNanos else ptsNanos
            val framePick = if (choosePrevious) "prev" else "curr"
            val chosenPts = reviewThumbnailTargetTimestamp(
                detectorTriggerFramePtsNanos = ptsNanos,
                detectorSelectedFramePtsNanos = detectorSelectedPts,
                supportsLivePersonSelector = false
            )

            // Capture thumbnail synchronously while the buffer reference is
            // still valid; ImageReader recycles it once we return. Android has
            // no live torso selector, so the review image intentionally uses
            // the detector-trigger frame while timing keeps its interpolation.
            lastCrossingThumbnail = captureGrayscaleThumbnail(
                yPlane,
                width,
                height,
                rowStride
            )
            compositeBuffer?.markCrossing()

            val s0 = if (result.movingLeftToRight) {
                -result.dBeforePx
            } else {
                result.dBeforePx
            }
            val s1 = if (result.movingLeftToRight) {
                result.dAfterPx
            } else {
                -result.dAfterPx
            }

            _crossingEvents.tryEmit(
                CrossingEvent(
                    timestamp = result.crossingTimestampNanos,
                    frameIndex = frameNumber,
                    occupancy = result.blobHeightFraction,
                    interpolationOffsetMs = result.interpolationFraction * 1000.0,
                    isTorsoLike = true,
                    chestPositionNormalized = result.blobCenterXFraction,
                    velocityPxPerSec = result.velocityPxPerSec,
                    crossingDirection = if (result.movingLeftToRight) "L->R" else "R->L",
                    workWidth = DetectionEngine.PROCESS_WIDTH,
                    detectorYNormalized = result.gateY.toFloat() / DetectionEngine.PROCESS_HEIGHT.toFloat(),
                    interpolationAlpha = result.interpolationFraction,
                    framePick = framePick,
                    s0 = s0,
                    s1 = s1,
                    isFrontCamera = engine.isFrontCamera,
                    detectorTriggerFramePts = ptsNanos,
                    chosenThumbnailFramePts = chosenPts,
                    savedThumbnailFramePts = chosenPts,
                    xAnchorRuntimeDisplayX = result.xAnchorRuntimeDisplayX,
                    xAnchorRuntimeRule = result.xAnchorRuntimeRule
                )
            )

            _detectionState.value = PhotoFinishDetector.State.TRIGGERED
            _engineState.value = EngineState.CAPTURING
            coolingDownFramesLeft = COOLDOWN_FRAMES_FOR_UI
        } else if (coolingDownFramesLeft > 0) {
            coolingDownFramesLeft--
            _detectionState.value = PhotoFinishDetector.State.COOLDOWN
            _engineState.value = EngineState.CAPTURING
            if (coolingDownFramesLeft == 0) {
                _detectionState.value = PhotoFinishDetector.State.READY
                _engineState.value = EngineState.ARMED
            }
        } else {
            // Engine running but not currently firing — UI shows ARMED so
            // the gate-line ring stays green and we don't oscillate.
            if (_detectionState.value != PhotoFinishDetector.State.READY) {
                _detectionState.value = PhotoFinishDetector.State.READY
            }
            if (_engineState.value != EngineState.ARMED) {
                _engineState.value = EngineState.ARMED
            }
        }

        previousYPlane = yPlane
        previousWidth = width
        previousHeight = height
        previousRowStride = rowStride
        previousPtsNanos = ptsNanos
    }

    fun setGatePosition(position: Float) {
        _gatePosition.value = position.coerceIn(0.05f, 0.95f)
    }

    fun pause() {
        isPaused = true
        engine.stop()
        hasStarted = false
        clearPreviousFrame()
        clearGateReadiness()
        _engineState.value = EngineState.PAUSED
        _detectionState.value = idleDetectionState()
    }

    fun resume() {
        // start() is deferred to the next frame to pick up its timestamp
        // as the new session start — matches iOS resetWarmup semantics.
        isPaused = false
        engine.resetWarmup()
        hasStarted = false
        clearPreviousFrame()
        clearGateReadiness()
        _engineState.value = EngineState.READY
        _detectionState.value = idleDetectionState()
    }

    fun reset() {
        isPaused = false
        engine.reset()
        hasStarted = false
        coolingDownFramesLeft = 0
        compositeBuffer?.reset()
        compositeBuffer?.startRecording()
        lastCrossingThumbnail = null
        clearPreviousFrame()
        clearGateReadiness()
        _engineState.value = EngineState.READY
        _detectionState.value = idleDetectionState()
    }

    fun isArmed(): Boolean =
        isPhoneStable && _engineState.value == EngineState.ARMED && coolingDownFramesLeft == 0

    fun getStateDescription(): String = when (_detectionState.value) {
        PhotoFinishDetector.State.UNSTABLE -> "Hold steady"
        PhotoFinishDetector.State.NO_ATHLETE -> "Ready"
        PhotoFinishDetector.State.ATHLETE_TOO_FAR -> "Move closer"
        PhotoFinishDetector.State.READY -> "Ready"
        PhotoFinishDetector.State.TRIGGERED -> "Crossing"
        PhotoFinishDetector.State.COOLDOWN -> "Captured"
    }

    fun getComposite(): Bitmap? = compositeBuffer?.getComposite()

    /**
     * Capture a 4× downsampled grayscale thumbnail from the current frame
     * Y plane. Called only inside [processFrame] right after the engine
     * fires, while the camera buffer is still alive.
     */
    private fun captureGrayscaleThumbnail(
        yPlane: ByteArray,
        w: Int,
        h: Int,
        stride: Int
    ): Bitmap? {
        val scale = 4
        val thumbW = w / scale
        val thumbH = h / scale
        if (thumbW <= 0 || thumbH <= 0) return null

        val bitmap = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(thumbW * thumbH)
        for (y in 0 until thumbH) {
            val srcRow = y * scale * stride
            val dstRow = y * thumbW
            for (x in 0 until thumbW) {
                val lum = yPlane[srcRow + x * scale].toInt() and 0xFF
                pixels[dstRow + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }
        bitmap.setPixels(pixels, 0, thumbW, 0, 0, thumbW, thumbH)
        return bitmap
    }

    private fun clearPreviousFrame() {
        previousYPlane = null
        previousWidth = 0
        previousHeight = 0
        previousRowStride = 0
        previousPtsNanos = null
    }

    private fun updateGateReadiness(ptsNanos: Long) {
        val lastTimestamp = prebufferFrameTimes.lastOrNull()
        if (lastTimestamp != null && ptsNanos < lastTimestamp) {
            prebufferFrameTimes.clear()
        }

        prebufferFrameTimes.addLast(ptsNanos)
        val cutoff = ptsNanos - PREBUFFER_RETENTION_NANOS
        while (prebufferFrameTimes.size > 1 && prebufferFrameTimes.first() < cutoff) {
            prebufferFrameTimes.removeFirst()
        }

        val spanNanos = if (prebufferFrameTimes.size >= 2) {
            (prebufferFrameTimes.last() - prebufferFrameTimes.first()).coerceAtLeast(0L)
        } else {
            0L
        }
        val rCenter = engine.latestGateOccupancy.coerceIn(0f, 1f).toDouble()
        val previous = _gateReadinessStatus.value
        val isClear = when {
            rCenter < GATE_CLEAR_BELOW_THRESHOLD -> true
            rCenter > GATE_UNCLEAR_ABOVE_THRESHOLD -> false
            else -> previous.isClear
        }

        _gateReadinessStatus.value = GateReadinessStatus(
            isClear = isClear,
            isPrebufferReady = spanNanos >= PREBUFFER_READY_NANOS,
            isStable = isPhoneStable,
            rCenter = rCenter,
            prebufferSpanMs = spanNanos.toDouble() / 1_000_000.0,
            lastFrameTimestampNanos = ptsNanos
        )
    }

    private fun clearGateReadiness() {
        prebufferFrameTimes.clear()
        _gateReadinessStatus.value = GateReadinessStatus()
    }

    private fun idleDetectionState(): PhotoFinishDetector.State =
        if (isPhoneStable) {
            PhotoFinishDetector.State.NO_ATHLETE
        } else {
            PhotoFinishDetector.State.UNSTABLE
        }

    private fun setPhoneStable(stable: Boolean) {
        val changed = isPhoneStable != stable
        isPhoneStable = stable
        if (!changed) return

        if (stable) {
            // Frames were intentionally skipped while the phone moved. Rebuild
            // the detector baseline and warmup window from the settled view.
            engine.resetWarmup()
            hasStarted = false
            clearPreviousFrame()
            clearGateReadiness()
            if (_detectionState.value == PhotoFinishDetector.State.UNSTABLE) {
                _detectionState.value = PhotoFinishDetector.State.NO_ATHLETE
                if (!isPaused) {
                    _engineState.value = EngineState.READY
                }
            }
        } else {
            blockDetectionForMotion()
        }
    }

    private fun blockDetectionForMotion() {
        engine.resetWarmup()
        hasStarted = false
        coolingDownFramesLeft = 0
        clearPreviousFrame()
        clearGateReadiness()
        if (!isPaused) {
            _engineState.value = EngineState.READY
            _detectionState.value = PhotoFinishDetector.State.UNSTABLE
        }
    }
}

data class GateReadinessStatus(
    val isClear: Boolean = false,
    val isPrebufferReady: Boolean = false,
    val isStable: Boolean = false,
    val rCenter: Double = 0.0,
    val prebufferSpanMs: Double = 0.0,
    val lastFrameTimestampNanos: Long? = null
) {
    val canArm: Boolean get() = isClear && isPrebufferReady && isStable
}

/** A detected crossing event. */
data class CrossingEvent(
    val timestamp: Long,           // Nanoseconds (monotonic clock)
    val frameIndex: Long,
    val occupancy: Float,
    val interpolationOffsetMs: Double,
    val isTorsoLike: Boolean,
    val chestPositionNormalized: Float = 0.5f,
    val velocityPxPerSec: Float = 0f,
    val crossingDirection: String? = null,
    val workWidth: Int? = null,
    val detectorYNormalized: Float? = null,
    val interpolationAlpha: Double? = null,
    val framePick: String? = null,
    val s0: Float? = null,
    val s1: Float? = null,
    val isFrontCamera: Boolean? = null,
    val detectorTriggerFramePts: Long? = null,
    val chosenThumbnailFramePts: Long? = null,
    val savedThumbnailFramePts: Long? = null,
    val xAnchorRuntimeDisplayX: Float? = null,
    val xAnchorRuntimeRule: String? = null
)
