package com.trackspeed.android.ui.screens.timing

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.camera.FrameProcessor
import com.trackspeed.android.camera.HighSpeedCameraManager
import com.trackspeed.android.detection.GateEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class BasicTimingViewModel @Inject constructor(
    private val cameraManager: HighSpeedCameraManager,
    private val frameProcessor: FrameProcessor,
    private val gateEngine: GateEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(BasicTimingUiState())
    val uiState: StateFlow<BasicTimingUiState> = _uiState.asStateFlow()

    // Timing state - protected by mutex for thread safety
    private val timingMutex = Mutex()
    private var startTimeNanos: Long? = null
    private var lastCrossingTimeNanos: Long? = null
    private val _splits = mutableListOf<Double>()

    init {
        // Observe camera state
        viewModelScope.launch {
            cameraManager.cameraState.collect { state ->
                _uiState.update { it.copy(cameraState = state) }
            }
        }

        // Observe FPS
        viewModelScope.launch {
            cameraManager.currentFps.collect { fps ->
                _uiState.update { it.copy(fps = fps) }
            }
        }

        // Observe gate position
        viewModelScope.launch {
            frameProcessor.gatePosition.collect { position ->
                _uiState.update { it.copy(gatePosition = position) }
            }
        }

        // Observe gate engine state
        viewModelScope.launch {
            gateEngine.engineState.collect { state ->
                _uiState.update {
                    it.copy(
                        isCalibrating = state == GateEngine.EngineState.CALIBRATING,
                        isArmed = state == GateEngine.EngineState.ARMED
                    )
                }
            }
        }

        // Observe calibration progress
        viewModelScope.launch {
            gateEngine.calibrationProgress.collect { progress ->
                _uiState.update { it.copy(calibrationProgress = progress) }
            }
        }

        // Observe crossing events
        viewModelScope.launch {
            gateEngine.crossingEvents.collect { event ->
                onCrossingDetected(event.timestamp)
            }
        }

        // Initialize camera and handle errors
        initializeCamera()
    }

    private fun initializeCamera() {
        val initialized = cameraManager.initialize()
        if (!initialized) {
            _uiState.update {
                it.copy(
                    cameraState = HighSpeedCameraManager.CameraState.Error(
                        "No suitable camera found. This device may not support high-speed capture."
                    )
                )
            }
        }
    }

    fun onCameraPermissionGranted() {
        _uiState.update { it.copy(hasPermission = true) }
        openCamera()
    }

    fun onSurfaceReady(surface: Surface) {
        // openCamera already calls closeCamera first (fixed in HighSpeedCameraManager)
        cameraManager.openCamera(surface) { frameData ->
            processFrame(frameData)
        }
    }

    fun onSurfaceDestroyed() {
        cameraManager.closeCamera()
    }

    private fun openCamera() {
        cameraManager.openCamera { frameData ->
            processFrame(frameData)
        }
    }

    private fun processFrame(frameData: HighSpeedCameraManager.FrameData) {
        // Start calibration on first frame if not already calibrating/calibrated
        if (frameData.frameIndex == 1L &&
            gateEngine.engineState.value == GateEngine.EngineState.NOT_CALIBRATED) {
            gateEngine.startCalibration()
        }

        // Pass frame to gate engine for processing
        gateEngine.processFrame(frameData)
    }

    fun setGatePosition(position: Float) {
        frameProcessor.setGatePosition(position)
        // Reset calibration when gate position changes significantly
        if (gateEngine.isCalibrated()) {
            gateEngine.reset()
        }
    }

    fun startTiming() {
        viewModelScope.launch {
            timingMutex.withLock {
                val currentState = _uiState.value
                if (currentState.isArmed && !currentState.isRunning) {
                    _splits.clear()
                    startTimeNanos = null  // Will be set on first crossing
                    _uiState.update {
                        it.copy(
                            isRunning = true,
                            currentTime = 0.0,
                            waitingForStart = true,
                            splits = emptyList()
                        )
                    }
                }
            }
        }
    }

    fun stopTiming() {
        viewModelScope.launch {
            timingMutex.withLock {
                val currentState = _uiState.value
                if (currentState.isRunning) {
                    val endTime = System.nanoTime()
                    val elapsed = startTimeNanos?.let { start ->
                        (endTime - start) / 1_000_000_000.0
                    } ?: 0.0

                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            waitingForStart = false,
                            currentTime = elapsed,
                            splits = _splits.toList()
                        )
                    }
                }
            }
        }
    }

    fun resetTiming() {
        viewModelScope.launch {
            timingMutex.withLock {
                val currentState = _uiState.value
                if (!currentState.isRunning) {
                    _splits.clear()
                    startTimeNanos = null
                    lastCrossingTimeNanos = null
                    _uiState.update {
                        it.copy(
                            currentTime = 0.0,
                            splits = emptyList(),
                            waitingForStart = false
                        )
                    }
                }
            }
        }
    }

    fun recalibrate() {
        gateEngine.reset()
        gateEngine.startCalibration()
    }

    /**
     * Called when a crossing is detected by the gate engine.
     * Uses mutex to prevent race conditions with UI controls.
     */
    private fun onCrossingDetected(timestampNanos: Long) {
        viewModelScope.launch {
            timingMutex.withLock {
                val currentState = _uiState.value

                if (!currentState.isRunning) {
                    // Not in timing mode - ignore
                    return@withLock
                }

                if (currentState.waitingForStart) {
                    // This is a start crossing - begin timing
                    startTimeNanos = timestampNanos
                    _uiState.update {
                        it.copy(
                            waitingForStart = false,
                            currentTime = 0.0
                        )
                    }
                } else {
                    // This is a finish/split crossing
                    startTimeNanos?.let { start ->
                        val elapsed = (timestampNanos - start) / 1_000_000_000.0
                        _splits.add(elapsed)
                        _uiState.update {
                            it.copy(
                                currentTime = elapsed,
                                splits = _splits.toList()
                            )
                        }
                    }
                }

                lastCrossingTimeNanos = timestampNanos
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.closeCamera()
        gateEngine.reset()
    }
}

data class BasicTimingUiState(
    val hasPermission: Boolean = false,
    val cameraState: HighSpeedCameraManager.CameraState = HighSpeedCameraManager.CameraState.Closed,
    val fps: Int = 0,
    val gatePosition: Float = 0.5f,
    val isCalibrating: Boolean = false,
    val calibrationProgress: Float = 0f,
    val isArmed: Boolean = false,
    val isRunning: Boolean = false,
    val waitingForStart: Boolean = false,
    val currentTime: Double = 0.0,
    val splits: List<Double> = emptyList()
)
