# TrackSpeed Android

Android version of the iOS app at `Documents/App/speed-swift`. Native Android port of **Speed Swift** -- enables cross-platform multi-device sprint timing where iOS and Android devices can pair together.

## Repositories

- **This repo (Android)**: https://github.com/sondregut/TrackSpeed-Android
- **iOS app (Speed Swift)**: `/Users/sondre/Documents/App/speed-swift/SprintTimer/`

## Project Context

- **Supabase Project**: `sprint-timer-race` (ID: `hkvrttatbpjwzuuckbqj`) - shared with iOS
- **Documentation**: `./docs/` contains PRD, tech specs, and protocols

## Current State (February 2026)

The app has a working solo timing mode with the Photo Finish detection algorithm. Multi-device timing (BLE pairing, cross-platform iOS-Android) is scaffolded but not yet functional. The experimental 60fps blob tracking mode was designed but not implemented in source code.

### What Works
- Solo practice timing (start/stop/lap with gate crossing detection)
- Photo Finish detection mode (frame differencing + CCL blob detection)
- Camera preview with draggable gate line overlay
- Grayscale thumbnail capture at each crossing
- Session history with Room DB persistence
- Settings screen (distance, start type, speed unit, sensitivity)
- Audio beep + haptic feedback on crossing
- Front/back camera switching
- BLE clock sync service (implemented, not wired to timing flow)

### What Is Scaffolded but Not Functional
- Multi-device race mode (placeholder screen)
- Supabase cloud relay for race events
- Athlete management

## Tech Stack

- Kotlin + Jetpack Compose (Material 3)
- Camera2 API (standard sessions at 30-120fps, auto-exposure)
- Room database (local sessions, runs, athletes)
- Hilt for dependency injection
- Coroutines + Flow for async
- No ML Kit, no pose detection, no 240fps high-speed capture

## Architecture Overview

```
com.trackspeed.android/
├── TrackSpeedApp.kt                    # Hilt Application class
├── MainActivity.kt                     # Single activity, Compose host
├── detection/                          # Core detection engine
│   ├── PhotoFinishDetector.kt          # Main detector (frame diff + CCL + velocity)
│   ├── ZeroAllocCCL.kt                 # Row-run connected component labeling
│   ├── RollingShutterCalculator.kt     # Rolling shutter timing compensation
│   └── GateEngine.kt                  # Coordinator, exposes reactive state
├── camera/
│   ├── CameraManager.kt               # Camera2 at 30-120fps, auto-exposure
│   └── FrameProcessor.kt              # Minimal stub (processing in detector)
├── audio/
│   └── CrossingFeedback.kt            # Beep + vibration on crossing
├── sync/
│   ├── BleClockSyncService.kt         # BLE GATT clock sync
│   ├── ClockSyncCalculator.kt         # NTP-style offset calculation
│   ├── ClockSyncConfig.kt             # Sync parameters
│   └── ClockSyncManager.kt            # Sync orchestration
├── data/
│   ├── local/
│   │   ├── TrackSpeedDatabase.kt      # Room DB (v1)
│   │   ├── entities/                  # TrainingSession, Run, Athlete
│   │   └── dao/                       # DAOs for each entity
│   └── repository/
│       └── SessionRepository.kt       # Session + run persistence with thumbnails
├── di/
│   ├── DatabaseModule.kt              # Room + DAO providers
│   └── SyncModule.kt                  # Clock sync providers
└── ui/
    ├── theme/                         # Material 3 colors, type, theme
    ├── navigation/NavGraph.kt         # All routes
    ├── components/CameraPreview.kt    # SurfaceView + gate line overlay
    └── screens/
        ├── home/HomeScreen.kt         # Bottom nav (Home/History/Settings)
        ├── timing/                    # BasicTimingScreen + ViewModel
        ├── history/                   # Session list + detail screens
        ├── settings/SettingsScreen.kt # Preferences
        └── sync/ClockSyncScreen.kt    # BLE sync UI
```

## Detection Algorithm (Photo Finish Mode)

The detection algorithm is ported from iOS `PhotoFinishDetector.swift`. It uses frame differencing with connected-component labeling (CCL) blob detection, not the older Precision mode (background model + pose detection).

### Per-Frame Processing Pipeline

1. **IMU gate** -- gyroscope checks phone stability (threshold 0.35 rad/s)
2. **Downsample luma** -- full frame to 160x284 work resolution
3. **Frame differencing** -- absolute diff vs previous frame, adaptive threshold
4. **Motion mask** -- binary mask of changed pixels
5. **CCL blob detection** -- `ZeroAllocCCL` finds connected components
6. **Size filter** -- largest blob must be >= 33% of frame height
7. **Chest X computation** -- column density scan to find body leading edge
8. **Velocity filter** -- must exceed 60 px/s at work resolution
9. **Gate crossing check** -- chest position crosses gate line
10. **Trajectory regression** -- 6-point linear regression for sub-frame timing
11. **Rolling shutter correction** -- compensates for row-sequential readout

### Key Detection Constants

```
WORK_W = 160, WORK_H = 284           # Downsampled processing resolution
GYRO_THRESHOLD = 0.35 rad/s          # IMU stability gate
MIN_BLOB_HEIGHT = 0.33               # Minimum blob height fraction
MIN_VELOCITY = 60 px/s               # At work resolution
COOLDOWN = 0.3s                      # Between detections
HYSTERESIS_DISTANCE = 0.25           # Fraction of frame width to rearm
EXIT_ZONE = 0.35                     # Blob must exit before rearm
TRAJECTORY_BUFFER = 6 points         # For linear regression interpolation
```

### Adaptive Noise Calibration

During warmup (first ~10 frames at current FPS):
- Collects pixel difference samples across frame pairs
- Computes median and MAD (Median Absolute Deviation)
- Sets threshold = median + 3.5 * MAD * 1.4826
- Clamped to [8, 40] range

### Key Classes

| Class | File | Purpose |
|-------|------|---------|
| `PhotoFinishDetector` | `detection/PhotoFinishDetector.kt` | Main per-frame detection logic with IMU, CCL, velocity, crossing |
| `ZeroAllocCCL` | `detection/ZeroAllocCCL.kt` | Zero-allocation row-run connected component labeling with union-find |
| `RollingShutterCalculator` | `detection/RollingShutterCalculator.kt` | Device-agnostic rolling shutter timing compensation |
| `GateEngine` | `detection/GateEngine.kt` | Singleton coordinator, wraps detector, exposes StateFlow for UI |
| `CameraManager` | `camera/CameraManager.kt` | Camera2 setup at 30-120fps with auto-exposure |
| `CrossingFeedback` | `audio/CrossingFeedback.kt` | Audio beep + haptic vibration on detection |

### Clock Sync (BLE)

- Offset convention: `t_remote = t_local + offset`
- Use `SystemClock.elapsedRealtimeNanos()` (not `System.nanoTime()`)
- NTP-style: 100 samples at 20Hz, keep lowest 20% RTT, median offset
- Quality tiers: Excellent (<3ms), Good (<5ms), Fair (<10ms), Poor (<15ms)

### Cross-Platform Communication

- BLE for local pairing (replaces Multipeer Connectivity)
- Supabase Realtime for remote/cloud relay
- JSON messages must match iOS exactly (snake_case fields)

## Supabase Tables (shared with iOS)

- `race_events` - Real-time cross-device timing
- `sessions` - Training session metadata
- `runs` - Individual timing runs
- `crossings` - Gate crossing details
- `pairing_requests` - Session code pairing

## Commands

```bash
# Build
./gradlew assembleDebug

# Test
./gradlew test

# Run on device
./gradlew installDebug
```

## Development History

This project was built using an AI agent team workflow with Claude Code:

1. **Initial foundation** (commit f65bf34) -- Project setup, Compose UI, Room DB, Camera2, navigation, detection engine scaffolding
2. **Thread safety fixes** (commit 367c66f) -- Fixed race conditions and stability issues
3. **CLAUDE.md + repo setup** (commit 3ede310) -- Added project documentation and GitHub reference
4. **BLE clock sync** (commit 16d5317) -- Implemented NTP-style clock synchronization over BLE with Photo Finish detection validation
5. **Experimental mode docs** (commit c6cff5f) -- Designed 60fps blob tracking mode (documentation only, not implemented in code)

The detection algorithm evolved from the original Precision mode specification (240fps + background model + ML Kit pose detection) to the Photo Finish mode (30-120fps + frame differencing + CCL blob detection), which matched the actual iOS app behavior more closely and required no ML dependencies.

## File References

@docs/architecture/DETECTION_ALGORITHM.md
@docs/protocols/CLOCK_SYNC_DETAILS.md
@docs/architecture/BACKEND_STRATEGY.md
