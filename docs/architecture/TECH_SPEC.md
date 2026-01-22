# TrackSpeed Android - Technical Specification

**Version:** 1.0
**Last Updated:** January 2026

---

## 1. System Overview

### 1.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        PRESENTATION LAYER                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │   Compose   │  │   Compose   │  │   Compose   │              │
│  │    Screens  │  │  Components │  │   ViewModels│              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────────┐
│                         DOMAIN LAYER                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │   Use Cases │  │   Entities  │  │ Repositories│              │
│  │             │  │             │  │ (Interfaces)│              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────────┐
│                          DATA LAYER                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │  Camera  │  │   Room   │  │ Supabase │  │   BLE    │        │
│  │ Manager  │  │    DB    │  │  Client  │  │ Manager  │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────────┐
│                        CORE/ENGINE LAYER                         │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    DETECTION ENGINE                       │   │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐          │   │
│  │  │ Background │  │  Crossing  │  │    Pose    │          │   │
│  │  │   Model    │  │  Detector  │  │  Service   │          │   │
│  │  └────────────┘  └────────────┘  └────────────┘          │   │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐          │   │
│  │  │ Composite  │  │Contiguous  │  │   Clock    │          │   │
│  │  │  Buffer    │  │RunFilter   │  │   Sync     │          │   │
│  │  └────────────┘  └────────────┘  └────────────┘          │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 Technology Stack

| Layer | Technology | Notes |
|-------|------------|-------|
| Language | Kotlin 1.9+ | Coroutines, Flow |
| UI | Jetpack Compose | Material 3 |
| DI | Hilt | Dagger-based |
| Camera | Camera2 API | For manual control |
| ML | ML Kit Pose Detection | Google's pose API |
| Local DB | Room | SQLite wrapper |
| Networking | Ktor / OkHttp | HTTP client |
| Backend | Supabase Kotlin | Auth, DB, Realtime |
| BLE | Android BLE API | BluetoothGatt |
| Async | Coroutines + Flow | Structured concurrency |
| Image | Bitmap / RenderScript | Image processing |
| Build | Gradle (Kotlin DSL) | Version catalogs |

---

## 2. Module Structure

```
app/
├── src/main/
│   ├── kotlin/com/trackspeed/android/
│   │   ├── TrackSpeedApp.kt              # Application class
│   │   ├── MainActivity.kt               # Single activity
│   │   │
│   │   ├── di/                           # Dependency Injection
│   │   │   ├── AppModule.kt
│   │   │   ├── CameraModule.kt
│   │   │   ├── DatabaseModule.kt
│   │   │   └── NetworkModule.kt
│   │   │
│   │   ├── ui/                           # Presentation Layer
│   │   │   ├── theme/
│   │   │   │   ├── Theme.kt
│   │   │   │   ├── Color.kt
│   │   │   │   └── Typography.kt
│   │   │   ├── navigation/
│   │   │   │   └── NavGraph.kt
│   │   │   ├── screens/
│   │   │   │   ├── home/
│   │   │   │   ├── basic/
│   │   │   │   ├── race/
│   │   │   │   ├── calibration/
│   │   │   │   ├── timing/
│   │   │   │   ├── results/
│   │   │   │   ├── history/
│   │   │   │   └── settings/
│   │   │   └── components/
│   │   │       ├── CameraPreview.kt
│   │   │       ├── GateLineOverlay.kt
│   │   │       ├── BubbleLevel.kt
│   │   │       ├── TimeDisplay.kt
│   │   │       └── PhotoFinishViewer.kt
│   │   │
│   │   ├── domain/                       # Domain Layer
│   │   │   ├── model/
│   │   │   │   ├── Session.kt
│   │   │   │   ├── Run.kt
│   │   │   │   ├── Crossing.kt
│   │   │   │   ├── Athlete.kt
│   │   │   │   └── TimingMessage.kt
│   │   │   ├── repository/
│   │   │   │   ├── SessionRepository.kt
│   │   │   │   └── UserRepository.kt
│   │   │   └── usecase/
│   │   │       ├── StartTimingSession.kt
│   │   │       ├── ProcessCrossing.kt
│   │   │       └── SyncClocks.kt
│   │   │
│   │   ├── data/                         # Data Layer
│   │   │   ├── local/
│   │   │   │   ├── db/
│   │   │   │   │   ├── TrackSpeedDatabase.kt
│   │   │   │   │   ├── dao/
│   │   │   │   │   └── entity/
│   │   │   │   └── storage/
│   │   │   │       └── ImageStorage.kt
│   │   │   ├── remote/
│   │   │   │   ├── SupabaseClient.kt
│   │   │   │   └── api/
│   │   │   └── repository/
│   │   │       └── SessionRepositoryImpl.kt
│   │   │
│   │   ├── engine/                       # Core Detection Engine
│   │   │   ├── camera/
│   │   │   │   ├── CameraManager.kt
│   │   │   │   ├── FrameProcessor.kt
│   │   │   │   └── CameraCapability.kt
│   │   │   ├── detection/
│   │   │   │   ├── BackgroundModel.kt
│   │   │   │   ├── CrossingDetector.kt
│   │   │   │   ├── ContiguousRunFilter.kt
│   │   │   │   └── DetectionState.kt
│   │   │   ├── pose/
│   │   │   │   ├── PoseService.kt
│   │   │   │   └── TorsoBoundsStore.kt
│   │   │   ├── composite/
│   │   │   │   └── CompositeBuffer.kt
│   │   │   └── GateEngine.kt             # Orchestrator
│   │   │
│   │   ├── communication/                # Multi-Device Layer
│   │   │   ├── transport/
│   │   │   │   ├── Transport.kt          # Interface
│   │   │   │   ├── BleTransport.kt
│   │   │   │   └── SupabaseTransport.kt
│   │   │   ├── sync/
│   │   │   │   └── ClockSyncService.kt
│   │   │   └── session/
│   │   │       └── RaceSession.kt
│   │   │
│   │   └── util/
│   │       ├── Extensions.kt
│   │       ├── TimeUtils.kt
│   │       └── PermissionUtils.kt
│   │
│   └── res/
│       ├── values/
│       ├── drawable/
│       └── raw/                          # Audio files
│
├── build.gradle.kts
└── proguard-rules.pro
```

---

## 3. Core Components Specification

### 3.1 Camera Manager

**Purpose:** Manage Camera2 API for high-speed capture

```kotlin
class CameraManager @Inject constructor(
    private val context: Context
) {
    // Camera state
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    // Configuration
    data class CameraConfig(
        val targetFps: Int = 240,
        val resolution: Size = Size(1280, 720),
        val cameraId: String = "0" // Back camera
    )

    // Public API
    suspend fun initialize(config: CameraConfig): Result<Unit>
    suspend fun startCapture(onFrame: (Image, Long) -> Unit): Result<Unit>
    suspend fun stopCapture()
    fun lockExposure()
    fun lockFocus()
    fun release()

    // Capability detection
    fun getMaxFps(cameraId: String): Int
    fun getSupportedResolutions(cameraId: String): List<Size>
}
```

**Key Implementation Details:**

```kotlin
// High-speed capture setup
private fun createHighSpeedSession() {
    val outputConfig = OutputConfiguration(imageReader!!.surface)
    val sessionConfig = SessionConfiguration(
        SessionConfiguration.SESSION_HIGH_SPEED,
        listOf(outputConfig),
        executor,
        stateCallback
    )
    cameraDevice?.createCaptureSession(sessionConfig)
}

// Frame rate configuration
private fun buildCaptureRequest(): CaptureRequest {
    return cameraDevice?.createCaptureRequest(
        CameraDevice.TEMPLATE_RECORD
    )?.apply {
        addTarget(imageReader!!.surface)
        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
    }?.build()!!
}
```

### 3.2 Background Model

**Purpose:** Model empty lane for foreground detection

```kotlin
class BackgroundModel {
    // Per-row statistics
    private lateinit var medians: FloatArray      // Median luminance per row
    private lateinit var mads: FloatArray         // MAD per row
    private lateinit var thresholds: FloatArray   // Adaptive threshold per row

    // Configuration
    companion object {
        const val CALIBRATION_FRAMES = 30
        const val MIN_THRESHOLD = 10f
        const val MAD_MULTIPLIER = 3.5f
        const val ADAPTATION_RATE = 0.002f  // Slow adaptation
    }

    // State
    var isCalibrated: Boolean = false
        private set

    // Public API
    fun addCalibrationFrame(luminanceColumn: ByteArray)
    fun finishCalibration()
    fun getForegroundMask(luminanceColumn: ByteArray): BooleanArray
    fun adaptBackground(luminanceColumn: ByteArray)  // Slow adaptation
    fun reset()
}
```

**Algorithm:**

```kotlin
fun finishCalibration() {
    // For each row, calculate median and MAD from calibration frames
    for (row in 0 until height) {
        val values = calibrationFrames.map { it[row].toFloat() }
        medians[row] = values.median()
        mads[row] = values.mad(medians[row])
        thresholds[row] = max(MIN_THRESHOLD, MAD_MULTIPLIER * mads[row])
    }
    isCalibrated = true
}

fun getForegroundMask(luminanceColumn: ByteArray): BooleanArray {
    return BooleanArray(height) { row ->
        abs(luminanceColumn[row].toFloat() - medians[row]) > thresholds[row]
    }
}
```

### 3.3 Crossing Detector

**Purpose:** State machine for crossing detection with hysteresis

```kotlin
class CrossingDetector {
    // State machine
    enum class State {
        WAITING_FOR_CLEAR,  // Waiting for lane to be empty
        ARMED,              // Ready to detect crossing
        CHEST_CROSSING,     // Torso entering gate
        POSTROLL,           // Capturing frames after trigger
        COOLDOWN            // Preventing double trigger
    }

    // Thresholds (with hysteresis)
    data class Thresholds(
        val enterOccupancy: Float = 0.22f,    // First contact
        val confirmOccupancy: Float = 0.35f,  // Confirm crossing
        val exitOccupancy: Float = 0.15f,     // Clear detection
        val confirmFrames: Int = 2,            // Frames to confirm
        val postrollFrames: Int = 50,          // ~200ms at 240fps
        val cooldownFrames: Int = 120          // ~500ms at 240fps
    )

    // Current state
    var state: State = State.WAITING_FOR_CLEAR
        private set

    // Crossing result
    data class CrossingResult(
        val timestamp: Long,           // Nanoseconds
        val triggerFrameIndex: Int,
        val occupancyAtTrigger: Float,
        val interpolatedOffsetMs: Double
    )

    // Public API
    fun processFrame(
        occupancy: Float,
        timestamp: Long,
        frameIndex: Int,
        previousOccupancy: Float
    ): CrossingResult?

    fun reset()
    fun arm()
}
```

**State Machine:**

```
                    occupancy < exit
    ┌─────────────────────────────────┐
    │                                 │
    ▼                                 │
┌───────────────┐    arm()    ┌───────────────┐
│ WAITING_FOR   │────────────▶│     ARMED     │
│    CLEAR      │             │               │
└───────────────┘             └───────┬───────┘
        ▲                             │ occupancy >= enter
        │                             ▼
        │                     ┌───────────────┐
        │                     │    CHEST      │
        │                     │   CROSSING    │
        │                     └───────┬───────┘
        │                             │ confirmed (2 frames)
        │                             ▼
        │  cooldown done      ┌───────────────┐
        │◀────────────────────│   POSTROLL    │
        │                     │  (50 frames)  │
        │                     └───────┬───────┘
        │                             │
        │                             ▼
        │                     ┌───────────────┐
        └─────────────────────│   COOLDOWN    │
                              │ (120 frames)  │
                              └───────────────┘
```

### 3.4 Pose Service

**Purpose:** Human pose detection for torso tracking

```kotlin
class PoseService @Inject constructor(
    private val context: Context
) {
    // ML Kit pose detector
    private val poseDetector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    // Torso bounds (thread-safe)
    private val _torsoBounds = MutableStateFlow<TorsoBounds?>(null)
    val torsoBounds: StateFlow<TorsoBounds?> = _torsoBounds.asStateFlow()

    data class TorsoBounds(
        val yTop: Float,      // Normalized 0-1, top of torso
        val yBottom: Float,   // Normalized 0-1, bottom of torso
        val confidence: Float
    )

    // EMA smoothing
    private var smoothedTop: Float? = null
    private var smoothedBottom: Float? = null
    private val alpha = 0.3f

    // Public API
    suspend fun processImage(image: InputImage): TorsoBounds?
    fun reset()
}
```

**Implementation:**

```kotlin
suspend fun processImage(image: InputImage): TorsoBounds? {
    return suspendCoroutine { continuation ->
        poseDetector.process(image)
            .addOnSuccessListener { pose ->
                val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
                val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)

                if (listOf(leftShoulder, rightShoulder, leftHip, rightHip)
                        .all { it != null && it.inFrameLikelihood > 0.2f }) {

                    val shoulderY = (leftShoulder!!.position.y + rightShoulder!!.position.y) / 2
                    val hipY = (leftHip!!.position.y + rightHip!!.position.y) / 2

                    // Normalize to 0-1
                    val normalizedTop = shoulderY / image.height
                    val normalizedBottom = hipY / image.height

                    // EMA smoothing
                    smoothedTop = smoothedTop?.let { alpha * normalizedTop + (1 - alpha) * it }
                        ?: normalizedTop
                    smoothedBottom = smoothedBottom?.let { alpha * normalizedBottom + (1 - alpha) * it }
                        ?: normalizedBottom

                    val bounds = TorsoBounds(smoothedTop!!, smoothedBottom!!, 0.9f)
                    _torsoBounds.value = bounds
                    continuation.resume(bounds)
                } else {
                    continuation.resume(null)
                }
            }
            .addOnFailureListener { continuation.resume(null) }
    }
}
```

### 3.5 Composite Buffer

**Purpose:** Generate photo-finish composite images

```kotlin
class CompositeBuffer(
    private val height: Int,
    private val maxWidth: Int = 6000  // Max ~25 seconds at 240fps
) {
    // Ring buffer for slit data
    private val buffer: ByteArray = ByteArray(height * maxWidth)
    private var writeIndex = 0
    private var frameCount = 0

    // Trigger information
    var triggerFrameIndex: Int = -1
        private set

    // Public API
    fun addSlit(luminanceColumn: ByteArray, timestamp: Long)
    fun markTrigger()
    fun export(): Bitmap
    fun reset()

    // Export as PNG
    fun exportToPng(file: File)
}
```

### 3.6 Gate Engine (Orchestrator)

**Purpose:** Coordinate all detection components

```kotlin
class GateEngine @Inject constructor(
    private val cameraManager: CameraManager,
    private val backgroundModel: BackgroundModel,
    private val crossingDetector: CrossingDetector,
    private val poseService: PoseService,
    private val compositeBuffer: CompositeBuffer
) {
    // State
    sealed class EngineState {
        object Idle : EngineState()
        object Calibrating : EngineState()
        object Armed : EngineState()
        object Detecting : EngineState()
        data class CrossingDetected(val result: CrossingResult) : EngineState()
        data class Error(val message: String) : EngineState()
    }

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // Gate configuration
    var gatePosition: Float = 0.5f  // 0-1, horizontal position in frame

    // Frame processing
    private var frameIndex = 0
    private var lastOccupancy = 0f

    // Public API
    suspend fun startCalibration()
    suspend fun finishCalibration()
    suspend fun arm()
    suspend fun stop()
    fun setGatePosition(position: Float)

    // Internal processing
    private fun processFrame(image: Image, timestamp: Long)
}
```

**Processing Pipeline:**

```kotlin
private fun processFrame(image: Image, timestamp: Long) {
    // 1. Extract 1-pixel column at gate position
    val column = extractColumn(image, gatePosition)

    // 2. Add to composite buffer
    compositeBuffer.addSlit(column, timestamp)

    // 3. Get foreground mask from background model
    val foregroundMask = backgroundModel.getForegroundMask(column)

    // 4. Get current torso bounds (updated at 30Hz by pose service)
    val torsoBounds = poseService.torsoBounds.value

    // 5. Calculate occupancy within torso band
    val occupancy = if (torsoBounds != null) {
        calculateOccupancy(foregroundMask, torsoBounds)
    } else {
        calculateFullOccupancy(foregroundMask)
    }

    // 6. Run crossing detector state machine
    val result = crossingDetector.processFrame(
        occupancy = occupancy,
        timestamp = timestamp,
        frameIndex = frameIndex,
        previousOccupancy = lastOccupancy
    )

    // 7. Handle crossing result
    if (result != null) {
        compositeBuffer.markTrigger()
        _state.value = EngineState.CrossingDetected(result)
    }

    lastOccupancy = occupancy
    frameIndex++
}
```

---

## 4. Communication Layer

### 4.1 Transport Interface

```kotlin
interface Transport {
    val connectionState: StateFlow<ConnectionState>
    val incomingMessages: Flow<TimingMessage>

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    suspend fun connect(peerId: String): Result<Unit>
    suspend fun disconnect()
    suspend fun send(message: TimingMessage): Result<Unit>
    suspend fun broadcast(message: TimingMessage): Result<Unit>
}
```

### 4.2 BLE Transport

```kotlin
class BleTransport @Inject constructor(
    private val context: Context
) : Transport {
    // GATT UUIDs (must match iOS)
    companion object {
        val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val TIMING_CHAR_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abd")
    }

    // BLE components
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothGattServer: BluetoothGattServer? = null

    // Modes
    enum class Mode { PERIPHERAL, CENTRAL }

    // Public API
    suspend fun startAdvertising()
    suspend fun startScanning(): Flow<BluetoothDevice>
    override suspend fun connect(peerId: String): Result<Unit>
    override suspend fun send(message: TimingMessage): Result<Unit>
}
```

### 4.3 Clock Sync Service

```kotlin
class ClockSyncService @Inject constructor(
    private val transport: Transport
) {
    // NTP-style sync
    data class SyncResult(
        val offsetNanos: Long,      // Clock offset
        val rttNanos: Long,         // Round-trip time
        val uncertaintyMs: Double   // Estimated uncertainty
    )

    // Sync state
    private val _syncState = MutableStateFlow<SyncState>(SyncState.NotSynced)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    sealed class SyncState {
        object NotSynced : SyncState()
        object Syncing : SyncState()
        data class Synced(val result: SyncResult) : SyncState()
    }

    // Quality tiers
    enum class SyncQuality {
        EXCELLENT,  // < 3ms
        GOOD,       // 3-5ms
        FAIR,       // 5-10ms
        POOR        // > 10ms
    }

    // Public API
    suspend fun startSync(peerDeviceId: String): Result<SyncResult>
    fun getCurrentOffset(): Long
    fun getQuality(): SyncQuality
}
```

**NTP Algorithm:**

```kotlin
suspend fun performSyncRound(): SyncResult {
    // T1: Send PING
    val t1 = SystemClock.elapsedRealtimeNanos()
    transport.send(TimingMessage.ClockSync.Ping(t1))

    // Wait for PONG
    val pong = transport.incomingMessages
        .filterIsInstance<TimingMessage.ClockSync.Pong>()
        .first()

    // T4: Receive PONG
    val t4 = SystemClock.elapsedRealtimeNanos()
    val t2 = pong.receiveTime
    val t3 = pong.sendTime

    // Calculate offset and RTT
    val rtt = (t4 - t1) - (t3 - t2)
    val offset = ((t2 - t1) + (t3 - t4)) / 2

    return SyncResult(
        offsetNanos = offset,
        rttNanos = rtt,
        uncertaintyMs = rtt / 2_000_000.0
    )
}
```

### 4.4 Timing Message Protocol

```kotlin
sealed class TimingMessage {
    // Clock synchronization
    sealed class ClockSync : TimingMessage() {
        data class Ping(val t1: Long) : ClockSync()
        data class Pong(val t1: Long, val receiveTime: Long, val sendTime: Long) : ClockSync()
    }

    // Session management
    data class GateReady(val role: GateRole, val deviceId: String) : TimingMessage()
    data class SessionStart(val sessionId: String) : TimingMessage()
    data class SessionEnd(val sessionId: String) : TimingMessage()

    // Timing events
    data class StartEvent(
        val timestamp: Long,
        val deviceId: String,
        val clockOffset: Long
    ) : TimingMessage()

    data class FinishEvent(
        val timestamp: Long,
        val deviceId: String,
        val clockOffset: Long
    ) : TimingMessage()

    // Keep-alive
    object Heartbeat : TimingMessage()

    // Serialization
    fun toByteArray(): ByteArray
    companion object {
        fun fromByteArray(bytes: ByteArray): TimingMessage
    }
}

enum class GateRole {
    START, FINISH, INTERMEDIATE
}
```

---

## 5. Data Layer

### 5.1 Room Database

```kotlin
@Database(
    entities = [
        SessionEntity::class,
        RunEntity::class,
        CrossingEntity::class,
        AthleteEntity::class
    ],
    version = 1
)
@TypeConverters(Converters::class)
abstract class TrackSpeedDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun runDao(): RunDao
    abstract fun crossingDao(): CrossingDao
    abstract fun athleteDao(): AthleteDao
}
```

### 5.2 Entities

```kotlin
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: Long,  // Epoch millis
    val distance: Double?,
    val startType: String,
    val notes: String?
)

@Entity(
    tableName = "runs",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RunEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val startTime: Long,
    val splitTimeMs: Double?,
    val athleteId: String?
)

@Entity(
    tableName = "crossings",
    foreignKeys = [ForeignKey(
        entity = RunEntity::class,
        parentColumns = ["id"],
        childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class CrossingEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val runId: String,
    val timestamp: Long,
    val triggerImagePath: String?,
    val compositeImagePath: String?,
    val crossingOffsetMs: Double?,
    val frameCount: Int,
    val triggerFrameIndex: Int,
    val triggerOccupancy: Float,
    val capturedFps: Double,
    val gatePosition: Double?
)

@Entity(tableName = "athletes")
data class AthleteEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int  // ARGB color
)
```

### 5.3 DAOs

```kotlin
@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY date DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)
}

@Dao
interface CrossingDao {
    @Query("SELECT * FROM crossings WHERE runId = :runId ORDER BY timestamp")
    fun getCrossingsForRun(runId: String): Flow<List<CrossingEntity>>

    @Insert
    suspend fun insert(crossing: CrossingEntity)
}
```

---

## 6. Supabase Integration

### 6.1 Client Setup

```kotlin
object SupabaseClient {
    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        install(Auth)
        install(Postgrest)
        install(Realtime)
        install(Storage)
    }
}
```

### 6.2 Race Events Table (Existing)

```kotlin
@Serializable
data class RaceEventDto(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("event_type") val eventType: String,  // "start" or "finish"
    @SerialName("crossing_time_nanos") val crossingTimeNanos: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String?,
    @SerialName("image_path") val imagePath: String?,
    @SerialName("clock_offset_nanos") val clockOffsetNanos: Long?,
    @SerialName("uncertainty_ms") val uncertaintyMs: Double?,
    @SerialName("created_at") val createdAt: String? = null
)

// Insert race event
suspend fun insertRaceEvent(event: RaceEventDto) {
    client.postgrest["race_events"].insert(event)
}

// Subscribe to race events
fun subscribeToRaceEvents(sessionId: String): Flow<RaceEventDto> {
    return client.realtime
        .channel("race_events:session_id=eq.$sessionId")
        .postgresChangeFlow<RaceEventDto>(schema = "public")
        .map { it.record }
}
```

---

## 7. Threading Model

```
┌─────────────────────────────────────────────────────────────┐
│                      MAIN THREAD                             │
│  • UI rendering (Compose)                                    │
│  • User input handling                                       │
│  • Navigation                                                │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                   CAMERA THREAD (HIGH PRIORITY)              │
│  • Frame capture callbacks                                   │
│  • Column extraction                                         │
│  • Background subtraction                                    │
│  • Occupancy calculation                                     │
│  • Crossing detection state machine                          │
│  • Composite buffer updates                                  │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    POSE THREAD (MEDIUM PRIORITY)             │
│  • ML Kit pose detection (~30Hz)                             │
│  • Torso bounds calculation                                  │
│  • EMA smoothing                                             │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    IO THREAD (LOW PRIORITY)                  │
│  • Room database operations                                  │
│  • Image file I/O                                            │
│  • Supabase network calls                                    │
│  • BLE operations                                            │
└─────────────────────────────────────────────────────────────┘
```

**Coroutine Dispatchers:**

```kotlin
object AppDispatchers {
    val Camera = newSingleThreadContext("CameraThread")
    val Pose = newSingleThreadContext("PoseThread")
    val IO = Dispatchers.IO
    val Main = Dispatchers.Main
}
```

---

## 8. Build Configuration

### 8.1 Gradle Dependencies

```kotlin
// build.gradle.kts (app)
dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Camera
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")

    // ML Kit
    implementation("com.google.mlkit:pose-detection:18.0.0-beta3")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Supabase
    implementation(platform("io.github.jan-tennert.supabase:bom:2.0.4"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:gotrue-kt")
    implementation("io.ktor:ktor-client-android:2.3.7")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}
```

### 8.2 Android Manifest Permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />

<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

---

## 9. Testing Strategy

### 9.1 Unit Tests

- `BackgroundModelTest` - MAD calculation, threshold adaptation
- `CrossingDetectorTest` - State machine transitions
- `ClockSyncServiceTest` - NTP algorithm, offset calculation
- `CompositeBufferTest` - Ring buffer, export

### 9.2 Integration Tests

- Camera capture pipeline
- BLE connection and messaging
- Room database operations
- Supabase sync

### 9.3 Device Testing Matrix

| Device | FPS | Priority |
|--------|-----|----------|
| Pixel 8 Pro | 240 | High |
| Samsung S24 | 240 | High |
| Pixel 6 | 120 | Medium |
| Samsung A54 | 60 | Low |

---

## 10. Performance Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| Frame processing | < 4ms | Systrace |
| Memory (active) | < 400MB | Profiler |
| Memory (idle) | < 100MB | Profiler |
| Battery (1hr timing) | < 20% | Manual test |
| App startup | < 2s | Cold start |
| Detection latency | < 10ms | End-to-end |

---

## Appendix A: iOS Compatibility Notes

### Timing Message Serialization

Both platforms must use identical serialization format. Recommended: JSON with camelCase keys.

```json
{
  "type": "startEvent",
  "timestamp": 1234567890123456789,
  "deviceId": "ABC123",
  "clockOffset": -5000000
}
```

### BLE GATT Service

| UUID | Type | Description |
|------|------|-------------|
| `12345678-...-abc` | Service | TrackSpeed Timing Service |
| `12345678-...-abd` | Characteristic | Timing Messages (R/W/Notify) |
| `12345678-...-abe` | Characteristic | Device Info (Read) |

### Clock Reference

- **Android:** `SystemClock.elapsedRealtimeNanos()`
- **iOS:** `mach_absolute_time()` converted to nanoseconds

Both use monotonic clocks that don't jump with system time changes.
