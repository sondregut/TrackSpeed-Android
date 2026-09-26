package com.trackspeed.android.ui.screens.onboarding.steps

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.audio.CrossingFeedback
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.detection.GateEngine
import com.trackspeed.android.detection.PhotoFinishDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SoloDemoUiState(
    val cameraState: CameraManager.CameraState = CameraManager.CameraState.Closed,
    val fps: Int = 0,
    val gatePosition: Float = 0.5f,
    val detectionState: PhotoFinishDetector.State = PhotoFinishDetector.State.NO_ATHLETE,
    val sensorOrientation: Int = 90,
    val isFrontCamera: Boolean = false,
    val isCapturing: Boolean = false,
    val detectionCount: Int = 0,
    val showFlash: Boolean = false,
    val thumbnails: List<Bitmap> = emptyList(),
    val cameraUnavailable: Boolean = false
)

@HiltViewModel
class SoloDemoViewModel @Inject constructor(
    private val application: Application,
    private val cameraManager: CameraManager,
    private val gateEngine: GateEngine,
    private val crossingFeedback: CrossingFeedback
) : ViewModel() {

    private val _uiState = MutableStateFlow(SoloDemoUiState())
    val uiState: StateFlow<SoloDemoUiState> = _uiState.asStateFlow()

    private var previewSurface: Surface? = null
    private var latestFrameData: CameraManager.FrameData? = null
    private var frameCount = 0L
    private var flashJob: Job? = null

    init {
        viewModelScope.launch {
            cameraManager.cameraState.collect { cameraState ->
                _uiState.update { it.copy(cameraState = cameraState) }
            }
        }
        viewModelScope.launch {
            cameraManager.currentFps.collect { fps ->
                _uiState.update { it.copy(fps = fps) }
            }
        }
        viewModelScope.launch {
            cameraManager.isFrontCamera.collect { isFront ->
                _uiState.update { it.copy(isFrontCamera = isFront) }
            }
        }
        viewModelScope.launch {
            gateEngine.gatePosition.collect { gatePosition ->
                _uiState.update { it.copy(gatePosition = gatePosition) }
            }
        }
        viewModelScope.launch {
            gateEngine.detectionState.collect { detectionState ->
                _uiState.update { it.copy(detectionState = detectionState) }
            }
        }
        viewModelScope.launch {
            gateEngine.crossingEvents.collect {
                if (_uiState.value.isCapturing) {
                    onCrossingDetected()
                }
            }
        }

        gateEngine.setGatePosition(FIXED_GATE_POSITION)
        initializeCamera()
    }

    private fun initializeCamera() {
        if (!hasCameraPermission()) return

        val initialized = cameraManager.initialize(useFrontCamera = false)
        if (initialized) {
            _uiState.update {
                it.copy(
                    sensorOrientation = cameraManager.getSensorOrientation(),
                    cameraUnavailable = false
                )
            }
        } else {
            _uiState.update { it.copy(cameraUnavailable = true) }
        }
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun onSurfaceReady(surface: Surface) {
        if (!hasCameraPermission()) {
            _uiState.update { it.copy(cameraUnavailable = true) }
            return
        }
        previewSurface = surface
        if (cameraManager.getPreviewSize() == null) {
            initializeCamera()
        }
        cameraManager.openCamera(surface) { frameData ->
            processFrame(frameData)
        }
    }

    fun onSurfaceDestroyed() {
        stopCamera()
    }

    fun beginCapture() {
        frameCount = 0L
        latestFrameData = null
        gateEngine.reset()
        gateEngine.setGatePosition(FIXED_GATE_POSITION)
        gateEngine.startMotionUpdates()
        _uiState.update {
            it.copy(
                isCapturing = true,
                detectionCount = 0,
                gatePosition = FIXED_GATE_POSITION,
                thumbnails = emptyList(),
                showFlash = false
            )
        }
    }

    fun stopCapture() {
        gateEngine.stopMotionUpdates()
        gateEngine.reset()
        _uiState.update {
            it.copy(
                isCapturing = false,
                showFlash = false,
                detectionState = PhotoFinishDetector.State.NO_ATHLETE
            )
        }
    }

    fun switchCamera() {
        val surface = previewSurface ?: return
        frameCount = 0L
        latestFrameData = null
        gateEngine.reset()
        gateEngine.setGatePosition(FIXED_GATE_POSITION)
        cameraManager.switchCamera(surface) { frameData ->
            processFrame(frameData)
        }
        _uiState.update {
            it.copy(
                sensorOrientation = cameraManager.getSensorOrientation(),
                isFrontCamera = cameraManager.isFrontCamera.value,
                gatePosition = FIXED_GATE_POSITION
            )
        }
    }

    fun stopCamera() {
        flashJob?.cancel()
        flashJob = null
        stopCapture()
        cameraManager.closeCamera()
        previewSurface = null
    }

    private fun processFrame(frameData: CameraManager.FrameData) {
        if (!_uiState.value.isCapturing) return

        frameCount++
        latestFrameData = frameData
        if (frameCount == 1L) {
            gateEngine.configure(
                fps = cameraManager.getAchievedFps().toDouble(),
                isFrontCamera = cameraManager.isFrontCamera.value
            )
        }

        gateEngine.processFrame(
            yPlane = frameData.yPlane,
            width = frameData.width,
            height = frameData.height,
            rowStride = frameData.rowStride,
            frameNumber = frameData.frameIndex,
            ptsNanos = frameData.timestampNanos,
            exposureNanos = frameData.exposureNanos
        )
    }

    private fun onCrossingDetected() {
        crossingFeedback.playCrossingBeep()
        val thumbnail = captureThumbnail() ?: gateEngine.lastCrossingThumbnail
        _uiState.update { current ->
            val thumbnails = if (thumbnail != null) {
                (current.thumbnails + thumbnail).takeLast(MAX_THUMBNAILS)
            } else {
                current.thumbnails
            }
            current.copy(
                detectionCount = current.detectionCount + 1,
                thumbnails = thumbnails,
                showFlash = true
            )
        }

        flashJob?.cancel()
        flashJob = viewModelScope.launch {
            delay(260)
            _uiState.update { it.copy(showFlash = false) }
        }
    }

    private fun captureThumbnail(): Bitmap? {
        val frame = latestFrameData ?: return null
        return try {
            val sampleW = 160
            val sampleH = 120
            val scaleX = frame.width.toFloat() / sampleW.toFloat()
            val scaleY = frame.height.toFloat() / sampleH.toFloat()

            val pixels = IntArray(sampleW * sampleH)
            for (sy in 0 until sampleH) {
                val srcY = (sy * scaleY).toInt().coerceIn(0, frame.height - 1)
                for (sx in 0 until sampleW) {
                    val srcX = (sx * scaleX).toInt().coerceIn(0, frame.width - 1)
                    val yIndex = srcY * frame.rowStride + srcX
                    val yVal = if (yIndex < frame.yPlane.size) {
                        frame.yPlane[yIndex].toInt() and 0xFF
                    } else {
                        0
                    }

                    val uvRow = srcY / 2
                    val uvCol = srcX / 2
                    val uvIndex = uvRow * frame.uvRowStride + uvCol * frame.uvPixelStride
                    val uVal = if (uvIndex < frame.uPlane.size) {
                        (frame.uPlane[uvIndex].toInt() and 0xFF) - 128
                    } else {
                        0
                    }
                    val vVal = if (uvIndex < frame.vPlane.size) {
                        (frame.vPlane[uvIndex].toInt() and 0xFF) - 128
                    } else {
                        0
                    }

                    val r = (yVal + 1.370705f * vVal).toInt().coerceIn(0, 255)
                    val g = (yVal - 0.337633f * uVal - 0.698001f * vVal).toInt().coerceIn(0, 255)
                    val b = (yVal + 1.732446f * uVal).toInt().coerceIn(0, 255)
                    pixels[sy * sampleW + sx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            val raw = Bitmap.createBitmap(pixels, sampleW, sampleH, Bitmap.Config.ARGB_8888)
            val matrix = Matrix().apply {
                val orientation = cameraManager.getSensorOrientation()
                if (orientation != 0) {
                    postRotate(orientation.toFloat())
                }
                if (cameraManager.isFrontCamera.value) {
                    postScale(-1f, 1f)
                }
            }

            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        stopCamera()
        super.onCleared()
    }

    companion object {
        private const val FIXED_GATE_POSITION = 0.5f
        private const val MAX_THUMBNAILS = 6
    }
}
