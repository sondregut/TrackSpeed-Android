# iOS to Android Reference Map

**Last Updated:** February 2026

Quick reference for finding iOS source code counterparts for the Android implementation.

> **Note:** This document was updated in v2.0 to reflect the actual Photo Finish mode implementation. The original version referenced Precision mode components (BackgroundModel, CrossingDetector, ContiguousRunFilter, PoseService, TorsoBoundsStore, CompositeBuffer) that were designed but never built in the Android app.

---

## Core Detection Engine

| Android Component | iOS File | Notes |
|-------------------|----------|-------|
| `PhotoFinishDetector` | `PhotoFinishDetector.swift` | Main detection logic: frame diff, CCL, velocity, crossing |
| `ZeroAllocCCL` | `ZeroAllocCCL.swift` | Row-run CCL with union-find, zero steady-state allocations |
| `RollingShutterCalculator` | `RollingShutterCalculator.swift` | Readout duration estimates, compensation calculation |
| `GateEngine` | `GateEngine.swift` | Coordinator exposing reactive state |

---

## Camera System

| Android Component | iOS File | Notes |
|-------------------|----------|-------|
| `CameraManager` | `CameraManager.swift` | Camera2 replaces AVFoundation; Point & Shoot mode |
| Frame extraction | `CameraManager.swift` | Y-plane luminance from YUV_420_888 |
| Focus lock | `CameraManager.swift` | `CONTROL_AF_MODE_OFF` with calculated distance |

---

## Communication Layer

| Android Component | iOS File | Notes |
|-------------------|----------|-------|
| `BleClockSyncService` | `ClockSyncService.swift` | NTP-style BLE clock sync |
| `ClockSyncCalculator` | `ClockSyncService.swift` | Offset and RTT calculation |
| Timing messages | `TimingMessage.swift` | JSON format must match exactly |
| Supabase transport (scaffolded) | `SupabaseBroadcastTransport.swift` | Cloud relay for race events |

---

## Data Models

| Android Entity | iOS Model | Notes |
|----------------|-----------|-------|
| `TrainingSessionEntity` | `TrainingSession` | Room replaces SwiftData |
| `RunEntity` | `Run` | Include splits and thumbnails |
| `AthleteEntity` | `Athlete` | Colors, personal bests |
| Supabase DTOs (scaffolded) | `SupabaseService.swift` | Must match table schemas |

---

## Key Constants Reference

### Detection Constants (PhotoFinishDetector)

```kotlin
// Work resolution
WORK_W = 160
WORK_H = 284

// IMU stability
GYRO_THRESHOLD = 0.35f            // rad/s
STABLE_DURATION_TO_ARM_S = 0.5f   // seconds

// Motion mask
DEFAULT_DIFF_THRESHOLD = 14
MIN_DIFF_THRESHOLD = 8
MAX_DIFF_THRESHOLD = 40

// Blob filtering
MIN_BLOB_HEIGHT_FOR_CROSSING = 0.33f  // 33% of frame height

// Body validation (column density)
MIN_COLUMN_DENSITY_FOR_BODY = WORK_H * 0.15f  // ~42 pixels
MIN_REGION_WIDTH_FOR_BODY = 8                  // consecutive dense columns

// Velocity
MIN_VELOCITY_PX_PER_SEC = 60.0f  // at work resolution

// Timing
COOLDOWN_DURATION_S = 0.3f
WARMUP_DURATION_S = 0.30f
REARM_DURATION_S = 0.2f
MIN_TIME_BETWEEN_CROSSINGS_S = 0.3f
ARMING_GRACE_PERIOD_S = 0.20f

// Rearm hysteresis
HYSTERESIS_DISTANCE_FRACTION = 0.25f
EXIT_ZONE_FRACTION = 0.35f

// Trajectory
TRAJECTORY_BUFFER_SIZE = 6
MIN_DIRECTION_CHANGE_PX = 2.0f
```

### Clock Sync Constants

```kotlin
// Full sync
FULL_SYNC_SAMPLES = 100
FULL_SYNC_INTERVAL_MS = 50        // 20Hz
FULL_SYNC_MAX_RTT_MS = 200
RTT_FILTER_PERCENTILE = 0.20      // Keep lowest 20%
MIN_VALID_SAMPLES = 10

// Quality tiers (uncertaintyMs)
EXCELLENT < 3
GOOD < 5
FAIR < 10
POOR < 15
BAD >= 15
```

---

## UI Screen Mapping

| iOS Screen | Android Screen | Status |
|------------|----------------|--------|
| `DashboardHomeView` | `HomeScreen` | Implemented |
| `BasicModeView` | `BasicTimingScreen` | Implemented |
| `RaceModeView` | `RaceModePlaceholder` | Placeholder |
| `SessionDetailView` | `SessionDetailScreen` | Implemented |
| `SettingsView` | `SettingsScreen` | Implemented |
| `ClockSyncView` | `ClockSyncScreen` | Implemented |
| `CreateSessionFlowView` | Not implemented | -- |
| `JoinSessionView` | Not implemented | -- |
| `TimingSessionView` | Not implemented | -- |

---

## Critical Implementation Notes

### 1. Offset Convention
```
t_remote = t_local + offset
// Positive offset = local behind remote
// This convention MUST match iOS exactly
```

### 2. Nanosecond Timestamps
```kotlin
// Android: Use elapsedRealtimeNanos()
val timestamp = SystemClock.elapsedRealtimeNanos()
// NOT System.nanoTime() - can jump on suspend
```

### 3. Adaptive Noise Calibration
```kotlin
// During warmup (first ~10 frames):
// threshold = median + 3.5 * MAD * 1.4826
// Clamped to [8, 40]
```

### 4. Column Density for Body Position
```kotlin
// Scan columns from leading edge inward
// Column is "dense" if longest contiguous run >= MIN_COLUMN_DENSITY_FOR_BODY (~42px)
// Need MIN_REGION_WIDTH_FOR_BODY (8) consecutive dense columns
// First such column is the chest X position
```

### 5. Sub-Frame Interpolation
```kotlin
// Primary: 6-point linear regression on trajectory buffer
// Fallback: 2-frame linear interpolation
// Must handle int64 nanosecond math carefully
```

### 6. Message Serialization
```kotlin
// JSON for Supabase/general messages (snake_case fields)
// Binary for BLE clock sync (performance)
// Must be byte-compatible with iOS
```

---

## File Path Patterns

### Thumbnail Storage

```
// Android
{context.filesDir}/thumbnails/{timestamp}.jpg
```

### Database

```
// Android (Room)
{context.getDatabasePath("trackspeed_database")}
```

---

## Protocol Version

```swift
kTimingProtocolVersion = 3  // Current version (from iOS)
```

---

## Supabase Configuration

```
// Shared with iOS
supabaseURL = "https://hkvrttatbpjwzuuckbqj.supabase.co"
// Use existing anon key from iOS app

// Tables
- race_events
- sessions
- runs
- crossings
- pairing_requests
- athletes

// Realtime channel pattern
"race_events:session_id=eq.{sessionId}"
```
