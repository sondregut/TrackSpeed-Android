package com.trackspeed.android.onephone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.detection.PhoneMotionStabilityPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

enum class SingleCameraReadiness { IDLE, NEEDS_PERMISSION, STARTING, READY, FAILED }

data class SingleCameraSprintUiState(
    val configuration: SingleCameraSprintConfiguration,
    val gates: List<SingleCameraSprintGate>,
    val direction: SingleCameraSprintDirection = SingleCameraSprintDirection.LEFT_TO_RIGHT,
    val readiness: SingleCameraReadiness = SingleCameraReadiness.IDLE,
    val phase: SingleCameraSprintPhase = SingleCameraSprintPhase.Calibrating,
    val bodyObservation: SingleCameraBodyObservation? = null,
    val isBodyVisibleOutsideTimingZone: Boolean = false,
    val splits: List<SingleCameraSprintSplit> = emptyList(),
    val result: SingleCameraSprintResult? = null,
    val liveElapsedSeconds: Double = 0.0,
    /** Camera-domain start (elapsed-realtime seconds) for smooth timer display. */
    val liveStartTimestamp: Double? = null,
    val sessionRuns: List<SingleCameraSprintRunRecord> = emptyList(),
    val currentRunId: String? = null,
    val phoneMotionWarningVisible: Boolean = false,
    val errorReason: SingleCameraInvalidReason? = null,
    val isFrontCamera: Boolean = false,
    val sensorOrientation: Int = 90,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val processedPoseFps: Double = 0.0,
    val hasAdjustedGates: Boolean = false,
    val activeGateDistance: Double? = null,
    val savedRunCount: Int = 0,
    val persistenceFailed: Boolean = false
) {
    val canArm: Boolean
        get() = readiness == SingleCameraReadiness.READY &&
            phase == SingleCameraSprintPhase.Calibrating &&
            (configuration.startStyle != SingleCameraSprintStartStyle.FLYING ||
                SingleCameraSprintFraming.assessment(gates, direction)?.isReady == true)

    val isConfigurationEditable: Boolean
        get() = phase == SingleCameraSprintPhase.Calibrating && sessionRuns.isEmpty()

    val currentRun: SingleCameraSprintRunRecord?
        get() = sessionRuns.firstOrNull { it.id == currentRunId }

    /** Upright camera aspect ratio (width / height) of the analysed frame. */
    val videoAspectRatio: Float
        get() {
            if (previewWidth <= 0 || previewHeight <= 0) return 16f / 9f
            val long = maxOf(previewWidth, previewHeight).toFloat()
            val short = minOf(previewWidth, previewHeight).toFloat()
            return long / short
        }

    fun isBestSessionRun(run: SingleCameraSprintRunRecord): Boolean =
        sessionRuns.filter {
            kotlin.math.abs(it.distanceMeters - run.distanceMeters) < 0.01 &&
                it.result.startStyle == run.result.startStyle &&
                it.result.drill == run.result.drill &&
                it.result.quality != SingleCameraSprintQuality.REVIEW
        }.minByOrNull { it.result.totalTimeSeconds }?.id == run.id
}

/**
 * Owns the one-phone landscape capture path (iOS `SingleCameraSprintController`).
 * Camera2 frames feed ML Kit on one worker that always processes the newest
 * frame; the timing engine stays on the camera timestamp domain even when pose
 * throughput drops below the capture frame rate.
 */
@HiltViewModel
class SingleCameraSprintViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val cameraManager: CameraManager,
    private val sessionRepository: SessionRepository
) : ViewModel(), SensorEventListener {

    private companion object {
        const val TAG = "SingleCameraSprint"
        const val POSE_DISPLAY_GRACE_SECONDS = 0.18
        const val MOTION_GRACE_SECONDS = 0.8
    }

    private val athleteId: String? = savedStateHandle.get<String>("athleteIds")
        ?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    private val sessionId = UUID.randomUUID().toString()

    private val initialConfiguration = SingleCameraSprintConfiguration.create(
        distanceMeters = savedStateHandle.get<String>("distance")?.toDoubleOrNull() ?: 10.0,
        startStyle = SingleCameraSprintStartStyle.fromRaw(savedStateHandle.get<String>("startStyle")),
        drill = SingleCameraSprintDrill.fromRaw(savedStateHandle.get<String>("drill"))
    )

    private val _uiState = MutableStateFlow(
        SingleCameraSprintUiState(
            configuration = initialConfiguration,
            gates = initialConfiguration.defaultGates()
        )
    )
    val uiState: StateFlow<SingleCameraSprintUiState> = _uiState.asStateFlow()

    private val engine = SingleCameraSprintTimingEngine()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Pose worker: newest-frame-wins slot processed on one thread.
    private val poseExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SingleCameraPose").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val pendingFrame = AtomicReference<CameraManager.FrameData?>(null)
    private val skippedFrames = AtomicLong(0)
    private val trackingGeneration = AtomicLong(0)
    private var poseDetector: SingleCameraPoseDetector? = null
    private val processedTimestamps = ArrayDeque<Double>()

    // Guarded by trackingLock; read on the pose worker.
    private val trackingLock = Any()
    private val selectionCoordinator = SingleCameraAthleteSelectionCoordinator()
    private var targetStartX = 0.14
    private var trackingDirection = SingleCameraSprintDirection.LEFT_TO_RIGHT
    private var initialDirectedXRange: ClosedFloatingPointRange<Double>? = null

    @Volatile private var displayRotationDegrees = 90
    private var previewSurface: Surface? = null

    private var activeGates: List<SingleCameraSprintGate> = emptyList()
    private var activeDirection = SingleCameraSprintDirection.LEFT_TO_RIGHT
    private var activeStartStyle = SingleCameraSprintStartStyle.STANDING
    private var activeDrill = SingleCameraSprintDrill.SPRINT
    private var activeRunId: String? = null

    // Phone motion (iOS Core Motion equivalent).
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val phoneMotionWarningState = SingleCameraPhoneMotionWarningState()
    private var motionGraceDeadlineNanos = 0L
    private var latestAccelerationG = 0.0
    private var latestRotationRadPerSecond = 0.0
    private var isMonitoringMotion = false

    init {
        val state = _uiState.value
        updateAcquisitionTarget(state.gates, state.direction, state.configuration)
        viewModelScope.launch {
            cameraManager.cameraState.collect { cameraState ->
                when (cameraState) {
                    is CameraManager.CameraState.Error -> _uiState.update {
                        it.copy(readiness = SingleCameraReadiness.FAILED)
                    }
                    is CameraManager.CameraState.Open, CameraManager.CameraState.Capturing -> _uiState.update {
                        if (it.readiness == SingleCameraReadiness.STARTING) {
                            it.copy(readiness = SingleCameraReadiness.READY)
                        } else {
                            it
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    // region Camera lifecycle

    fun onPermissionResult(granted: Boolean) {
        if (!granted) {
            _uiState.update { it.copy(readiness = SingleCameraReadiness.NEEDS_PERMISSION) }
            return
        }
        if (_uiState.value.readiness == SingleCameraReadiness.READY) return
        val initialized = cameraManager.initialize(useFrontCamera = _uiState.value.isFrontCamera)
        if (!initialized) {
            _uiState.update { it.copy(readiness = SingleCameraReadiness.FAILED) }
            return
        }
        publishCameraGeometry(SingleCameraReadiness.STARTING)
        previewSurface?.let { openCamera(it) }
    }

    fun onSurfaceReady(surface: Surface) {
        previewSurface = surface
        if (_uiState.value.readiness == SingleCameraReadiness.STARTING ||
            _uiState.value.readiness == SingleCameraReadiness.READY
        ) {
            openCamera(surface)
        }
    }

    fun onSurfaceDestroyed() {
        previewSurface = null
        cameraManager.closeCamera()
        if (_uiState.value.phase.isRunInProgress) {
            handle(engine.invalidateExternally(SingleCameraInvalidReason.CAMERA_INTERRUPTED))
            syncEngineState()
        }
        if (_uiState.value.readiness == SingleCameraReadiness.READY) {
            _uiState.update { it.copy(readiness = SingleCameraReadiness.STARTING) }
        }
    }

    fun retryCamera() {
        _uiState.update { it.copy(readiness = SingleCameraReadiness.IDLE) }
        onPermissionResult(granted = true)
    }

    fun setDisplayRotation(degrees: Int) {
        displayRotationDegrees = degrees
    }

    fun switchCamera() {
        if (_uiState.value.phase != SingleCameraSprintPhase.Calibrating) return
        val surface = previewSurface ?: return
        advanceTrackingGeneration()
        cameraManager.switchCamera(surface) { frame -> onFrame(frame) }
        _uiState.update {
            it.copy(bodyObservation = null, isBodyVisibleOutsideTimingZone = false)
        }
        publishCameraGeometry(SingleCameraReadiness.STARTING)
    }

    private fun openCamera(surface: Surface) {
        advanceTrackingGeneration()
        cameraManager.openCamera(surface) { frame -> onFrame(frame) }
    }

    private fun publishCameraGeometry(readiness: SingleCameraReadiness) {
        val size = cameraManager.getPreviewSize()
        _uiState.update {
            it.copy(
                readiness = readiness,
                sensorOrientation = cameraManager.getSensorOrientation(),
                isFrontCamera = cameraManager.isFrontCamera.value,
                previewWidth = size?.width ?: 0,
                previewHeight = size?.height ?: 0
            )
        }
    }

    // endregion

    // region Pose pipeline (camera image thread -> pose worker -> main)

    private fun onFrame(frame: CameraManager.FrameData) {
        if (pendingFrame.getAndSet(frame) != null) {
            skippedFrames.incrementAndGet()
            return
        }
        poseExecutor.execute { drainFrames() }
    }

    private fun drainFrames() {
        while (true) {
            val frame = pendingFrame.getAndSet(null) ?: return
            processFrame(frame)
        }
    }

    private fun processFrame(frame: CameraManager.FrameData) {
        val generation = trackingGeneration.get()
        val detector = poseDetector ?: SingleCameraPoseDetector().also { poseDetector = it }
        val isFront = cameraManager.isFrontCamera.value
        val rotation = SingleCameraPoseDetector.imageRotationDegrees(
            sensorOrientationDegrees = cameraManager.getSensorOrientation(),
            displayRotationDegrees = displayRotationDegrees,
            isFrontCamera = isFront
        )
        val observations = detector.detect(frame, rotation, mirrorHorizontally = isFront)
        val timestamp = frame.timestampNanos / 1_000_000_000.0

        processedTimestamps.addLast(timestamp)
        while (processedTimestamps.isNotEmpty() && timestamp - processedTimestamps.first() > 1.0) {
            processedTimestamps.removeFirst()
        }
        val fps = if (processedTimestamps.size >= 2) {
            (processedTimestamps.size - 1) / (processedTimestamps.last() - processedTimestamps.first())
                .coerceAtLeast(0.001)
        } else {
            0.0
        }

        val selection = synchronized(trackingLock) {
            if (generation != trackingGeneration.get()) return
            selectionCoordinator.select(observations, targetStartX, timestamp, trackingDirection, initialDirectedXRange)
        }
        mainHandler.post {
            if (generation == trackingGeneration.get()) accept(selection, timestamp, fps)
        }
    }

    private fun accept(selection: SingleCameraAthleteSelection, timestamp: Double, fps: Double) {
        val previousPhase = engine.phase
        val visible = selection.visibleObservation
        _uiState.update { state ->
            val displayed = when {
                visible != null -> visible
                state.bodyObservation != null &&
                    timestamp - state.bodyObservation.timestamp > POSE_DISPLAY_GRACE_SECONDS -> null
                else -> state.bodyObservation
            }
            state.copy(
                bodyObservation = displayed,
                isBodyVisibleOutsideTimingZone = if (visible != null) {
                    selection.isVisibleOutsideTimingZone
                } else if (displayed == null) {
                    false
                } else {
                    state.isBodyVisibleOutsideTimingZone
                },
                processedPoseFps = fps
            )
        }

        val observation = selection.timingObservation
        val events = if (observation != null) {
            engine.ingest(
                SingleCameraPoseSample(
                    timestamp = observation.timestamp,
                    rootX = observation.root.x,
                    rootY = observation.root.y,
                    confidence = observation.confidence
                )
            )
        } else {
            engine.ingestMissingPose(timestamp)
        }
        syncEngineState()

        val wasConfirmingFlyingEntry = previousPhase is SingleCameraSprintPhase.Locking ||
            previousPhase == SingleCameraSprintPhase.Armed
        val resumedFlyingSearch = activeStartStyle == SingleCameraSprintStartStyle.FLYING &&
            wasConfirmingFlyingEntry &&
            (engine.phase == SingleCameraSprintPhase.Acquiring ||
                events.contains(SingleCameraSprintTimingEvent.AthleteAcquired))
        if (resumedFlyingSearch) {
            // The engine returned to pre-start search; release the tracker
            // identity too so it cannot follow a person already past 0 m.
            synchronized(trackingLock) { selectionCoordinator.reset() }
            if (engine.phase == SingleCameraSprintPhase.Acquiring) {
                _uiState.update { it.copy(bodyObservation = null, isBodyVisibleOutsideTimingZone = false) }
            }
        }
        handle(events)
    }

    private fun syncEngineState() {
        _uiState.update {
            it.copy(
                phase = engine.phase,
                splits = engine.splits,
                liveElapsedSeconds = engine.liveElapsedSeconds
            )
        }
    }

    private fun handle(events: List<SingleCameraSprintTimingEvent>) {
        for (event in events) {
            when (event) {
                SingleCameraSprintTimingEvent.AthleteAcquired ->
                    Log.i(TAG, "[SINGLE_CAMERA_ATHLETE_ACQUIRED] style=${activeStartStyle.rawValue}")
                SingleCameraSprintTimingEvent.Armed ->
                    Log.i(TAG, "[SINGLE_CAMERA_READY_TO_RUN] run=$activeRunId")
                is SingleCameraSprintTimingEvent.Started -> {
                    _uiState.update { it.copy(liveStartTimestamp = event.timestamp) }
                    Log.i(TAG, "[SINGLE_CAMERA_STARTED] run=$activeRunId drill=${activeDrill.rawValue}")
                }
                is SingleCameraSprintTimingEvent.GateCrossed -> Log.i(
                    TAG,
                    "[SINGLE_CAMERA_GATE] run=$activeRunId meters=${event.split.distanceMeters} " +
                        "elapsed=${"%.4f".format(event.split.elapsedSeconds)}"
                )
                is SingleCameraSprintTimingEvent.Completed -> completeRun(event.result)
                is SingleCameraSprintTimingEvent.Invalidated -> {
                    stopMotionMonitoring()
                    _uiState.update { it.copy(liveStartTimestamp = null, errorReason = event.reason) }
                    Log.w(TAG, "[SINGLE_CAMERA_INVALID] run=$activeRunId reason=${event.reason}")
                }
            }
        }
    }

    private fun completeRun(result: SingleCameraSprintResult) {
        stopMotionMonitoring()
        val phoneMoved = _uiState.value.phoneMotionWarningVisible
        val displayResult = if (phoneMoved) result.markedForPhoneMotionReview() else result
        val runId = activeRunId ?: UUID.randomUUID().toString().also { activeRunId = it }
        _uiState.update { state ->
            val record = SingleCameraSprintRunRecord(
                id = runId,
                runNumber = state.sessionRuns.size + 1,
                createdAtMillis = System.currentTimeMillis(),
                result = displayResult,
                gates = activeGates,
                direction = activeDirection,
                phoneMotionWarning = phoneMoved
            )
            state.copy(
                result = displayResult,
                liveElapsedSeconds = displayResult.totalTimeSeconds,
                liveStartTimestamp = null,
                sessionRuns = state.sessionRuns + record,
                currentRunId = runId
            )
        }
        Log.i(
            TAG,
            "[SINGLE_CAMERA_COMPLETED] run=$runId drill=${activeDrill.rawValue} " +
                "time=${"%.4f".format(displayResult.totalTimeSeconds)} quality=${displayResult.quality.rawValue} " +
                "poseFps=${"%.1f".format(displayResult.estimatedPoseFPS)} phoneMoved=$phoneMoved"
        )
        persistCompletedRuns()
    }

    // endregion

    // region Setup and run control

    fun arm(): Boolean {
        val state = _uiState.value
        if (state.readiness != SingleCameraReadiness.READY || state.phase != SingleCameraSprintPhase.Calibrating) {
            return false
        }
        val configuration = state.configuration
        if (!engine.arm(state.gates, state.direction, configuration.startStyle, configuration.drill)) {
            val reason = (engine.phase as? SingleCameraSprintPhase.Invalid)?.reason
                ?: SingleCameraInvalidReason.GATE_LAYOUT
            engine.reset()
            _uiState.update { it.copy(errorReason = reason) }
            return false
        }
        activeGates = state.gates
        activeDirection = state.direction
        activeStartStyle = configuration.startStyle
        activeDrill = configuration.drill
        activeRunId = UUID.randomUUID().toString()
        advanceTrackingGeneration()
        updateAcquisitionTarget(state.gates, state.direction, configuration)
        _uiState.update {
            it.copy(
                phase = engine.phase,
                splits = emptyList(),
                result = null,
                liveElapsedSeconds = 0.0,
                liveStartTimestamp = null,
                errorReason = null,
                phoneMotionWarningVisible = false,
                bodyObservation = null,
                isBodyVisibleOutsideTimingZone = false
            )
        }
        phoneMotionWarningState.reset()
        startMotionMonitoring()
        Log.i(
            TAG,
            "[SINGLE_CAMERA_ARM] run=$activeRunId drill=${activeDrill.rawValue} style=${activeStartStyle.rawValue} " +
                "direction=${activeDirection.rawValue} gates=" +
                activeGates.joinToString(",") { "${it.distanceMeters}:${"%.3f".format(it.normalizedX)}" }
        )
        return true
    }

    fun resetRun() {
        stopMotionMonitoring()
        engine.reset()
        activeRunId = null
        phoneMotionWarningState.reset()
        advanceTrackingGeneration()
        val state = _uiState.value
        updateAcquisitionTarget(state.gates, state.direction, state.configuration)
        _uiState.update {
            it.copy(
                phase = SingleCameraSprintPhase.Calibrating,
                splits = emptyList(),
                result = null,
                liveElapsedSeconds = 0.0,
                liveStartTimestamp = null,
                errorReason = null,
                phoneMotionWarningVisible = false,
                isBodyVisibleOutsideTimingZone = false
            )
        }
    }

    fun moveGate(distanceMeters: Double, normalizedX: Double) {
        val state = _uiState.value
        if (state.phase != SingleCameraSprintPhase.Calibrating) return
        val gates = SingleCameraSprintGateLayout.moving(distanceMeters, normalizedX, state.gates, state.direction)
        _uiState.update {
            it.copy(gates = gates, activeGateDistance = distanceMeters, hasAdjustedGates = true, errorReason = null)
        }
        updateAcquisitionTarget(gates, state.direction, state.configuration)
    }

    fun endGateDrag() {
        _uiState.update { it.copy(activeGateDistance = null) }
    }

    fun flipDirection() {
        val state = _uiState.value
        if (state.phase != SingleCameraSprintPhase.Calibrating) return
        val direction = state.direction.flipped
        val gates = SingleCameraSprintGateLayout.flipped(state.gates)
        _uiState.update {
            it.copy(direction = direction, gates = gates, activeGateDistance = null, errorReason = null)
        }
        updateAcquisitionTarget(gates, direction, state.configuration)
    }

    fun selectDrill(drill: SingleCameraSprintDrill) =
        applyConfiguration(_uiState.value.configuration.changingDrill(drill))

    fun selectStartStyle(startStyle: SingleCameraSprintStartStyle) =
        applyConfiguration(_uiState.value.configuration.changingStartStyle(startStyle))

    fun selectDistance(distanceMeters: Double) =
        applyConfiguration(_uiState.value.configuration.changingDistance(distanceMeters))

    private fun applyConfiguration(updated: SingleCameraSprintConfiguration) {
        val state = _uiState.value
        if (!state.isConfigurationEditable || updated == state.configuration) return
        val gates = SingleCameraSprintGateLayout.reconfigured(state.gates, updated, state.direction)
        _uiState.update {
            it.copy(configuration = updated, gates = gates, activeGateDistance = null, errorReason = null)
        }
        updateAcquisitionTarget(gates, state.direction, updated)
        Log.i(
            TAG,
            "[SINGLE_CAMERA_SETUP_CHANGED] drill=${updated.drill.rawValue} style=${updated.startStyle.rawValue} " +
                "distance=${updated.distanceMeters} gates=${gates.size}"
        )
    }

    fun dismissError() {
        if (_uiState.value.phase == SingleCameraSprintPhase.Calibrating) {
            _uiState.update { it.copy(errorReason = null) }
        }
    }

    private fun updateAcquisitionTarget(
        gates: List<SingleCameraSprintGate>,
        direction: SingleCameraSprintDirection,
        configuration: SingleCameraSprintConfiguration
    ) {
        val startX = SingleCameraSprintAcquisition.targetX(gates, direction, configuration.startStyle, configuration.drill)
        val range = SingleCameraSprintAcquisition.initialDirectedRange(
            gates,
            direction,
            configuration.startStyle,
            engine.parameters.standingStartZoneHalfWidthMeters,
            configuration.drill
        )
        synchronized(trackingLock) {
            if (trackingDirection != direction || initialDirectedXRange != range) selectionCoordinator.reset()
            targetStartX = startX
            trackingDirection = direction
            initialDirectedXRange = range
        }
    }

    private fun advanceTrackingGeneration() {
        synchronized(trackingLock) {
            trackingGeneration.incrementAndGet()
            selectionCoordinator.reset()
        }
    }

    // endregion

    // region Persistence

    private fun persistCompletedRuns() {
        val runs = _uiState.value.sessionRuns
        if (runs.isEmpty()) return
        viewModelScope.launch {
            val saved = runCatching { sessionRepository.saveOnePhoneSprintRuns(sessionId, runs, athleteId) }
            saved.onSuccess { count -> _uiState.update { it.copy(savedRunCount = count, persistenceFailed = false) } }
            saved.onFailure { error ->
                Log.e(TAG, "[SINGLE_CAMERA_SESSION_SAVE_FAILED] ${error.javaClass.simpleName}")
                _uiState.update { it.copy(persistenceFailed = true) }
            }
        }
    }

    /** Saves every run; [onSaved] runs on success so the caller can leave. */
    fun finishSession(onSaved: () -> Unit) {
        val runs = _uiState.value.sessionRuns
        if (runs.isEmpty()) {
            onSaved()
            return
        }
        viewModelScope.launch {
            val saved = runCatching { sessionRepository.saveOnePhoneSprintRuns(sessionId, runs, athleteId) }
            if (saved.isSuccess) {
                _uiState.update { it.copy(savedRunCount = saved.getOrDefault(0), persistenceFailed = false) }
                onSaved()
            } else {
                _uiState.update { it.copy(persistenceFailed = true) }
            }
        }
    }

    // endregion

    // region Phone motion

    private fun startMotionMonitoring() {
        val manager = sensorManager ?: return
        motionGraceDeadlineNanos = SystemClock.elapsedRealtimeNanos() + (MOTION_GRACE_SECONDS * 1e9).toLong()
        latestAccelerationG = 0.0
        latestRotationRadPerSecond = 0.0
        if (isMonitoringMotion) return
        manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            manager.registerListener(this, it, PhoneMotionStabilityPolicy.SAMPLE_PERIOD_MICROS)
        }
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            manager.registerListener(this, it, PhoneMotionStabilityPolicy.SAMPLE_PERIOD_MICROS)
        }
        isMonitoringMotion = true
    }

    private fun stopMotionMonitoring() {
        if (isMonitoringMotion) sensorManager?.unregisterListener(this)
        isMonitoringMotion = false
        phoneMotionWarningState.endSampleSequence()
        motionGraceDeadlineNanos = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val magnitude = PhoneMotionStabilityPolicy.magnitude(event.values[0], event.values[1], event.values[2])
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION ->
                latestAccelerationG = magnitude / PhoneMotionStabilityPolicy.STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED
            Sensor.TYPE_GYROSCOPE -> latestRotationRadPerSecond = magnitude
            else -> return
        }
        // Evaluate once per gyroscope sample (or per acceleration sample without a gyro).
        val hasGyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        if (hasGyro && event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (SystemClock.elapsedRealtimeNanos() < motionGraceDeadlineNanos) return
        if (!_uiState.value.phase.isRunInProgress) return
        val moved = PhoneMotionStabilityPolicy.detectsMovement(latestAccelerationG, latestRotationRadPerSecond)
        if (!phoneMotionWarningState.ingest(moved)) return
        _uiState.update { it.copy(phoneMotionWarningVisible = true) }
        Log.w(TAG, "[SINGLE_CAMERA_PHONE_MOTION_WARNING] run=$activeRunId timingContinues=true")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // endregion

    override fun onCleared() {
        stopMotionMonitoring()
        advanceTrackingGeneration()
        cameraManager.closeCamera()
        pendingFrame.set(null)
        poseExecutor.execute {
            poseDetector?.close()
            poseDetector = null
        }
        poseExecutor.shutdown()
        super.onCleared()
    }
}
