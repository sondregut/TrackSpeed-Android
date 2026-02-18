# Speed Swift (iOS) Comprehensive Review for Android Port

**Reviewed:** February 2026
**iOS App Location:** `/Users/sondre/Documents/App/speed-swift/SprintTimer/SprintTimer/`
**Purpose:** Document all features, UI patterns, and implementation details for the TrackSpeed-Android port.

---

## Table of Contents

1. [App Architecture & Entry Point](#1-app-architecture--entry-point)
2. [Navigation & Tab Structure](#2-navigation--tab-structure)
3. [Home Screen / Dashboard](#3-home-screen--dashboard)
4. [Test Presets / Templates](#4-test-presets--templates)
5. [Session Creation Flow](#5-session-creation-flow)
6. [Timing Session View](#6-timing-session-view)
7. [Solo / Basic Mode](#7-solo--basic-mode)
8. [Join Session Flow](#8-join-session-flow)
9. [Detection Modes](#9-detection-modes)
10. [Camera & FPS Settings](#10-camera--fps-settings)
11. [Start Types](#11-start-types)
12. [Voice Start / ElevenLabs](#12-voice-start--elevenlabs)
13. [Session History](#13-session-history)
14. [Athlete Management](#14-athlete-management)
15. [Profile & Settings](#15-profile--settings)
16. [User Settings (All Preferences)](#16-user-settings-all-preferences)
17. [Subscription / Paywall](#17-subscription--paywall)
18. [Onboarding Flow](#18-onboarding-flow)
19. [Theme & Color System](#19-theme--color-system)
20. [Models & Data Persistence](#20-models--data-persistence)
21. [Multi-Device Communication](#21-multi-device-communication)
22. [Video Overlay Feature](#22-video-overlay-feature)
23. [Calculator Tools](#23-calculator-tools)
24. [Device Capability Detection](#24-device-capability-detection)
25. [Missing Features in Android](#25-missing-features-in-android)

---

## 1. App Architecture & Entry Point

**File:** `SprintTimerApp.swift`

- Entry point uses `@main` with `SwiftUI App` protocol
- `ModelContainer` with schema: `PersistedCrossing`, `TrainingSession`, `Run`, `Athlete`, `UserProfile`
- Has DB corruption recovery with in-memory fallback
- `AppDelegate` handles:
  - RevenueCat configuration via `SubscriptionManager.configure()`
  - Google Sign-In URL handling
  - Push notification registration
  - Memory warning handling (clears ElevenLabs audio caches)
- `RootView` decides between `OnboardingContainerView` and `ContentView` based on `settings.hasCompletedOnboarding`
- Supports deep links: `trackspeed://promo`, `trackspeed://invite/CODE`, `trackspeed://settings`
- Status bar is hidden app-wide: `.statusBarHidden()`
- Preferred color scheme set from `settings.appearanceMode.colorScheme`
- Toast overlay applied at root level: `.toastOverlay()`

**Android equivalent:** Hilt DI + Room database + single Activity with Compose navigation.

---

## 2. Navigation & Tab Structure

**File:** `Views/MainTabView.swift`

4-tab layout with native `TabView`:

| Tab | Label | Icon | View |
|-----|-------|------|------|
| 0 | Home | `house.fill` | `DashboardHomeView` |
| 1 | Templates | `list.bullet.rectangle.portrait` | `TemplatesView` |
| 2 | History | `clock.arrow.circlepath` | `SessionHistoryView` |
| 3 | Profile | `person.circle.fill` | `ProfileView` (contains settings) |

- Uses `.glassTabBar()` modifier for frosted glass effect
- `fullScreenCover` for Create Session and Paywall
- Listens for `.didReceivePromoOffer` notification to show paywall

**Key difference from Android:** Android currently does not have this tab structure. The iOS app has evolved from a simpler layout to this 4-tab dashboard approach.

---

## 3. Home Screen / Dashboard

**File:** `Views/Home/DashboardHomeView.swift`

Layout (top to bottom in a `List` with `.insetGrouped` style):

1. **Billing Issue Banner** - Shows if `subscriptionManager.isInBillingGracePeriod`
2. **Header Section** - App icon ("HomeIcon" image asset), "TrackSpeed" title, "Precision sprint timing" subtitle
3. **Quick Start Section** - "QUICK START" label
   - 2x2 grid of `PresetCardButton` cards (adaptive based on usage via `settings.adaptiveFeaturedPresets`)
   - Each card shows: colored icon circle, preset name
   - Solo ("Practice") preset is free; all others require Pro subscription
   - "Custom Session" button with slider icon - opens `CreateSessionFlowView`
   - "Join Session" button - opens `JoinSessionView` (FREE for everyone)
4. **Recent Sessions Section** - Last 5 sessions with "See All" link to History tab
   - `RecentSessionRow` for each session
   - Empty state: `ContentUnavailableView` with "No Sessions Yet"
5. **Debug Tools** (DEBUG only) - Collapsible debug section

**Greeting:** Time-based greeting ("Good morning/afternoon/evening") + first name from settings

**Transport-aware UI:** Start/Join button icons and colors change based on `TransportFactory.preferredType`:
- Wi-Fi Aware: green, wifi icon
- Multipeer: blue, antenna icon
- Bluetooth: orange/warning, dot.radiowaves icon

**Android status:** Android needs this full dashboard with Quick Start grid, recent sessions, and join button.

---

## 4. Test Presets / Templates

**File:** `Models/TestPreset.swift`, `Views/TemplatesView.swift`

### Preset Definitions

Each preset has: `id`, `name`, `shortName`, `distance`, `icon`, `color`, `category`, `availableStartTypes`, `defaultStartType`, `minPhones`, `maxPhones`, `gatePositions`, `tips`, optional `selectableDistances`.

| ID | Name | Distance | Start | Phones | Category |
|----|------|----------|-------|--------|----------|
| `40yd` | 40 Yard Dash | 36.576m | Touch Release | 2 | Combine |
| `60m` | 60m Sprint | 60m | Voice Command | 2-3 | Acceleration |
| `flying` | Flying Sprint | 10/20/30m | Flying | 2 | Max Speed |
| `takeoff-velocity` | Take Off Velocity | 5m | Flying | 2 | Max Speed |
| `flying-10m` | Flying 10m | 10m | Flying | 2 | Max Speed |
| `flying-30m` | Flying 30m | 30m | Flying | 2 | Max Speed |
| `30m` | 30m Sprint | 30m | Touch Release | 2 | Acceleration |
| `practice` | Solo Mode | 0 (variable) | Flying | 1 | Acceleration |
| `5-10-5` | Pro Agility | 18.288m | In-Frame | 1 | Agility |
| `100m` | 100m Sprint | 100m | Voice Command | 2-4 | Acceleration |
| `10m` | 10m Acceleration | 10m | Touch Release | 2 | Acceleration |
| `20m` | 20m Sprint | 20m | Touch Release | 2-3 | Acceleration |
| `l-drill` | L-Drill (3-Cone) | 27.432m | In-Frame | 1 | Agility |

### Categories

| Category | Icon | Color |
|----------|------|-------|
| Acceleration | `hare.fill` | Primary |
| Max Speed | `bolt.fill` | Warning/Orange |
| Agility | `arrow.left.arrow.right` | Success/Green |
| Combine | `sportscourt.fill` | Info/Blue |

### Sport-Specific Defaults

Presets adapt to user's sport category (set during onboarding):
- Sprints: Flying 10m, Flying 30m, 30m Sprint, Solo
- Team Sports: Flying 10m, 40 Yard Dash, Pro Agility, Solo
- Field Events: Flying 10m, Take Off Velocity, Flying 30m, Solo

### Templates View

- Grouped by category with section headers
- Searchable
- Each row: icon, name, short distance, description, phone count
- Pro gate overlay for non-free presets

**Android status:** Android needs the full preset system with sport-adaptive defaults.

---

## 5. Session Creation Flow

**File:** `Views/CreateSessionFlowView.swift`

### Flow Steps (Host)

1. **Pairing** (Wi-Fi Aware only, iOS 26+) - Device pairing
2. **Connect** - Scan for and connect to other phones. Auto-assigns roles.
3. **Track Setup** - Configure start type and gate distances
4. **Athletes** - Select athletes for the session

Step indicator with numbered circles and connecting lines (checkmarks for completed steps).

### Key Implementation Details

- `TransportWrapper` wraps the connection transport
- `RaceSession` is created here and passed to `TimingSessionView` to prevent recreation on SwiftUI re-renders
- BLE device ID mapping: `bleToDeviceIdentityMap` maps CBCentral identifiers to DeviceIdentity
- Gate distances stored as `[Int: Double]` (gate index -> cumulative distance from start)
- Default: 2 phones, host is finish line
- Solo mode triggers `BasicModeView` directly

### Preset Session Flow

**File:** `Views/PresetSessionView.swift`

Streamlined flow for presets:
1. **Info** - Preset description, tips, distance selection (for flying), gate count (for 60m)
2. **Start Type** - Only shown if preset has multiple options
3. **Athletes** - Select athletes
4. **Pairing** - Wi-Fi Aware only
5. **Connect** - Connect devices

---

## 6. Timing Session View

**File:** `Views/TimingSessionView.swift`

This is the main camera/timing view shown during an active session. Very complex (~2000+ lines).

### Session States

```
setupPreview → calibrating → calibrated → waitingForCrossing → running → finished
```

### Key UI Elements

- **Camera preview** - Full screen camera feed
- **Gate position line** - Vertical line overlay at gate position
- **Status indicators** - Calibration state, armed state, connection status
- **Timer display** - Elapsed time when running
- **Tab bar** - Two tabs: Record (camera) and Results (run list)
- **Trigger flash** - Screen flash on crossing detection
- **Exit confirmation** - Alert before leaving session
- **Start type dropdown** - Changeable mid-session
- **Settings sheet** - Mid-session settings
- **Touch Start overlay** - For touch release start type
- **Voice Start overlay** - For voice command start type
- **Countdown overlay** - For countdown start type
- **PR toast** - Celebration when personal record is set
- **Photo Finish** - Fullscreen photo finish review
- **Calibration scrubber** - Frame-by-frame scrubber for calibration

### Start Type Integration

- **Flying Start**: Camera detection on both phones
- **Touch Release**: Start phone shows touch overlay, finish phone uses camera
- **Voice Command**: Start phone plays AI voice commands, timer starts on "GO!"
- **Countdown**: Start phone plays "3, 2, 1, BEEP!", timer starts on beep
- **In-Frame**: Front camera, timer starts when athlete leaves frame

### Gate Roles

- `start` - Start line camera
- `finish` - Finish line camera
- `lap` - Intermediate timing gate
- `control` - Host control only (no camera)

### Crossing Feedback

- Beep sound (custom MP3: `beep-401570.mp3`)
- Screen flash (white flash overlay)
- Time announcement via ElevenLabs TTS
- All toggleable in settings

---

## 7. Solo / Basic Mode

**File:** `BasicModeView.swift`

Single-phone mode for lap timing:

### States

- `voiceStart` - Showing voice start overlay
- `calibrating` - Building background model
- `armed` - Ready for detection
- `running` - Timer running, counting laps
- `stopped` - Session ended
- `waitingInFrame` - For in-frame start: waiting for athlete to enter
- `inFrameReady` - Athlete in frame, ready to start when they leave

### Features

- Each crossing is a lap
- 1-second cooldown between crossings (prevents double-trigger)
- Shows lap list with thumbnails and times
- Supports flying start, voice command, countdown, and in-frame start
- `CrossingRecord`: stores PTS nanos, thumbnail data, computed lap/split times

---

## 8. Join Session Flow

**File:** `JoinSessionView.swift`

### Flow (Secondary Phone)

1. Check network state (WiFi on? Bluetooth available?)
2. **Pairing step** (Wi-Fi Aware only) - Pair with host device
3. **Scanning** - Search for host
4. **Connecting** - Establish connection
5. **Connected** - Wait for host to start session

### Visual Distinction

- **Green gradient background** for joiner (vs blue for host) - visual distinction
- Transport-specific visuals (WiFi Aware green, Multipeer blue, BLE orange)
- Rotating tips during search
- Elapsed time display during search

### State Management

- `RaceSession` lives in `JoinSessionView` (not in fullScreenCover) to survive re-renders
- Role/config assigned by host during handshake
- Supports auto-election as host if both phones tap "Join"

---

## 9. Detection Modes

**File:** `GateEngine.swift` (DetectionMode enum)

Three detection modes:

### 1. Photo Finish (Default)

**File:** `PhotoFinishDetector.swift`

- No calibration needed, instant ready
- Frame differencing + blob tracking
- Work resolution: 160x284 (portrait, downsampled)
- IMU (gyroscope) stability check: threshold 0.15 rad/s, 1.0s stable before arming
- Minimum blob height: 33% of frame for crossing, 20% for "Ready" status
- Motion threshold: adaptive, default 14 (out of 255)
- Velocity filter: >= 60 px/s at work resolution
- Cooldown: 0.3s between triggers
- Rearm hysteresis: 25% of work width (40px)
- Column density: 15% vertical run, 8+ columns horizontal for body vs arm distinction
- Rolling shutter compensation applied
- FPS options: 30, 60, 120 (default 30)

### 2. Precision Mode

- Pose detection (Apple Vision / ML Kit) + background subtraction
- Requires calibration (30 frames background model)
- Sub-millisecond accuracy with quadratic interpolation
- 3-strip validation, chest band filtering
- Rolling shutter correction
- FPS options: Auto, 60, 120, 240 (device-dependent)

### 3. Experimental Mode

- Zero-allocation blob tracking (GatedTracker)
- Low thermal for 4+ hour runtime
- Auto-recovery after phone movement
- FPS options: 30, 60, 120 (default 60)

### Gate Status

`GateStatus` struct tracks:
- `isCalibrated`, `isArmed`, `isClear`, `isPrebufferReady`, `isStable`
- `rCenter` (center strip occupancy 0-1)
- `prebufferSpanMs` (minimum 350ms required)
- `isCameraShaking`, `isTooFar` (Photo Finish specific)
- `blobHeightFraction`, `motionAmount`
- `detectionMode`, `isBackgroundStable`
- `canArm` computed property (mode-specific requirements)

---

## 10. Camera & FPS Settings

**Files:** `UserSettings.swift` (lines 105-247), `CameraManager.swift`, `DeviceCapability.swift`

### FPS Preference (Precision Mode)

```swift
enum FPSPreference: String, CaseIterable, Codable {
    case auto = "auto"     // "Recommended for your device"
    case fps60 = "60"      // "Battery saver, lower precision" - battery.100 icon
    case fps120 = "120"    // "Balanced performance" - speedometer icon
    case fps240 = "240"    // "Maximum precision" - bolt.fill icon
}
```

- Default: `auto` (uses `DeviceCapability.shared.recommendedMaxFPS`)
- Shows warning if selected FPS exceeds device capability
- UI: Presented in Settings as a Picker

### Photo Finish FPS

```swift
enum PhotoFinishFPSPreference: String, CaseIterable, Codable {
    case fps30 = "30"    // "Lower thermal, ~17ms thumbnail accuracy" - leaf.fill
    case fps60 = "60"    // "Balanced, ~8ms thumbnail accuracy" - speedometer
    case fps120 = "120"  // "Best accuracy, ~4ms thumbnails" - bolt.fill
}
```

- Default: `fps30` (30fps)
- This is what the Settings page shows as "Camera FPS" picker
- Used in the default Photo Finish detection mode

### Experimental FPS

```swift
enum ExperimentalFPSPreference: String, CaseIterable, Codable {
    case fps30 = "30"    // "Lowest thermal, 8hr+ runtime" - leaf.fill
    case fps60 = "60"    // "Balanced (recommended)" - speedometer
    case fps120 = "120"  // "Higher precision, more thermal" - bolt.fill
}
```

- Default: `fps60` (60fps)

### Camera Manager

- Uses `AVCaptureSession` with `AVCaptureVideoDataOutput`
- Session queue for configuration, video queue (`.userInteractive` QoS) for frame delivery
- Thermal monitoring: logs state changes, throttle logic (currently disabled for debug)
- Exposure monitoring: warns if > 4ms (1/250s) - blur risk
- Properties exposed: `currentFPS`, `isRunning`, `isUsingFrontCamera`, `isExposureLocked`, `isWhiteBalanceLocked`, `isFocusLocked`, `isFullyCalibrated`
- Performance monitor: tracks actual FPS and dropped frames

### How FPS is Presented in UI

In `Views/Settings/SettingsView.swift` (line 55-58):
```swift
Picker("Camera FPS", selection: $settings.photoFinishFPS) {
    ForEach(PhotoFinishFPSPreference.allCases, id: \.self) { fps in
        Label(fps.displayName, systemImage: fps.icon).tag(fps)
    }
}
```

It's a simple `Picker` in the "Preferences" section, showing 30/60/120fps options for Photo Finish mode.

---

## 11. Start Types

**File:** `Models/StartType.swift`

| Type | Display Name | Icon | Description | Requires Start Gate | Front Camera |
|------|-------------|------|-------------|-------------------|--------------|
| `flying` | Flying Start | `figure.run` | Timer starts on first phone crossing | Yes | No |
| `touchRelease` | Touch Release | `rectangle.and.hand.point.up.left.filled` | Hold screen, lift finger to start | No | No |
| `countdown` | Countdown | `timer` | 3, 2, 1, BEEP! | No | No |
| `voiceCommand` | Voice Command | `mic.fill` | AI voice: On your marks, Set, GO! | No | No |
| `inFrame` | In-Frame Start | `person.crop.artframe` | Stand in frame, timer starts when you leave | No | Yes |

### Sprint Distances

```swift
enum SprintDistance: Double, CaseIterable {
    case m10 = 10, m20 = 20, m30 = 30, m40 = 40, m50 = 50
    case m60 = 60, m80 = 80, m100 = 100, m150 = 150, m200 = 200
}
```

---

## 12. Voice Start / ElevenLabs

**Files:** `VoiceStartService.swift`, `ElevenLabsService.swift`

### Voice Configuration

- **Provider options:** System (AVSpeechSynthesizer, offline) or ElevenLabs (AI voice, online)
- **ElevenLabs voices:**
  - Adam (Deep Male) - `pNInz6obpgDQGcFmaJgB`
  - Josh (Friendly Male) - `TxGEqnHWrfWFTfGW9XjX`
  - Arnold (Commanding Male) - `VR6AewLTigWG4xSOukaG` (default)
  - Rachel (Clear Female) - `21m00Tcm4TlvDq8ikWAM`
  - Bella (Warm Female) - `EXAVITQu4vr4xnSDxMaL`
  - Elli (Energetic Female) - `MF3mGyEYCl7XYWbV9V6O`

### Voice Start Timing

Configurable in Settings:
- "On your marks" to "Set" delay: 8-12s (range 3-20s, step 1s)
- "Set" to "GO!" delay: 1.5-2.3s (range 0.5-5.0s, step 0.1s)
- International starters average 1.78s for Set-to-GO

### Start Sound Types

```swift
enum StartSoundType: String, CaseIterable {
    case beep = "beep"        // "Simple electronic beep"
    case gunshot = "gunshot"  // "Realistic starting pistol"
    case whistle = "whistle"  // "Coach whistle"
}
```

### ElevenLabs TTS

- Uses Supabase Edge Function as proxy: `elevenlabs-tts`
- Disk + memory cache for generated audio
- Time announcements after crossing detection
- Prevents concurrent announcements (queue buildup protection)

---

## 13. Session History

**File:** `Views/History/SessionHistoryView.swift`, `Views/History/SessionDetailView.swift`

### History View

- Sessions listed chronologically (newest first)
- **Filters:**
  - Time: All, This Week, This Month
  - Distance: All, 40m, 60m, 100m, 200m
  - Start Type: All, Flying, Touch Release, Voice Command, Countdown
  - Mode: All, 1-Phone, 2-Phone
- **Sort options:** Newest, Oldest, Fastest, Most Runs
- **Search:** Searches date, location, athlete names, distance
- **Export:** CSV export with share sheet
- Searchable

### Session Detail View

- Session info bar: date, distance, start type, mode description
- Athlete filter bar (if multiple athletes)
- Runs sorted by time (fastest first)
- Each run row (compact): run number, time, athlete color chip, speed, PB/SB badges
- Expandable: shows start/finish/lap thumbnails
- Tap thumbnail for fullscreen view
- Swipe-to-delete runs
- Video overlay option per run

### Run Detail View

**File:** `Views/History/RunDetailView.swift`

- Detailed view for a single run
- Shows all gate images
- Split times display
- Frame scrubber for calibration
- Share card generation

---

## 14. Athlete Management

**File:** `Views/AthleteListView.swift`, `Models/Athlete.swift`

### Athlete Model

- Fields: `id` (UUID), `name`, `nickname`, `color` (AthleteColor), `photoData`, `birthdate`, `gender`, `personalBests` ([String: Double]), `seasonBests` ([String: Double])
- PR key format: `"startType_distanceM"` (e.g., `"flying_30m"`, `"touchRelease_40m"`)
- `updatePersonalBest()` returns `PRUpdateResult` with `isNewPR`, `previousBest`, `improvement`
- `personalBestsByStartType` computed property groups PRs

### Athlete Colors

8 colors: red, orange, yellow, green, blue, purple, pink, gray

### Gender

3 options: male, female, other

### Athlete List View

- Search by name or nickname
- Add/Edit forms
- Empty state with icon and CTA
- Syncs to Supabase cloud in background

---

## 15. Profile & Settings

**File:** `Views/Settings/SettingsView.swift`

Settings is a `List` with sections:

### Preferences Section

- Frame Calibration toggle (DEBUG/TestFlight only)
- Save Crossing Frames toggle (default: on)
- Frame Scrubbing toggle (default: off)
- Camera FPS picker (30/60/120 for Photo Finish mode)
- Speed Unit picker (m/s, km/h, mph)
- Start Voice picker (6 ElevenLabs voices)
- Preview Voice button (plays sample)

### Voice Start Timing Section

- "Marks -> Set" delay: two sliders (min/max), 3-20s range
- "Set -> GO!" delay: two sliders (min/max), 0.5-5.0s range
- Footer: "International starters use 1.5-2.3s for Set -> GO!"

### Crossing Feedback Section

- Crossing Beep toggle (default: on)
- Screen Flash toggle (default: on)
- Announce Times toggle (default: on)

### Notifications Section

- Link to `NotificationSettingsView`

### Connection Section

- Radio-button list: Auto, Multipeer, Bluetooth
- Each shows icon, name, subtitle
- Info button opens connection info sheet
- Wi-Fi Aware option (iOS 26+ only)

### Data Section

- Storage Used (calculated)
- Saved Results count
- Export Data button (CSV)
- Clear Data button (with confirmation)

### Account Section (if authenticated)

- Sign Out button
- Delete Account button (requires typing "DELETE" to confirm)

### Debug Section (DEBUG builds)

- Various debug toggles and buttons
- Show Paywall, Reset Onboarding, etc.

### App Info

- Version display

---

## 16. User Settings (All Preferences)

**File:** `UserSettings.swift` - Comprehensive list of all persisted settings:

| Setting | Key | Default | Type |
|---------|-----|---------|------|
| Appearance Mode | `appearanceMode` | `system` | AppearanceMode (system/light/dark/athleteMindset) |
| Speed Unit | `speedUnit` | `m/s` | SpeedUnit |
| User Name | `userName` | `""` | String |
| Last Distance | `lastDistance` | `20.0` | Double |
| Connection Method | `connectionMethod` | `auto` | ConnectionMethod |
| FPS Preference | `fpsPreference` | `auto` | FPSPreference |
| Detection Mode | `detectionMode` | `photoFinish` | DetectionMode |
| Experimental FPS | `experimentalFPS` | `60` | ExperimentalFPSPreference |
| Photo Finish Min Blob Size | `photoFinishMinBlobSize` | `0.20` | Float |
| Photo Finish FPS | `photoFinishFPS` | `30` | PhotoFinishFPSPreference |
| Enable Frame Calibration | `enableFrameCalibration` | `true` (debug) | Bool |
| Show Debug UI | `showDebugUI` | `false` | Bool |
| Show Speed in Results | `showSpeedInResults` | `true` | Bool |
| Show Speed in Video Overlay | `showSpeedInVideoOverlay` | `true` | Bool |
| Show Run Type in Video Overlay | `showRunTypeInVideoOverlay` | `true` | Bool |
| Center Thumbnails | `centerThumbnails` | `false` | Bool |
| Save Multiphone Frames | `saveMultiphoneFrames` | `true` | Bool |
| Enable Multiphone Calibration | `enableMultiphoneCalibration` | `false` | Bool |
| Crossing Beep Enabled | `crossingBeepEnabled` | `true` | Bool |
| Crossing Flash Enabled | `crossingFlashEnabled` | `true` | Bool |
| Announce Times Enabled | `announceTimesEnabled` | `true` | Bool |
| Selected Voice | `selectedVoice` | Arnold | ElevenLabsService.VoiceID |
| Start Sound Type | `startSoundType` | `beep` | StartSoundType |
| On Your Marks Delay Min | `onYourMarksDelayMin` | `8.0` | Double |
| On Your Marks Delay Max | `onYourMarksDelayMax` | `12.0` | Double |
| Set Hold Time Min | `setHoldTimeMin` | `1.5` | Double |
| Set Hold Time Max | `setHoldTimeMax` | `2.3` | Double |
| App Language | `appLanguage` | `""` (system) | String |
| Attribution Source | `attributionSource` | `""` | String |
| User Referral Code | `userReferralCode` | `""` | String |
| Has Skipped Login | `hasSkippedLogin` | `false` | Bool |
| Has Completed Full Onboarding | `hasCompletedFullOnboarding` | `false` | Bool |
| User ID (Supabase) | `userId` | `nil` | String? |
| Sport Category | `sportCategory` | `nil` | SportCategory? |
| Preset Launch Counts | `presetLaunchCounts` | `{}` | JSON dict |

### Profile Photo

- Stored as JPEG at `Documents/profile_photo.jpg`
- 80% compression quality
- Load/save/delete methods
- `hasProfilePhoto` computed property

### Preset Distances

```swift
static let presetDistances: [Double] = [10, 20, 30, 40, 60, 100]
```

### Speed Conversion

- `convertSpeed(_ mps: Double) -> Double` - converts m/s to selected unit
- `formatSpeed(_ mps: Double) -> String` - formats with unit suffix

---

## 17. Subscription / Paywall

**File:** `SubscriptionManager.swift`, `Views/PaywallView.swift`

### Products

- Monthly: $8.99/month
- Yearly: $49.99/year (54% savings)

### Integration

- RevenueCat SDK (`appl_XGiqCpycqHRisYTkyTSpTUgXHCm`)
- Entitlement: "Track Speed Pro"
- Pro access via: RevenueCat subscription OR promo code OR Supabase manual grant

### Pro Gates

- Multi-phone sync requires Pro (joining is free)
- Solo/Practice mode is free
- All presets except "practice" require Pro

### States Tracked

- `isProUser`, `hasRevenueCatPro`, `hasPromoAccess`, `hasSupabaseSubscription`
- `willRenew`, `isCancelledButActive`, `isInBillingGracePeriod`
- `gracePeriodDaysRemaining`, `expirationDate`

### Paywall Features

- Highlighted feature parameter (e.g., `.multiPhoneSync`)
- Trial reminder scheduling
- Promotional offer notifications
- Spin wheel discount (shown once if paywall skipped)

---

## 18. Onboarding Flow

**File:** `Views/Onboarding/OnboardingContainerView.swift`

### Steps (19 total)

0. **Get Started** - Welcome screen
1. **Value Proposition** - "Turn your phone into a professional sprint timer"
2. **How It Works** - Quick 3-step overview
3. **Athlete Details** - Sport/discipline selection
4. **Flying Time** - Current PR input
5. **Goal Time** - Target time setting
6. **Goal Motivation** - Progress graph visualization
7. **Start Types** - 5 ways to start (contextualized to sport)
8. **Multi-Device** - Advanced multi-phone feature
9. **Attribution** - Where they heard about the app (includes promo code entry)
10. **Rating** - Social proof
11. **Competitor Comparison** - Price anchoring vs competitors
12. **Auth** - Sign in with Apple / Google
13. **Trial Intro** - Free trial offer
14. **Trial Reminder** - Reminder 2 days before trial ends
15. **Notification** - Push notification permission
16. **Paywall** - Subscription selection
17. **Spin Wheel** - Gamified discount (shown once if paywall skipped)
18. **Completion** - "You're all set!"

### Auth Options

- Sign in with Apple
- Google Sign-In
- Skip login (local-only usage, with `isGuestJoinMode` for free joining)

### Profile Collection

- Full name, role (athlete/coach), sport discipline, primary event PR, flying sprint PR, goal time

---

## 19. Theme & Color System

**File:** `AppTheme.swift`

### Appearance Modes

| Mode | Scheme | Description |
|------|--------|-------------|
| System | nil (follows device) | Default |
| Light | `.light` | Cal AI-inspired warm design |
| Dark | `.dark` | LaserSpeed design - deep charcoal |
| AthleteMindset | `.dark` | Premium near-black (Chase Sapphire aesthetic) |

### Color Palette

**Light Mode (Cal AI-inspired):**
- Background: `#F5F5F7` (soft cool gray)
- Surface: `#FFFFFF` (pure white cards)
- Primary text: `#1D1312` (warm near-black)
- Secondary text: `#8E8C8B` (muted gray)

**Dark Mode (LaserSpeed):**
- Background: `#191919` (deep charcoal)
- Background gradient: `#263138` to `#0e1316` (blue-tinted)
- Surface: `#2B2E32` (blue-gray)
- Primary text: `#FDFDFD` (off-white)

**AthleteMindset (Premium):**
- Background gradient: `#0d0d0d` → `#1f1f1f` → `#141414` (diagonal)
- Accent: `#5c8db8` (light navy)
- Primary text: pure white

### Semantic Colors

- Success: `#34A47A` (light) / `#22C55E` (dark)
- Error: `#D96D6D` (light) / `#FC0726` (dark)
- Warning: `#EC9732` (light) / `#FEAA21` (dark)
- Info: `#68A1D6`
- Primary Accent: System accent (light) / `#5C8DB8` (dark)

### Gradients

- Host background: blue-tinted dark gradient
- Joiner background: green-tinted dark gradient (`#1a3328` to `#0d1a14`)
- AthleteMindset: 3-color diagonal gradient

### Glass Effects

- Frosted glass tab bar (`.glassTabBar()`)
- Glass card backgrounds (`.adaptiveGlassCard()`)
- Glass sheet backgrounds (`.glassSheetBackground()`)

---

## 20. Models & Data Persistence

### SwiftData Models

**TrainingSession** (`Models/TrainingSession.swift`):
- `id` (UUID), `date`, `name`, `location`, `notes`
- `distance` (Double), `startTypeRaw` (String)
- `numberOfPhones` (Int, default 2), `numberOfGates` (Int, default 2)
- `gateConfigJSON` (String?)
- `thumbnailPath` (String?)
- `runs` relationship (cascade delete)
- Computed: `bestTime`, `averageTime`, `runsByAthlete`, `athletes`

**Run** (`Models/TrainingSession.swift`):
- `id` (UUID), `athleteId`, `athleteName`, `athleteColorRaw`
- `runNumber` (Int), `timestamp` (Date), `time` (Double seconds)
- `reactionTime` (Double?), `distance` (Double), `startTypeRaw`
- `numberOfPhones`, `isPersonalBest`, `isSeasonBest`
- `startImagePath`, `finishImagePath`, `lapImagePathsJSON`
- `splitsJSON` - encoded `[PersistedSegmentSplit]`
- `localGateFramesDataJSON` - frame data for multi-phone scrubbing
- Computed: `speed`, `speedKMH`, `speedMPH`, `formattedSpeed`

**Athlete** (`Models/Athlete.swift`): See section 14.

**UserProfile** (`Models/UserProfile.swift`):
- `id`, `supabaseId`, `email`, `fullName`, `dateOfBirth`
- `roleRaw` (UserRole), `primaryEvent`, `personalRecord`
- `flyingPRDistanceRaw`, `flyingPR`
- `onboardingCompleted`
- `photoData`, `phoneNumber`, `smsNotificationsEnabled`

**PersistedCrossing** (`PersistedCrossing.swift`): Historical crossing records.

### Image Storage

- `ImageStorageService` saves images to disk with relative paths
- Session images stored as: `sessions/{sessionId}/runs/{runId}/start.jpg`, etc.
- Legacy: `finishImageData` as external storage blob (migration compatible)

---

## 21. Multi-Device Communication

### Transport Types

**File:** `TimingTransport.swift`, `UnifiedTransport.swift`

- **Wi-Fi Aware** (iOS 26+): Extended range, lowest latency
- **Multipeer Connectivity**: Works on all iOS devices, same WiFi
- **Bluetooth**: Works without WiFi
- Factory pattern: `TransportFactory.preferredType` returns best available

### Protocol

**File:** `TimingMessage.swift`

Protocol version: 3

Message types (payload enum):
- **Handshake:** `sessionConfig`, `sessionConfigAck`, `roleRequest`, `roleAssignment`, `startTiming`
- **Clock Sync:** `clockSyncPing`, `clockSyncPong`, `clockSyncResult`
- **Timing:** `crossingEvent`, `crossingAck`, `startEvent`
- **Control:** `gateStatus`, `pauseDetection`, `resumeDetection`, `resetForNextRun`, `endSession`
- **Media:** `thumbnailData`, `calibrationFrames`
- **Config:** `startTypeChange`, `distanceChange`

### Gate Assignment

```swift
struct GateAssignment: Codable {
    let role: TimingRole    // startLine, finishLine, lapGate, controlOnly
    let gateIndex: Int      // 0 = start, N-1 = finish
    let distanceFromStart: Double
    var targetDeviceId: String?
}
```

### Session Config (Network)

```swift
struct TimingSessionConfig: Codable {
    let distance: Double
    let startType: String
    let numberOfGates: Int
    let hostRole: TimingRole
    let fpsMode: Int
    let protocolVersion: UInt16
}
```

### RaceSession State Machine

**File:** `RaceSession.swift`

States: `idle` -> `connecting` -> `syncing` -> `ready` -> `waitingForCrossing` -> `running(startTime)` -> `finished(result)`

Network alerts: `wifiOff`, `internetLost`, `multipeerStale`, `bothPathsDown`, `poorConnectionQuality`

### Hybrid Architecture

- **Huddle Phase**: P2P (Multipeer/BLE/Wi-Fi Aware) for clock sync (phones nearby)
- **Race Phase**: Supabase Realtime for timing events (works at any distance)
- Both paths used simultaneously for redundancy with deduplication

---

## 22. Video Overlay Feature

**File:** `VideoOverlay/VideoOverlayViewModel.swift`, `VideoOverlay/VideoImportView.swift`, etc.

Allows importing video and overlaying timing data:

1. **Import** - Select video from Photos or Files
2. **Sync** - Mark the start crossing point in the video
3. **Preview** - See overlay with split times
4. **Export** - Render video with timing overlay

Overlay shows:
- Split times at each gate crossing point
- Speed display (configurable: on/off)
- Run type display (configurable: on/off)

---

## 23. Calculator Tools

### Wind Adjustment Calculator

**File:** `Models/WindAdjustmentCalculator.swift`, `Views/Calculators/WindAdjustmentView.swift`

Based on Moinat, Fabius & Emanuel (2018):
- Events: 100m, 200m, 100/110m Hurdles
- Formula: `deltaP = a*w + b*w^2` (w = wind speed m/s)
- Legal wind limit: 2.0 m/s
- Max accepted: 10.0 m/s
- Input: event, measured time, wind speed
- Output: wind-adjusted time, wind effect in seconds

### Distance Converter

**File:** `Models/DistanceConverter.swift`, `Views/Calculators/DistanceConverterView.swift`

Modes:
1. **Distance** - Convert equivalent times between 60m/100m/200m using 2025 WA Scoring Tables
2. **Flying Sprint** - Convert flying sprint times
3. **100m Predictor** - Predict 100m from 30m block + 10m flying
4. **Lane Draw** - Lane stagger calculations

Uses WA quadratic formula: `points = a*t^2 + b*t + c`

---

## 24. Device Capability Detection

**File:** `DeviceCapability.swift`

Maps iPhone chip generation to recommended FPS:

| Chip | Devices | Recommended FPS |
|------|---------|----------------|
| A11 or older | iPhone 8, X | 60 |
| A12-A13 | iPhone XR, XS, 11 | 120 |
| A14+ | iPhone 12+ | 240 |

- Singleton: `DeviceCapability.shared`
- Properties: `modelIdentifier`, `chipGeneration`, `recommendedMaxFPS`
- `canHandle240FPS`: A14+ chips
- `canHandle120FPS`: A12+ chips
- `effectiveMaxFPS(userPreference:)`: caps at device capability

**Android equivalent:** Need similar chip/SoC detection for Android devices to cap FPS appropriately.

---

## 25. Missing Features in Android

Based on this review, the following features exist in iOS but may be missing or incomplete in the Android port:

### Critical (Core Functionality)

1. **Photo Finish Detection Mode** - Default detection, no calibration needed
2. **Multi-device pairing & timing** - BLE/Multipeer/Supabase Realtime
3. **Clock synchronization** - NTP-style with drift tracking
4. **5 Start Types** - Flying, Touch Release, Countdown, Voice Command, In-Frame
5. **Solo/Practice mode** - Single phone lap timer
6. **Gate role assignment** - Start/Finish/Lap/Control roles
7. **Multi-gate sessions** - 2-4 phones with intermediate splits

### Important (User Experience)

8. **Quick Start presets** - 13 predefined test configurations
9. **Sport-adaptive defaults** - Presets change based on sport category
10. **Preset usage tracking** - Adaptive home screen based on usage
11. **Session history with filters** - Date, distance, start type, mode filters
12. **Athlete management** - Create, edit, assign to runs, PR tracking
13. **Crossing feedback** - Beep sound, screen flash, voice time announcement
14. **Voice Start with ElevenLabs** - AI voice for "On your marks, Set, GO!"
15. **Start sound options** - Beep, gunshot, whistle
16. **Video overlay** - Import video and overlay timing data
17. **Wind adjustment calculator** - Science-based wind correction
18. **Distance converter** - WA Scoring Tables conversion
19. **Frame scrubbing / calibration** - Per-frame review of crossings

### Nice to Have (Polish)

20. **4-tab navigation** - Home, Templates, History, Profile
21. **Glass/frosted UI effects** - Tab bar, cards, sheets
22. **3 theme modes** - Light, Dark, AthleteMindset
23. **Onboarding flow** - 19-step guided setup
24. **Subscription/Paywall** - RevenueCat integration
25. **PR celebration toast** - Personal record celebration
26. **Export to CSV** - Session data export
27. **Share card generation** - Social media sharing
28. **Push notifications** - Training reminders, trial reminders
29. **Referral system** - Invite codes, extended trials
30. **Deep link support** - `trackspeed://` URL scheme
31. **Thermal monitoring** - FPS throttling on overheating
32. **Rolling shutter correction** - Per-device correction
33. **Joiner green background** - Visual distinction for secondary phone
34. **Transport-aware UI** - Icons/colors change based on connection method
35. **Session state recovery** - Survive app background/termination
36. **Profile photo** - User avatar from camera/gallery
37. **App language selection** - Multi-language support
38. **Billing grace period banner** - Payment failure handling

---

## Appendix: File Index

### Core Services
| File | Purpose |
|------|---------|
| `SprintTimerApp.swift` | App entry point, ModelContainer setup |
| `SprintTimerViewModel.swift` | Main view model for camera/detection |
| `UserSettings.swift` | All persisted user preferences |
| `CameraManager.swift` | 240fps camera capture |
| `GateEngine.swift` | Detection mode config, gate status |
| `PhotoFinishDetector.swift` | Photo Finish detection algorithm |
| `CrossingDetector.swift` | Precision mode crossing detection |
| `ExperimentalCrossingDetector.swift` | Experimental blob tracking |
| `BackgroundModel.swift` | Background subtraction model |
| `PoseService.swift` | ML Kit/Vision pose detection |
| `ContiguousRunFilter.swift` | Chest band / contiguous run |
| `TorsoBoundsStore.swift` | EMA-smoothed torso tracking |
| `CompositeBuffer.swift` | Photo finish composite |
| `RollingShutterService.swift` | Rolling shutter correction |
| `ClockSyncService.swift` | NTP-style clock synchronization |
| `RaceSession.swift` | Multi-phone session state machine |
| `RaceSession+*.swift` | Extensions for message handling, timing, etc. |

### Models
| File | Purpose |
|------|---------|
| `Models/TrainingSession.swift` | Session + Run models (SwiftData) |
| `Models/Athlete.swift` | Athlete model with PR tracking |
| `Models/UserProfile.swift` | User profile + sport categories |
| `Models/StartType.swift` | 5 start types + sprint distances |
| `Models/TestPreset.swift` | 13 predefined test presets |
| `Models/WindAdjustmentCalculator.swift` | Wind correction (Moinat 2018) |
| `Models/DistanceConverter.swift` | WA Scoring Tables converter |

### Views
| File | Purpose |
|------|---------|
| `ContentView.swift` | Root content, HomeView, TestView |
| `Views/MainTabView.swift` | 4-tab navigation |
| `Views/Home/DashboardHomeView.swift` | Home dashboard |
| `Views/TemplatesView.swift` | All templates by category |
| `Views/CreateSessionFlowView.swift` | Host session creation |
| `Views/PresetSessionView.swift` | Preset-based session setup |
| `Views/TimingSessionView.swift` | Active timing session |
| `BasicModeView.swift` | Solo/practice mode |
| `JoinSessionView.swift` | Secondary phone join flow |
| `Views/History/SessionHistoryView.swift` | Session history list |
| `Views/History/SessionDetailView.swift` | Session detail |
| `Views/History/RunDetailView.swift` | Run detail |
| `Views/AthleteListView.swift` | Athlete management |
| `Views/Settings/SettingsView.swift` | App settings |
| `Views/SoloLapsView.swift` | Solo mode lap list |
| `Views/PaywallView.swift` | Subscription paywall |
| `Views/Onboarding/*.swift` | 19-step onboarding |
| `VideoOverlay/*.swift` | Video overlay feature |
| `Views/Calculators/*.swift` | Wind/distance calculators |

### Transport / Communication
| File | Purpose |
|------|---------|
| `TimingMessage.swift` | Protocol messages (v3) |
| `TimingTransport.swift` | Transport protocol |
| `UnifiedTransport.swift` | Unified transport abstraction |
| `MultipeerTransport.swift` | Multipeer Connectivity |
| `BluetoothTransport.swift` | BLE transport |
| `BluetoothManager.swift` | BLE device management |
| `SupabaseBroadcastTransport.swift` | Supabase Realtime transport |
| `WiFiAware/*.swift` | Wi-Fi Aware (iOS 26+) |

### Config & Services
| File | Purpose |
|------|---------|
| `Config/AppConfig.swift` | API keys, URLs |
| `AppTheme.swift` | Colors, gradients, typography |
| `DeviceCapability.swift` | Device FPS capability detection |
| `SubscriptionManager.swift` | RevenueCat integration |
| `AuthService.swift` | Apple/Google sign-in |
| `ElevenLabsService.swift` | AI voice TTS |
| `VoiceStartService.swift` | Voice start commands |
| `ImageStorageService.swift` | Disk image persistence |
| `SessionPersistenceService.swift` | SwiftData persistence |
| `SupabaseService.swift` | Supabase client |
| `ProfileService.swift` | Profile sync |
| `AnalyticsService.swift` | PostHog analytics |
