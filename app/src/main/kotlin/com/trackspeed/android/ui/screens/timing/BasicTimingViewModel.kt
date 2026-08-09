package com.trackspeed.android.ui.screens.timing

import android.graphics.Bitmap
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.analytics.AnalyticsEvent
import com.trackspeed.android.R
import com.trackspeed.android.analytics.AnalyticsService
import com.trackspeed.android.audio.CrossingFeedback
import com.trackspeed.android.audio.VoiceStartService
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.camera.CrossingThumbnailBuffer
import com.trackspeed.android.camera.reviewThumbnailTargetTimestamp
import com.trackspeed.android.cloud.CrossingDebugUploadQueue
import com.trackspeed.android.cloud.ThumbnailUploadQueue
import com.trackspeed.android.cloud.TimingWorkloadCoordinator
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.diagnostics.DetectionReviewLogStore
import com.trackspeed.android.detection.CrossingEvent
import com.trackspeed.android.detection.DetectionEngine
import com.trackspeed.android.detection.GateEngine
import com.trackspeed.android.detection.PhotoFinishDetector
import com.trackspeed.android.notifications.NotificationService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class BasicTimingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val cameraManager: CameraManager,
    private val gateEngine: GateEngine,
    private val crossingFeedback: CrossingFeedback,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val athleteDao: AthleteDao,
    val voiceStartService: VoiceStartService,
    private val subscriptionManager: SubscriptionManager,
    private val detectionReviewLogStore: DetectionReviewLogStore,
    private val notificationService: NotificationService,
    private val thumbnailUploadQueue: ThumbnailUploadQueue,
    private val crossingDebugUploadQueue: CrossingDebugUploadQueue,
    private val workloadCoordinator: TimingWorkloadCoordinator,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    private companion object {
        private const val REVIEW_PROMPT_SESSION_COUNT = 3
    }

    // Session configuration from navigation arguments (overrides settings defaults)
    private var sessionDistance: Double = (savedStateHandle.get<Float>("distance") ?: SettingsRepository.Defaults.DISTANCE.toFloat()).toDouble()
    private var sessionStartType: String = savedStateHandle.get<String>("startType") ?: SettingsRepository.Defaults.START_TYPE

    // Athletes selected for this session
    private val athleteIdsRaw: String = savedStateHandle.get<String>("athleteIds") ?: ""
    private val selectedAthleteIds = MutableStateFlow(
        athleteIdsRaw.split(",").filter { it.isNotBlank() }.toSet()
    )
    private val activeAthleteId = MutableStateFlow<String?>(null)
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
    private var detectionReviewSessionId: String? = null
    private var detectionReviewLogActive = false
    private var liveTimingWorkloadActive = false
    private var activeAnalyticsSoloSession = false

    // Timer tick job for live clock updates
    private var timerTickJob: Job? = null

    private val crossingThumbnailBuffer = CrossingThumbnailBuffer()
    private var previewSurface: Surface? = null

    init {
        viewModelScope.launch {
            processUploadQueuesIfIdle()
        }

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
                onCrossingDetected(event)
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

        viewModelScope.launch {
            settingsRepository.detectionDiagnosticsEnabled.collect { enabled ->
                _uiState.update { it.copy(detectionDiagnosticsEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            settingsRepository.detectionReviewAutoUploadEnabled.collect { enabled ->
                _uiState.update { it.copy(detectionReviewAutoUploadEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            settingsRepository.showSpeedInResults.collect { enabled ->
                _uiState.update { it.copy(showSpeedInResults = enabled) }
            }
        }

        viewModelScope.launch {
            settingsRepository.startSoundType.collect { rawValue ->
                _uiState.update { it.copy(startSoundType = rawValue) }
            }
        }

        viewModelScope.launch {
            settingsRepository.preStartDelayMin.collect { value ->
                _uiState.update { it.copy(preStartDelayMin = value) }
            }
        }

        viewModelScope.launch {
            settingsRepository.preStartDelayMax.collect { value ->
                _uiState.update { it.copy(preStartDelayMax = value) }
            }
        }

        viewModelScope.launch {
            settingsRepository.marksSetDelayMin.collect { value ->
                _uiState.update { it.copy(marksSetDelayMin = value) }
            }
        }

        viewModelScope.launch {
            settingsRepository.setGoHoldMin.collect { value ->
                _uiState.update { it.copy(setGoHoldMin = value) }
            }
        }

        viewModelScope.launch {
            settingsRepository.includeReadyCommand.collect { enabled ->
                _uiState.update { it.copy(includeReadyCommand = enabled) }
            }
        }

        viewModelScope.launch {
            settingsRepository.voiceProvider.collect { provider ->
                _uiState.update { it.copy(voiceProvider = provider) }
            }
        }

        viewModelScope.launch {
            settingsRepository.elevenLabsVoice.collect { voice ->
                _uiState.update { it.copy(elevenLabsVoice = voice) }
            }
        }

        viewModelScope.launch {
            settingsRepository.voiceGender.collect { gender ->
                _uiState.update { it.copy(voiceGender = gender) }
            }
        }

        viewModelScope.launch {
            settingsRepository.appLanguage.collect { language ->
                _uiState.update { it.copy(appLanguage = language) }
            }
        }

        viewModelScope.launch {
            settingsRepository.cameraPerformanceDiagnosticsEnabled.collect { enabled ->
                _uiState.update { it.copy(cameraPerformanceDiagnosticsEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            combine(
                athleteDao.getAllAthletes(),
                selectedAthleteIds,
                activeAthleteId
            ) { athletes, selectedIds, activeId ->
                val availableIds = athletes.map { it.id }.toSet()
                val sanitizedSelectedIds = selectedIds.intersect(availableIds)
                val selectedAthletes = athletes.filter { it.id in sanitizedSelectedIds }
                val effectiveActiveId = activeId
                    ?.takeIf { it in sanitizedSelectedIds }
                    ?: selectedAthletes.firstOrNull()?.id
                BasicTimingAthleteSelection(
                    athletes = athletes,
                    selectedAthleteIds = sanitizedSelectedIds,
                    sessionAthletes = selectedAthletes,
                    activeAthleteId = effectiveActiveId
                )
            }.collect { selection ->
                sessionAthletes = selection.sessionAthletes
                if (selection.selectedAthleteIds != selectedAthleteIds.value) {
                    selectedAthleteIds.value = selection.selectedAthleteIds
                }
                if (selection.activeAthleteId != activeAthleteId.value) {
                    activeAthleteId.value = selection.activeAthleteId
                }
                _uiState.update {
                    it.copy(
                        athletes = selection.athletes,
                        selectedAthleteIds = selection.selectedAthleteIds,
                        activeAthleteId = selection.activeAthleteId
                    )
                }
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
                    it.copy(
                        cameraState = CameraManager.CameraState.Error(
                            context.getString(R.string.camera_error_no_suitable)
                        )
                    )
                }
            }
        }

        // Load preferred FPS from settings (async, OK to be late — default is locked 30fps)
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
        crossingThumbnailBuffer.reset()
        cameraManager.openCamera(surface) { frameData -> processFrame(frameData) }
    }

    fun onSurfaceDestroyed() {
        gateEngine.stopMotionUpdates()
        cameraManager.closeCamera()
        crossingThumbnailBuffer.reset()
    }

    fun switchCamera() {
        frameCount = 0
        gateEngine.stopMotionUpdates()
        crossingThumbnailBuffer.reset()
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
        crossingThumbnailBuffer.appendFrame(
            frame = frameData,
            orientationDegrees = cameraManager.getSensorOrientation(),
            isFrontCamera = cameraManager.isFrontCamera.value
        )

        // Start IMU monitoring on first frame
        if (frameCount == 1L) {
            val fps = cameraManager.getAchievedFps().toDouble()
            val isFront = cameraManager.isFrontCamera.value
            gateEngine.configure(
                fps,
                isFront,
                cooldownSeconds = DetectionEngine.DEFAULT_COOLDOWN_SECONDS
            )
            gateEngine.startMotionUpdates()
        }

        // Pass frame to gate engine
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

    fun setGatePosition(position: Float) {
        gateEngine.setGatePosition(position)
    }

    private fun startTimerTick() {
        timerTickJob?.cancel()
        timerTickJob = viewModelScope.launch {
            while (true) {
                delay(100) // 10Hz tick
                val start = startTimeNanos ?: continue
                val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000_000.0
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
            var didStart = false
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
                    didStart = true
                }
            }
            if (didStart) {
                beginLiveTimingWorkloadIfNeeded()
                beginDetectionReviewLogIfNeeded()
                trackSoloSessionCreatedIfNeeded()
            }
        }
    }

    /**
     * Handle an external start event from Touch, Countdown, Voice, or InFrame overlays.
     * Sets the start timestamp directly and begins the timer tick.
     */
    fun handleExternalStart(timestampNanos: Long) {
        viewModelScope.launch {
            var didStart = false
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
                didStart = true
            }
            if (didStart) {
                beginLiveTimingWorkloadIfNeeded()
                beginDetectionReviewLogIfNeeded()
                trackSoloSessionCreatedIfNeeded()
            }
        }
    }

    fun stopTiming() {
        stopTimerTick()
        viewModelScope.launch {
            var didStop = false
            timingMutex.withLock {
                val currentState = _uiState.value
                if (currentState.isRunning) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            waitingForStart = false
                        )
                    }
                    didStop = true
                }
            }
            if (didStop) {
                trackSoloSessionCompletedIfNeeded(reason = "userExit")
                endDetectionReviewLogIfActive()
                endLiveTimingWorkloadIfNeeded()
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

    private fun onCrossingDetected(event: CrossingEvent) {
        // Use the detector's sub-frame interpolated timestamp (elapsedRealtimeNanos domain)
        // rather than capturing our own System.nanoTime(). The detector applies trajectory
        // regression and rolling shutter correction for sub-frame accuracy.
        val crossingNanos = event.timestamp
        val thumbnailTargetPts = event.detectorTriggerFramePts?.let { triggerPts ->
            reviewThumbnailTargetTimestamp(
                detectorTriggerFramePtsNanos = triggerPts,
                detectorSelectedFramePtsNanos = event.chosenThumbnailFramePts ?: triggerPts,
                supportsLivePersonSelector = false
            )
        } ?: event.chosenThumbnailFramePts
        val thumbnail = crossingThumbnailBuffer.bitmapClosestTo(thumbnailTargetPts)

        viewModelScope.launch {
            timingMutex.withLock {
                val currentState = _uiState.value

                if (!currentState.isRunning) return@withLock

                // Feedback acknowledges an accepted timing event. A detector
                // callback received after the session stopped must not sound
                // like a recorded crossing.
                crossingFeedback.playCrossingBeep()

                if (currentState.waitingForStart) {
                    // First crossing = START
                    startTimeNanos = crossingNanos
                    lapCounter = 0
                    val startLap = SoloLapResult(
                        lapNumber = 0,
                        totalTimeSeconds = 0.0,
                        lapTimeSeconds = 0.0,
                        thumbnail = thumbnail,
                        gatePosition = currentState.gatePosition,
                        crossingVelocityPxPerSec = event.velocityPxPerSec,
                        crossingDirection = event.crossingDirection,
                        workWidth = event.workWidth,
                        crossingTimestampNanos = crossingNanos,
                        detectorYPosition = event.detectorYNormalized,
                        interpolationAlpha = event.interpolationAlpha,
                        framePick = event.framePick,
                        s0 = event.s0,
                        s1 = event.s1,
                        isFrontCamera = event.isFrontCamera,
                        detectorTriggerFramePts = event.detectorTriggerFramePts,
                        chosenThumbnailFramePts = event.chosenThumbnailFramePts,
                        savedThumbnailFramePts = event.savedThumbnailFramePts
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
                        val activeAthlete = currentState.activeAthleteId?.let { athleteId ->
                            sessionAthletes.firstOrNull { it.id == athleteId }
                                ?: currentState.athletes.firstOrNull { it.id == athleteId }
                        }

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
                            speedMs = lapSpeedMs,
                            crossingVelocityPxPerSec = event.velocityPxPerSec,
                            crossingDirection = event.crossingDirection,
                            workWidth = event.workWidth,
                            crossingTimestampNanos = crossingNanos,
                            detectorYPosition = event.detectorYNormalized,
                            interpolationAlpha = event.interpolationAlpha,
                            framePick = event.framePick,
                            s0 = event.s0,
                            s1 = event.s1,
                            isFrontCamera = event.isFrontCamera,
                            detectorTriggerFramePts = event.detectorTriggerFramePts,
                            chosenThumbnailFramePts = event.chosenThumbnailFramePts,
                            savedThumbnailFramePts = event.savedThumbnailFramePts,
                            athleteId = activeAthlete?.id,
                            athleteName = activeAthlete?.displayName,
                            athleteColor = activeAthlete?.color
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
     * Called from the composable to update session config from navigation arguments.
     */
    fun setSessionConfig(distance: Double, startType: String) {
        sessionDistance = distance
        sessionStartType = startType
        _uiState.update {
            it.copy(distance = distance, startType = startType)
        }
    }

    fun setDetectionDiagnosticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDetectionDiagnosticsEnabled(enabled)
        }
    }

    fun setDetectionReviewAutoUploadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDetectionReviewAutoUploadEnabled(enabled)
        }
    }

    fun setShowSpeedInResults(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowSpeedInResults(enabled)
        }
    }

    fun setStartSoundType(rawValue: String) {
        viewModelScope.launch {
            settingsRepository.setStartSoundType(rawValue)
        }
    }

    fun setVoiceProvider(provider: String) {
        viewModelScope.launch {
            settingsRepository.setVoiceProvider(provider)
        }
    }

    fun setElevenLabsVoice(voice: String) {
        viewModelScope.launch {
            settingsRepository.setElevenLabsVoice(voice)
        }
    }

    fun setVoiceGender(gender: String) {
        viewModelScope.launch {
            settingsRepository.setVoiceGender(gender)
        }
    }

    fun setAppLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.setAppLanguage(language)
        }
    }

    fun setPreStartDelayMin(value: Float) {
        viewModelScope.launch {
            settingsRepository.setPreStartDelayMin(value)
            settingsRepository.setPreStartDelayMax(value + 2f)
        }
    }

    fun setMarksSetDelayMin(value: Float) {
        viewModelScope.launch {
            settingsRepository.setMarksSetDelayMin(value)
            settingsRepository.setMarksSetDelayMax(value + 4f)
        }
    }

    fun setSetGoHoldMin(value: Float) {
        viewModelScope.launch {
            settingsRepository.setSetGoHoldMin(value)
            settingsRepository.setSetGoHoldMax(value + 0.8f)
        }
    }

    fun setIncludeReadyCommand(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setIncludeReadyCommand(enabled)
        }
    }

    fun setCameraPerformanceDiagnosticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCameraPerformanceDiagnosticsEnabled(enabled)
        }
    }

    fun setSelectedAthletes(ids: Set<String>) {
        val state = _uiState.value
        val availableIds = state.athletes.map { it.id }.toSet()
        val sanitizedIds = ids.intersect(availableIds)
        val selectedAthletes = state.athletes.filter { it.id in sanitizedIds }
        val nextActiveAthleteId = state.activeAthleteId
            ?.takeIf { it in sanitizedIds }
            ?: selectedAthletes.firstOrNull()?.id
        sessionAthletes = selectedAthletes
        _uiState.update {
            it.copy(
                selectedAthleteIds = sanitizedIds,
                activeAthleteId = nextActiveAthleteId
            )
        }
        selectedAthleteIds.value = sanitizedIds
        activeAthleteId.value = nextActiveAthleteId
    }

    fun setActiveAthlete(athleteId: String) {
        val state = _uiState.value
        if (athleteId !in state.selectedAthleteIds) return
        activeAthleteId.value = athleteId
        _uiState.update { it.copy(activeAthleteId = athleteId) }
    }

    suspend fun exportDetectionReviewLog(): Uri {
        return detectionReviewLogStore.exportCurrentLog()
    }

    suspend fun uploadDetectionReviewLog(): String {
        return detectionReviewLogStore.uploadCurrentLog()
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
            val completedSessionCount = sessionRepository.getTotalSessionCount().first()
            val shouldRequestReview = completedSessionCount >= REVIEW_PROMPT_SESSION_COUNT &&
                !settingsRepository.hasBeenAskedForReview.first()

            if (shouldRequestReview) {
                settingsRepository.setHasBeenAskedForReview(true)
                notificationService.cancelRatingPrompt()
            }

            _uiState.update {
                it.copy(
                    sessionSaved = true,
                    reviewPromptRequested = shouldRequestReview
                )
            }
            processUploadQueuesIfIdle()
        }
    }

    fun onPaywallPromptConsumed() {
        _uiState.update { it.copy(showPaywallPrompt = false) }
    }

    fun onSessionSavedConsumed() {
        _uiState.update { it.copy(sessionSaved = false) }
    }

    fun onReviewPromptRequestedConsumed() {
        _uiState.update { it.copy(reviewPromptRequested = false) }
    }

    override fun onCleared() {
        super.onCleared()
        trackSoloSessionCompletedIfNeeded(reason = "userExit")
        endLiveTimingWorkloadIfNeeded()
        if (detectionReviewLogActive) {
            detectionReviewLogStore.endSessionAsync(detectionDiagnosticsMessage(enabled = false))
        }
        stopTimerTick()
        gateEngine.stopMotionUpdates()
        cameraManager.closeCamera()
        gateEngine.reset()
        crossingThumbnailBuffer.reset()
    }

    private fun beginLiveTimingWorkloadIfNeeded() {
        if (liveTimingWorkloadActive) return
        liveTimingWorkloadActive = true
        workloadCoordinator.beginLiveTiming()
    }

    private fun endLiveTimingWorkloadIfNeeded() {
        if (!liveTimingWorkloadActive) return
        liveTimingWorkloadActive = false
        workloadCoordinator.endLiveTiming()
        viewModelScope.launch {
            processUploadQueuesIfIdle()
        }
    }

    private suspend fun processUploadQueuesIfIdle() {
        if (workloadCoordinator.isLiveTimingActive) return
        sessionRepository.processPendingCloudUploads()
        thumbnailUploadQueue.processQueue()
        crossingDebugUploadQueue.processQueue()
    }

    private fun trackSoloSessionCreatedIfNeeded() {
        if (activeAnalyticsSoloSession) return
        activeAnalyticsSoloSession = true
        analyticsService.track(
            AnalyticsEvent.SESSION_CREATED,
            mapOf(
                "role" to "solo",
                "number_of_gates" to 1,
                "distance_m" to sessionDistance,
                "mode" to "solo"
            )
        )
    }

    private fun trackSoloSessionCompletedIfNeeded(reason: String) {
        if (!activeAnalyticsSoloSession) return
        val actualLapCount = _laps.count { it.lapNumber > 0 }
        analyticsService.track(
            AnalyticsEvent.SESSION_COMPLETED,
            mapOf(
                "reason" to reason,
                "role" to "solo",
                "number_of_gates" to 1,
                "distance_m" to sessionDistance,
                "run_count" to actualLapCount,
                "solo_lap_count" to actualLapCount
            )
        )
        activeAnalyticsSoloSession = false
    }

    private suspend fun beginDetectionReviewLogIfNeeded() {
        if (detectionReviewLogActive || !_uiState.value.detectionDiagnosticsEnabled) return
        val sessionId = UUID.randomUUID().toString()
        detectionReviewSessionId = sessionId
        detectionReviewLogActive = true
        detectionReviewLogStore.startSession(
            sessionId = sessionId,
            mode = "solo",
            role = "solo",
            gateIndex = 0
        )
        detectionReviewLogStore.appendForContext(
            sessionId = sessionId,
            mode = "solo",
            role = "solo",
            gateIndex = 0,
            message = detectionDiagnosticsMessage(enabled = true)
        )
    }

    private suspend fun endDetectionReviewLogIfActive() {
        if (!detectionReviewLogActive) return
        detectionReviewLogStore.endSession(detectionDiagnosticsMessage(enabled = false))
        detectionReviewLogActive = false
        detectionReviewSessionId = null
    }

    private fun detectionDiagnosticsMessage(enabled: Boolean): String {
        return "[DETECTION-DIAGNOSTICS] method=PhotoFinish diagnostics=${if (enabled) "on" else "off"} " +
            "mode=solo role=solo gateIndex=0"
    }
}

/**
 * A single lap result in solo mode.
 */
data class SoloLapResult(
    val lapNumber: Int,            // 0 = START, 1+ = laps
    val totalTimeSeconds: Double,  // Cumulative time from start
    val lapTimeSeconds: Double,    // Time for this specific lap
    val thumbnail: Bitmap?,        // Color frame at crossing
    val gatePosition: Float,       // Gate position for overlay
    val speedMs: Double = 0.0,     // Speed in meters per second for this lap
    val crossingVelocityPxPerSec: Float? = null,
    val crossingDirection: String? = null,
    val workWidth: Int? = null,
    val crossingTimestampNanos: Long? = null,  // Raw detector timestamp (elapsedRealtimeNanos)
    val detectorYPosition: Float? = null,
    val interpolationAlpha: Double? = null,
    val framePick: String? = null,
    val s0: Float? = null,
    val s1: Float? = null,
    val isFrontCamera: Boolean? = null,
    val detectorTriggerFramePts: Long? = null,
    val chosenThumbnailFramePts: Long? = null,
    val savedThumbnailFramePts: Long? = null,
    val athleteId: String? = null,
    val athleteName: String? = null,
    val athleteColor: String? = null
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
    val reviewPromptRequested: Boolean = false,
    val showPaywallPrompt: Boolean = false,
    val sensorOrientation: Int = 90,
    val isFrontCamera: Boolean = false,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val distance: Double = 60.0,
    val startType: String = "flying",
    val currentSpeedMs: Double = 0.0,
    val speedUnit: String = "m/s",
    val detectionDiagnosticsEnabled: Boolean = SettingsRepository.Defaults.DETECTION_DIAGNOSTICS_ENABLED,
    val detectionReviewAutoUploadEnabled: Boolean = SettingsRepository.Defaults.DETECTION_REVIEW_AUTO_UPLOAD_ENABLED,
    val showSpeedInResults: Boolean = SettingsRepository.Defaults.SHOW_SPEED_IN_RESULTS,
    val startSoundType: String = SettingsRepository.Defaults.START_SOUND_TYPE,
    val preStartDelayMin: Float = SettingsRepository.Defaults.PRE_START_DELAY_MIN,
    val preStartDelayMax: Float = SettingsRepository.Defaults.PRE_START_DELAY_MAX,
    val marksSetDelayMin: Float = SettingsRepository.Defaults.MARKS_SET_DELAY_MIN,
    val setGoHoldMin: Float = SettingsRepository.Defaults.SET_GO_HOLD_MIN,
    val includeReadyCommand: Boolean = SettingsRepository.Defaults.INCLUDE_READY_COMMAND,
    val voiceProvider: String = SettingsRepository.Defaults.VOICE_PROVIDER,
    val elevenLabsVoice: String = SettingsRepository.Defaults.ELEVEN_LABS_VOICE,
    val voiceGender: String = SettingsRepository.Defaults.VOICE_GENDER,
    val appLanguage: String = SettingsRepository.Defaults.APP_LANGUAGE,
    val cameraPerformanceDiagnosticsEnabled: Boolean = SettingsRepository.Defaults.CAMERA_PERFORMANCE_DIAGNOSTICS_ENABLED,
    val athletes: List<AthleteEntity> = emptyList(),
    val selectedAthleteIds: Set<String> = emptySet(),
    val activeAthleteId: String? = null
)

private data class BasicTimingAthleteSelection(
    val athletes: List<AthleteEntity>,
    val selectedAthleteIds: Set<String>,
    val sessionAthletes: List<AthleteEntity>,
    val activeAthleteId: String?
)
