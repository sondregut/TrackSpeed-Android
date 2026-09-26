# TrackSpeed Android - Architecture Overview

**Version:** 1.0
**Last Updated:** January 2026

---

## 1. Clean Architecture Layers

```
┌────────────────────────────────────────────────────────────────────────┐
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │                      PRESENTATION LAYER                         │   │
│   │                                                                 │   │
│   │   Jetpack Compose UI  ←→  ViewModels  ←→  UI State             │   │
│   │                                                                 │   │
│   └────────────────────────────────────────────────────────────────┘   │
│                                    │                                   │
│                                    │ StateFlow / Events                │
│                                    ▼                                   │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │                        DOMAIN LAYER                             │   │
│   │                                                                 │   │
│   │   Use Cases  ←→  Domain Models  ←→  Repository Interfaces      │   │
│   │                                                                 │   │
│   └────────────────────────────────────────────────────────────────┘   │
│                                    │                                   │
│                                    │ Implementations                   │
│                                    ▼                                   │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │                         DATA LAYER                              │   │
│   │                                                                 │   │
│   │   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐      │   │
│   │   │  Local   │  │  Remote  │  │  Camera  │  │   BLE    │      │   │
│   │   │   Room   │  │ Supabase │  │ Camera2  │  │ Android  │      │   │
│   │   └──────────┘  └──────────┘  └──────────┘  └──────────┘      │   │
│   │                                                                 │   │
│   └────────────────────────────────────────────────────────────────┘   │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │                         ENGINE LAYER                            │   │
│   │                    (Core Detection Logic)                       │   │
│   │                                                                 │   │
│   │   Pure Kotlin algorithms - no Android dependencies              │   │
│   │                                                                 │   │
│   └────────────────────────────────────────────────────────────────┘   │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Dependency Flow

```
UI (Compose)
    │
    │ observes
    ▼
ViewModel
    │
    │ calls
    ▼
Use Case
    │
    │ uses
    ▼
Repository Interface ◄─── Repository Implementation
    │                              │
    │                              │ uses
    │                              ▼
    │                    Data Sources (Room, Supabase, etc.)
    │
    │ also uses
    ▼
Engine (GateEngine, CrossingDetector, etc.)
```

**Dependency Rule:** Inner layers know nothing about outer layers. Dependencies point inward.

---

## 3. Key Components

### 3.1 Presentation Layer

```
ui/
├── screens/
│   ├── home/
│   │   ├── HomeScreen.kt           # Compose UI
│   │   └── HomeViewModel.kt        # State management
│   │
│   ├── basic/
│   │   ├── BasicTimingScreen.kt
│   │   └── BasicTimingViewModel.kt
│   │
│   ├── race/
│   │   ├── RaceModeScreen.kt
│   │   ├── RaceModeViewModel.kt
│   │   ├── DevicePairingScreen.kt
│   │   └── DevicePairingViewModel.kt
│   │
│   ├── calibration/
│   │   ├── CalibrationScreen.kt
│   │   └── CalibrationViewModel.kt
│   │
│   ├── timing/
│   │   ├── ActiveTimingScreen.kt
│   │   └── ActiveTimingViewModel.kt
│   │
│   ├── results/
│   │   ├── ResultsScreen.kt
│   │   └── ResultsViewModel.kt
│   │
│   └── history/
│       ├── HistoryScreen.kt
│       └── HistoryViewModel.kt
│
├── components/
│   ├── CameraPreview.kt            # Camera preview composable
│   ├── GateLineOverlay.kt          # Draggable gate line
│   ├── BubbleLevel.kt              # Level indicator
│   ├── TimeDisplay.kt              # Large time text
│   └── PhotoFinishViewer.kt        # Horizontal scrolling composite
│
├── navigation/
│   └── NavGraph.kt                 # Navigation compose
│
└── theme/
    ├── Theme.kt
    ├── Color.kt
    └── Typography.kt
```

### 3.2 Domain Layer

```
domain/
├── model/
│   ├── Session.kt                  # Domain model
│   ├── Run.kt
│   ├── Crossing.kt
│   ├── Athlete.kt
│   ├── GateRole.kt
│   └── SyncQuality.kt
│
├── repository/
│   ├── SessionRepository.kt        # Interface
│   ├── CrossingRepository.kt
│   └── UserRepository.kt
│
└── usecase/
    ├── session/
    │   ├── CreateSessionUseCase.kt
    │   ├── GetSessionsUseCase.kt
    │   └── DeleteSessionUseCase.kt
    │
    ├── timing/
    │   ├── StartTimingUseCase.kt
    │   ├── ProcessCrossingUseCase.kt
    │   └── CalculateSplitTimeUseCase.kt
    │
    └── sync/
        ├── SyncClocksUseCase.kt
        └── ConnectDeviceUseCase.kt
```

### 3.3 Data Layer

```
data/
├── local/
│   ├── db/
│   │   ├── TrackSpeedDatabase.kt
│   │   ├── entity/
│   │   │   ├── SessionEntity.kt
│   │   │   ├── RunEntity.kt
│   │   │   ├── CrossingEntity.kt
│   │   │   └── AthleteEntity.kt
│   │   ├── dao/
│   │   │   ├── SessionDao.kt
│   │   │   ├── RunDao.kt
│   │   │   ├── CrossingDao.kt
│   │   │   └── AthleteDao.kt
│   │   └── Converters.kt
│   │
│   └── storage/
│       └── ImageStorage.kt         # File system storage
│
├── remote/
│   ├── SupabaseClient.kt
│   ├── dto/
│   │   ├── RaceEventDto.kt
│   │   └── UserProfileDto.kt
│   └── api/
│       ├── RaceEventApi.kt
│       └── AuthApi.kt
│
├── repository/
│   ├── SessionRepositoryImpl.kt
│   ├── CrossingRepositoryImpl.kt
│   └── UserRepositoryImpl.kt
│
└── mapper/
    ├── SessionMapper.kt            # Entity ↔ Domain
    ├── CrossingMapper.kt
    └── RaceEventMapper.kt          # DTO ↔ Domain
```

### 3.4 Engine Layer (Core Detection)

```
engine/
├── camera/
│   ├── CameraManager.kt            # Camera2 API wrapper
│   ├── FrameProcessor.kt           # Frame extraction
│   ├── CameraCapability.kt         # Device capability detection
│   └── ImageUtils.kt               # YUV conversion utilities
│
├── detection/
│   ├── BackgroundModel.kt          # Background subtraction
│   ├── CrossingDetector.kt         # State machine
│   ├── ContiguousRunFilter.kt      # Torso filtering
│   ├── DetectionState.kt           # State enum
│   └── DetectionConfig.kt          # Thresholds config
│
├── pose/
│   ├── PoseService.kt              # ML Kit wrapper
│   └── TorsoBoundsStore.kt         # Thread-safe bounds
│
├── composite/
│   ├── CompositeBuffer.kt          # Photo-finish buffer
│   └── CompositeExporter.kt        # PNG export
│
├── GateEngine.kt                   # Orchestrator
└── GateEngineState.kt              # Engine state sealed class
```

### 3.5 Communication Layer

```
communication/
├── transport/
│   ├── Transport.kt                # Interface
│   ├── BleTransport.kt             # Bluetooth LE
│   ├── SupabaseTransport.kt        # Cloud relay
│   └── MockTransport.kt            # Testing
│
├── protocol/
│   ├── TimingMessage.kt            # Message types
│   ├── MessageSerializer.kt        # JSON serialization
│   └── MessageValidator.kt         # Validation
│
├── sync/
│   ├── ClockSyncService.kt         # NTP-style sync
│   └── SyncQualityMonitor.kt       # Quality tracking
│
├── discovery/
│   ├── BleAdvertiser.kt            # Peripheral mode
│   ├── BleScanner.kt               # Central mode
│   └── SessionCodeManager.kt       # 6-digit codes
│
└── session/
    ├── RaceSession.kt              # Multi-device session
    ├── RaceSessionState.kt         # Session state machine
    └── DeviceInfo.kt               # Connected device info
```

---

## 4. State Management

### 4.1 ViewModel State Pattern

```kotlin
// UI State - immutable data class
data class BasicTimingUiState(
    val engineState: GateEngineState = GateEngineState.Idle,
    val gatePosition: Float = 0.5f,
    val currentFps: Int = 0,
    val lastCrossing: Crossing? = null,
    val crossingHistory: List<Crossing> = emptyList(),
    val isCalibrated: Boolean = false,
    val errorMessage: String? = null
)

// ViewModel with StateFlow
@HiltViewModel
class BasicTimingViewModel @Inject constructor(
    private val gateEngine: GateEngine,
    private val processedCrossingUseCase: ProcessCrossingUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(BasicTimingUiState())
    val uiState: StateFlow<BasicTimingUiState> = _uiState.asStateFlow()

    // One-time events (navigation, snackbars)
    private val _events = Channel<BasicTimingEvent>()
    val events = _events.receiveAsFlow()

    init {
        observeEngineState()
    }

    private fun observeEngineState() {
        viewModelScope.launch {
            gateEngine.state.collect { state ->
                _uiState.update { it.copy(engineState = state) }

                if (state is GateEngineState.CrossingDetected) {
                    processCrossing(state.result)
                }
            }
        }
    }

    fun startCalibration() {
        viewModelScope.launch {
            gateEngine.startCalibration()
        }
    }

    // ... other actions
}
```

### 4.2 Navigation Events

```kotlin
sealed class BasicTimingEvent {
    data class NavigateToResults(val crossingId: String) : BasicTimingEvent()
    data class ShowError(val message: String) : BasicTimingEvent()
    object CalibrationComplete : BasicTimingEvent()
}
```

---

## 5. Dependency Injection (Hilt)

### 5.1 Module Structure

```kotlin
// AppModule.kt - Application-wide singletons
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient {
        return createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }

    @Provides
    @Singleton
    fun provideImageStorage(
        @ApplicationContext context: Context
    ): ImageStorage = ImageStorage(context)
}

// DatabaseModule.kt
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): TrackSpeedDatabase {
        return Room.databaseBuilder(
            context,
            TrackSpeedDatabase::class.java,
            "trackspeed.db"
        ).build()
    }

    @Provides
    fun provideSessionDao(db: TrackSpeedDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideCrossingDao(db: TrackSpeedDatabase): CrossingDao = db.crossingDao()
}

// CameraModule.kt
@Module
@InstallIn(SingletonComponent::class)
object CameraModule {

    @Provides
    @Singleton
    fun provideCameraManager(
        @ApplicationContext context: Context
    ): CameraManager = CameraManager(context)

    @Provides
    @Singleton
    fun providePoseService(
        @ApplicationContext context: Context
    ): PoseService = PoseService(context)

    @Provides
    @Singleton
    fun provideGateEngine(
        cameraManager: CameraManager,
        poseService: PoseService
    ): GateEngine = GateEngine(cameraManager, poseService)
}

// RepositoryModule.kt
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindSessionRepository(
        impl: SessionRepositoryImpl
    ): SessionRepository

    @Binds
    abstract fun bindCrossingRepository(
        impl: CrossingRepositoryImpl
    ): CrossingRepository
}
```

---

## 6. Navigation Architecture

```kotlin
// NavGraph.kt
@Composable
fun TrackSpeedNavGraph(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        // Home
        composable(Screen.Home.route) {
            HomeScreen(
                onBasicModeClick = { navController.navigate(Screen.BasicTiming.route) },
                onRaceModeClick = { navController.navigate(Screen.RaceMode.route) },
                onHistoryClick = { navController.navigate(Screen.History.route) }
            )
        }

        // Basic Timing Flow
        composable(Screen.BasicTiming.route) {
            BasicTimingScreen(
                onCalibrate = { navController.navigate(Screen.Calibration.route) },
                onResults = { crossingId ->
                    navController.navigate(Screen.Results.createRoute(crossingId))
                }
            )
        }

        composable(Screen.Calibration.route) {
            CalibrationScreen(
                onCalibrationComplete = {
                    navController.navigate(Screen.ActiveTiming.route) {
                        popUpTo(Screen.BasicTiming.route)
                    }
                }
            )
        }

        composable(Screen.ActiveTiming.route) {
            ActiveTimingScreen(
                onCrossing = { crossingId ->
                    navController.navigate(Screen.Results.createRoute(crossingId))
                },
                onCancel = { navController.popBackStack() }
            )
        }

        // Results
        composable(
            route = Screen.Results.route,
            arguments = listOf(navArgument("crossingId") { type = NavType.StringType })
        ) { backStackEntry ->
            ResultsScreen(
                crossingId = backStackEntry.arguments?.getString("crossingId") ?: "",
                onAgain = { navController.navigate(Screen.ActiveTiming.route) },
                onDone = { navController.popBackStack(Screen.Home.route, false) }
            )
        }

        // Race Mode Flow
        composable(Screen.RaceMode.route) {
            RaceModeScreen(
                onPairDevices = { navController.navigate(Screen.DevicePairing.route) }
            )
        }

        composable(Screen.DevicePairing.route) {
            DevicePairingScreen(
                onPaired = { navController.navigate(Screen.RaceCalibration.route) }
            )
        }

        // History
        composable(Screen.History.route) {
            HistoryScreen(
                onSessionClick = { sessionId ->
                    navController.navigate(Screen.SessionDetail.createRoute(sessionId))
                }
            )
        }
    }
}

// Screen definitions
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object BasicTiming : Screen("basic_timing")
    object Calibration : Screen("calibration")
    object ActiveTiming : Screen("active_timing")
    object RaceMode : Screen("race_mode")
    object DevicePairing : Screen("device_pairing")
    object RaceCalibration : Screen("race_calibration")
    object History : Screen("history")

    object Results : Screen("results/{crossingId}") {
        fun createRoute(crossingId: String) = "results/$crossingId"
    }

    object SessionDetail : Screen("session/{sessionId}") {
        fun createRoute(sessionId: String) = "session/$sessionId"
    }
}
```

---

## 7. Comparison with iOS Architecture

| Aspect | iOS (Speed Swift) | Android (TrackSpeed) |
|--------|-------------------|---------------------|
| **UI Framework** | SwiftUI | Jetpack Compose |
| **State Management** | @Observable / @State | StateFlow / ViewModel |
| **DI** | Manual / Environment | Hilt |
| **Navigation** | NavigationStack | Navigation Compose |
| **Local DB** | SwiftData | Room |
| **Camera** | AVFoundation | Camera2 |
| **Pose Detection** | Apple Vision | ML Kit |
| **Async** | Swift Concurrency | Kotlin Coroutines |
| **BLE** | CoreBluetooth | Android BLE |

### Architecture Mapping

```
iOS                              Android
───────────────────────────────────────────────
SwiftUI View         ←→          Compose Screen
@Observable class    ←→          ViewModel
@Model (SwiftData)   ←→          @Entity (Room)
actor               ←→          Mutex / Actor pattern
async/await         ←→          suspend functions
Task { }            ←→          viewModelScope.launch { }
@MainActor          ←→          Dispatchers.Main
Combine Publisher   ←→          StateFlow / Flow
```

---

## 8. Error Handling Strategy

### 8.1 Result Type

```kotlin
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable) : Result<Nothing>()

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    fun exceptionOrNull(): Throwable? = when (this) {
        is Success -> null
        is Error -> exception
    }
}

// Extension for coroutines
suspend fun <T> runCatchingResult(block: suspend () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        Result.Error(e)
    }
}
```

### 8.2 Domain Exceptions

```kotlin
sealed class TrackSpeedException(message: String) : Exception(message) {
    // Camera errors
    class CameraNotAvailable : TrackSpeedException("Camera not available")
    class CameraPermissionDenied : TrackSpeedException("Camera permission denied")
    class HighSpeedNotSupported : TrackSpeedException("High-speed capture not supported")

    // BLE errors
    class BleNotEnabled : TrackSpeedException("Bluetooth not enabled")
    class BleConnectionFailed : TrackSpeedException("BLE connection failed")
    class BleTimeout : TrackSpeedException("BLE operation timed out")

    // Sync errors
    class ClockSyncFailed : TrackSpeedException("Clock synchronization failed")
    class SyncQualityPoor : TrackSpeedException("Clock sync quality too poor")

    // Detection errors
    class CalibrationFailed : TrackSpeedException("Background calibration failed")
    class NoTorsoDetected : TrackSpeedException("No athlete detected in frame")
}
```

---

## 9. Testing Architecture

### 9.1 Test Structure

```
src/
├── main/                       # Production code
├── test/                       # Unit tests
│   ├── engine/
│   │   ├── BackgroundModelTest.kt
│   │   ├── CrossingDetectorTest.kt
│   │   └── ClockSyncServiceTest.kt
│   ├── repository/
│   │   └── SessionRepositoryTest.kt
│   └── viewmodel/
│       └── BasicTimingViewModelTest.kt
│
└── androidTest/                # Instrumentation tests
    ├── db/
    │   └── TrackSpeedDatabaseTest.kt
    ├── camera/
    │   └── CameraManagerTest.kt
    └── ui/
        └── BasicTimingScreenTest.kt
```

### 9.2 Test Doubles

```kotlin
// Fake repository for testing
class FakeSessionRepository : SessionRepository {
    private val sessions = mutableListOf<Session>()

    override fun getSessions(): Flow<List<Session>> = flow {
        emit(sessions.toList())
    }

    override suspend fun createSession(session: Session) {
        sessions.add(session)
    }

    // ... other methods
}

// Mock transport for BLE testing
class MockTransport : Transport {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<TimingMessage>()
    override val incomingMessages = _messages.asSharedFlow()

    // Test helpers
    suspend fun simulateMessage(message: TimingMessage) {
        _messages.emit(message)
    }

    fun simulateConnect() {
        _connectionState.value = ConnectionState.CONNECTED
    }
}
```

---

## 10. Performance Considerations

### 10.1 Memory Management

```kotlin
// Use object pools for frequently allocated objects
object FramePool {
    private val pool = ArrayDeque<ByteArray>(10)
    private val lock = Mutex()

    suspend fun acquire(size: Int): ByteArray {
        lock.withLock {
            return pool.pollFirst()?.takeIf { it.size >= size }
                ?: ByteArray(size)
        }
    }

    suspend fun release(buffer: ByteArray) {
        lock.withLock {
            if (pool.size < 10) {
                pool.addLast(buffer)
            }
        }
    }
}
```

### 10.2 Frame Processing Optimization

```kotlin
// Process frames on dedicated thread
private val cameraDispatcher = newSingleThreadContext("CameraThread")

fun processFrame(image: Image) {
    // Run on camera thread to avoid context switching
    scope.launch(cameraDispatcher) {
        // Fast path - minimal allocations
        val column = extractColumnDirect(image)  // Reuse buffer
        val mask = backgroundModel.getForegroundMaskDirect(column)  // Reuse buffer
        val occupancy = calculateOccupancy(mask, torsoBounds)
        val result = crossingDetector.processFrame(occupancy, timestamp, frameIndex)

        if (result != null) {
            // Switch to main for UI update
            withContext(Dispatchers.Main) {
                _crossingDetected.emit(result)
            }
        }
    }
}
```

---

## Appendix: iOS Code Reference

When implementing Android components, refer to these iOS files for algorithm details:

| Android Component | iOS Reference |
|-------------------|---------------|
| `BackgroundModel` | `speed-swift/.../BackgroundModel.swift` |
| `CrossingDetector` | `speed-swift/.../CrossingDetector.swift` |
| `ClockSyncService` | `speed-swift/.../ClockSyncService.swift` |
| `GateEngine` | `speed-swift/.../GateEngine.swift` |
| `CompositeBuffer` | `speed-swift/.../CompositeBuffer.swift` |
| `MultipeerTransport` | `speed-swift/.../MultipeerTransport.swift` |
