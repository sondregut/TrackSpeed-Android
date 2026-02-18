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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camera manager using standard Camera2 API at 30-120fps.
 *
 * Photo Finish mode doesn't need 240fps high-speed capture.
 * Uses auto-exposure (point-and-shoot) with frame rate capping.
 *
 * Ported from iOS CameraManager.swift Point & Shoot mode.
 */
@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CameraManager"
        private val DEFAULT_FPS_ORDER = intArrayOf(120, 60, 30)
    }

    /** Set preferred FPS before calling initialize(). */
    var preferredFps: Int = 120
        set(value) {
            field = value.coerceIn(30, 120)
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

    /** Sensor orientation in degrees (0, 90, 180, 270) */
    fun getSensorOrientation(): Int = sensorOrientation

    // Frame callback
    private var frameCallback: FrameCallback? = null

    // Frame statistics
    private var frameCount = 0L
    private var lastFrameTimestamp = 0L

    sealed class CameraState {
        data object Closed : CameraState()
        data object Opening : CameraState()
        data class Open(val fps: Int, val resolution: Size) : CameraState()
        data object Capturing : CameraState()
        data class Error(val message: String) : CameraState()
    }

    data class FrameData(
        val yPlane: ByteArray,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val timestampNanos: Long,
        val frameIndex: Long
    )

    fun interface FrameCallback {
        fun onFrame(frame: FrameData)
    }

    /**
     * Initialize camera - find best camera config for Photo Finish.
     * Prefers highest FPS up to 120, with at least 720p resolution.
     */
    fun initialize(useFrontCamera: Boolean = false): Boolean {
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

                val result = findBestConfig(configMap)
                if (result != null) {
                    selectedCameraId = cameraId
                    selectedSize = result.first
                    selectedFpsRange = result.second
                    achievedFps = result.second.upper
                    _currentFps.value = achievedFps
                    _isFrontCamera.value = useFrontCamera
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    Log.i(TAG, "Camera found: $cameraId (${if (useFrontCamera) "front" else "back"}), ${result.first.width}x${result.first.height} @ ${achievedFps}fps, sensor=$sensorOrientation°")
                    return true
                }
            }

            Log.e(TAG, "No suitable camera found")
            return false
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error during initialization", e)
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
            this.previewSurface = previewSurface
            this.frameCallback = callback
            isClosed = false
            _cameraState.value = CameraState.Opening

            cameraThread = HandlerThread("CameraThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
            imageThread = HandlerThread("ImageThread").apply { start() }
            imageHandler = Handler(imageThread!!.looper)

            val sysCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            try {
                @SuppressLint("MissingPermission")
                sysCameraManager.openCamera(selectedCameraId!!, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession()
                    }
                    override fun onDisconnected(camera: CameraDevice) { closeCamera() }
                    override fun onError(camera: CameraDevice, error: Int) {
                        _cameraState.value = CameraState.Error("Camera error: $error")
                        closeCamera()
                    }
                }, cameraHandler)
            } catch (e: CameraAccessException) {
                _cameraState.value = CameraState.Error("Failed to switch camera: ${e.message}")
            }
        }
    }

    private fun findBestConfig(configMap: android.hardware.camera2.params.StreamConfigurationMap): Pair<Size, Range<Int>>? {
        val outputSizes = configMap.getOutputSizes(ImageFormat.YUV_420_888) ?: return null

        // Find best resolution (prefer 1080p, accept 720p+)
        val goodSizes = outputSizes.filter { it.width >= 1280 && it.width <= 1920 }
            .sortedByDescending { it.width * it.height }

        val targetSize = goodSizes.firstOrNull()
            ?: outputSizes.filter { it.width >= 640 }.maxByOrNull { it.width * it.height }
            ?: return null

        // Build FPS priority: preferred first, then fallbacks in descending order
        val fpsOrder = buildList {
            add(preferredFps)
            for (fps in DEFAULT_FPS_ORDER) {
                if (fps != preferredFps && fps <= preferredFps) add(fps)
            }
            if (!contains(30)) add(30)
        }

        // Try each FPS in order
        for (targetFps in fpsOrder) {
            val minDuration = configMap.getOutputMinFrameDuration(ImageFormat.YUV_420_888, targetSize)
            if (minDuration > 0) {
                val maxPossibleFps = (1_000_000_000L / minDuration).toInt()
                if (maxPossibleFps >= targetFps) {
                    return Pair(targetSize, Range(targetFps, targetFps))
                }
            }
        }

        // Fallback to 30fps
        return Pair(targetSize, Range(30, 30))
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
        _cameraState.value = CameraState.Opening

        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        imageThread = HandlerThread("ImageThread").apply { start() }
        imageHandler = Handler(imageThread!!.looper)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.i(TAG, "Camera opened: $cameraId")
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    _cameraState.value = CameraState.Error("Camera error: $error")
                    closeCamera()
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
            _cameraState.value = CameraState.Error("Failed to open camera: ${e.message}")
        }
    }

    private fun createCaptureSession() {
        if (isClosed) return
        val camera = cameraDevice ?: return
        val size = selectedSize ?: return

        imageReader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            3
        ).apply {
            setOnImageAvailableListener({ reader ->
                processImage(reader)
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
                        if (isClosed) {
                            session.close()
                            return
                        }
                        captureSession = session
                        startCapture(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configuration failed")
                        _cameraState.value = CameraState.Error("Session configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
            _cameraState.value = CameraState.Error("Session creation failed: ${e.message}")
        }
    }

    private fun startCapture(session: CameraCaptureSession) {
        if (isClosed) return
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

                // Cap exposure duration to prevent motion blur at high FPS
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                if (exposureRange != null) {
                    val maxExposureNs = when {
                        fpsRange.upper >= 100 -> 4_000_000L   // 4ms @ 120fps
                        fpsRange.upper >= 50 -> 8_000_000L    // 8ms @ 60fps
                        else -> 16_700_000L                     // 16.7ms @ 30fps
                    }
                    // AE will auto-adjust ISO to compensate
                    // We don't directly set sensor exposure in AE mode, but we can set AE antibanding
                }

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

            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)

            _cameraState.value = CameraState.Capturing
            Log.i(TAG, "Capture started at ${fpsRange.upper}fps, auto-exposure, focus locked")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start capture", e)
            _cameraState.value = CameraState.Error("Capture start failed: ${e.message}")
        }
    }

    private fun processImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return

        try {
            val timestamp = SystemClock.elapsedRealtimeNanos()
            frameCount++

            // Extract Y plane (luminance) from YUV_420_888
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride

            val width = image.width
            val height = image.height
            val luminanceBuffer = ByteArray(yRowStride * height)

            yBuffer.rewind()
            val bytesToRead = minOf(luminanceBuffer.size, yBuffer.remaining())
            yBuffer.get(luminanceBuffer, 0, bytesToRead)

            val frameData = FrameData(
                yPlane = luminanceBuffer,
                width = width,
                height = height,
                rowStride = yRowStride,
                timestampNanos = timestamp,
                frameIndex = frameCount
            )

            frameCallback?.onFrame(frameData)

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

    fun closeCamera() {
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

        _cameraState.value = CameraState.Closed
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
