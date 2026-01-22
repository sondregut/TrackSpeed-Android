package com.trackspeed.android.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
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
 * High-speed camera manager using Camera2 API for 240fps capture.
 *
 * Key features:
 * - Constrained high-speed capture session for 240fps
 * - YUV_420_888 format for efficient luminance extraction
 * - Exposure lock for consistent background model
 * - Frame timestamp tracking with SystemClock.elapsedRealtimeNanos()
 */
@Singleton
class HighSpeedCameraManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "HighSpeedCamera"
        const val TARGET_FPS = 240
        const val FALLBACK_FPS = 120
        const val MIN_ACCEPTABLE_FPS = 60
    }

    // Camera state
    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Closed)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _currentFps = MutableStateFlow(0)
    val currentFps: StateFlow<Int> = _currentFps.asStateFlow()

    // Camera2 components
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    // Threading
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    // Camera characteristics cache
    private var selectedCameraId: String? = null
    private var selectedFpsRange: Range<Int>? = null
    private var selectedSize: Size? = null
    private var isHighSpeedSupported = false

    // Frame callback
    private var frameCallback: FrameCallback? = null

    // Frame statistics
    private var frameCount = 0L
    private var lastFrameTimestamp = 0L

    sealed class CameraState {
        object Closed : CameraState()
        object Opening : CameraState()
        data class Open(val fps: Int, val resolution: Size) : CameraState()
        object Capturing : CameraState()
        data class Error(val message: String) : CameraState()
    }

    data class FrameData(
        val luminanceBuffer: ByteArray,
        val width: Int,
        val height: Int,
        val timestampNanos: Long,
        val frameIndex: Long
    )

    fun interface FrameCallback {
        fun onFrame(frame: FrameData)
    }

    /**
     * Initialize camera and find best high-speed configuration.
     */
    fun initialize(): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            // Find back camera with high-speed support
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val configMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: continue

                // Check for high-speed video support
                val highSpeedSizes = configMap.getHighSpeedVideoSizes()
                if (highSpeedSizes.isNotEmpty()) {
                    val (size, fpsRange) = findBestHighSpeedConfig(configMap)
                    if (fpsRange != null && size != null) {
                        selectedCameraId = cameraId
                        selectedSize = size
                        selectedFpsRange = fpsRange
                        isHighSpeedSupported = true
                        _currentFps.value = fpsRange.upper
                        Log.i(TAG, "High-speed camera found: $cameraId, ${size.width}x${size.height} @ ${fpsRange.upper}fps")
                        return true
                    }
                }

                // Fallback to regular camera with highest fps
                val (regularSize, regularFps) = findBestRegularConfig(configMap)
                if (regularSize != null && regularFps >= MIN_ACCEPTABLE_FPS) {
                    selectedCameraId = cameraId
                    selectedSize = regularSize
                    selectedFpsRange = Range(regularFps, regularFps)
                    isHighSpeedSupported = false
                    _currentFps.value = regularFps
                    Log.i(TAG, "Regular camera: $cameraId, ${regularSize.width}x${regularSize.height} @ ${regularFps}fps")
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

    private fun findBestHighSpeedConfig(configMap: StreamConfigurationMap): Pair<Size?, Range<Int>?> {
        val highSpeedSizes = configMap.getHighSpeedVideoSizes()
        var bestSize: Size? = null
        var bestFpsRange: Range<Int>? = null
        var bestFps = 0

        for (size in highSpeedSizes) {
            val fpsRanges = configMap.getHighSpeedVideoFpsRangesFor(size)
            for (range in fpsRanges) {
                // Prefer 240fps, then 120fps
                val fps = range.upper
                if (fps >= TARGET_FPS && fps > bestFps) {
                    bestFps = fps
                    bestSize = size
                    bestFpsRange = range
                } else if (fps >= FALLBACK_FPS && bestFps < TARGET_FPS && fps > bestFps) {
                    bestFps = fps
                    bestSize = size
                    bestFpsRange = range
                }
            }
        }

        return Pair(bestSize, bestFpsRange)
    }

    private fun findBestRegularConfig(configMap: StreamConfigurationMap): Pair<Size?, Int> {
        val outputSizes = configMap.getOutputSizes(ImageFormat.YUV_420_888)
        // Find a reasonable size (720p or similar)
        val targetSize = outputSizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: outputSizes.firstOrNull { it.width <= 1920 }
            ?: outputSizes.firstOrNull()

        // Check available FPS ranges
        val fpsRanges = configMap.getOutputMinFrameDuration(
            ImageFormat.YUV_420_888,
            targetSize ?: return Pair(null, 0)
        )
        // Convert min frame duration to fps
        val maxFps = if (fpsRanges > 0) (1_000_000_000L / fpsRanges).toInt() else 30

        return Pair(targetSize, maxFps.coerceAtMost(60))
    }

    /**
     * Open camera and prepare for capture.
     */
    @SuppressLint("MissingPermission")
    fun openCamera(previewSurface: Surface? = null, callback: FrameCallback) {
        // Close any existing camera/threads first to prevent leaks
        closeCamera()

        val cameraId = selectedCameraId ?: run {
            _cameraState.value = CameraState.Error("Camera not initialized. Call initialize() first.")
            return
        }

        this.previewSurface = previewSurface
        this.frameCallback = callback
        _cameraState.value = CameraState.Opening

        // Start camera thread
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        // Start image processing thread
        imageThread = HandlerThread("ImageThread").apply { start() }
        imageHandler = Handler(imageThread!!.looper)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

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
        val camera = cameraDevice ?: return
        val size = selectedSize ?: return

        // Create ImageReader for YUV frames
        imageReader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            3 // Triple buffering
        ).apply {
            setOnImageAvailableListener({ reader ->
                processImage(reader)
            }, imageHandler)
        }

        val surfaces = mutableListOf<Surface>()
        surfaces.add(imageReader!!.surface)
        previewSurface?.let { surfaces.add(it) }

        try {
            if (isHighSpeedSupported) {
                createHighSpeedSession(camera, surfaces)
            } else {
                createRegularSession(camera, surfaces)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
            _cameraState.value = CameraState.Error("Session creation failed: ${e.message}")
        }
    }

    private fun createHighSpeedSession(camera: CameraDevice, surfaces: List<Surface>) {
        camera.createConstrainedHighSpeedCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    Log.i(TAG, "High-speed session configured")
                    startHighSpeedCapture(session as CameraConstrainedHighSpeedCaptureSession)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "High-speed session configuration failed")
                    _cameraState.value = CameraState.Error("High-speed session failed")
                }
            },
            cameraHandler
        )
    }

    private fun createRegularSession(camera: CameraDevice, surfaces: List<Surface>) {
        camera.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    Log.i(TAG, "Regular session configured")
                    startRegularCapture(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Regular session configuration failed")
                    _cameraState.value = CameraState.Error("Session configuration failed")
                }
            },
            cameraHandler
        )
    }

    private fun startHighSpeedCapture(session: CameraConstrainedHighSpeedCaptureSession) {
        val camera = cameraDevice ?: return
        val fpsRange = selectedFpsRange ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(imageReader!!.surface)
                previewSurface?.let { addTarget(it) }

                // Set high-speed fps range
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

                // Lock exposure for consistent detection
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }

            // Get burst requests for high-speed
            val requests = session.createHighSpeedRequestList(requestBuilder.build())

            session.setRepeatingBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    // Frame captured
                }
            }, cameraHandler)

            _cameraState.value = CameraState.Capturing
            Log.i(TAG, "High-speed capture started at ${fpsRange.upper}fps")

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start high-speed capture", e)
            _cameraState.value = CameraState.Error("Capture start failed: ${e.message}")
        }
    }

    private fun startRegularCapture(session: CameraCaptureSession) {
        val camera = cameraDevice ?: return
        val fpsRange = selectedFpsRange ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(imageReader!!.surface)
                previewSurface?.let { addTarget(it) }

                // Set fps range
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

                // Lock exposure
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }

            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)

            _cameraState.value = CameraState.Capturing
            Log.i(TAG, "Regular capture started at ${fpsRange.upper}fps")

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start regular capture", e)
            _cameraState.value = CameraState.Error("Capture start failed: ${e.message}")
        }
    }

    private fun processImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return

        try {
            // Use monotonic clock for timing (matches iOS mach_absolute_time)
            val timestamp = SystemClock.elapsedRealtimeNanos()
            frameCount++

            // Extract Y plane (luminance) from YUV_420_888
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride

            // Copy luminance data
            val width = image.width
            val height = image.height
            val luminanceBuffer = ByteArray(width * height)

            if (yPixelStride == 1 && yRowStride == width) {
                // Optimized path: contiguous buffer
                yBuffer.get(luminanceBuffer)
            } else {
                // Handle row stride padding
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(luminanceBuffer, row * width, width)
                }
            }

            // Create frame data and invoke callback
            val frameData = FrameData(
                luminanceBuffer = luminanceBuffer,
                width = width,
                height = height,
                timestampNanos = timestamp,
                frameIndex = frameCount
            )

            frameCallback?.onFrame(frameData)

            // Update FPS statistics
            if (lastFrameTimestamp > 0) {
                val elapsed = timestamp - lastFrameTimestamp
                if (elapsed > 0) {
                    val instantFps = 1_000_000_000.0 / elapsed
                    // Smooth FPS display
                    _currentFps.value = ((_currentFps.value * 0.9) + (instantFps * 0.1)).toInt()
                }
            }
            lastFrameTimestamp = timestamp

        } finally {
            image.close()
        }
    }

    /**
     * Close camera and release resources.
     */
    fun closeCamera() {
        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

        imageReader?.close()
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

    /**
     * Get camera characteristics for UI display.
     */
    fun getCameraInfo(): CameraInfo? {
        return selectedSize?.let { size ->
            CameraInfo(
                resolution = size,
                fps = _currentFps.value,
                isHighSpeed = isHighSpeedSupported
            )
        }
    }

    data class CameraInfo(
        val resolution: Size,
        val fps: Int,
        val isHighSpeed: Boolean
    )
}
