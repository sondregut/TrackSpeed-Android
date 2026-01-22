# TrackSpeed Android

Native Android port of the iOS **Speed Swift** app. Enables cross-platform multi-device sprint timing where iOS and Android devices can pair together.

## Repositories

- **This repo (Android)**: https://github.com/sondregut/TrackSpeed-Android
- **iOS app (Speed Swift)**: `/Users/sondre/Documents/App/speed-swift/SprintTimer/`

## Project Context

- **Supabase Project**: `sprint-timer-race` (ID: `hkvrttatbpjwzuuckbqj`) - shared with iOS
- **Documentation**: `./docs/` contains PRD, tech specs, and protocols

## Tech Stack

- Kotlin + Jetpack Compose
- Camera2 API (not CameraX) for 240fps capture
- ML Kit Pose Detection (replaces Apple Vision)
- Room database (replaces SwiftData)
- Supabase Kotlin client
- Hilt for DI

## Critical Implementation Details

### Detection Algorithm
- Uses **Precision mode** (not Simple mode)
- Thresholds: enter=0.22, confirm=0.35, clear=0.15
- 3-strip validation with 0.55 adjacent threshold
- Fixed 0.2s post-roll (not exit-based)
- Quadratic sub-frame interpolation

### Clock Sync
- Offset convention: `t_remote = t_local + offset`
- Use `SystemClock.elapsedRealtimeNanos()` (not `System.nanoTime()`)
- 100 samples, keep lowest 20% RTT, use median offset

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

## File References

@docs/architecture/DETECTION_ALGORITHM.md
@docs/protocols/CLOCK_SYNC_DETAILS.md
@docs/architecture/BACKEND_STRATEGY.md
