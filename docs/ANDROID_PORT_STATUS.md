# TrackSpeed Android - Port Status & Implementation Checklist

**Generated:** 2026-02-18
**iOS Reference App:** Speed Swift (`/Users/sondre/Documents/App/speed-swift/SprintTimer/`)
**Android App:** TrackSpeed Android (`/Users/sondre/Documents/App/TrackSpeed-Android/`)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Feature-by-Feature Comparison](#2-feature-by-feature-comparison)
3. [iOS Swift File Mapping](#3-ios-swift-file-mapping)
4. [Missing UI Screens](#4-missing-ui-screens)
5. [Missing Services & Logic](#5-missing-services--logic)
6. [Missing Data Models](#6-missing-data-models)
7. [Priority Implementation Order](#7-priority-implementation-order)

---

## 1. Executive Summary

| Metric | Count |
|--------|-------|
| Android Kotlin files | 37 |
| iOS feature categories | 26 |
| Features Done on Android | 10 |
| Features Partial on Android | 6 |
| Features Missing on Android | 30+ |
| Estimated port completion | ~15-20% |

The Android app has a solid foundation: Camera2 integration, Photo Finish detection (blob-based), BLE clock sync, Room database, and a basic solo timing UI. However, the majority of iOS functionality is missing, most critically the entire multi-phone timing system, Precision detection mode, Supabase cloud integration, and most of the UI polish/flows.

---

## 2. Feature-by-Feature Comparison

### 2.1 Core Detection & Timing

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| Photo Finish detection (blob-based) | Yes | Yes | **Done** | Android uses ZeroAllocCCL, velocity filtering, IMU stability |
| Precision detection (background subtraction) | Yes | No | **Missing** | Dual-loop: 30Hz pose + 240fps crossing. See DETECTION_ALGORITHM.md |
| Experimental 60fps blob detection | Yes | Partial | **Partial** | Android has basic blob detection but not the full experimental mode |
| Background model (calibration, MAD thresholds) | Yes | No | **Missing** | Per-row median/MAD, adaptive thresholds, slow adaptation |
| Crossing detector state machine | Yes | No | **Missing** | WAITING_FOR_CLEAR -> ARMED -> POSTROLL -> COOLDOWN states |
| Contiguous run filter (chest band) | Yes | No | **Missing** | Longest contiguous foreground run in chest band |
| 3-strip torso validation | Yes | No | **Missing** | Left/center/right strips, adjacent threshold 0.55 |
| Sub-frame quadratic interpolation | Yes | No | **Missing** | Quadratic curve fitting for sub-frame crossing time |
| Rolling shutter compensation | Yes | Yes | **Done** | Device-specific readout durations |
| Gate line (vertical slit) | Yes | Yes | **Done** | Draggable gate position with visual feedback |
| IMU stability gate | Yes | Yes | **Done** | Accelerometer/gyroscope phone-steady check |
| Pose detection (ML Kit / Apple Vision) | Yes (Vision) | No | **Missing** | ML Kit Pose Detection for torso bounds (30Hz slow loop) |
| Torso bounds tracking (EMA smoothed) | Yes | No | **Missing** | TorsoBoundsStore with alpha=0.3 smoothing |
| Distance filter (reject far objects) | Yes | No | **Missing** | MIN_TORSO_HEIGHT_FRACTION = 0.12 |
| Composite buffer (photo-finish slit scan) | Yes | No | **Missing** | Accumulates vertical slit columns into composite image |
| Frame scrubber | Yes | No | **Missing** | Scrub through captured frames to review crossing |

### 2.2 Camera

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| High-speed capture (240fps) | Yes | No | **Missing** | Android Camera2 high-speed API needed |
| Standard capture (30-120fps) | Yes | Yes | **Done** | Camera2 with YUV_420_888, auto-exposure |
| Front/back camera switching | Yes | Yes | **Done** | With sensor orientation transform |
| Auto-exposure (point & shoot) | Yes | Yes | **Done** | AE on, focus locked at ~1.5-2.5m |
| Exposure lock for consistency | Yes | Partial | **Partial** | Android caps exposure but doesn't fully lock |
| Camera preview with rotation | Yes | Yes | **Done** | TextureView with matrix transform |
| Thumbnail capture at crossing | Yes | Yes | **Done** | Grayscale Y-plane thumbnail (120x160) |

### 2.3 Solo Timing Mode

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| Start/stop/reset controls | Yes | Yes | **Done** | |
| Live timer display | Yes | Yes | **Done** | 10Hz tick, monospace font |
| Lap recording on crossing | Yes | Yes | **Done** | Lap number, total time, split time |
| Lap cards with thumbnails | Yes | Yes | **Done** | Grayscale thumbnail + gate line overlay |
| Save session to database | Yes | Yes | **Done** | Via SessionRepository |
| Waiting-for-start state | Yes | Yes | **Done** | First crossing = START |
| Audio beep on crossing | Yes | Yes | **Done** | ToneGenerator + haptic |

### 2.4 Multi-Phone Timing

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| RaceSession orchestrator | Yes (~4000 lines) | No | **Missing** | Central state machine for multi-device sessions |
| TimingMessage protocol | Yes (40+ types, 865 lines) | No | **Missing** | JSON messages: handshake, sync, crossing, results |
| Dual-path broadcasting (P2P + cloud) | Yes | No | **Missing** | Messages sent via both transport paths |
| Device roles (start phone / finish phone) | Yes | No | **Missing** | Role assignment during session setup |
| Gate configuration (start, split_1-3, finish) | Yes | No | **Missing** | Up to 5 gates across multiple phones |
| Cross-device split time calculation | Yes | No | **Missing** | Clock-offset-adjusted timing |
| Session code pairing | Yes | No | **Missing** | 6-digit codes for device discovery |
| Multi-athlete support in sessions | Yes | No | **Missing** | Multiple athletes per session, color-coded |
| Reaction time detection | Yes | No | **Missing** | Touch-release, voice command, countdown starts |
| Live timing display (multi-phone) | Yes | No | **Missing** | Real-time splits as athletes cross gates |
| Results aggregation | Yes | No | **Missing** | Combine all gate crossings into final results |

### 2.5 Communication & Transport

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| BLE clock sync (NTP-style) | Yes | Yes | **Done** | Full GATT implementation, 80 samples, RTT filtering |
| BLE data transport | Yes | Partial | **Partial** | Sync works, but no message transport for timing |
| MultipeerConnectivity / WiFi Aware | Yes (MPC) | No | **Missing** | Android equivalent: WiFi Aware or WiFi Direct |
| Supabase Realtime relay | Yes | No | **Missing** | WebSocket subscription for cross-device events |
| Transport abstraction layer | Yes | No | **Missing** | Unified interface for BLE/WiFi/Cloud transport |
| Message serialization (JSON, snake_case) | Yes | No | **Missing** | Must match iOS format exactly |
| Binary BLE message format | Yes | Partial | **Partial** | Clock sync uses binary, but no timing messages |

### 2.6 Clock Synchronization

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| Full sync (80+ samples) | Yes | Yes | **Done** | |
| Mini sync (refresh) | Yes | Partial | **Partial** | Config exists but not auto-triggered |
| RTT filtering (lowest 15-20%) | Yes | Yes | **Done** | |
| Median offset calculation | Yes | Yes | **Done** | |
| Drift tracking (linear regression) | Yes | Yes | **Done** | DriftTracker with 10-minute window |
| Quality tiers (Excellent/Good/Fair/Poor/Bad) | Yes | Yes | **Done** | |
| Sync UI with progress | Yes | Yes | **Done** | Role selection, progress bar, quality display |
| Auto mini-sync during sessions | Yes | No | **Missing** | Periodic refresh every 60s |

### 2.7 Cloud / Supabase Integration

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| Supabase client setup | Yes | No | **Missing** | Supabase Kotlin client needed |
| race_events table sync | Yes | No | **Missing** | Real-time cross-device events |
| sessions table sync | Yes | No | **Missing** | Cloud session metadata |
| runs table sync | Yes | No | **Missing** | Cloud run data |
| crossings table sync | Yes | No | **Missing** | Detailed crossing records |
| pairing_requests table | Yes | No | **Missing** | Session code matching |
| Supabase Realtime subscription | Yes | No | **Missing** | WebSocket for live events |
| Supabase Storage (images) | Yes | No | **Missing** | Cloud thumbnail/image backup |
| Authentication (anonymous + Google) | Yes (Apple) | No | **Missing** | Google Sign-In equivalent |
| Offline-first with sync queue | Yes | No | **Missing** | Write local, sync later pattern |

### 2.8 UI Screens & Navigation

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| Home screen with quick start | Yes | Yes | **Done** | 3-tab layout, quick start grid |
| Recent sessions on home | Yes | Yes | **Done** | Latest sessions list |
| Session history list | Yes | Yes | **Done** | Sorted by date |
| Session detail view | Yes | Yes | **Done** | Stats card + run cards |
| Basic settings screen | Yes | Partial | **Partial** | Missing persistence, many options |
| Solo timing screen | Yes | Yes | **Done** | Camera + timer + laps |
| Clock sync screen | Yes | Yes | **Done** | Role selection + progress + results |
| Race mode placeholder | Yes (full) | Placeholder | **Missing** | Just shows "Coming Soon" |
| Onboarding flow (19 steps) | Yes | No | **Missing** | Permission requests, feature tour |
| Create session flow | Yes | No | **Missing** | Multi-step: distance, start type, gates, athletes |
| Join session flow | Yes | No | **Missing** | Enter session code, pair devices |
| Live timing session view | Yes | No | **Missing** | Active multi-phone timing display |
| Results view (multi-phone) | Yes | No | **Missing** | Combined results from all gates |
| Profile screen | Yes | No | **Missing** | User profile, stats, achievements |
| Athletes management | Yes | No | **Missing** | Add/edit/delete athletes, color picker |
| Athlete detail view | Yes | No | **Missing** | Personal bests, history, stats |
| Templates/Presets | Yes | No | **Missing** | Saved session configurations |
| Start type selection overlay | Yes | No | **Missing** | Touch release, voice command, countdown |
| Calibration screen | Yes | No | **Missing** | Background model calibration flow |
| Device pairing screen | Yes | No | **Missing** | BLE scan, connect, pair |
| Debug overlay | Yes | No | **Missing** | Detection values, occupancy, state |

### 2.9 Audio & Haptics

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| Crossing beep | Yes | Yes | **Done** | ToneGenerator 1000Hz |
| Haptic feedback | Yes | Yes | **Done** | VibrationEffect |
| Voice start (ElevenLabs AI) | Yes | No | **Missing** | "On your marks, set, go!" with AI voice |
| Countdown audio | Yes | No | **Missing** | Beep sequence before start |
| Ready/set/go audio cues | Yes | No | **Missing** | Audio feedback for multi-phone sync |

### 2.10 Data & Persistence

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| Room/SwiftData database | Yes | Yes | **Done** | Room v1 with 3 entities |
| Training session entity | Yes | Partial | **Partial** | Missing many iOS fields (see section 6) |
| Run entity | Yes | Partial | **Partial** | Missing athlete, images, splits fields |
| Athlete entity | Yes | Partial | **Partial** | Missing birthdate, gender, bests |
| Session repository | Yes | Partial | **Partial** | Basic save/load, no cloud sync |
| Image storage service | Yes | No | **Missing** | Thumbnail + full-res file management |
| Session persistence service (full) | Yes | No | **Missing** | Complete session lifecycle management |
| Personal best tracking | Yes | No | **Missing** | Track/display PBs per distance |
| Season best tracking | Yes | No | **Missing** | Track/display SBs per year |

### 2.11 Settings & Configuration

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| Distance preference | Yes | Partial | **Partial** | UI exists but no persistence |
| Start type preference | Yes | Partial | **Partial** | UI exists but no persistence |
| Speed unit (m/s, km/h, mph) | Yes | Partial | **Partial** | UI exists but no persistence |
| Dark mode toggle | Yes | Partial | **Partial** | UI exists but no persistence |
| Detection sensitivity | Yes | Partial | **Partial** | UI exists but no persistence |
| Persistent settings (DataStore) | Yes | No | **Missing** | Need Preferences DataStore |
| Wind speed entry | Yes | No | **Missing** | For wind-adjusted times |
| Photo quality setting | Yes | No | **Missing** | Image resolution/compression |
| Notification preferences | Yes | No | **Missing** | Push notification settings |
| Default gate configuration | Yes | No | **Missing** | Preset gate positions |
| Export settings | Yes | No | **Missing** | CSV/PDF export options |

### 2.12 Monetization & Growth

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| Subscription/paywall | Yes (RevenueCat) | No | **Missing** | In-app purchases |
| Free tier limitations | Yes | No | **Missing** | Feature gating |
| Referral system | Yes | No | **Missing** | Invite friends for rewards |
| Analytics tracking | Yes | No | **Missing** | Usage analytics |

### 2.13 Utilities & Helpers

| Feature | iOS | Android | Status | Notes |
|---------|-----|---------|--------|-------|
| Wind adjustment calculator | Yes | No | **Missing** | IAAF wind correction formulas |
| Distance converter | Yes | No | **Missing** | Meters to yards, etc. |
| Time formatter (athletic format) | Yes | Partial | **Partial** | Basic formatting exists in UI |
| Device identity service | Yes | No | **Missing** | Persistent UUID + device name |
| Connectivity monitor | Yes | No | **Missing** | Network/BLE state tracking |

---

## 3. iOS Swift File Mapping

This section maps every significant iOS feature area to its Android equivalent (or notes it as missing).

### 3.1 Detection & Camera

| iOS Component | iOS Description | Android Equivalent | Status |
|--------------|-----------------|-------------------|--------|
| `CrossingDetector.swift` (~1163 lines) | Precision state machine: WAITING_FOR_CLEAR -> ARMED -> POSTROLL -> COOLDOWN. Enter/confirm/clear thresholds, persistence filtering, 3-strip validation, sub-frame interpolation | None | **Missing** |
| `BackgroundModel.swift` | Per-row median/MAD calibration, adaptive thresholds, foreground masking, slow adaptation | None | **Missing** |
| `ContiguousRunFilter.swift` | Chest band calculation, longest contiguous run, 3-strip analysis | None | **Missing** |
| `GateEngine.swift` (~600+ lines) | Coordinates pose + detection + background model, full pipeline orchestration | `GateEngine.kt` (wrapper for PhotoFinishDetector) | **Partial** - Android wraps blob detector, not precision pipeline |
| `PhotoFinishDetector.swift` | iOS blob-based detection mode | `PhotoFinishDetector.kt` (955 lines) | **Done** - Full blob detection with CCL, velocity filtering |
| `PoseService.swift` | Apple Vision pose detection at 30Hz, torso bounds extraction | None | **Missing** - Need ML Kit Pose Detection |
| `TorsoBoundsStore.swift` | Thread-safe EMA-smoothed torso position tracking | None | **Missing** |
| `CameraManager.swift` | AVCaptureSession, 240fps high-speed, exposure/focus control | `CameraManager.kt` (456 lines) | **Partial** - 30-120fps only, no 240fps high-speed |
| `CompositeBuffer.swift` | Photo-finish slit-scan image accumulation | None | **Missing** |
| `IMUService.swift` | CoreMotion accelerometer/gyroscope for stability detection | Inline in `PhotoFinishDetector.kt` | **Done** - IMU stability gate built into detector |
| `ZeroAllocCCL` (if iOS has equivalent) | Connected component labeling | `ZeroAllocCCL.kt` (257 lines) | **Done** |
| `RollingShutterCalculator` | Per-device readout time compensation | `RollingShutterCalculator.kt` | **Done** |

### 3.2 Multi-Phone Timing

| iOS Component | iOS Description | Android Equivalent | Status |
|--------------|-----------------|-------------------|--------|
| `RaceSession.swift` (~4000 lines) | Central orchestrator: session lifecycle, device management, crossing aggregation, results calculation, state machine | None | **Missing** - Largest single gap |
| `TimingMessage.swift` (865 lines) | 40+ message types: handshake, sync, crossing, results, control. JSON serialization with snake_case | None | **Missing** |
| `TransportService.swift` | Abstraction layer over BLE/WiFi/Cloud transports | None | **Missing** |
| `MultipeerService.swift` | Apple MultipeerConnectivity for local P2P | None | **Missing** - Need WiFi Aware or WiFi Direct |
| `BLETransport.swift` | BLE GATT for data messages (not just clock sync) | `BleClockSyncService.kt` (sync only) | **Partial** - Only clock sync, no message transport |
| `SessionCodeService.swift` | Generate/validate 6-digit pairing codes | None | **Missing** |

### 3.3 Cloud & Backend

| iOS Component | iOS Description | Android Equivalent | Status |
|--------------|-----------------|-------------------|--------|
| `SupabaseService.swift` (~2150 lines) | Full Supabase client: auth, database CRUD, realtime subscriptions, storage | None | **Missing** |
| `CloudRelayService.swift` | Supabase Realtime as fallback transport for timing messages | None | **Missing** |
| `ImageStorageService.swift` | Local + cloud image management | None | **Missing** |
| `SessionPersistenceService.swift` | Complete session save/load/sync lifecycle | `SessionRepository.kt` (basic) | **Partial** - Basic save only |

### 3.4 Clock Sync

| iOS Component | iOS Description | Android Equivalent | Status |
|--------------|-----------------|-------------------|--------|
| `ClockSyncService.swift` | NTP-style sync, multi-sample, RTT filtering | `ClockSyncCalculator.kt` + `ClockSyncManager.kt` | **Done** |
| `DriftTracker.swift` | Linear regression drift prediction | In `ClockSyncCalculator.kt` | **Done** |
| BLE clock sync transport | Binary ping/pong over BLE GATT | `BleClockSyncService.kt` (625 lines) | **Done** |

### 3.5 UI Views

| iOS View | iOS Description | Android Equivalent | Status |
|----------|-----------------|-------------------|--------|
| `ContentView.swift` | Root navigation + tab bar | `NavGraph.kt` + `HomeScreen.kt` | **Done** |
| `HomeView.swift` | Main dashboard with quick start, recent sessions | `HomeScreen.kt` | **Done** |
| `OnboardingView.swift` (19 steps) | Permission requests, feature tour, setup wizard | None | **Missing** |
| `CreateSessionView.swift` | Multi-step session creation flow | None | **Missing** |
| `JoinSessionView.swift` | Enter code, discover & pair devices | None | **Missing** |
| `TimingSessionView.swift` | Live multi-phone timing display | None | **Missing** |
| `SoloTimingView.swift` | Solo practice timing | `BasicTimingScreen.kt` | **Done** |
| `ResultsView.swift` | Multi-gate results with splits | None | **Missing** |
| `RunDetailView.swift` | Individual run breakdown | None | **Missing** |
| `SessionHistoryView.swift` | Session list | `SessionHistoryScreen.kt` | **Done** |
| `SessionDetailView.swift` | Session detail with runs | `SessionDetailScreen.kt` | **Done** |
| `ProfileView.swift` | User profile, stats | None | **Missing** |
| `AthletesView.swift` | Athlete list management | None | **Missing** |
| `AthleteDetailView.swift` | Individual athlete stats, PBs | None | **Missing** |
| `AthleteEditView.swift` | Create/edit athlete | None | **Missing** |
| `SettingsView.swift` | Full settings with persistence | `SettingsScreen.kt` (no persistence) | **Partial** |
| `TemplatesView.swift` | Session presets/templates | None | **Missing** |
| `ClockSyncView.swift` | Sync UI | `ClockSyncScreen.kt` | **Done** |
| `CalibrationView.swift` | Background model calibration | None | **Missing** |
| `DevicePairingView.swift` | BLE device discovery and pairing | None | **Missing** |
| `StartTypeOverlayView.swift` | Touch release, voice, countdown start modes | None | **Missing** |
| `FrameScrubberView.swift` | Review crossing frames | None | **Missing** |
| `DebugOverlayView.swift` | Detection debug values | None | **Missing** |
| `PaywallView.swift` | Subscription purchase | None | **Missing** |
| `ReferralView.swift` | Invite/referral system | None | **Missing** |

### 3.6 Services & Utilities

| iOS Service | iOS Description | Android Equivalent | Status |
|-------------|-----------------|-------------------|--------|
| `VoiceStartService.swift` | ElevenLabs AI voice for "On your marks, set, go!" | None | **Missing** |
| `ElevenLabsService.swift` | AI text-to-speech API client | None | **Missing** |
| `AudioService.swift` | Sound effects, countdown beeps | `CrossingFeedback.kt` (beep only) | **Partial** |
| `WindCalculator.swift` | IAAF wind adjustment formulas | None | **Missing** |
| `DistanceConverter.swift` | Unit conversions (m, yd, ft) | None | **Missing** |
| `TimeFormatter.swift` | Athletic time formatting (MM:SS.cc) | Inline in UI composables | **Partial** |
| `DeviceIdentity.swift` | Persistent device UUID + name | None | **Missing** |
| `UserSettings.swift` | Persistent app preferences | None | **Missing** - Need Preferences DataStore |
| `SubscriptionManager.swift` | RevenueCat integration | None | **Missing** |
| `AnalyticsService.swift` | Usage tracking | None | **Missing** |
| `NotificationService.swift` | Push notifications | None | **Missing** |
| `DeepLinkHandler.swift` | URL scheme handling | None | **Missing** |
| `ConnectivityMonitor.swift` | Network/BLE state tracking | None | **Missing** |
| `HapticService.swift` | Haptic patterns | Inline in `CrossingFeedback.kt` | **Done** |

### 3.7 Data Models

| iOS Model | iOS Description | Android Equivalent | Status |
|-----------|-----------------|-------------------|--------|
| `TrainingSession` (SwiftData) | Full session model with all metadata | `TrainingSessionEntity.kt` (partial) | **Partial** |
| `Run` (SwiftData) | Run with athlete, images, splits | `RunEntity.kt` (partial) | **Partial** |
| `Athlete` (SwiftData) | Athlete with bests, photo, demographics | `AthleteEntity.kt` (partial) | **Partial** |
| `GateConfiguration` | Gate roles, positions, device assignments | None | **Missing** |
| `TimingResult` | Combined multi-gate result | None | **Missing** |
| `SplitTime` | Individual split with uncertainty | None | **Missing** |
| `CrossingEvent` | Detailed crossing data with interpolation | Basic crossing in `GateEngine.kt` | **Partial** |
| `SessionTemplate` | Saved session presets | None | **Missing** |
| `AthleteColor` | Color assignment enum | In `Color.kt` (colors defined) | **Partial** |
| `StartType` | Enum: manual, touchRelease, voiceCommand, countdown | String in settings | **Partial** |
| `GateRole` | Enum: start, split_1-3, finish, lap | None | **Missing** |
| `DeviceRole` | Enum: host, participant | None | **Missing** |

---

## 4. Missing UI Screens

### 4.1 Critical Missing Screens (Required for Core Features)

| Screen | Priority | Complexity | Description |
|--------|----------|------------|-------------|
| **Create Session Flow** | P0 | High | Multi-step wizard: choose distance, start type, number of phones, gate assignment, athlete selection |
| **Join Session Flow** | P0 | Medium | Enter 6-digit code, discover nearby devices, pair via BLE, confirm connection |
| **Live Timing Session** | P0 | Very High | Active multi-phone timing display: live timer, crossing events from all gates, per-athlete tracking |
| **Results View** | P0 | High | Combined results: final times, splits per gate, uncertainty display, comparison table |
| **Device Pairing Screen** | P0 | Medium | BLE scan results, connect/pair flow, connection status indicators |
| **Calibration Screen** | P1 | Medium | Background model calibration: hold phone steady, progress indicator, quality check |

### 4.2 Important Missing Screens

| Screen | Priority | Complexity | Description |
|--------|----------|------------|-------------|
| **Onboarding Flow** | P1 | Medium | 19-step tour: camera permission, BLE permission, feature explanation, setup guide |
| **Athletes Management** | P1 | Medium | List athletes, add/edit with name, nickname, color picker, photo |
| **Athlete Detail** | P1 | Medium | Personal bests per distance, run history, season bests, stats |
| **Profile Screen** | P2 | Low | User profile, total sessions, total athletes, achievements |
| **Templates/Presets** | P2 | Medium | Save/load session configurations (distance, gates, start type) |
| **Start Type Overlay** | P1 | High | Touch release detection, voice command ("Go!"), countdown (3-2-1) |

### 4.3 Nice-to-Have Missing Screens

| Screen | Priority | Complexity | Description |
|--------|----------|------------|-------------|
| **Frame Scrubber** | P3 | Medium | Review frames around crossing, pinch to zoom, frame-by-frame navigation |
| **Debug Overlay** | P3 | Low | Occupancy values, state machine status, detection thresholds, FPS |
| **Paywall** | P3 | Medium | Subscription tiers, feature comparison, purchase flow |
| **Referral** | P4 | Low | Invite code generation, reward tracking |
| **Video Export** | P4 | High | Overlay timing data on video, share as MP4 |
| **Run Detail View** | P2 | Medium | Individual run breakdown: all splits, images per gate, wind adjustment |

### 4.4 Screen Enhancements Needed

| Existing Screen | Enhancement | Priority |
|----------------|-------------|----------|
| **HomeScreen** | Add "Multi-Phone" quick start card, join session button, preset grid | P0 |
| **HomeScreen** | Show sync status indicator, connected devices count | P1 |
| **SettingsScreen** | Persist all settings via DataStore, add many missing options | P1 |
| **BasicTimingScreen** | Add composite photo-finish image view, better lap cards | P2 |
| **SessionDetailScreen** | Add athlete filter, export options, cloud sync status | P2 |
| **SessionHistoryScreen** | Add search, filter by date/distance/athlete, sort options | P2 |

---

## 5. Missing Services & Logic

### 5.1 Critical Missing Services

#### 5.1.1 RaceSession Orchestrator (Highest Priority)

**iOS equivalent:** `RaceSession.swift` (~4000 lines)

This is the single largest missing component. It manages the entire multi-phone timing session lifecycle:

- Session creation and configuration
- Device discovery and pairing
- Role assignment (which phone is start, which is finish)
- Gate configuration management
- Clock synchronization coordination
- Crossing event aggregation from multiple devices
- Split time calculation with clock offset adjustment
- Results computation and display
- Session state machine (setup -> syncing -> ready -> timing -> results)
- Error handling and recovery
- Session persistence

**Estimated effort:** 2-3 weeks

#### 5.1.2 TimingMessage Protocol

**iOS equivalent:** `TimingMessage.swift` (865 lines, 40+ message types)

Complete message protocol for cross-device communication:

```
Categories:
- Handshake: device_info, session_config, role_assignment, ready_ack
- Sync: clock_sync_ping, clock_sync_pong, clock_sync_result
- Timing: crossing_detected, crossing_confirmed, start_event, finish_event
- Control: pause, resume, reset, abort
- Results: run_result, session_result, split_time
- Status: heartbeat, battery_level, camera_status, detection_status
```

All messages must use JSON with snake_case field names to match iOS exactly.

**Estimated effort:** 1 week

#### 5.1.3 Transport Layer

**iOS equivalent:** `TransportService.swift`, `MultipeerService.swift`, `BLETransport.swift`

Unified transport abstraction:

```kotlin
interface TimingTransport {
    fun send(message: TimingMessage)
    val incomingMessages: Flow<TimingMessage>
    val connectionState: StateFlow<ConnectionState>
    fun connect(deviceId: String)
    fun disconnect()
}
```

Implementations needed:
- **BLE Transport**: Extend existing `BleClockSyncService` to carry timing messages
- **WiFi Aware / WiFi Direct**: Android equivalent of MultipeerConnectivity
- **Supabase Realtime**: Cloud relay fallback

**Estimated effort:** 2 weeks

#### 5.1.4 Supabase Integration

**iOS equivalent:** `SupabaseService.swift` (~2150 lines)

Full cloud backend integration:

- Supabase Kotlin client setup (io.github.jan-tennert.supabase)
- Authentication (anonymous + Google Sign-In)
- Database operations (race_events, sessions, runs, crossings, pairing_requests)
- Realtime subscriptions for live timing events
- Storage for image upload/download
- Offline queue for failed operations

**Estimated effort:** 1-2 weeks

### 5.2 Important Missing Services

#### 5.2.1 Precision Detection Pipeline

The entire Precision detection mode from the iOS app:

| Component | Description | Estimated Effort |
|-----------|-------------|-----------------|
| BackgroundModel | Per-row median/MAD calibration, foreground masking | 3 days |
| CrossingDetector | State machine with thresholds, persistence, 3-strip validation | 5 days |
| ContiguousRunFilter | Chest band, longest run, 3-strip analysis | 2 days |
| PoseService (ML Kit) | 30Hz pose detection, torso bounds extraction | 3 days |
| TorsoBoundsStore | EMA-smoothed thread-safe bounds tracking | 1 day |
| Sub-frame interpolation | Quadratic + linear interpolation | 2 days |
| Full GateEngine pipeline | Orchestrate all components | 3 days |

**Total estimated effort:** 2.5 weeks

#### 5.2.2 Persistent Settings (DataStore)

Replace hardcoded defaults with persisted preferences:

```kotlin
// Need Preferences DataStore for:
- Distance (default meters)
- Start type (manual/touchRelease/voice/countdown)
- Speed unit (m/s, km/h, mph)
- Dark mode (system/light/dark)
- Detection sensitivity (low/medium/high)
- Default gate position
- Photo quality
- Notification preferences
- Onboarding completed flag
```

**Estimated effort:** 2 days

#### 5.2.3 DeviceIdentity Service

```kotlin
class DeviceIdentity(context: Context) {
    val deviceId: String  // Persistent UUID
    val deviceName: String  // "Samsung Galaxy S24"
    val platform: String = "android"
}
```

**Estimated effort:** 0.5 days

#### 5.2.4 Image Storage Service

```kotlin
class ImageStorageService(context: Context) {
    suspend fun saveThumbnail(sessionId: String, runId: String, bitmap: Bitmap): String
    suspend fun saveFullRes(sessionId: String, runId: String, bitmap: Bitmap): String
    suspend fun loadImage(path: String): Bitmap?
    suspend fun deleteSession(sessionId: String)
    fun getSessionDirectory(sessionId: String): File
}
```

**Estimated effort:** 2 days

### 5.3 Nice-to-Have Missing Services

| Service | iOS Equivalent | Description | Effort |
|---------|---------------|-------------|--------|
| VoiceStartService | `VoiceStartService.swift` | AI voice "On your marks, set, go!" | 3 days |
| ElevenLabsService | `ElevenLabsService.swift` | Text-to-speech API client | 2 days |
| WindCalculator | `WindCalculator.swift` | IAAF wind correction formulas | 1 day |
| DistanceConverter | `DistanceConverter.swift` | Unit conversions | 0.5 days |
| CompositeBuffer | `CompositeBuffer.swift` | Slit-scan photo-finish image | 3 days |
| SubscriptionManager | `SubscriptionManager.swift` | Google Play Billing | 1 week |
| AnalyticsService | `AnalyticsService.swift` | Firebase Analytics or similar | 2 days |
| ConnectivityMonitor | `ConnectivityMonitor.swift` | Network/BLE state tracking | 1 day |

---

## 6. Missing Data Models

### 6.1 TrainingSessionEntity - Missing Fields

Current Android entity is missing these iOS fields:

```kotlin
// Fields to add to TrainingSessionEntity:
val numberOfPhones: Int = 1          // 1 for solo, 2+ for multi
val numberOfGates: Int = 1           // Number of timing gates
val gateConfigJson: String? = null   // JSON: gate roles + device assignments
val thumbnailPath: String? = null    // Path to session thumbnail
val cloudId: String? = null          // Supabase UUID if synced
val lastSyncedAt: Long? = null       // Last cloud sync timestamp
```

### 6.2 RunEntity - Missing Fields

```kotlin
// Fields to add to RunEntity:
val athleteId: String? = null        // FK to athlete
val athleteColor: String? = null     // Color hex for display
val isSeasonBest: Boolean = false    // Season best flag
val startImagePath: String? = null   // Image at start gate
val finishImagePath: String? = null  // Image at finish gate
val lapImagePathsJson: String? = null // JSON array of lap image paths
val splitsJson: String? = null       // JSON: [{gate, time, uncertainty}]
val numberOfPhones: Int = 1          // Phones used for this run
```

### 6.3 AthleteEntity - Missing Fields

```kotlin
// Fields to add to AthleteEntity:
val birthdate: Long? = null          // Epoch millis
val gender: String? = null           // "male", "female", "other"
val personalBestsJson: String? = null // JSON: {"60m": 7.45, "100m": 11.2}
val seasonBestsJson: String? = null  // JSON: {"2026": {"60m": 7.50}}
val photoData: ByteArray? = null     // Profile photo bytes
val updatedAt: Long = System.currentTimeMillis()
```

### 6.4 Entirely Missing Entities

```kotlin
// New entities needed:

@Entity(tableName = "gate_configurations")
data class GateConfigurationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val gateRole: String,       // "start", "split_1", "split_2", "split_3", "finish", "lap"
    val deviceId: String,
    val gatePosition: Float,
    val orderIndex: Int
)

@Entity(tableName = "split_times")
data class SplitTimeEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val gateRole: String,
    val timeSeconds: Double,
    val uncertaintyMs: Double,
    val deviceId: String,
    val crossingTimeNanos: Long,
    val thumbnailPath: String?
)

@Entity(tableName = "session_templates")
data class SessionTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val distance: Double,
    val startType: String,
    val numberOfPhones: Int,
    val numberOfGates: Int,
    val gateConfigJson: String?,
    val createdAt: Long,
    val updatedAt: Long
)
```

### 6.5 Room Database Migration

Current database is v1. Adding fields requires a migration:

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add missing columns to training_sessions
        db.execSQL("ALTER TABLE training_sessions ADD COLUMN numberOfPhones INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE training_sessions ADD COLUMN numberOfGates INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE training_sessions ADD COLUMN gateConfigJson TEXT")
        db.execSQL("ALTER TABLE training_sessions ADD COLUMN thumbnailPath TEXT")
        db.execSQL("ALTER TABLE training_sessions ADD COLUMN cloudId TEXT")
        db.execSQL("ALTER TABLE training_sessions ADD COLUMN lastSyncedAt INTEGER")

        // Add missing columns to runs
        db.execSQL("ALTER TABLE runs ADD COLUMN athleteId TEXT")
        db.execSQL("ALTER TABLE runs ADD COLUMN athleteColor TEXT")
        db.execSQL("ALTER TABLE runs ADD COLUMN isSeasonBest INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE runs ADD COLUMN startImagePath TEXT")
        db.execSQL("ALTER TABLE runs ADD COLUMN finishImagePath TEXT")
        db.execSQL("ALTER TABLE runs ADD COLUMN lapImagePathsJson TEXT")
        db.execSQL("ALTER TABLE runs ADD COLUMN splitsJson TEXT")
        db.execSQL("ALTER TABLE runs ADD COLUMN numberOfPhones INTEGER NOT NULL DEFAULT 1")

        // Add missing columns to athletes
        db.execSQL("ALTER TABLE athletes ADD COLUMN birthdate INTEGER")
        db.execSQL("ALTER TABLE athletes ADD COLUMN gender TEXT")
        db.execSQL("ALTER TABLE athletes ADD COLUMN personalBestsJson TEXT")
        db.execSQL("ALTER TABLE athletes ADD COLUMN seasonBestsJson TEXT")
        db.execSQL("ALTER TABLE athletes ADD COLUMN updatedAt INTEGER")

        // Create new tables
        db.execSQL("""CREATE TABLE IF NOT EXISTS gate_configurations (
            id TEXT PRIMARY KEY NOT NULL,
            sessionId TEXT NOT NULL,
            gateRole TEXT NOT NULL,
            deviceId TEXT NOT NULL,
            gatePosition REAL NOT NULL,
            orderIndex INTEGER NOT NULL
        )""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS split_times (
            id TEXT PRIMARY KEY NOT NULL,
            runId TEXT NOT NULL,
            gateRole TEXT NOT NULL,
            timeSeconds REAL NOT NULL,
            uncertaintyMs REAL NOT NULL,
            deviceId TEXT NOT NULL,
            crossingTimeNanos INTEGER NOT NULL,
            thumbnailPath TEXT
        )""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS session_templates (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            distance REAL NOT NULL,
            startType TEXT NOT NULL,
            numberOfPhones INTEGER NOT NULL,
            numberOfGates INTEGER NOT NULL,
            gateConfigJson TEXT,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL
        )""")
    }
}
```

---

## 7. Priority Implementation Order

### Phase 1: Foundation Hardening (1-2 weeks)

**Goal:** Make the existing solo mode production-ready.

| Task | Files to Create/Modify | Effort | Why First |
|------|----------------------|--------|-----------|
| 1.1 Persistent settings (DataStore) | `UserSettingsRepository.kt`, modify `SettingsScreen.kt` | 2 days | Settings don't persist between app restarts |
| 1.2 DeviceIdentity service | `DeviceIdentity.kt` | 0.5 days | Required for all multi-device features |
| 1.3 Complete database entities | Modify all 3 entities, add migration | 1 day | Missing fields block future features |
| 1.4 Image storage service | `ImageStorageService.kt` | 1 day | Proper thumbnail management |
| 1.5 Time formatting utility | `TimeFormatter.kt` | 0.5 days | Consistent time display everywhere |
| 1.6 Improve settings screen | Modify `SettingsScreen.kt` | 1 day | Add all missing options with persistence |

### Phase 2: Precision Detection (2-3 weeks)

**Goal:** Port the iOS Precision detection algorithm for accurate timing.

| Task | Files to Create | Effort | Dependency |
|------|----------------|--------|------------|
| 2.1 BackgroundModel | `detection/BackgroundModel.kt` | 3 days | None |
| 2.2 ContiguousRunFilter | `detection/ContiguousRunFilter.kt` | 2 days | None |
| 2.3 CrossingDetector state machine | `detection/CrossingDetector.kt` | 5 days | 2.1, 2.2 |
| 2.4 ML Kit PoseService | `detection/PoseService.kt` | 3 days | Add ML Kit dependency |
| 2.5 TorsoBoundsStore | `detection/TorsoBoundsStore.kt` | 1 day | 2.4 |
| 2.6 Sub-frame interpolation | In `CrossingDetector.kt` | 2 days | 2.3 |
| 2.7 Full GateEngine rewrite | Modify `GateEngine.kt` | 3 days | 2.1-2.6 |
| 2.8 Calibration screen | `ui/screens/calibration/CalibrationScreen.kt` | 2 days | 2.1 |

### Phase 3: Multi-Phone Communication (2-3 weeks)

**Goal:** Enable two phones to communicate for timing.

| Task | Files to Create | Effort | Dependency |
|------|----------------|--------|------------|
| 3.1 TimingMessage protocol | `protocol/TimingMessage.kt` | 3 days | None |
| 3.2 Transport abstraction | `transport/TimingTransport.kt` | 1 day | 3.1 |
| 3.3 BLE message transport | `transport/BleTimingTransport.kt` | 3 days | 3.1, 3.2 |
| 3.4 WiFi Direct transport | `transport/WifiDirectTransport.kt` | 4 days | 3.1, 3.2 |
| 3.5 Device pairing screen | `ui/screens/pairing/DevicePairingScreen.kt` | 3 days | 3.3 |
| 3.6 Session code service | `service/SessionCodeService.kt` | 1 day | None |

### Phase 4: Multi-Phone Timing (3-4 weeks)

**Goal:** Full multi-device timing sessions.

| Task | Files to Create | Effort | Dependency |
|------|----------------|--------|------------|
| 4.1 RaceSession orchestrator | `session/RaceSession.kt` | 2 weeks | Phase 3 |
| 4.2 Create session flow UI | `ui/screens/session/CreateSessionScreen.kt` | 3 days | 4.1 |
| 4.3 Join session flow UI | `ui/screens/session/JoinSessionScreen.kt` | 2 days | 4.1, 3.6 |
| 4.4 Live timing session UI | `ui/screens/session/TimingSessionScreen.kt` | 4 days | 4.1 |
| 4.5 Results view | `ui/screens/results/ResultsScreen.kt` | 3 days | 4.1 |
| 4.6 Start type overlays | `ui/screens/timing/StartTypeOverlay.kt` | 3 days | 4.1 |

### Phase 5: Cloud Integration (1-2 weeks)

**Goal:** Supabase integration for cloud relay and persistence.

| Task | Files to Create | Effort | Dependency |
|------|----------------|--------|------------|
| 5.1 Supabase client setup | `cloud/SupabaseConfig.kt`, `cloud/SupabaseModule.kt` | 1 day | Add Supabase dependency |
| 5.2 Cloud DTOs | `cloud/dto/*.kt` | 1 day | None |
| 5.3 Race events sync | `cloud/RaceEventService.kt` | 2 days | 5.1, 5.2 |
| 5.4 Realtime relay transport | `transport/SupabaseRealtimeTransport.kt` | 2 days | 5.1, Phase 3 |
| 5.5 Pairing via Supabase | `cloud/PairingService.kt` | 1 day | 5.1, 3.6 |
| 5.6 Session cloud backup | Modify `SessionRepository.kt` | 2 days | 5.1, 5.2 |

### Phase 6: Athletes & History (1-2 weeks)

**Goal:** Full athlete management and enhanced history.

| Task | Files to Create | Effort | Dependency |
|------|----------------|--------|------------|
| 6.1 Athletes list screen | `ui/screens/athletes/AthletesScreen.kt` | 2 days | None |
| 6.2 Athlete detail screen | `ui/screens/athletes/AthleteDetailScreen.kt` | 2 days | 6.1 |
| 6.3 Athlete edit screen | `ui/screens/athletes/AthleteEditScreen.kt` | 2 days | 6.1 |
| 6.4 PB/SB tracking logic | `data/repository/AthleteRepository.kt` | 2 days | 6.1 |
| 6.5 Enhanced session history | Modify `SessionHistoryScreen.kt` | 1 day | None |
| 6.6 Run detail view | `ui/screens/history/RunDetailScreen.kt` | 1 day | None |

### Phase 7: Polish & Advanced Features (2-3 weeks)

**Goal:** Feature parity with iOS nice-to-haves.

| Task | Effort | Priority |
|------|--------|----------|
| 7.1 Onboarding flow | 3 days | P2 |
| 7.2 Templates/presets | 2 days | P2 |
| 7.3 Profile screen | 1 day | P2 |
| 7.4 Composite buffer (slit scan) | 3 days | P2 |
| 7.5 Frame scrubber | 2 days | P3 |
| 7.6 Debug overlay | 1 day | P3 |
| 7.7 Wind adjustment calculator | 1 day | P3 |
| 7.8 Voice start (ElevenLabs) | 3 days | P3 |
| 7.9 Video export overlay | 5 days | P4 |
| 7.10 Subscription/paywall | 5 days | P3 |

### Phase 8: High-Speed Camera (Optional, 1 week)

**Goal:** 240fps capture on supported devices.

| Task | Effort | Notes |
|------|--------|-------|
| 8.1 Camera2 high-speed API | 3 days | Only on supported devices |
| 8.2 Constrained high-speed session | 2 days | Requires specific surface configuration |
| 8.3 Device capability detection | 1 day | Graceful fallback to 120/60/30fps |

---

## Appendix A: Total Estimated Effort

| Phase | Duration | Cumulative |
|-------|----------|------------|
| Phase 1: Foundation | 1-2 weeks | 1-2 weeks |
| Phase 2: Precision Detection | 2-3 weeks | 3-5 weeks |
| Phase 3: Communication | 2-3 weeks | 5-8 weeks |
| Phase 4: Multi-Phone Timing | 3-4 weeks | 8-12 weeks |
| Phase 5: Cloud Integration | 1-2 weeks | 9-14 weeks |
| Phase 6: Athletes & History | 1-2 weeks | 10-16 weeks |
| Phase 7: Polish & Advanced | 2-3 weeks | 12-19 weeks |
| Phase 8: High-Speed Camera | 1 week | 13-20 weeks |

**Total estimated: 13-20 weeks for full iOS feature parity.**

**MVP (solo + multi-phone timing): Phases 1-4 = 8-12 weeks.**

---

## Appendix B: Dependency Graph

```
Phase 1 (Foundation)
    │
    ├── Phase 2 (Precision Detection) ──────────────┐
    │                                                │
    ├── Phase 3 (Communication) ─── Phase 4 ────────┤
    │       │                    (Multi-Phone)       │
    │       │                        │               │
    │       └── Phase 5 ─────────────┘               │
    │           (Cloud)                              │
    │                                                │
    ├── Phase 6 (Athletes) ──────────────────────────┤
    │                                                │
    └── Phase 7 (Polish) ───────────────────────────┘
                                                     │
                                            Phase 8 (240fps)
```

---

## Appendix C: Key Files Quick Reference

### Android Files (Existing)

| File | Path | Lines | Status |
|------|------|-------|--------|
| PhotoFinishDetector | `detection/PhotoFinishDetector.kt` | 955 | Done |
| BleClockSyncService | `sync/BleClockSyncService.kt` | 625 | Done |
| CameraManager | `camera/CameraManager.kt` | 456 | Done |
| BasicTimingScreen | `ui/screens/timing/BasicTimingScreen.kt` | ~400 | Done |
| BasicTimingViewModel | `ui/screens/timing/BasicTimingViewModel.kt` | 403 | Done |
| HomeScreen | `ui/screens/home/HomeScreen.kt` | ~350 | Done |
| ClockSyncCalculator | `sync/ClockSyncCalculator.kt` | ~250 | Done |
| ZeroAllocCCL | `detection/ZeroAllocCCL.kt` | 257 | Done |
| CameraPreview | `ui/components/CameraPreview.kt` | 330 | Done |
| GateEngine | `detection/GateEngine.kt` | ~200 | Partial |
| SessionRepository | `data/repository/SessionRepository.kt` | ~150 | Partial |
| NavGraph | `ui/navigation/NavGraph.kt` | 161 | Partial |

### iOS Components Needing Android Equivalents

| iOS Component | Est. Lines | Priority |
|--------------|------------|----------|
| RaceSession | ~4000 | P0 |
| SupabaseService | ~2150 | P1 |
| CrossingDetector (Precision) | ~1163 | P1 |
| TimingMessage | ~865 | P0 |
| BackgroundModel | ~400 | P1 |
| PoseService | ~300 | P1 |
| TransportService | ~500 | P0 |
| VoiceStartService | ~300 | P3 |
| SessionPersistenceService | ~400 | P1 |
| All UI screens | ~5000+ | P0-P3 |
