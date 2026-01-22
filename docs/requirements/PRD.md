# TrackSpeed Android - Product Requirements Document

**Version:** 1.0
**Last Updated:** January 2026
**Status:** Draft

---

## 1. Executive Summary

TrackSpeed Android is a native Android application that brings professional-grade sprint timing to Android devices. The app uses high-speed camera capture (120-240fps) combined with computer vision to detect athlete gate crossings with millisecond precision.

The app will be fully compatible with the existing TrackSpeed iOS app (Speed Swift), enabling cross-platform multi-device timing sessions where Android and iOS devices can work together as start/finish gates.

### 1.1 Vision Statement

*Enable coaches and athletes to measure sprint performance with professional accuracy using only their smartphones, regardless of whether they use iOS or Android.*

### 1.2 Key Value Propositions

1. **Professional Accuracy** - Sub-10ms timing accuracy using 240fps capture and sub-frame interpolation
2. **Cross-Platform** - iOS and Android devices work seamlessly together
3. **Accessible** - No expensive equipment required, just smartphones
4. **Flexible** - Single phone lap timing or multi-phone split timing

---

## 2. Target Users

### 2.1 Primary Users

| User Type | Description | Key Needs |
|-----------|-------------|-----------|
| **Track Coaches** | High school, college, club coaches | Accurate split times, session history, athlete tracking |
| **Athletes** | Sprinters, speed athletes | Personal timing, progress tracking |
| **Sports Scientists** | Performance analysts | Data export, precise measurements |

### 2.2 User Personas

**Coach Carlos** - High school track coach
- Has a mix of Android and iPhone among athletes/assistants
- Needs two-phone timing for 40-yard dash testing
- Values simplicity and reliability over features

**Athlete Amara** - College sprinter
- Uses Android phone (Samsung Galaxy)
- Wants to time training runs when coach isn't available
- Needs photo-finish proof for personal records

---

## 3. Product Features

### 3.1 Core Features (MVP)

#### F1: Single-Phone Timing Mode
- **Description:** Use one phone as a lap counter/gate timer
- **Functionality:**
  - Position phone perpendicular to track
  - Calibrate background (30 frames)
  - Detect multiple crossings (laps)
  - Display elapsed time for each crossing
  - Generate photo-finish composite image
- **Acceptance Criteria:**
  - Timing accuracy < 10ms
  - Works in outdoor daylight conditions
  - Minimum 10 crossings per session

#### F2: Two-Phone Timing Mode (Race Mode)
- **Description:** Two phones (start + finish gates) synchronized for split timing
- **Functionality:**
  - Device discovery via BLE or session code
  - Clock synchronization between devices
  - Start gate triggers timer
  - Finish gate stops timer
  - Split time = finish_time - start_time
- **Acceptance Criteria:**
  - Cross-platform: Android↔Android and Android↔iOS
  - Clock sync accuracy < 5ms
  - Works at distances up to 200m (cloud relay)

#### F3: High-Speed Camera Capture
- **Description:** Capture at 120-240fps for precise detection
- **Functionality:**
  - Auto-detect device maximum frame rate
  - Lock exposure/focus after calibration
  - Real-time frame processing pipeline
- **Acceptance Criteria:**
  - Maintain target fps throughout session
  - Graceful degradation on lower-end devices

#### F4: Crossing Detection Engine
- **Description:** Computer vision algorithm to detect athlete gate crossings
- **Functionality:**
  - Background subtraction model
  - Pose detection for torso tracking
  - Hysteresis state machine (prevent double triggers)
  - Sub-frame interpolation for < 1ms precision
  - Photo-finish composite generation
- **Acceptance Criteria:**
  - < 1% false positive rate
  - < 1% missed crossing rate
  - Works with varied clothing colors

#### F5: Session Persistence
- **Description:** Save timing sessions locally
- **Functionality:**
  - Store session metadata (date, distance, athletes)
  - Store individual run times
  - Store photo-finish images
  - Session history browsing
- **Acceptance Criteria:**
  - Sessions persist across app restarts
  - Images stored efficiently (compressed)

### 3.2 Secondary Features (Post-MVP)

#### F6: Cloud Sync
- Sync sessions to Supabase backend
- Share sessions across devices
- Remote multi-device timing

#### F7: Multiple Start Methods
- Flying start (first crossing triggers)
- Touch release (finger lift starts timer)
- Audio gun (sound triggers timer)
- Voice commands ("On your marks, Set, GO!")

#### F8: Athlete Management
- Create athlete profiles
- Assign runs to athletes
- Track athlete progress over time

#### F9: Multi-Gate Timing (3+ phones)
- Intermediate split gates
- 10m, 20m, 30m split analysis

#### F10: Subscription/Premium Features
- Extended session history
- Cloud backup
- Advanced analytics

---

## 4. Functional Requirements

### 4.1 Camera System

| ID | Requirement | Priority |
|----|-------------|----------|
| CAM-01 | Support Camera2 API for manual control | P0 |
| CAM-02 | Capture at device maximum fps (target 240fps) | P0 |
| CAM-03 | Lock auto-exposure after calibration | P0 |
| CAM-04 | Lock auto-focus after calibration | P0 |
| CAM-05 | Support YUV_420_888 format for processing | P0 |
| CAM-06 | Display real-time preview during setup | P0 |
| CAM-07 | Support front and rear cameras | P1 |
| CAM-08 | Handle camera permission gracefully | P0 |

### 4.2 Detection System

| ID | Requirement | Priority |
|----|-------------|----------|
| DET-01 | Background model with per-row statistics | P0 |
| DET-02 | 30-frame calibration capture | P0 |
| DET-03 | Adaptive threshold (MAD-based) | P0 |
| DET-04 | Pose detection at ~30Hz | P0 |
| DET-05 | Torso band filtering | P0 |
| DET-06 | Contiguous run validation | P0 |
| DET-07 | Hysteresis state machine | P0 |
| DET-08 | Sub-frame interpolation | P0 |
| DET-09 | Photo-finish composite buffer | P0 |
| DET-10 | Debug overlay visualization | P1 |

### 4.3 Communication System

| ID | Requirement | Priority |
|----|-------------|----------|
| COM-01 | BLE peripheral mode (advertising) | P0 |
| COM-02 | BLE central mode (scanning) | P0 |
| COM-03 | BLE GATT service for timing protocol | P0 |
| COM-04 | NTP-style clock synchronization | P0 |
| COM-05 | Supabase Realtime integration | P0 |
| COM-06 | Session code generation/joining | P0 |
| COM-07 | Heartbeat/keep-alive mechanism | P0 |
| COM-08 | Automatic reconnection | P1 |

### 4.4 Data Persistence

| ID | Requirement | Priority |
|----|-------------|----------|
| DAT-01 | Room database for session storage | P0 |
| DAT-02 | Image file storage (internal) | P0 |
| DAT-03 | Session CRUD operations | P0 |
| DAT-04 | Data export (CSV/JSON) | P2 |
| DAT-05 | Supabase cloud sync | P1 |

---

## 5. Non-Functional Requirements

### 5.1 Performance

| ID | Requirement | Target |
|----|-------------|--------|
| PERF-01 | Frame processing latency | < 4ms per frame |
| PERF-02 | Crossing detection latency | < 10ms from event |
| PERF-03 | App startup time | < 3 seconds |
| PERF-04 | Memory usage | < 500MB during capture |
| PERF-05 | Battery drain | < 20% per hour of active timing |

### 5.2 Reliability

| ID | Requirement | Target |
|----|-------------|--------|
| REL-01 | Crash-free sessions | > 99% |
| REL-02 | Detection accuracy | > 99% true positives |
| REL-03 | Clock sync accuracy | < 5ms offset |
| REL-04 | BLE connection stability | > 95% uptime |

### 5.3 Compatibility

| ID | Requirement | Target |
|----|-------------|--------|
| COMP-01 | Android version | API 26+ (Android 8.0+) |
| COMP-02 | High-fps devices | Pixel 4+, Samsung S20+, etc. |
| COMP-03 | Cross-platform | iOS 17+ (Speed Swift) |
| COMP-04 | Screen sizes | Phone (not tablet optimized) |

### 5.4 Security

| ID | Requirement | Priority |
|----|-------------|----------|
| SEC-01 | Secure Supabase authentication | P0 |
| SEC-02 | No sensitive data in logs | P0 |
| SEC-03 | BLE pairing security | P1 |

---

## 6. User Interface Requirements

### 6.1 Design Principles

1. **Minimal Setup** - Get to timing as fast as possible
2. **Clear Feedback** - Always show system state (calibrating, armed, etc.)
3. **Large Touch Targets** - Usable on sunny track with sweaty fingers
4. **Consistent with iOS** - Similar UX to Speed Swift for cross-platform users

### 6.2 Key Screens

| Screen | Purpose | Priority |
|--------|---------|----------|
| Home/Dashboard | Mode selection, quick start | P0 |
| Basic Timing | Single-phone lap timing | P0 |
| Race Mode | Two-phone split timing | P0 |
| Device Pairing | BLE/code-based connection | P0 |
| Calibration | Setup assistant, gate positioning | P0 |
| Active Timing | Camera preview, status, cancel | P0 |
| Results | Time display, photo-finish | P0 |
| Session History | Past sessions list | P0 |
| Settings | Preferences, account | P1 |

### 6.3 UI Components

- Camera preview (TextureView/SurfaceView)
- Gate line overlay (draggable)
- Bubble level indicator
- Status indicators (Ready/Not Ready)
- Large time display (monospace font)
- Photo-finish horizontal scroller

---

## 7. Technical Constraints

### 7.1 Android Platform Constraints

1. **Camera2 API Complexity** - More boilerplate than iOS AVFoundation
2. **Device Fragmentation** - Variable fps support across devices
3. **Background Processing** - Android may kill background services
4. **BLE Limitations** - MTU negotiation required for larger packets

### 7.2 Cross-Platform Constraints

1. **No Multipeer Connectivity** - Must use BLE for local pairing
2. **Clock Differences** - Android uses `SystemClock.elapsedRealtimeNanos()`
3. **Protocol Compatibility** - Must match iOS TimingMessage format

---

## 8. Dependencies

### 8.1 External Services

| Service | Purpose | Required |
|---------|---------|----------|
| Supabase | Auth, database, realtime, storage | Yes |
| RevenueCat | Subscription management | Post-MVP |
| Google ML Kit | Pose detection | Yes |

### 8.2 Third-Party Libraries

| Library | Purpose |
|---------|---------|
| Jetpack Compose | UI framework |
| Camera2/CameraX | Camera capture |
| ML Kit Pose | Body pose detection |
| Room | Local database |
| Kotlin Coroutines | Async operations |
| Supabase Kotlin | Backend client |
| Hilt | Dependency injection |

---

## 9. Success Metrics

### 9.1 MVP Success Criteria

- [ ] Single-phone timing works with < 10ms accuracy
- [ ] Two Android phones can pair and time together
- [ ] Android can pair with iOS (Speed Swift)
- [ ] Photo-finish images generate correctly
- [ ] Sessions persist and can be reviewed

### 9.2 Post-Launch KPIs

| Metric | Target |
|--------|--------|
| App crashes | < 1% of sessions |
| Timing accuracy | < 5ms (validated) |
| Cross-platform pairing success | > 90% |
| User retention (7-day) | > 30% |

---

## 10. Risks and Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Device doesn't support high fps | High | Medium | Graceful degradation, device compatibility list |
| BLE connection unreliable | High | Medium | Fallback to cloud relay |
| ML Kit pose detection too slow | Medium | Low | Run on separate thread, skip frames |
| Clock sync drift during race | High | Low | Periodic re-sync, quality monitoring |

---

## 11. Timeline (Estimated)

| Phase | Duration | Deliverable |
|-------|----------|-------------|
| Phase 1: Setup | 1 week | Project structure, dependencies |
| Phase 2: Camera/Detection | 3 weeks | Core timing engine |
| Phase 3: Communication | 2 weeks | BLE + Supabase integration |
| Phase 4: UI | 2 weeks | All MVP screens |
| Phase 5: Testing | 1 week | Device testing, bug fixes |
| **Total MVP** | **9 weeks** | Functional Android app |

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| Gate | Virtual line on camera view that athletes cross |
| Crossing | Event when athlete passes through gate |
| Photo-finish | Composite image showing crossing moment |
| Split time | Time between start and finish gate crossings |
| MAD | Median Absolute Deviation (robust outlier detection) |
| Torso band | Vertical region of frame containing athlete's torso |
| Sub-frame interpolation | Estimating crossing time between frames |

---

## Appendix B: Reference

- iOS App: `/Users/sondre/Documents/App/speed-swift/SprintTimer/`
- Supabase Project: Existing (shared with iOS)
- Design System: Match iOS app theming
