# iOS Speed Swift - Complete Technical Reference

**Generated:** February 2026
**Source:** `/Users/sondre/Documents/App/speed-swift/SprintTimer/SprintTimer/`
**Purpose:** Definitive backend/logic reference for porting to Android (TrackSpeed)

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [RaceSession (Session Orchestrator)](#2-racesession)
3. [TimingMessage (Protocol)](#3-timingmessage)
4. [CrossingDetector (State Machine)](#4-crossingdetector)
5. [BackgroundModel (Background Subtraction)](#5-backgroundmodel)
6. [ContiguousRunFilter (Torso Validation)](#6-contiguousrunfilter)
7. [TorsoBoundsStore (Thread-Safe Pose Data)](#7-torsoboundsstore)
8. [PoseService (Vision Pose Detection)](#8-poseservice)
9. [CameraManager (240fps Capture)](#9-cameramanager)
10. [GateEngine (Gate Mode Config)](#10-gateengine)
11. [ClockSyncService (NTP-Style Sync)](#11-clocksyncservice)
12. [CompositeBuffer (Photo-Finish)](#12-compositebuffer)
13. [RollingShutterService](#13-rollingshutterservice)
14. [IMUService (Motion/Stability)](#14-imuservice)
15. [SupabaseService (Cloud Backend)](#15-supabaseservice)
16. [SessionPersistenceService (Local DB)](#16-sessionpersistenceservice)
17. [ImageStorageService (File I/O)](#17-imagestorageservice)
18. [VoiceStartService (AI Voice Commands)](#18-voicestartservice)
19. [ElevenLabsService (AI Audio)](#19-elevenlabsservice)
20. [DeviceIdentity (Stable Device ID)](#20-deviceidentity)
21. [Data Models](#21-data-models)
22. [Transport Layer](#22-transport-layer)
23. [UserSettings](#23-usersettings)
24. [Key Data Flow Diagrams](#24-key-data-flow-diagrams)

---

## 1. Architecture Overview

The iOS app uses a dual-loop architecture for precision sprint timing:

```
SLOW LOOP (15-30Hz)              FAST LOOP (240Hz)
PoseService (Vision)             CameraManager (AVFoundation)
  |                                |
  v                                v
TorsoBoundsStore ----+----> CrossingDetector
  (thread-safe)      |        |
                     |        v
                     +----> BackgroundModel
                     |        |
                     |        v
                     +----> ContiguousRunFilter
                              |
                              v
                         RaceSession (orchestrator)
                           |          |
                           v          v
                   ClockSyncService  SupabaseService
                           |          |
                           v          v
                   TimingTransport   Supabase Realtime
```

### Session States

```
idle -> connecting -> syncing -> ready -> waitingForCrossing -> running(startTime) -> finished(result)
```

### Key Design Decisions
- **Offset convention:** `t_remote = t_local + offset` (positive = local behind)
- **Clock source:** `mach_absolute_time()` on iOS, `SystemClock.elapsedRealtimeNanos()` on Android
- **Detection:** Precision mode only (not Simple mode)
- **Post-roll:** Fixed 0.2s (not exit-based)
- **Dual-path messaging:** Critical timing messages sent via both P2P (Multipeer) and Supabase Realtime
- **Continuous mode:** Gates auto-reset after each crossing, timer restarts on re-cross

---

## 2. RaceSession

**File:** `RaceSession.swift` (1248 lines) + 5 extension files (~4000 lines total)
**Type:** `@Observable class`
**Role:** Central session orchestrator for multi-phone timing

### State Enum
```swift
enum State: Equatable {
    case idle
    case connecting
    case syncing
    case ready
    case waitingForCrossing
    case running(startTime: UInt64)  // 0 = waiting for start event
    case finished(result: RaceResult)
}
```

### Key Properties
| Property | Type | Purpose |
|----------|------|---------|
| `state` | `State` | Current session state |
| `role` | `TimingRole?` | This phone's role (startLine/finishLine/lapGate/controlOnly) |
| `isHost` | `Bool` | Whether this phone is the session host |
| `isSoloMode` | `Bool` | Single-phone mode (no peer) |
| `sessionId` | `UUID` | Current session identifier |
| `distance` | `Double` | Total race distance in meters |
| `startType` | `StartType` | How the timer starts |
| `numberOfGates` | `Int` | Number of gates (2=start+finish, 3+=with lap) |
| `sessionRuns` | `[SessionRun]` | History of runs in this session |
| `connectedGates` | `[ConnectedGate]` | Connected peer gates |
| `soloLapResults` | `[(lapNumber, elapsedNanos, image, ...)]` | Solo mode lap list |
| `gateDistances` | `[Int: Double]` | Gate index to distance mapping |
| `localGateId` | `String` | This device's gate ID (from DeviceIdentity) |
| `localGateIndex` | `Int` | This device's gate index (0=start, N-1=finish) |
| `currentRunId` | `UUID` | Current run UUID for thumbnail tracking |
| `gatePosition` | `CGFloat` | Gate line position (0-1, normalized X) |
| `pairingCode` | `String?` | 4-digit Supabase pairing code |
| `isDetectionPaused` | `Bool` | Whether detection is temporarily paused |

### Key Flags (Race State Guards)
| Flag | Purpose |
|------|---------|
| `startEventRecorded` | Prevents duplicate start processing |
| `finishResultRecorded` | Prevents duplicate finish processing |
| `resilientCrossingDetected` | Local crossing stored (even without network) |
| `immediateCrossingPending` | Image still being captured |
| `handshakeComplete` | V3 handshake completed |
| `isAlignmentConfirmed` | User confirmed camera alignment |
| `localReadyLatched` | Gate calibrated + stable + clear |

### Init Parameters
```swift
init(
    distance: Double,
    startType: StartType,
    numberOfGates: Int,
    isHost: Bool,
    soloMode: Bool = false,
    existingTransport: (any TimingTransport)? = nil,
    preAssignedRoles: [String: TimingRole]? = nil,
    joinerReceivedConfig: TimingSessionConfig? = nil,
    precomputedClockOffset: Int64? = nil,
    hybridSessionId: String? = nil
)
```

### Extension: RaceSession+MessageHandling (687 lines)
- `handleMessage(_:)` - Main message router with seq-number dedup and cross-transport dedup
- Handles ~40+ message types organized by category
- Hub-and-spoke relay for multi-gate: host relays startEvent/crossingEvent to all peers
- Thumbnail update handler with event ID validation and grace period for late arrivals
- Supabase event handling with image download
- Connection state management with resilient reconnection

### Extension: RaceSession+CrossingDetection (813 lines)
- `reportCrossing()` - Called by crossing detector when chest crosses line
- `reportImmediateCrossing()` - Instant feedback (beep, timer start) before post-roll
- `completeCrossingWithImage()` - Final processing after post-roll captures image
- **Solo mode:** First crossing starts timer, subsequent crossings are laps
- **Multi-phone mode:** Start phone broadcasts, finish phone calculates split
- **Resilient detection:** Stores crossings locally even without network, processes when start arrives
- **Continuous mode:** Both phones reset flags on new crossing without waiting for auto-reset
- Touch release and voice command start support
- Three-step crossing flow: `reportImmediateCrossing` -> `completeCrossingWithImage` -> `broadcastCrossingEvent`

### Extension: RaceSession+Timing (1031 lines)
- `handleStartCrossing()` / `handleFinishCrossing()` - Role-specific crossing handlers
- `processFinishCrossing()` - Calculates split time with minimum 10ms guard
- `handleStartEvent()` - Converts remote timestamp to local time, processes buffered crossings
- `handleFinishResult()` - Start phone receives finish split from peer
- `calculateAndBroadcastMultiGateResult()` - Host calculates segment splits from all crossings
- `completeRunAndAutoReset()` - Auto-saves run, announces time, schedules 0.5s reset
- `autoResetForNewRun()` - Resets all flags, broadcasts `.newRun` to peers
- 5s/12s timeout for buffered finish crossings (with/without internet)
- Elapsed timer using `TimingMessage.monotonicNanos()` for drift-free display

### Extension: RaceSession+PublicActions (991 lines)
- `selectRole(_:)` - Host creates pairing code, starts MultipeerConnectivity
- `joinSessionWithCode(_:)` - Joiner looks up host via Supabase, starts targeted search
- `startSoloSession()` - Direct to waitingForCrossing without peer
- `confirmAlignment()` - Gates detection after user confirms camera setup
- `updateGateDistances()` - Host broadcasts distance config changes
- `pauseDetectionAndBroadcast()` / `resumeDetectionAndBroadcast()` - Multi-phone pause sync
- `attemptReconnection()` - Re-advertise and browse for lost peers
- `endSession()` / `reset()` / `backToSetup()` - Lifecycle management
- `saveCurrentRun()` - Persists run to session history with auto-backup to Supabase
- `cancelCurrentRun()` / `cancelPendingCrossing()` - Void/abort runs
- Multi-athlete support: `setSessionAthletes()`, `selectNextAthlete()`

### Types (RaceSession+Types.swift, 192 lines)
```swift
struct RaceResult {
    let splitNanos: UInt64
    let uncertaintyMs: Double
    let startTimestamp: UInt64
    let finishTimestamp: UInt64
    let distance: Double?
    // Computed: splitSeconds, formattedSplit, speedMps, speedKph, formattedSpeed
}

struct SessionRun {
    let id: UUID
    var runNumber: Int
    let timestamp: Date
    let result: RaceResult
    var startImage, finishImage, lapImage: UIImage?
    var lapImages: [Int: UIImage]
    var gateMedia: [TimingRole: RemoteMedia]
    var localGateFrames: [LocalGateFrameData]?
    var calibrationFrames: [CalibrationFrameData]?
    var algorithmData: CalibrationAlgorithmData?
    var athleteId: UUID?
    var athleteName, athleteColor: String?/AthleteColor?
    var distance: Double?
    var startType: StartType?
    var segments: [SegmentSplit]
}

struct ConnectedGate {
    var id: String
    var peerName: String
    var role: TimingRole
    var gateIndex: Int
    var distanceFromStart: Double
    var status: GateStatusInfo?
}
```

---

## 3. TimingMessage

**File:** `TimingMessage.swift` (865 lines)
**Role:** All message types for cross-device communication

### Protocol Version
```swift
static let kTimingProtocolVersion = 3
```

### TimingRole Enum
```swift
enum TimingRole: String, Codable {
    case startLine
    case finishLine
    case lapGate
    case controlOnly
}
```

### TimingMessage Structure
```swift
struct TimingMessage: Codable {
    let protocolVersion: Int
    let seq: Int
    let senderId: String
    let sessionId: UUID
    let messageId: UUID?
    let eventId: String?
    let payload: Payload
    let createdAtNanos: UInt64?
}
```

### Payload Cases (40+ types)
**Handshake:** `sessionConfig`, `sessionConfigAck`, `roleRequest`, `roleAssigned`, `gateAssigned`, `roleAssignedAck`, `gateAssignedAck`
**ACK/Retry:** `ack(messageId)`, `nack(messageId, reason)`
**Clock Sync:** `syncRequest`, `syncComplete`, `syncPing`, `syncPong`
**Session Control:** `countdown`, `armed`, `startEvent`, `finishResult`, `abort`, `newRun`, `cancelRun`, `sessionEnded`, `calibrateRequest`
**Timing Events:** `crossingEvent`, `timingResultBroadcast`, `multiGateResult`
**Mid-Session Config:** `startTypeChanged`, `distanceConfigChanged`, `pauseDetection`, `resumeDetection`, `adjustGateLine`
**Supabase Hybrid:** `supabaseSession`
**Thumbnail:** `thumbnailUpdate`
**Event Reconciliation:** `eventSync`, `eventSyncResponse`, `configVersion`

### Key Types
```swift
struct TimingSessionConfig: Codable {
    let distance: Double
    let startType: String
    let numberOfGates: Int
    let hostRole: String
    let fpsMode: String?
    let protocolVersion: Int
}

struct GateAssignment: Codable {
    let role: String
    let gateIndex: Int
    let distanceFromStart: Double
    let targetDeviceId: String?
}

struct SegmentSplit: Codable {
    let fromGateIndex, toGateIndex: Int
    let fromGateId, toGateId: String?
    let splitNanos: UInt64
    let distanceMeters: Double?
    let cumulativeSplitNanos: UInt64
    let cumulativeDistanceMeters: Double?
}

struct GateStatusInfo: Codable {
    var isCalibrated, isArmed, isClear, isPrebufferReady, isStable: Bool
    var gatePosition: Double
    var batteryLevel: Float?
}
```

### Utility Functions
```swift
static func monotonicNanos() -> UInt64  // DispatchTime.now().uptimeNanoseconds
static func generateEventId(runId: UUID, gateId: String, timestampNanos: UInt64) -> String
```

---

## 4. CrossingDetector

**File:** `CrossingDetector.swift` (1163 lines)
**Role:** State machine for detecting athlete crossings at gate line

### Detection States
```swift
enum CrossingState {
    case waitingForClear   // Gate must be empty for 0.2s
    case armed             // Ready to detect
    case postroll          // Capturing 0.2s after trigger
    case cooldown          // 0.1s between crossings
    case chestCrossing     // Legacy (unused)
}
```

### Threshold Constants
```
enterThreshold = 0.22       // First contact
confirmThreshold = 0.35     // Must reach for valid crossing
gateClearBelow = 0.15       // Below = gate clear
gateUnclearAbove = 0.25     // Above = resets clear timer
fallbackArmThreshold = 0.80 // DISABLED (no-pose fallback)
strongEvidenceThreshold = 0.85  // Bypasses proximity check
adjacentStripThreshold = 0.55   // 3-strip validation
minTorsoHeightFraction = 0.16   // Distance filter
minRunChestFraction = 0.55      // Chest band occupancy
absoluteMinRunChest = 12        // Minimum pixel run
persistenceFrames = 2           // Frames above confirm
postrollDuration = 0.2s         // Fixed post-roll
armingGracePeriod = 0.2s        // Photo Finish safety
cooldownDuration = 0.1s         // Between crossings
```

### Main Processing Method
```swift
func processFrame(
    rCenter: Float,              // Center strip occupancy
    rLeft: Float, rRight: Float, // Adjacent strip occupancies
    runCenter: Int,              // Longest contiguous run (center)
    runLeft: Int, runRight: Int, // Adjacent strip runs
    torsoBounds: TorsoBoundsStore.Snapshot?,
    framePtsNanos: UInt64,
    frameHeight: Int,
    bandTop: Int, bandBottom: Int
)
```

### Validation Pipeline
1. **Distance filter:** Reject if `torsoHeightFraction < 0.16`
2. **Three-strip validation:** Center occupancy >= confirm threshold + at least one adjacent >= 0.55
3. **Uniform occupancy veto:** All 3 strips > 0.85 = invalid background, reject
4. **Proximity check:** Crossing Y must be near pose position (quality-dependent: full=0.30, partial=0.20)
5. **Strong evidence override:** Occupancy >= 0.85 bypasses proximity check
6. **Persistence:** Must be above confirm threshold for 2+ consecutive frames

### Sub-Frame Interpolation
- **Quadratic interpolation** (preferred): Fits curve to 3 recent below-threshold samples, solves for threshold crossing time
- **Linear interpolation** (fallback): Between last below-threshold and first above-threshold samples
- Both apply rolling shutter correction via `RollingShutterService`

### Callbacks
```swift
var onCrossingStarted: ((UInt64) -> Void)?  // Immediate (before post-roll)
var onTrigger: ((UInt64, UIImage?) -> Void)?  // After post-roll complete
```

---

## 5. BackgroundModel

**File:** `BackgroundModel.swift` (533 lines)
**Role:** Per-row background subtraction for gate line

### Calibration Config
```
frameCount = 30              // Frames for calibration
minMAD = 10.0                // Minimum MAD threshold
madMultiplier = 3.5          // Threshold = MAD * multiplier
defaultThreshold = 45.0      // Fallback for low-variance
samplingBandWidth = 5        // Pixels around gate
adaptationRate = 0.002       // Very slow (0.2%) adaptation
```

### Algorithm
1. **Calibration:** Collect 30 frames, compute per-row median + MAD
2. **Threshold:** `max(defaultThreshold, minMAD, madMultiplier * MAD)` per row
3. **Foreground detection:** `abs(pixel - background) > threshold`
4. **Three-strip extraction:** `extractThreeStripMasks(gatePosition, stripDelta)`
   - `stripDelta = max(3, width/100)` pixels
5. **Background adaptation:** EMA (alpha=0.002) when gate clear (occupancy < 15%), only for drift > 5.0
6. **Stability tracking:** EMA-smoothed rows adapted, hysteresis (unstable > 200, stable < 80), 1.5s stable duration

### Thread Safety
- `NSLock` for array access during calibration/reset

### Key Methods
```swift
func addCalibrationFrame(luminanceColumn: [UInt8])
func finishCalibration() -> Bool
func extractThreeStripMasks(frame, gatePosition, frameHeight) -> (left, center, right)
func getForegroundMask(luminanceColumn: [UInt8]) -> [Bool]
func adaptBackground(luminanceColumn: [UInt8], occupancy: Float)
```

---

## 6. ContiguousRunFilter

**File:** `ContiguousRunFilter.swift` (359 lines)
**Role:** Finds longest contiguous foreground run in chest band

### Chest Band Calculation
```
bandHalfHeight = clamp(0.03 * torsoHeight, min=8, max=20) pixels
chestCenter = torsoTop + 0.33 * torsoHeight
band = [chestCenter - halfHeight, chestCenter + halfHeight]
```

### Key Types
```swift
struct ChestBandResult {
    let longestRun: Int      // Pixels
    let rChest: Float        // longestRun / bandHeight
    let rTorso: Float        // Run / torso height
    let bandTop, bandBottom: Int
}

struct ThreeStripResult {
    let rLeft, rCenter, rRight: Float
    let runLeft, runCenter, runRight: Int
    let bandTop, bandBottom: Int
    let torsoTop, torsoBottom: Float?
    func isTorsoLike() -> Bool  // center >= confirm && run >= 12 && (left || right >= 0.55)
}
```

### Key Method
```swift
static func analyzeThreeStrips(
    leftMask, centerMask, rightMask: [Bool],
    torsoBounds: TorsoBoundsStore.Snapshot?,
    frameHeight: Int
) -> ThreeStripResult
```

---

## 7. TorsoBoundsStore

**File:** `TorsoBoundsStore.swift` (277 lines)
**Role:** Thread-safe container for pose detection results

### Thread Safety
- Uses `OSAllocatedUnfairLock` (fastest lock on Apple platforms)

### Snapshot Structure
```swift
struct Snapshot {
    let yTop: Float           // Shoulder Y (normalized 0-1)
    let yBottom: Float        // Hip Y (normalized 0-1)
    let chestY: Float         // Chest Y (computed)
    let confidence: Float
    let frameNumber: Int
    let timestamp: UInt64
    let joints: StoredJoints
    let quality: PoseQuality
}
```

### PoseQuality
```swift
enum PoseQuality {
    case full      // Both shoulders + hips detected
    case partial   // Shoulders only
    case stale     // Up to 8 consecutive failures
    case none      // No pose data
}
```

### StoredJoints
```swift
struct StoredJoints {
    let leftShoulderY, rightShoulderY: Float?
    let leftHipY, rightHipY: Float?
    let leftShoulderX, rightShoulderX: Float?
    var chestCenterX: Float?  // Computed average
}
```

### Smoothing
- EMA for torso height (alpha=0.3)
- Stale handling: up to 8 consecutive pose failures before `.none`
- Fallback: middle 50% of frame when no pose data

---

## 8. PoseService

**File:** `PoseService.swift` (~200 lines)
**Role:** Apple Vision body pose detection at 15Hz

### Configuration
```
sampleRate = 16             // Every 16th frame at 240fps = ~15Hz
minJointConfidence = 0.2
smoothingAlpha = 0.3
boundsPadding = 0.1
```

### Architecture
- Dedicated `DispatchQueue` (not actor) for predictable scheduling
- Backlog guard via `OSAllocatedUnfairLock(isProcessing)` - prevents queue buildup
- Uses `VNDetectHumanBodyPoseRequest` (Vision framework)
- Results written to `TorsoBoundsStore` (thread-safe)

### Android Equivalent
- Replace `VNDetectHumanBodyPoseRequest` with ML Kit Pose Detection
- Use `STREAM_MODE` for real-time
- Map landmarks: `LEFT_SHOULDER`, `RIGHT_SHOULDER`, `LEFT_HIP`, `RIGHT_HIP`

---

## 9. CameraManager

**File:** `CameraManager.swift` (1022 lines)
**Role:** AVFoundation 240fps video capture

### FPS Configuration
```
Target FPS cascade: 240 -> 120 -> 60
FPS source priority: fpsOverride > UserSettings > DeviceCapability
Session preset: .inputPriority (for high-fps formats)
Pixel format: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange (YUV)
```

### Camera Calibration
- `lockExposure()` - Fixes exposure for consistent background model
- `lockWhiteBalance()` - Prevents color shifts
- `lockFocus()` - Prevents focus hunting
- Experimental mode: auto-exposure with cap per FPS, focus locked at 0.7
- Point & Shoot mode: auto-exposure + locked FPS

### Thermal Monitoring
- State-based FPS throttling (currently disabled for debug)
- Monitors `ProcessInfo.processInfo.thermalState`

### Camera Switching
- Front/back camera switch preserves FPS mode
- Uses `AVCaptureDevice.DiscoverySession` for format selection

### Delegate Protocol
```swift
protocol CameraManagerDelegate: AnyObject {
    func cameraManager(_ manager: CameraManager, didOutputFrame: CMSampleBuffer)
    func cameraManager(_ manager: CameraManager, didChangeState: CameraState)
}
```

### Android Equivalent
- Use Camera2 API (not CameraX) for 240fps control
- `CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP` for format selection
- `CaptureRequest.CONTROL_AE_LOCK`, `CONTROL_AWB_LOCK`, `CONTROL_AF_MODE`

---

## 10. GateEngine

**File:** `GateEngine.swift` (235 lines)
**Role:** Gate configuration and detection mode management

### Detection Modes
```swift
enum DetectionMode: String, Codable {
    case precision      // Full dual-loop (primary mode)
    case photoFinish    // Slit-scan composite
    case experimental   // Testing new algorithms
}
```

### Gate Modes
```swift
enum GateMode {
    case basic           // Single phone
    case multiphoneGate  // Part of multi-phone session
}
```

### GateStatus
```swift
struct GateStatus {
    var isCalibrated, isArmed, isClear, isPrebufferReady, isStable: Bool
    var rCenter: Float
    var prebufferSpanMs: Double
    // Detection mode-specific fields
}
```

### Sink Protocol
```swift
protocol GateEventSink: AnyObject {
    func onCrossing(timestampNanos: UInt64, image: UIImage?)
    func onImmediateCrossing(timestampNanos: UInt64)
    func onPtsUpdate(ptsNanos: UInt64)
    func onStatusUpdate(status: GateStatus)
}
```

---

## 11. ClockSyncService

**File:** `ClockSyncService.swift` (1178 lines)
**Role:** NTP-style clock synchronization between devices

### Offset Convention
```
t_remote = t_local + offset
Positive offset = local clock is BEHIND remote
```

### Sync Parameters
| Parameter | WiFi | BLE |
|-----------|------|-----|
| Full sync samples | 100 | 80 |
| Sample interval | 50ms | 50ms |
| Max RTT | 50ms | 200ms |
| RTT filter | lowest 20% | lowest 15% |
| Min valid samples | 10 | 10 |
| Mini sync samples | 30 | 25 |
| Mini sync interval | 100ms | 100ms |
| Auto-refresh | 60s | 60s |

### Quality Tiers
```
EXCELLENT: < 3ms
GOOD: 3-5ms
FAIR: 5-10ms (minimum for timing)
POOR: 10-15ms
BAD: > 15ms
```

### Algorithm
1. Send N ping messages with timestamps (T1)
2. Server records receive time (T2) and send time (T3)
3. Client records receive time (T4)
4. RTT = (T4 - T1) - (T3 - T2)
5. Offset = ((T2 - T1) + (T3 - T4)) / 2
6. Filter: keep lowest 20% RTT samples
7. Additional: adaptive outlier rejection (> 2x min RTT)
8. BLE additional: remove > 2 std dev from median RTT
9. Final: prefer min-RTT sample, validate against weighted average
10. Uncertainty = minRTT/2 + MAD of offsets

### Drift Tracking
- Linear regression over offset history
- Predicts future offset: `lastOffset + driftRate * elapsed`
- Requires 30+ seconds of data

### Persistence
- Save/load sync results to UserDefaults
- Max age: 2 hours before requiring re-sync

### Key Methods
```swift
func startFullSync()
func startMiniSync()
func convertToLocal(_ remoteTimestamp: UInt64) -> UInt64?
func acceptPeerResult(offsetNanos: Int64, uncertaintyMs: Double)
func persistSyncResult(peerId: String)
func softReset()  // Preserves result, resets state machine
```

---

## 12. CompositeBuffer

**File:** `CompositeBuffer.swift` (~300 lines)
**Role:** Ring buffer for photo-finish slit-scan composite

### Configuration
```
maxColumns = 5000          // Max slit columns
preRollColumns = 200       // ~0.8s at 240fps
postRollColumns = 100      // ~0.4s at 240fps
```

### Thread Safety
- Uses `NSLock`

### How It Works
- Stores 1-pixel vertical slits from each frame at gate position
- Creates a slit-scan composite image for photo-finish review
- Pre-roll captures columns before crossing, post-roll captures after
- `markTrigger(frameIndex)` marks the crossing point in the composite

---

## 13. RollingShutterService

**File:** `RollingShutterService.swift` (124 lines)
**Role:** Corrects for CMOS rolling shutter timing offset

### Default Readout Duration
```
iPhone 15 Pro: ~4.7ms
iPhone 14 Pro: ~4.5ms
iPhone 13 Pro: ~5.0ms
Default: 4.7ms
```

### Correction Formula
```
correctedTime = framePTS + (crossingRowY / frameHeight) * readoutDuration
```

### Precision Impact
- Without correction: +/-2.08ms (half frame interval at 240fps)
- With correction: +/-0.012ms (2.4us per row)

### Key Methods
```swift
func preciseCrossingTimeNanos(framePtsNanos: UInt64, crossingRowY: Int, frameHeight: Int) -> UInt64
func nanosPerRow(frameHeight: Int) -> Double
```

---

## 14. IMUService

**File:** `IMUService.swift` (391 lines)
**Role:** CoreMotion-based stability detection for camera setup

### Configuration
```
sampleInterval = 1/60 (60Hz)
smoothingAlpha = 0.08 (lenient mode)
stableThreshold = 0.25 rad/s (~14 deg/s)
unstableThreshold = 0.4 rad/s (~23 deg/s)
stableArmingDuration = 0.1s (100ms)
unstableDisarmDuration = 0.3s (300ms)
```

### Reference Frame
- Uses `.xArbitraryZVertical` (stable, no compass jumps)
- Relative attitude via `multiply(byInverseOf:)` for clean yaw delta

### Stability Detection
- Uses 80th percentile of rotation rate magnitude (tolerates brief spikes)
- Hysteresis state machine: different thresholds for arming vs disarming
- Outputs: `isStable`, `isArmedForDetection`, `deltaYaw`, `roll`

### Attitude Snapshot
```swift
struct AttitudeSnapshot {
    let yaw: Double    // Relative yaw (perpendicular check)
    let roll: Double   // Roll (level check)
    let isStable: Bool
    let isArmedForDetection: Bool
    // Computed: perpendicularError, isPerpendicular(threshold:), isLevel(threshold:)
}
```

### Android Equivalent
- Use `SensorManager` with `TYPE_GAME_ROTATION_VECTOR`
- Same EMA smoothing and hysteresis state machine
- Yaw/roll extraction from rotation matrix

---

## 15. SupabaseService

**File:** `SupabaseService.swift` (2150 lines)
**Role:** Cloud backend communication (shared with iOS)

### Singleton
```swift
@MainActor @Observable final class SupabaseService
static let shared = SupabaseService()
```

### Core Features

#### Race Events (Real-Time)
- `sendStartEvent(crossingTimeNanos:, clockOffsetNanos:, uncertaintyMs:)`
- `sendFinishEvent(crossingTimeNanos:, imagePath:, clockOffsetNanos:, uncertaintyMs:)`
- `subscribeToEvents()` - Realtime INSERT on `race_events`
- Foreground reconnection with catch-up query on missed events

#### Crossings & Thumbnails
- `insertCrossingWithThumbnail()` - Single atomic INSERT with thumbnail URL
- `uploadThumbnailOnly()` - Upload first, then INSERT (more reliable)
- `subscribeToCrossings()` - Realtime INSERT + UPDATE on `crossings`
- Poll fallback for missed Realtime UPDATE (every 500ms for 5s)

#### Session Pairing (4-digit codes)
- `createPairingRequest()` - Host creates code
- `joinPairingRequest(code:)` - Joiner enters code, gets host's deviceId
- `subscribeToPairingRequest(code:)` - Host waits for joiner

#### Training Sessions & Runs (Cloud Backup)
- `createTrainingSession()`, `insertRun()`, `updateRunThumbnail()`
- `fetchSessions()`, `fetchRuns()` - History retrieval
- `deleteSession()`, `deleteRun()` - With Storage cleanup

#### Athlete Sync
- `syncAthlete()`, `updateAthleteBests()`, `deleteAthlete()`, `fetchAthletes()`

#### Promo Codes & Referrals
- `redeemPromoCode()` - Free/trial promo code redemption
- `getOrCreateReferralCode()`, `trackReferralSignup()`, `getReferralStats()`

#### Push Notifications
- `registerDeviceToken()`, `unregisterDeviceToken()`

#### Ground Truth Annotations
- `uploadGroundTruthAnnotation()` - Frame thumbnails + metadata for detection tuning

### Key DTOs (all snake_case for Postgres)
- `RaceEvent` (id, session_id, event_type, crossing_time_nanos, device_id, device_name, etc.)
- `SupabaseCrossingRecord` (session_id, run_id, gate_role, crossing_time_nanos, thumbnail_url)
- `PairingRequest` (session_code, host_device_id, joiner_device_id, status)
- `SupabaseSession` (device_id, user_id, distance, start_type)
- `SupabaseRun` (session_id, run_number, time_seconds, distance, start_type, athlete_*)
- `SupabaseAthlete` (device_id, name, color, personal_bests, season_bests)

---

## 16. SessionPersistenceService

**File:** `SessionPersistenceService.swift` (768 lines)
**Role:** SwiftData persistence for training sessions and runs

### Singleton
```swift
@MainActor final class SessionPersistenceService
static let shared = SessionPersistenceService()
```

### Session Lifecycle
1. `startSession(modelContext:, distance:, startType:, numberOfPhones:, numberOfGates:)` - Creates TrainingSession
2. `saveRun(from: SessionRun)` - Persists a run with images, splits, athlete bests
3. `endSession()` - Updates thumbnail, saves context

### Run Saving Features
- Saves start/finish/lap images via `ImageStorageService`
- Persists segment splits as JSON
- Saves local gate frame data for frame scrubbing
- Checks and updates athlete personal/season bests
- Checks user flying sprint PR
- Auto-backup to Supabase (with `RunUploadQueue` retry on failure)
- History limit warning for free users

### Solo Session Saving
- `saveSoloSession(soloLapResults:, distance:, startType:)` - Converts lap tuples to SwiftData

### Deletion
- `deleteSession()` / `deleteRun()` - Deletes local images + cascades to Supabase
- `deleteAllLocalData()` - For account deletion

---

## 17. ImageStorageService

**File:** `ImageStorageService.swift` (309 lines)
**Role:** Local file system image persistence

### Storage Locations
- `Documents/CrossingImages/` - Trigger frames, composites
- `Documents/CrossingImages/frames_{crossingId}/` - Frame sequences
- `Documents/SessionImages/` - Run start/finish/lap images

### Key Methods
```swift
func saveImage(_ image: UIImage, prefix: String, crossingId: UUID) -> String?  // JPEG 0.8
func saveCompositeImage(_ image: UIImage, crossingId: UUID) -> String?  // PNG (lossless)
func saveSessionImage(_ image: UIImage, prefix: String, runId: UUID) -> String?  // JPEG 0.7
func loadImage(relativePath: String) -> UIImage?
func deleteImagesForRun(_ runId: UUID)
func saveAllFrames(_ frames: [(UIImage, Int)], crossingId: UUID) -> [String]  // JPEG 0.6
```

### Android Equivalent
- Use `Context.filesDir` or `Context.getExternalFilesDir(null)`
- Same relative path structure

---

## 18. VoiceStartService

**File:** `VoiceStartService.swift` (674 lines)
**Role:** AI voice command sequence for sprint starts

### Voice Providers
```swift
enum VoiceProvider: String, Codable {
    case system      // AVSpeechSynthesizer (free, offline)
    case elevenLabs  // Eleven Labs API (premium)
}
```

### Sequence Phases
```
idle -> preloading -> preStart -> onYourMarks -> waitingForSet ->
[ready -> waitingAfterReady ->] set -> waitingForGo -> go -> started
```

### Timing Configuration
```swift
struct VoiceStartConfig {
    var preStartDelay: 3.0...5.0        // User sets phone down
    var onYourMarksDelay: 10.0...10.0   // Athlete positions
    var setHoldTime: 1.5...2.3          // Tension (international avg: 1.78s)
    var finalCue: FinalCue              // gunSound / voiceGo / whistle
}
```

### Start Timestamp
- Captured at `.go` phase via `TimingMessage.monotonicNanos()` BEFORE sound plays
- Delivered via `onStart: ((UInt64) -> Void)?` callback

### Audio Preloading
- Eleven Labs phrases cached in memory before sequence
- Sound effects (gun, whistle) also pre-cached

---

## 19. ElevenLabsService

**File:** `ElevenLabsService.swift` (567 lines)
**Role:** AI voice generation via Eleven Labs API (through Supabase Edge Function)

### Voice IDs
```swift
enum VoiceID: String {
    case adam, josh, arnold     // Male
    case rachel, bella, elli   // Female
}
```

### Speech Models
- `eleven_monolingual_v1` - Standard (higher quality)
- `eleven_flash_v2_5` - Flash (75ms latency, for time announcements)

### Time Announcement
```swift
// Formats: 10.45 -> "10 point 4 5" (individual digits for clarity)
func speakTime(seconds: Double, voiceId: VoiceID)  // Fire-and-forget
```

### Caching
- Two-tier: memory + disk (`Caches/ElevenLabsAudio/`)
- Flash audio for time announcements is NOT cached (unique, would cause memory pressure)
- Rate-limited: skips if already announcing (prevents queue buildup)

### API Route
- Calls Supabase Edge Function which proxies to Eleven Labs
- URL: `AppConfig.Supabase.elevenLabsTTSURL`
- Auth: Supabase anon key

---

## 20. DeviceIdentity

**File:** `DeviceIdentity.swift` (97 lines)
**Role:** Stable UUID persisted in Keychain

### Key Properties
```swift
let deviceId: String  // UUID stored in Keychain, survives reinstalls
```

### Keychain Storage
- Service: `com.sprinttimer.deviceidentity`
- Account: `deviceId`
- Accessible: `kSecAttrAccessibleAfterFirstUnlock`

### Usage
- Deterministic peer connection ordering (lower ID invites)
- Device identification in Supabase tables
- Consistent across app launches and reinstalls

### Android Equivalent
- Use `EncryptedSharedPreferences` or Android Keystore
- Generate UUID on first launch, persist across reinstalls

---

## 21. Data Models

### TrainingSession (SwiftData @Model)
**File:** `Models/TrainingSession.swift` (351 lines)

```swift
@Model class TrainingSession {
    var id: UUID
    var date: Date
    var name, location, notes: String?
    var distance: Double
    var startTypeRaw: String
    var numberOfPhones, numberOfGates: Int
    var gateConfigJSON: String?
    var thumbnailPath: String?
    @Relationship(deleteRule: .cascade) var runs: [Run]
    var createdAt, updatedAt: Date
    // Computed: startType, bestTime, averageTime, runCount
}
```

### Run (SwiftData @Model)
```swift
@Model class Run {
    var id: UUID
    var runNumber: Int
    var time: Double         // seconds
    var distance: Double
    var startTypeRaw: String
    var numberOfPhones: Int
    var athleteId: UUID?
    var athleteName, athleteColor: String?
    var isPersonalBest, isSeasonBest: Bool
    var startImagePath, finishImagePath: String?
    var lapImagePathsJSON: String?
    var splitsJSON: String?
    var localGateFramesDataJSON: String?
    var localGateRole: String?
    var timestamp: Date
}
```

### Athlete (SwiftData @Model)
**File:** `Models/Athlete.swift` (228 lines)

```swift
@Model class Athlete {
    var id: UUID
    var name, nickname: String?
    var color: AthleteColor
    var photoData: Data?
    var birthdate: Date?
    var gender: Gender?
    var personalBests: [String: Double]   // Key: "flying_30m"
    var seasonBests: [String: Double]
    // Methods: updatePersonalBest(), updateSeasonBest(), prKey()
}

enum AthleteColor: String, Codable, CaseIterable {
    case red, orange, yellow, green, blue, purple, pink, gray
}
```

### StartType Enum
**File:** `Models/StartType.swift` (88 lines)

```swift
enum StartType: String, Codable, CaseIterable {
    case flying         // First gate crossing starts timer
    case touchRelease   // Finger lift triggers start
    case countdown      // 3, 2, 1, BEEP!
    case voiceCommand   // AI voice: "On your marks, Set, BEEP!"
    case inFrame        // Athlete in frame, timer starts when they leave
}

enum SprintDistance: Double, CaseIterable {
    case m10=10, m20=20, m30=30, m40=40, m50=50, m60=60, m80=80, m100=100, m150=150, m200=200
}
```

---

## 22. Transport Layer

### TimingTransport Protocol
**File:** `TimingTransport.swift` (55 lines)

```swift
protocol TimingTransport: AnyObject {
    var connectionState: TransportConnectionState { get }
    var onConnectionStateChanged: ((TransportConnectionState) -> Void)? { get set }
    var onMessageReceived: ((TimingMessage) -> Void)? { get set }
    func startSearching(forDeviceId: String?)
    func stopSearching()
    func send(_ message: TimingMessage)
    func disconnect()
    func attemptReconnect()
    var connectedPeerCount: Int { get }
    func connectedDeviceIds() -> [String]
    func setExpectedPeerCount(_ count: Int)
    func deviceId(forSenderId: String) -> String?
    func peerName(forDeviceId: String) -> String?
}

enum TransportConnectionState {
    case disconnected
    case searching
    case connecting
    case connected(peerName: String)
    case error(String)
}
```

### Transport Implementations
- **MultipeerTransport:** Uses MultipeerConnectivity framework (Apple-only, replaced by WiFi Aware on Android)
- **UnifiedTransport:** Wraps multiple transports, provides single interface
- **SupabaseBroadcastTransport:** Uses Supabase Realtime as backup transport

### Dual-Path Broadcasting
```swift
func broadcastDual(payload: TimingMessage.Payload, eventId: String? = nil) {
    // Send via both Multipeer (fast, local) AND Supabase (reliable, internet)
    // Cross-transport deduplication via messageId/eventId
}
```

---

## 23. UserSettings

**File:** `UserSettings.swift` (~300+ lines)
**Role:** App preferences stored in UserDefaults

### Key Settings
```swift
@Observable final class UserSettings {
    static let shared = UserSettings()

    var appearanceMode: AppearanceMode      // system/light/dark/athleteMindset
    var connectionMethod: ConnectionMethod  // auto/wifiAware/multipeer/bluetooth
    var fpsPreference: FPSPreference       // auto/fps60/fps120/fps240
    var announceTimesEnabled: Bool          // Voice time announcements
    var selectedVoice: ElevenLabsService.VoiceID
    var startSoundType: StartSoundType     // beep/gunshot/whistle
    var onYourMarksDelayMin/Max: Double    // Voice start timing
    var setHoldTimeMin/Max: Double         // Voice start timing
    var userId: String?                    // Supabase user ID
    var pendingFlyingPRSync: String        // Retry flag for cloud sync
    var isEligibleForInfluencerOffer: Bool
}

enum AppearanceMode: String, Codable { case system, light, dark, athleteMindset }
enum ConnectionMethod: String, Codable { case auto, wifiAware, multipeer, bluetooth }
enum FPSPreference: String, Codable { case auto, fps60, fps120, fps240 }
```

---

## 24. Key Data Flow Diagrams

### Multi-Phone Crossing Flow
```
START PHONE                           FINISH PHONE
-----------                           ------------
Crossing detected
  |
  v
reportImmediateCrossing()
  | -> Start timer, play beep
  | -> Store resilientCrossingTimestamp
  v
completeCrossingWithImage()
  | -> Draw gate line on image
  | -> broadcastCrossingEvent(.startLine)
  |     |-> broadcastDual (P2P + Supabase)
  |     |-> uploadThumbnailInBackground()
  | -> supabase.sendStartEvent()
  | -> state = .running(startTime)
  |                                    handleStartEvent(remoteTimestamp)
  |                                      | -> convertToLocal(remoteTimestamp)
  |                                      | -> state = .running(localStartTime)
  |                                      | -> startElapsedTimer()
  |                                      v
  |                                    Crossing detected
  |                                      |
  |                                      v
  |                                    reportImmediateCrossing()
  |                                      | -> Store timestamp
  |                                      v
  |                                    completeCrossingWithImage()
  |                                      | -> processFinishCrossing()
  |                                      |     splitNanos = finish - start
  |                                      | -> broadcastDual(.timingResultBroadcast)
  |                                      | -> completeRunAndAutoReset()
  v                                      v
handleTimingResultBroadcast()          Auto-save + announce time
  | -> completeRunAndAutoReset()         |
  | -> Auto-save + announce time         v
  v                                    autoResetForNewRun() (0.5s)
autoResetForNewRun() (0.5s)              | -> broadcastDual(.newRun)
  | -> state = .waitingForCrossing       | -> state = .waitingForCrossing
```

### Solo Mode Flow
```
SINGLE PHONE
-----------
First crossing: completeSoloCrossing()
  | -> startEventRecorded = true
  | -> soloLapResults.append(lapNumber: 0, elapsedNanos: 0)
  | -> resetDetectionFlagsForNextCrossing()

Subsequent crossings: completeSoloCrossing()
  | -> elapsedNanos = timestampNanos - startTimestampLocal
  | -> lapSplitNanos = elapsedNanos - previousElapsedNanos
  | -> soloLapResults.append(lapNumber, elapsedNanos)
  | -> speakTime(lapSplitSeconds)  // Announce LAP SPLIT
  | -> resetDetectionFlagsForNextCrossing()
  | -> UI throttling: max 1 update per 300ms
  | -> Memory: trim to 50 entries, nil calibration for old laps
```

### Clock Sync Flow
```
DEVICE A (Client)                    DEVICE B (Server)
-----------------                    -----------------
startFullSync()
  |
  |-- for i in 0..<100:
  |     T1 = monotonicNanos()
  |     send(syncPing(T1))
  |                                    T2 = monotonicNanos()
  |                                    T3 = monotonicNanos()
  |                                    send(syncPong(T1, T2, T3))
  |     T4 = monotonicNanos()
  |     RTT = (T4-T1) - (T3-T2)
  |     offset = ((T2-T1) + (T3-T4)) / 2
  |     if RTT < maxRTT: addSample()
  |     delay(50ms)
  |
  |-- Sort by RTT ascending
  |-- Keep lowest 20%
  |-- Remove > 2x minRTT
  |-- Median offset -> finalOffset
  |-- Uncertainty = minRTT/2 + MAD
  |-- Quality tier assignment
  |
  v
SyncResult(offsetNanos, uncertaintyMs, quality)
```

---

## Appendix: File Size Summary

| File | Lines | Category |
|------|-------|----------|
| RaceSession.swift | 1248 | Orchestrator |
| RaceSession+MessageHandling.swift | 687 | Orchestrator |
| RaceSession+CrossingDetection.swift | 813 | Orchestrator |
| RaceSession+Timing.swift | 1031 | Orchestrator |
| RaceSession+PublicActions.swift | 991 | Orchestrator |
| RaceSession+Types.swift | 192 | Orchestrator |
| TimingMessage.swift | 865 | Protocol |
| CrossingDetector.swift | 1163 | Detection |
| BackgroundModel.swift | 533 | Detection |
| ContiguousRunFilter.swift | 359 | Detection |
| TorsoBoundsStore.swift | 277 | Detection |
| PoseService.swift | ~200 | Detection |
| CameraManager.swift | 1022 | Camera |
| GateEngine.swift | 235 | Camera |
| ClockSyncService.swift | 1178 | Sync |
| CompositeBuffer.swift | ~300 | Camera |
| RollingShutterService.swift | 124 | Detection |
| IMUService.swift | 391 | Sensor |
| SupabaseService.swift | 2150 | Cloud |
| SessionPersistenceService.swift | 768 | Persistence |
| ImageStorageService.swift | 309 | Persistence |
| VoiceStartService.swift | 674 | Audio |
| ElevenLabsService.swift | 567 | Audio |
| DeviceIdentity.swift | 97 | Identity |
| TrainingSession.swift | 351 | Model |
| Athlete.swift | 228 | Model |
| StartType.swift | 88 | Model |
| UserSettings.swift | ~300 | Settings |
| TimingTransport.swift | 55 | Transport |
| **Total** | **~16,000+** | |
