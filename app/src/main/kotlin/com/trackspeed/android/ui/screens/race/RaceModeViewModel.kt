package com.trackspeed.android.ui.screens.race

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.cloud.RaceEventService
import com.trackspeed.android.cloud.SessionCodeGenerator
import com.trackspeed.android.cloud.dto.RaceEventDto
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.detection.CrossingEvent
import com.trackspeed.android.detection.GateEngine
import com.trackspeed.android.detection.PhotoFinishDetector
import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.sync.BleClockSyncService
import com.trackspeed.android.sync.ClockSyncManager
import com.trackspeed.android.sync.SyncQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.sqrt

@HiltViewModel
class RaceModeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraManager: CameraManager,
    private val gateEngine: GateEngine,
    private val clockSyncManager: ClockSyncManager,
    private val bleClockSyncService: BleClockSyncService,
    private val raceEventService: RaceEventService,
    private val sessionCodeGenerator: SessionCodeGenerator,
    private val sessionRepository: SessionRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "RaceModeViewModel"
    }

    private val preConfiguredDistance = savedStateHandle.get<Float>("distance")
        ?.toDouble()?.takeIf { it > 0 }
    private val preConfiguredStartType = savedStateHandle.get<String>("startType")
        ?.ifBlank { null }

    private val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("trackspeed", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", newId).apply()
            newId
        }
    }
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    // Session ID for cloud sync (generated per race session)
    private var sessionId: String = UUID.randomUUID().toString()

    private val _uiState = MutableStateFlow(
        RaceModeUiState(
            distanceMeters = preConfiguredDistance ?: 60.0
        )
    )
    val uiState: StateFlow<RaceModeUiState> = _uiState.asStateFlow()

    // Timer job
    private var timerJob: Job? = null
    // Realtime race event subscription
    private var raceEventSubscriptionJob: Job? = null
    // Pairing request watcher
    private var pairingWatcherJob: Job? = null
    // BLE timeout for cloud-only fallback
    private var bleTimeoutJob: Job? = null

    // Timing state
    private var localStartTimeNanos: Long? = null
    private var localFinishTimeNanos: Long? = null
    private var remoteStartTimeNanos: Long? = null
    private var frameCount = 0L

    // Surface for camera
    private var previewSurface: Surface? = null

    init {
        // Observe sync state from ClockSyncManager
        viewModelScope.launch {
            clockSyncManager.syncState.collect { syncState ->
                _uiState.update { current ->
                    when (syncState) {
                        is ClockSyncManager.SyncState.NotSynced -> current.copy(
                            syncProgress = 0f,
                            syncQuality = null,
                            syncUncertaintyMs = 0.0,
                            syncOffsetMs = 0.0
                        )
                        is ClockSyncManager.SyncState.WaitingForPeer -> current.copy(
                            syncProgress = 0f
                        )
                        is ClockSyncManager.SyncState.Connecting -> current.copy(
                            syncProgress = 0f
                        )
                        is ClockSyncManager.SyncState.Syncing -> current.copy(
                            syncProgress = syncState.progress
                        )
                        is ClockSyncManager.SyncState.Synced -> current.copy(
                            syncProgress = 1f,
                            syncQuality = syncState.quality,
                            syncUncertaintyMs = syncState.uncertaintyMs,
                            syncOffsetMs = syncState.offsetMs,
                            phase = if (current.phase == RacePhase.SYNCING) RacePhase.RACE_READY else current.phase
                        )
                        is ClockSyncManager.SyncState.Error -> current.copy(
                            errorMessage = syncState.message
                        )
                    }
                }
            }
        }

        // Observe BLE connection state for pairing phase
        viewModelScope.launch {
            bleClockSyncService.state.collect { bleState ->
                val currentPhase = _uiState.value.phase
                when (bleState) {
                    is BleClockSyncService.State.Scanning -> {
                        if (currentPhase == RacePhase.PAIRING) {
                            _uiState.update { it.copy(pairingStatus = "scanning") }
                        }
                    }
                    is BleClockSyncService.State.Connecting -> {
                        _uiState.update { it.copy(pairingStatus = "connecting") }
                    }
                    is BleClockSyncService.State.Connected -> {
                        _uiState.update {
                            it.copy(
                                pairingStatus = "connected",
                                isDeviceConnected = true,
                                phase = RacePhase.SYNCING
                            )
                        }
                    }
                    is BleClockSyncService.State.Syncing -> {
                        _uiState.update {
                            it.copy(
                                phase = RacePhase.SYNCING,
                                syncProgress = bleState.progress
                            )
                        }
                    }
                    is BleClockSyncService.State.Synced -> {
                        _uiState.update {
                            it.copy(
                                phase = RacePhase.RACE_READY,
                                syncQuality = bleState.result.quality,
                                syncUncertaintyMs = bleState.result.uncertaintyMs,
                                syncOffsetMs = bleState.result.offsetMs,
                                syncProgress = 1f
                            )
                        }
                    }
                    is BleClockSyncService.State.Error -> {
                        _uiState.update {
                            it.copy(
                                errorMessage = bleState.message,
                                pairingStatus = "error"
                            )
                        }
                    }
                    is BleClockSyncService.State.Idle -> {
                        // No action needed
                    }
                    is BleClockSyncService.State.Pairing -> {
                        if (currentPhase == RacePhase.PAIRING) {
                            _uiState.update { it.copy(pairingStatus = "searching") }
                        }
                    }
                    is BleClockSyncService.State.ClientReady -> {
                        // Server-side: client ready for notifications, handshake proceeding
                    }
                }
            }
        }

        // Observe detection state from GateEngine
        viewModelScope.launch {
            gateEngine.detectionState.collect { state ->
                _uiState.update { it.copy(detectionState = state) }
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

        // Observe camera state
        viewModelScope.launch {
            cameraManager.cameraState.collect { state ->
                _uiState.update { it.copy(cameraState = state) }
            }
        }

        // Observe front camera state
        viewModelScope.launch {
            cameraManager.isFrontCamera.collect { isFront ->
                _uiState.update { it.copy(isFrontCamera = isFront) }
            }
        }

        // Observe crossing events for active race
        viewModelScope.launch {
            gateEngine.crossingEvents.collect { event ->
                handleLocalCrossing(event)
            }
        }

        // Observe incoming BLE messages from the remote device
        viewModelScope.launch {
            bleClockSyncService.incomingMessages.collect { message ->
                handleRemoteMessage(message.payload)
            }
        }

        // Initialize camera early so preview dimensions are available for configureTransform
        viewModelScope.launch {
            initializeCamera()
        }
    }

    private fun initializeCamera() {
        if (!cameraManager.initialize()) {
            _uiState.update {
                it.copy(cameraState = CameraManager.CameraState.Error("No suitable camera found."))
            }
        } else {
            val previewSize = cameraManager.getPreviewSize()
            _uiState.update {
                it.copy(
                    sensorOrientation = cameraManager.getSensorOrientation(),
                    previewWidth = previewSize?.width ?: 0,
                    previewHeight = previewSize?.height ?: 0
                )
            }
        }
    }

    // === Role Selection ===

    fun selectRole(role: DeviceRole) {
        when (role) {
            DeviceRole.START -> {
                // Host: generate a session code and create pairing request
                _uiState.update {
                    it.copy(
                        role = role,
                        isHostingSession = true,
                        phase = RacePhase.SESSION_CODE,
                        pairingError = null
                    )
                }
                viewModelScope.launch {
                    try {
                        val code = sessionCodeGenerator.generateSessionCode()
                        sessionId = code
                        raceEventService.createPairingRequest(code, deviceId, deviceName)
                        _uiState.update { it.copy(sessionCode = code) }
                        startPairingWatcher(code)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to generate session code", e)
                        _uiState.update {
                            it.copy(
                                pairingError = e.message ?: "Failed to create session",
                                phase = RacePhase.ROLE_SELECTION
                            )
                        }
                    }
                }
            }
            DeviceRole.FINISH -> {
                // Joiner: show code entry UI
                _uiState.update {
                    it.copy(
                        role = role,
                        isHostingSession = false,
                        phase = RacePhase.SESSION_CODE,
                        pairingError = null
                    )
                }
            }
        }
    }

    // === Session Code Pairing ===

    fun joinSession(code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(pairingError = null) }
            try {
                raceEventService.joinPairingRequest(code, deviceId, deviceName)
                sessionId = code
                _uiState.update {
                    it.copy(
                        sessionCode = code,
                        phase = RacePhase.PAIRING
                    )
                }
                // Start BLE scanning as client
                clockSyncManager.startAsClient()
                startBleTimeoutFallback()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to join session", e)
                _uiState.update {
                    it.copy(pairingError = "Invalid code or session not found")
                }
            }
        }
    }

    fun clearPairingError() {
        _uiState.update { it.copy(pairingError = null) }
    }

    private fun startPairingWatcher(code: String) {
        pairingWatcherJob?.cancel()
        pairingWatcherJob = viewModelScope.launch {
            try {
                raceEventService.watchPairingRequest(code).collect { dto ->
                    if (dto.status == "matched") {
                        val remoteName = if (_uiState.value.isHostingSession) {
                            dto.joinerDeviceName
                        } else {
                            dto.hostDeviceName
                        }
                        _uiState.update {
                            it.copy(
                                remoteDeviceName = remoteName,
                                connectedDeviceName = remoteName ?: "Other Device",
                                phase = RacePhase.PAIRING
                            )
                        }
                        // Host starts BLE advertising after pairing match
                        if (_uiState.value.isHostingSession) {
                            clockSyncManager.startAsServer()
                            startBleTimeoutFallback()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pairing watcher error (non-critical)", e)
            }
        }
    }

    /**
     * If BLE connection doesn't complete within 15s after Supabase pairing,
     * fall back to cloud-only mode using Supabase Realtime.
     */
    private fun startBleTimeoutFallback() {
        bleTimeoutJob?.cancel()
        bleTimeoutJob = viewModelScope.launch {
            delay(15_000)
            val current = _uiState.value
            if (current.phase == RacePhase.PAIRING && !current.isDeviceConnected) {
                Log.w(TAG, "BLE timeout - falling back to cloud-only mode")
                _uiState.update {
                    it.copy(
                        phase = RacePhase.RACE_READY,
                        isCloudOnlyMode = true,
                        syncUncertaintyMs = 50.0,
                        syncProgress = 1f
                    )
                }
            }
        }
    }

    // === Camera ===

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

    private fun processFrame(frameData: CameraManager.FrameData) {
        frameCount++
        if (frameCount == 1L) {
            val fps = cameraManager.getAchievedFps().toDouble()
            val isFront = cameraManager.isFrontCamera.value
            gateEngine.configure(fps, isFront)
            gateEngine.startMotionUpdates()
        }

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

    // === Session Start ===

    fun startSession() {
        // Use existing sessionId from pairing code if set, otherwise generate new
        if (sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString()
        }
        _uiState.update {
            it.copy(
                phase = RacePhase.ACTIVE_RACE,
                raceStatus = "waiting",
                elapsedTimeSeconds = 0.0,
                resultTimeSeconds = null,
                resultUncertaintyMs = null,
                errorMessage = null
            )
        }
        localStartTimeNanos = null
        localFinishTimeNanos = null
        remoteStartTimeNanos = null
        gateEngine.reset()

        // Subscribe to cloud race events as backup to BLE
        raceEventSubscriptionJob?.cancel()
        raceEventSubscriptionJob = viewModelScope.launch {
            try {
                raceEventService.subscribeToRaceEvents(sessionId)
                    .filter { it.deviceId != deviceId }
                    .collect { event ->
                        when (event.eventType) {
                            "start" -> {
                                if (localStartTimeNanos == null) {
                                    Log.i(TAG, "Remote start received via cloud relay")
                                    onRemoteStartReceived(event.crossingTimeNanos)
                                }
                            }
                            "finish" -> {
                                Log.i(TAG, "Remote finish via cloud: ${event.crossingTimeNanos}")
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Race event subscription failed (non-critical)", e)
            }
        }
    }

    // === Crossing Handling ===

    private fun handleLocalCrossing(event: CrossingEvent) {
        val currentState = _uiState.value
        if (currentState.phase != RacePhase.ACTIVE_RACE) return

        val crossingTimeNanos = SystemClock.elapsedRealtimeNanos()

        when (currentState.role) {
            DeviceRole.START -> {
                // Start phone: record start time, notify finish phone via BLE
                localStartTimeNanos = crossingTimeNanos
                Log.i(TAG, "START crossing detected at $crossingTimeNanos ns")

                _uiState.update {
                    it.copy(raceStatus = "started")
                }

                // Start the live timer
                startTimerTick()

                // Send start event to finish phone via BLE
                // Convert to remote clock so finish phone can use it directly
                val remoteTimeNanos = clockSyncManager.toRemoteTime(crossingTimeNanos)
                val sent = bleClockSyncService.sendMessage(
                    TimingPayload.StartEvent(
                        monotonicNanos = remoteTimeNanos,
                        thumbnailData = null
                    )
                )
                Log.i(TAG, "Sent StartEvent via BLE (remote=$remoteTimeNanos): sent=$sent")

                // Upload start event to Supabase (fire-and-forget)
                uploadRaceEvent("start", crossingTimeNanos)
            }
            DeviceRole.FINISH -> {
                // Finish phone: record finish time, calculate split
                localFinishTimeNanos = crossingTimeNanos
                Log.i(TAG, "FINISH crossing detected at $crossingTimeNanos ns")

                stopTimerTick()
                calculateResult()

                // Upload finish event to Supabase (fire-and-forget)
                uploadRaceEvent("finish", crossingTimeNanos)
            }
            null -> { /* Should not happen */ }
        }
    }

    /**
     * Handle an incoming message from the remote device via BLE.
     */
    private fun handleRemoteMessage(payload: TimingPayload) {
        when (payload) {
            is TimingPayload.StartEvent -> {
                onRemoteStartReceived(payload.monotonicNanos)
            }
            is TimingPayload.CrossingEvent -> {
                Log.i(TAG, "Remote crossing event: gate=${payload.gateId}, role=${payload.role}")
                // Can be extended for multi-gate support
            }
            is TimingPayload.NewRun -> {
                Log.i(TAG, "Remote requested new run")
                startNewRace()
            }
            is TimingPayload.CancelRun -> {
                Log.i(TAG, "Remote cancelled run")
                startNewRace()
            }
            is TimingPayload.Abort -> {
                Log.i(TAG, "Remote aborted: ${payload.reason}")
                _uiState.update { it.copy(errorMessage = "Remote: ${payload.reason}") }
            }
            else -> {
                Log.d(TAG, "Received remote message: ${payload::class.simpleName}")
            }
        }
    }

    /**
     * Called when the finish phone receives a start event from the start phone.
     */
    private fun onRemoteStartReceived(remoteTimestampNanos: Long) {
        if (_uiState.value.phase != RacePhase.ACTIVE_RACE) return

        remoteStartTimeNanos = remoteTimestampNanos
        val localStartTime = clockSyncManager.toLocalTime(remoteTimestampNanos)
        localStartTimeNanos = localStartTime

        Log.i(TAG, "Remote start received: remote=$remoteTimestampNanos, local=$localStartTime")

        _uiState.update {
            it.copy(raceStatus = "started")
        }

        startTimerTick()
    }

    private fun calculateResult() {
        val start = localStartTimeNanos ?: return
        val finish = localFinishTimeNanos ?: return

        val splitNanos = finish - start
        val splitSeconds = splitNanos / 1_000_000_000.0

        // Calculate combined uncertainty
        val syncUncertainty = _uiState.value.syncUncertaintyMs
        // Detection uncertainty is approximately half a frame duration
        val fps = cameraManager.getAchievedFps().toDouble()
        val detectionUncertaintyMs = if (fps > 0) (1000.0 / fps / 2.0) else 5.0
        val totalUncertaintyMs = sqrt(
            syncUncertainty.pow(2) + detectionUncertaintyMs.pow(2) * 2 // x2 for start + finish
        )

        Log.i(TAG, "RESULT: ${String.format("%.3f", splitSeconds)}s +/- ${String.format("%.1f", totalUncertaintyMs)}ms")

        _uiState.update {
            it.copy(
                phase = RacePhase.RESULT,
                resultTimeSeconds = splitSeconds,
                resultUncertaintyMs = totalUncertaintyMs,
                elapsedTimeSeconds = splitSeconds,
                raceStatus = "finished"
            )
        }

        // Save result to local Room DB
        viewModelScope.launch {
            try {
                sessionRepository.saveRaceResult(
                    distance = _uiState.value.distanceMeters,
                    startType = preConfiguredStartType ?: "standing",
                    timeSeconds = splitSeconds
                )
                Log.i(TAG, "Saved race result to local DB")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save race result", e)
            }
        }
    }

    /**
     * Upload a race event to Supabase (fire-and-forget, non-blocking).
     */
    private fun uploadRaceEvent(eventType: String, crossingTimeNanos: Long) {
        viewModelScope.launch {
            try {
                val syncOffset = clockSyncManager.getOffsetNanos()
                raceEventService.insertRaceEvent(
                    RaceEventDto(
                        sessionId = sessionId,
                        eventType = eventType,
                        crossingTimeNanos = crossingTimeNanos,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        clockOffsetNanos = syncOffset,
                        uncertaintyMs = _uiState.value.syncUncertaintyMs
                    )
                )
                Log.d(TAG, "Uploaded $eventType event to Supabase")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to upload race event to cloud (non-critical)", e)
            }
        }
    }

    // === Timer ===

    private fun startTimerTick() {
        timerJob?.cancel()
        val startTime = localStartTimeNanos ?: return

        timerJob = viewModelScope.launch {
            while (true) {
                delay(50) // 20Hz tick
                val now = SystemClock.elapsedRealtimeNanos()
                val elapsed = (now - startTime) / 1_000_000_000.0
                _uiState.update { it.copy(elapsedTimeSeconds = elapsed) }
            }
        }
    }

    private fun stopTimerTick() {
        timerJob?.cancel()
        timerJob = null
    }

    // === Navigation ===

    fun resetToRoleSelection() {
        stopTimerTick()
        raceEventSubscriptionJob?.cancel()
        raceEventSubscriptionJob = null
        pairingWatcherJob?.cancel()
        pairingWatcherJob = null
        bleTimeoutJob?.cancel()
        bleTimeoutJob = null
        clockSyncManager.stop()
        gateEngine.stopMotionUpdates()
        gateEngine.reset()
        cameraManager.closeCamera()

        localStartTimeNanos = null
        localFinishTimeNanos = null
        remoteStartTimeNanos = null
        frameCount = 0

        _uiState.update {
            RaceModeUiState() // Reset to defaults
        }
    }

    fun startNewRace() {
        _uiState.update {
            it.copy(
                phase = RacePhase.RACE_READY,
                resultTimeSeconds = null,
                resultUncertaintyMs = null,
                elapsedTimeSeconds = 0.0,
                raceStatus = "",
                errorMessage = null
            )
        }
        localStartTimeNanos = null
        localFinishTimeNanos = null
        remoteStartTimeNanos = null
        gateEngine.reset()
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // === Distance ===

    fun setDistance(distance: Double) {
        _uiState.update { it.copy(distanceMeters = distance) }
    }

    override fun onCleared() {
        super.onCleared()
        stopTimerTick()
        raceEventSubscriptionJob?.cancel()
        pairingWatcherJob?.cancel()
        bleTimeoutJob?.cancel()
        clockSyncManager.stop()
        gateEngine.stopMotionUpdates()
        cameraManager.closeCamera()
        gateEngine.reset()
    }
}

// === State Models ===

enum class RacePhase {
    ROLE_SELECTION,
    SESSION_CODE,
    PAIRING,
    SYNCING,
    RACE_READY,
    ACTIVE_RACE,
    RESULT
}

enum class DeviceRole {
    START,
    FINISH
}

data class RaceModeUiState(
    val phase: RacePhase = RacePhase.ROLE_SELECTION,
    val role: DeviceRole? = null,

    // Session code pairing
    val sessionCode: String = "",
    val isHostingSession: Boolean = false,
    val pairingError: String? = null,
    val remoteDeviceName: String? = null,
    val isCloudOnlyMode: Boolean = false,

    // Pairing
    val pairingStatus: String = "",
    val isDeviceConnected: Boolean = false,
    val connectedDeviceName: String = "Other Device",

    // Sync
    val syncProgress: Float = 0f,
    val syncQuality: SyncQuality? = null,
    val syncUncertaintyMs: Double = 0.0,
    val syncOffsetMs: Double = 0.0,

    // Camera
    val hasPermission: Boolean = false,
    val cameraState: CameraManager.CameraState = CameraManager.CameraState.Closed,
    val fps: Int = 0,
    val gatePosition: Float = 0.5f,
    val detectionState: PhotoFinishDetector.State = PhotoFinishDetector.State.UNSTABLE,
    val sensorOrientation: Int = 90,
    val isFrontCamera: Boolean = false,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,

    // Race
    val distanceMeters: Double = 60.0,
    val raceStatus: String = "",
    val elapsedTimeSeconds: Double = 0.0,
    val resultTimeSeconds: Double? = null,
    val resultUncertaintyMs: Double? = null,

    // Error
    val errorMessage: String? = null
)
