package com.trackspeed.android.ui.screens.timing

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.audio.CrossingFeedback
import com.trackspeed.android.audio.VoiceStartService
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.detection.GateEngine
import com.trackspeed.android.detection.PhotoFinishDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class BasicTimingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val cameraManager: CameraManager,
    private val gateEngine: GateEngine,
    private val crossingFeedback: CrossingFeedback,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val athleteDao: AthleteDao,
    val voiceStartService: VoiceStartService,
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {

    // Session configuration from navigation arguments (overrides settings defaults)
    private var sessionDistance: Double = (savedStateHandle.get<Float>("distance") ?: SettingsRepository.Defaults.DISTANCE.toFloat()).toDouble()
    private var sessionStartType: String = savedStateHandle.get<String>("startType") ?: SettingsRepository.Defaults.START_TYPE

    // Athletes selected for this session
    private val athleteIdsRaw: String = savedStateHandle.get<String>("athleteIds") ?: ""
    private var sessionAthletes: List<AthleteEntity> = emptyList()

    val isProUser: StateFlow<Boolean> = subscriptionManager.isProUser

    /** Raw StateFlow for InFrameStartOverlay (needs StateFlow, not snapshot). */
    val detectionStateFlow: StateFlow<PhotoFinishDetector.State> = gateEngine.detectionState

    private val _uiState = MutableStateFlow(BasicTimingUiState(
        distance = sessionDistance,
        startType = sessionStartType
    ))
    val uiState: StateFlow<BasicTimingUiState> = _uiState.asStateFlow()

    // Timing state
    private val timingMutex = Mutex()
    private var startTimeNanos: Long? = null
    private var lastCrossingTimeNanos: Long? = null
    private val _laps = mutableListOf<SoloLapResult>()
    private var frameCount = 0L
    private var lapCounter = 0

    // Timer tick job for live clock updates
    private var timerTickJob: Job? = null

    // Latest frame for thumbnail capture
    @Volatile private var latestFrameData: CameraManager.FrameData? = null
    private var previewSurface: Surface? = null

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
            gateEngine.gatePosition.collect { position ->
                _uiState.update { it.copy(gatePosition = position) }
            }
        }

        // Observe detection state
        viewModelScope.launch {
            gateEngine.detectionState.collect { state ->
                _uiState.update { it.copy(detectionState = state) }
            }
        }

        // Observe engine state (armed, etc.)
        viewModelScope.launch {
            gateEngine.engineState.collect { state ->
                _uiState.update {
                    it.copy(isArmed = state == GateEngine.EngineState.ARMED)
                }
            }
        }

        // Observe crossing events
        viewModelScope.launch {
            gateEngine.crossingEvents.collect { event ->
                onCrossingDetected(event.timestamp)
            }
        }

        // Observe front camera state
        viewModelScope.launch {
            cameraManager.isFrontCamera.collect { isFront ->
                _uiState.update { it.copy(isFrontCamera = isFront) }
            }
        }

        // Only observe settings if no nav args were provided (defaults were used).
        // Nav args take priority over settings when navigating from a template.
        val navDistance = savedStateHandle.get<Float>("distance")
        val navStartType = savedStateHandle.get<String>("startType")
        if (navDistance == null) {
            viewModelScope.launch {
                settingsRepository.defaultDistance.collect { distance ->
                    sessionDistance = distance
                    _uiState.update { it.copy(distance = distance) }
                }
            }
        }
        if (navStartType == null) {
            viewModelScope.launch {
                settingsRepository.startType.collect { startType ->
                    sessionStartType = startType
                    _uiState.update { it.copy(startType = startType) }
                }
            }
        }

        // Observe speed unit from settings
        viewModelScope.launch {
            settingsRepository.speedUnit.collect { unit ->
                _uiState.update { it.copy(speedUnit = unit) }
            }
        }

        // Load selected athletes from navigation argument
        viewModelScope.launch {
            val ids = athleteIdsRaw.split(",").filter { it.isNotBlank() }
            if (ids.isNotEmpty()) {
                sessionAthletes = ids.mapNotNull { athleteDao.getAthleteById(it) }
            }
        }

        // Initialize camera early so preview dimensions are available for configureTransform.
        // No suspend before initialize() — runs synchronously via Dispatchers.Main.immediate,
        // completing before the TextureView surface is created (matches RaceMode pattern).
        viewModelScope.launch {
            val initialized = cameraManager.initialize()
            if (initialized) {
                val previewSize = cameraManager.getPreviewSize()
                _uiState.update {
                    it.copy(
                        sensorOrientation = cameraManager.getSensorOrientation(),
                        previewWidth = previewSize?.width ?: 0,
                        previewHeight = previewSize?.height ?: 0
                    )
                }
            } else {
                _uiState.update {
                    it.copy(cameraState = CameraManager.CameraState.Error("No suitable camera found."))
                }
            }
        }

        // Load preferred FPS from settings (async, OK to be late — default is 120fps)
        viewModelScope.launch {
            val fps = settingsRepository.getPreferredFpsOnce()
            cameraManager.preferredFps = fps
        }
    }

    fun onCameraPermissionGranted() {
        _uiState.update { it.copy(hasPermission = true) }
    }

    fun onSurfaceReady(surface: Surface) {
        if (!_uiState.value.hasPermission) return
        previewSurface = surface
        frameCount = 0
        cameraManager.openCamera(surface) { frameData -> processFrame(frameData) }
    }

    fun onSurfaceDestroyed() {
        gateEngine.stopMotionUpdates()
        cameraManager.closeCamera()
    }

    fun switchCamera() {
        frameCount = 0
        gateEngine.stopMotionUpdates()
        cameraManager.switchCamera(previewSurface) { frameData ->
            processFrame(frameData)
        }
        // Re-read dimensions after switch (switchCamera calls initialize internally)
        val previewSize = cameraManager.getPreviewSize()
        _uiState.update {
            it.copy(
                sensorOrientation = cameraManager.getSensorOrientation(),
                isFrontCamera = cameraManager.isFrontCamera.value,
                previewWidth = previewSize?.width ?: 0,
                previewHeight = previewSize?.height ?: 0
            )
        }
    }

    private fun processFrame(frameData: CameraManager.FrameData) {
        frameCount++
        latestFrameData = frameData

        // Start IMU monitoring on first frame
        if (frameCount == 1L) {
            val fps = cameraManager.getAchievedFps().toDouble()
            val isFront = cameraManager.isFrontCamera.value
            gateEngine.configure(fps, isFront)
            gateEngine.startMotionUpdates()
        }

        // Pass frame to gate engine
        gateEngine.processFrame(
            yPlane = frameData.yPlane,
            width = frameData.width,
            height = frameData.height,
            rowStride = frameData.rowStride,
            frameNumber = frameData.frameIndex,
            ptsNanos = frameData.timestampNanos
        )
    }

    fun setGatePosition(position: Float) {
        gateEngine.setGatePosition(position)
    }

    private fun startTimerTick() {
        timerTickJob?.cancel()
        timerTickJob = viewModelScope.launch {
            while (true) {
                delay(100) // 10Hz tick
                val start = startTimeNanos ?: continue
                val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
                _uiState.update { it.copy(currentTime = elapsed) }
            }
        }
    }

    private fun stopTimerTick() {
        timerTickJob?.cancel()
        timerTickJob = null
    }

    fun startTiming() {
        viewModelScope.launch {
            timingMutex.withLock {
                val currentState = _uiState.value
                if (!currentState.isRunning) {
                    _laps.clear()
                    lapCounter = 0
                    startTimeNanos = null
                    _uiState.update {
                        it.copy(
                            isRunning = true,
                            currentTime = 0.0,
                            waitingForStart = true,
                            laps = emptyList()
                        )
                    }
                }
            }
        }
    }

    /**
     * Handle an external start event from Touch, Countdown, Voice, or InFrame overlays.
     * Sets the start timestamp directly and begins the timer tick.
     */
    fun handleExternalStart(timestampNanos: Long) {
        viewModelScope.launch {
            timingMutex.withLock {
                _laps.clear()
                lapCounter = 0
                startTimeNanos = timestampNanos
                val startLap = SoloLapResult(
                    lapNumber = 0,
                    totalTimeSeconds = 0.0,
                    lapTimeSeconds = 0.0,
                    thumbnail = null,
                    gatePosition = _uiState.value.gatePosition
                )
                _laps.add(startLap)
                _uiState.update {
                    it.copy(
                        isRunning = true,
                        waitingForStart = false,
                        currentTime = 0.0,
                        laps = _laps.toList()
                    )
                }
                startTimerTick()
            }
        }
    }

    fun stopTiming() {
        stopTimerTick()
        viewModelScope.launch {
            timingMutex.withLock {
                val currentState = _uiState.value
                if (currentState.isRunning) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            waitingForStart = false
                        )
                    }
                }
            }
        }
    }

    fun resetTiming() {
        stopTimerTick()
        viewModelScope.launch {
            timingMutex.withLock {
                val currentState = _uiState.value
                if (!currentState.isRunning) {
                    _laps.clear()
                    lapCounter = 0
                    startTimeNanos = null
                    lastCrossingTimeNanos = null
                    gateEngine.reset()
                    _uiState.update {
                        it.copy(
                            currentTime = 0.0,
                            laps = emptyList(),
                            waitingForStart = false,
                            currentSpeedMs = 0.0
                        )
                    }
                }
            }
        }
    }

    private fun onCrossingDetected(timestampNanos: Long) {
        // Audio + haptic feedback immediately
        crossingFeedback.playCrossingBeep()

        // Capture timestamp and thumbnail immediately (before coroutine dispatch)
        val crossingNanos = System.nanoTime()
        val thumbnail = captureThumbnail()

        viewModelScope.launch {
            timingMutex.withLock {
                val currentState = _uiState.value

                if (!currentState.isRunning) return@withLock

                if (currentState.waitingForStart) {
                    // First crossing = START
                    startTimeNanos = crossingNanos
                    lapCounter = 0
                    val startLap = SoloLapResult(
                        lapNumber = 0,
                        totalTimeSeconds = 0.0,
                        lapTimeSeconds = 0.0,
                        thumbnail = thumbnail,
                        gatePosition = currentState.gatePosition
                    )
                    _laps.add(startLap)
                    _uiState.update {
                        it.copy(
                            waitingForStart = false,
                            currentTime = 0.0,
                            laps = _laps.toList()
                        )
                    }
                    // Start the live timer tick
                    startTimerTick()
                } else {
                    startTimeNanos?.let { start ->
                        lapCounter++
                        val totalElapsed = (crossingNanos - start) / 1_000_000_000.0
                        val prevTotal = if (_laps.size > 1) {
                            _laps.last().totalTimeSeconds
                        } else {
                            0.0
                        }
                        val lapTime = totalElapsed - prevTotal

                        // Compute speed for this lap (m/s)
                        val lapSpeedMs = if (lapTime > 0.0) {
                            currentState.distance / lapTime
                        } else {
                            0.0
                        }

                        val lap = SoloLapResult(
                            lapNumber = lapCounter,
                            totalTimeSeconds = totalElapsed,
                            lapTimeSeconds = lapTime,
                            thumbnail = thumbnail,
                            gatePosition = currentState.gatePosition,
                            speedMs = lapSpeedMs
                        )
                        _laps.add(lap)

                        _uiState.update {
                            it.copy(
                                currentTime = totalElapsed,
                                laps = _laps.toList(),
                                currentSpeedMs = lapSpeedMs
                            )
                        }

                        // Announce the lap time via voice if enabled
                        crossingFeedback.announceTime(lapTime)
                    }
                }

                lastCrossingTimeNanos = crossingNanos
            }
        }
    }

    /**
     * Capture a thumbnail from the latest camera frame at the moment of crossing.
     */
    private fun captureThumbnail(): Bitmap? {
        val frame = latestFrameData ?: return null
        return try {
            val orientation = cameraManager.getSensorOrientation()
            val isFront = cameraManager.isFrontCamera.value

            // Sample from the landscape camera buffer at reduced size
            val sampleW = 160  // landscape width
            val sampleH = 120  // landscape height
            val scaleX = frame.width.toFloat() / sampleW
            val scaleY = frame.height.toFloat() / sampleH

            val pixels = IntArray(sampleW * sampleH)
            for (sy in 0 until sampleH) {
                val srcY = (sy * scaleY).toInt().coerceIn(0, frame.height - 1)
                for (sx in 0 until sampleW) {
                    val srcX = (sx * scaleX).toInt().coerceIn(0, frame.width - 1)
                    val yVal = frame.yPlane[srcY * frame.rowStride + srcX].toInt() and 0xFF

                    // UV is subsampled 2x2
                    val uvRow = srcY / 2
                    val uvCol = srcX / 2
                    val uvIdx = uvRow * frame.uvRowStride + uvCol * frame.uvPixelStride
                    val uVal = if (uvIdx < frame.uPlane.size) (frame.uPlane[uvIdx].toInt() and 0xFF) - 128 else 0
                    val vVal = if (uvIdx < frame.vPlane.size) (frame.vPlane[uvIdx].toInt() and 0xFF) - 128 else 0

                    val r = (yVal + 1.370705f * vVal).toInt().coerceIn(0, 255)
                    val g = (yVal - 0.337633f * uVal - 0.698001f * vVal).toInt().coerceIn(0, 255)
                    val b = (yVal + 1.732446f * uVal).toInt().coerceIn(0, 255)

                    pixels[sy * sampleW + sx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            var bitmap = Bitmap.createBitmap(pixels, sampleW, sampleH, Bitmap.Config.ARGB_8888)

            // Rotate to match display orientation
            if (orientation != 0) {
                val matrix = Matrix()
                matrix.postRotate(orientation.toFloat())
                if (isFront) {
                    matrix.postScale(-1f, 1f)
                }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, sampleW, sampleH, matrix, true)
            }

            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Called from the composable to update session config from navigation arguments.
     */
    fun setSessionConfig(distance: Double, startType: String) {
        sessionDistance = distance
        sessionStartType = startType
        _uiState.update {
            it.copy(distance = distance, startType = startType)
        }
    }

    /**
     * Check if a start mode is available to the current user.
     */
    fun canUseStartMode(modeName: String): Boolean {
        return subscriptionManager.canUseStartMode(modeName)
    }

    fun saveSession() {
        viewModelScope.launch {
            val laps = _laps.toList()
            if (laps.size <= 1) return@launch // Need at least one actual lap

            if (!subscriptionManager.canSaveSession()) {
                _uiState.update { it.copy(showPaywallPrompt = true) }
                return@launch
            }

            sessionRepository.saveSession(
                name = null,
                distance = sessionDistance,
                startType = sessionStartType,
                laps = laps,
                athletes = sessionAthletes
            )
            _uiState.update { it.copy(sessionSaved = true) }
        }
    }

    fun onPaywallPromptConsumed() {
        _uiState.update { it.copy(showPaywallPrompt = false) }
    }

    fun onSessionSavedConsumed() {
        _uiState.update { it.copy(sessionSaved = false) }
    }

    override fun onCleared() {
        super.onCleared()
        stopTimerTick()
        gateEngine.stopMotionUpdates()
        cameraManager.closeCamera()
        gateEngine.reset()
    }
}

/**
 * A single lap result in solo mode.
 */
data class SoloLapResult(
    val lapNumber: Int,            // 0 = START, 1+ = laps
    val totalTimeSeconds: Double,  // Cumulative time from start
    val lapTimeSeconds: Double,    // Time for this specific lap
    val thumbnail: Bitmap?,        // Grayscale frame at crossing
    val gatePosition: Float,       // Gate position for overlay
    val speedMs: Double = 0.0      // Speed in meters per second for this lap
)

data class BasicTimingUiState(
    val hasPermission: Boolean = false,
    val cameraState: CameraManager.CameraState = CameraManager.CameraState.Closed,
    val fps: Int = 0,
    val gatePosition: Float = 0.5f,
    val detectionState: PhotoFinishDetector.State = PhotoFinishDetector.State.UNSTABLE,
    val isArmed: Boolean = false,
    val isRunning: Boolean = false,
    val waitingForStart: Boolean = false,
    val currentTime: Double = 0.0,
    val laps: List<SoloLapResult> = emptyList(),
    val sessionSaved: Boolean = false,
    val showPaywallPrompt: Boolean = false,
    val sensorOrientation: Int = 90,
    val isFrontCamera: Boolean = false,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val distance: Double = 60.0,
    val startType: String = "standing",
    val currentSpeedMs: Double = 0.0,
    val speedUnit: String = "m/s"
)
