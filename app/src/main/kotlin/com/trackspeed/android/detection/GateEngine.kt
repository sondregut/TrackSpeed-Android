package com.trackspeed.android.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
 * Gate engine coordinator for Photo Finish detection mode.
 *
 * Wraps PhotoFinishDetector and exposes reactive state for the UI.
 * Replaces the old Precision mode (240fps + background model + pose detection).
 */
@Singleton
class GateEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GateEngine"
    }

    // Photo Finish detector (created on demand to allow re-creation on reset)
    private var detector: PhotoFinishDetector = PhotoFinishDetector(context)

    // Photo finish slit-scan composite buffer (created lazily on first frame)
    private var compositeBuffer: CompositeBuffer? = null

    // State
    private val _engineState = MutableStateFlow(EngineState.READY)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    // Crossing events
    private val _crossingEvents = MutableSharedFlow<CrossingEvent>(extraBufferCapacity = 10)
    val crossingEvents: SharedFlow<CrossingEvent> = _crossingEvents.asSharedFlow()

    // Detection state for UI
    private val _detectionState = MutableStateFlow(PhotoFinishDetector.State.UNSTABLE)
    val detectionState: StateFlow<PhotoFinishDetector.State> = _detectionState.asStateFlow()

    // FPS
    private val _currentFps = MutableStateFlow(0)
    val currentFps: StateFlow<Int> = _currentFps.asStateFlow()

    // Gate position
    private val _gatePosition = MutableStateFlow(0.5f)
    val gatePosition: StateFlow<Float> = _gatePosition.asStateFlow()

    // Temporary frame data reference (only valid during processFrame call)
    private var _currentYPlane: ByteArray? = null
    private var _currentWidth: Int = 0
    private var _currentHeight: Int = 0
    private var _currentRowStride: Int = 0

    // Last crossing thumbnail (captured synchronously in crossing callback)
    var lastCrossingThumbnail: Bitmap? = null
        private set

    enum class EngineState {
        READY,         // Photo Finish doesn't need calibration
        ARMED,
        CAPTURING,
        PAUSED
    }

    init {
        // Wire up crossing callback
        detector.onCrossingDetected = { adjustedPts, detectionPts, monotonicNanos, frameNumber, chestPositionNormalized ->
            // Capture thumbnail from current frame data (still valid during this synchronous callback)
            lastCrossingThumbnail = captureGrayscaleThumbnail()

            // Mark crossing in composite buffer
            compositeBuffer?.markCrossing()

            val event = CrossingEvent(
                timestamp = adjustedPts,
                frameIndex = frameNumber,
                occupancy = 0f,
                interpolationOffsetMs = 0.0,
                isTorsoLike = true,
                chestPositionNormalized = chestPositionNormalized
            )
            _crossingEvents.tryEmit(event)
        }
    }

    /**
     * Configure detector for given FPS.
     */
    fun configure(fps: Double, isFrontCamera: Boolean = false) {
        detector.configure(fps)
        detector.isFrontCamera = isFrontCamera
        _currentFps.value = fps.toInt()
    }

    /**
     * Start IMU monitoring for stability detection.
     */
    fun startMotionUpdates() {
        detector.startMotionUpdates()
    }

    /**
     * Stop IMU monitoring.
     */
    fun stopMotionUpdates() {
        detector.stopMotionUpdates()
    }

    /**
     * Process a camera frame.
     * @param yPlane Y (luminance) plane data
     * @param width Frame width
     * @param height Frame height
     * @param rowStride Y plane row stride
     * @param frameNumber Sequential frame number
     * @param ptsNanos Presentation timestamp in nanoseconds
     */
    fun processFrame(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        frameNumber: Long,
        ptsNanos: Long
    ) {
        detector.gatePosition = _gatePosition.value

        // Feed slit to composite buffer (photo finish image)
        val composite = compositeBuffer ?: CompositeBuffer(height).also {
            compositeBuffer = it
            it.startRecording()
        }
        composite.addSlit(yPlane, width, height, rowStride, _gatePosition.value, ptsNanos)

        // Store frame reference for thumbnail capture during crossing callback
        _currentYPlane = yPlane
        _currentWidth = width
        _currentHeight = height
        _currentRowStride = rowStride

        val result = detector.processFrame(
            yPlane = yPlane,
            width = width,
            height = height,
            rowStride = rowStride,
            frameNumber = frameNumber,
            ptsNanos = ptsNanos
        )

        // Clear reference after processing (buffer may be recycled by camera framework)
        _currentYPlane = null

        // Update UI state
        _detectionState.value = result.state

        _engineState.value = when (result.state) {
            PhotoFinishDetector.State.UNSTABLE,
            PhotoFinishDetector.State.NO_ATHLETE,
            PhotoFinishDetector.State.ATHLETE_TOO_FAR -> EngineState.READY

            PhotoFinishDetector.State.READY -> EngineState.ARMED
            PhotoFinishDetector.State.TRIGGERED,
            PhotoFinishDetector.State.COOLDOWN -> EngineState.CAPTURING
        }
    }

    fun setGatePosition(position: Float) {
        _gatePosition.value = position.coerceIn(0.05f, 0.95f)
    }

    fun pause() {
        detector.isPaused = true
        _engineState.value = EngineState.PAUSED
    }

    fun resume() {
        detector.isPaused = false
    }

    fun reset() {
        detector.reset()
        compositeBuffer?.reset()
        compositeBuffer?.startRecording()
        lastCrossingThumbnail = null
        _engineState.value = EngineState.READY
    }

    /**
     * Capture a scaled-down grayscale thumbnail from current Y-plane data.
     * Only valid during processFrame (called synchronously from crossing callback).
     */
    private fun captureGrayscaleThumbnail(): Bitmap? {
        val yPlane = _currentYPlane ?: return null
        val w = _currentWidth
        val h = _currentHeight
        val stride = _currentRowStride

        // Scale down 4x for thumbnail
        val scale = 4
        val thumbW = w / scale
        val thumbH = h / scale
        if (thumbW <= 0 || thumbH <= 0) return null

        val bitmap = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(thumbW * thumbH)
        for (y in 0 until thumbH) {
            for (x in 0 until thumbW) {
                val lum = yPlane[(y * scale) * stride + (x * scale)].toInt() and 0xFF
                pixels[y * thumbW + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }
        bitmap.setPixels(pixels, 0, thumbW, 0, 0, thumbW, thumbH)
        return bitmap
    }

    /**
     * Get the photo finish composite bitmap.
     */
    fun getComposite(): Bitmap? = compositeBuffer?.getComposite()

    fun isArmed(): Boolean {
        return detector.state == PhotoFinishDetector.State.READY && detector.isPhoneStable
    }

    /**
     * Get current detection state description for UI.
     */
    fun getStateDescription(): String = detector.stateDescription

    /**
     * Get detector for direct access (debug info etc).
     */
    fun getDetector(): PhotoFinishDetector = detector
}

/**
 * A detected crossing event.
 */
data class CrossingEvent(
    val timestamp: Long,           // Nanoseconds (monotonic clock)
    val frameIndex: Long,
    val occupancy: Float,
    val interpolationOffsetMs: Double,
    val isTorsoLike: Boolean,
    val chestPositionNormalized: Float = 0.5f  // Normalized X position (0-1) of chest for thumbnail cropping
)
