package com.trackspeed.android.ui.screens.race

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.detection.CrossingEvent
import com.trackspeed.android.detection.GateEngine
import com.trackspeed.android.detection.PhotoFinishDetector
import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.sync.BleClockSyncService
import com.trackspeed.android.sync.ClockSyncManager
import com.trackspeed.android.sync.SyncQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.sqrt

@HiltViewModel
class RaceModeViewModel @Inject constructor(
    private val cameraManager: CameraManager,
    private val gateEngine: GateEngine,
    private val clockSyncManager: ClockSyncManager,
    private val bleClockSyncService: BleClockSyncService
) : ViewModel() {

    companion object {
        private const val TAG = "RaceModeViewModel"
    }

    private val _uiState = MutableStateFlow(RaceModeUiState())
    val uiState: StateFlow<RaceModeUiState> = _uiState.asStateFlow()

    // Timer job
    private var timerJob: Job? = null

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
                            _uiState.update { it.copy(pairingStatus = "Searching for devices...") }
                        }
                    }
                    is BleClockSyncService.State.Connecting -> {
                        _uiState.update { it.copy(pairingStatus = "Connecting...") }
                    }
                    is BleClockSyncService.State.Connected -> {
                        _uiState.update {
                            it.copy(
                                pairingStatus = "Connected",
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
                                pairingStatus = "Error: ${bleState.message}"
                            )
                        }
                    }
                    is BleClockSyncService.State.Idle -> {
                        // No action needed
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
    }

    // === Role Selection ===

    fun selectRole(role: DeviceRole) {
        _uiState.update { it.copy(role = role, phase = RacePhase.PAIRING) }

        // Start BLE based on role
        when (role) {
            DeviceRole.START -> {
                // Start phone acts as BLE server (reference clock)
                clockSyncManager.startAsServer()
            }
            DeviceRole.FINISH -> {
                // Finish phone acts as BLE client (syncs to start phone)
                clockSyncManager.startAsClient()
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

        if (!cameraManager.initialize()) {
            _uiState.update {
                it.copy(cameraState = CameraManager.CameraState.Error("No suitable camera found."))
            }
            return
        }
        _uiState.update { it.copy(sensorOrientation = cameraManager.getSensorOrientation()) }

        cameraManager.openCamera(surface) { frameData ->
            processFrame(frameData)
        }
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
        _uiState.update {
            it.copy(
                phase = RacePhase.ACTIVE_RACE,
                raceStatus = "Waiting for crossing...",
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
                    it.copy(raceStatus = "Runner started! Waiting for finish...")
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
            }
            DeviceRole.FINISH -> {
                // Finish phone: record finish time, calculate split
                localFinishTimeNanos = crossingTimeNanos
                Log.i(TAG, "FINISH crossing detected at $crossingTimeNanos ns")

                stopTimerTick()
                calculateResult()
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
            it.copy(raceStatus = "Runner started! Waiting for finish...")
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
                raceStatus = "Finished!"
            )
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
        clockSyncManager.stop()
        gateEngine.stopMotionUpdates()
        cameraManager.closeCamera()
        gateEngine.reset()
    }
}

// === State Models ===

enum class RacePhase {
    ROLE_SELECTION,
    PAIRING,
    SYNCING,
    RACE_READY,
    ACTIVE_RACE,
    RESULT
}

enum class DeviceRole(val label: String, val description: String) {
    START("Start Phone", "Place at the start line"),
    FINISH("Finish Phone", "Place at the finish line")
}

data class RaceModeUiState(
    val phase: RacePhase = RacePhase.ROLE_SELECTION,
    val role: DeviceRole? = null,

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

    // Race
    val distanceMeters: Double = 60.0,
    val raceStatus: String = "",
    val elapsedTimeSeconds: Double = 0.0,
    val resultTimeSeconds: Double? = null,
    val resultUncertaintyMs: Double? = null,

    // Error
    val errorMessage: String? = null
)
