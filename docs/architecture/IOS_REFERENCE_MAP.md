# iOS to Android Reference Map

**Last Updated:** January 2026

Quick reference for finding iOS source code when implementing Android equivalents.

---

## Core Detection Engine

| Android Component | iOS File | Key Lines | Notes |
|-------------------|----------|-----------|-------|
| `BackgroundModel` | `BackgroundModel.swift` | 12-41 (config), 208-222 (detection) | MAD-based thresholding |
| `CrossingDetector` | `CrossingDetector.swift` | 12-45 (thresholds), 256-450 (state machine) | 5-state machine |
| `ContiguousRunFilter` | `ContiguousRunFilter.swift` | 100-114 (3-strip), 305-358 (band calc) | Chest band filtering |
| `PoseService` | `PoseService.swift` | Full file | ML Kit replaces Vision |
| `TorsoBoundsStore` | `TorsoBoundsStore.swift` | Full file | Thread-safe, EMA smoothing |
| `CompositeBuffer` | `CompositeBuffer.swift` | Full file | Ring buffer for photo-finish |
| `GateEngine` | `GateEngine.swift` | Full file | Orchestrator |

---

## Camera System

| Android Component | iOS File | Key Lines | Notes |
|-------------------|----------|-----------|-------|
| `CameraManager` | `CameraManager.swift` | Full file | Camera2 replaces AVFoundation |
| Frame extraction | `CameraManager.swift` | YUV processing | Same pixel format |
| Exposure/Focus lock | `CameraManager.swift` | Lock methods | Similar API concepts |
| Thermal monitoring | `CameraManager.swift` | 102-133 | Use Android ThermalManager |

---

## Communication Layer

| Android Component | iOS File | Key Lines | Notes |
|-------------------|----------|-----------|-------|
| `TimingMessage` | `TimingMessage.swift` | Full file | Must match exactly |
| `ClockSyncService` | `ClockSyncService.swift` | 8-18 (convention), 33-83 (NTP), 92-134 (quality), 136-217 (drift) | Critical accuracy |
| `MultipeerTransport` | `MultipeerTransport.swift` | Full file | BLE replaces Multipeer |
| `SupabaseTransport` | `SupabaseBroadcastTransport.swift` | Full file | Supabase Kotlin |
| `RaceSession` | `RaceSession.swift` | Full file | Session state machine |

---

## Data Models

| Android Entity | iOS Model | File | Notes |
|----------------|-----------|------|-------|
| `SessionEntity` | `TrainingSession` | `Models/TrainingSession.swift` | Room replaces SwiftData |
| `RunEntity` | `Run` | `Models/Run.swift` | Include splits JSON |
| `CrossingEntity` | `PersistedCrossing` | Referenced in Run | Image paths |
| `AthleteEntity` | `Athlete` | `Models/Athlete.swift` | Colors, PBs |
| `RaceEventDto` | `RaceEvent` | `SupabaseService.swift` | Supabase table schema |

---

## Key Constants Reference

### Detection Thresholds (CrossingDetector.swift lines 12-45)

```swift
// Primary thresholds
enterThreshold = 0.22           // First contact
confirmThreshold = 0.35         // Must reach to confirm
gateClearBelow = 0.15           // Hysteresis lower
gateUnclearAbove = 0.25         // Hysteresis upper
fallbackArmThreshold = 0.65     // No-pose fallback

// Distance filtering
minTorsoHeightFraction = 0.12   // 12% of frame

// 3-strip validation
adjacentStripThreshold = 0.55   // Side strip requirement
strongEvidenceThreshold = 0.85  // Override proximity

// Chest band
minRunChestFraction = 0.55      // 55% of band height
absoluteMinRunChest = 12        // Minimum 12 pixels

// Timing
gateClearDuration = 0.2s        // Clear before arming
postrollDuration = 0.2s         // Capture after trigger
cooldownDuration = 0.1s         // Between crossings
persistenceFrames = 2           // Frames above confirm
```

### Background Model (BackgroundModel.swift lines 12-41)

```swift
frameCount = 30                 // Calibration frames
minMAD = 10.0                   // Minimum MAD
madMultiplier = 3.5             // Threshold multiplier
defaultThreshold = 45.0         // Fallback
samplingBandWidth = 5           // Pixels around gate
adaptationRate = 0.002          // Slow adaptation
```

### Clock Sync (ClockSyncService.swift)

```swift
// Multipeer full sync
fullSyncSamples = 100
fullSyncInterval = 50ms
fullSyncMaxRTT = 50ms
rttFilterPercentile = 0.20
minValidSamples = 10

// Multipeer mini sync
miniSyncSamples = 30
miniSyncInterval = 100ms
miniSyncMaxRTT = 150ms
refreshInterval = 60s

// BLE sync
bleFullSamples = 80
bleMiniSamples = 25
bleRttFilter = 0.15
bleMaxRTT = 200ms (full), 350ms (mini)

// Quality tiers (uncertaintyMs)
excellent < 3
good < 5
fair < 10
poor < 15
bad >= 15
```

### Heartbeat Intervals (RaceSession.swift)

```swift
idle = 5s
armed = 1s
running = 0.5s
```

### Rolling Shutter (Device-specific)

```swift
iPhone 15 Pro readout = 4.7ms
// Apply: correctedTime = timestamp + (rowFraction × readoutTime)
```

---

## Protocol Version

```swift
kTimingProtocolVersion = 3  // Current version
```

Changes:
- v2: Initial handshake protocol
- v3: Host-controlled calibration/arming

---

## Supabase Configuration

```swift
// From SupabaseService.swift
supabaseURL = "https://hkvrttatbpjwzuuckbqj.supabase.co"
// Use existing anon key from iOS app

// Tables
- race_events (existing)
- race_sessions (existing)
- users (existing)

// Realtime channel pattern
"race_events:session_id=eq.{sessionId}"
```

---

## File Path Patterns

### Image Storage

```
// iOS pattern (ImageStorageService)
{documentsDir}/sessions/{sessionId}/gates/{gateId}/thumbnails/{timestamp}.jpg

// Android equivalent
{context.filesDir}/sessions/{sessionId}/gates/{gateId}/thumbnails/{timestamp}.jpg
```

### Composite Images

```
// iOS
{documentsDir}/sessions/{sessionId}/composites/{runId}.png

// Android
{context.filesDir}/sessions/{sessionId}/composites/{runId}.png
```

---

## UI Screen Mapping

| iOS Screen | Android Screen | Notes |
|------------|----------------|-------|
| `MainTabView` | `MainScreen` | Bottom nav |
| `DashboardHomeView` | `HomeScreen` | Mode selection |
| `BasicModeView` | `BasicTimingScreen` | Single phone |
| `RaceModeView` | `RaceModeScreen` | Multi-phone |
| `CreateSessionFlowView` | `CreateSessionFlow` | Host setup |
| `JoinSessionView` | `JoinSessionScreen` | Device pairing |
| `SetupAssistantView` | `SetupAssistantScreen` | Handshake |
| `TimingSessionView` | `TimingSessionScreen` | Main timing |
| `LiveSessionResultsView` | `ResultsScreen` | Run results |
| `SessionDetailView` | `SessionDetailScreen` | History detail |

---

## Debug Overlays

| iOS Overlay | Purpose | Android Equivalent |
|-------------|---------|-------------------|
| `DetectionDebugOverlay` | rLeft/rCenter/rRight, state, rejection reasons | `DetectionDebugComposable` |
| `ConnectionDebugOverlay` | Multipeer/Supabase status | `ConnectionDebugComposable` |
| `ThermalDebugView` | CPU/thermal state | `ThermalDebugComposable` |
| `MultipeerDebugView` | P2P details | `BleDebugComposable` |

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

### 3. Three-Strip Delta
```kotlin
// Auto-calculate strip spacing
val stripDelta = maxOf(3, frameWidth / 100)
```

### 4. Chest Band Calculation
```kotlin
// Band half-height = clamp(0.03 × torsoHeight, 8, 20) pixels
val bandHalfHeight = (0.03f * torsoHeightPx)
    .toInt()
    .coerceIn(8, 20)
```

### 5. Sub-Frame Interpolation
```kotlin
// Quadratic preferred (3+ samples)
// Linear fallback (1-2 samples)
// Must handle int64 nanosecond math carefully
```

### 6. Post-Roll is Fixed Duration
```kotlin
// 0.2 seconds, NOT exit-detection based
// Matches Photo Finish approach
```

### 7. Message Serialization
```kotlin
// JSON for Supabase/general messages
// Binary for BLE clock sync (performance)
// Must be byte-compatible with iOS
```
