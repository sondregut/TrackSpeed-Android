package com.trackspeed.android.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camera manager using Camera2 at 30 fps with auto-exposure.
 *
 * Locked to 30 fps to match iOS commit c46bbac4 ("Match detection pipeline
 * to testing app: lock 30fps") — the new geometry-based DetectionEngine
 * was tuned and validated against 30 fps frames; running at higher rates
 * shifts noise / motion-blur thresholds the tuning depends on. The
 * `preferredFps` setter is retained for API compatibility but ignored.
 */
@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "CameraManager"
        private const val LOCKED_FPS = 30
        private const val DIAGNOSTIC_SUMMARY_FRAMES = 30
        private const val DIAGNOSTIC_SUMMARY_INTERVAL_NANOS = 5_000_000_000L
        private const val SLOW_AVG_PROCESSING_MS = 33.0
        private const val SLOW_MAX_PROCESSING_MS = 60.0
        private const val MAX_CAPTURE_METADATA_ENTRIES = 64
    }

    /**
     * Kept for API compatibility — ignored. The engine is tuned for 30 fps
     * and we don't currently support per-session overrides.
     */
    var preferredFps: Int = LOCKED_FPS
        set(value) {
            // Always clamp to the locked value; log if a caller tried to
            // change it so tuning regressions are visible in logcat.
            if (value != LOCKED_FPS) {
                Log.w(TAG, "preferredFps=$value ignored — camera is locked to ${LOCKED_FPS}fps")
            }
            field = LOCKED_FPS
        }

    // Camera state
    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Closed)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _currentFps = MutableStateFlow(0)
    val currentFps: StateFlow<Int> = _currentFps.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    // Camera2 components
    @Volatile private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    @Volatile private var isClosed = true
    private val cameraGeneration = AtomicLong(0L)

    // Threading
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    // Camera config cache
    private var selectedCameraId: String? = null
    private var selectedFpsRange: Range<Int>? = null
    private var selectedSize: Size? = null
    private var achievedFps: Int = 30
    private var sensorOrientation: Int = 0
    private var sensorTimestampSource: Int = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
    private var timestampMapper = CameraTimestampMapper(sourceIsRealtime = false)

    private data class CaptureMetadata(val exposureNanos: Long?)

    private val captureMetadataLock = Any()
    private val captureMetadataByTimestamp = LinkedHashMap<Long, CaptureMetadata>()
    @Volatile private var latestExposureNanos: Long? = null

    /** Sensor orientation in degrees (0, 90, 180, 270) */
    fun getSensorOrientation(): Int = sensorOrientation

    /** Selected camera resolution (landscape, e.g. 1280×720). Null before initialize(). */
    fun getPreviewSize(): Size? = selectedSize

    // Frame callback
    private var frameCallback: FrameCallback? = null

    // Pre-allocated frame buffers — resized only when dimensions change
    private var luminanceBuffer = ByteArray(0)
    private var uData = ByteArray(0)
    private var vData = ByteArray(0)
    private var lastBufferWidth = 0
    private var lastBufferHeight = 0

    // Frame statistics
    private var frameCount = 0L
    private var lastFrameTimestamp = 0L
    private var processingTimeSumMs = 0.0
    private var processingTimeMaxMs = 0.0
    private var processingTimeSamples = 0
    private var lastProcessingSummaryLogNanos = 0L

    @Volatile private var cameraPerformanceDiagnosticsEnabled = SettingsRepository.Defaults.CAMERA_PERFORMANCE_DIAGNOSTICS_ENABLED

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        settingsScope.launch {
            settingsRepository.cameraPerformanceDiagnosticsEnabled.collect { enabled ->
                cameraPerformanceDiagnosticsEnabled = enabled
            }
        }
    }

    sealed class CameraState {
        data object Closed : CameraState()
        data object Opening : CameraState()
        data class Open(val fps: Int, val resolution: Size) : CameraState()
        data object Capturing : CameraState()
        data class Error(val message: String) : CameraState()
    }

    data class FrameData(
        val yPlane: ByteArray,
        val uPlane: ByteArray,
        val vPlane: ByteArray,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val uvRowStride: Int,
        val uvPixelStride: Int,
        val timestampNanos: Long,
        val exposureNanos: Long?,
        val frameIndex: Long
    )

    fun interface FrameCallback {
        fun onFrame(frame: FrameData)
    }

    /**
     * Initialize the best 30 fps-capable camera config for Photo Finish.
     */
    fun initialize(useFrontCamera: Boolean = false): Boolean {
        selectedCameraId = null
        selectedSize = null
        selectedFpsRange = null
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val targetFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK

        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                if (facing != targetFacing) continue

                val configMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: continue

                val fpsRanges = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                ).orEmpty()
                val result = findBestConfig(configMap, fpsRanges)
                if (result != null) {
                    selectedCameraId = cameraId
                    selectedSize = result.first
                    selectedFpsRange = result.second
                    achievedFps = result.second.upper
                    _currentFps.value = achievedFps
                    _isFrontCamera.value = useFrontCamera
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    sensorTimestampSource = characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE
                    ) ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
                    timestampMapper = CameraTimestampMapper(
                        sourceIsRealtime = sensorTimestampSource ==
                            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                    )
                    Log.i(
                        TAG,
                        "Camera found: $cameraId (${if (useFrontCamera) "front" else "back"}), " +
                            "${result.first.width}x${result.first.height} @ ${achievedFps}fps, " +
                            "sensor=$sensorOrientation°, timestampSource=$sensorTimestampSource"
                    )
                    return true
                }
            }

            Log.e(TAG, "No suitable camera found")
            return false
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error during initialization", e)
            return false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid camera configuration during initialization", e)
            return false
        }
    }

    /**
     * Switch between front and back camera.
     */
    fun switchCamera(previewSurface: Surface? = null, callback: FrameCallback) {
        val useFront = !_isFrontCamera.value
        closeCamera()
        if (initialize(useFront)) {
            openCamera(previewSurface, callback)
        } else {
            _cameraState.value = CameraState.Error("Requested camera is not available")
        }
    }

    private fun findBestConfig(
        configMap: android.hardware.camera2.params.StreamConfigurationMap,
        availableFpsRanges: Array<out Range<Int>>
    ): Pair<Size, Range<Int>>? {
        val outputSizes = configMap.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        val selectedStream = CameraConfigurationSelector.selectStream(
            candidates = outputSizes.map { size ->
                CameraStreamCandidate(
                    width = size.width,
                    height = size.height,
                    minimumFrameDurationNanos = configMap.getOutputMinFrameDuration(
                        ImageFormat.YUV_420_888,
                        size
                    )
                )
            },
            targetFps = LOCKED_FPS
        ) ?: return null
        val selectedRange = CameraConfigurationSelector.selectFpsRange(
            availableFpsRanges.map { CameraFpsCandidate(it.lower, it.upper) },
            LOCKED_FPS
        ) ?: return null
        val targetSize = outputSizes.first {
            it.width == selectedStream.width && it.height == selectedStream.height
        }
        if (selectedStream.minimumFrameDurationNanos > 1_000_000_000L / LOCKED_FPS) {
            Log.w(
                TAG,
                "No YUV size sustains ${LOCKED_FPS}fps; using ${targetSize.width}x${targetSize.height} " +
                    "at sensor maximum ${1_000_000_000L / selectedStream.minimumFrameDurationNanos}fps"
            )
        }
        if (selectedRange.lower != LOCKED_FPS || selectedRange.upper != LOCKED_FPS) {
            Log.w(TAG, "Exact ${LOCKED_FPS}fps AE range unavailable; using ${selectedRange.lower}-${selectedRange.upper}fps")
        }
        return Pair(targetSize, Range(selectedRange.lower, selectedRange.upper))
    }

    /**
     * Open camera and start capture.
     */
    @SuppressLint("MissingPermission")
    fun openCamera(previewSurface: Surface? = null, callback: FrameCallback) {
        closeCamera()

        val cameraId = selectedCameraId ?: run {
            _cameraState.value = CameraState.Error("Camera not initialized. Call initialize() first.")
            return
        }

        this.previewSurface = previewSurface
        this.frameCallback = callback
        isClosed = false
        val generation = cameraGeneration.incrementAndGet()
        _cameraState.value = CameraState.Opening

        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        imageThread = HandlerThread("ImageThread").apply { start() }
        imageHandler = Handler(imageThread!!.looper)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (generation != cameraGeneration.get() || isClosed) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    Log.i(TAG, "Camera opened: $cameraId")
                    createCaptureSession(generation)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    if (generation == cameraGeneration.get()) {
                        closeCamera()
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    if (generation == cameraGeneration.get()) {
                        failCamera("Camera error: $error")
                    }
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
            failCamera("Failed to open camera: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission was revoked while opening", e)
            failCamera("Camera permission is required")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Camera rejected the selected configuration", e)
            failCamera("Camera configuration is not supported")
        }
    }

    private fun createCaptureSession(generation: Long) {
        if (isClosed || generation != cameraGeneration.get()) return
        val camera = cameraDevice ?: return
        val size = selectedSize ?: return

        imageReader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            3
        ).apply {
            setOnImageAvailableListener({ reader ->
                processImage(reader, generation)
            }, imageHandler)
        }

        val surfaces = mutableListOf<Surface>()
        surfaces.add(imageReader!!.surface)
        previewSurface?.let { surfaces.add(it) }

        try {
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (isClosed || generation != cameraGeneration.get()) {
                            session.close()
                            return
                        }
                        captureSession = session
                        startCapture(session, generation)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configuration failed")
                        session.close()
                        if (generation == cameraGeneration.get()) {
                            failCamera("Session configuration failed")
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
            if (generation == cameraGeneration.get()) {
                failCamera("Session creation failed: ${e.message}")
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid capture-session surface configuration", e)
            if (generation == cameraGeneration.get()) {
                failCamera("Session surfaces are not supported")
            }
        }
    }

    private fun startCapture(session: CameraCaptureSession, generation: Long) {
        if (isClosed || generation != cameraGeneration.get()) return
        val camera = cameraDevice ?: return
        val fpsRange = selectedFpsRange ?: return
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraId = selectedCameraId ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(imageReader!!.surface)
                previewSurface?.let { addTarget(it) }

                // Point & Shoot: auto-exposure (let Android handle brightness)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

                // Auto white balance
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

                // Camera is locked at 30 fps; the AE driver will pick an
                // exposure automatically within the 30 fps frame budget. We don't set sensor
                // exposure manually in AE mode — leaving this branch out
                // matches iOS commit c46bbac4 + 3726d455 (drop dead
                // thermal-throttle branches now that FPS is locked).

                // Lock focus at ~1.5-2.5m range
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                val characteristics2 = cameraManager.getCameraCharacteristics(cameraId)
                val minFocusDist = characteristics2.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                if (minFocusDist != null && minFocusDist > 0) {
                    // 0.7 * minFocusDist approximates 1.5-2.5m range
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocusDist * 0.3f)
                }

                // Disable HDR (causes frame drops)
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)

                // Disable video stabilization (we need raw frames)
                set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
            }

            if (isClosed || generation != cameraGeneration.get()) {
                session.close()
                return
            }

            session.setRepeatingRequest(
                requestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        if (generation != cameraGeneration.get()) return
                        val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        latestExposureNanos = exposure
                        synchronized(captureMetadataLock) {
                            captureMetadataByTimestamp[sensorTimestamp] = CaptureMetadata(exposure)
                            while (captureMetadataByTimestamp.size > MAX_CAPTURE_METADATA_ENTRIES) {
                                val oldest = captureMetadataByTimestamp.entries.iterator()
                                if (!oldest.hasNext()) break
                                oldest.next()
                                oldest.remove()
                            }
                        }
                    }
                },
                cameraHandler
            )

            _cameraState.value = CameraState.Capturing
            Log.i(TAG, "Capture started at ${fpsRange.upper}fps, auto-exposure, focus locked")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start capture", e)
            if (generation == cameraGeneration.get()) {
                failCamera("Capture start failed: ${e.message}")
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Camera rejected repeating request", e)
            if (generation == cameraGeneration.get()) {
                failCamera("Capture settings are not supported")
            }
        } catch (e: IllegalStateException) {
            if (isClosed || generation != cameraGeneration.get()) {
                Log.i(TAG, "Capture session closed before repeating request")
                return
            }
            Log.e(TAG, "Capture session closed unexpectedly", e)
            failCamera("Camera session ended unexpectedly")
        }
    }

    private fun processImage(reader: ImageReader, generation: Long) {
        val image = reader.acquireLatestImage() ?: return

        try {
            if (isClosed || generation != cameraGeneration.get()) return
            val callbackTimestamp = SystemClock.elapsedRealtimeNanos()
            val sensorTimestamp = image.timestamp
            val exposureNanos = synchronized(captureMetadataLock) {
                captureMetadataByTimestamp.remove(sensorTimestamp)?.exposureNanos
            } ?: latestExposureNanos
            val timestamp = timestampMapper.toElapsedRealtimeNanos(
                sensorTimestampNanos = sensorTimestamp,
                callbackElapsedRealtimeNanos = callbackTimestamp
            ) ?: return
            frameCount++

            // Extract Y plane (luminance) from YUV_420_888
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride

            val width = image.width
            val height = image.height

            // Resize pre-allocated buffers only when dimensions change
            val yBufSize = yRowStride * height
            if (width != lastBufferWidth || height != lastBufferHeight) {
                luminanceBuffer = ByteArray(yBufSize)
                lastBufferWidth = width
                lastBufferHeight = height
            } else if (luminanceBuffer.size < yBufSize) {
                luminanceBuffer = ByteArray(yBufSize)
            }

            yBuffer.rewind()
            val bytesToRead = minOf(luminanceBuffer.size, yBuffer.remaining())
            yBuffer.get(luminanceBuffer, 0, bytesToRead)

            // Extract U and V planes for color thumbnails
            val uPlaneObj = image.planes[1]
            val vPlaneObj = image.planes[2]
            val uvRowStride = uPlaneObj.rowStride
            val uvPixelStride = uPlaneObj.pixelStride
            val uBuffer = uPlaneObj.buffer
            val vBuffer = vPlaneObj.buffer
            uBuffer.rewind()
            vBuffer.rewind()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            if (uData.size < uSize) uData = ByteArray(uSize)
            if (vData.size < vSize) vData = ByteArray(vSize)
            uBuffer.get(uData, 0, uSize)
            vBuffer.get(vData, 0, vSize)

            val frameData = FrameData(
                yPlane = luminanceBuffer.copyOf(bytesToRead),
                uPlane = uData.copyOf(uSize),
                vPlane = vData.copyOf(vSize),
                width = width,
                height = height,
                rowStride = yRowStride,
                uvRowStride = uvRowStride,
                uvPixelStride = uvPixelStride,
                timestampNanos = timestamp,
                exposureNanos = exposureNanos,
                frameIndex = frameCount
            )

            val callbackStartNanos = SystemClock.elapsedRealtimeNanos()
            frameCallback?.onFrame(frameData)
            val callbackMs = (SystemClock.elapsedRealtimeNanos() - callbackStartNanos) / 1_000_000.0
            recordProcessingSummary(callbackMs, timestamp)

            // Update FPS statistics
            if (lastFrameTimestamp > 0) {
                val elapsed = timestamp - lastFrameTimestamp
                if (elapsed > 0) {
                    val instantFps = 1_000_000_000.0 / elapsed
                    _currentFps.value = ((_currentFps.value * 0.9) + (instantFps * 0.1)).toInt()
                }
            }
            lastFrameTimestamp = timestamp
        } finally {
            image.close()
        }
    }

    private fun recordProcessingSummary(processingMs: Double, timestampNanos: Long) {
        processingTimeSumMs += processingMs
        processingTimeMaxMs = maxOf(processingTimeMaxMs, processingMs)
        processingTimeSamples++

        if (processingTimeSamples < DIAGNOSTIC_SUMMARY_FRAMES) return

        val avgMs = processingTimeSumMs / processingTimeSamples
        val isSlow = avgMs >= SLOW_AVG_PROCESSING_MS || processingTimeMaxMs >= SLOW_MAX_PROCESSING_MS
        val shouldLog = (cameraPerformanceDiagnosticsEnabled || isSlow) &&
            (lastProcessingSummaryLogNanos == 0L ||
                timestampNanos - lastProcessingSummaryLogNanos >= DIAGNOSTIC_SUMMARY_INTERVAL_NANOS)

        if (shouldLog) {
            val size = selectedSize
            val resolution = if (size != null) "${size.width}x${size.height}" else "unknown"
            Log.i(
                TAG,
                "[PROC_MS] avg=${"%.1f".format(Locale.US, processingTimeSumMs / processingTimeSamples)} " +
                    "max=${"%.1f".format(Locale.US, processingTimeMaxMs)} samples=$processingTimeSamples " +
                    "frame=$frameCount fps=${_currentFps.value} target=$LOCKED_FPS resolution=$resolution"
            )
            lastProcessingSummaryLogNanos = timestampNanos
        }

        processingTimeSumMs = 0.0
        processingTimeMaxMs = 0.0
        processingTimeSamples = 0
    }

    fun closeCamera() {
        cleanupCameraResources(publishClosedState = true)
    }

    private fun failCamera(message: String) {
        cleanupCameraResources(publishClosedState = false)
        _cameraState.value = CameraState.Error(message)
    }

    private fun cleanupCameraResources(publishClosedState: Boolean) {
        cameraGeneration.incrementAndGet()
        isClosed = true

        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null

        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null

        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        imageThread?.quitSafely()
        imageThread = null
        imageHandler = null

        frameCallback = null
        previewSurface = null

        frameCount = 0
        lastFrameTimestamp = 0
        processingTimeSumMs = 0.0
        processingTimeMaxMs = 0.0
        processingTimeSamples = 0
        lastProcessingSummaryLogNanos = 0L
        lastBufferWidth = 0
        lastBufferHeight = 0
        synchronized(captureMetadataLock) {
            captureMetadataByTimestamp.clear()
        }
        latestExposureNanos = null
        timestampMapper.reset()

        if (publishClosedState) {
            _cameraState.value = CameraState.Closed
        }
        Log.i(TAG, "Camera closed")
    }

    fun getAchievedFps(): Int = achievedFps

    data class CameraInfo(
        val resolution: Size,
        val fps: Int,
        val isFrontCamera: Boolean
    )

    fun getCameraInfo(): CameraInfo? {
        return selectedSize?.let { size ->
            CameraInfo(
                resolution = size,
                fps = achievedFps,
                isFrontCamera = _isFrontCamera.value
            )
        }
    }
}
