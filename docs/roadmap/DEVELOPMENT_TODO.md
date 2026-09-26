# TrackSpeed Android - Development Roadmap

**Last Updated:** February 2026

---

## Quick Reference

| Phase | Status |
|-------|--------|
| Phase 1: Project Setup | Completed |
| Phase 2: Detection Engine | Completed |
| Phase 3: Solo Timing UI | Completed |
| Phase 4: Session History + Settings | Completed |
| Phase 5: BLE Clock Sync | Completed (standalone, not wired to timing) |
| Phase 6: Multi-Device Timing | Not Started |
| Phase 7: Polish + Testing | Not Started |

---

## Completed Work

### Phase 1: Project Setup (Done)
- [x] Created Android project (Kotlin, Jetpack Compose, Material 3)
- [x] Package: `com.trackspeed.android`, Min SDK 26
- [x] Added dependencies: Compose, Hilt, Room, Camera2, Navigation
- [x] Set up Hilt DI modules (DatabaseModule, SyncModule)
- [x] Created Material 3 theme (colors, typography)
- [x] Set up navigation graph with all routes
- [x] Created Application class with @HiltAndroidApp

### Phase 2: Detection Engine (Done)
- [x] Ported `PhotoFinishDetector` from iOS `PhotoFinishDetector.swift`
  - Frame differencing with adaptive noise calibration (MAD-based)
  - Downsampling to 160x284 work resolution
  - IMU stability gating (gyroscope at 0.35 rad/s threshold)
  - 6-state detection state machine
  - Velocity filtering (60 px/s minimum)
  - Rearm hysteresis (exit zone + distance fraction)
- [x] Implemented `ZeroAllocCCL` (row-run connected component labeling)
  - Union-find with path compression
  - Pre-allocated buffers for zero steady-state allocations
  - Blob statistics (bbox, centroid, area, heightFrac)
- [x] Implemented `RollingShutterCalculator`
  - Device-agnostic readout duration estimates
  - Compensation calculation based on detection row
- [x] Implemented `GateEngine` coordinator
  - Wraps PhotoFinishDetector
  - Exposes StateFlow for engine state, detection state, crossing events
  - Gate position management
  - IMU monitoring lifecycle
- [x] Implemented `CameraManager` using Camera2 API
  - Standard sessions at 30-120fps (NOT high-speed)
  - Auto-exposure (point and shoot mode)
  - Focus locked at ~1.5-2.5m range
  - Front/back camera switching
  - YUV_420_888 frame extraction
  - FPS statistics

### Phase 3: Solo Timing UI (Done)
- [x] `BasicTimingScreen` -- Full camera preview with timing controls
- [x] Camera preview composable with `SurfaceView`
- [x] Draggable gate line overlay
- [x] Timer display (live updating at 10Hz)
- [x] Start/Stop/Reset controls
- [x] Lap list with grayscale thumbnail capture at each crossing
- [x] Detection state indicator overlay
- [x] Audio beep + haptic feedback on crossing (`CrossingFeedback`)
- [x] Front/back camera switch button

### Phase 4: Session History + Settings (Done)
- [x] Room database (v1) with TrainingSession, Run, Athlete entities
- [x] `SessionRepository` with thumbnail JPEG storage
- [x] Home screen with bottom navigation (Home/History/Settings)
- [x] Session history list view
- [x] Session detail screen
- [x] Settings screen (distance, start type, speed unit, dark mode, sensitivity)
- [x] Navigation graph with all routes

### Phase 5: BLE Clock Sync (Done - Standalone)
- [x] `BleClockSyncService` -- BLE GATT server/client
- [x] `ClockSyncCalculator` -- NTP-style offset calculation
  - 100 samples at 20Hz
  - Lowest 20% RTT filtering
  - Median offset calculation
  - Quality tier assessment
- [x] `ClockSyncManager` -- Orchestration
- [x] `ClockSyncScreen` -- UI for sync process
- [x] Clock sync config constants matching iOS

**Note:** Clock sync is implemented and tested standalone, but not yet wired into the multi-device timing flow.

---

## Remaining Work

### Phase 6: Multi-Device Timing (Not Started)

#### 6.1 Transport Layer
- [ ] Define `Transport` interface
- [ ] Implement `TimingMessage` sealed class (match iOS format exactly)
- [ ] JSON serialization for general messages (snake_case fields)
- [ ] Binary format for BLE clock sync

#### 6.2 BLE Pairing
- [ ] BLE advertising (peripheral mode)
- [ ] BLE scanning (central mode)
- [ ] Device discovery and connection
- [ ] Handle Android 12+ BLE permissions

#### 6.3 Supabase Integration
- [ ] Set up Supabase client with shared credentials
- [ ] Implement Realtime channel subscription for race events
- [ ] Insert race events from local crossings
- [ ] Session code generation for device pairing

#### 6.4 Race Session Manager
- [ ] Session state machine (host/join flow)
- [ ] Device role assignment (start/finish)
- [ ] Cross-device event distribution
- [ ] Split time calculation with uncertainty propagation

#### 6.5 Race Mode UI
- [ ] Replace `RaceModePlaceholder` with full UI
- [ ] Device pairing screen (BLE scan + session codes)
- [ ] Sync status screen (clock sync progress)
- [ ] Race timing screen (multi-device status, waiting state, results)

### Phase 7: Polish + Testing (Not Started)

#### 7.1 Cross-Platform Testing
- [ ] Test Android-to-iOS BLE pairing
- [ ] Verify message format compatibility
- [ ] Validate clock sync accuracy cross-platform
- [ ] Test split time calculation

#### 7.2 Device Testing
- [ ] Test on Pixel 8 Pro (120fps)
- [ ] Test on Samsung S24 (120fps)
- [ ] Test on Pixel 6 (60fps)
- [ ] Test on mid-range device (30fps)
- [ ] Document device compatibility

#### 7.3 Performance
- [ ] Profile frame processing latency
- [ ] Optimize memory usage
- [ ] Test battery consumption over 1hr session
- [ ] Fix any thermal throttling issues

#### 7.4 Bug Fixes + Polish
- [ ] Fix discovered bugs
- [ ] Improve error handling
- [ ] Add loading states
- [ ] Polish animations and transitions
- [ ] Accessibility improvements

---

## Post-MVP Features (Future)

### v1.1 - Enhanced Features
- [ ] Multiple start methods (touch release, audio gun, voice)
- [ ] Athlete management (profiles, colors)
- [ ] Session cloud sync via Supabase
- [ ] Data export (CSV/JSON)

### v1.2 - Multi-Gate
- [ ] Support for 3+ phones
- [ ] Intermediate split timing
- [ ] Split analysis dashboard

### v1.3 - Premium
- [ ] Subscription tiers
- [ ] Extended history
- [ ] Advanced analytics

---

## Technical Debt + Known Issues

### To Address
- [ ] FrameProcessor.kt is a stub -- all processing is in PhotoFinishDetector
- [ ] Experimental 60fps detection mode was designed (docs exist) but never implemented
- [ ] No unit tests yet for detection engine
- [ ] Supabase credentials not yet configured in BuildConfig

### Design Decisions
- Photo Finish mode was chosen over the original Precision mode design because:
  1. It matches the actual iOS app behavior (ported from PhotoFinishDetector.swift)
  2. No ML Kit dependency required
  3. Works with standard Camera2 sessions (no high-speed capture needed)
  4. Simpler calibration (automatic warmup vs. manual 30-frame background collection)

---

## Dependencies on iOS Team

1. **Message Format** -- Both platforms must agree on JSON schema (snake_case)
2. **BLE UUIDs** -- Share BLE service/characteristic UUIDs
3. **Testing** -- Need iOS device for cross-platform testing
4. **Supabase Schema** -- Coordinate any table changes

---

## Resources

- **iOS Reference Code:** `/Users/sondre/Documents/App/speed-swift/SprintTimer/`
- **Detection Algorithm:** `./docs/architecture/DETECTION_ALGORITHM.md`
- **Clock Sync Spec:** `./docs/protocols/CLOCK_SYNC_DETAILS.md`
- **Backend Strategy:** `./docs/architecture/BACKEND_STRATEGY.md`
- **GitHub Repo:** https://github.com/sondregut/TrackSpeed-Android
