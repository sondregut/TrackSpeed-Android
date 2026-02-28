package com.trackspeed.android.ui.screens.race

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.cloud.RaceEventService
import com.trackspeed.android.cloud.StorageService
import com.trackspeed.android.cloud.dto.CrossingDto
import com.trackspeed.android.cloud.dto.RaceEventDto
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.detection.CrossingEvent
import com.trackspeed.android.detection.GateEngine
import com.trackspeed.android.detection.PhotoFinishDetector
import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.sync.BleClockSyncService
import com.trackspeed.android.sync.ClockSyncManager
import com.trackspeed.android.sync.SyncQuality
import com.trackspeed.android.util.toJpeg
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val storageService: StorageService,
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

    // Supabase session ID (shared UUID for cross-platform thumbnail/crossing sync)
    private var supabaseSessionId: String? = null
    private var currentRunId: String = UUID.randomUUID().toString()
    private var crossingSubscriptionJob: Job? = null

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
                            // Transition to RACE_READY from SYNCING (client) or PAIRING (server receives SyncComplete)
                            phase = if (current.phase == RacePhase.SYNCING || current.phase == RacePhase.PAIRING) {
                                RacePhase.RACE_READY
                            } else {
                                current.phase
                            }
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
                        // Auto-resolve role from BLE dual-mode
                        val isServer = clockSyncManager.isServer.value
                        val resolvedRole = if (isServer) DeviceRole.START else DeviceRole.FINISH
                        _uiState.update {
                            it.copy(
                                pairingStatus = "connected",
                                isDeviceConnected = true,
                                role = resolvedRole,
                                isHostingSession = isServer,
                                // Server: stay in PAIRING to accept more clients
                                // Client: transition to SYNCING for handshake + clock sync
                                phase = if (isServer) it.phase else RacePhase.SYNCING
                            )
                        }
                    }
                    is BleClockSyncService.State.Syncing -> {
                        // Only transition to SYNCING for clients; server stays in PAIRING
                        if (!clockSyncManager.isServer.value) {
                            _uiState.update {
                                it.copy(
                                    phase = RacePhase.SYNCING,
                                    syncProgress = bleState.progress
                                )
                            }
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

        // Observe connected gate count from ClockSyncManager
        viewModelScope.launch {
            clockSyncManager.connectedGateCount.collect { count ->
                _uiState.update { it.copy(connectedDeviceCount = count) }
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

        // Observe Supabase session ID for cross-platform thumbnail sync
        viewModelScope.launch {
            clockSyncManager.supabaseSessionId.filterNotNull().collect { supabaseId ->
                Log.i(TAG, "Supabase session ID received: $supabaseId")
                supabaseSessionId = supabaseId
                // If already in active race, re-subscribe with correct session ID
                if (_uiState.value.phase == RacePhase.ACTIVE_RACE) {
                    restartCloudSubscriptions(supabaseId)
                }
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

        // Auto-start BLE dual-mode pairing when entering race mode
        startRaceMode()
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

    // === Auto BLE Pairing ===

    /**
     * Start race mode: goes directly to PAIRING phase with BLE dual-mode.
     * Both phones advertise and scan simultaneously. The first connection
     * resolves roles: server = START, client = FINISH.
     */
    fun startRaceMode() {
        sessionId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                phase = RacePhase.PAIRING,
                pairingStatus = "searching"
            )
        }
        // Start BLE dual-mode: advertise + scan simultaneously
        clockSyncManager.startAutoSync()
        Log.i(TAG, "Race mode started: BLE dual-mode pairing, sessionId=$sessionId")
    }

    /**
     * Called when enough phones are connected during PAIRING.
     * Proceeds to wait for sync to complete (handled by BLE state observer).
     */
    fun confirmPairing() {
        val count = _uiState.value.connectedDeviceCount
        if (count < 2) {
            Log.w(TAG, "Cannot confirm pairing: only $count devices connected")
            return
        }
        Log.i(TAG, "Pairing confirmed with $count devices")
        // Server is the reference clock (offset = 0), so it's always "synced"
        // Transition directly to RACE_READY
        _uiState.update {
            it.copy(
                phase = RacePhase.RACE_READY,
                syncProgress = 1f,
                syncOffsetMs = 0.0,
                syncUncertaintyMs = 0.0
            )
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
        currentRunId = UUID.randomUUID().toString()
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

        // Use Supabase session ID if available (fixes cross-platform session ID mismatch)
        val effectiveSessionId = supabaseSessionId ?: sessionId

        // Subscribe to cloud race events as backup to BLE
        startRaceEventSubscription(effectiveSessionId)

        // Subscribe to crossings table for thumbnail sync
        startCrossingSubscription(effectiveSessionId)
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

                // Grab thumbnail before any async work
                val thumbnail = gateEngine.lastCrossingThumbnail

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

                // Upload crossing with thumbnail to Supabase for cross-platform sync
                uploadCrossingWithThumbnail("start", crossingTimeNanos, thumbnail)
            }
            DeviceRole.FINISH -> {
                // Finish phone: record finish time, calculate split
                localFinishTimeNanos = crossingTimeNanos
                Log.i(TAG, "FINISH crossing detected at $crossingTimeNanos ns")

                // Grab thumbnail before any async work
                val thumbnail = gateEngine.lastCrossingThumbnail

                stopTimerTick()
                calculateResult()

                // Upload finish event to Supabase (fire-and-forget)
                uploadRaceEvent("finish", crossingTimeNanos)

                // Upload crossing with thumbnail to Supabase for cross-platform sync
                uploadCrossingWithThumbnail("finish", crossingTimeNanos, thumbnail)
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
                val effectiveSessionId = supabaseSessionId ?: sessionId
                raceEventService.insertRaceEvent(
                    RaceEventDto(
                        sessionId = effectiveSessionId,
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

    // === Cloud Subscriptions ===

    private fun startRaceEventSubscription(effectiveSessionId: String) {
        raceEventSubscriptionJob?.cancel()
        raceEventSubscriptionJob = viewModelScope.launch {
            try {
                raceEventService.subscribeToRaceEvents(effectiveSessionId)
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

    private fun startCrossingSubscription(effectiveSessionId: String) {
        crossingSubscriptionJob?.cancel()
        crossingSubscriptionJob = viewModelScope.launch {
            try {
                raceEventService.subscribeToCrossings(effectiveSessionId)
                    .filter { it.deviceId != deviceId && it.thumbnailUrl != null }
                    .collect { crossing ->
                        val bitmap = downloadThumbnail(crossing.thumbnailUrl!!)
                        if (bitmap != null) {
                            _uiState.update {
                                it.copy(
                                    peerThumbnail = bitmap,
                                    peerGateRole = crossing.gateRole
                                )
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Crossing subscription failed (non-critical)", e)
            }
        }
    }

    private fun restartCloudSubscriptions(supabaseId: String) {
        startRaceEventSubscription(supabaseId)
        startCrossingSubscription(supabaseId)
    }

    // === Thumbnail Upload/Download ===

    private fun uploadCrossingWithThumbnail(gateRole: String, crossingTimeNanos: Long, thumbnail: Bitmap?) {
        val sid = supabaseSessionId ?: return
        viewModelScope.launch {
            try {
                var thumbnailUrl: String? = null
                if (thumbnail != null) {
                    val imageData = thumbnail.toJpeg(quality = 30)
                    val path = "sessions/$sid/crossing_${gateRole}_${System.currentTimeMillis()}.jpg"
                    thumbnailUrl = storageService.uploadThumbnail("race-photos", path, imageData)
                }
                raceEventService.insertCrossing(
                    CrossingDto(
                        sessionId = sid,
                        runId = currentRunId,
                        gateRole = gateRole,
                        deviceId = deviceId,
                        crossingTimeNanos = crossingTimeNanos,
                        thumbnailUrl = thumbnailUrl
                    )
                )
                Log.d(TAG, "Uploaded crossing with thumbnail: gate=$gateRole, url=$thumbnailUrl")
            } catch (e: Exception) {
                Log.w(TAG, "Crossing/thumbnail upload failed (non-critical)", e)
            }
        }
    }

    private suspend fun downloadThumbnail(url: String): Bitmap? {
        return try {
            withContext(Dispatchers.IO) {
                val bytes = java.net.URL(url).readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download thumbnail", e)
            null
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

    fun resetToStart() {
        stopTimerTick()
        raceEventSubscriptionJob?.cancel()
        raceEventSubscriptionJob = null
        crossingSubscriptionJob?.cancel()
        crossingSubscriptionJob = null
        supabaseSessionId = null
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
        currentRunId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                phase = RacePhase.RACE_READY,
                resultTimeSeconds = null,
                resultUncertaintyMs = null,
                elapsedTimeSeconds = 0.0,
                raceStatus = "",
                errorMessage = null,
                peerThumbnail = null,
                peerGateRole = null
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
        crossingSubscriptionJob?.cancel()
        clockSyncManager.stop()
        gateEngine.stopMotionUpdates()
        cameraManager.closeCamera()
        gateEngine.reset()
    }
}

// === State Models ===

enum class RacePhase {
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
    val phase: RacePhase = RacePhase.PAIRING,
    val role: DeviceRole? = null,

    // Pairing
    val isHostingSession: Boolean = false,
    val pairingStatus: String = "",
    val isDeviceConnected: Boolean = false,
    val connectedDeviceCount: Int = 0,
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

    // Peer thumbnail (received via Supabase Realtime crossing sync)
    val peerThumbnail: Bitmap? = null,
    val peerGateRole: String? = null,

    // Error
    val errorMessage: String? = null
)
