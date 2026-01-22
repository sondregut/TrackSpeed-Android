# TrackSpeed Android - Implementation Plan

**Status**: Ready to Start
**Estimated Duration**: 9 weeks

---

## Phase 1: Project Setup (Week 1)

### Step 1.1: Create Android Project
- [ ] Create new project in Android Studio
  - Name: TrackSpeed
  - Package: `com.trackspeed.android`
  - Min SDK: 26 (Android 8.0)
  - Template: Empty Compose Activity
- [ ] Configure Kotlin DSL build files
- [ ] Set up version catalog (`libs.versions.toml`)

### Step 1.2: Add Dependencies
```toml
# Core dependencies to add:
- Jetpack Compose BOM 2024.01.00
- Hilt 2.50
- Room 2.6.1
- Camera2 1.3.1
- ML Kit Pose 18.0.0-beta3
- Supabase Kotlin 2.0.4
- Kotlinx Serialization 1.6.2
- Navigation Compose 2.7.6
```

### Step 1.3: Configure Project
- [ ] Add permissions to AndroidManifest (camera, BLE, internet)
- [ ] Create `local.properties` with Supabase credentials
- [ ] Set up ProGuard rules
- [ ] Configure BuildConfig for API keys

### Step 1.4: Create Package Structure
```
com.trackspeed.android/
├── di/           # Hilt modules
├── ui/           # Compose screens & components
├── domain/       # Use cases & domain models
├── data/         # Repositories & data sources
├── engine/       # Detection algorithm
└── communication/ # BLE & Supabase transport
```

### Step 1.5: Base Setup
- [ ] Create Application class with Hilt
- [ ] Set up MainActivity with Compose
- [ ] Create theme (colors, typography)
- [ ] Create basic navigation graph

**Deliverable**: App builds and runs with empty home screen

---

## Phase 2: Camera & Detection Engine (Weeks 2-4)

### Step 2.1: Camera Manager (Week 2)
- [ ] Implement `CameraManager` class
  - Camera2 session setup
  - High-speed capture configuration
  - ImageReader for YUV frames
- [ ] Create `CameraPreview` composable
- [ ] Implement exposure/focus locking
- [ ] Add device capability detection (max FPS)
- [ ] Handle camera permissions

**Test**: Camera preview displays at 240fps (or max available)

### Step 2.2: Background Model (Week 2)
- [ ] Port `BackgroundModel` from iOS
  - 30-frame calibration
  - Per-row median calculation
  - MAD-based adaptive thresholds
- [ ] Implement 3-strip extraction
- [ ] Add slow background adaptation
- [ ] Write unit tests

**Test**: Calibration completes, foreground mask generated correctly

### Step 2.3: Crossing Detector (Week 3)
- [ ] Port `CrossingDetector` state machine
  - 5 states: waitingForClear, armed, postroll, cooldown, paused
  - Threshold constants from iOS
- [ ] Implement `ContiguousRunFilter`
  - Chest band calculation
  - Longest run detection
- [ ] Implement 3-strip torso validation
- [ ] Add distance filtering
- [ ] Write unit tests

**Test**: State transitions work correctly with mock data

### Step 2.4: Pose Service (Week 3)
- [ ] Implement `PoseService` with ML Kit
  - Stream mode pose detection
  - Shoulder/hip landmark extraction
- [ ] Create `TorsoBoundsStore` (thread-safe)
- [ ] Add EMA smoothing
- [ ] Process every 8th frame (30Hz at 240fps)

**Test**: Pose bounds update correctly when person visible

### Step 2.5: Sub-Frame Interpolation (Week 4)
- [ ] Implement quadratic interpolation
- [ ] Implement linear fallback
- [ ] Add rolling shutter correction
- [ ] Create `OccupancyHistory` ring buffer

**Test**: Interpolated timestamps accurate to <1ms

### Step 2.6: Composite Buffer (Week 4)
- [ ] Implement ring buffer for slit data
- [ ] Add trigger frame marking
- [ ] Create Bitmap export
- [ ] Save as PNG file

**Test**: Photo-finish images generate correctly

### Step 2.7: Gate Engine Integration (Week 4)
- [ ] Create `GateEngine` orchestrator
- [ ] Wire up all components
- [ ] Implement calibration flow
- [ ] Implement armed state
- [ ] Handle crossing events

**Deliverable**: Single-phone timing works end-to-end

---

## Phase 3: Communication Layer (Weeks 5-6)

### Step 3.1: Transport Abstraction (Week 5)
- [ ] Define `Transport` interface
- [ ] Define `TimingMessage` sealed class
- [ ] Implement JSON serialization (match iOS format exactly)
- [ ] Implement binary format for clock sync

### Step 3.2: BLE Transport (Week 5)
- [ ] Implement GATT server (peripheral mode)
  - Service UUID: `12345678-1234-1000-8000-00805F9B34FB`
  - Timing characteristic
  - Device info characteristic
  - Clock sync characteristic
- [ ] Implement GATT client (central mode)
- [ ] Handle MTU negotiation
- [ ] Implement scanning & advertising
- [ ] Handle Android 12+ BLE permissions

**Test**: Two Android phones can discover and connect

### Step 3.3: Clock Sync Service (Week 5)
- [ ] Port NTP algorithm from iOS
  - 100 samples at 20Hz
  - RTT filtering (lowest 20%)
  - Median offset calculation
- [ ] Implement quality tiers
- [ ] Add drift tracking
- [ ] Write unit tests

**Test**: Clock sync achieves <5ms uncertainty

### Step 3.4: Supabase Transport (Week 6)
- [ ] Set up Supabase client
- [ ] Implement Realtime channel subscription
- [ ] Implement broadcast sending
- [ ] Add presence tracking
- [ ] Handle reconnection

**Test**: Events sync via Supabase Realtime

### Step 3.5: Race Session Manager (Week 6)
- [ ] Implement session state machine
- [ ] Handle device pairing flow
- [ ] Implement role assignment (start/finish)
- [ ] Calculate split times with uncertainty

**Deliverable**: Two Android phones can time together

---

## Phase 4: User Interface (Weeks 7-8)

### Step 4.1: Core Components (Week 7)
- [ ] `CameraPreviewComposable` - Full-screen camera
- [ ] `GateLineOverlay` - Draggable gate position
- [ ] `BubbleLevel` - Perpendicular indicator
- [ ] `TimeDisplay` - Large monospace time
- [ ] `PhotoFinishViewer` - Horizontal scroll composite
- [ ] `StatusIndicator` - Ready/Not Ready badge

### Step 4.2: Home & Navigation (Week 7)
- [ ] `HomeScreen` - Mode selection
- [ ] Bottom navigation setup
- [ ] Navigation graph with all routes

### Step 4.3: Basic Timing Flow (Week 7)
- [ ] `BasicTimingScreen` - Single phone mode entry
- [ ] `CalibrationScreen` - Setup with gate line
- [ ] `ActiveTimingScreen` - Live detection
- [ ] `ResultsScreen` - Time + photo-finish display

### Step 4.4: Race Mode Flow (Week 8)
- [ ] `RaceModeScreen` - Multi-phone mode entry
- [ ] `DevicePairingScreen` - BLE scan + session codes
- [ ] `SyncStatusScreen` - Clock sync progress
- [ ] `RaceTimingScreen` - Coordinated timing

### Step 4.5: History & Settings (Week 8)
- [ ] `HistoryScreen` - Session list
- [ ] `SessionDetailScreen` - Runs list
- [ ] `SettingsScreen` - Preferences

**Deliverable**: Complete UI matching iOS app functionality

---

## Phase 5: Integration & Testing (Week 9)

### Step 5.1: Cross-Platform Testing
- [ ] Test Android ↔ iOS BLE pairing
- [ ] Verify message format compatibility
- [ ] Validate clock sync accuracy cross-platform
- [ ] Test split time calculation

### Step 5.2: iOS App Updates (if needed)
- [ ] Add BLE GATT server to iOS
- [ ] Ensure TimingMessage format matches
- [ ] Test bidirectional pairing

### Step 5.3: Device Testing
- [ ] Pixel 8 Pro (240fps)
- [ ] Samsung S24 (240fps)
- [ ] Pixel 6 (120fps)
- [ ] Mid-range device (60fps)

### Step 5.4: Performance & Polish
- [ ] Profile frame processing
- [ ] Optimize memory usage
- [ ] Test battery consumption
- [ ] Fix bugs and edge cases

**Deliverable**: Production-ready Android app

---

## Implementation Order Summary

```
Week 1: Project setup, dependencies, base architecture
Week 2: CameraManager, BackgroundModel
Week 3: CrossingDetector, PoseService
Week 4: Interpolation, CompositeBuffer, GateEngine
Week 5: BLE Transport, Clock Sync
Week 6: Supabase Transport, Race Session
Week 7: UI Components, Basic Timing Flow
Week 8: Race Mode UI, History, Settings
Week 9: Cross-platform testing, Polish
```

---

## Getting Started

To begin implementation:

1. Open Android Studio
2. Create new project with settings from Step 1.1
3. Follow steps in order, checking off as completed
4. Reference `docs/architecture/` for detailed specs
5. Reference iOS code at `/Users/sondre/Documents/App/speed-swift/SprintTimer/`

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Device doesn't support 240fps | Detect capability, use max available, show warning |
| BLE unreliable | Implement reconnection, fallback to Supabase |
| ML Kit too slow | Separate thread, increase frame skip interval |
| Clock sync drift | Periodic mini-sync, drift prediction |
| Cross-platform message mismatch | Strict JSON schema validation, integration tests |
