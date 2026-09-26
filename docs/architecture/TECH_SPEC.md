# TrackSpeed Android - Technical Specification

**Version:** 2.0
**Last Updated:** February 2026

> **Note:** This replaces v1.0 which described a Precision mode architecture (240fps + background model + ML Kit pose detection) that was never implemented. The actual app uses Photo Finish mode (30-120fps + frame differencing + CCL blob detection), ported from the iOS PhotoFinishDetector.swift.

---

## 1. System Overview

### 1.1 High-Level Architecture

```
+---------------------------------------------------------------------+
|                        PRESENTATION LAYER                            |
|  +---------------+  +---------------+  +---------------+            |
|  |   Compose     |  |   Compose     |  |   Compose     |            |
|  |    Screens    |  |  Components   |  |   ViewModels  |            |
|  +---------------+  +---------------+  +---------------+            |
+---------------------------------------------------------------------+
                              |
+---------------------------------------------------------------------+
|                          DATA LAYER                                  |
|  +------------+  +------------+  +------------+  +------------+     |
|  |  Camera    |  |   Room     |  | Supabase   |  |   BLE      |     |
|  |  Manager   |  |    DB      |  |  Client    |  |  Sync      |     |
|  +------------+  +------------+  +------------+  +------------+     |
+---------------------------------------------------------------------+
                              |
+---------------------------------------------------------------------+
|                       CORE/ENGINE LAYER                               |
|  +------------------------------------------------------------+    |
|  |                   DETECTION ENGINE                           |    |
|  |  +-----------------+  +------------------+                   |    |
|  |  | PhotoFinish     |  |  ZeroAllocCCL    |                   |    |
|  |  | Detector        |  |  (blob labeling) |                   |    |
|  |  +-----------------+  +------------------+                   |    |
|  |  +-----------------+  +------------------+                   |    |
|  |  | RollingShutter  |  |  GateEngine      |                   |    |
|  |  | Calculator      |  |  (coordinator)   |                   |    |
|  |  +-----------------+  +------------------+                   |    |
|  +------------------------------------------------------------+    |
+---------------------------------------------------------------------+
```

### 1.2 Technology Stack

| Layer | Technology | Notes |
|-------|------------|-------|
| Language | Kotlin 1.9+ | Coroutines, Flow |
| UI | Jetpack Compose | Material 3 |
| DI | Hilt | Dagger-based |
| Camera | Camera2 API | Standard sessions, 30-120fps |
| Local DB | Room | SQLite wrapper |
| Networking | Ktor / OkHttp | HTTP client |
| Backend | Supabase Kotlin | Auth, DB, Realtime |
| BLE | Android BLE API | BluetoothGatt |
| Async | Coroutines + Flow | Structured concurrency |
| Image | Bitmap | Grayscale thumbnail capture |
| Build | Gradle (Kotlin DSL) | Version catalogs |

**Not used:** ML Kit, CameraX, RenderScript, high-speed Camera2 sessions.

---

## 2. Actual Module Structure

```
app/
  src/main/
    kotlin/com/trackspeed/android/
      TrackSpeedApp.kt                  # @HiltAndroidApp
      MainActivity.kt                   # Single activity, Compose host

      detection/                        # Core detection engine
        PhotoFinishDetector.kt          # Main detector (955 lines)
        ZeroAllocCCL.kt                 # CCL with union-find (257 lines)
        RollingShutterCalculator.kt     # Rolling shutter correction (59 lines)
        GateEngine.kt                   # Singleton coordinator (180 lines)

      camera/
        CameraManager.kt               # Camera2 at 30-120fps (456 lines)
        FrameProcessor.kt              # Stub (14 lines, processing in detector)

      audio/
        CrossingFeedback.kt            # Beep + vibration (57 lines)

      sync/
        BleClockSyncService.kt         # BLE GATT clock sync
        ClockSyncCalculator.kt         # NTP-style offset calculation
        ClockSyncConfig.kt             # Sync parameters
        ClockSyncManager.kt            # Sync orchestration

      data/
        local/
          TrackSpeedDatabase.kt        # Room DB v1
          entities/                    # TrainingSession, Run, Athlete
          dao/                         # DAOs for each entity
        repository/
          SessionRepository.kt         # Session + run persistence with thumbnails

      di/
        DatabaseModule.kt              # Room + DAO providers
        SyncModule.kt                  # Clock sync providers

      ui/
        theme/                         # Material 3 colors, type, theme
        navigation/NavGraph.kt         # All routes
        components/CameraPreview.kt    # SurfaceView + gate line overlay
        screens/
          home/HomeScreen.kt           # Bottom nav (Home/History/Settings)
          timing/                      # BasicTimingScreen + ViewModel
          history/                     # Session list + detail screens
          settings/SettingsScreen.kt   # Preferences
          sync/ClockSyncScreen.kt      # BLE sync UI

    res/
      values/
      drawable/
```

---

## 3. Core Components Specification

### 3.1 Camera Manager

**Purpose:** Manage Camera2 API for standard-speed capture (30-120fps)

The CameraManager uses standard Camera2 sessions (NOT high-speed sessions). It operates in "point and shoot" mode with auto-exposure, matching the iOS CameraManager.swift behavior.

```kotlin
@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val TARGET_FPS_ORDER = intArrayOf(120, 60, 30)
    }

    // Prefers highest FPS up to 120, with at least 720p resolution
    fun initialize(useFrontCamera: Boolean = false): Boolean
    fun openCamera(previewSurface: Surface?, callback: FrameCallback)
    fun switchCamera(previewSurface: Surface?, callback: FrameCallback)
    fun closeCamera()
    fun getAchievedFps(): Int
    fun getSensorOrientation(): Int
}
```

**Key implementation details:**
- Uses `CameraDevice.TEMPLATE_RECORD` for capture requests
- Auto-exposure (`CONTROL_AE_MODE_ON`) -- lets Android handle brightness
- Focus locked at ~1.5-2.5m range (`CONTROL_AF_MODE_OFF` with calculated distance)
- Video stabilization disabled (need raw frames)
- HDR disabled (causes frame drops)
- YUV_420_888 format, 3-image buffer via ImageReader
- Separate HandlerThreads for camera and image processing

### 3.2 PhotoFinishDetector

**Purpose:** Per-frame detection using frame differencing + CCL blob analysis

This is the main detection class. It processes every camera frame and determines if an athlete is crossing the gate line. Ported from iOS `PhotoFinishDetector.swift`.

```kotlin
class PhotoFinishDetector {
    companion object {
        // Work resolution
        const val WORK_W = 160
        const val WORK_H = 284

        // IMU
        const val GYRO_THRESHOLD = 0.35f
        const val STABLE_DURATION_TO_ARM_S = 0.5f

        // Detection
        const val DEFAULT_DIFF_THRESHOLD = 14
        const val MIN_BLOB_HEIGHT_FOR_CROSSING = 0.33f
        const val MIN_VELOCITY_PX_PER_SEC = 60.0f
        const val COOLDOWN_DURATION_S = 0.3f

        // Rearm hysteresis
        const val HYSTERESIS_DISTANCE_FRACTION = 0.25f
        const val EXIT_ZONE_FRACTION = 0.35f

        // Trajectory
        const val TRAJECTORY_BUFFER_SIZE = 6
    }

    enum class State {
        UNSTABLE, NO_ATHLETE, ATHLETE_TOO_FAR,
        READY, TRIGGERED, COOLDOWN
    }

    fun processFrame(
        yPlane: ByteArray, width: Int, height: Int,
        rowStride: Int, frameNumber: Long, ptsNanos: Long
    ): DetectionResult
}
```

### 3.3 ZeroAllocCCL

**Purpose:** Connected component labeling with zero steady-state allocations

```kotlin
class ZeroAllocCCL(width: Int, height: Int, maxLabels: Int = 4096) {
    // Pre-allocated: equivalence table, label stats, run buffers
    // Row-run labeling with union-find and path compression

    data class CCLBlob(
        val bboxMinX: Int, val bboxMinY: Int,
        val bboxWidth: Int, val bboxHeight: Int,
        val centroidX: Float, val centroidY: Float,
        val areaPixels: Int, val heightFrac: Float
    )

    fun label(mask: ByteArray): List<CCLBlob>
}
```

### 3.4 RollingShutterCalculator

**Purpose:** Compensate for rolling shutter timing offset

```kotlin
object RollingShutterCalculator {
    // Readout duration estimates by camera type and FPS
    fun getReadoutDuration(isFrontCamera: Boolean, fps: Double): Double
    fun calculateCompensationNanos(
        isFrontCamera: Boolean, fps: Double, chestYNormalized: Float
    ): Long
}
```

### 3.5 GateEngine (Coordinator)

**Purpose:** Coordinate detection components and expose reactive state

```kotlin
@Singleton
class GateEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class EngineState { IDLE, ARMED, DETECTING }

    // Public state flows
    val engineState: StateFlow<EngineState>
    val detectionState: StateFlow<PhotoFinishDetector.State>
    val crossingEvents: SharedFlow<CrossingEvent>
    val gatePosition: StateFlow<Float>
    val fpsDisplay: StateFlow<Double>

    fun configure(fps: Double, isFrontCamera: Boolean)
    fun setGatePosition(position: Float)
    fun processFrame(yPlane: ByteArray, width: Int, height: Int,
                     rowStride: Int, frameNumber: Long, ptsNanos: Long)
    fun startMotionUpdates()
    fun stopMotionUpdates()
    fun reset()
}
```

---

## 4. Communication Layer

### 4.1 BLE Clock Sync Service

The BLE clock sync uses an NTP-style protocol for sub-5ms timing accuracy between devices.

```kotlin
class BleClockSyncService(private val context: Context) {
    // GATT service for clock sync
    // 100 ping/pong samples at 20Hz
    // Keep lowest 20% RTT, use median offset

    data class SyncResult(
        val offsetNanos: Long,
        val uncertaintyMs: Double,
        val quality: SyncQuality
    )

    enum class SyncQuality { EXCELLENT, GOOD, FAIR, POOR, BAD }
}
```

### 4.2 Timing Message Protocol

Messages must match the iOS app format exactly (snake_case JSON fields) for cross-platform compatibility.

### 4.3 Clock Reference

```kotlin
// Android: Use elapsedRealtimeNanos() for all timing
val timestamp = SystemClock.elapsedRealtimeNanos()
// NOT System.nanoTime() - can jump on suspend
```

---

## 5. Data Layer

### 5.1 Room Database

```kotlin
@Database(
    entities = [
        TrainingSessionEntity::class,
        RunEntity::class,
        AthleteEntity::class
    ],
    version = 1
)
abstract class TrackSpeedDatabase : RoomDatabase() {
    abstract fun trainingSessionDao(): TrainingSessionDao
    abstract fun runDao(): RunDao
    abstract fun athleteDao(): AthleteDao
}
```

### 5.2 Session Repository

```kotlin
class SessionRepository @Inject constructor(
    private val sessionDao: TrainingSessionDao,
    private val runDao: RunDao,
    private val context: Context
) {
    // Saves session + runs + JPEG thumbnails
    suspend fun saveSession(name: String?, distance: Double,
                           startType: String, laps: List<SoloLapResult>)
    fun getAllSessions(): Flow<List<TrainingSessionEntity>>
}
```

Thumbnails are stored as JPEG files in the app's internal storage at `{filesDir}/thumbnails/{timestamp}.jpg`.

---

## 6. Supabase Integration

### 6.1 Shared Backend

The app shares the Supabase backend with the iOS app (project `sprint-timer-race`). See `docs/architecture/BACKEND_STRATEGY.md` for full details.

### 6.2 Tables

- `race_events` -- Real-time cross-device timing events
- `sessions` -- Training session metadata
- `runs` -- Individual timing runs
- `crossings` -- Gate crossing details
- `pairing_requests` -- Session code pairing

---

## 7. Threading Model

```
+-------------------------------------------------------------+
|                      MAIN THREAD                              |
|  - UI rendering (Compose)                                     |
|  - User input handling                                        |
|  - Navigation                                                 |
+-------------------------------------------------------------+

+-------------------------------------------------------------+
|                  CAMERA THREAD (HandlerThread)                |
|  - Camera2 session management                                 |
|  - Capture request submission                                 |
+-------------------------------------------------------------+

+-------------------------------------------------------------+
|                  IMAGE THREAD (HandlerThread)                 |
|  - ImageReader callbacks                                      |
|  - Frame extraction (Y-plane luminance)                       |
|  - PhotoFinishDetector.processFrame() execution               |
|  - CCL blob detection                                         |
|  - Gate crossing detection                                    |
+-------------------------------------------------------------+

+-------------------------------------------------------------+
|                   IO COROUTINE DISPATCHER                     |
|  - Room database operations                                   |
|  - Image file I/O (thumbnail JPEG storage)                    |
|  - Supabase network calls                                     |
+-------------------------------------------------------------+
```

Note: There is no separate pose thread -- the Photo Finish mode does not use ML Kit or any pose detection.

---

## 8. Build Configuration

### 8.1 Key Dependencies

```kotlin
// build.gradle.kts (app) - actual dependencies
dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:..."))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:...")

    // Camera2 (direct API, not CameraX)
    // No separate camera library needed -- uses android.hardware.camera2

    // Room
    implementation("androidx.room:room-runtime:...")
    implementation("androidx.room:room-ktx:...")
    ksp("androidx.room:room-compiler:...")

    // Hilt
    implementation("com.google.dagger:hilt-android:...")
    ksp("com.google.dagger:hilt-compiler:...")
    implementation("androidx.hilt:hilt-navigation-compose:...")

    // Supabase (scaffolded, not yet active)
    implementation(platform("io.github.jan-tennert.supabase:bom:..."))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:...")
}
```

**Not included:** ML Kit Pose Detection, CameraX, RenderScript.

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

### 9.1 Unit Tests (To Be Written)

- `PhotoFinishDetectorTest` -- State transitions, crossing detection logic
- `ZeroAllocCCLTest` -- Blob labeling accuracy, union-find correctness
- `ClockSyncCalculatorTest` -- NTP algorithm, offset calculation
- `RollingShutterCalculatorTest` -- Compensation values

### 9.2 Device Testing Matrix

| Device | Expected FPS | Priority |
|--------|-------------|----------|
| Pixel 8 Pro | 120 | High |
| Samsung S24 | 120 | High |
| Pixel 6 | 60 | Medium |
| Mid-range | 30 | Low |

---

## 10. Performance Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| Frame processing | < 8ms at 160x284 | Systrace |
| Memory (active) | < 200MB | Profiler |
| Memory (idle) | < 80MB | Profiler |
| Battery (1hr timing) | < 15% | Manual test |
| App startup | < 2s | Cold start |
| Detection latency | < 1 frame | End-to-end |

---

## Appendix: Differences from v1.0 Specification

The original v1.0 of this document described a "Precision mode" architecture that was designed but never implemented. Here is what changed:

| Feature | v1.0 Spec (Not Built) | v2.0 Actual Implementation |
|---------|----------------------|---------------------------|
| Frame rate | 240fps high-speed | 30-120fps standard |
| Camera session | High-speed session | Regular session |
| Detection | Background model + occupancy | Frame differencing + CCL |
| Pose detection | ML Kit at 30Hz | None |
| Torso tracking | Shoulder/hip landmarks + EMA | Column density scan |
| Gate analysis | 3 vertical strips | Full-frame blob analysis |
| Threshold model | Per-row median + MAD background | Per-frame adaptive diff threshold |
| State machine | 5-state (occupancy levels) | 6-state (velocity + size) |
| Interpolation | Quadratic (3+ samples) | Linear regression (6 trajectory points) |
| Dependencies | ML Kit, high-speed Camera2 | Standard Camera2 only |
| Calibration | Required (30 frames, static scene) | Automatic (warmup noise calibration) |
| Composite buffer | Ring buffer for photo-finish | Not implemented (thumbnails instead) |

---

## Appendix: iOS Source File Reference

| Android Component | iOS Source File |
|-------------------|-----------------|
| `PhotoFinishDetector` | `PhotoFinishDetector.swift` |
| `ZeroAllocCCL` | `ZeroAllocCCL.swift` |
| `RollingShutterCalculator` | `RollingShutterCalculator.swift` |
| `GateEngine` | `GateEngine.swift` |
| `CameraManager` | `CameraManager.swift` (Point & Shoot mode) |
