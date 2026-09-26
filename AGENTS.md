# TrackSpeed Android Agent Guide

Canonical instructions for the native Android app. Keep durable repository
boundaries here and verify changing behavior, constants, versions, and live
service state from the current source.

## Start From the Real State

- Confirm this repository root, branch, and `git status --short --branch`
  before editing. The checkout may contain extensive unrelated work.
- Preserve unrelated changes. Do not reset, discard, broadly stage, commit,
  push, publish, deploy, or mutate production unless the user requests that
  exact outcome.
- Read the implementation and focused tests before trusting dated plans,
  parity reports, or copied constants.
- Treat compilation, installation, launch, and runtime proof as separate
  claims.

## Repository Role

- Android repository: `/Users/sondre/Documents/App/TrackSpeed-Android`
- iOS product source of truth: `/Users/sondre/Documents/App/speed-swift`
- Android package: `com.trackspeed.android`
- App module: `app/`
- Current architecture and protocol docs: `docs/`

The Android app should match the current native iOS product where the platforms
share behavior, but preserve platform-native Kotlin, Compose, Camera2, and BLE
implementation. Do not use the older Expo/React Native MVP as a parity source.
When parity matters, inspect the live iOS implementation and Android call sites
rather than relying on a dated parity checklist.

## Current Sources of Truth

- App bootstrap and navigation: `TrackSpeedApp.kt`, `MainActivity.kt`, and
  `ui/navigation/NavGraph.kt`
- Detection: `detection/DetectionEngine.kt`, `detection/GateEngine.kt`, and
  `camera/CameraManager.kt`
- Timing and pairing: `sync/`, `protocol/TimingMessage.kt`, and the relevant
  race view models
- Persistence: `data/local/` and `data/repository/`
- Backend and auth: `cloud/`
- Billing and promo access: `billing/`
- Build configuration: `app/build.gradle.kts`, `gradle/libs.versions.toml`, and
  `gradle.properties`

`DetectionEngine.kt` is the active detector. Treat `PhotoFinishDetector.kt` and
archived algorithm descriptions as compatibility or history unless current
call sites prove otherwise. Read thresholds, work dimensions, timestamp
mapping, camera configuration, and clock-sync parameters from current source;
do not copy them into this file.

## Cross-Platform Contracts

- Preserve message field names, event-time semantics, authentication, and
  backward compatibility across Android and iOS.
- Pair timing events by event timestamps rather than arrival order.
- Keep local BLE delivery, backend relay, clock sync, session pairing, media
  transfer, and UI connection state as separate failure domains.
- Before changing a shared Supabase table, RPC, storage path, entitlement, or
  promo-code contract, inspect the current iOS, Android, and backend consumers.
- Schema changes, production writes, remote migrations, function deploys, and
  Play Console actions require explicit user authorization.

## Kotlin and Android Conventions

- Follow existing Kotlin, Compose, Hilt, Room, coroutine, and Flow patterns.
- Keep UI state on the appropriate main/UI scope and make cancellation and
  lifecycle ownership explicit.
- Preserve sensor-time and monotonic-clock domains through camera, BLE, and
  timing paths; do not substitute wall-clock time.
- Avoid large image or media payloads on small-message BLE paths.
- Keep user-visible strings in resources and maintain the existing locale
  structure.
- Do not log tokens, credentials, signed URLs, raw user data, or sensitive
  payloads.

## Validation

```bash
./gradlew assembleDebug
./gradlew test
```

Use the narrowest module or test target that can disprove the change. For UI,
camera, detection, audio, BLE, or multi-phone behavior, build success is not
runtime proof: install and exercise the real route on the intended device(s).
Record which device, OS, and build were actually tested and separate remaining
physical acceptance from completed offline verification.

For cross-platform timing changes, verify Android unit tests plus the matching
iOS protocol or timing tests. Do not claim Android-to-Android or Android-to-iOS
behavior without physical evidence for the tested radio/device permutation.

## Handoff

Report scoped files changed, verification performed, remaining device or live
checks, and unrelated dirty state preserved. Do not imply a commit, push,
deployment, Play upload, or release unless it was requested and verified.
