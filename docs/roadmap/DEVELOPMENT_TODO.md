# TrackSpeed Android - Development Roadmap

**Last Updated:** January 2026

---

## Quick Reference

| Phase | Duration | Status |
|-------|----------|--------|
| Phase 1: Project Setup | 1 week | Not Started |
| Phase 2: Camera & Detection | 3 weeks | Not Started |
| Phase 3: Communication | 2 weeks | Not Started |
| Phase 4: UI | 2 weeks | Not Started |
| Phase 5: Integration & Testing | 1 week | Not Started |

**Total Estimated Duration:** 9 weeks

---

## Phase 1: Project Setup (Week 1)

### 1.1 Android Project Creation
- [ ] Create new Android project in Android Studio
  - Application name: TrackSpeed
  - Package name: `com.trackspeed.android`
  - Minimum SDK: API 26 (Android 8.0)
  - Target SDK: API 34 (Android 14)
  - Language: Kotlin
  - Build: Kotlin DSL
- [ ] Configure Gradle version catalogs
- [ ] Set up module structure (app, core, feature modules if needed)

### 1.2 Dependencies Setup
- [ ] Add Jetpack Compose dependencies
- [ ] Add Hilt for dependency injection
- [ ] Add Room for local database
- [ ] Add Camera2 API dependencies
- [ ] Add ML Kit Pose Detection
- [ ] Add Supabase Kotlin client
- [ ] Add Kotlinx Serialization
- [ ] Add Kotlin Coroutines

### 1.3 Project Configuration
- [ ] Configure ProGuard rules
- [ ] Set up BuildConfig for Supabase credentials
- [ ] Configure Android Manifest permissions
- [ ] Set up signing configs for debug/release
- [ ] Create .gitignore for Android project

### 1.4 Base Architecture
- [ ] Create package structure (ui, domain, data, engine, communication)
- [ ] Set up Hilt modules (AppModule, DatabaseModule, etc.)
- [ ] Create base ViewModel class
- [ ] Set up Navigation Compose
- [ ] Create theme (colors, typography, shapes)

### Deliverables
- [ ] Project builds successfully
- [ ] Empty app runs on device/emulator
- [ ] All dependencies resolved

---

## Phase 2: Camera & Detection Engine (Weeks 2-4)

### 2.1 Camera Manager (Week 2)
- [ ] Implement CameraManager class
  - [ ] Camera2 API setup
  - [ ] Device capability detection (max FPS)
  - [ ] High-speed capture session
  - [ ] ImageReader configuration
  - [ ] Preview surface binding
- [ ] Implement frame callback pipeline
- [ ] Add exposure/focus lock functionality
- [ ] Create CameraPreview Compose component
- [ ] Handle camera permissions

### 2.2 Background Model (Week 2)
- [ ] Port BackgroundModel from iOS
  - [ ] Per-row median calculation
  - [ ] MAD (Median Absolute Deviation) calculation
  - [ ] Adaptive threshold computation
  - [ ] Calibration frame collection (30 frames)
  - [ ] Foreground mask generation
  - [ ] Slow background adaptation
- [ ] Unit tests for BackgroundModel

### 2.3 Crossing Detector (Week 3)
- [ ] Port CrossingDetector state machine
  - [ ] State enum (WAITING_FOR_CLEAR, ARMED, CHEST_CROSSING, POSTROLL, COOLDOWN)
  - [ ] Threshold configuration (enter, confirm, exit)
  - [ ] Frame-by-frame state transitions
  - [ ] Sub-frame interpolation
- [ ] Implement ContiguousRunFilter
  - [ ] Longest run calculation
  - [ ] Minimum run validation
- [ ] Unit tests for CrossingDetector

### 2.4 Pose Service (Week 3)
- [ ] Implement PoseService with ML Kit
  - [ ] Pose detector initialization
  - [ ] Image processing pipeline
  - [ ] Shoulder/hip landmark extraction
  - [ ] Normalized torso bounds calculation
  - [ ] EMA smoothing
- [ ] Thread-safe TorsoBoundsStore
- [ ] Integration with frame processing

### 2.5 Composite Buffer (Week 4)
- [ ] Implement CompositeBuffer
  - [ ] Ring buffer for slit data
  - [ ] Timestamp tracking
  - [ ] Trigger frame marking
  - [ ] Bitmap export
  - [ ] PNG file export
- [ ] Photo-finish visualization

### 2.6 Gate Engine (Week 4)
- [ ] Implement GateEngine orchestrator
  - [ ] Component coordination
  - [ ] State management
  - [ ] Calibration flow
  - [ ] Armed state
  - [ ] Crossing detection flow
- [ ] Frame processing pipeline
  - [ ] Column extraction
  - [ ] Background subtraction
  - [ ] Occupancy calculation
  - [ ] Pose-filtered detection
- [ ] Integration tests

### Deliverables
- [ ] Single-phone timing works
- [ ] Background calibration successful
- [ ] Crossings detected accurately
- [ ] Photo-finish images generated
- [ ] Detection accuracy validated

---

## Phase 3: Communication Layer (Weeks 5-6)

### 3.1 Transport Abstraction (Week 5)
- [ ] Define Transport interface
- [ ] Define TimingMessage sealed class
- [ ] Implement JSON serialization for messages
- [ ] Implement binary format for clock sync

### 3.2 BLE Transport (Week 5)
- [ ] Implement BLE advertising (peripheral mode)
  - [ ] GATT server setup
  - [ ] Service and characteristic creation
  - [ ] Advertising start/stop
- [ ] Implement BLE scanning (central mode)
  - [ ] Device discovery
  - [ ] Service filtering
  - [ ] Connection management
- [ ] Implement GATT operations
  - [ ] Read/Write characteristics
  - [ ] Notifications/Indications
  - [ ] MTU negotiation
- [ ] Handle BLE permissions (Android 12+)

### 3.3 Clock Sync Service (Week 5)
- [ ] Implement NTP-style clock sync
  - [ ] Ping/Pong message exchange
  - [ ] T1/T2/T3/T4 timestamp handling
  - [ ] Offset calculation
  - [ ] RTT calculation
  - [ ] Uncertainty estimation
- [ ] Multi-sample averaging
- [ ] Outlier rejection
- [ ] Quality tier assessment
- [ ] Unit tests for sync algorithm

### 3.4 Supabase Transport (Week 6)
- [ ] Implement Supabase Realtime integration
  - [ ] Channel subscription
  - [ ] Broadcast sending
  - [ ] Presence tracking
- [ ] Session code generation/validation
- [ ] Reconnection handling

### 3.5 Race Session Manager (Week 6)
- [ ] Implement RaceSession class
  - [ ] Session state machine
  - [ ] Device management
  - [ ] Role assignment
  - [ ] Event distribution
- [ ] Split time calculation
- [ ] Uncertainty propagation

### Deliverables
- [ ] BLE pairing works (Android ↔ Android)
- [ ] Clock sync achieves < 5ms accuracy
- [ ] Supabase events transmit correctly
- [ ] Two-phone timing works (Android only)

---

## Phase 4: User Interface (Weeks 7-8)

### 4.1 Core Components (Week 7)
- [ ] CameraPreview composable
- [ ] GateLineOverlay (draggable)
- [ ] BubbleLevel indicator
- [ ] TimeDisplay (large monospace)
- [ ] PhotoFinishViewer (horizontal scroll)
- [ ] StatusIndicator (Ready/Not Ready)
- [ ] ConnectionStatus badge

### 4.2 Home Screen (Week 7)
- [ ] Mode selection (Basic / Race)
- [ ] Recent sessions preview
- [ ] Quick start buttons
- [ ] Settings access

### 4.3 Basic Timing Flow (Week 7)
- [ ] BasicTimingScreen
  - [ ] Mode explanation
  - [ ] Start calibration button
- [ ] CalibrationScreen
  - [ ] Camera preview with gate line
  - [ ] Calibration progress
  - [ ] Status feedback
- [ ] ActiveTimingScreen
  - [ ] Full-screen camera
  - [ ] Status overlay
  - [ ] Cancel button
- [ ] ResultsScreen
  - [ ] Large time display
  - [ ] Photo-finish viewer
  - [ ] Again / Done buttons

### 4.4 Race Mode Flow (Week 8)
- [ ] RaceModeScreen
  - [ ] Mode explanation
  - [ ] Create/Join options
- [ ] DevicePairingScreen
  - [ ] BLE scanning
  - [ ] Session code entry
  - [ ] Device list
  - [ ] Connection status
- [ ] SyncStatusScreen
  - [ ] Clock sync progress
  - [ ] Quality indicator
- [ ] RaceTimingScreen
  - [ ] Multi-device status
  - [ ] Waiting state
  - [ ] Results display

### 4.5 History & Settings (Week 8)
- [ ] HistoryScreen
  - [ ] Session list
  - [ ] Date grouping
  - [ ] Search/filter
- [ ] SessionDetailScreen
  - [ ] Runs list
  - [ ] Individual run details
  - [ ] Photo-finish access
- [ ] SettingsScreen
  - [ ] User preferences
  - [ ] Account (if applicable)
  - [ ] About / Help

### 4.6 Navigation (Week 8)
- [ ] Implement NavGraph
- [ ] Deep linking (if needed)
- [ ] Back stack management
- [ ] Screen transitions

### Deliverables
- [ ] All screens implemented
- [ ] Navigation works correctly
- [ ] UI matches design specs
- [ ] Responsive to different screen sizes

---

## Phase 5: Integration & Testing (Week 9)

### 5.1 Cross-Platform Testing
- [ ] Test Android ↔ iOS BLE pairing
- [ ] Verify message format compatibility
- [ ] Validate clock sync across platforms
- [ ] Test timing accuracy cross-platform

### 5.2 iOS App Updates
- [ ] Add BLE GATT server to iOS app
- [ ] Ensure TimingMessage format matches
- [ ] Test iOS as central, Android as peripheral
- [ ] Test Android as central, iOS as peripheral

### 5.3 Device Testing
- [ ] Test on Pixel 8 Pro (240fps)
- [ ] Test on Samsung S24 (240fps)
- [ ] Test on Pixel 6 (120fps)
- [ ] Test on mid-range device (60fps)
- [ ] Document device compatibility

### 5.4 Performance Optimization
- [ ] Profile frame processing
- [ ] Optimize memory usage
- [ ] Test battery consumption
- [ ] Fix thermal throttling issues

### 5.5 Bug Fixes & Polish
- [ ] Fix discovered bugs
- [ ] Improve error handling
- [ ] Add loading states
- [ ] Polish animations
- [ ] Accessibility improvements

### 5.6 Documentation
- [ ] Update README
- [ ] Code documentation
- [ ] User guide (if needed)

### Deliverables
- [ ] Cross-platform timing works
- [ ] All major bugs fixed
- [ ] Performance meets targets
- [ ] Ready for beta testing

---

## Post-MVP Features (Future)

### v1.1 - Enhanced Features
- [ ] Multiple start methods (touch release, audio gun, voice)
- [ ] Athlete management
- [ ] Session cloud sync
- [ ] Data export (CSV/JSON)

### v1.2 - Multi-Gate
- [ ] Support for 3+ phones
- [ ] Intermediate split timing
- [ ] Split analysis dashboard

### v1.3 - Premium
- [ ] RevenueCat integration
- [ ] Subscription tiers
- [ ] Extended history
- [ ] Advanced analytics

### v2.0 - Advanced
- [ ] Video recording
- [ ] Replay with overlay
- [ ] Apple Watch / Wear OS companion
- [ ] Coaching insights

---

## Technical Debt & Known Issues

### To Address
- [ ] (Add items as discovered)

### Won't Fix (MVP)
- [ ] (Add items as decided)

---

## Dependencies on iOS Team

1. **BLE GATT Server** - iOS needs to implement GATT server mode
2. **Message Format** - Both teams must agree on JSON schema
3. **UUID Constants** - Share BLE service/characteristic UUIDs
4. **Testing** - Need iOS device for cross-platform testing

---

## Risk Register

| Risk | Mitigation | Status |
|------|------------|--------|
| Device doesn't support 240fps | Detect capability, graceful degradation | Open |
| BLE connection unstable | Implement reconnection, cloud fallback | Open |
| ML Kit too slow | Separate thread, skip frames | Open |
| Clock sync drift | Periodic re-sync, quality monitoring | Open |

---

## Meeting Notes / Decisions

### [Date] - Initial Planning
- Decided on native Android (not KMP) for MVP
- BLE + Supabase hybrid for communication
- JSON for messages, binary for clock sync

---

## Resources

- **iOS Reference Code:** `/Users/sondre/Documents/App/speed-swift/SprintTimer/`
- **PRD:** `./docs/requirements/PRD.md`
- **Tech Spec:** `./docs/architecture/TECH_SPEC.md`
- **Architecture:** `./docs/architecture/ARCHITECTURE.md`
- **Protocol:** `./docs/protocols/CROSS_PLATFORM_PROTOCOL.md`
