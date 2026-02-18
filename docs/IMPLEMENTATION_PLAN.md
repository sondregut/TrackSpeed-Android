# TrackSpeed Android - Implementation Plan

**Status**: Phases 1-5 Complete, Phases 6-7 Remaining
**Last Updated:** February 2026

> **Note:** This document was updated in v2.0 to reflect actual implementation progress. The original plan described a 9-week sequential build using Precision mode (240fps + background model + ML Kit). The actual implementation used Photo Finish mode (30-120fps + frame differencing + CCL) and was built using an AI agent team workflow.

---

## What Has Been Built

### Phase 1: Project Setup (Complete)
- [x] Android project with Kotlin, Jetpack Compose, Material 3
- [x] Hilt DI, Room database, Navigation Compose
- [x] Camera2 API integration (standard sessions, 30-120fps)
- [x] Material 3 theme, application class, manifest permissions

### Phase 2: Detection Engine (Complete)
- [x] `PhotoFinishDetector` -- Frame differencing + CCL blob detection + velocity filtering
- [x] `ZeroAllocCCL` -- Row-run connected component labeling with union-find
- [x] `RollingShutterCalculator` -- Rolling shutter timing compensation
- [x] `GateEngine` -- Coordinator wrapping detector with reactive state
- [x] `CameraManager` -- Camera2 at 30-120fps with auto-exposure
- [x] IMU stability gating via gyroscope

**How it works:**
1. Camera delivers YUV frames at 30-120fps
2. PhotoFinishDetector downsamples to 160x284 and runs frame differencing
3. ZeroAllocCCL finds connected components (blobs) in the motion mask
4. Largest blob is validated (size, velocity, body shape via column density)
5. Gate crossing is detected via sign-flip of chest position relative to gate line
6. Sub-frame timing via 6-point linear regression on trajectory buffer
7. Rolling shutter correction applied based on detection row

### Phase 3: Solo Timing UI (Complete)
- [x] Full-screen camera preview with gate line overlay
- [x] Timer with live updates, start/stop/reset controls
- [x] Lap list with grayscale thumbnails captured at each crossing
- [x] Audio beep + haptic feedback on crossing
- [x] Detection state indicator (unstable/ready/too far/etc.)
- [x] Front/back camera switching

### Phase 4: History + Settings (Complete)
- [x] Room database with TrainingSession, Run, Athlete entities
- [x] Session history list and detail views
- [x] Settings screen (distance, start type, speed unit, sensitivity)
- [x] JPEG thumbnail persistence

### Phase 5: BLE Clock Sync (Complete - Standalone)
- [x] NTP-style clock sync over BLE GATT
- [x] 100 samples at 20Hz, lowest 20% RTT, median offset
- [x] Quality tiers (Excellent/Good/Fair/Poor/Bad)
- [x] Sync UI screen

---

## What Remains to Build

### Phase 6: Multi-Device Timing (Not Started)

This is the main remaining work to enable cross-platform iOS-Android sprint timing.

#### Step 6.1: Transport Layer
- [ ] Define `Transport` interface matching iOS pattern
- [ ] Implement `TimingMessage` sealed class (JSON, snake_case)
- [ ] Wire BLE clock sync into transport abstraction

#### Step 6.2: BLE Device Pairing
- [ ] Advertise as peripheral (GATT server)
- [ ] Scan as central (GATT client)
- [ ] Device discovery, connection, and MTU negotiation
- [ ] Android 12+ BLE permission handling

#### Step 6.3: Supabase Cloud Relay
- [ ] Initialize Supabase client with shared credentials
- [ ] Subscribe to `race_events` Realtime channel
- [ ] Insert crossing events into `race_events` table
- [ ] Session code generation via `pairing_requests` table

#### Step 6.4: Race Session Manager
- [ ] Session state machine (create/join/ready/running/finished)
- [ ] Host: create session, assign roles, start countdown
- [ ] Join: enter session code, receive role assignment
- [ ] Split time calculation with clock offset and uncertainty

#### Step 6.5: Race Mode UI
- [ ] Replace `RaceModePlaceholder` in NavGraph
- [ ] Device pairing screen (scan/connect/session code)
- [ ] Clock sync progress screen
- [ ] Multi-device timing screen with cross-device events

**Deliverable**: Two phones (Android-Android or Android-iOS) can time a sprint together.

### Phase 7: Integration Testing + Polish (Not Started)

#### Step 7.1: Cross-Platform Testing
- [ ] Android-to-iOS BLE pairing
- [ ] Message format compatibility verification
- [ ] Clock sync accuracy cross-platform
- [ ] Split time accuracy validation

#### Step 7.2: Device Testing
- [ ] Pixel 8 Pro, Samsung S24 (120fps)
- [ ] Pixel 6 (60fps), mid-range (30fps)

#### Step 7.3: Performance + Polish
- [ ] Frame processing profiling
- [ ] Memory and battery optimization
- [ ] Error handling improvements
- [ ] UI polish and accessibility

**Deliverable**: Production-ready Android app.

---

## Architecture Decisions

### Why Photo Finish Mode Instead of Precision Mode

The original implementation plan (v1.0) specified a Precision mode with:
- 240fps high-speed Camera2 sessions
- Background model (30-frame calibration, per-row MAD thresholds)
- ML Kit Pose Detection at 30Hz for torso tracking
- 3-strip validation, contiguous run filter, quadratic interpolation

This was replaced with Photo Finish mode because:
1. **Matches iOS reality** -- The iOS app actually uses PhotoFinishDetector.swift, not the Precision mode
2. **No ML dependency** -- Eliminates Google ML Kit as a requirement
3. **Simpler camera** -- Standard Camera2 sessions at 30-120fps work on all devices
4. **Automatic calibration** -- MAD-based warmup replaces manual 30-frame background collection
5. **Proven algorithm** -- Direct port from working iOS code

### Component Mapping (Plan vs. Actual)

| v1.0 Plan | v2.0 Actual | Status |
|-----------|-------------|--------|
| `BackgroundModel` | Not needed | Replaced by frame differencing |
| `CrossingDetector` | `PhotoFinishDetector` | Different algorithm entirely |
| `ContiguousRunFilter` | Column density scan (inside PhotoFinishDetector) | Integrated |
| `PoseService` | Not needed | No pose detection |
| `TorsoBoundsStore` | Not needed | No torso tracking |
| `CompositeBuffer` | Not implemented | Thumbnails used instead |
| `GateEngine` | `GateEngine` | Simplified coordinator |
| `CameraManager` | `CameraManager` | Standard sessions, not high-speed |

---

## Getting Started (For New Contributors)

To continue implementation:

1. Read `CLAUDE.md` for project overview and current state
2. Read `docs/architecture/DETECTION_ALGORITHM.md` for detection algorithm details
3. Read `docs/protocols/CLOCK_SYNC_DETAILS.md` for sync protocol
4. Read `docs/architecture/BACKEND_STRATEGY.md` for Supabase integration
5. Reference iOS code at `/Users/sondre/Documents/App/speed-swift/SprintTimer/`
6. Start with Phase 6: Multi-Device Timing

```bash
# Build and run
./gradlew assembleDebug
./gradlew installDebug
```

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| BLE connection unreliable | Implement reconnection, Supabase cloud fallback |
| Clock sync drift in long sessions | Periodic mini-sync, drift prediction |
| Cross-platform message mismatch | Strict JSON schema validation, integration tests |
| Detection false positives | Tune velocity threshold, blob size filters, column density validation |
| Thermal throttling at 120fps | Frame skip (process every 2nd frame), downsampled processing |
