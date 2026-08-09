package com.trackspeed.android.ui.screens.race

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.R
import com.trackspeed.android.analytics.AnalyticsEvent
import com.trackspeed.android.analytics.AnalyticsService
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.camera.CrossingThumbnailBuffer
import com.trackspeed.android.camera.reviewThumbnailTargetTimestamp
import com.trackspeed.android.cloud.RaceEventService
import com.trackspeed.android.cloud.StorageService
import com.trackspeed.android.cloud.ThumbnailUploadQueue
import com.trackspeed.android.cloud.CrossingDebugUploadQueue
import com.trackspeed.android.cloud.DeviceIdProvider
import com.trackspeed.android.cloud.TimingWorkloadCoordinator
import com.trackspeed.android.cloud.dto.CrossingDto
import com.trackspeed.android.cloud.dto.RaceEventDto
import com.trackspeed.android.cloud.safeCloudErrorCode
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.recovery.PersistedSessionState
import com.trackspeed.android.data.recovery.SessionStateRecoveryService
import com.trackspeed.android.data.repository.LocalGateFrameSnapshot
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.diagnostics.DetectionReviewLogStore
import com.trackspeed.android.detection.CrossingEvent
import com.trackspeed.android.detection.DetectionEngine
import com.trackspeed.android.detection.GateEngine
import com.trackspeed.android.detection.PhotoFinishDetector
import com.trackspeed.android.audio.VoiceStartPhase
import com.trackspeed.android.audio.VoiceStartService
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.model.StartSoundType
import com.trackspeed.android.model.StartType
import com.trackspeed.android.protocol.GateAssignment
import com.trackspeed.android.protocol.GateStatusInfo
import com.trackspeed.android.protocol.SegmentSplit
import com.trackspeed.android.protocol.SyncableTimingEvent
import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.protocol.TimingRole
import com.trackspeed.android.protocol.TimingSessionConfig
import com.trackspeed.android.sync.BleClockSyncService
import com.trackspeed.android.sync.ClockSyncManager
import com.trackspeed.android.sync.SyncQuality
import com.trackspeed.android.detection.GateReadinessStatus
import com.trackspeed.android.ui.components.DetectionReviewSubmission
import com.trackspeed.android.ui.components.TimingSessionEndConfirmation
import com.trackspeed.android.ui.components.TimingSessionEndOrigin
import com.trackspeed.android.ui.components.TimingSessionEndPresentation
import com.trackspeed.android.ui.components.TimingSessionEndSummary
import com.trackspeed.android.util.ImageDownloadValidator
import com.trackspeed.android.util.toJpeg
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max
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
    private val thumbnailUploadQueue: ThumbnailUploadQueue,
    private val crossingDebugUploadQueue: CrossingDebugUploadQueue,
    private val workloadCoordinator: TimingWorkloadCoordinator,
    private val sessionRepository: SessionRepository,
    private val athleteDao: AthleteDao,
    private val sessionStateRecoveryService: SessionStateRecoveryService,
    private val voiceStartService: VoiceStartService,
    private val settingsRepository: SettingsRepository,
    private val subscriptionManager: SubscriptionManager,
    private val detectionReviewLogStore: DetectionReviewLogStore,
    private val analyticsService: AnalyticsService,
    private val deviceIdProvider: DeviceIdProvider,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "RaceModeViewModel"
        private const val START_EVENT_WAIT_BLE_HEALTHY_MS = 6_000L
        private const val START_EVENT_WAIT_CLOUD_AVAILABLE_MS = 10_000L
        private const val START_EVENT_WAIT_P2P_ONLY_MS = 14_000L
        private const val START_EVENT_WAIT_BOTH_UNAVAILABLE_MS = 18_000L
        private const val START_EVENT_WAIT_WARNING_MS = 10_000L
        private const val START_EVENT_WAIT_RECOVERY_MS = 14_000L
        private const val AUTO_RESET_DELAY_MS = 3_000L
        private const val MAX_RESULT_SPLIT_NANOS = 300_000_000_000L // 5 minutes
        private const val EVENT_SYNC_RATE_LIMIT_NANOS = 5_000_000_000L
        private const val MAX_RUNS_IN_EVENT_LOG = 8
        private const val MAX_SEQUENCES_PER_SENDER = 500
        private const val MAX_PROCESSED_MESSAGE_IDS = 500
        private const val MAX_PENDING_REMOTE_THUMBNAILS_PER_RUN = 4
        private const val GATE_READINESS_RECOVERY_TIMEOUT_MS = 30_000L
        private const val MAX_GATE_READINESS_RECOVERY_ATTEMPTS = 3
        private const val LOCAL_GATE_CALIBRATION_TIMEOUT_MS = 4_000L
        private const val LOCAL_GATE_CALIBRATION_POLL_MS = 100L
        private const val LOCAL_SESSION_SAVE_TIMEOUT_MS = 5_000L
        private val DEFAULT_HOST_ROLE = TimingRole.FINISH_LINE
    }

    private val preConfiguredDistance = savedStateHandle.get<Float>("distance")
        ?.toDouble()?.takeIf { it > 0 }
    private val preConfiguredStartType = savedStateHandle.get<String>("startType")
        ?.ifBlank { null }
    private val preConfiguredNumberOfGates = savedStateHandle.get<Int>("numberOfGates") ?: 2
    private val preConfiguredGateDistances = savedStateHandle.get<String>("gateDistances")
        ?.ifBlank { null }
    private val entryMode = savedStateHandle.get<String>("mode")
        ?.lowercase()
        ?.takeIf { it in setOf("auto", "join", "host") }
        ?: "auto"
    private val configuredHostRole = parseTimingRole(
        savedStateHandle.get<String>("hostRole")
    ) ?: DEFAULT_HOST_ROLE
    private val athleteIdsRaw = savedStateHandle.get<String>("athleteIds") ?: ""
    private val isGuestJoinMode = savedStateHandle.get<Boolean>("guestJoin") ?: false
    private val initialSelectedAthleteIds = athleteIdsRaw.split(",")
        .filter { it.isNotBlank() }
        .toSet()
    private var sessionSelectedAthleteIds = initialSelectedAthleteIds
    private var sessionAthletes: List<AthleteEntity> = emptyList()
    private var activeAthleteId: String? = sessionSelectedAthleteIds.firstOrNull()
    private var hasStartedRaceMode = false
    private val initialStartType = StartType.fromRawValue(preConfiguredStartType ?: "flying").rawValue
    private val initialDistanceMeters = preConfiguredDistance ?: 60.0
    private val initialGateDistances = parseGateDistances(
        rawValue = preConfiguredGateDistances,
        gateCount = preConfiguredNumberOfGates,
        fallbackTotalDistanceMeters = initialDistanceMeters
    )

    private val deviceId: String by lazy { deviceIdProvider.deviceId }
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    // Session ID for cloud sync (generated per race session)
    private var sessionId: String = UUID.randomUUID().toString()

    // Supabase session ID (shared UUID for cross-platform thumbnail/crossing sync)
    private var supabaseSessionId: String? = null
    private var cloudSubscriptionSessionId: String? = null
    private var lastCloudRaceEventCreatedAt: String? = null
    private var lastCloudCrossingCreatedAt: String? = null
    private var currentRunId: String = UUID.randomUUID().toString()
    private val runIdentityRegistry = RunIdentityRegistry()
    private val finishConfirmationPauseCoordinator = FinishConfirmationPauseCoordinator()
    private var currentRunNumber = 1
    private var crossingSubscriptionJob: Job? = null
    private var cloudSubscriptionRecoveryJob: Job? = null
    private var activeSessionRunsJob: Job? = null
    private var trackedRaceSessionCreatedId: String? = null
    private var trackedRaceSessionCompletedId: String? = null

    private val _uiState = MutableStateFlow(
        RaceModeUiState(
            startType = initialStartType,
            distanceMeters = initialDistanceMeters,
            numberOfGates = preConfiguredNumberOfGates,
            gateDistances = initialGateDistances,
            hostRole = configuredHostRole,
            isJoinMode = entryMode == "join",
            isHostingSession = entryMode == "host",
            role = if (entryMode == "host") configuredHostRole.toDeviceRole() else null,
            selectedAthleteIds = sessionSelectedAthleteIds,
            activeAthleteId = activeAthleteId
        )
    )
    val uiState: StateFlow<RaceModeUiState> = _uiState.asStateFlow()

    val voiceStartServiceForOverlay: VoiceStartService
        get() = voiceStartService

    // Timer job
    private var timerJob: Job? = null
    // Realtime race event subscription
    private var raceEventSubscriptionJob: Job? = null

    // Timing state
    private var localStartTimeNanos: Long? = null
    private var localFinishTimeNanos: Long? = null
    private var remoteStartTimeNanos: Long? = null
    private var frameCount = 0L

    // Buffered finish crossing: saved when finish phone detects before start event arrives
    private var bufferedFinishTimeNanos: Long? = null
    private var bufferedFinishThumbnail: Bitmap? = null

    // Start event timeout
    private var startEventTimeoutJob: Job? = null
    private var multiGateTimeoutJob: Job? = null
    private var autoResetJob: Job? = null
    private var gateCalibrationJob: Job? = null
    private var lastPauseResumeTimestampNanos: Long = 0L
    private var pausedSinceNanos: Long = 0L
    private var appBackgroundPausedDetection = false
    private var appBackgroundedDuringStartedRun = false
    private var foregroundGateReadinessRecoveryInProgress = false
    private var detectionPausedForForegroundRecovery = false
    private var gateReadinessRecoveryAttempts = 0
    private var gateReadinessRecoveryJob: Job? = null
    private var liveTimingWorkloadActive = false

    // Message deduplication: track processed event IDs to prevent duplicates across BLE + cloud
    private val processedEventIds = mutableSetOf<String>()
    private val processedMessageIds = linkedSetOf<String>()
    private val receivedSequencesBySender = mutableMapOf<String, MutableSet<Long>>()
    private val lastReceivedSessionIdBySender = mutableMapOf<String, String>()
    private val pollingCrossingThumbnailIds = mutableSetOf<String>()
    private val completedResultStartReferences = mutableSetOf<Long>()
    private var lastProcessedFinishSplitNanos: Long? = null
    private val knownPeerDeviceIds = mutableSetOf<String>()
    private val pendingPeerReconnects = mutableSetOf<String>()
    private val reconnectReplayJobs = mutableMapOf<String, Job>()
    private val syncableEventLog = linkedMapOf<String, MutableList<SyncableTimingEvent>>()
    private val lastEventSyncAtBySender = mutableMapOf<String, Long>()
    private var syncableEventSeq = 0L
    private val pendingRemoteAdjustments = mutableMapOf<String, TimingPayload.AdjustmentUpdate>()
    private val pendingRemoteCalibrations = mutableMapOf<String, MutableList<RemoteGateCalibration>>()
    private val pendingRemoteThumbnails = mutableMapOf<String, MutableList<PendingRemoteThumbnail>>()
    private val remoteThumbnailMetadataByEventId = mutableMapOf<String, TimingPayload.ThumbnailMetadata>()
    private val recentStartSnapshots = linkedMapOf<Long, RecentStartSnapshot>()

    // Surface for camera
    private var previewSurface: Surface? = null

    private val crossingThumbnailBuffer = CrossingThumbnailBuffer()
    @Volatile private var localFrameScrubbingEnabled: Boolean = SettingsRepository.Defaults.ENABLE_FRAME_SCRUBBING
    private val localGateFrameBuffer = LocalGateFrameBuffer()

    // Thumbnail captured at finish crossing (saved with race result)
    private var finishThumbnail: Bitmap? = null
    private var localStartCalibration: LocalGateCalibration? = null
    private var localFinishCalibration: LocalGateCalibration? = null
    private var lastBroadcastCalibrationUpdate: TimingPayload.CalibrationUpdate? = null

    private val receivedGateCrossings = mutableMapOf<Int, RecordedGateCrossing>()

    init {
        viewModelScope.launch {
            processUploadQueuesIfIdle()
        }

        // Observe sync state from ClockSyncManager
        viewModelScope.launch {
            settingsRepository.startSoundType.collect { rawValue ->
                _uiState.update { it.copy(startSoundType = rawValue) }
            }
        }

        viewModelScope.launch {
            settingsRepository.showSpeedInResults.collect { enabled ->
                _uiState.update { it.copy(showSpeedInResults = enabled) }
            }
        }

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
            settingsRepository.preStartDelayMin.collect { value ->
                _uiState.update { it.copy(preStartDelayMin = value) }
            }
        }

        viewModelScope.launch {
            combine(
                settingsRepository.enableFrameScrubbing,
                settingsRepository.saveCrossingFrames
            ) { enableFrameScrubbing, saveCrossingFrames ->
                enableFrameScrubbing || saveCrossingFrames
            }.collect { enabled ->
                localFrameScrubbingEnabled = enabled
                if (!enabled) {
                    localGateFrameBuffer.reset()
                }
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
            athleteDao.getAllAthletes().collect { athletes ->
                val availableIds = athletes.map { it.id }.toSet()
                val sanitizedSelectedIds = sessionSelectedAthleteIds.intersect(availableIds)
                if (sanitizedSelectedIds != sessionSelectedAthleteIds) {
                    sessionSelectedAthleteIds = sanitizedSelectedIds
                }
                sessionAthletes = athletes.filter { it.id in sanitizedSelectedIds }
                activeAthleteId = activeAthleteId
                    ?.takeIf { it in sanitizedSelectedIds }
                    ?: sessionAthletes.firstOrNull()?.id
                _uiState.update {
                    it.copy(
                        athletes = athletes,
                        selectedAthleteIds = sanitizedSelectedIds,
                        activeAthleteId = activeAthleteId
                    )
                }
            }
        }

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
                            // A joiner is ready when its own sync completes. The
                            // host must remain in PAIRING until every configured
                            // timing phone has completed its handshake and sync.
                            phase = if (!clockSyncManager.isServer.value &&
                                !current.isHostingSession &&
                                (current.phase == RacePhase.SYNCING || current.phase == RacePhase.PAIRING)
                            ) {
                                RacePhase.RACE_READY
                            } else {
                                current.phase
                            }
                        )
                        is ClockSyncManager.SyncState.Error -> current.copy(
                            errorMessage = context.getString(R.string.race_error_clock_sync)
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
                            _uiState.update {
                                it.copy(
                                    pairingStatus = pairingStatusForMode(
                                        joinStatus = context.getString(R.string.race_pairing_status_scanning_host),
                                        hostStatus = context.getString(R.string.race_pairing_status_waiting_joiners),
                                        defaultStatus = context.getString(R.string.race_pairing_status_scanning)
                                    )
                                )
                            }
                        }
                    }
                    is BleClockSyncService.State.Connecting -> {
                        _uiState.update {
                            it.copy(
                                pairingStatus = pairingStatusForMode(
                                    joinStatus = context.getString(R.string.race_pairing_status_connecting_host),
                                    hostStatus = context.getString(R.string.race_pairing_status_connecting_phone),
                                    defaultStatus = context.getString(R.string.race_pairing_status_connecting)
                                )
                            )
                        }
                    }
                    is BleClockSyncService.State.Connected -> {
                        // Auto-resolve role from BLE dual-mode
                        val isServer = clockSyncManager.isServer.value
                        val resolvedRole = if (isServer) {
                            configuredHostRole.toDeviceRole()
                        } else {
                            clockSyncManager.localGateAssignment.value?.role?.toDeviceRole()
                        }
                        _uiState.update {
                            it.copy(
                                pairingStatus = context.getString(R.string.race_pairing_status_connected),
                                isDeviceConnected = true,
                                role = resolvedRole ?: it.role,
                                isHostingSession = isServer,
                                // Server: stay in PAIRING to accept more clients
                                // Client: transition to SYNCING for handshake + clock sync
                                phase = if (isServer) it.phase else RacePhase.SYNCING
                            )
                        }
                        if (isServer) {
                            trackRaceSessionCreatedIfNeeded()
                        }
                    }
                    is BleClockSyncService.State.Syncing -> {
                        // Only transition to SYNCING for clients; server stays in PAIRING
                        if (!clockSyncManager.isServer.value &&
                            currentPhase != RacePhase.ACTIVE_RACE &&
                            currentPhase != RacePhase.RESULT
                        ) {
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
                                phase = if (!clockSyncManager.isServer.value &&
                                    !it.isHostingSession &&
                                    (it.phase == RacePhase.SYNCING || it.phase == RacePhase.PAIRING)
                                ) {
                                    RacePhase.RACE_READY
                                } else {
                                    it.phase
                                },
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
                                errorMessage = context.getString(R.string.race_error_connection),
                                pairingStatus = context.getString(R.string.race_pairing_status_error)
                            )
                        }
                    }
                    is BleClockSyncService.State.Idle -> {
                        // No action needed
                    }
                    is BleClockSyncService.State.Pairing -> {
                        if (currentPhase == RacePhase.PAIRING) {
                            _uiState.update {
                                it.copy(
                                    pairingStatus = pairingStatusForMode(
                                        joinStatus = context.getString(R.string.race_pairing_status_scanning_host),
                                        hostStatus = context.getString(R.string.race_pairing_status_waiting_joiners),
                                        defaultStatus = context.getString(R.string.race_pairing_status_searching)
                                    )
                                )
                            }
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

        viewModelScope.launch {
            clockSyncManager.syncedGateCount.collect { count ->
                _uiState.update { it.copy(syncedDeviceCount = count) }
            }
        }

        // Observe this device's assigned timing gate.
        viewModelScope.launch {
            clockSyncManager.localGateAssignment.collect { assignment ->
                _uiState.update {
                    val configuredDistance = assignment?.gateIndex?.let { gateIndex ->
                        it.gateDistances[gateIndex]
                    }
                    it.copy(
                        gateAssignment = assignment?.let { gateAssignment ->
                            configuredDistance?.let { distance ->
                                gateAssignment.copy(distanceFromStart = distance)
                            } ?: gateAssignment
                        },
                        role = assignment?.role?.toDeviceRole() ?: it.role,
                        localGateIndex = assignment?.gateIndex,
                        localGateDistanceMeters = configuredDistance ?: assignment?.distanceFromStart
                    )
                }
                ensureDesiredCameraFacing()
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
                handleRemoteMessage(message)
            }
        }

        viewModelScope.launch {
            bleClockSyncService.connectionEvents.collect { event ->
                handleBleConnectionEvent(event)
            }
        }

        // Initialize camera early so preview dimensions are available for configureTransform
        viewModelScope.launch {
            initializeCamera(desiredFrontCamera(_uiState.value))
        }

        // Race mode starts after Compose has resolved Android runtime BLE permissions.
    }

    private fun initializeCamera(useFrontCamera: Boolean) {
        if (!cameraManager.initialize(useFrontCamera = useFrontCamera)) {
            _uiState.update {
                it.copy(
                    cameraState = CameraManager.CameraState.Error(
                        context.getString(R.string.camera_error_no_suitable)
                    )
                )
            }
        } else {
            updateCameraMetadata()
        }
    }

    private fun updateCameraMetadata() {
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

    private fun desiredFrontCamera(state: RaceModeUiState): Boolean {
        val startType = StartType.fromRawValue(state.startType)
        val assignmentRole = state.gateAssignment?.role
        val isStartGate = state.role == DeviceRole.START || assignmentRole == TimingRole.START_LINE
        return startType.usesFrontCamera && isStartGate
    }

    private fun ensureDesiredCameraFacing() {
        if (!_uiState.value.requiresLocalCamera) return

        val desiredFront = desiredFrontCamera(_uiState.value)
        val surface = previewSurface

        if (surface == null) {
            initializeCamera(desiredFront)
            return
        }

        if (cameraManager.isFrontCamera.value == desiredFront) return

        frameCount = 0
        gateEngine.stopMotionUpdates()
        crossingThumbnailBuffer.reset()
        cameraManager.switchCamera(surface) { frameData -> processFrame(frameData) }
        updateCameraMetadata()
    }

    // === Auto BLE Pairing ===

    private fun pairingStatusForMode(
        joinStatus: String,
        hostStatus: String,
        defaultStatus: String
    ): String = when (entryMode) {
        "join" -> joinStatus
        "host" -> hostStatus
        else -> defaultStatus
    }

    /**
     * Start race mode with explicit host/client routes matching iOS:
     * Create Session hosts; Join Session scans as a client. The legacy Race Mode
     * route can still use dual-mode pairing.
     */
    fun onBluetoothPermissionResult(granted: Boolean) {
        if (!granted) {
            _uiState.update {
                it.copy(
                    pairingStatus = context.getString(R.string.race_pairing_status_permission),
                    errorMessage = context.getString(R.string.race_error_nearby_permission)
                )
            }
            return
        }

        if (hasStartedRaceMode) return
        hasStartedRaceMode = true
        startRaceMode()
    }

    fun startRaceMode() {
        sessionId = UUID.randomUUID().toString()
        currentRunNumber = 1
        runIdentityRegistry.clear()
        recentStartSnapshots.clear()
        clearEnvelopeDedupeState()
        cloudSubscriptionSessionId = null
        lastCloudRaceEventCreatedAt = null
        lastCloudCrossingCreatedAt = null
        pollingCrossingThumbnailIds.clear()
        pendingRemoteThumbnails.clear()
        _uiState.update {
            it.copy(
                phase = RacePhase.PAIRING,
                pairingStatus = pairingStatusForMode(
                    joinStatus = context.getString(R.string.race_pairing_status_scanning_host),
                    hostStatus = context.getString(R.string.race_pairing_status_waiting_joiners),
                    defaultStatus = context.getString(R.string.race_pairing_status_searching)
                )
            )
        }
        val config = TimingSessionConfig(
            distance = _uiState.value.distanceMeters,
            startType = _uiState.value.startType,
            numberOfGates = _uiState.value.numberOfGates.coerceAtLeast(2),
            hostRole = configuredHostRole,
            fpsMode = 30,
            hostIsProUser = subscriptionManager.isProUser.value
        )

        when (entryMode) {
            "join" -> clockSyncManager.startAsClient()
            "host" -> clockSyncManager.startAsServer(config)
            else -> clockSyncManager.startAutoSync(config)
        }
        sessionId = bleClockSyncService.currentSessionId
        observeActiveSessionRuns(sessionId)
        trackRaceSessionCreatedIfNeeded()
        Log.i(TAG, "Race mode started: mode=$entryMode, sessionId=$sessionId")
    }

    private fun observeActiveSessionRuns(targetSessionId: String) {
        if (targetSessionId.isBlank()) return
        activeSessionRunsJob?.cancel()
        activeSessionRunsJob = viewModelScope.launch {
            sessionRepository.getRunsForSession(targetSessionId).collect { runs ->
                _uiState.update { it.copy(completedRuns = runs) }
            }
        }
    }

    /**
     * Called when enough phones are connected during PAIRING.
     * Proceeds to wait for sync to complete (handled by BLE state observer).
     */
    fun confirmPairing() {
        val state = _uiState.value
        val count = state.connectedDeviceCount
        val syncedCount = state.syncedDeviceCount
        val requiredCount = state.requiredPhysicalDeviceCount
        if (!pairingHasRequiredReadyDevices(count, syncedCount, requiredCount)) {
            Log.w(
                TAG,
                "Cannot confirm pairing: $count/$requiredCount connected, " +
                    "$syncedCount/$requiredCount synchronized"
            )
            _uiState.update {
                it.copy(
                    errorMessage = if (count < requiredCount) {
                        context.resources.getQuantityString(
                            R.plurals.race_error_connect_phones,
                            requiredCount,
                            requiredCount
                        )
                    } else {
                        context.resources.getQuantityString(
                            R.plurals.race_error_wait_sync,
                            requiredCount,
                            requiredCount
                        )
                    }
                )
            }
            return
        }
        Log.i(TAG, "Pairing confirmed with $count connected and $syncedCount synchronized devices")
        val sentStartTiming = bleClockSyncService.sendCriticalMessage(TimingPayload.StartTiming())
        Log.i(TAG, "Sent startTiming broadcast: $sentStartTiming")
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
        if (!_uiState.value.requiresLocalCamera) return
        previewSurface = surface
        frameCount = 0
        crossingThumbnailBuffer.reset()
        initializeCamera(desiredFrontCamera(_uiState.value))
        cameraManager.openCamera(surface) { frameData -> processFrame(frameData) }
    }

    fun onSurfaceDestroyed() {
        previewSurface = null
        gateEngine.stopMotionUpdates()
        cameraManager.closeCamera()
        crossingThumbnailBuffer.reset()
    }

    private fun processFrame(frameData: CameraManager.FrameData) {
        frameCount++
        crossingThumbnailBuffer.appendFrame(
            frame = frameData,
            orientationDegrees = cameraManager.getSensorOrientation(),
            isFrontCamera = cameraManager.isFrontCamera.value
        )
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

        gateEngine.processFrame(
            yPlane = frameData.yPlane,
            width = frameData.width,
            height = frameData.height,
            rowStride = frameData.rowStride,
            frameNumber = frameData.frameIndex,
            ptsNanos = frameData.timestampNanos,
            exposureNanos = frameData.exposureNanos
        )

        if (localFrameScrubbingEnabled && _uiState.value.phase == RacePhase.ACTIVE_RACE) {
            localGateFrameBuffer.appendFrame(
                frame = frameData,
                occupancy = gateEngine.gateReadinessStatus.value.rCenter.toFloat(),
                orientationDegrees = cameraManager.getSensorOrientation(),
                isFrontCamera = cameraManager.isFrontCamera.value
            )
        }
    }

    fun setGatePosition(position: Float) {
        gateEngine.setGatePosition(position)
        if (_uiState.value.phase == RacePhase.RACE_READY) {
            gateCalibrationJob?.cancel()
            gateCalibrationJob = null
            _uiState.update {
                it.copy(
                    isLocalGateCalibrating = false,
                    localGateStatus = defaultGateStatus(position.coerceIn(0.05f, 0.95f).toDouble())
                )
            }
        }
    }

    fun beginLocalGateCalibration() {
        val state = _uiState.value
        val canCalibrateForRecovery =
            state.phase == RacePhase.ACTIVE_RACE && foregroundGateReadinessRecoveryInProgress
        if (!state.requiresLocalCamera || (state.phase != RacePhase.RACE_READY && !canCalibrateForRecovery)) {
            Log.w(TAG, "Ignoring local gate calibration outside ready camera state")
            return
        }

        gateCalibrationJob?.cancel()
        gateEngine.resume()

        val calibratingStatus = gateStatusFromReadiness(
            readiness = gateEngine.gateReadinessStatus.value,
            isCalibrated = false,
            isArmed = false
        )
        _uiState.update {
            it.copy(
                isLocalGateCalibrating = true,
                localGateStatus = calibratingStatus,
                errorMessage = null
            )
        }
        publishLocalGateStatus(calibratingStatus)

        gateCalibrationJob = viewModelScope.launch {
            val startMs = SystemClock.elapsedRealtime()
            var lastPublishedStatus = calibratingStatus

            while (true) {
                val readiness = gateEngine.gateReadinessStatus.value
                val pendingStatus = gateStatusFromReadiness(
                    readiness = readiness,
                    isCalibrated = false,
                    isArmed = false
                )
                if (pendingStatus != lastPublishedStatus) {
                    _uiState.update { it.copy(localGateStatus = pendingStatus) }
                    publishLocalGateStatus(pendingStatus)
                    lastPublishedStatus = pendingStatus
                }

                if (readiness.canArm) {
                    completeLocalGateCalibration(readiness)
                    return@launch
                }

                if (SystemClock.elapsedRealtime() - startMs >= LOCAL_GATE_CALIBRATION_TIMEOUT_MS) {
                    failLocalGateCalibration(readiness)
                    return@launch
                }

                delay(LOCAL_GATE_CALIBRATION_POLL_MS)
            }
        }
    }

    private fun completeLocalGateCalibration(readiness: GateReadinessStatus) {
        if (!readiness.canArm) {
            failLocalGateCalibration(readiness)
            return
        }

        val calibratedStatus = gateStatusFromReadiness(
            readiness = readiness,
            isCalibrated = true,
            isArmed = true
        )

        _uiState.update {
            it.copy(
                isLocalGateCalibrating = false,
                localGateStatus = calibratedStatus,
                errorMessage = null
            )
        }
        publishLocalGateStatus(calibratedStatus)
        bleClockSyncService.sendMessage(
            TimingPayload.CalibrationStatus(
                gateId = deviceId,
                success = true,
                error = null
            )
        )
        maybeSendLocalArmedAck(calibratedStatus)
        tryCompleteGateReadinessRecovery("local gate calibrated")
        Log.i(TAG, "Local gate calibrated and armed")
    }

    private fun failLocalGateCalibration(readiness: GateReadinessStatus) {
        val status = gateStatusFromReadiness(
            readiness = readiness,
            isCalibrated = false,
            isArmed = false
        )
        val reason = gateReadinessBlockedReason(readiness)
        _uiState.update {
            it.copy(
                isLocalGateCalibrating = false,
                localGateStatus = status,
                errorMessage = reason
            )
        }
        publishLocalGateStatus(status)
        bleClockSyncService.sendMessage(
            TimingPayload.CalibrationStatus(
                gateId = deviceId,
                success = false,
                error = reason
            )
        )
        Log.w(
            TAG,
            "Local gate calibration blocked: $reason " +
                "(clear=${readiness.isClear}, prebuffer=${readiness.isPrebufferReady}, " +
                "stable=${readiness.isStable}, rCenter=${readiness.rCenter}, " +
                "prebufferMs=${readiness.prebufferSpanMs})"
        )
    }

    private fun gateStatusFromReadiness(
        readiness: GateReadinessStatus,
        isCalibrated: Boolean,
        isArmed: Boolean
    ): GateStatusInfo {
        return GateStatusInfo(
            isCalibrated = isCalibrated,
            isArmed = isArmed,
            isClear = readiness.isClear,
            isPrebufferReady = readiness.isPrebufferReady,
            isStable = readiness.isStable,
            gatePosition = _uiState.value.gatePosition.toDouble(),
            batteryLevel = null
        )
    }

    private fun gateReadinessBlockedReason(readiness: GateReadinessStatus): String {
        return when {
            !readiness.isStable ->
                context.getString(R.string.race_error_hold_before_arming)
            readiness.lastFrameTimestampNanos == null ->
                context.getString(R.string.race_error_wait_camera_frames)
            !readiness.isPrebufferReady ->
                context.getString(R.string.race_error_wait_camera_buffer)
            !readiness.isClear ->
                context.getString(R.string.race_error_gate_occupied)
            else ->
                context.getString(R.string.race_error_gate_not_ready)
        }
    }

    private fun startSessionBlockedReason(state: RaceModeUiState): String {
        return when {
            !state.isLocalGateReady ->
                context.getString(R.string.race_error_calibrate_phone)
            !state.isRemoteGateReadinessSatisfied -> {
                val missing = (state.requiredRemoteReadyGateCount - state.remoteArmedGateIds.size)
                    .coerceAtLeast(1)
                context.resources.getQuantityString(
                    R.plurals.race_error_wait_gate_phones,
                    missing,
                    missing
                )
            }
            else ->
                context.getString(R.string.race_error_gates_not_ready)
        }
    }

    private fun publishLocalGateStatus(status: GateStatusInfo) {
        bleClockSyncService.sendMessage(
            TimingPayload.GateStatus(
                gateId = deviceId,
                status = status
            )
        )
    }

    private fun maybeSendLocalArmedAck(status: GateStatusInfo = _uiState.value.localGateStatus) {
        if (!status.isReady) return
        val role = _uiState.value.gateAssignment?.role
            ?: _uiState.value.role?.toTimingRole()
            ?: return
        if (role == TimingRole.CONTROL_ONLY) return

        bleClockSyncService.sendCriticalMessage(
            TimingPayload.ArmedAck(
                gateId = deviceId,
                role = role
            )
        )
    }

    private fun markLocalGateFrameEvent(crossingTimeNanos: Long) {
        if (localFrameScrubbingEnabled) {
            localGateFrameBuffer.markEvent(crossingTimeNanos)
        }
    }

    private suspend fun localGateFramesForSave(runId: String): List<LocalGateFrameSnapshot> {
        if (!localFrameScrubbingEnabled) return emptyList()

        val deadlineNanos = SystemClock.elapsedRealtimeNanos() + 650_000_000L
        while (localGateFrameBuffer.isPostrollActive &&
            SystemClock.elapsedRealtimeNanos() < deadlineNanos
        ) {
            delay(25)
        }

        val frames = localGateFrameBuffer.snapshotEventFrames()
        if (frames.isNotEmpty()) {
            Log.i(TAG, "[FrameScrub] Saving ${frames.size} local gate frames for run ${runId.take(8)}")
        }
        return frames
    }

    private suspend fun selectedRaceAthleteForSave(): AthleteEntity? {
        activeAthleteId?.let { id ->
            sessionAthletes.firstOrNull { it.id == id }?.let { return it }
            athleteDao.getAthleteById(id)?.let { return it }
        }
        sessionAthletes.firstOrNull()?.let { return it }
        return sessionSelectedAthleteIds.firstOrNull()?.let { athleteDao.getAthleteById(it) }
    }

    private fun snapshotActiveStartForLateResult() {
        val localStart = localStartTimeNanos ?: return
        val startReference = remoteStartTimeNanos ?: localStart
        if (startReference <= 0L || completedResultStartReferences.contains(startReference)) return

        val state = _uiState.value
        recentStartSnapshots[startReference] = RecentStartSnapshot(
            runId = canonicalRunId(currentRunId),
            runNumber = currentRunNumber,
            startReferenceNanos = startReference,
            localStartNanos = localStart,
            capturedAtMillis = System.currentTimeMillis(),
            distanceMeters = state.distanceMeters,
            startType = state.startType,
            numberOfGates = state.numberOfGates,
            gateDistances = state.gateDistances,
            gatePosition = state.gatePosition,
            athleteId = activeAthleteId
        )
        while (recentStartSnapshots.size > MAX_RUNS_IN_EVENT_LOG) {
            recentStartSnapshots.remove(recentStartSnapshots.keys.first())
        }
        Log.i(TAG, "Retained start ${startReference} for delayed result pairing")
    }

    // === Session Start ===

    fun startSession() {
        val state = _uiState.value
        if (!state.isHostingSession) {
            Log.w(TAG, "Ignoring local startSession on non-host device")
            return
        }
        if (!state.canStartSession) {
            val reason = startSessionBlockedReason(state)
            _uiState.update { it.copy(errorMessage = reason) }
            Log.w(TAG, "Blocked startSession: $reason")
            return
        }

        beginActiveSession(
            runId = UUID.randomUUID().toString(),
            announceToPeers = true
        )
    }

    private fun beginActiveSession(
        runId: String,
        announceToPeers: Boolean
    ) {
        snapshotActiveStartForLateResult()
        beginLiveTimingWorkloadIfNeeded()
        clearForegroundGateReadinessRecovery()
        autoResetJob?.cancel()
        autoResetJob = null
        cancelMultiGateTimeout()
        gateCalibrationJob?.cancel()
        gateCalibrationJob = null
        // Use existing sessionId from pairing code if set, otherwise generate new
        if (sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString()
        }
        observeActiveSessionRuns(sessionId)
        currentRunId = runId
        val syncFrozen = clockSyncManager.freezeActiveSessionSync("active session started")
        if (!syncFrozen) {
            Log.w(TAG, "Active session started before clock sync could be frozen")
        }
        _uiState.update {
            it.copy(
                phase = RacePhase.ACTIVE_RACE,
                raceStatus = "waiting",
                elapsedTimeSeconds = 0.0,
                resultTimeSeconds = null,
                resultUncertaintyMs = null,
                resultSegments = emptyList(),
                receivedGateCount = 0,
                peerThumbnail = null,
                peerGateRole = null,
                errorMessage = null
            )
        }
        localStartTimeNanos = null
        localFinishTimeNanos = null
        remoteStartTimeNanos = null
        pausedSinceNanos = 0L
        appBackgroundPausedDetection = false
        appBackgroundedDuringStartedRun = false
        bufferedFinishTimeNanos = null
        bufferedFinishThumbnail = null
        finishThumbnail = null
        localStartCalibration = null
        localFinishCalibration = null
        lastBroadcastCalibrationUpdate = null
        processedEventIds.clear()
        clearResultDedupeState()
        receivedGateCrossings.clear()
        localGateFrameBuffer.reset()
        remoteThumbnailMetadataByEventId.clear()
        if (_uiState.value.requiresLocalCamera) {
            gateEngine.reset()
            gateEngine.resume()
        }

        if (announceToPeers) {
            bleClockSyncService.sendCriticalMessage(
                TimingPayload.StartRun(countdownSeconds = 0),
                runId = currentRunId
            )
        }

        cancelStartEventWaitTimeout()

        // Use Supabase session ID if available (fixes cross-platform session ID mismatch)
        val effectiveSessionId = supabaseSessionId ?: sessionId

        // Subscribe to cloud race events as backup to BLE
        startRaceEventSubscription(effectiveSessionId)

        // Subscribe to crossings table for thumbnail sync
        startCrossingSubscription(effectiveSessionId)
    }

    // === Voice Start ===

    private fun prepareLocalStartForTimestamp(startTimestampNanos: Long): Boolean {
        val previousStart = localStartTimeNanos
        if (previousStart == null) return true

        if (startTimestampNanos <= previousStart) {
            Log.d(TAG, "Ignoring stale local start $startTimestampNanos; previous=$previousStart")
            return false
        }

        if (_uiState.value.phase != RacePhase.ACTIVE_RACE || localFinishTimeNanos != null) {
            Log.d(TAG, "Ignoring local replacement start outside active unfinished run")
            return false
        }

        val previousRunId = currentRunId
        val replacementRunId = UUID.randomUUID().toString()
        currentRunId = runIdentityRegistry.registerAlias(previousRunId, replacementRunId)
        remoteStartTimeNanos = null
        clearResultDedupeState()
        bufferedFinishTimeNanos = null
        bufferedFinishThumbnail = null
        Log.i(TAG, "Replacement local start accepted; new runId=${currentRunId.take(8)}")
        return true
    }

    /**
     * Start a voice countdown sequence ("On your marks... Set... GO!").
     * Only available for the START phone. When the GO! beep plays, the
     * audio-compensated timestamp is used as the start time, accounting
     * for speaker pipeline latency (matches iOS monotonicNanosAudioCompensated).
     */
    fun startVoiceCountdown() {
        if (_uiState.value.role != DeviceRole.START) {
            Log.w(TAG, "Voice start only available on START phone")
            return
        }
        if (_uiState.value.phase != RacePhase.ACTIVE_RACE) {
            Log.w(TAG, "Cannot start voice countdown outside ACTIVE_RACE phase")
            return
        }

        _uiState.update { it.copy(voiceStartPhase = VoiceStartPhase.PRE_START) }

        viewModelScope.launch {
            // Observe phase changes for UI
            val phaseJob = launch {
                voiceStartService.phase.collect { phase ->
                    _uiState.update { it.copy(voiceStartPhase = phase) }
                }
            }

            voiceStartService.speakCountdown { audioCompensatedTimestamp ->
                if (!prepareLocalStartForTimestamp(audioCompensatedTimestamp)) {
                    phaseJob.cancel()
                    return@speakCountdown
                }

                // This is the precise GO! moment with audio latency compensation
                localStartTimeNanos = audioCompensatedTimestamp
                Log.i(TAG, "Voice GO! at $audioCompensatedTimestamp ns (audio-compensated)")

                _uiState.update { it.copy(raceStatus = "started") }
                startTimerTick()

                // Send start event to finish phone via BLE (raw local timestamp)
                val sent = bleClockSyncService.sendMessage(
                    TimingPayload.StartEvent(
                        monotonicNanos = audioCompensatedTimestamp,
                        thumbnailData = null
                    ),
                    runId = currentRunId
                )
                Log.i(TAG, "Sent voice StartEvent via BLE: sent=$sent")
                recordSyncableStartEvent(currentRunId, audioCompensatedTimestamp)

                // Upload start event to Supabase
                uploadRaceEvent("start", audioCompensatedTimestamp)

                if (_uiState.value.numberOfGates > 2) {
                    val assignment = _uiState.value.gateAssignment ?: GateAssignment.start()
                    val crossing = RecordedGateCrossing(
                        gateId = deviceId,
                        role = TimingRole.START_LINE,
                        gateIndex = assignment.gateIndex.coerceAtLeast(0),
                        timestampNanos = audioCompensatedTimestamp,
                        thumbnail = null
                    )
                    if (recordGateCrossing(crossing)) {
                        val eventId = TimingMessage.generateEventId(
                            currentRunId,
                            deviceId,
                            audioCompensatedTimestamp
                        )
                        bleClockSyncService.sendCriticalMessage(
                            TimingPayload.CrossingEvent(
                                gateId = deviceId,
                                role = TimingRole.START_LINE,
                                gateIndex = crossing.gateIndex,
                                timestampNanos = audioCompensatedTimestamp,
                                confidence = 1.0,
                                thumbnailData = null
                            ),
                            eventId = eventId,
                            runId = currentRunId
                        )
                        uploadCrossingWithThumbnail("start", audioCompensatedTimestamp, null)
                        tryCalculateMultiGateResult()
                    }
                }
            }

            phaseJob.cancel()
        }
    }

    /**
     * Cancel a running voice countdown.
     */
    fun cancelVoiceCountdown() {
        voiceStartService.cancel()
        _uiState.update { it.copy(voiceStartPhase = VoiceStartPhase.IDLE) }
    }

    fun setStartSoundType(rawValue: String) {
        viewModelScope.launch {
            settingsRepository.setStartSoundType(rawValue)
        }
    }

    fun setShowSpeedInResults(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowSpeedInResults(enabled)
        }
    }

    fun updateRunDistance(runId: String, newDistance: Double) {
        viewModelScope.launch {
            try {
                sessionRepository.updateRunDistance(runId, newDistance.coerceAtLeast(1.0))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update race run distance", e)
                _uiState.update {
                    it.copy(errorMessage = context.getString(R.string.race_error_update_distance))
                }
            }
        }
    }

    fun deleteRun(runId: String) {
        viewModelScope.launch {
            try {
                sessionRepository.deleteRun(runId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete race run", e)
                _uiState.update {
                    it.copy(errorMessage = context.getString(R.string.race_error_delete_run))
                }
            }
        }
    }

    fun submitCrossingReview(submission: DetectionReviewSubmission) {
        viewModelScope.launch {
            val target = submission.target
            detectionReviewLogStore.appendForContext(
                sessionId = target.sessionId,
                mode = target.mode,
                role = target.gateLabel,
                gateIndex = gateIndexFromGateLabel(target.gateLabel),
                message = submission.rawMessage
            )

            val uploaded = raceEventService.insertCrossingReviewMark(
                sessionId = target.sessionId,
                runNumber = target.runNumber,
                gateLabel = target.gateLabel,
                target = target.target,
                mode = target.mode,
                crossingDirection = target.crossingDirection,
                issue = submission.issue,
                actualX = submission.actualX?.toDouble(),
                actualY = submission.actualY?.toDouble(),
                detectorX = target.detectorX.toDouble(),
                detectorY = target.detectorY?.toDouble(),
                deltaX = submission.actualX?.let { it.toDouble() - target.detectorX.toDouble() },
                deltaY = submission.actualY?.let { actualY ->
                    target.detectorY?.let { actualY.toDouble() - it.toDouble() }
                },
                interpolationAlpha = target.interpolationAlpha,
                framePick = target.framePick,
                s0 = target.s0,
                s1 = target.s1,
                isFrontCamera = target.isFrontCamera,
                detectionDistance = target.detectionDistance,
                workWidth = target.workWidth,
                exposureMs = target.exposureMs,
                iso = target.iso,
                detectorTriggerFramePts = target.detectorTriggerFramePts,
                chosenThumbnailFramePts = target.chosenThumbnailFramePts,
                savedThumbnailFramePts = target.savedThumbnailFramePts,
                note = submission.note.ifBlank { null },
                rawMessage = submission.rawMessage,
                rawImageData = submission.rawImageData,
                reviewImageData = submission.reviewImageData
            )

            val uploadStatus = if (uploaded) "complete" else "failed"
            val uploadTag = if (submission.rawMessage.startsWith("[DETECTION-NOTE]")) {
                "DETECTION-NOTE-UPLOAD"
            } else {
                "DETECTION-MARK-UPLOAD"
            }
            detectionReviewLogStore.appendForContext(
                sessionId = target.sessionId,
                mode = target.mode,
                role = target.gateLabel,
                gateIndex = gateIndexFromGateLabel(target.gateLabel),
                message = "[$uploadTag] event=$uploadStatus schema=4 markerKey=${target.runId}:${target.gateLabel}"
            )
        }
    }

    private fun gateIndexFromGateLabel(gateLabel: String?): Int {
        val normalized = gateLabel?.trim()?.lowercase() ?: return 0
        return when {
            normalized == "start" -> 0
            normalized == "finish" -> (_uiState.value.numberOfGates - 1).coerceAtLeast(1)
            normalized.startsWith("lap ") -> normalized.substringAfter("lap ").toIntOrNull() ?: 0
            normalized.startsWith("lap_") -> normalized.substringAfter("lap_").toIntOrNull() ?: 0
            else -> 0
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

    fun handleExternalStart(startTimestampNanos: Long) {
        val state = _uiState.value
        if (state.phase != RacePhase.ACTIVE_RACE) return

        val assignment = state.gateAssignment ?: fallbackGateAssignment(state.role, state.numberOfGates)
        val isStartDevice = state.role == DeviceRole.START || assignment?.role == TimingRole.START_LINE
        if (!isStartDevice) {
            Log.w(TAG, "Ignoring external start on non-start device")
            return
        }
        if (!prepareLocalStartForTimestamp(startTimestampNanos)) {
            return
        }

        localStartTimeNanos = startTimestampNanos
        _uiState.update {
            it.copy(
                raceStatus = "started",
                voiceStartPhase = VoiceStartPhase.STARTED
            )
        }
        startTimerTick()

        bleClockSyncService.sendMessage(
            TimingPayload.StartEvent(
                monotonicNanos = startTimestampNanos,
                thumbnailData = null
            ),
            runId = currentRunId
        )
        recordSyncableStartEvent(currentRunId, startTimestampNanos)
        uploadRaceEvent("start", startTimestampNanos)

        if (state.numberOfGates > 2 && assignment != null) {
            val referenceTimeNanos = referenceTimestampFromLocal(startTimestampNanos)
            val crossing = RecordedGateCrossing(
                gateId = deviceId,
                role = TimingRole.START_LINE,
                gateIndex = assignment.gateIndex.coerceAtLeast(0),
                timestampNanos = referenceTimeNanos,
                thumbnail = null
            )
            if (recordGateCrossing(crossing)) {
                val eventId = TimingMessage.generateEventId(
                    currentRunId,
                    deviceId,
                    referenceTimeNanos
                )
                bleClockSyncService.sendCriticalMessage(
                    TimingPayload.CrossingEvent(
                        gateId = deviceId,
                        role = TimingRole.START_LINE,
                        gateIndex = crossing.gateIndex,
                        timestampNanos = referenceTimeNanos,
                        confidence = 1.0,
                        thumbnailData = null
                    ),
                    eventId = eventId,
                    runId = currentRunId
                )
                uploadCrossingWithThumbnail("start", referenceTimeNanos, null)
                tryCalculateMultiGateResult()
            }
        }

        Log.i(TAG, "External ${state.startType} start triggered at $startTimestampNanos ns")
    }

    // === Crossing Handling ===

    private fun CrossingEvent.toLocalGateCalibration(
        role: TimingRole,
        gatePosition: Float,
        gateIndex: Int? = null
    ): LocalGateCalibration {
        return LocalGateCalibration(
            role = role,
            gateIndex = gateIndex,
            gatePosition = gatePosition,
            velocityPxPerSec = velocityPxPerSec,
            crossingDirection = crossingDirection,
            workWidth = workWidth,
            thumbnailDebug = toGateThumbnailDebugJson(gatePosition)
        )
    }

    private fun CrossingEvent.toGateThumbnailDebugJson(gatePosition: Float): JsonElement {
        return buildJsonObject {
            put("configuredGatePosition", JsonPrimitive(gatePosition))
            put("detectorPosition", JsonPrimitive(xAnchorRuntimeDisplayX ?: gatePosition))
            xAnchorRuntimeDisplayX?.let { put("interpolatedDisplayPosition", JsonPrimitive(it)) }
            put("detYSource", JsonPrimitive(xAnchorRuntimeRule?.let { "torsoFraction030+$it" } ?: "torsoFraction030"))
            put("velocityPxPerSec", JsonPrimitive(velocityPxPerSec))
            detectorYNormalized?.let { put("detectorYPosition", JsonPrimitive(it)) }
            interpolationAlpha?.let { put("interpolationAlpha", JsonPrimitive(it)) }
            framePick?.let { put("chosenFramePick", JsonPrimitive(it)) }
            s0?.let { put("s0", JsonPrimitive(it)) }
            s1?.let { put("s1", JsonPrimitive(it)) }
            isFrontCamera?.let { put("isFrontCamera", JsonPrimitive(it)) }
            workWidth?.let { put("workBufferW", JsonPrimitive(it)) }
            detectorTriggerFramePts?.let { put("detectorTriggerFramePtsNanos", JsonPrimitive(it)) }
            chosenThumbnailFramePts?.let { put("chosenThumbnailFramePtsNanos", JsonPrimitive(it)) }
            savedThumbnailFramePts?.let { put("savedThumbnailFramePtsNanos", JsonPrimitive(it)) }
        }
    }

    private fun broadcastCalibrationUpdate(calibration: LocalGateCalibration) {
        val payload = TimingPayload.CalibrationUpdate(
            role = calibration.role,
            gatePosition = calibration.gatePosition,
            velocityPxPerSec = calibration.velocityPxPerSec,
            crossingDirection = calibration.crossingDirection,
            workWidth = calibration.workWidth,
            thumbnailDebug = calibration.thumbnailDebug
        )
        lastBroadcastCalibrationUpdate = payload
        bleClockSyncService.sendMessage(
            payload,
            runId = currentRunId
        )
    }

    private fun sendThumbnailWireUpdates(
        eventId: String,
        assignment: GateAssignment,
        calibration: LocalGateCalibration,
        thumbnail: Bitmap?
    ) {
        bleClockSyncService.sendMessage(
            TimingPayload.ThumbnailMetadata(
                eventId = eventId,
                gateId = deviceId,
                role = assignment.role,
                gateIndex = assignment.gateIndex,
                gatePosition = calibration.gatePosition,
                velocityPxPerSec = calibration.velocityPxPerSec,
                crossingDirection = calibration.crossingDirection,
                workWidth = calibration.workWidth,
                thumbnailDebug = calibration.thumbnailDebug
            ),
            eventId = eventId,
            runId = currentRunId
        )

        if (thumbnail == null) return
        val thumbnailData = runCatching {
            Base64.encodeToString(thumbnail.toJpeg(quality = 35), Base64.NO_WRAP)
        }.onFailure {
            Log.w(TAG, "Failed to encode thumbnail update for ${eventId.take(16)}", it)
        }.getOrNull() ?: return

        val payload = TimingPayload.ThumbnailUpdate(
            eventId = eventId,
            gateId = deviceId,
            role = assignment.role,
            thumbnailData = thumbnailData
        )
        val state = _uiState.value
        val knownRecipients = if (state.isHostingSession) {
            buildSet {
                addAll(knownPeerDeviceIds)
                addAll(bleClockSyncService.connectedPeerDeviceIds())
                remove(deviceId)
            }
        } else {
            emptySet()
        }
        if (knownRecipients.isEmpty()) {
            bleClockSyncService.sendCriticalMessage(
                payload,
                eventId = eventId,
                runId = currentRunId
            )
        } else {
            knownRecipients.forEach { peerDeviceId ->
                bleClockSyncService.sendCriticalMessage(
                    payload,
                    eventId = eventId,
                    targetDeviceId = peerDeviceId,
                    runId = currentRunId
                )
            }
        }
    }

    private fun handleLocalCrossing(event: CrossingEvent) {
        val currentState = _uiState.value
        if (currentState.phase != RacePhase.ACTIVE_RACE) return
        if (currentState.raceStatus == "paused") return

        // Use the precise timestamp from PhotoFinishDetector which includes
        // trajectory regression (6-point linear interpolation) and rolling shutter correction,
        // providing up to ~30ms of sub-frame timing precision
        val crossingTimeNanos = event.timestamp

        // Capture color thumbnail immediately (before any coroutine dispatch)
        val thumbnailTargetPts = event.detectorTriggerFramePts?.let { triggerPts ->
            reviewThumbnailTargetTimestamp(
                detectorTriggerFramePtsNanos = triggerPts,
                detectorSelectedFramePtsNanos = event.chosenThumbnailFramePts ?: triggerPts,
                supportsLivePersonSelector = false
            )
        } ?: event.chosenThumbnailFramePts
        val thumbnail = crossingThumbnailBuffer.bitmapClosestTo(thumbnailTargetPts)

        val assignment = currentState.gateAssignment
            ?: fallbackGateAssignment(currentState.role, currentState.numberOfGates)
        val startType = StartType.fromRawValue(currentState.startType)
        if (startType.usesStartTrigger &&
            (currentState.role == DeviceRole.START || assignment?.role == TimingRole.START_LINE)
        ) {
            Log.d(TAG, "Ignoring start camera crossing for trigger-based start type ${startType.rawValue}")
            return
        }
        if (currentState.numberOfGates > 2 && assignment != null) {
            handleLocalMultiGateCrossing(assignment, event, crossingTimeNanos, thumbnail)
            return
        }

        when (currentState.role) {
            DeviceRole.START -> {
                if (!prepareLocalStartForTimestamp(crossingTimeNanos)) {
                    return
                }

                // Start phone: record start time, notify finish phone via BLE
                localStartTimeNanos = crossingTimeNanos
                markLocalGateFrameEvent(crossingTimeNanos)
                val calibration = event.toLocalGateCalibration(TimingRole.START_LINE, currentState.gatePosition)
                localStartCalibration = calibration
                Log.i(TAG, "START crossing detected at $crossingTimeNanos ns")

                _uiState.update {
                    it.copy(raceStatus = "started")
                }

                // Start the live timer
                startTimerTick()

                // Send start event to finish phone via BLE
                // Send raw local timestamp (sender's clock) — receiver converts via toLocalTime()
                // This matches the iOS convention where startEvent carries the sender's raw monotonic time
                val sent = bleClockSyncService.sendMessage(
                    TimingPayload.StartEvent(
                        monotonicNanos = crossingTimeNanos,
                        thumbnailData = null
                    ),
                    runId = currentRunId
                )
                Log.i(TAG, "Sent StartEvent via BLE (local=$crossingTimeNanos): sent=$sent")
                recordSyncableStartEvent(currentRunId, crossingTimeNanos)
                broadcastCalibrationUpdate(calibration)

                // Upload start event to Supabase (fire-and-forget)
                uploadRaceEvent("start", crossingTimeNanos)

                // Upload crossing with thumbnail to Supabase for cross-platform sync
                uploadCrossingWithThumbnail("start", crossingTimeNanos, thumbnail)
            }
            DeviceRole.FINISH -> {
                // Finish phone: record finish time, calculate split
                localFinishTimeNanos = crossingTimeNanos
                markLocalGateFrameEvent(crossingTimeNanos)
                finishThumbnail = thumbnail
                val calibration = event.toLocalGateCalibration(TimingRole.FINISH_LINE, currentState.gatePosition)
                localFinishCalibration = calibration
                Log.i(TAG, "FINISH crossing detected at $crossingTimeNanos ns")
                broadcastCalibrationUpdate(calibration)

                if (localStartTimeNanos != null) {
                    // Start event already arrived — calculate immediately
                    stopTimerTick()
                    calculateResult()
                } else {
                    // Start event hasn't arrived yet (BLE latency) — buffer the finish crossing
                    // It will be processed when onRemoteStartReceived() is called
                    bufferedFinishTimeNanos = crossingTimeNanos
                    bufferedFinishThumbnail = thumbnail
                    Log.i(TAG, "Buffered finish crossing — waiting for start event from remote")
                    _uiState.update { it.copy(raceStatus = "waiting_for_start") }
                    scheduleStartEventWaitTimeout()
                }

                // Upload crossing with thumbnail to Supabase for cross-platform sync.
                // The finish race_event is uploaded after result calculation with iOS
                // semantics: crossing_time_nanos carries splitNanos, not raw finish time.
                uploadCrossingWithThumbnail("finish", crossingTimeNanos, thumbnail)
            }
            DeviceRole.LAP -> {
                Log.w(TAG, "Ignoring LAP crossing in two-gate timing path")
            }
            DeviceRole.CONTROL -> {
                Log.d(TAG, "Ignoring local crossing on control-only host")
            }
            null -> { /* Should not happen */ }
        }
    }

    private fun handleLocalMultiGateCrossing(
        assignment: GateAssignment,
        event: CrossingEvent,
        crossingTimeNanos: Long,
        thumbnail: Bitmap?
    ) {
        if (assignment.role == TimingRole.CONTROL_ONLY || assignment.gateIndex < 0) return
        if (assignment.role == TimingRole.START_LINE &&
            !prepareLocalStartForTimestamp(crossingTimeNanos)
        ) {
            return
        }
        val referenceTimeNanos = referenceTimestampFromLocal(crossingTimeNanos)
        val calibration = event.toLocalGateCalibration(
            role = assignment.role,
            gatePosition = _uiState.value.gatePosition,
            gateIndex = assignment.gateIndex
        )

        val crossing = RecordedGateCrossing(
            gateId = deviceId,
            role = assignment.role,
            gateIndex = assignment.gateIndex,
            timestampNanos = referenceTimeNanos,
            thumbnail = thumbnail,
            calibration = calibration
        )
        if (!recordGateCrossing(crossing)) return
        markLocalGateFrameEvent(crossingTimeNanos)

        val gateRole = gateRoleString(assignment.role, assignment.gateIndex)
        val eventId = TimingMessage.generateEventId(currentRunId, deviceId, referenceTimeNanos)
        recordSyncableCrossingEvent(currentRunId, eventId, crossing)
        Log.i(TAG, "Local multi-gate crossing: gate=${assignment.gateIndex}, role=${assignment.role}, local=$crossingTimeNanos, reference=$referenceTimeNanos")

        bleClockSyncService.sendCriticalMessage(
            TimingPayload.CrossingEvent(
                gateId = deviceId,
                role = assignment.role,
                gateIndex = assignment.gateIndex,
                timestampNanos = referenceTimeNanos,
                confidence = 1.0,
                thumbnailData = null
            ),
            eventId = eventId,
            runId = currentRunId
        )
        sendThumbnailWireUpdates(
            eventId = eventId,
            assignment = assignment,
            calibration = calibration,
            thumbnail = thumbnail
        )
        broadcastCalibrationUpdate(calibration)

        if (assignment.role == TimingRole.START_LINE) {
            uploadRaceEvent("start", crossingTimeNanos)
        }
        uploadCrossingWithThumbnail(gateRole, referenceTimeNanos, thumbnail)

        when (assignment.role) {
            TimingRole.START_LINE -> {
                localStartTimeNanos = crossingTimeNanos
                localStartCalibration = calibration
                _uiState.update { it.copy(raceStatus = "started") }
                startTimerTick()

                bleClockSyncService.sendMessage(
                    TimingPayload.StartEvent(
                        monotonicNanos = crossingTimeNanos,
                        thumbnailData = null
                    ),
                    runId = currentRunId
                )
                recordSyncableStartEvent(currentRunId, crossingTimeNanos)
            }
            TimingRole.FINISH_LINE -> {
                localFinishTimeNanos = referenceTimeNanos
                finishThumbnail = thumbnail
                localFinishCalibration = calibration
                _uiState.update { it.copy(raceStatus = "waiting_for_result") }
            }
            TimingRole.LAP_GATE -> {
                _uiState.update { it.copy(raceStatus = "waiting_for_result") }
            }
            TimingRole.CONTROL_ONLY -> Unit
        }

        tryCalculateMultiGateResult()
    }

    private fun handleRemoteCrossingEvent(
        payload: TimingPayload.CrossingEvent,
        runId: String? = null,
        eventId: String? = null
    ) {
        if (!_uiState.value.isHostingSession && payload.role == TimingRole.START_LINE) {
            onRemoteStartReceived(payload.timestampNanos, runId)
        }

        val eventRunId = canonicalRunId(runId)
        val crossing = RecordedGateCrossing(
            gateId = payload.gateId,
            role = payload.role,
            gateIndex = payload.gateIndex,
            timestampNanos = payload.timestampNanos,
            thumbnail = null
        )
        val resolvedEventId = eventId
            ?: TimingMessage.generateEventId(eventRunId, payload.gateId, payload.timestampNanos)
        recordSyncableCrossingEvent(eventRunId, resolvedEventId, crossing)

        if (!runIdentityRegistry.isSameRun(eventRunId, currentRunId)) {
            Log.i(
                TAG,
                "Recorded crossing for non-active run ${eventRunId.take(8)} without applying it to ${currentRunId.take(8)}"
            )
            return
        }

        val recorded = recordGateCrossing(crossing)
        Log.i(TAG, "Remote crossing event: gate=${payload.gateIndex}, role=${payload.role}, recorded=$recorded")

        if (recorded) {
            tryCalculateMultiGateResult()
        }
    }

    private fun recordGateCrossing(crossing: RecordedGateCrossing): Boolean {
        val gateCount = _uiState.value.numberOfGates.coerceAtLeast(2)
        if (crossing.gateIndex !in 0 until gateCount) {
            Log.w(TAG, "Ignoring crossing for invalid gate ${crossing.gateIndex} (gateCount=$gateCount)")
            return false
        }
        val existing = receivedGateCrossings[crossing.gateIndex]
        if (existing != null) {
            val candidateWins = crossingCandidateWins(
                role = crossing.role,
                existingTimestampNanos = existing.timestampNanos,
                candidateTimestampNanos = crossing.timestampNanos
            )
            if (!candidateWins) {
                Log.d(TAG, "Ignoring superseded crossing for gate ${crossing.gateIndex}")
                return false
            }
            Log.i(TAG, "Replacing crossing winner for gate ${crossing.gateIndex}")
        }

        val wasEmpty = receivedGateCrossings.isEmpty()
        receivedGateCrossings[crossing.gateIndex] = crossing
        val count = receivedGateCrossings.size
        _uiState.update {
            it.copy(
                receivedGateCount = count,
                raceStatus = if (it.numberOfGates > 2) "collecting_gates" else it.raceStatus
            )
        }
        if (wasEmpty) {
            startMultiGateTimeoutIfNeeded()
        }
        return true
    }

    private fun recordSyncableCrossingEvent(
        runId: String,
        eventId: String,
        crossing: RecordedGateCrossing
    ) {
        val bucket = syncableEventLog.getOrPut(runId) { mutableListOf() }
        if (bucket.any { it.eventId == eventId }) return

        syncableEventSeq += 1
        bucket += SyncableTimingEvent(
            eventId = eventId,
            eventType = "crossingEvent",
            gateId = crossing.gateId,
            gateIndex = crossing.gateIndex,
            timestampNanos = crossing.timestampNanos,
            seq = syncableEventSeq
        )

        if (syncableEventLog.size > MAX_RUNS_IN_EVENT_LOG) {
            val oldestRunId = syncableEventLog.keys.firstOrNull { it != runId }
            if (oldestRunId != null) {
                syncableEventLog.remove(oldestRunId)
            }
        }
    }

    private fun recordSyncableStartEvent(runId: String, startTimestampNanos: Long) {
        val eventId = TimingMessage.generateEventId(runId, deviceId, startTimestampNanos)
        val bucket = syncableEventLog.getOrPut(runId) { mutableListOf() }
        if (bucket.any { it.eventId == eventId }) return

        syncableEventSeq += 1
        bucket += SyncableTimingEvent(
            eventId = eventId,
            eventType = "startEvent",
            gateId = deviceId,
            gateIndex = 0,
            timestampNanos = startTimestampNanos,
            seq = syncableEventSeq
        )

        if (syncableEventLog.size > MAX_RUNS_IN_EVENT_LOG) {
            val oldestRunId = syncableEventLog.keys.firstOrNull { it != runId }
            if (oldestRunId != null) {
                syncableEventLog.remove(oldestRunId)
            }
        }
    }

    private fun scheduleStartEventWaitTimeout() {
        cancelStartEventWaitTimeout()

        val policy = startEventWaitPolicy()
        Log.i(
            TAG,
            "Waiting for startEvent (timeout ${policy.timeoutMs / 1000}s, reason=${policy.reason})"
        )

        startEventTimeoutJob = viewModelScope.launch {
            var elapsedMs = 0L

            policy.warningAfterMs?.let { warningAt ->
                delay(warningAt)
                elapsedMs = warningAt
                if (!isWaitingForStartEvent()) {
                    startEventTimeoutJob = null
                    return@launch
                }
                Log.w(TAG, "Still waiting for startEvent after ${warningAt / 1000}s")
                _uiState.update {
                    it.copy(errorMessage = context.getString(R.string.race_error_waiting_start_phone))
                }
            }

            policy.recoveryAfterMs?.takeIf { it > elapsedMs }?.let { recoveryAt ->
                delay(recoveryAt - elapsedMs)
                elapsedMs = recoveryAt
                if (!isWaitingForStartEvent()) {
                    startEventTimeoutJob = null
                    return@launch
                }
                attemptAdaptiveStartEventRecovery(policy.reason)
            }

            val remainingMs = (policy.timeoutMs - elapsedMs).coerceAtLeast(0L)
            if (remainingMs > 0) {
                delay(remainingMs)
            }

            if (!isWaitingForStartEvent()) {
                startEventTimeoutJob = null
                return@launch
            }

            Log.w(TAG, "Timeout waiting for startEvent - discarding stale finish crossing")
            discardBufferedFinishAfterStartEventTimeout(policy.reason)
            startEventTimeoutJob = null
        }
    }

    private fun cancelStartEventWaitTimeout() {
        startEventTimeoutJob?.cancel()
        startEventTimeoutJob = null
    }

    private fun startEventWaitPolicy(): StartEventWaitPolicy {
        val bleConnected = bleClockSyncService.connectedPeerDeviceIds().isNotEmpty()
        val internetAvailable = hasValidatedInternetConnection()

        return when {
            bleConnected && internetAvailable -> StartEventWaitPolicy(
                timeoutMs = START_EVENT_WAIT_BLE_HEALTHY_MS,
                warningAfterMs = null,
                recoveryAfterMs = null,
                reason = "ble_healthy"
            )
            !bleConnected && internetAvailable -> StartEventWaitPolicy(
                timeoutMs = START_EVENT_WAIT_CLOUD_AVAILABLE_MS,
                warningAfterMs = null,
                recoveryAfterMs = null,
                reason = "ble_disconnected_cloud_available"
            )
            bleConnected -> StartEventWaitPolicy(
                timeoutMs = START_EVENT_WAIT_P2P_ONLY_MS,
                warningAfterMs = null,
                recoveryAfterMs = null,
                reason = "internet_unavailable_ble_connected"
            )
            else -> StartEventWaitPolicy(
                timeoutMs = START_EVENT_WAIT_BOTH_UNAVAILABLE_MS,
                warningAfterMs = START_EVENT_WAIT_WARNING_MS,
                recoveryAfterMs = START_EVENT_WAIT_RECOVERY_MS,
                reason = "both_unavailable"
            )
        }
    }

    private fun hasValidatedInternetConnection(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isWaitingForStartEvent(): Boolean {
        return _uiState.value.phase == RacePhase.ACTIVE_RACE &&
            localStartTimeNanos == null &&
            (bufferedFinishTimeNanos != null || localFinishTimeNanos != null)
    }

    private fun attemptAdaptiveStartEventRecovery(reason: String) {
        Log.w(TAG, "Attempting adaptive startEvent recovery ($reason)")
        val effectiveSessionId = supabaseSessionId ?: sessionId
        restartCloudSubscriptions(effectiveSessionId)
        retryPendingReconnectReplays("start_event_wait")
        bleClockSyncService.sendMessage(
            TimingPayload.EventSync(
                lastSeenEventId = null,
                runId = currentRunId
            ),
            runId = currentRunId
        )
    }

    private fun discardBufferedFinishAfterStartEventTimeout(reason: String) {
        stopTimerTick()
        bufferedFinishTimeNanos = null
        bufferedFinishThumbnail = null
        localFinishTimeNanos = null
        finishThumbnail = null
        localFinishCalibration = null
        clearResultDedupeState()

        if (_uiState.value.requiresLocalCamera) {
            gateEngine.reset()
            gateEngine.resume()
        }

        _uiState.update {
            it.copy(
                raceStatus = "waiting",
                elapsedTimeSeconds = 0.0,
                errorMessage = context.getString(R.string.race_error_start_timeout)
            )
        }
        Log.i(TAG, "Discarded buffered finish after startEvent timeout ($reason)")
    }

    private fun tryCalculateMultiGateResult(requireAllGates: Boolean = true) {
        val state = _uiState.value
        if (!state.isHostingSession || state.numberOfGates <= 2 || state.phase != RacePhase.ACTIVE_RACE) return

        val gateCount = state.numberOfGates.coerceAtLeast(2)
        if (requireAllGates && receivedGateCrossings.size < gateCount) return
        if (receivedGateCrossings.size < 2) return
        if (receivedGateCrossings[0] == null) {
            cancelInvalidMultiGateRun("missing start gate")
            return
        }
        if (receivedGateCrossings[gateCount - 1] == null) {
            cancelInvalidMultiGateRun("missing finish gate")
            return
        }

        val ordered = mutableListOf<RecordedGateCrossing>()
        val toleranceNanos = timestampOrderingToleranceNanos()
        var previousTimestamp: Long? = null

        for ((gateIndex, crossing) in receivedGateCrossings.toSortedMap()) {
            val previous = previousTimestamp
            val effectiveTimestamp = if (previous != null && crossing.timestampNanos < previous) {
                val earlyBy = previous - crossing.timestampNanos
                if (earlyBy > toleranceNanos) {
                    Log.w(TAG, "Multi-gate crossing out of order at gate $gateIndex by ${earlyBy}ns")
                    cancelInvalidMultiGateRun("gate $gateIndex crossed out of order")
                    return
                }
                previous
            } else {
                crossing.timestampNanos
            }

            ordered.add(crossing.copy(timestampNanos = effectiveTimestamp))
            previousTimestamp = effectiveTimestamp
        }

        val start = ordered.first()
        val finish = ordered.last()
        val totalSplitNanos = finish.timestampNanos - start.timestampNanos
        if (totalSplitNanos < 1_000_000) {
            Log.w(TAG, "Multi-gate split too short (${totalSplitNanos}ns), ignoring")
            return
        }

        cancelMultiGateTimeout()
        val segments = buildSegmentSplits(ordered, state.gateDistances, gateCount)
        val splitSeconds = totalSplitNanos / 1_000_000_000.0
        val uncertaintyMs = calculateTimingUncertaintyMs(eventCount = gateCount)
        val resultRunId = currentRunId
        val localGateCalibrationForSave = ordered.firstOrNull { it.gateId == deviceId }?.calibration
            ?: finish.calibration

        stopTimerTick()
        finishThumbnail = finish.thumbnail ?: finishThumbnail

        _uiState.update {
            it.copy(
                phase = RacePhase.RESULT,
                resultTimeSeconds = splitSeconds,
                resultUncertaintyMs = uncertaintyMs,
                resultSegments = segments,
                elapsedTimeSeconds = splitSeconds,
                raceStatus = "finished"
            )
        }
        endLiveTimingWorkloadIfNeeded()
        scheduleAutoResetForNewRun()

        bleClockSyncService.sendCriticalMessage(
            TimingPayload.MultiGateResult(
                totalSplitNanos = totalSplitNanos,
                segments = segments,
                uncertaintyMs = uncertaintyMs
            ),
            runId = resultRunId
        )

        viewModelScope.launch {
            try {
                val athlete = selectedRaceAthleteForSave()
                sessionRepository.saveRaceResult(
                    sessionId = sessionId,
                    runNumber = currentRunNumber,
                    runId = resultRunId,
                    distance = state.distanceMeters,
                    startType = state.startType,
                    numberOfGates = gateCount,
                    gateDistances = state.gateDistances,
                    timeSeconds = splitSeconds,
                    thumbnail = finishThumbnail,
                    gatePosition = state.gatePosition,
                    athleteId = athlete?.id,
                    athleteName = athlete?.name,
                    athleteColor = athlete?.color,
                    crossingTimestampNanos = finish.timestampNanos,
                    segments = segments,
                    localGateRole = localGateCalibrationForSave?.role,
                    crossingVelocityPxPerSec = localGateCalibrationForSave?.velocityPxPerSec,
                    crossingDirection = localGateCalibrationForSave?.crossingDirection,
                    workWidth = localGateCalibrationForSave?.workWidth,
                    thumbnailDebugJson = localGateCalibrationForSave?.thumbnailDebug?.toString(),
                    localGateFrames = localGateFramesForSave(resultRunId)
                )
                applyPendingRemoteRunUpdates(resultRunId)
                Log.i(TAG, "Saved multi-gate race result to local DB")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save multi-gate race result", e)
            }
        }
    }

    private fun startMultiGateTimeoutIfNeeded() {
        val state = _uiState.value
        if (!state.isHostingSession || state.numberOfGates <= 2 || state.phase != RacePhase.ACTIVE_RACE) return
        if (multiGateTimeoutJob?.isActive == true) return

        val timeoutMs = multiGateTimeoutMillis(state.distanceMeters)
        multiGateTimeoutJob = viewModelScope.launch {
            delay(timeoutMs)
            handleMultiGateTimeout()
        }
        Log.i(TAG, "Multi-gate timeout started: ${timeoutMs / 1000}s for ${state.numberOfGates} gates")
    }

    private fun cancelMultiGateTimeout() {
        multiGateTimeoutJob?.cancel()
        multiGateTimeoutJob = null
    }

    private fun handleMultiGateTimeout() {
        val state = _uiState.value
        if (!state.isHostingSession || state.numberOfGates <= 2 || state.phase != RacePhase.ACTIVE_RACE) return

        val crossingCount = receivedGateCrossings.size
        val finishGateIndex = state.numberOfGates.coerceAtLeast(2) - 1
        val hasStart = receivedGateCrossings[0] != null
        val hasFinish = receivedGateCrossings[finishGateIndex] != null

        Log.w(TAG, "Multi-gate timeout: $crossingCount/${state.numberOfGates} crossings, start=$hasStart, finish=$hasFinish")

        if (crossingCount >= 2 && hasStart && hasFinish) {
            tryCalculateMultiGateResult(requireAllGates = false)
        } else {
            cancelInvalidMultiGateRun("missing start or finish gate")
        }
    }

    private fun cancelInvalidMultiGateRun(reason: String) {
        Log.w(TAG, "Canceling multi-gate run: $reason")
        autoResetJob?.cancel()
        autoResetJob = null
        cancelMultiGateTimeout()
        stopTimerTick()
        receivedGateCrossings.clear()
        localGateFrameBuffer.reset()
        clearResultDedupeState()
        remoteThumbnailMetadataByEventId.clear()
        localStartTimeNanos = null
        localFinishTimeNanos = null
        remoteStartTimeNanos = null
        bufferedFinishTimeNanos = null
        bufferedFinishThumbnail = null
        finishThumbnail = null
        localStartCalibration = null
        localFinishCalibration = null
        cancelStartEventWaitTimeout()
        gateEngine.reset()

        bleClockSyncService.sendCriticalMessage(
            TimingPayload.CancelRun(),
            runId = currentRunId
        )

        _uiState.update {
            it.copy(
                phase = RacePhase.RACE_READY,
                raceStatus = "",
                elapsedTimeSeconds = 0.0,
                resultTimeSeconds = null,
                resultUncertaintyMs = null,
                resultSegments = emptyList(),
                receivedGateCount = 0,
                errorMessage = context.getString(R.string.race_error_run_canceled)
            )
        }
    }

    private fun multiGateTimeoutMillis(distanceMeters: Double): Long {
        val timeoutSeconds = if (distanceMeters > 0.0) {
            val expectedDurationSeconds = distanceMeters / 10.0
            (3.0 * expectedDurationSeconds + 5.0).coerceIn(15.0, 60.0)
        } else {
            30.0
        }
        return (timeoutSeconds * 1000.0).toLong()
    }

    /**
     * Handle an incoming message from the remote device via BLE.
     * Includes deduplication via eventId to prevent processing the same
     * timing event twice when it arrives via both BLE and Supabase cloud relay.
     */
    private fun handleRemoteMessage(message: com.trackspeed.android.protocol.TimingMessage) {
        val payload = message.payload
        if (!isMessageTargetedToLocalDevice(message)) {
            Log.d(TAG, "Ignoring targeted race message for ${targetDescription(message)}")
            return
        }
        if (shouldDropStaleSessionEnvelope(message)) {
            return
        }
        if (shouldDropDuplicateEnvelope(message)) {
            return
        }

        rememberPeerDevice(message.senderId)
        schedulePendingReconnectReplayIfNeeded(message.senderId, "peer message ${payload::class.simpleName}")

        // Deduplicate: skip if we've already processed this eventId
        val eventId = message.eventId
        if (eventId != null && shouldDeduplicateRaceEventById(payload)) {
            if (!processedEventIds.add(eventId)) {
                Log.d(TAG, "Duplicate message (eventId=${eventId.take(12)}), skipping")
                return
            }
            // Cap the dedup set to prevent unbounded growth
            if (processedEventIds.size > 200) {
                val toRemove = processedEventIds.take(100)
                processedEventIds.removeAll(toRemove.toSet())
            }
        }

        when (payload) {
            is TimingPayload.StartTiming -> {
                handleStartTiming()
            }
            is TimingPayload.CalibrateRequest,
            is TimingPayload.CalibrateAll -> {
                beginLocalGateCalibration()
            }
            is TimingPayload.CalibrationStatus -> {
                handleCalibrationStatus(payload)
            }
            is TimingPayload.ArmAll -> {
                maybeSendLocalArmedAck()
            }
            is TimingPayload.ArmedAck -> {
                if (_uiState.value.isHostingSession && payload.gateId != deviceId) {
                    relayCriticalFromHost(
                        payload = payload,
                        excludingDeviceId = payload.gateId,
                        runId = message.runId
                    )
                }
                handleArmedAck(payload)
            }
            is TimingPayload.DisarmAll -> {
                handleDisarmAll()
            }
            is TimingPayload.GateStatus -> {
                handleGateStatus(payload)
            }
            is TimingPayload.StartEvent -> {
                if (_uiState.value.isHostingSession && _uiState.value.numberOfGates > 2) {
                    relayCriticalFromHost(
                        payload = payload,
                        excludingDeviceId = message.senderId,
                        eventId = message.eventId,
                        runId = message.runId
                    )
                }
                onRemoteStartReceived(payload.monotonicNanos, message.runId)
            }
            is TimingPayload.CrossingEvent -> {
                if (shouldRelayRemoteCrossing(payload)) {
                    relayCriticalFromHost(
                        payload = payload,
                        excludingDeviceId = payload.gateId,
                        eventId = message.eventId,
                        runId = message.runId
                    )
                }
                handleRemoteCrossingEvent(payload, message.runId, message.eventId)
            }
            is TimingPayload.MultiGateResult -> {
                handleRemoteMultiGateResult(payload)
            }
            is TimingPayload.FinishResult -> {
                handleRemoteFinishResult(payload, message.runId)
            }
            is TimingPayload.TimingResultBroadcast -> {
                handleRemoteTimingResult(payload, message.runId)
            }
            is TimingPayload.StartRun -> {
                val announcedRunId = message.runId ?: UUID.randomUUID().toString()
                Log.i(TAG, "Remote started run: ${announcedRunId.take(8)}")
                beginActiveSession(
                    runId = announcedRunId,
                    announceToPeers = false
                )
            }
            is TimingPayload.NewRun -> {
                val announcedRunId = message.runId ?: UUID.randomUUID().toString()
                Log.i(TAG, "Remote requested new run: ${announcedRunId.take(8)}")
                beginActiveSession(
                    runId = announcedRunId,
                    announceToPeers = false
                )
                currentRunNumber += 1
            }
            is TimingPayload.CancelRun -> {
                Log.i(TAG, "Remote cancelled run")
                cancelCurrentRun(broadcast = false)
            }
            is TimingPayload.SessionEnded -> {
                if (_uiState.value.isHostingSession && _uiState.value.numberOfGates > 2) {
                    relayCriticalFromHost(
                        payload = payload,
                        excludingDeviceId = message.senderId,
                        eventId = message.eventId,
                        runId = message.runId
                    )
                }
                handleSessionEnded(payload.reason)
            }
            is TimingPayload.DistanceConfigChanged -> {
                handleDistanceConfigChanged(payload.gateDistances)
            }
            is TimingPayload.StartTypeChanged -> {
                handleStartTypeChanged(payload.startType)
            }
            is TimingPayload.SyncRequest -> {
                handlePeerSyncRequest()
            }
            is TimingPayload.PauseDetection -> {
                handleRemotePauseDetection(
                    eventId = message.eventId,
                    senderId = message.senderId
                )
            }
            is TimingPayload.ResumeDetection -> {
                handleRemoteResumeDetection(
                    eventId = message.eventId,
                    senderId = message.senderId
                )
            }
            is TimingPayload.AdjustGateLine -> {
                handleAdjustGateLine(payload.gateId, payload.position)
            }
            is TimingPayload.ThumbnailUpdate -> {
                if (_uiState.value.isHostingSession && payload.gateId != deviceId) {
                    relayCriticalFromHost(
                        payload = payload,
                        excludingDeviceId = payload.gateId,
                        eventId = message.eventId ?: payload.eventId,
                        runId = message.runId
                    )
                }
                handleThumbnailUpdate(payload, message.runId)
            }
            is TimingPayload.ThumbnailMetadata -> {
                if (_uiState.value.isHostingSession && payload.gateId != deviceId) {
                    relayCriticalFromHost(
                        payload = payload,
                        excludingDeviceId = payload.gateId,
                        eventId = message.eventId ?: payload.eventId,
                        runId = message.runId
                    )
                }
                handleThumbnailMetadata(payload, message.runId)
            }
            is TimingPayload.EventSync -> {
                handleEventSyncRequest(payload, message.senderId)
            }
            is TimingPayload.EventSyncResponse -> {
                handleEventSyncResponse(payload, message.runId)
            }
            is TimingPayload.CalibrationUpdate -> {
                handleCalibrationUpdate(payload, message.runId)
            }
            is TimingPayload.AdjustmentUpdate -> {
                handleAdjustmentUpdate(payload)
            }
            is TimingPayload.Abort -> {
                Log.i(TAG, "Remote aborted: ${payload.reason}")
                _uiState.update {
                    it.copy(errorMessage = context.getString(R.string.race_error_remote))
                }
            }
            else -> {
                Log.d(TAG, "Received remote message: ${payload::class.simpleName}")
            }
        }
    }

    private fun shouldRelayRemoteCrossing(payload: TimingPayload.CrossingEvent): Boolean {
        val state = _uiState.value
        if (!state.isHostingSession || payload.gateId == deviceId) return false
        if (state.numberOfGates <= 2) return false
        return receivedGateCrossings[payload.gateIndex] == null
    }

    private fun relayCriticalFromHost(
        payload: TimingPayload,
        excludingDeviceId: String?,
        eventId: String? = null,
        runId: String? = null
    ) {
        if (!_uiState.value.isHostingSession) return

        var relayedCount = 0
        buildSet {
            addAll(knownPeerDeviceIds)
            addAll(bleClockSyncService.connectedPeerDeviceIds())
        }
            .asSequence()
            .filter { peerDeviceId ->
                peerDeviceId != deviceId && peerDeviceId != excludingDeviceId
            }
            .forEach { peerDeviceId ->
                val sent = bleClockSyncService.sendCriticalMessage(
                    payload = payload,
                    eventId = eventId,
                    targetDeviceId = peerDeviceId,
                    runId = runId
                )
                if (sent) relayedCount += 1
            }

        if (relayedCount > 0) {
            Log.i(
                TAG,
                "Relayed ${payload::class.simpleName} to $relayedCount peer(s), " +
                    "excluding=${excludingDeviceId?.take(8) ?: "none"}"
            )
        }
    }

    private fun handleStartTiming() {
        _uiState.update { current ->
            val syncReady = current.syncProgress >= 1f || current.syncQuality != null
            current.copy(
                phase = if (syncReady && current.phase != RacePhase.ACTIVE_RACE && current.phase != RacePhase.RESULT) {
                    RacePhase.RACE_READY
                } else {
                    current.phase
                },
                pairingStatus = context.getString(R.string.race_pairing_status_starting)
            )
        }
        val state = _uiState.value
        if (state.localGateStatus.isReady) {
            publishLocalGateStatus(state.localGateStatus)
            maybeSendLocalArmedAck(state.localGateStatus)
            tryCompleteGateReadinessRecovery("startTiming")
        }
        Log.i(TAG, "Received startTiming")
    }

    private fun handleGateStatus(payload: TimingPayload.GateStatus) {
        if (payload.gateId == deviceId) {
            _uiState.update { it.copy(localGateStatus = payload.status) }
        } else {
            _uiState.update { current ->
                current.copy(
                    remoteGateStatuses = current.remoteGateStatuses + (payload.gateId to payload.status),
                    remoteArmedGateIds = if (payload.status.isReady) {
                        current.remoteArmedGateIds + payload.gateId
                    } else {
                        current.remoteArmedGateIds - payload.gateId
                    }
                )
            }
        }
        Log.i(
            TAG,
            "Gate status ${payload.gateId.take(8)} calibrated=${payload.status.isCalibrated} armed=${payload.status.isArmed}"
        )
        tryCompleteGateReadinessRecovery("gate status")
    }

    private fun handleArmedAck(payload: TimingPayload.ArmedAck) {
        if (payload.gateId == deviceId) {
            _uiState.update {
                it.copy(localGateStatus = it.localGateStatus.copy(isArmed = true))
            }
            tryCompleteGateReadinessRecovery("local armed ACK")
            return
        }

        _uiState.update { current ->
            val existingStatus = current.remoteGateStatuses[payload.gateId]
                ?: defaultGateStatus(current.gatePosition.toDouble())
            val armedStatus = existingStatus.copy(
                isCalibrated = true,
                isArmed = true,
                isClear = true,
                isPrebufferReady = true,
                isStable = true
            )
            current.copy(
                remoteGateStatuses = current.remoteGateStatuses + (payload.gateId to armedStatus),
                remoteArmedGateIds = current.remoteArmedGateIds + payload.gateId
            )
        }
        Log.i(TAG, "Received armedAck from ${payload.role.value} ${payload.gateId.take(8)}")
        if (_uiState.value.localGateStatus.isReady) {
            sendLocalArmedAckToPeer(
                peerId = payload.gateId,
                peerAddress = bleClockSyncService.getDeviceAddress(payload.gateId) ?: ""
            )
        }
        tryCompleteGateReadinessRecovery("remote armed ACK")
    }

    private fun handleCalibrationStatus(payload: TimingPayload.CalibrationStatus) {
        val status = defaultGateStatus(_uiState.value.gatePosition.toDouble()).copy(
            isCalibrated = payload.success,
            isArmed = payload.success,
            isClear = payload.success,
            isPrebufferReady = payload.success,
            isStable = payload.success
        )
        if (payload.gateId == deviceId) {
            _uiState.update { it.copy(localGateStatus = status) }
        } else {
            _uiState.update { current ->
                current.copy(
                    remoteGateStatuses = current.remoteGateStatuses + (payload.gateId to status),
                    remoteArmedGateIds = if (payload.success) {
                        current.remoteArmedGateIds + payload.gateId
                    } else {
                        current.remoteArmedGateIds - payload.gateId
                    }
                )
            }
        }
        payload.error?.let { Log.w(TAG, "Calibration status error from ${payload.gateId.take(8)}: $it") }
        tryCompleteGateReadinessRecovery("calibration status")
    }

    private fun handleDisarmAll() {
        gateCalibrationJob?.cancel()
        gateCalibrationJob = null
        gateEngine.pause()
        val disarmedStatus = _uiState.value.localGateStatus.copy(isArmed = false)
        _uiState.update {
            it.copy(
                isLocalGateCalibrating = false,
                localGateStatus = disarmedStatus,
                raceStatus = if (it.phase == RacePhase.ACTIVE_RACE) "paused" else it.raceStatus
            )
        }
        publishLocalGateStatus(disarmedStatus)
        Log.i(TAG, "Received disarmAll")
    }

    private fun handleSessionEnded(reason: String) {
        val state = _uiState.value
        if (state.sessionEndPresentation is TimingSessionEndPresentation.Saving ||
            state.sessionEndPresentation is TimingSessionEndPresentation.Completed
        ) {
            Log.d(TAG, "Ignoring duplicate peer session end while finish flow is active")
            return
        }

        val confirmation = (state.sessionEndPresentation as? TimingSessionEndPresentation.Confirmation)
            ?.value
            ?: buildSessionEndConfirmation(state)
        val endingSessionId = sessionId
        val origin = when (reason) {
            "hostLeft" -> TimingSessionEndOrigin.HOST
            "partnerLeft" -> TimingSessionEndOrigin.PARTNER
            else -> if (state.isHostingSession) {
                TimingSessionEndOrigin.PARTNER
            } else {
                TimingSessionEndOrigin.HOST
            }
        }

        autoResetJob?.cancel()
        autoResetJob = null
        pauseDetection(broadcast = false)
        _uiState.update {
            it.copy(sessionEndPresentation = TimingSessionEndPresentation.Saving(isSharedSession = true))
        }

        viewModelScope.launch {
            val summary = awaitSessionEndSummary(
                endingSessionId = endingSessionId,
                expectedRunCount = confirmation.runCount,
                origin = origin
            )
            Log.i(TAG, "Peer ended session: $reason; secured ${summary.runCount} run(s)")
            resetToStart()
            _uiState.update {
                it.copy(sessionEndPresentation = TimingSessionEndPresentation.Completed(summary))
            }
        }
    }

    private fun handleDistanceConfigChanged(gateDistances: Map<Int, Double>) {
        if (_uiState.value.isHostingSession) {
            Log.d(TAG, "Host ignoring own distanceConfigChanged message")
            return
        }

        applyGateDistances(gateDistances, broadcast = false)
        Log.i(TAG, "Gate distance config updated from host: ${gateDistances.toSortedMap()}")
    }

    private fun handleStartTypeChanged(startTypeRaw: String) {
        if (_uiState.value.isHostingSession) {
            Log.d(TAG, "Host ignoring own startTypeChanged message")
            return
        }

        val startType = StartType.fromRawValue(startTypeRaw)
        _uiState.update { it.copy(startType = startType.rawValue) }
        ensureDesiredCameraFacing()
        Log.i(TAG, "Start type updated from host: ${startType.displayName}")
    }

    fun toggleDetectionPause() {
        if (_uiState.value.raceStatus == "paused") {
            resumeDetection(broadcast = true)
        } else {
            pauseDetection(broadcast = true)
        }
    }

    fun onAppBackgrounded() {
        val state = _uiState.value
        when {
            state.phase == RacePhase.ACTIVE_RACE && state.raceStatus != "paused" -> {
                appBackgroundedDuringStartedRun = isRunTimingInProgress()
                appBackgroundPausedDetection = true
                markKnownPeersPendingReconnectAfterBackground()
                pauseDetection(broadcast = true)
                Log.i(TAG, "App backgrounded during active race; detection paused")
            }
            finishConfirmationPauseCoordinator.blocksNormalResume -> {
                appBackgroundedDuringStartedRun =
                    state.phase == RacePhase.ACTIVE_RACE && isRunTimingInProgress()
                appBackgroundPausedDetection = true
                markKnownPeersPendingReconnectAfterBackground()
                enforceAutomaticDetectionPause()
                Log.i(
                    TAG,
                    "App backgrounded during finish confirmation; lifecycle now owns the pause"
                )
            }
        }

        saveActiveSessionSnapshot()
    }

    fun onAppForegrounded() {
        viewModelScope.launch {
            processUploadQueuesIfIdle()
        }

        val effectiveSessionId = supabaseSessionId ?: sessionId
        if (_uiState.value.phase == RacePhase.ACTIVE_RACE) {
            restartCloudSubscriptions(effectiveSessionId)
        }

        if (appBackgroundedDuringStartedRun && _uiState.value.phase == RacePhase.ACTIVE_RACE) {
            appBackgroundedDuringStartedRun = false
            cancelCurrentRun(broadcast = true)
            Log.w(TAG, "App foregrounded after active timing run; canceled stale run before re-arming")
        }

        if (appBackgroundPausedDetection && _uiState.value.phase == RacePhase.ACTIVE_RACE) {
            appBackgroundPausedDetection = false
            recoverClockSyncAfterForeground()
        } else {
            appBackgroundedDuringStartedRun = false
            appBackgroundPausedDetection = false
        }
    }

    private fun isRunTimingInProgress(): Boolean {
        return localStartTimeNanos != null ||
            remoteStartTimeNanos != null ||
            localFinishTimeNanos != null ||
            bufferedFinishTimeNanos != null ||
            receivedGateCrossings.isNotEmpty() ||
            _uiState.value.raceStatus == "started"
    }

    private fun recoverClockSyncAfterForeground() {
        invalidateGateReadinessForForegroundRecovery()
        retryPendingReconnectReplays("foreground recovery")

        if (clockSyncManager.restoreFrozenSyncIfNeeded("foreground recovery")) {
            Log.w(TAG, "Foreground recovery kept frozen sync; gate must be re-armed before timing resumes")
            return
        }

        if (_uiState.value.isHostingSession) {
            clockSyncManager.invalidateAndResync(
                reason = "foreground recovery",
                announceRequest = false
            )
            val sent = bleClockSyncService.sendCriticalMessage(TimingPayload.SyncRequest())
            Log.w(TAG, "Foreground recovery requested peer clock re-sync from reference phone: sent=$sent")
        } else {
            _uiState.update {
                it.copy(
                    phase = RacePhase.SYNCING,
                    syncProgress = 0f
                )
            }
            clockSyncManager.invalidateAndResync(reason = "foreground recovery")
            Log.w(TAG, "Foreground recovery started full clock re-sync; detection remains paused")
        }
    }

    private fun invalidateGateReadinessForForegroundRecovery() {
        gateCalibrationJob?.cancel()
        gateCalibrationJob = null
        gateEngine.pause()
        val disarmedStatus = _uiState.value.localGateStatus.copy(
            isArmed = false,
            isPrebufferReady = false,
            isStable = false
        )
        _uiState.update {
            it.copy(
                isLocalGateCalibrating = false,
                localGateStatus = disarmedStatus,
                remoteGateStatuses = it.remoteGateStatuses.mapValues { (_, status) ->
                    status.copy(isArmed = false)
                },
                remoteArmedGateIds = emptySet(),
                raceStatus = if (it.phase == RacePhase.ACTIVE_RACE) "paused" else it.raceStatus,
                errorMessage = context.getString(R.string.race_error_rearm_after_background)
            )
        }
        foregroundGateReadinessRecoveryInProgress = true
        detectionPausedForForegroundRecovery = true
        gateReadinessRecoveryAttempts = 0
        armGateReadinessRecoveryWatchdog()
        publishLocalGateStatus(disarmedStatus)
    }

    private fun handlePeerSyncRequest() {
        if (clockSyncManager.isServer.value) {
            Log.i(TAG, "Peer requested re-sync; reference phone will answer pings")
            return
        }

        if (_uiState.value.phase == RacePhase.ACTIVE_RACE) {
            invalidateGateReadinessForForegroundRecovery()
            clockSyncManager.restoreFrozenSyncIfNeeded("peer syncRequest")
            Log.i(TAG, "Peer requested re-sync during active race; kept frozen active-session sync")
            return
        }

        _uiState.update {
            it.copy(
                phase = RacePhase.SYNCING,
                syncProgress = 0f
            )
        }
        Log.i(TAG, "Peer requested re-sync during setup; waiting for full sync")
    }

    private fun rememberPeerDevice(senderId: String) {
        if (senderId != deviceId) {
            knownPeerDeviceIds.add(senderId)
        }
    }

    private fun markKnownPeersPendingReconnectAfterBackground() {
        val state = _uiState.value
        val knownPeers = buildSet {
            addAll(knownPeerDeviceIds)
            addAll(state.remoteGateStatuses.keys)
            addAll(state.remoteArmedGateIds)
            addAll(bleClockSyncService.connectedPeerDeviceIds())
        }.filter { it != deviceId }

        pendingPeerReconnects.addAll(knownPeers)
        if (knownPeers.isNotEmpty()) {
            Log.i(TAG, "Marked ${knownPeers.size} peer(s) for foreground reconnect replay")
        }
    }

    private fun handleBleConnectionEvent(event: BleClockSyncService.ConnectionEvent) {
        val matchingPeers = knownPeerDeviceIds.filter { peerId ->
            bleClockSyncService.getDeviceAddress(peerId) == event.device.address
        }

        if (event.connected) {
            matchingPeers.forEach { peerId ->
                schedulePendingReconnectReplayIfNeeded(peerId, "BLE reconnect")
            }
            return
        }

        if (_uiState.value.phase != RacePhase.PAIRING) {
            pendingPeerReconnects.addAll(matchingPeers)
            if (matchingPeers.isNotEmpty()) {
                Log.i(TAG, "Marked ${matchingPeers.size} peer(s) pending after BLE disconnect")
            }
        }
    }

    private fun retryPendingReconnectReplays(reason: String) {
        pendingPeerReconnects.toList().forEach { peerId ->
            schedulePendingReconnectReplayIfNeeded(peerId, reason)
        }
    }

    private fun schedulePendingReconnectReplayIfNeeded(peerId: String, reason: String) {
        if (peerId == deviceId || peerId !in pendingPeerReconnects) return
        if (reconnectReplayJobs[peerId]?.isActive == true) return

        reconnectReplayJobs[peerId] = viewModelScope.launch {
            delay(300)
            val replayed = replayStateToReconnectedPeer(peerId, reason)
            if (replayed) {
                pendingPeerReconnects.remove(peerId)
                Log.i(TAG, "Replayed reconnect state to ${peerId.take(8)} after $reason")
            }
            reconnectReplayJobs.remove(peerId)
        }
    }

    private fun replayStateToReconnectedPeer(peerId: String, reason: String): Boolean {
        return if (_uiState.value.isHostingSession) {
            replayHostStateToPeer(peerId, reason)
        } else {
            rebroadcastLocalStateForReconnect(peerId, reason)
        }
    }

    private fun replayHostStateToPeer(peerId: String, reason: String): Boolean {
        val peerAddress = bleClockSyncService.getDeviceAddress(peerId) ?: run {
            Log.d(TAG, "Reconnect replay waiting for BLE address for ${peerId.take(8)}")
            return false
        }

        val sentSession = clockSyncManager.resendSessionStateToPeer(
            senderId = peerId,
            supabaseSessionId = supabaseSessionId ?: sessionId
        )
        if (!sentSession) return false

        bleClockSyncService.sendCriticalMessageToDevice(
            TimingPayload.StartTiming(),
            peerAddress,
            targetDeviceId = peerId
        )

        if (_uiState.value.raceStatus == "paused") {
            bleClockSyncService.sendCriticalMessageToDevice(
                TimingPayload.PauseDetection(),
                peerAddress,
                targetDeviceId = peerId,
                runId = currentRunId
            )
        }

        val startTime = localStartTimeNanos
        if (_uiState.value.phase == RacePhase.ACTIVE_RACE && startTime != null) {
            bleClockSyncService.sendCriticalMessageToDevice(
                TimingPayload.StartEvent(monotonicNanos = startTime, thumbnailData = null),
                peerAddress,
                targetDeviceId = peerId,
                runId = currentRunId
            )
        }

        sendLocalArmedAckToPeer(peerId, peerAddress)
        lastBroadcastCalibrationUpdate?.let { calibration ->
            bleClockSyncService.sendCriticalMessageToDevice(
                calibration,
                peerAddress,
                targetDeviceId = peerId,
                runId = currentRunId
            )
        }

        Log.i(TAG, "Host replayed session state to ${peerId.take(8)} after $reason")
        return true
    }

    private fun rebroadcastLocalStateForReconnect(peerId: String, reason: String): Boolean {
        val sentAck = sendLocalArmedAckBroadcast()
        val sentCalibration = lastBroadcastCalibrationUpdate?.let { calibration ->
            bleClockSyncService.sendCriticalMessage(
                calibration,
                runId = currentRunId
            )
        } ?: false

        Log.i(
            TAG,
            "Non-host replayed local state after $reason for ${peerId.take(8)} " +
                "(armedAck=$sentAck, calibration=$sentCalibration)"
        )
        return sentAck || sentCalibration || _uiState.value.localGateStatus.isReady
    }

    private fun sendLocalArmedAckBroadcast(): Boolean {
        val status = _uiState.value.localGateStatus
        if (!status.isReady) return false
        val role = localTimingRole() ?: return false
        if (role == TimingRole.CONTROL_ONLY) return false
        return bleClockSyncService.sendCriticalMessage(
            TimingPayload.ArmedAck(
                gateId = deviceId,
                role = role
            ),
            runId = currentRunId
        )
    }

    private fun sendLocalArmedAckToPeer(peerId: String, peerAddress: String): Boolean {
        if (peerAddress.isBlank()) return false
        val status = _uiState.value.localGateStatus
        if (!status.isReady) return false
        val role = localTimingRole() ?: return false
        if (role == TimingRole.CONTROL_ONLY) return false
        return bleClockSyncService.sendCriticalMessageToDevice(
            TimingPayload.ArmedAck(
                gateId = deviceId,
                role = role
            ),
            peerAddress,
            targetDeviceId = peerId,
            runId = currentRunId
        )
    }

    private fun localTimingRole(): TimingRole? {
        return _uiState.value.gateAssignment?.role
            ?: _uiState.value.role?.toTimingRole()
    }

    private fun expectedRemoteArmedGateIds(state: RaceModeUiState = _uiState.value): Set<String> {
        val role = localTimingRole()
        if (role == null || role == TimingRole.CONTROL_ONLY || state.numberOfGates <= 1) {
            return emptySet()
        }

        val statusPeers = state.remoteGateStatuses.keys.filter { it != deviceId }.toSet()
        if (statusPeers.isNotEmpty()) return statusPeers

        val connectedPeers = bleClockSyncService.connectedPeerDeviceIds()
            .filter { it != deviceId }
            .toSet()
        val requiredRemoteGateCount = (state.numberOfGates - 1).coerceAtLeast(1)
        if (connectedPeers.size >= requiredRemoteGateCount) return connectedPeers

        val knownPeers = knownPeerDeviceIds.filter { it != deviceId }.toSet()
        if (knownPeers.size >= requiredRemoteGateCount) return knownPeers

        return emptySet()
    }

    private fun allGateReadinessBarrierSatisfied(state: RaceModeUiState = _uiState.value): Boolean {
        val role = localTimingRole()
        if (role == null || role == TimingRole.CONTROL_ONLY || state.numberOfGates <= 1) {
            return true
        }
        if (!state.localGateStatus.isReady) return false

        val requiredGateIds = expectedRemoteArmedGateIds(state)
        val requiredRemoteGateCount = (state.numberOfGates - 1).coerceAtLeast(1)
        return if (requiredGateIds.isEmpty()) {
            state.remoteArmedGateIds.size >= requiredRemoteGateCount
        } else {
            state.remoteArmedGateIds.containsAll(requiredGateIds)
        }
    }

    private fun tryCompleteGateReadinessRecovery(reason: String): Boolean {
        if (!foregroundGateReadinessRecoveryInProgress) return false
        val state = _uiState.value
        if (!allGateReadinessBarrierSatisfied(state)) {
            val missing = missingRemoteArmedGateDescription(state)
            Log.i(TAG, "Gate recovery still waiting after $reason: $missing")
            return false
        }

        val shouldResumeAfterRecovery = detectionPausedForForegroundRecovery && state.raceStatus == "paused"
        clearForegroundGateReadinessRecovery()
        _uiState.update {
            it.copy(errorMessage = null)
        }
        if (shouldResumeAfterRecovery) {
            resumeDetection(broadcast = true)
            Log.i(TAG, "Resumed detection after gate-readiness recovery ($reason)")
        }
        return true
    }

    private fun clearForegroundGateReadinessRecovery() {
        foregroundGateReadinessRecoveryInProgress = false
        detectionPausedForForegroundRecovery = false
        gateReadinessRecoveryAttempts = 0
        gateReadinessRecoveryJob?.cancel()
        gateReadinessRecoveryJob = null
    }

    private fun armGateReadinessRecoveryWatchdog() {
        gateReadinessRecoveryJob?.cancel()
        gateReadinessRecoveryJob = viewModelScope.launch {
            delay(GATE_READINESS_RECOVERY_TIMEOUT_MS)
            handleGateReadinessRecoveryTimeout()
        }
    }

    private fun handleGateReadinessRecoveryTimeout() {
        if (!foregroundGateReadinessRecoveryInProgress) return
        if (tryCompleteGateReadinessRecovery("watchdog")) return

        gateReadinessRecoveryAttempts += 1
        val state = _uiState.value
        val missing = missingRemoteArmedGateDescription(state)
        Log.w(
            TAG,
            "Gate-readiness recovery still blocked after ${GATE_READINESS_RECOVERY_TIMEOUT_MS / 1000}s " +
                "(attempt $gateReadinessRecoveryAttempts/$MAX_GATE_READINESS_RECOVERY_ATTEMPTS); $missing"
        )

        if (state.localGateStatus.isReady) {
            sendLocalArmedAckBroadcast()
            publishLocalGateStatus(state.localGateStatus)
        }
        retryPendingReconnectReplays("gate-readiness watchdog")

        if (gateReadinessRecoveryAttempts < MAX_GATE_READINESS_RECOVERY_ATTEMPTS) {
            armGateReadinessRecoveryWatchdog()
        } else {
            gateReadinessRecoveryJob = null
            _uiState.update {
                it.copy(errorMessage = context.getString(R.string.race_error_gate_recovery_timeout))
            }
            Log.e(TAG, "Gate-readiness recovery timed out after $MAX_GATE_READINESS_RECOVERY_ATTEMPTS attempts")
        }
    }

    private fun missingRemoteArmedGateDescription(state: RaceModeUiState = _uiState.value): String {
        if (!state.localGateStatus.isReady) {
            return "waiting for local gate re-arm"
        }
        val expected = expectedRemoteArmedGateIds(state)
        val missing = if (expected.isEmpty()) {
            emptySet()
        } else {
            expected - state.remoteArmedGateIds
        }
        if (missing.isNotEmpty()) {
            return "awaiting armedAck from ${missing.joinToString { it.take(8) }}"
        }
        val requiredCount = (state.numberOfGates - 1).coerceAtLeast(1)
        val remaining = requiredCount - state.remoteArmedGateIds.size
        return "awaiting armedAck from ${remaining.coerceAtLeast(0)} gate(s)"
    }

    private fun pauseDetection(
        broadcast: Boolean,
        eventId: String? = null
    ) {
        val now = SystemClock.elapsedRealtimeNanos()
        lastPauseResumeTimestampNanos = now
        if (pausedSinceNanos == 0L) {
            pausedSinceNanos = now
        }
        stopTimerTick()
        gateEngine.pause()
        _uiState.update {
            it.copy(
                raceStatus = if (it.phase == RacePhase.ACTIVE_RACE) "paused" else it.raceStatus
            )
        }
        if (broadcast) {
            bleClockSyncService.sendCriticalMessage(
                TimingPayload.PauseDetection(),
                eventId = eventId,
                runId = currentRunId
            )
        }
        Log.i(
            TAG,
            "Detection paused, broadcast=$broadcast, finishConfirmation=${eventId?.let {
                FinishConfirmationPauseCoordinator.isFinishConfirmationEvent(it)
            } == true}"
        )
    }

    private fun resumeDetection(
        broadcast: Boolean,
        eventId: String? = null
    ): Boolean {
        val isFinishConfirmationResume =
            FinishConfirmationPauseCoordinator.finishConfirmationEventId(eventId) != null
        if (finishConfirmationPauseCoordinator.blocksNormalResume && !isFinishConfirmationResume) {
            gateEngine.pause()
            _uiState.update {
                it.copy(
                    raceStatus = if (it.phase == RacePhase.ACTIVE_RACE) "paused" else it.raceStatus
                )
            }
            Log.w(TAG, "Ignoring resumeDetection while finish confirmation owns the pause")
            return false
        }

        if (foregroundGateReadinessRecoveryInProgress) {
            if (!allGateReadinessBarrierSatisfied()) {
                Log.w(TAG, "Ignoring resumeDetection while gate-readiness recovery is incomplete")
                _uiState.update {
                    it.copy(errorMessage = context.getString(R.string.race_error_rearm_all))
                }
                return false
            }
            clearForegroundGateReadinessRecovery()
        }

        val now = SystemClock.elapsedRealtimeNanos()
        lastPauseResumeTimestampNanos = now
        if (pausedSinceNanos > 0L) {
            val pauseDuration = (now - pausedSinceNanos).coerceAtLeast(0L)
            localStartTimeNanos = localStartTimeNanos?.plus(pauseDuration)
            pausedSinceNanos = 0L
        }
        gateEngine.resume()
        _uiState.update {
            it.copy(
                raceStatus = if (it.raceStatus == "paused") {
                    if (localStartTimeNanos != null) "started" else "waiting"
                } else {
                    it.raceStatus
                }
            )
        }
        if (localStartTimeNanos != null && _uiState.value.phase == RacePhase.ACTIVE_RACE) {
            startTimerTick()
        }
        if (broadcast) {
            bleClockSyncService.sendCriticalMessage(
                TimingPayload.ResumeDetection(),
                eventId = eventId,
                runId = currentRunId
            )
        }
        Log.i(
            TAG,
            "Detection resumed, broadcast=$broadcast, finishConfirmation=$isFinishConfirmationResume"
        )
        return true
    }

    private fun handleRemotePauseDetection(eventId: String?, senderId: String) {
        if (_uiState.value.isHostingSession && _uiState.value.numberOfGates > 2) {
            relayCriticalFromHost(
                payload = TimingPayload.PauseDetection(),
                excludingDeviceId = senderId,
                eventId = eventId,
                runId = currentRunId
            )
        }
        val finishEventId = FinishConfirmationPauseCoordinator.finishConfirmationEventId(eventId)
        if (finishEventId != null) {
            finishConfirmationPauseCoordinator.receivePause(finishEventId)
            pauseDetection(broadcast = false, eventId = finishEventId)
            Log.i(TAG, "Remote finish confirmation paused detection")
            return
        }

        val timestamp = SystemClock.elapsedRealtimeNanos()
        if (timestamp <= lastPauseResumeTimestampNanos) {
            Log.d(TAG, "Ignoring out-of-order pauseDetection")
            return
        }

        pauseDetection(broadcast = false)
        Log.i(TAG, "Remote paused detection")
    }

    private fun handleRemoteResumeDetection(eventId: String?, senderId: String) {
        val finishEventId = FinishConfirmationPauseCoordinator.finishConfirmationEventId(eventId)
        val resumeIsAllowedToPropagate = finishEventId != null ||
            !finishConfirmationPauseCoordinator.blocksNormalResume
        if (resumeIsAllowedToPropagate &&
            _uiState.value.isHostingSession &&
            _uiState.value.numberOfGates > 2
        ) {
            relayCriticalFromHost(
                payload = TimingPayload.ResumeDetection(),
                excludingDeviceId = senderId,
                eventId = eventId,
                runId = currentRunId
            )
        }
        if (finishEventId != null) {
            val cancelResult = finishConfirmationPauseCoordinator.receiveResume(
                eventId = finishEventId,
                automaticPauseRemains = automaticDetectionPauseRemains()
            )
            if (cancelResult.eventId == null) {
                Log.d(TAG, "Ignoring non-matching finish-confirmation resume")
                return
            }
            if (cancelResult.shouldResumeLocally) {
                resumeDetection(broadcast = false, eventId = finishEventId)
            } else {
                enforceAutomaticDetectionPause()
            }
            Log.i(
                TAG,
                "Remote finish confirmation cancelled; resumed=${cancelResult.shouldResumeLocally}"
            )
            return
        }

        val timestamp = SystemClock.elapsedRealtimeNanos()
        if (timestamp <= lastPauseResumeTimestampNanos) {
            Log.d(TAG, "Ignoring out-of-order resumeDetection")
            return
        }

        resumeDetection(broadcast = false)
        Log.i(TAG, "Remote resumed detection")
    }

    private fun automaticDetectionPauseRemains(): Boolean =
        appBackgroundPausedDetection ||
            detectionPausedForForegroundRecovery ||
            foregroundGateReadinessRecoveryInProgress

    private fun enforceAutomaticDetectionPause() {
        stopTimerTick()
        gateEngine.pause()
        _uiState.update {
            it.copy(
                raceStatus = if (it.phase == RacePhase.ACTIVE_RACE) "paused" else it.raceStatus
            )
        }
    }

    private fun handleAdjustGateLine(gateId: String, position: Double) {
        if (gateId != deviceId) return

        val normalizedPosition = position.toFloat()
        gateEngine.setGatePosition(normalizedPosition)
        Log.i(TAG, "Remote adjusted local gate line to $normalizedPosition")
    }

    private fun canonicalRunId(runId: String?): String =
        runIdentityRegistry.resolve(runId ?: currentRunId)

    private fun adoptCanonicalRunId(incomingRunId: String?, reason: String) {
        if (incomingRunId.isNullOrBlank()) return
        val previousRunId = canonicalRunId(currentRunId)
        val incomingCanonical = canonicalRunId(incomingRunId)
        if (previousRunId.equals(incomingCanonical, ignoreCase = true)) {
            currentRunId = incomingCanonical
            return
        }

        val canonical = runIdentityRegistry.registerAlias(previousRunId, incomingCanonical)
        currentRunId = canonical
        canonicalizePendingRunUpdates(canonical)
        Log.i(
            TAG,
            "Adopted canonical run ${canonical.take(8)} after $reason (was ${previousRunId.take(8)})"
        )
    }

    private fun registerRunAlias(aliasRunId: String?, canonicalRunId: String, reason: String) {
        if (aliasRunId.isNullOrBlank()) return
        val canonical = runIdentityRegistry.registerAlias(aliasRunId, canonicalRunId)
        canonicalizePendingRunUpdates(canonical)
        Log.d(TAG, "Registered run alias ${aliasRunId.take(8)} -> ${canonical.take(8)} ($reason)")
    }

    private fun canonicalizePendingRunUpdates(canonicalRunId: String) {
        val thumbnailKeys = pendingRemoteThumbnails.keys
            .filter { it != canonicalRunId && runIdentityRegistry.isSameRun(it, canonicalRunId) }
        thumbnailKeys.forEach { alias ->
            val moved = pendingRemoteThumbnails.remove(alias).orEmpty()
            val destination = pendingRemoteThumbnails.getOrPut(canonicalRunId) { mutableListOf() }
            moved.forEach { pending ->
                destination.removeAll { it.role == pending.role && it.gateIndex == pending.gateIndex }
                destination += pending
            }
        }

        val calibrationKeys = pendingRemoteCalibrations.keys
            .filter { it != canonicalRunId && runIdentityRegistry.isSameRun(it, canonicalRunId) }
        calibrationKeys.forEach { alias ->
            pendingRemoteCalibrations.remove(alias)?.let { moved ->
                pendingRemoteCalibrations.getOrPut(canonicalRunId) { mutableListOf() }.addAll(moved)
            }
        }

        val adjustmentKeys = pendingRemoteAdjustments.keys
            .filter { it != canonicalRunId && runIdentityRegistry.isSameRun(it, canonicalRunId) }
        adjustmentKeys.forEach { alias ->
            pendingRemoteAdjustments.remove(alias)?.let { pendingRemoteAdjustments[canonicalRunId] = it }
        }
    }

    private fun handleThumbnailUpdate(payload: TimingPayload.ThumbnailUpdate, runId: String?) {
        val metadata = remoteThumbnailMetadataByEventId[payload.eventId]
        if (metadata != null && (metadata.gateId != payload.gateId || metadata.role != payload.role)) {
            Log.w(TAG, "Thumbnail metadata mismatch for ${payload.eventId.take(16)}")
        }
        val gateRole = metadata
            ?.takeIf { it.gateId == payload.gateId && it.role == payload.role }
            ?.let { gateRoleString(it.role, it.gateIndex) }
            ?: thumbnailRoleString(payload.role)

        // Base64/JPEG decode is deliberately off Main so a photo cannot stall
        // timing, ACK, or control-message handling.
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                decodeBase64Bitmap(
                    payload.thumbnailData,
                    "thumbnail update ${payload.eventId.take(16)}"
                )
            } ?: return@launch

            val targetRunId = canonicalRunId(runId)
            if (runIdentityRegistry.isSameRun(targetRunId, currentRunId)) {
                _uiState.update {
                    it.copy(
                        peerThumbnail = bitmap,
                        peerGateRole = gateRole
                    )
                }
            }

            val gateIndex = metadata?.gateIndex ?: when (payload.role) {
                TimingRole.START_LINE -> 0
                TimingRole.FINISH_LINE -> _uiState.value.numberOfGates.coerceAtLeast(2) - 1
                TimingRole.LAP_GATE -> null
                TimingRole.CONTROL_ONLY -> null
            }
            val applied = sessionRepository.applyRemoteThumbnail(
                runId = targetRunId,
                role = payload.role,
                gateIndex = gateIndex,
                thumbnail = bitmap
            )
            if (!applied) {
                bufferRemoteThumbnail(targetRunId, payload.role, gateIndex, bitmap)
            }
            Log.i(
                TAG,
                "Received thumbnail update ${payload.eventId.take(16)} from ${payload.gateId.take(8)}"
            )
        }
    }

    private fun clearResultDedupeState() {
        completedResultStartReferences.clear()
        lastProcessedFinishSplitNanos = null
    }

    private fun shouldDropStaleSessionEnvelope(message: com.trackspeed.android.protocol.TimingMessage): Boolean {
        val currentSessionId = bleClockSyncService.currentSessionId
        if (message.sessionId.equals(currentSessionId, ignoreCase = true)) {
            // ClockSyncManager and this ViewModel independently collect the
            // shared BLE stream. The manager can adopt the host session first;
            // still converge the race/persistence session when this collector
            // receives the same bootstrap envelope afterwards.
            if (
                !_uiState.value.isHostingSession &&
                !message.sessionId.equals(sessionId, ignoreCase = true) &&
                shouldAdoptIncomingSessionId(message)
            ) {
                Log.i(
                    TAG,
                    "Synchronizing local race session ${message.sessionId.take(8)} " +
                        "after transport adoption"
                )
                sessionId = message.sessionId
                observeActiveSessionRuns(sessionId)
                clearEnvelopeDedupeState()
            }
            return false
        }

        // Joiners can adopt the host's envelope session from the same bootstrap
        // messages iOS accepts. Other foreign-session messages are stale.
        if (!_uiState.value.isHostingSession && shouldAdoptIncomingSessionId(message)) {
            Log.i(
                TAG,
                "Adopting race envelope session ${message.sessionId.take(8)} from ${message.senderId.take(8)}"
            )
            bleClockSyncService.setSessionId(message.sessionId)
            sessionId = message.sessionId
            observeActiveSessionRuns(sessionId)
            clearEnvelopeDedupeState()
            return false
        }

        Log.w(
            TAG,
            "Ignoring race message for different session from ${message.senderId.take(8)}; " +
                "expected=${currentSessionId.take(8)}, got=${message.sessionId.take(8)}"
        )
        return true
    }

    private fun shouldAdoptIncomingSessionId(message: com.trackspeed.android.protocol.TimingMessage): Boolean {
        return when (message.payload) {
            is TimingPayload.SessionConfig,
            is TimingPayload.RoleAssigned,
            is TimingPayload.GateAssigned,
            is TimingPayload.SupabaseSession,
            is TimingPayload.HybridSessionInfo -> true
            else -> false
        }
    }

    private fun shouldDropDuplicateEnvelope(message: com.trackspeed.android.protocol.TimingMessage): Boolean {
        val previousSessionId = lastReceivedSessionIdBySender[message.senderId]
        if (previousSessionId != null && previousSessionId != message.sessionId) {
            Log.d(TAG, "New race message session from ${message.senderId.take(8)}: ${message.sessionId.take(8)}")
            receivedSequencesBySender[message.senderId]?.clear()
        }
        lastReceivedSessionIdBySender[message.senderId] = message.sessionId

        val senderSequences = receivedSequencesBySender.getOrPut(message.senderId) { mutableSetOf() }
        if (!senderSequences.add(message.seq)) {
            Log.d(TAG, "Ignoring duplicate race message seq=${message.seq} from ${message.senderId.take(8)}")
            return true
        }
        if (senderSequences.size > MAX_SEQUENCES_PER_SENDER) {
            val retained = senderSequences.sorted().takeLast(MAX_SEQUENCES_PER_SENDER)
            senderSequences.clear()
            senderSequences.addAll(retained)
        }

        val messageId = message.messageId
        if (messageId != null) {
            if (!processedMessageIds.add(messageId)) {
                Log.d(TAG, "Ignoring duplicate race messageId=${messageId.take(8)}")
                return true
            }
            cleanupProcessedMessageIdsIfNeeded()
        }
        return false
    }

    private fun cleanupProcessedMessageIdsIfNeeded() {
        if (processedMessageIds.size <= MAX_PROCESSED_MESSAGE_IDS) return
        val removeCount = processedMessageIds.size - (MAX_PROCESSED_MESSAGE_IDS / 2)
        val toRemove = processedMessageIds.take(removeCount).toSet()
        processedMessageIds.removeAll(toRemove)
    }

    private fun clearEnvelopeDedupeState() {
        processedMessageIds.clear()
        receivedSequencesBySender.clear()
        lastReceivedSessionIdBySender.clear()
    }

    private fun isMessageTargetedToLocalDevice(message: com.trackspeed.android.protocol.TimingMessage): Boolean {
        val envelopeTarget = message.targetDeviceId
        if (envelopeTarget != null && envelopeTarget != deviceId) {
            return false
        }

        return when (val payload = message.payload) {
            is TimingPayload.RoleAssigned ->
                payload.targetDeviceId == null || payload.targetDeviceId == deviceId
            is TimingPayload.GateAssigned ->
                payload.assignment.targetDeviceId == null || payload.assignment.targetDeviceId == deviceId
            else -> true
        }
    }

    private fun targetDescription(message: com.trackspeed.android.protocol.TimingMessage): String {
        val payloadTarget = when (val payload = message.payload) {
            is TimingPayload.RoleAssigned -> payload.targetDeviceId
            is TimingPayload.GateAssigned -> payload.assignment.targetDeviceId
            else -> null
        }
        return (message.targetDeviceId ?: payloadTarget ?: "unknown").take(8)
    }

    private fun decodeBase64Bitmap(base64Data: String?, label: String): Bitmap? {
        if (base64Data.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode $label", e)
            null
        }
    }

    private fun handleThumbnailMetadata(payload: TimingPayload.ThumbnailMetadata, runId: String?) {
        remoteThumbnailMetadataByEventId[payload.eventId] = payload
        if (remoteThumbnailMetadataByEventId.size > 200) {
            val retained = remoteThumbnailMetadataByEventId.entries.toList().takeLast(100)
            remoteThumbnailMetadataByEventId.clear()
            retained.forEach { (eventId, metadata) ->
                remoteThumbnailMetadataByEventId[eventId] = metadata
            }
        }

        Log.i(
            TAG,
            "Received thumbnail metadata ${payload.eventId.take(16)} " +
                "role=${payload.role}, gate=${payload.gateIndex}, " +
                "pos=${payload.gatePosition}, velocity=${payload.velocityPxPerSec}"
        )

        applyOrBufferRemoteCalibration(
            runId = runId,
            calibration = RemoteGateCalibration(
                role = payload.role,
                gatePosition = payload.gatePosition,
                gateIndex = payload.gateIndex,
                velocityPxPerSec = payload.velocityPxPerSec,
                crossingDirection = payload.crossingDirection,
                workWidth = payload.workWidth,
                thumbnailDebugJson = payload.thumbnailDebug?.toString()
            )
        )
    }

    private fun handleEventSyncRequest(
        payload: TimingPayload.EventSync,
        senderId: String
    ) {
        val state = _uiState.value
        if (!state.isHostingSession) {
            Log.d(TAG, "Ignoring eventSync request on non-host device")
            return
        }

        val now = SystemClock.elapsedRealtimeNanos()
        val lastRequestAt = lastEventSyncAtBySender[senderId]
        if (lastRequestAt != null && now - lastRequestAt < EVENT_SYNC_RATE_LIMIT_NANOS) {
            Log.w(TAG, "Rate-limited eventSync from ${senderId.take(8)}")
            return
        }
        lastEventSyncAtBySender[senderId] = now
        if (lastEventSyncAtBySender.size > 32) {
            val oldest = lastEventSyncAtBySender.minByOrNull { it.value }?.key
            if (oldest != null) lastEventSyncAtBySender.remove(oldest)
        }

        val requestedRunId = canonicalRunId(payload.runId)
        val runEvents = syncableEventLog.entries
            .filter { (loggedRunId, _) -> runIdentityRegistry.isSameRun(loggedRunId, requestedRunId) }
            .flatMap { it.value }
            .distinctBy { it.eventId }
            .sortedBy { it.seq }
        val eventsToSend = payload.lastSeenEventId?.let { lastSeen ->
            val lastSeenIndex = runEvents.indexOfFirst { it.eventId == lastSeen }
            if (lastSeenIndex >= 0) runEvents.drop(lastSeenIndex + 1) else runEvents
        } ?: runEvents

        Log.i(
            TAG,
            "EventSync: replaying ${eventsToSend.size} events for run ${requestedRunId.take(8)} to ${senderId.take(8)}"
        )
        bleClockSyncService.sendMessage(
            TimingPayload.EventSyncResponse(
                events = eventsToSend,
                fromEventId = eventsToSend.firstOrNull()?.eventId
            )
        )
    }

    private fun handleEventSyncResponse(
        payload: TimingPayload.EventSyncResponse,
        runId: String?
    ) {
        Log.i(
            TAG,
            "EventSync: received ${payload.events.size} events from ${payload.fromEventId ?: "start"}"
        )
        payload.events.forEach { event ->
            when (event.eventType) {
                "crossingEvent" -> {
                    val gateCount = _uiState.value.numberOfGates.coerceAtLeast(2)
                    val inferredRole = when (event.gateIndex) {
                        0 -> TimingRole.START_LINE
                        gateCount - 1 -> TimingRole.FINISH_LINE
                        else -> TimingRole.LAP_GATE
                    }
                    handleRemoteCrossingEvent(
                        TimingPayload.CrossingEvent(
                            gateId = event.gateId,
                            role = inferredRole,
                            gateIndex = event.gateIndex,
                            timestampNanos = event.timestampNanos,
                            confidence = 1.0,
                            thumbnailData = null
                        ),
                        runId = runId,
                        eventId = event.eventId
                    )
                }
                "startEvent" -> {
                    onRemoteStartReceived(event.timestampNanos, runId)
                }
                "finishResult" -> {
                    val splitNanos = event.splitNanos
                    if (splitNanos == null) {
                        Log.w(TAG, "EventSync: finishResult missing splitNanos (${event.eventId.take(16)})")
                    } else {
                        handleRemoteFinishResult(
                            TimingPayload.FinishResult(
                                splitNanos = splitNanos,
                                uncertaintyMs = event.uncertaintyMs ?: _uiState.value.syncUncertaintyMs,
                                imageData = null
                            ),
                            runId = runId
                        )
                    }
                }
                else -> Log.w(TAG, "EventSync: unsupported event type ${event.eventType}")
            }
        }
    }

    private fun handleCalibrationUpdate(payload: TimingPayload.CalibrationUpdate, runId: String?) {
        Log.i(
            TAG,
            "Received calibration update role=${payload.role}, " +
                "pos=${payload.gatePosition}, velocity=${payload.velocityPxPerSec}, " +
                "dir=${payload.crossingDirection}, workWidth=${payload.workWidth}"
        )

        applyOrBufferRemoteCalibration(
            runId = runId,
            calibration = RemoteGateCalibration(
                role = payload.role,
                gatePosition = payload.gatePosition,
                gateIndex = null,
                velocityPxPerSec = payload.velocityPxPerSec,
                crossingDirection = payload.crossingDirection,
                workWidth = payload.workWidth,
                thumbnailDebugJson = payload.thumbnailDebug?.toString()
            )
        )
    }

    private fun handleAdjustmentUpdate(payload: TimingPayload.AdjustmentUpdate) {
        val targetRunId = canonicalRunId(payload.runId)
        Log.i(
            TAG,
            "Received adjustment update run=${targetRunId.take(8)}, " +
                "gate=${payload.gateLabel}, pos=${payload.newGatePosition}, " +
                "corrected=${payload.correctedTimeSeconds}"
        )

        if (runIdentityRegistry.isSameRun(targetRunId, currentRunId)) {
            val correctedSegments = payload.splitsJSON?.let { splitsJson ->
                runCatching { Json.decodeFromString<List<SegmentSplit>>(splitsJson) }
                    .onFailure { Log.w(TAG, "Failed to decode adjusted split payload", it) }
                    .getOrNull()
            }
            _uiState.update { state ->
                state.copy(
                    gatePosition = adjustedLocalGatePosition(
                        currentPosition = state.gatePosition,
                        gateLabel = payload.gateLabel,
                        newGatePosition = payload.newGatePosition
                    ),
                    resultTimeSeconds = payload.correctedTimeSeconds ?: state.resultTimeSeconds,
                    elapsedTimeSeconds = payload.correctedTimeSeconds ?: state.elapsedTimeSeconds,
                    resultSegments = correctedSegments ?: state.resultSegments
                )
            }
        }

        viewModelScope.launch {
            val applied = sessionRepository.applyRemoteGateAdjustment(
                runId = targetRunId,
                gateLabel = payload.gateLabel,
                newGatePosition = payload.newGatePosition,
                correctedTimeSeconds = payload.correctedTimeSeconds,
                splitsJson = payload.splitsJSON
            )
            if (!applied) {
                Log.w(TAG, "No saved run found for adjustment ${targetRunId.take(8)}")
                pendingRemoteAdjustments[targetRunId] = payload
            }
        }
    }

    private fun adjustedLocalGatePosition(
        currentPosition: Float,
        gateLabel: String,
        newGatePosition: Double
    ): Float {
        val label = gateLabel.trim().lowercase()
        val state = _uiState.value
        val localRole = state.gateAssignment?.role ?: fallbackGateAssignment(state.role, state.numberOfGates)?.role
        val shouldApplyToLocalGate = when {
            label.contains("start") -> localRole == TimingRole.START_LINE
            label.contains("finish") || label.contains("crossing") -> localRole == TimingRole.FINISH_LINE
            else -> true
        }

        return if (shouldApplyToLocalGate) {
            newGatePosition.toFloat().coerceIn(0f, 1f)
        } else {
            currentPosition
        }
    }

    private fun applyOrBufferRemoteCalibration(
        runId: String?,
        calibration: RemoteGateCalibration
    ) {
        val targetRunId = canonicalRunId(runId)
        viewModelScope.launch {
            val applied = sessionRepository.applyRemoteGateCalibration(
                runId = targetRunId,
                role = calibration.role,
                gateIndex = calibration.gateIndex,
                gatePosition = calibration.gatePosition,
                velocityPxPerSec = calibration.velocityPxPerSec,
                crossingDirection = calibration.crossingDirection,
                workWidth = calibration.workWidth,
                thumbnailDebugJson = calibration.thumbnailDebugJson
            )
            if (!applied) {
                pendingRemoteCalibrations
                    .getOrPut(targetRunId) { mutableListOf() }
                    .add(calibration)
                Log.w(TAG, "Buffered remote calibration until run ${targetRunId.take(8)} is saved")
            }
        }
    }

    private suspend fun applyPendingRemoteRunUpdates(runId: String) {
        val targetRunId = canonicalRunId(runId)
        canonicalizePendingRunUpdates(targetRunId)
        pendingRemoteThumbnails.remove(targetRunId)?.forEach { pending ->
            val applied = sessionRepository.applyRemoteThumbnail(
                runId = targetRunId,
                role = pending.role,
                gateIndex = pending.gateIndex,
                thumbnail = pending.thumbnail
            )
            if (!applied) {
                Log.w(TAG, "Failed to apply buffered remote thumbnail for run ${targetRunId.take(8)}")
            }
        }

        pendingRemoteCalibrations.remove(targetRunId)?.forEach { calibration ->
            sessionRepository.applyRemoteGateCalibration(
                runId = targetRunId,
                role = calibration.role,
                gateIndex = calibration.gateIndex,
                gatePosition = calibration.gatePosition,
                velocityPxPerSec = calibration.velocityPxPerSec,
                crossingDirection = calibration.crossingDirection,
                workWidth = calibration.workWidth,
                thumbnailDebugJson = calibration.thumbnailDebugJson
            )
        }

        pendingRemoteAdjustments.remove(targetRunId)?.let { adjustment ->
            sessionRepository.applyRemoteGateAdjustment(
                runId = targetRunId,
                gateLabel = adjustment.gateLabel,
                newGatePosition = adjustment.newGatePosition,
                correctedTimeSeconds = adjustment.correctedTimeSeconds,
                splitsJson = adjustment.splitsJSON
            )
        }
    }

    /**
     * Called when the finish phone receives a start event from the start phone.
     */
    private fun onRemoteStartReceived(
        remoteTimestampNanos: Long,
        runId: String? = null
    ) {
        val phase = _uiState.value.phase
        if (phase != RacePhase.ACTIVE_RACE && phase != RacePhase.RESULT) return
        val previousRemoteStart = remoteStartTimeNanos
        if (previousRemoteStart != null && remoteTimestampNanos == previousRemoteStart) {
            Log.d(TAG, "Ignoring duplicate remote start event")
            return
        }
        if (previousRemoteStart != null && remoteTimestampNanos < previousRemoteStart) {
            Log.w(TAG, "Ignoring stale remote start event $remoteTimestampNanos; latest=$previousRemoteStart")
            return
        }

        // Cancel the start event timeout since we received it
        cancelStartEventWaitTimeout()

        // Use drift-corrected offset for better accuracy in longer sessions
        val localStartTime = clockSyncManager.toLocalTimeWithDrift(remoteTimestampNanos)
        val isReplacementStart = localStartTimeNanos != null
        if (isReplacementStart) {
            if (localFinishTimeNanos != null) {
                applyLateReplacementStart(
                    remoteTimestampNanos = remoteTimestampNanos,
                    localStartTimeNanos = localStartTime,
                    incomingRunId = runId,
                    alreadyShowingResult = phase == RacePhase.RESULT
                )
                return
            }
            adoptCanonicalRunId(runId, "replacement remote start")
            clearResultDedupeState()
            Log.i(TAG, "Replacement remote start accepted; runId=${currentRunId.take(8)}")
        } else {
            adoptCanonicalRunId(runId, "remote start")
        }
        remoteStartTimeNanos = remoteTimestampNanos
        localStartTimeNanos = localStartTime

        Log.i(TAG, "Remote start received: remote=$remoteTimestampNanos, local=$localStartTime")

        _uiState.update {
            it.copy(raceStatus = "started")
        }

        // Check if a finish crossing was buffered (detected before start event arrived)
        val bufferedFinish = bufferedFinishTimeNanos
        if (bufferedFinish != null) {
            Log.i(TAG, "Processing buffered finish crossing: $bufferedFinish ns")
            localFinishTimeNanos = bufferedFinish
            finishThumbnail = bufferedFinishThumbnail
            bufferedFinishTimeNanos = null
            bufferedFinishThumbnail = null
            stopTimerTick()
            calculateResult()
        } else {
            startTimerTick()
        }
    }

    private fun applyLateReplacementStart(
        remoteTimestampNanos: Long,
        localStartTimeNanos: Long,
        incomingRunId: String?,
        alreadyShowingResult: Boolean
    ) {
        val finish = this.localFinishTimeNanos ?: return
        val splitNanos = finish - localStartTimeNanos
        if (splitNanos < 1_000_000L || splitNanos > MAX_RESULT_SPLIT_NANOS) {
            Log.w(
                TAG,
                "Ignoring late replacement start with invalid split: start=$localStartTimeNanos finish=$finish"
            )
            return
        }

        // The finish phone may already have persisted and broadcast this run.
        // Keep that identity canonical and treat the replacement-start ID as an
        // alias so delayed media follows the saved result instead of duplicating it.
        val canonicalRunId = canonicalRunId(currentRunId)
        registerRunAlias(incomingRunId, canonicalRunId, "late replacement start")
        currentRunId = canonicalRunId
        remoteStartTimeNanos = remoteTimestampNanos
        this.localStartTimeNanos = localStartTimeNanos
        clearResultDedupeState()
        completedResultStartReferences += remoteTimestampNanos

        if (!alreadyShowingResult) {
            calculateResult()
            return
        }

        val splitSeconds = splitNanos / 1_000_000_000.0
        autoResetJob?.cancel()
        _uiState.update {
            it.copy(
                phase = RacePhase.RESULT,
                resultTimeSeconds = splitSeconds,
                elapsedTimeSeconds = splitSeconds,
                raceStatus = "finished"
            )
        }

        val uncertaintyMs = _uiState.value.resultUncertaintyMs ?: _uiState.value.syncUncertaintyMs
        bleClockSyncService.sendCriticalMessage(
            TimingPayload.TimingResultBroadcast(
                splitNanos = splitNanos,
                uncertaintyMs = uncertaintyMs,
                startGateId = remoteTimestampNanos.toString(),
                finishGateId = deviceId
            ),
            runId = canonicalRunId
        )
        uploadFinishResultEvent(
            splitNanos = splitNanos,
            startReferenceNanos = remoteTimestampNanos,
            uncertaintyMs = uncertaintyMs
        )

        viewModelScope.launch {
            var corrected = false
            for (attempt in 0 until 6) {
                corrected = sessionRepository.correctSavedRaceResult(
                    runId = canonicalRunId,
                    correctedTimeSeconds = splitSeconds,
                    crossingTimestampNanos = finish
                )
                if (corrected) break
                if (attempt < 5) delay(100L)
            }
            if (!corrected) {
                Log.w(TAG, "Corrected replacement start before saved run ${canonicalRunId.take(8)} became available")
            }
            applyPendingRemoteRunUpdates(canonicalRunId)
        }
        scheduleAutoResetForNewRun()
        Log.i(
            TAG,
            "Applied late replacement start to ${canonicalRunId.take(8)} and re-broadcast ${String.format("%.3f", splitSeconds)}s"
        )
    }

    private fun handleRemoteMultiGateResult(payload: TimingPayload.MultiGateResult) {
        if (_uiState.value.phase != RacePhase.ACTIVE_RACE &&
            _uiState.value.phase != RacePhase.RACE_READY
        ) {
            return
        }

        val splitSeconds = payload.totalSplitNanos / 1_000_000_000.0
        stopTimerTick()
        cancelMultiGateTimeout()
        _uiState.update {
            it.copy(
                phase = RacePhase.RESULT,
                resultTimeSeconds = splitSeconds,
                resultUncertaintyMs = payload.uncertaintyMs,
                resultSegments = payload.segments,
                elapsedTimeSeconds = splitSeconds,
                raceStatus = "finished"
            )
        }
        endLiveTimingWorkloadIfNeeded()
        scheduleAutoResetForNewRun()
        Log.i(TAG, "Received multi-gate result: ${String.format("%.3f", splitSeconds)}s, segments=${payload.segments.size}")
    }

    private fun handleRemoteFinishResult(payload: TimingPayload.FinishResult, runId: String?) {
        val state = _uiState.value
        val localRole = state.gateAssignment?.role
            ?: fallbackGateAssignment(state.role, state.numberOfGates)?.role
        if (localRole != TimingRole.START_LINE) {
            Log.d(TAG, "Ignoring legacy finishResult on non-start role: $localRole")
            return
        }
        if (state.phase != RacePhase.ACTIVE_RACE && state.phase != RacePhase.RACE_READY) {
            return
        }
        if (payload.splitNanos <= 0L) {
            Log.w(TAG, "Legacy finishResult ignored - invalid split ${payload.splitNanos}")
            return
        }
        if (payload.splitNanos > MAX_RESULT_SPLIT_NANOS) {
            Log.w(TAG, "Legacy finishResult ignored - split exceeds max run duration ${payload.splitNanos}")
            return
        }
        if (lastProcessedFinishSplitNanos == payload.splitNanos) {
            Log.d(TAG, "Legacy finishResult ignored - duplicate split ${payload.splitNanos}")
            return
        }
        lastProcessedFinishSplitNanos = payload.splitNanos

        val image = decodeBase64Bitmap(payload.imageData, "legacy finishResult image")
        val splitSeconds = payload.splitNanos / 1_000_000_000.0
        val finishTimestamp = (localStartTimeNanos ?: SystemClock.elapsedRealtimeNanos()) + payload.splitNanos
        adoptCanonicalRunId(runId, "legacy finish result")
        val resultRunId = canonicalRunId(runId)
        stopTimerTick()
        _uiState.update {
            it.copy(
                phase = RacePhase.RESULT,
                resultTimeSeconds = splitSeconds,
                resultUncertaintyMs = payload.uncertaintyMs,
                elapsedTimeSeconds = splitSeconds,
                raceStatus = "finished",
                peerThumbnail = image ?: it.peerThumbnail
            )
        }
        endLiveTimingWorkloadIfNeeded()
        scheduleAutoResetForNewRun()

        viewModelScope.launch {
            try {
                val latestState = _uiState.value
                val athlete = selectedRaceAthleteForSave()
                sessionRepository.saveRaceResult(
                    sessionId = sessionId,
                    runNumber = currentRunNumber,
                    runId = resultRunId,
                    distance = latestState.distanceMeters,
                    startType = latestState.startType,
                    numberOfGates = latestState.numberOfGates,
                    gateDistances = latestState.gateDistances,
                    timeSeconds = splitSeconds,
                    thumbnail = image ?: latestState.peerThumbnail,
                    gatePosition = latestState.gatePosition,
                    athleteId = athlete?.id,
                    athleteName = athlete?.name,
                    athleteColor = athlete?.color,
                    crossingTimestampNanos = finishTimestamp,
                    localGateRole = localStartCalibration?.role,
                    crossingVelocityPxPerSec = localStartCalibration?.velocityPxPerSec,
                    crossingDirection = localStartCalibration?.crossingDirection,
                    workWidth = localStartCalibration?.workWidth,
                    thumbnailDebugJson = localStartCalibration?.thumbnailDebug?.toString(),
                    localGateFrames = localGateFramesForSave(resultRunId)
                )
                applyPendingRemoteRunUpdates(resultRunId)
                Log.i(TAG, "Saved legacy finishResult to local DB")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save legacy finishResult", e)
            }
        }

        Log.i(TAG, "Received legacy finishResult: ${String.format("%.3f", splitSeconds)}s")
    }

    private fun handleRemoteTimingResult(
        payload: TimingPayload.TimingResultBroadcast,
        runId: String? = null,
        requiresStartReference: Boolean = false
    ) {
        if (payload.finishGateId == deviceId) {
            Log.d(TAG, "Ignoring own timing result broadcast")
            return
        }
        if (_uiState.value.phase != RacePhase.ACTIVE_RACE &&
            _uiState.value.phase != RacePhase.RACE_READY
        ) {
            return
        }

        val startReference = payload.startGateId.toLongOrNull()?.takeIf { it > 0L }
        val currentStartReference = remoteStartTimeNanos ?: localStartTimeNanos
        if (requiresStartReference && startReference == null) {
            Log.w(TAG, "Timing result ignored - missing required start reference")
            return
        }
        if (startReference != null &&
            currentStartReference != startReference &&
            recentStartSnapshots.containsKey(startReference)
        ) {
            handleHistoricalTimingResult(
                snapshot = recentStartSnapshots.getValue(startReference),
                payload = payload,
                runId = runId
            )
            return
        }
        if (startReference != null && currentStartReference == null) {
            Log.w(TAG, "Timing result ignored - no local start reference for $startReference")
            return
        }
        if (startReference != null &&
            currentStartReference != null &&
            currentStartReference != startReference
        ) {
            Log.w(TAG, "Timing result ignored - stale start reference $startReference, current $currentStartReference")
            return
        }
        if (payload.splitNanos <= 0L) {
            Log.w(TAG, "Timing result ignored - invalid split ${payload.splitNanos}")
            return
        }
        if (payload.splitNanos > MAX_RESULT_SPLIT_NANOS) {
            Log.w(TAG, "Timing result ignored - split exceeds max run duration ${payload.splitNanos}")
            return
        }

        val resultReference = startReference ?: currentStartReference
        if (resultReference != null && !completedResultStartReferences.add(resultReference)) {
            Log.d(TAG, "Timing result ignored - duplicate start reference $resultReference")
            return
        }

        adoptCanonicalRunId(runId, "timing result")
        val splitSeconds = payload.splitNanos / 1_000_000_000.0
        val finishTimestamp = (localStartTimeNanos ?: SystemClock.elapsedRealtimeNanos()) + payload.splitNanos
        val resultRunId = canonicalRunId(runId)
        lastProcessedFinishSplitNanos = payload.splitNanos
        stopTimerTick()
        _uiState.update {
            it.copy(
                phase = RacePhase.RESULT,
                resultTimeSeconds = splitSeconds,
                resultUncertaintyMs = payload.uncertaintyMs,
                elapsedTimeSeconds = splitSeconds,
                raceStatus = "finished"
            )
        }
        endLiveTimingWorkloadIfNeeded()
        scheduleAutoResetForNewRun()

        viewModelScope.launch {
            try {
                val state = _uiState.value
                val athlete = selectedRaceAthleteForSave()
                sessionRepository.saveRaceResult(
                    sessionId = sessionId,
                    runNumber = currentRunNumber,
                    runId = resultRunId,
                    distance = state.distanceMeters,
                    startType = state.startType,
                    numberOfGates = state.numberOfGates,
                    gateDistances = state.gateDistances,
                    timeSeconds = splitSeconds,
                    thumbnail = state.peerThumbnail,
                    gatePosition = state.gatePosition,
                    athleteId = athlete?.id,
                    athleteName = athlete?.name,
                    athleteColor = athlete?.color,
                    crossingTimestampNanos = finishTimestamp,
                    localGateRole = localStartCalibration?.role,
                    crossingVelocityPxPerSec = localStartCalibration?.velocityPxPerSec,
                    crossingDirection = localStartCalibration?.crossingDirection,
                    workWidth = localStartCalibration?.workWidth,
                    thumbnailDebugJson = localStartCalibration?.thumbnailDebug?.toString(),
                    localGateFrames = localGateFramesForSave(resultRunId)
                )
                applyPendingRemoteRunUpdates(resultRunId)
                Log.i(TAG, "Saved remote timing result to local DB")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save remote timing result", e)
            }
        }

        Log.i(TAG, "Received timing result: ${String.format("%.3f", splitSeconds)}s")
    }

    private fun handleHistoricalTimingResult(
        snapshot: RecentStartSnapshot,
        payload: TimingPayload.TimingResultBroadcast,
        runId: String?
    ) {
        if (payload.splitNanos < 1_000_000L || payload.splitNanos > MAX_RESULT_SPLIT_NANOS) {
            Log.w(TAG, "Historical timing result ignored - invalid split ${payload.splitNanos}")
            return
        }
        if (!completedResultStartReferences.add(snapshot.startReferenceNanos)) {
            Log.d(TAG, "Historical timing result ignored - duplicate start ${snapshot.startReferenceNanos}")
            return
        }

        val canonicalRunId = if (runId.isNullOrBlank()) {
            canonicalRunId(snapshot.runId)
        } else {
            runIdentityRegistry.registerAlias(snapshot.runId, runId)
        }
        canonicalizePendingRunUpdates(canonicalRunId)
        recentStartSnapshots.remove(snapshot.startReferenceNanos)
        val splitSeconds = payload.splitNanos / 1_000_000_000.0
        val finishTimestamp = snapshot.localStartNanos + payload.splitNanos

        viewModelScope.launch {
            try {
                val athlete = snapshot.athleteId?.let { athleteDao.getAthleteById(it) }
                sessionRepository.saveRaceResult(
                    sessionId = sessionId,
                    runNumber = snapshot.runNumber,
                    runId = canonicalRunId,
                    distance = snapshot.distanceMeters,
                    startType = snapshot.startType,
                    numberOfGates = snapshot.numberOfGates,
                    gateDistances = snapshot.gateDistances,
                    timeSeconds = splitSeconds,
                    thumbnail = null,
                    gatePosition = snapshot.gatePosition,
                    athleteId = athlete?.id,
                    athleteName = athlete?.name,
                    athleteColor = athlete?.color,
                    crossingTimestampNanos = finishTimestamp,
                    runCreatedAtMillis = snapshot.capturedAtMillis
                )
                applyPendingRemoteRunUpdates(canonicalRunId)
                Log.i(
                    TAG,
                    "Saved delayed result ${String.format("%.3f", splitSeconds)}s for historical run ${canonicalRunId.take(8)}"
                )
            } catch (error: Exception) {
                completedResultStartReferences.remove(snapshot.startReferenceNanos)
                recentStartSnapshots[snapshot.startReferenceNanos] = snapshot
                Log.e(TAG, "Failed to save delayed historical timing result", error)
            }
        }
    }

    private fun calculateResult() {
        val start = localStartTimeNanos ?: return
        val finish = localFinishTimeNanos ?: return

        val splitNanos = finish - start
        if (splitNanos <= 0) {
            Log.e(TAG, "Invalid split: finish ($finish) is not after start ($start)")
            _uiState.update {
                it.copy(errorMessage = context.getString(R.string.race_error_invalid_timing))
            }
            return
        }
        // Reject sub-millisecond splits as clock sync artifacts (matches iOS 1ms floor)
        if (splitNanos < 1_000_000) {
            Log.w(TAG, "Split too short (${splitNanos}ns < 1ms) — likely clock sync artifact, ignoring")
            return
        }
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

        val resultStartReference = remoteStartTimeNanos ?: start
        val resultRunId = currentRunId
        completedResultStartReferences.add(resultStartReference)

        _uiState.update {
            it.copy(
                phase = RacePhase.RESULT,
                resultTimeSeconds = splitSeconds,
                resultUncertaintyMs = totalUncertaintyMs,
                elapsedTimeSeconds = splitSeconds,
                raceStatus = "finished"
            )
        }
        endLiveTimingWorkloadIfNeeded()
        scheduleAutoResetForNewRun()

        bleClockSyncService.sendCriticalMessage(
            TimingPayload.TimingResultBroadcast(
                splitNanos = splitNanos,
                uncertaintyMs = totalUncertaintyMs,
                startGateId = resultStartReference.toString(),
                finishGateId = deviceId
            ),
            runId = resultRunId
        )

        uploadFinishResultEvent(
            splitNanos = splitNanos,
            startReferenceNanos = resultStartReference,
            uncertaintyMs = totalUncertaintyMs
        )

        // Save result to local Room DB
        viewModelScope.launch {
            try {
                val athlete = selectedRaceAthleteForSave()
                sessionRepository.saveRaceResult(
                    sessionId = sessionId,
                    runNumber = currentRunNumber,
                    runId = resultRunId,
                    distance = _uiState.value.distanceMeters,
                    startType = _uiState.value.startType,
                    numberOfGates = _uiState.value.numberOfGates,
                    gateDistances = _uiState.value.gateDistances,
                    timeSeconds = splitSeconds,
                    thumbnail = finishThumbnail,
                    gatePosition = _uiState.value.gatePosition,
                    athleteId = athlete?.id,
                    athleteName = athlete?.name,
                    athleteColor = athlete?.color,
                    crossingTimestampNanos = finish,
                    localGateRole = localFinishCalibration?.role,
                    crossingVelocityPxPerSec = localFinishCalibration?.velocityPxPerSec,
                    crossingDirection = localFinishCalibration?.crossingDirection,
                    workWidth = localFinishCalibration?.workWidth,
                    thumbnailDebugJson = localFinishCalibration?.thumbnailDebug?.toString(),
                    localGateFrames = localGateFramesForSave(resultRunId)
                )
                applyPendingRemoteRunUpdates(resultRunId)
                Log.i(TAG, "Saved race result to local DB")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save race result", e)
            }
        }
    }

    private fun buildSegmentSplits(
        orderedCrossings: List<RecordedGateCrossing>,
        gateDistances: Map<Int, Double>,
        gateCount: Int
    ): List<SegmentSplit> = buildMultiGateSegmentSplits(
        orderedCrossings = orderedCrossings.map { crossing ->
            MultiGateCrossingTime(
                gateId = crossing.gateId,
                gateIndex = crossing.gateIndex,
                timestampNanos = crossing.timestampNanos
            )
        },
        gateDistances = gateDistances,
        gateCount = gateCount,
        fallbackTotalDistanceMeters = _uiState.value.distanceMeters
    )

    private fun calculateTimingUncertaintyMs(eventCount: Int): Double {
        val syncUncertainty = _uiState.value.syncUncertaintyMs
        val fps = cameraManager.getAchievedFps().toDouble()
        val detectionUncertaintyMs = if (fps > 0) (1000.0 / fps / 2.0) else 5.0
        return sqrt(syncUncertainty.pow(2) + detectionUncertaintyMs.pow(2) * eventCount)
    }

    private fun timestampOrderingToleranceNanos(): Long {
        val toleranceMs = max(1.0, calculateTimingUncertaintyMs(eventCount = 2))
        return (toleranceMs * 1_000_000.0).toLong()
    }

    private fun distanceForGateIndex(
        gateIndex: Int,
        gateCount: Int,
        gateDistances: Map<Int, Double>
    ): Double {
        val finishIndex = (gateCount - 1).coerceAtLeast(1)
        val finishDistance = gateDistances[finishIndex] ?: _uiState.value.distanceMeters
        return gateDistances[gateIndex]
            ?: (finishDistance * gateIndex.toDouble() / finishIndex.toDouble())
    }

    private fun fallbackGateAssignment(role: DeviceRole?, gateCount: Int): GateAssignment? {
        val finishIndex = (gateCount - 1).coerceAtLeast(1)
        val gateDistances = _uiState.value.gateDistances
        val distance = gateDistances[finishIndex] ?: _uiState.value.distanceMeters
        return when (role) {
            DeviceRole.START -> GateAssignment.start()
            DeviceRole.FINISH -> GateAssignment.finish(finishIndex, distance)
            DeviceRole.LAP -> {
                val gateIndex = _uiState.value.localGateIndex?.coerceIn(1, finishIndex - 1) ?: 1
                GateAssignment.intermediate(
                    gateIndex = gateIndex,
                    distanceFromStart = distanceForGateIndex(gateIndex, gateCount, gateDistances)
                )
            }
            DeviceRole.CONTROL -> GateAssignment(
                role = TimingRole.CONTROL_ONLY,
                gateIndex = -1,
                distanceFromStart = 0.0
            )
            null -> null
        }
    }

    private fun gateRoleString(role: TimingRole, gateIndex: Int): String {
        return when (role) {
            TimingRole.START_LINE -> "start"
            TimingRole.FINISH_LINE -> "finish"
            TimingRole.LAP_GATE -> "split_$gateIndex"
            TimingRole.CONTROL_ONLY -> "control"
        }
    }

    private fun thumbnailRoleString(role: TimingRole): String {
        return when (role) {
            TimingRole.START_LINE -> "start"
            TimingRole.FINISH_LINE -> "finish"
            TimingRole.LAP_GATE -> "lap"
            TimingRole.CONTROL_ONLY -> "control"
        }
    }

    private fun referenceTimestampFromLocal(localTimestampNanos: Long): Long {
        return if (_uiState.value.isHostingSession) {
            localTimestampNanos
        } else {
            clockSyncManager.toRemoteTimeWithDrift(localTimestampNanos)
        }
    }

    /**
     * Upload a race event to Supabase (fire-and-forget, non-blocking).
     */
    private fun uploadRaceEvent(eventType: String, crossingTimeNanos: Long) {
        val eventRunId = currentRunId
        viewModelScope.launch {
            try {
                val syncOffset = clockSyncManager.getOffsetNanos()
                val effectiveSessionId = supabaseSessionId ?: sessionId
                raceEventService.insertRaceEvent(
                    RaceEventDto(
                        sessionId = effectiveSessionId,
                        runId = eventRunId,
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
                Log.w(TAG, "Failed to upload race event to cloud (non-critical): ${e.safeCloudErrorCode()}")
            }
        }
    }

    private fun uploadFinishResultEvent(
        splitNanos: Long,
        startReferenceNanos: Long,
        uncertaintyMs: Double
    ) {
        val eventRunId = currentRunId
        viewModelScope.launch {
            try {
                val effectiveSessionId = supabaseSessionId ?: sessionId
                raceEventService.insertRaceEvent(
                    RaceEventDto(
                        sessionId = effectiveSessionId,
                        runId = eventRunId,
                        eventType = "finish",
                        crossingTimeNanos = splitNanos,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        clockOffsetNanos = startReferenceNanos,
                        uncertaintyMs = uncertaintyMs
                    )
                )
                Log.d(TAG, "Uploaded finish result to Supabase")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to upload finish result to cloud (non-critical): ${e.safeCloudErrorCode()}")
            }
        }
    }

    // === Cloud Subscriptions ===

    private fun startRaceEventSubscription(effectiveSessionId: String) {
        prepareCloudSubscriptionSession(effectiveSessionId)
        val catchUpSince = lastCloudRaceEventCreatedAt
        raceEventSubscriptionJob?.cancel()
        raceEventSubscriptionJob = viewModelScope.launch {
            try {
                if (catchUpSince != null) {
                    raceEventService.catchUpRaceEvents(
                        sessionId = effectiveSessionId,
                        sinceCreatedAt = catchUpSince,
                        excludingDeviceId = deviceId
                    ).forEach { event ->
                        handleCloudRaceEvent(event)
                    }
                }

                raceEventService.subscribeToRaceEvents(effectiveSessionId)
                    .collect { event ->
                        handleCloudRaceEvent(event)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Race event subscription failed (non-critical): ${e.safeCloudErrorCode()}")
                scheduleCloudSubscriptionRecovery(effectiveSessionId, "race_events_stream_failed")
            }
        }
    }

    private fun startCrossingSubscription(effectiveSessionId: String) {
        prepareCloudSubscriptionSession(effectiveSessionId)
        val catchUpSince = lastCloudCrossingCreatedAt
        crossingSubscriptionJob?.cancel()
        crossingSubscriptionJob = viewModelScope.launch {
            try {
                if (catchUpSince != null) {
                    raceEventService.catchUpCrossings(
                        sessionId = effectiveSessionId,
                        sinceCreatedAt = catchUpSince,
                        excludingDeviceId = deviceId
                    ).forEach { crossing ->
                        handleCloudCrossingEvent(crossing)
                    }
                }

                raceEventService.subscribeToCrossings(effectiveSessionId)
                    .collect { crossing ->
                        handleCloudCrossingEvent(crossing)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Crossing subscription failed (non-critical): ${e.safeCloudErrorCode()}")
                scheduleCloudSubscriptionRecovery(effectiveSessionId, "crossings_stream_failed")
            }
        }
    }

    private fun scheduleCloudSubscriptionRecovery(effectiveSessionId: String, reason: String) {
        if (cloudSubscriptionRecoveryJob?.isActive == true) return

        cloudSubscriptionRecoveryJob = viewModelScope.launch {
            delay(800L)
            val activeSessionId = supabaseSessionId ?: sessionId
            if (activeSessionId != effectiveSessionId) {
                Log.d(TAG, "Skipping cloud recovery for stale session $effectiveSessionId ($reason)")
                return@launch
            }
            if (_uiState.value.phase != RacePhase.ACTIVE_RACE) {
                Log.d(TAG, "Skipping cloud recovery outside active race ($reason)")
                return@launch
            }

            Log.i(TAG, "Recovering cloud subscriptions after $reason")
            restartCloudSubscriptions(effectiveSessionId)
        }
    }

    private fun prepareCloudSubscriptionSession(effectiveSessionId: String) {
        if (cloudSubscriptionSessionId == effectiveSessionId) return

        cloudSubscriptionSessionId = effectiveSessionId
        lastCloudRaceEventCreatedAt = null
        lastCloudCrossingCreatedAt = null
        pollingCrossingThumbnailIds.clear()
        pendingRemoteThumbnails.clear()
    }

    private fun rememberCloudRaceEventCursor(createdAt: String?) {
        if (createdAt == null) return
        if (lastCloudRaceEventCreatedAt == null || createdAt > lastCloudRaceEventCreatedAt!!) {
            lastCloudRaceEventCreatedAt = createdAt
        }
    }

    private fun rememberCloudCrossingCursor(createdAt: String?) {
        if (createdAt == null) return
        if (lastCloudCrossingCreatedAt == null || createdAt > lastCloudCrossingCreatedAt!!) {
            lastCloudCrossingCreatedAt = createdAt
        }
    }

    private fun handleCloudRaceEvent(event: RaceEventDto) {
        if (event.deviceId == deviceId) return
        rememberCloudRaceEventCursor(event.createdAt)

        // Deduplicate: use cloud event ID or synthetic key
        val cloudEventId = event.id ?: "cloud-${event.eventType}-${event.crossingTimeNanos}"
        if (!processedEventIds.add(cloudEventId)) {
            Log.d(TAG, "Duplicate cloud event ($cloudEventId), skipping")
            return
        }

        when (event.eventType) {
            "start" -> {
                // The event contains the source device's local clock nanos +
                // its sync offset. Convert to the server's reference clock
                // (source_local + source_offset = server_time) so that
                // onRemoteStartReceived -> toLocalTime works correctly
                // regardless of which device is server vs client.
                val serverTimeNanos = event.crossingTimeNanos +
                    (event.clockOffsetNanos ?: 0L)
                Log.i(TAG, "Remote start received via cloud relay " +
                    "(raw=${event.crossingTimeNanos}, offset=${event.clockOffsetNanos}, " +
                    "server=$serverTimeNanos)")
                onRemoteStartReceived(serverTimeNanos, event.runId)
            }
            "finish" -> {
                Log.i(TAG, "Remote finish result via cloud: split=${event.crossingTimeNanos}")
                scheduleFinishEventImageDownload(event)
                handleRemoteTimingResult(
                    TimingPayload.TimingResultBroadcast(
                        splitNanos = event.crossingTimeNanos,
                        uncertaintyMs = event.uncertaintyMs ?: _uiState.value.syncUncertaintyMs,
                        startGateId = event.clockOffsetNanos?.toString().orEmpty(),
                        finishGateId = event.deviceId
                    ),
                    runId = event.runId,
                    requiresStartReference = true
                )
            }
            else -> {
                Log.w(TAG, "EventSync: unsupported event type ${event.eventType}")
            }
        }
    }

    private fun scheduleFinishEventImageDownload(event: RaceEventDto) {
        val imagePath = event.imagePath ?: return
        val targetRunId = canonicalRunId(event.runId)
        val finishGateIndex = _uiState.value.numberOfGates.coerceAtLeast(2) - 1

        viewModelScope.launch {
            val bitmap = downloadThumbnail(imagePath) ?: return@launch
            applyRemoteCloudThumbnail(
                runId = targetRunId,
                gateRole = "finish",
                role = TimingRole.FINISH_LINE,
                gateIndex = finishGateIndex,
                thumbnail = bitmap,
                isCurrentRun = runIdentityRegistry.isSameRun(targetRunId, currentRunId)
            )
        }
    }

    private suspend fun handleCloudCrossingEvent(crossing: CrossingDto) {
        if (crossing.deviceId == deviceId) return
        rememberCloudCrossingCursor(crossing.createdAt)
        if (crossing.thumbnailUrl == null) {
            scheduleCrossingThumbnailPoll(crossing)
        }
        handleCloudCrossing(crossing)
    }

    private fun scheduleCrossingThumbnailPoll(crossing: CrossingDto) {
        val crossingId = crossing.id ?: return
        if (!pollingCrossingThumbnailIds.add(crossingId)) return

        viewModelScope.launch {
            try {
                val updated = raceEventService.pollForCrossingThumbnail(crossingId)
                if (updated != null && updated.deviceId != deviceId) {
                    handleCloudCrossing(updated)
                }
            } finally {
                pollingCrossingThumbnailIds.remove(crossingId)
            }
        }
    }

    private suspend fun handleCloudCrossing(crossing: CrossingDto) {
        val state = _uiState.value
        val roleAndIndex = parseCloudGateRole(crossing.gateRole, state.numberOfGates)
        val targetRunId = canonicalRunId(crossing.runId)
        val isCurrentRun = runIdentityRegistry.isSameRun(targetRunId, currentRunId)

        val thumbnailUrl = crossing.thumbnailUrl
        if (thumbnailUrl != null && roleAndIndex != null) {
            val bitmap = downloadThumbnail(thumbnailUrl)
            if (bitmap != null) {
                val (role, gateIndex) = roleAndIndex
                applyRemoteCloudThumbnail(
                    runId = targetRunId,
                    gateRole = crossing.gateRole,
                    role = role,
                    gateIndex = gateIndex,
                    thumbnail = bitmap,
                    isCurrentRun = isCurrentRun
                )
            }
        }

        if (!isCurrentRun) {
            Log.d(TAG, "Processed cloud crossing thumbnail for non-current run ${targetRunId.take(8)}")
            return
        }

        val latestState = _uiState.value
        if (!latestState.isHostingSession || latestState.numberOfGates <= 2 || latestState.phase != RacePhase.ACTIVE_RACE) {
            return
        }

        roleAndIndex ?: return
        val (role, gateIndex) = roleAndIndex
        val recorded = recordGateCrossing(
            RecordedGateCrossing(
                gateId = crossing.deviceId,
                role = role,
                gateIndex = gateIndex,
                timestampNanos = crossing.crossingTimeNanos,
                thumbnail = null
            )
        )
        Log.i(TAG, "Cloud crossing event: gate=$gateIndex, role=$role, recorded=$recorded")

        if (recorded) {
            tryCalculateMultiGateResult()
        }
    }

    private suspend fun applyRemoteCloudThumbnail(
        runId: String,
        gateRole: String,
        role: TimingRole,
        gateIndex: Int?,
        thumbnail: Bitmap,
        isCurrentRun: Boolean
    ) {
        if (isCurrentRun) {
            _uiState.update {
                it.copy(
                    peerThumbnail = thumbnail,
                    peerGateRole = gateRole
                )
            }
        }

        val applied = sessionRepository.applyRemoteThumbnail(
            runId = runId,
            role = role,
            gateIndex = gateIndex,
            thumbnail = thumbnail
        )
        if (!applied) {
            bufferRemoteThumbnail(runId, role, gateIndex, thumbnail)
        }
    }

    private fun bufferRemoteThumbnail(
        runId: String,
        role: TimingRole,
        gateIndex: Int?,
        thumbnail: Bitmap
    ) {
        val targetRunId = canonicalRunId(runId)
        val entries = pendingRemoteThumbnails.getOrPut(targetRunId) { mutableListOf() }
        entries.removeAll { it.role == role && it.gateIndex == gateIndex }
        entries.add(
            PendingRemoteThumbnail(
                role = role,
                gateIndex = gateIndex,
                thumbnail = thumbnail
            )
        )
        if (entries.size > MAX_PENDING_REMOTE_THUMBNAILS_PER_RUN) {
            entries.subList(0, entries.size - MAX_PENDING_REMOTE_THUMBNAILS_PER_RUN).clear()
        }
        Log.d(TAG, "Buffered remote thumbnail until run ${targetRunId.take(8)} is saved")
    }

    private fun parseCloudGateRole(gateRole: String, gateCount: Int): Pair<TimingRole, Int>? {
        val normalized = gateRole.lowercase()
        return when {
            normalized == "start" -> TimingRole.START_LINE to 0
            normalized == "finish" -> TimingRole.FINISH_LINE to (gateCount - 1)
            normalized.startsWith("split_") -> {
                val index = normalized.substringAfter("split_").toIntOrNull() ?: return null
                if (index <= 0 || index >= gateCount - 1) return null
                TimingRole.LAP_GATE to index
            }
            normalized == "lap" && gateCount > 2 -> TimingRole.LAP_GATE to 1
            else -> null
        }
    }

    private fun restartCloudSubscriptions(supabaseId: String) {
        startRaceEventSubscription(supabaseId)
        startCrossingSubscription(supabaseId)
    }

    // === Thumbnail Upload/Download ===

    private fun uploadCrossingWithThumbnail(gateRole: String, crossingTimeNanos: Long, thumbnail: Bitmap?) {
        val sid = supabaseSessionId ?: return
        val runIdForUpload = currentRunId
        viewModelScope.launch {
            val imageData = thumbnail?.toJpeg(quality = 85)
            var queued = false
            try {
                var thumbnailUrl: String? = null
                if (imageData != null) {
                    val path = "crossings/$sid/${UUID.randomUUID()}.jpg"
                    if (storageService.uploadObject("race-photos", path, imageData)) {
                        thumbnailUrl = path
                    } else {
                        queued = enqueueCrossingThumbnailRetry(
                            sessionId = sid,
                            runId = runIdForUpload,
                            gateRole = gateRole,
                            crossingTimeNanos = crossingTimeNanos,
                            imageData = imageData
                        )
                    }
                }
                raceEventService.insertCrossing(
                    CrossingDto(
                        sessionId = sid,
                        runId = runIdForUpload,
                        gateRole = gateRole,
                        deviceId = deviceId,
                        crossingTimeNanos = crossingTimeNanos,
                        thumbnailUrl = thumbnailUrl
                    )
                )
                Log.d(TAG, "Uploaded crossing with thumbnail: gate=$gateRole, url=$thumbnailUrl")
            } catch (e: Exception) {
                Log.w(TAG, "Crossing/thumbnail upload failed (non-critical): ${e.safeCloudErrorCode()}")
                if (imageData != null && !queued) {
                    enqueueCrossingThumbnailRetry(
                        sessionId = sid,
                        runId = runIdForUpload,
                        gateRole = gateRole,
                        crossingTimeNanos = crossingTimeNanos,
                        imageData = imageData
                    )
                }
            }
        }
    }

    private suspend fun enqueueCrossingThumbnailRetry(
        sessionId: String,
        runId: String,
        gateRole: String,
        crossingTimeNanos: Long,
        imageData: ByteArray
    ): Boolean {
        return try {
            thumbnailUploadQueue.enqueue(
                sessionId = sessionId,
                runId = runId,
                gateRole = gateRole,
                crossingTimeNanos = crossingTimeNanos,
                imageData = imageData
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enqueue crossing thumbnail retry: ${e.safeCloudErrorCode()}")
            false
        }
    }

    private suspend fun downloadThumbnail(url: String): Bitmap? {
        return try {
            withContext(Dispatchers.IO) {
                val resolvedUrl = storageService.resolveDownloadUrl("race-photos", url)
                val connection = java.net.URL(resolvedUrl).openConnection().apply {
                    connectTimeout = 5_000
                    readTimeout = 10_000
                }
                val bytes = ImageDownloadValidator.readValidatedImageBytes(connection)
                    ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download thumbnail: ${e.safeCloudErrorCode()}")
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

    private fun scheduleAutoResetForNewRun() {
        if (!_uiState.value.isHostingSession) return

        autoResetJob?.cancel()
        autoResetJob = viewModelScope.launch {
            delay(AUTO_RESET_DELAY_MS)
            if (_uiState.value.phase != RacePhase.RESULT) {
                Log.i(TAG, "Auto-reset skipped - no longer showing result")
                return@launch
            }

            Log.i(TAG, "Auto-resetting for next run")
            autoResetJob = null
            beginNextActiveRun(broadcast = true)
        }
    }

    // === Navigation ===

    private fun trackRaceSessionCreatedIfNeeded() {
        val state = _uiState.value
        val isHost = state.isHostingSession || entryMode == "host" || clockSyncManager.isServer.value
        if (!isHost) return

        val analyticsSessionId = supabaseSessionId ?: sessionId
        if (analyticsSessionId.isBlank() || trackedRaceSessionCreatedId == analyticsSessionId) return

        analyticsService.track(
            AnalyticsEvent.SESSION_CREATED,
            mapOf(
                "role" to state.hostRole.value,
                "number_of_gates" to state.numberOfGates,
                "distance_m" to state.distanceMeters,
                "mode" to "multi_phone"
            )
        )
        trackedRaceSessionCreatedId = analyticsSessionId
    }

    private fun trackRaceSessionCompletedIfNeeded(reason: String) {
        val analyticsSessionId = supabaseSessionId ?: sessionId
        if (analyticsSessionId.isBlank() || trackedRaceSessionCompletedId == analyticsSessionId) return

        val state = _uiState.value
        analyticsService.track(
            AnalyticsEvent.SESSION_COMPLETED,
            mapOf(
                "reason" to reason,
                "role" to (state.role?.toTimingRole()?.value ?: "none"),
                "number_of_gates" to state.numberOfGates,
                "distance_m" to state.distanceMeters,
                "run_count" to state.completedRuns.size,
                "solo_lap_count" to 0
            )
        )
        trackedRaceSessionCompletedId = analyticsSessionId
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

    fun endSessionAndReset(reason: String = "userExit") {
        trackRaceSessionCompletedIfNeeded(reason)
        bleClockSyncService.sendMessage(TimingPayload.SessionEnded(reason = reason))
        resetToStart()
    }

    fun presentSessionEndConfirmation() {
        val state = _uiState.value
        if (state.sessionEndPresentation != null || state.phase == RacePhase.PAIRING) return

        autoResetJob?.cancel()
        autoResetJob = null

        val confirmation = buildSessionEndConfirmation(state)
        val pauseResult = finishConfirmationPauseCoordinator.open(
            detectionAlreadyPaused = isDetectionEffectivelyPaused(state)
        )
        if (pauseResult.introducedPause) {
            pauseDetection(
                broadcast = true,
                eventId = checkNotNull(pauseResult.eventId)
            )
        } else {
            enforceAutomaticDetectionPause()
        }
        _uiState.update {
            it.copy(
                sessionEndPresentation = TimingSessionEndPresentation.Confirmation(confirmation)
            )
        }
        Log.i(
            TAG,
            "Session-end confirmation opened; runs=${confirmation.runCount}, " +
                "introducedPause=${pauseResult.introducedPause}"
        )
    }

    fun cancelSessionEndConfirmation() {
        if (_uiState.value.sessionEndPresentation !is TimingSessionEndPresentation.Confirmation) return

        val cancelResult = finishConfirmationPauseCoordinator.cancelLocal(
            automaticPauseRemains = automaticDetectionPauseRemains()
        )
        _uiState.update { it.copy(sessionEndPresentation = null) }

        cancelResult.eventId?.let { eventId ->
            // Cancel the synchronized confirmation pause on every phone with
            // the exact ID that introduced it. Local automatic pauses remain.
            bleClockSyncService.sendCriticalMessage(
                payload = TimingPayload.ResumeDetection(),
                eventId = eventId,
                runId = currentRunId
            )
        }
        when {
            cancelResult.shouldResumeLocally -> {
                resumeDetection(broadcast = false, eventId = cancelResult.eventId)
            }
            cancelResult.eventId != null || automaticDetectionPauseRemains() -> {
                enforceAutomaticDetectionPause()
            }
            else -> {
                // The confirmation preserved a pre-existing manual pause.
                gateEngine.pause()
            }
        }
        Log.i(
            TAG,
            "Session-end confirmation cancelled; resumed=${cancelResult.shouldResumeLocally}"
        )
    }

    fun confirmSessionEnd() {
        val confirmation = (
            _uiState.value.sessionEndPresentation as? TimingSessionEndPresentation.Confirmation
        )?.value ?: return
        val endingSessionId = sessionId
        val wasHost = _uiState.value.isHostingSession

        _uiState.update {
            it.copy(sessionEndPresentation = TimingSessionEndPresentation.Saving(isSharedSession = true))
        }
        viewModelScope.launch {
            val summary = awaitSessionEndSummary(
                endingSessionId = endingSessionId,
                expectedRunCount = confirmation.runCount,
                origin = TimingSessionEndOrigin.LOCAL
            )
            val reason = if (wasHost) "hostLeft" else "partnerLeft"
            trackRaceSessionCompletedIfNeeded(reason)
            bleClockSyncService.sendMessage(TimingPayload.SessionEnded(reason = reason))
            Log.i(TAG, "Session ended locally after securing ${summary.runCount} run(s)")
            resetToStart()
            _uiState.update {
                it.copy(sessionEndPresentation = TimingSessionEndPresentation.Completed(summary))
            }
        }
    }

    fun dismissSessionEndSummary() {
        _uiState.update { it.copy(sessionEndPresentation = null) }
    }

    private fun buildSessionEndConfirmation(
        state: RaceModeUiState = _uiState.value
    ): TimingSessionEndConfirmation {
        val completedRuns = state.completedRuns.filter { it.timeSeconds > 0.0 }
        val currentResult = state.resultTimeSeconds?.takeIf { it > 0.0 }
        val currentCanonicalRunId = canonicalRunId(currentRunId)
        val currentResultAlreadyStored = completedRuns.any { run ->
            canonicalRunId(run.id) == currentCanonicalRunId
        }
        val resultNeedsCounting = currentResult != null && !currentResultAlreadyStored
        val validTimes = buildList {
            addAll(completedRuns.map { it.timeSeconds })
            if (resultNeedsCounting) add(checkNotNull(currentResult))
        }
        return TimingSessionEndConfirmation(
            isSharedSession = true,
            runCount = completedRuns.size + if (resultNeedsCounting) 1 else 0,
            bestTime = validTimes.minOrNull()
        )
    }

    private suspend fun awaitSessionEndSummary(
        endingSessionId: String,
        expectedRunCount: Int,
        origin: TimingSessionEndOrigin
    ): TimingSessionEndSummary {
        val runsFlow = sessionRepository.getRunsForSession(endingSessionId)
        val runs = if (expectedRunCount > 0) {
            withTimeoutOrNull(LOCAL_SESSION_SAVE_TIMEOUT_MS) {
                runsFlow.first { values ->
                    values.count { it.timeSeconds > 0.0 } >= expectedRunCount
                }
            } ?: runsFlow.first()
        } else {
            runsFlow.first()
        }
        val completedRuns = runs.filter { it.timeSeconds > 0.0 }
        val savedSessionId = if (
            completedRuns.isNotEmpty() && sessionRepository.getSession(endingSessionId) != null
        ) {
            endingSessionId
        } else {
            null
        }
        if (completedRuns.size < expectedRunCount) {
            Log.w(
                TAG,
                "Session-end local save timed out: expected=$expectedRunCount, stored=${completedRuns.size}"
            )
        }
        return TimingSessionEndSummary(
            origin = origin,
            runCount = completedRuns.size,
            bestTime = completedRuns.minOfOrNull { it.timeSeconds },
            savedSessionId = savedSessionId,
            isGuest = isGuestJoinMode
        )
    }

    private fun isDetectionEffectivelyPaused(state: RaceModeUiState = _uiState.value): Boolean =
        state.raceStatus == "paused" ||
            automaticDetectionPauseRemains() ||
            finishConfirmationPauseCoordinator.blocksNormalResume

    fun resetToStart() {
        endLiveTimingWorkloadIfNeeded()
        stopTimerTick()
        clearForegroundGateReadinessRecovery()
        autoResetJob?.cancel()
        autoResetJob = null
        cancelMultiGateTimeout()
        cancelStartEventWaitTimeout()
        gateCalibrationJob?.cancel()
        gateCalibrationJob = null
        raceEventSubscriptionJob?.cancel()
        raceEventSubscriptionJob = null
        crossingSubscriptionJob?.cancel()
        crossingSubscriptionJob = null
        cloudSubscriptionRecoveryJob?.cancel()
        cloudSubscriptionRecoveryJob = null
        activeSessionRunsJob?.cancel()
        activeSessionRunsJob = null
        trackedRaceSessionCreatedId = null
        trackedRaceSessionCompletedId = null
        supabaseSessionId = null
        clockSyncManager.stop()
        gateEngine.stopMotionUpdates()
        gateEngine.reset()
        cameraManager.closeCamera()
        localGateFrameBuffer.reset()
        crossingThumbnailBuffer.reset()

        localStartTimeNanos = null
        localFinishTimeNanos = null
        remoteStartTimeNanos = null
        pausedSinceNanos = 0L
        appBackgroundPausedDetection = false
        appBackgroundedDuringStartedRun = false
        bufferedFinishTimeNanos = null
        bufferedFinishThumbnail = null
        finishThumbnail = null
        localStartCalibration = null
        localFinishCalibration = null
        lastBroadcastCalibrationUpdate = null
        processedEventIds.clear()
        clearEnvelopeDedupeState()
        clearResultDedupeState()
        cloudSubscriptionSessionId = null
        lastCloudRaceEventCreatedAt = null
        lastCloudCrossingCreatedAt = null
        pollingCrossingThumbnailIds.clear()
        pendingRemoteThumbnails.clear()
        reconnectReplayJobs.values.forEach { it.cancel() }
        reconnectReplayJobs.clear()
        pendingPeerReconnects.clear()
        knownPeerDeviceIds.clear()
        receivedGateCrossings.clear()
        remoteThumbnailMetadataByEventId.clear()
        syncableEventLog.clear()
        lastEventSyncAtBySender.clear()
        syncableEventSeq = 0L
        frameCount = 0
        finishConfirmationPauseCoordinator.reset()
        sessionStateRecoveryService.clearActiveSession()

        _uiState.update {
            RaceModeUiState(
                startType = initialStartType,
                distanceMeters = initialDistanceMeters,
                numberOfGates = preConfiguredNumberOfGates,
                gateDistances = initialGateDistances
            )
        }
        viewModelScope.launch {
            processUploadQueuesIfIdle()
        }
    }

    fun startNewRace() {
        val state = _uiState.value
        if (!state.isHostingSession) {
            Log.w(TAG, "Ignoring local new-run request on non-host device")
            return
        }

        beginNextActiveRun(broadcast = true)
    }

    fun cancelCurrentRun() {
        cancelCurrentRun(broadcast = true)
    }

    private fun cancelCurrentRun(broadcast: Boolean) {
        val canceledRunId = currentRunId
        Log.i(TAG, "Canceling current run: ${canceledRunId.take(8)}, broadcast=$broadcast")

        autoResetJob?.cancel()
        autoResetJob = null
        cancelMultiGateTimeout()
        stopTimerTick()
        cancelStartEventWaitTimeout()

        localStartTimeNanos = null
        localFinishTimeNanos = null
        remoteStartTimeNanos = null
        pausedSinceNanos = 0L
        appBackgroundPausedDetection = false
        appBackgroundedDuringStartedRun = false
        bufferedFinishTimeNanos = null
        bufferedFinishThumbnail = null
        finishThumbnail = null
        localStartCalibration = null
        localFinishCalibration = null
        lastBroadcastCalibrationUpdate = null
        processedEventIds.clear()
        clearResultDedupeState()
        receivedGateCrossings.clear()
        localGateFrameBuffer.reset()
        remoteThumbnailMetadataByEventId.clear()
        syncableEventLog.remove(canceledRunId)
        currentRunId = UUID.randomUUID().toString()

        if (_uiState.value.requiresLocalCamera) {
            gateEngine.reset()
            gateEngine.resume()
        }

        _uiState.update {
            it.copy(
                phase = RacePhase.ACTIVE_RACE,
                raceStatus = "waiting",
                elapsedTimeSeconds = 0.0,
                resultTimeSeconds = null,
                resultUncertaintyMs = null,
                resultSegments = emptyList(),
                receivedGateCount = 0,
                peerThumbnail = null,
                peerGateRole = null,
                errorMessage = null
            )
        }

        if (broadcast) {
            bleClockSyncService.sendCriticalMessage(
                TimingPayload.CancelRun(),
                runId = canceledRunId
            )
        }
    }

    private suspend fun processUploadQueuesIfIdle() {
        if (workloadCoordinator.isLiveTimingActive) return
        sessionRepository.processPendingCloudUploads()
        thumbnailUploadQueue.processQueue()
        crossingDebugUploadQueue.processQueue()
    }

    private fun beginNextActiveRun(
        runId: String = UUID.randomUUID().toString(),
        broadcast: Boolean
    ) {
        beginActiveSession(
            runId = runId,
            announceToPeers = false
        )
        currentRunNumber += 1

        if (broadcast) {
            bleClockSyncService.sendCriticalMessage(
                TimingPayload.NewRun(),
                runId = currentRunId
            )
            bleClockSyncService.sendCriticalMessage(
                TimingPayload.ResumeDetection(),
                runId = currentRunId
            )
        }
    }

    private fun returnToRaceReadyForNewRun() {
        autoResetJob?.cancel()
        autoResetJob = null
        cancelMultiGateTimeout()
        currentRunId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                phase = RacePhase.RACE_READY,
                resultTimeSeconds = null,
                resultUncertaintyMs = null,
                resultSegments = emptyList(),
                receivedGateCount = 0,
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
        pausedSinceNanos = 0L
        appBackgroundPausedDetection = false
        appBackgroundedDuringStartedRun = false
        bufferedFinishTimeNanos = null
        bufferedFinishThumbnail = null
        finishThumbnail = null
        localStartCalibration = null
        localFinishCalibration = null
        processedEventIds.clear()
        clearResultDedupeState()
        receivedGateCrossings.clear()
        remoteThumbnailMetadataByEventId.clear()
        cancelStartEventWaitTimeout()
        gateEngine.reset()
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun saveActiveSessionSnapshot() {
        val state = _uiState.value
        if (state.phase == RacePhase.RESULT) {
            sessionStateRecoveryService.clearActiveSession()
            return
        }

            val role = state.gateAssignment?.role?.value
            ?: state.role?.name?.lowercase()
            ?: "unknown"

        sessionStateRecoveryService.saveActiveSession(
            PersistedSessionState(
                savedAtMillis = System.currentTimeMillis(),
                sessionId = supabaseSessionId ?: sessionId,
                role = role,
                runNumber = currentRunNumber,
                timerStartTimeNanos = localStartTimeNanos,
                resilientCrossingTimestampNanos = localFinishTimeNanos ?: bufferedFinishTimeNanos,
                peerDeviceIds = bleClockSyncService.connectedDeviceIds(),
                distance = state.distanceMeters,
                startType = state.startType,
                numberOfGates = state.numberOfGates,
                isHost = state.isHostingSession
            )
        )
    }

    // === Distance ===

    fun setActiveAthlete(athleteId: String) {
        val state = _uiState.value
        if (athleteId !in state.selectedAthleteIds) {
            Log.w(TAG, "Ignoring active athlete outside this session: ${athleteId.take(8)}")
            return
        }

        activeAthleteId = athleteId
        _uiState.update { it.copy(activeAthleteId = athleteId) }

        val athleteName = state.athletes.firstOrNull { it.id == athleteId }?.displayName ?: athleteId.take(8)
        Log.i(TAG, "Active race athlete changed: $athleteName")
    }

    fun applySessionSettings(
        startTypeRaw: String,
        distanceMeters: Double,
        gateDistances: Map<Int, Double>,
        selectedAthleteIds: Set<String>,
        activeAthleteId: String?
    ) {
        val state = _uiState.value
        if (!state.isHostingSession) {
            Log.w(TAG, "Ignoring session settings change on non-host device")
            return
        }

        val startType = StartType.fromRawValue(startTypeRaw)
        val availableAthleteIds = state.athletes.map { it.id }.toSet()
        val sanitizedAthleteIds = selectedAthleteIds.intersect(availableAthleteIds)
        val resolvedActiveAthleteId = activeAthleteId
            ?.takeIf { it in sanitizedAthleteIds }
            ?: sanitizedAthleteIds.firstOrNull()
        val gateCount = state.numberOfGates.coerceAtLeast(2)
        val requestedDistance = distanceMeters.coerceAtLeast(1.0)
        val normalizedDistances = normalizeGateDistances(
            gateDistances = gateDistances.takeIf { it.isNotEmpty() }
                ?: defaultGateDistances(gateCount, requestedDistance),
            gateCount = gateCount,
            fallbackTotalDistanceMeters = requestedDistance
        )
        val finishIndex = gateCount - 1
        val finishDistance = normalizedDistances[finishIndex]?.coerceAtLeast(1.0) ?: requestedDistance
        val startTypeChanged = state.startType != startType.rawValue
        val distancesChanged = !sameGateDistances(state.gateDistances, normalizedDistances, gateCount) ||
            kotlin.math.abs(state.distanceMeters - finishDistance) > 0.001

        sessionSelectedAthleteIds = sanitizedAthleteIds
        sessionAthletes = state.athletes.filter { it.id in sanitizedAthleteIds }
        this.activeAthleteId = resolvedActiveAthleteId

        _uiState.update { current ->
            val localDistance = current.localGateIndex?.let { normalizedDistances[it] }
            val updatedAssignment = current.gateAssignment?.let { assignment ->
                normalizedDistances[assignment.gateIndex]?.let { distance ->
                    assignment.copy(distanceFromStart = distance)
                } ?: assignment
            }

            current.copy(
                startType = startType.rawValue,
                distanceMeters = finishDistance,
                gateDistances = normalizedDistances,
                selectedAthleteIds = sanitizedAthleteIds,
                activeAthleteId = resolvedActiveAthleteId,
                gateAssignment = updatedAssignment,
                localGateDistanceMeters = localDistance ?: current.localGateDistanceMeters
            )
        }

        if (startTypeChanged) {
            ensureDesiredCameraFacing()
            bleClockSyncService.sendCriticalMessage(
                TimingPayload.StartTypeChanged(startType.rawValue),
                runId = currentRunId.takeIf { _uiState.value.phase == RacePhase.ACTIVE_RACE }
            )
        }

        if (distancesChanged) {
            bleClockSyncService.sendCriticalMessage(
                TimingPayload.DistanceConfigChanged(normalizedDistances),
                runId = currentRunId.takeIf { _uiState.value.phase == RacePhase.ACTIVE_RACE }
            )
        }

        Log.i(
            TAG,
            "Session settings applied: ${startType.displayName}, ${finishDistance}m, ${sanitizedAthleteIds.size} athletes"
        )
    }

    fun setStartType(startTypeRaw: String) {
        val state = _uiState.value
        if (!state.isHostingSession) {
            Log.w(TAG, "Ignoring start type change on non-host device")
            return
        }

        val startType = StartType.fromRawValue(startTypeRaw)
        _uiState.update { it.copy(startType = startType.rawValue) }
        ensureDesiredCameraFacing()
        bleClockSyncService.sendCriticalMessage(
            TimingPayload.StartTypeChanged(startType.rawValue),
            runId = currentRunId.takeIf { _uiState.value.phase == RacePhase.ACTIVE_RACE }
        )
        Log.i(TAG, "Start type changed by host: ${startType.displayName}")
    }

    fun setDistance(distance: Double) {
        val state = _uiState.value
        if (!state.isHostingSession) {
            Log.w(TAG, "Ignoring distance change on non-host device")
            return
        }

        val gateCount = state.numberOfGates.coerceAtLeast(2)
        val scaledDistances = scaledGateDistances(
            gateCount = gateCount,
            totalDistanceMeters = distance,
            currentGateDistances = state.gateDistances
        )
        applyGateDistances(scaledDistances, broadcast = true)
    }

    fun setSegmentDistance(fromGateIndex: Int, toGateIndex: Int, segmentDistanceMeters: Double) {
        val state = _uiState.value
        if (!state.isHostingSession) {
            Log.w(TAG, "Ignoring segment distance change on non-host device")
            return
        }

        val gateCount = state.numberOfGates.coerceAtLeast(2)
        val finishIndex = gateCount - 1
        val fromIndex = fromGateIndex.coerceIn(0, finishIndex - 1)
        val toIndex = toGateIndex.coerceIn(1, finishIndex)
        if (toIndex != fromIndex + 1) {
            Log.w(TAG, "Ignoring non-adjacent segment update: $fromGateIndex->$toGateIndex")
            return
        }

        val currentDistances = normalizeGateDistances(
            gateDistances = state.gateDistances,
            gateCount = gateCount,
            fallbackTotalDistanceMeters = state.distanceMeters
        ).toMutableMap()
        val oldSegmentDistance = (currentDistances[toIndex] ?: 0.0) - (currentDistances[fromIndex] ?: 0.0)
        val delta = segmentDistanceMeters.coerceAtLeast(1.0) - oldSegmentDistance

        for (index in toIndex..finishIndex) {
            currentDistances[index] = (currentDistances[index] ?: 0.0) + delta
        }

        applyGateDistances(currentDistances, broadcast = true)
    }

    private fun applyGateDistances(
        gateDistances: Map<Int, Double>,
        broadcast: Boolean
    ) {
        val state = _uiState.value
        val gateCount = state.numberOfGates.coerceAtLeast(2)
        val normalizedDistances = normalizeGateDistances(
            gateDistances = gateDistances,
            gateCount = gateCount,
            fallbackTotalDistanceMeters = state.distanceMeters
        )
        val finishIndex = gateCount - 1
        val finishDistance = normalizedDistances[finishIndex] ?: state.distanceMeters

        _uiState.update { current ->
            val localDistance = current.localGateIndex?.let { normalizedDistances[it] }
            val updatedAssignment = current.gateAssignment?.let { assignment ->
                normalizedDistances[assignment.gateIndex]?.let { distance ->
                    assignment.copy(distanceFromStart = distance)
                } ?: assignment
            }

            current.copy(
                distanceMeters = finishDistance,
                gateDistances = normalizedDistances,
                gateAssignment = updatedAssignment,
                localGateDistanceMeters = localDistance ?: current.localGateDistanceMeters
            )
        }

        if (broadcast && _uiState.value.isHostingSession) {
            bleClockSyncService.sendCriticalMessage(
                TimingPayload.DistanceConfigChanged(normalizedDistances),
                runId = currentRunId.takeIf { _uiState.value.phase == RacePhase.ACTIVE_RACE }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTimerTick()
        autoResetJob?.cancel()
        cancelMultiGateTimeout()
        cancelStartEventWaitTimeout()
        raceEventSubscriptionJob?.cancel()
        crossingSubscriptionJob?.cancel()
        cloudSubscriptionRecoveryJob?.cancel()
        clearForegroundGateReadinessRecovery()
        reconnectReplayJobs.values.forEach { it.cancel() }
        reconnectReplayJobs.clear()
        clockSyncManager.stop()
        gateEngine.stopMotionUpdates()
        cameraManager.closeCamera()
        gateEngine.reset()
        crossingThumbnailBuffer.reset()
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
    LAP,
    FINISH,
    CONTROL
}

data class RaceModeUiState(
    val phase: RacePhase = RacePhase.PAIRING,
    val role: DeviceRole? = null,
    val startType: String = "flying",
    val hostRole: TimingRole = TimingRole.FINISH_LINE,

    // Pairing
    val isJoinMode: Boolean = false,
    val isHostingSession: Boolean = false,
    val pairingStatus: String = "",
    val isDeviceConnected: Boolean = false,
    val connectedDeviceCount: Int = 0,
    val syncedDeviceCount: Int = 0,
    val connectedDeviceName: String = "",

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
    val startSoundType: String = StartSoundType.BEEP.rawValue,
    val showSpeedInResults: Boolean = SettingsRepository.Defaults.SHOW_SPEED_IN_RESULTS,
    val speedUnit: String = SettingsRepository.Defaults.SPEED_UNIT,
    val detectionDiagnosticsEnabled: Boolean = SettingsRepository.Defaults.DETECTION_DIAGNOSTICS_ENABLED,
    val preStartDelayMin: Float = SettingsRepository.Defaults.PRE_START_DELAY_MIN,
    val preStartDelayMax: Float = SettingsRepository.Defaults.PRE_START_DELAY_MAX,
    val marksSetDelayMin: Float = SettingsRepository.Defaults.MARKS_SET_DELAY_MIN,
    val setGoHoldMin: Float = SettingsRepository.Defaults.SET_GO_HOLD_MIN,
    val includeReadyCommand: Boolean = SettingsRepository.Defaults.INCLUDE_READY_COMMAND,
    val voiceProvider: String = SettingsRepository.Defaults.VOICE_PROVIDER,
    val elevenLabsVoice: String = SettingsRepository.Defaults.ELEVEN_LABS_VOICE,
    val voiceGender: String = SettingsRepository.Defaults.VOICE_GENDER,
    val appLanguage: String = SettingsRepository.Defaults.APP_LANGUAGE,

    // Race
    val distanceMeters: Double = 60.0,
    val numberOfGates: Int = 2,
    val gateDistances: Map<Int, Double> = mapOf(0 to 0.0, 1 to 60.0),
    val athletes: List<AthleteEntity> = emptyList(),
    val selectedAthleteIds: Set<String> = emptySet(),
    val activeAthleteId: String? = null,
    val gateAssignment: GateAssignment? = null,
    val localGateIndex: Int? = null,
    val localGateDistanceMeters: Double? = null,
    val localGateStatus: GateStatusInfo = defaultGateStatus(),
    val remoteGateStatuses: Map<String, GateStatusInfo> = emptyMap(),
    val remoteArmedGateIds: Set<String> = emptySet(),
    val isLocalGateCalibrating: Boolean = false,
    val receivedGateCount: Int = 0,
    val raceStatus: String = "",
    val elapsedTimeSeconds: Double = 0.0,
    val resultTimeSeconds: Double? = null,
    val resultUncertaintyMs: Double? = null,
    val resultSegments: List<SegmentSplit> = emptyList(),
    val completedRuns: List<RunEntity> = emptyList(),
    val sessionEndPresentation: TimingSessionEndPresentation? = null,

    // Voice start
    val voiceStartPhase: VoiceStartPhase = VoiceStartPhase.IDLE,

    // Peer thumbnail (received via Supabase Realtime crossing sync)
    val peerThumbnail: Bitmap? = null,
    val peerGateRole: String? = null,

    // Error
    val errorMessage: String? = null
) {
    val requiredPhysicalDeviceCount: Int
        get() = if (isHostingSession && hostRole == TimingRole.CONTROL_ONLY) {
            numberOfGates.coerceAtLeast(2) + 1
        } else {
            numberOfGates.coerceAtLeast(2)
        }

    val requiresLocalCamera: Boolean
        get() = role != DeviceRole.CONTROL

    val isLocalGateReady: Boolean
        get() = role == DeviceRole.CONTROL || !requiresLocalCamera || localGateStatus.isReady

    val requiredRemoteReadyGateCount: Int
        get() = (requiredPhysicalDeviceCount - 1).coerceAtLeast(0)

    val isRemoteGateReadinessSatisfied: Boolean
        get() = remoteArmedGateIds.size >= requiredRemoteReadyGateCount

    val canStartSession: Boolean
        get() = isHostingSession && isLocalGateReady && isRemoteGateReadinessSatisfied
}

private data class RecordedGateCrossing(
    val gateId: String,
    val role: TimingRole,
    val gateIndex: Int,
    val timestampNanos: Long,
    val thumbnail: Bitmap?,
    val calibration: LocalGateCalibration? = null
)

private data class LocalGateCalibration(
    val role: TimingRole,
    val gateIndex: Int?,
    val gatePosition: Float,
    val velocityPxPerSec: Float,
    val crossingDirection: String?,
    val workWidth: Int?,
    val thumbnailDebug: JsonElement? = null
)

private data class RemoteGateCalibration(
    val role: TimingRole,
    val gateIndex: Int?,
    val gatePosition: Float,
    val velocityPxPerSec: Float,
    val crossingDirection: String?,
    val workWidth: Int?,
    val thumbnailDebugJson: String?
)

private data class PendingRemoteThumbnail(
    val role: TimingRole,
    val gateIndex: Int?,
    val thumbnail: Bitmap
)

private data class RecentStartSnapshot(
    val runId: String,
    val runNumber: Int,
    val startReferenceNanos: Long,
    val localStartNanos: Long,
    val capturedAtMillis: Long,
    val distanceMeters: Double,
    val startType: String,
    val numberOfGates: Int,
    val gateDistances: Map<Int, Double>,
    val gatePosition: Float,
    val athleteId: String?
)

private data class StartEventWaitPolicy(
    val timeoutMs: Long,
    val warningAfterMs: Long?,
    val recoveryAfterMs: Long?,
    val reason: String
)

internal fun defaultGateDistances(
    gateCount: Int,
    totalDistanceMeters: Double
): Map<Int, Double> {
    val finishIndex = (gateCount - 1).coerceAtLeast(1)
    val totalDistance = totalDistanceMeters.coerceAtLeast(1.0)
    return (0..finishIndex).associateWith { gateIndex ->
        if (gateIndex == 0) {
            0.0
        } else {
            totalDistance * gateIndex.toDouble() / finishIndex.toDouble()
        }
    }
}

internal fun parseGateDistances(
    rawValue: String?,
    gateCount: Int,
    fallbackTotalDistanceMeters: Double
): Map<Int, Double> {
    val parsedDistances = rawValue
        ?.split(",")
        ?.mapNotNull { it.toDoubleOrNull() }
        ?.takeIf { it.size >= gateCount.coerceAtLeast(1) }

    if (parsedDistances.isNullOrEmpty()) {
        return defaultGateDistances(gateCount, fallbackTotalDistanceMeters)
    }

    val finishIndex = (gateCount - 1).coerceAtLeast(1)
    val distances = (0..finishIndex).associateWith { gateIndex ->
        parsedDistances.getOrNull(gateIndex)
            ?: (fallbackTotalDistanceMeters * gateIndex.toDouble() / finishIndex.toDouble())
    }

    return normalizeGateDistances(
        gateDistances = distances,
        gateCount = gateCount,
        fallbackTotalDistanceMeters = fallbackTotalDistanceMeters
    )
}

private fun parseTimingRole(rawValue: String?): TimingRole? {
    val normalized = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return TimingRole.entries.firstOrNull { role ->
        role.value.equals(normalized, ignoreCase = true) ||
            role.name.equals(normalized, ignoreCase = true)
    }
}

internal fun scaledGateDistances(
    gateCount: Int,
    totalDistanceMeters: Double,
    currentGateDistances: Map<Int, Double>
): Map<Int, Double> {
    val finishIndex = (gateCount - 1).coerceAtLeast(1)
    if (finishIndex == 1) {
        return defaultGateDistances(gateCount, totalDistanceMeters)
    }

    val current = normalizeGateDistances(
        gateDistances = currentGateDistances,
        gateCount = gateCount,
        fallbackTotalDistanceMeters = totalDistanceMeters
    )
    val oldTotal = current[finishIndex]?.takeIf { it > 0.0 } ?: totalDistanceMeters
    val scale = totalDistanceMeters.coerceAtLeast(1.0) / oldTotal
    return current.mapValues { (gateIndex, distance) ->
        if (gateIndex == 0) 0.0 else distance * scale
    }
}

internal fun normalizeGateDistances(
    gateDistances: Map<Int, Double>,
    gateCount: Int,
    fallbackTotalDistanceMeters: Double
): Map<Int, Double> {
    val finishIndex = (gateCount - 1).coerceAtLeast(1)
    val fallback = defaultGateDistances(gateCount, fallbackTotalDistanceMeters)
    val normalized = linkedMapOf<Int, Double>()
    var previousDistance = 0.0

    normalized[0] = 0.0
    for (gateIndex in 1..finishIndex) {
        val rawDistance = gateDistances[gateIndex] ?: fallback[gateIndex] ?: previousDistance + 1.0
        val distance = max(previousDistance + 1.0, rawDistance)
        normalized[gateIndex] = distance
        previousDistance = distance
    }

    return normalized
}

internal fun sameGateDistances(
    first: Map<Int, Double>,
    second: Map<Int, Double>,
    gateCount: Int
): Boolean {
    for (gateIndex in 0 until gateCount.coerceAtLeast(2)) {
        if (kotlin.math.abs((first[gateIndex] ?: 0.0) - (second[gateIndex] ?: 0.0)) > 0.001) {
            return false
        }
    }
    return true
}

internal data class MultiGateCrossingTime(
    val gateId: String,
    val gateIndex: Int,
    val timestampNanos: Long
)

internal fun buildMultiGateSegmentSplits(
    orderedCrossings: List<MultiGateCrossingTime>,
    gateDistances: Map<Int, Double>,
    gateCount: Int,
    fallbackTotalDistanceMeters: Double = 0.0
): List<SegmentSplit> {
    if (orderedCrossings.size < 2) return emptyList()
    val finishIndex = (gateCount - 1).coerceAtLeast(1)
    val finishDistance = gateDistances[finishIndex] ?: fallbackTotalDistanceMeters
    fun distance(gateIndex: Int): Double = gateDistances[gateIndex]
        ?: (finishDistance * gateIndex.toDouble() / finishIndex.toDouble())

    var cumulativeSplitNanos = 0L
    return orderedCrossings.zipWithNext { fromGate, toGate ->
        val splitNanos = toGate.timestampNanos - fromGate.timestampNanos
        cumulativeSplitNanos += splitNanos
        val toDistance = distance(toGate.gateIndex)
        SegmentSplit(
            fromGateIndex = fromGate.gateIndex,
            toGateIndex = toGate.gateIndex,
            fromGateId = fromGate.gateId,
            toGateId = toGate.gateId,
            splitNanos = splitNanos,
            distanceMeters = max(0.0, toDistance - distance(fromGate.gateIndex)),
            cumulativeSplitNanos = cumulativeSplitNanos,
            cumulativeDistanceMeters = toDistance
        )
    }
}

private fun TimingRole.toDeviceRole(): DeviceRole {
    return when (this) {
        TimingRole.START_LINE -> DeviceRole.START
        TimingRole.LAP_GATE -> DeviceRole.LAP
        TimingRole.FINISH_LINE -> DeviceRole.FINISH
        TimingRole.CONTROL_ONLY -> DeviceRole.CONTROL
    }
}

private fun DeviceRole.toTimingRole(): TimingRole {
    return when (this) {
        DeviceRole.START -> TimingRole.START_LINE
        DeviceRole.LAP -> TimingRole.LAP_GATE
        DeviceRole.FINISH -> TimingRole.FINISH_LINE
        DeviceRole.CONTROL -> TimingRole.CONTROL_ONLY
    }
}

private fun defaultGateStatus(gatePosition: Double = 0.5): GateStatusInfo {
    return GateStatusInfo(
        isCalibrated = false,
        isArmed = false,
        isClear = false,
        isPrebufferReady = false,
        isStable = false,
        gatePosition = gatePosition,
        batteryLevel = null
    )
}
