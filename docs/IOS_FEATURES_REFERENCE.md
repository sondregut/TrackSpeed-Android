# iOS Speed Swift - Complete Feature Reference

**Source:** `/Users/sondre/Documents/App/speed-swift/SprintTimer/SprintTimer/`
**Last Updated:** February 2026

This document catalogs every user-facing feature of the iOS Speed Swift (TrackSpeed) app, intended as a reference for the Android port.

---

## 1. App Structure & Navigation

### 1.1 Tab Bar (4 Tabs)

The main app uses a 4-tab layout (`MainTabView.swift`):

| Tab | Label | Icon | View |
|-----|-------|------|------|
| 0 | Home | `house.fill` | `DashboardHomeView` |
| 1 | Templates | `list.bullet.rectangle.portrait` | `TemplatesView` |
| 2 | History | `clock.arrow.circlepath` | `SessionHistoryView` |
| 3 | Profile | `person.circle.fill` | `ProfileView` |

The tab bar uses a frosted glass style (`.glassTabBar()`).

### 1.2 Root View Flow

`SprintTimerApp` > `RootView`:
- If user has NOT completed full onboarding: shows `OnboardingContainerView`
- If user HAS completed onboarding: shows `MainTabView`
- Guest join mode is supported (join session without subscribing)

---

## 2. Onboarding Flow

Source: `Views/Onboarding/OnboardingContainerView.swift`

The onboarding is a linear 19-step flow with a progress bar. Steps:

| Step | Screen | Purpose |
|------|--------|---------|
| 0 | `getStarted` | Welcome screen with "Get Started", "Join Session" (guest), and "Sign In" options |
| 1 | `valueProposition` | "Turn your phone into a professional sprint timer" |
| 2 | `howItWorks` | Quick 3-step overview (paged TabView) |
| 3 | `athleteDetails` | Sport discipline selection (sprints, hurdles, field events, team sports, etc.) |
| 4 | `flyingTime` | Current flying sprint PR input (10m, 20m, or 30m) |
| 5 | `goalTime` | Goal time input for selected flying distance |
| 6 | `goalMotivation` | "Great goal!" with progress graph visualization |
| 7 | `startTypes` | Shows 5 ways to start (flying, touch release, countdown, voice command, in-frame) |
| 8 | `multiDevice` | Multi-device timing feature showcase |
| 9 | `attribution` | "Where did you hear about us?" + promo code entry |
| 10 | `rating` | Social proof / app ratings |
| 11 | `competitorComparison` | Competitor pricing comparison (price anchoring) |
| 12 | `auth` | Apple Sign-In / Google Sign-In |
| 13 | `trialIntro` | "We want you to try TrackSpeed for free" |
| 14 | `trialReminder` | "You'll get a reminder 2 days before trial ends" |
| 15 | `notification` | Push notification permission request |
| 16 | `paywall` | Subscription screen (monthly/yearly) |
| 17 | `spinWheel` | Gamified discount wheel (shown once if paywall skipped) |
| 18 | `completion` | "You're all set!" |

Key behaviors:
- Each step has a back button (except first and last)
- Progress bar shows completion percentage
- Users can skip login and use as guest
- Users can join a session directly from the welcome screen without subscribing

### 2.1 Data Collected During Onboarding

- Full name
- Role: Athlete or Coach
- Sport discipline (from `SportDiscipline` enum: 100m, 200m, 400m, hurdles, middle distance, long distance, field events, team sports, strength/fitness)
- Sport category (derived from discipline: sprints, hurdles, middleDistance, longDistance, fieldEvents, teamSports, strength)
- Flying sprint PR time + distance (10m, 20m, or 30m)
- Goal time
- Attribution source
- Promo/referral code

---

## 3. Home Screen (Dashboard)

Source: `Views/Home/DashboardHomeView.swift`

### 3.1 Header
- App icon and "TrackSpeed" branding
- "Precision sprint timing" subtitle

### 3.2 Quick Start Grid
- 2x2 grid of featured test presets (adaptive based on usage history)
- Default featured presets: 40 Yard Dash, 60m Sprint, Flying Sprint, Solo Mode
- Adapts after usage: top 4 most-used presets bubble to top
- Sport-specific defaults based on onboarding category selection
- "Custom Session" button below the grid

### 3.3 Action Buttons
- **Start Run** (multi-phone): Opens `CreateSessionFlowView`
- **Join** (gate): Opens `JoinSessionView`
- Button icons/colors change based on transport type (Wi-Fi Aware, Multipeer, Bluetooth)

### 3.4 Recent Sessions
- Shows last few training sessions with:
  - Session name/date
  - Distance and start type
  - Best time
  - Run count
  - Thumbnail image

### 3.5 Billing Banner
- Non-dismissible banner shown during billing grace period if payment fails

---

## 4. Test Presets / Templates

Source: `Models/TestPreset.swift`, `Views/TemplatesView.swift`

### 4.1 Available Presets

| ID | Name | Distance | Start Type | Phones | Category |
|----|------|----------|------------|--------|----------|
| `10m` | 10m Acceleration | 10m | Touch/Voice/Countdown | 2 | Acceleration |
| `20m` | 20m Sprint | 20m | Touch/Voice/Countdown | 2-3 | Acceleration |
| `30m` | 30m Sprint | 30m | Touch/Voice/Countdown | 2 | Acceleration |
| `60m` | 60m Sprint | 60m | Touch/Voice/Countdown | 2-3 | Acceleration |
| `100m` | 100m Sprint | 100m | Touch/Voice/Countdown | 2-4 | Acceleration |
| `practice` | Solo Mode | Variable | Flying | 1 | Acceleration |
| `flying-10m` | Flying 10m | 10m | Flying | 2 | Max Speed |
| `flying-30m` | Flying 30m | 30m | Flying | 2 | Max Speed |
| `flying` | Flying Sprint | 10/20/30m (selectable) | Flying | 2 | Max Speed |
| `takeoff-velocity` | Take Off Velocity | 5m | Flying | 2 | Max Speed |
| `5-10-5` | Pro Agility (5-10-5) | 20yd (18.29m) | In-Frame | 1 | Agility |
| `l-drill` | L-Drill (3-Cone) | 30yd (27.43m) | In-Frame | 1 | Agility |
| `40yd` | 40 Yard Dash | 40yd (36.58m) | Touch/Voice/Countdown | 2 | Combine |

### 4.2 Templates Screen
- Full list of all presets organized by category (Acceleration, Max Speed, Agility, Combine)
- Search bar for filtering
- Each row shows: icon, name, short distance, phone count, category badge
- Non-Pro users see a lock icon on multi-phone presets
- Tapping a locked preset shows the paywall
- Solo Mode (practice) is always free

### 4.3 Preset Session View
- When a preset is selected, opens `PresetSessionView` which handles the full flow
- Shows tips/recommendations specific to each preset
- For selectable-distance presets (Flying Sprint), shows distance picker

---

## 5. Start Types

Source: `Models/StartType.swift`

### 5.1 Flying Start
- Timer starts when athlete runs past the first phone (gate crossing triggers start)
- Requires 2+ phones (one at start, one at finish)
- Best for max velocity testing (30m+ runup before Phone 1)

### 5.2 Touch Release
- User holds finger on screen, lifts finger to start timer
- Timer starts at the moment of finger release
- Good for 40-yard dash and short sprints
- Uses `TouchStartOverlay` / `TouchStartReadyView`

### 5.3 Countdown
- Automated countdown: 3, 2, 1, BEEP!
- Timer starts on the beep
- Uses `CountdownStartOverlay`
- Sound is configurable (beep, gunshot, whistle)

### 5.4 Voice Command
- AI voice calls out: "On your marks", "Set", "GO!" (or gunshot sound)
- Uses ElevenLabs AI voices (premium) or system TTS (free)
- Configurable timing delays:
  - Pre-start delay: 3-5s (time to set phone down)
  - "On your marks" to "Set": 8-12s (configurable, athletes settle in position)
  - "Set" to "GO!": 1.5-2.3s (configurable, mimics international starters)
- Timer starts at the GO! moment
- Uses `VoiceStartOverlay`

### 5.5 In-Frame Start
- Uses front (selfie) camera
- Athlete stands in frame; screen shows "Ready"
- Timer starts when athlete leaves the frame
- Timer stops when athlete returns to frame
- Used for agility tests (Pro Agility 5-10-5, L-Drill)

---

## 6. Detection Modes

Source: `GateEngine.swift`, `PhotoFinishDetector.swift`, `CrossingDetector.swift`, `ExperimentalCrossingDetector.swift`

### 6.1 Photo Finish Mode (Default)
- **No calibration needed** - instant ready state
- Frame differencing (diff vs previous frame) to detect motion
- Downsampled to 160x284 work resolution
- IMU stability gate: phone must be stable for 1.0s before arming
- Blob detection via connected component labeling (CCL)
- Size filter: blob must be >= 33% of frame height (filters arms vs torso)
- Column density analysis: minimum 8 columns wide for body region
- Velocity filter: >= 60 px/s minimum movement
- Crossing check + linear interpolation + exposure compensation
- Hysteresis rearm: person must move well outside gate zone (25% of work width)
- Cooldown: 0.3s between triggers
- FPS options: 30, 60, or 120 fps (configurable)
- Gate position adjustable (default center)

### 6.2 Precision Mode
- Uses dual-loop architecture:
  - **Slow loop (30Hz)**: ML Kit (Vision framework on iOS) pose detection for torso tracking
  - **Fast loop (240fps)**: Background subtraction + crossing detection
- Requires calibration (30 frames of empty scene)
- Background model: per-row median + MAD-based adaptive thresholds
- 3-strip validation (left, center, right columns)
- Chest band filtering based on pose detection torso bounds
- EMA smoothing on torso bounds (alpha = 0.3)
- State machine: WAITING_FOR_CLEAR > ARMED > POSTROLL > COOLDOWN
- Thresholds: enter=0.22, confirm=0.35, clear=0.15
- Sub-frame quadratic interpolation for precise timing
- Rolling shutter correction
- FPS options: auto, 60, 120, 240 fps

### 6.3 Experimental Mode
- Zero-allocation blob tracking pipeline
- IMU-based auto-recovery (re-calibrates when phone is moved)
- State machine: moving > stabilizing > acquire > tracking > triggered > cooldown
- Designed for long sessions (4+ hours, low thermal)
- Gated tracker with ROI (region of interest)
- FPS options: 30, 60, or 120 fps
- Gate half-width for overlap detection

### 6.4 User Selection
- Detection mode selected in Settings (stored in `UserSettings.detectionMode`)
- Default is Photo Finish mode
- Each mode has its own FPS setting

---

## 7. Camera Features

Source: `CameraManager.swift`, `CameraPreviewView.swift`

### 7.1 Camera Configuration
- Uses AVFoundation (not CameraX equivalent)
- Supports 60, 120, and 240 fps capture
- Auto FPS selection based on device capability
- Front/back camera switching (front for in-frame start type)
- Exposure locking for consistent detection
- White balance locking
- Focus locking
- Thermal monitoring and automatic FPS throttling when device overheats

### 7.2 Gate Line
- Vertical gate line overlay on camera preview
- Draggable to reposition (0.0 to 1.0 normalized position, default 0.5)
- Visual indicator on the camera view
- 3-strip extraction at gate position (left, center, right)

### 7.3 Calibration (Precision Mode)
- Exposure lock indicator
- White balance lock indicator
- Focus lock indicator
- Blur risk warning if exposure > 4ms or ISO > 800
- Calibration quality levels: notReady, acceptable, pass

### 7.4 Bubble Level
- `BubbleLevelView` for helping align phone vertically
- `PerpendicularDialView` for alignment assistance

---

## 8. Solo Mode (Single Phone)

Source: `BasicModeView.swift`, `Views/SoloLapsView.swift`

### 8.1 Overview
- Single phone lap timer with unlimited crossings
- Each crossing is a "lap" with photo thumbnail
- Flying start: first crossing starts timer, subsequent crossings record laps
- Voice start: timer starts at GO!, crossings record laps

### 8.2 States
- `voiceStart`: Showing voice start overlay (if voice command start type)
- `calibrating`: Setting up detection
- `armed`: Ready to detect first crossing
- `running`: Timer active, recording laps
- `stopped`: Session complete
- `waitingInFrame`: Waiting for athlete to enter frame (in-frame start)
- `inFrameReady`: Athlete in frame, ready to start when they leave

### 8.3 Lap Display
- Scrolling list of laps (newest first)
- Each lap shows:
  - Lap number
  - Cumulative time from start
  - Individual lap time (split)
  - Thumbnail image of crossing moment
  - Best lap indicator (highlighted)
- Header shows: "Practice" label, total lap count, best lap time

### 8.4 Cooldown
- 1.0 second cooldown between crossings to prevent double-triggers
- CrossingDetector's own clear-to-arm logic is also active

---

## 9. Multi-Phone Timing

### 9.1 Session Creation Flow

Source: `Views/CreateSessionFlowView.swift`

Steps (varies by transport):
1. **Pairing** (Wi-Fi Aware only, iOS 26+): Device pairing step
2. **Connect**: Scan for and connect to other phones. Auto-assigns roles
3. **Track Setup**: Configure start type, distances between gates
4. **Athletes**: Select athletes for the session (optional)

Then launches `TimingSessionView` for the active timing session.

### 9.2 Join Flow

Source: `JoinSessionView.swift`

- Secondary phone flow: Pairing > Scanning > Connected > TimingSessionView
- Shows pairing step for Wi-Fi Aware, direct scanning for Multipeer/Bluetooth
- Green gradient background to visually distinguish from host
- Role and config assigned by host during handshake
- Supports guest join (no subscription required)

### 9.3 Roles

Source: `TimingMessage.swift`

| Role | Display | Icon | Description |
|------|---------|------|-------------|
| `startLine` | Start | `flag.fill` | Gate at the start line |
| `finishLine` | Finish | `flag.checkered` | Gate at the finish line |
| `lapGate` | Lap | `repeat` | Intermediate timing gate |
| `controlOnly` | Control | `slider.horizontal.3` | Host that only controls, no camera |

### 9.4 Gate Assignment

- `GateAssignment` struct: role, gateIndex, distanceFromStart
- Gate indices: 0 = start, 1...N-2 = intermediate, N-1 = finish
- Up to 4 phones supported (100m Sprint: start, 30m split, 60m split, finish)
- Host picks role first, joiners get remaining roles

### 9.5 Session Configuration

Exchanged during handshake:
- Distance (meters)
- Start type
- Number of gates
- Host's role
- Camera FPS mode
- Protocol version (currently v3)

### 9.6 Race Session State Machine

Source: `RaceSession.swift`

States:
1. `idle` - Not started
2. `connecting` - Pairing + handshaking
3. `syncing` - Clock sync in progress
4. `ready` - Synced, waiting for local readiness
5. `waitingForCrossing` - Locally ready, accepting crossings
6. `running(startTime)` - Timer running
7. `finished(result)` - Race complete

### 9.7 Race Result

- Split time in nanoseconds
- Uncertainty in milliseconds
- Start and finish timestamps
- Speed in m/s, km/h, mph
- Formatted split string (e.g., "7.45" or "1:23.45")

### 9.8 Session Runs

Each run within a session stores:
- Run number
- Timestamp
- Result (split, uncertainty)
- Start image (thumbnail from start gate)
- Finish image (thumbnail from finish gate)
- Lap images (for multi-gate sessions)
- Athlete info (ID, name, color)
- Per-run distance and start type (can differ from session defaults)
- Segment splits for multi-gate sessions
- Gate media (remote thumbnails from other gates)
- Local gate frames (for frame scrubbing)

### 9.9 Network Alerts

UI warnings during sessions:
- WiFi Off
- No Internet
- Connection Stale (multipeer heartbeat timeout)
- Connection Lost (both paths down)
- Poor Connection Quality

---

## 10. Multi-Device Communication

### 10.1 Transport Layer

Source: `TimingTransport.swift`, `MultipeerTransport.swift`, `BluetoothTransport.swift`, `SupabaseBroadcastTransport.swift`

Three transport options:
1. **Multipeer Connectivity** (default): Apple's peer-to-peer framework, works on all devices
2. **Bluetooth (BLE)**: Works without Wi-Fi
3. **Wi-Fi Aware** (iOS 26+): Extended range, new iOS feature

User selectable in Settings with "Auto" option that picks best available.

### 10.2 Connection Method Selection

In Settings > Connection:
- **Auto**: Best available (default)
- **Wi-Fi Aware**: iOS 26+, extended range
- **Multipeer**: Works on all devices
- **Bluetooth**: Works without Wi-Fi

### 10.3 Hybrid Architecture

- **Huddle Phase**: P2P (Multipeer/BLE/WiFi Aware) for clock synchronization (phones must be nearby)
- **Race Phase**: Supabase Realtime for start/finish events (works at any distance)

### 10.4 Pairing

Source: `RaceSession+PublicActions.swift`

- Host creates a 4-digit pairing code via Supabase (`pairing_requests` table)
- Joiner enters code to look up host's device ID
- Then targeted peer-to-peer connection is established
- Fallback: blind Multipeer search if Supabase is unavailable
- Also supports direct Wi-Fi Aware device pairing on iOS 26+

### 10.5 Handshake Protocol

Source: `RaceSession+Handshake.swift`

- Protocol version: 3
- Host sends session config (distance, start type, gate count, FPS)
- Joiner receives config and gets role assignment
- Host-controlled calibration and arming

### 10.6 Clock Synchronization

Source: `ClockSyncService.swift`

- NTP-style protocol with RTT filtering
- Full sync: 100 samples at 20Hz, keep lowest 20% RTT, median offset
- Mini sync: 30 samples at 10Hz (periodic refresh every 60s)
- Quality tiers: Excellent (<3ms), Good (3-5ms), Fair (5-10ms), Poor (10-15ms), Bad (>15ms)
- Minimum FAIR quality required for timing
- Drift tracking with linear regression for long sessions
- Convention: `t_remote = t_local + offset`

### 10.7 Thumbnail Sync

Source: `RaceSession+ThumbnailSync.swift`

- After each crossing, thumbnail images are synced between phones
- Allows each phone to display start AND finish images
- Images compressed before transmission

---

## 11. Timing & Results

### 11.1 Crossing Event

Source: `SprintTimerViewModel.swift`

When a crossing is detected:
- Event PTS (presentation timestamp) in nanoseconds
- Pre-frames (from ring buffer before event)
- Post-frames (captured after event)
- Confidence level: pose, occupancy, fallback, or motion
- Thumbnail image extraction

### 11.2 Sub-Frame Interpolation

- Quadratic interpolation when 3+ samples available
- Linear interpolation fallback with 2 samples
- Achieves sub-frame timing precision

### 11.3 Rolling Shutter Correction

Source: `RollingShutterCalculator.swift`, `RollingShutterService.swift`

- Device-specific readout time compensation
- Corrects for sequential row readout (top to bottom)
- Default ~5ms readout time, calibrated per device model

### 11.4 Split Time Calculation

For multi-phone sessions:
- Adjusted start time = startEvent.crossingTimeNanos - clockOffset
- Split = finishTime - adjustedStartTime
- Combined uncertainty = sqrt(start^2 + finish^2 + sync^2)

### 11.5 Segment Splits

For multi-gate sessions (3+ phones):
- Each segment records: fromGateIndex, toGateIndex, splitNanos, cumulativeSplitNanos
- Displayed as both segment times and cumulative times

---

## 12. Results Display & History

### 12.1 Live Session Results

Source: `Views/LiveSessionResultsView.swift`, `Views/ExpandableResultsView.swift`

During active sessions:
- Running timer display
- List of completed runs
- Each run shows: time, speed, start/finish thumbnails
- Gate thumbnail strip showing all gates
- Expandable results section

### 12.2 Session History

Source: `Views/History/SessionHistoryView.swift`

- All training sessions sorted by date (newest first)
- Filter options:
  - Date: All, This Week, This Month
  - Distance: All, 40m, 60m, 100m, 200m
  - Start Type: All, Flying, Touch Release, Voice Command, Countdown
  - Mode: All, 1-Phone, 2-Phone
- Sort options: Newest, Oldest, Fastest, Most Runs
- Search across date, location, notes, name, athlete names, distance
- Export functionality (generates shareable URL/file)

### 12.3 Run Detail View

Source: `Views/History/RunDetailView.swift`

- Large finish image
- Time display with unit (e.g., "7.45s")
- Speed in user-selected unit
- Start type and distance
- PR/SB badges if applicable
- Start and finish thumbnails side by side
- Segment splits for multi-gate runs
- Frame scrubber for crossing frame review

### 12.4 Frame Scrubber

Source: `Views/History/RunFramesScrubberView.swift`

- Horizontal scrubber through captured frames around the crossing moment
- Shows frame number, timestamp
- Gate line overlay on each frame
- Available for both single-phone and multi-phone sessions
- Can scrub through pre-trigger and post-trigger frames

### 12.5 Calibration Frame Scrubber

Source: `Views/CalibrationFrameScrubberView.swift`

- For TestFlight/debug builds
- Allows annotating ground truth crossing frames
- Used for detection algorithm calibration

---

## 13. Athletes

### 13.1 Athlete Model

Source: `Models/Athlete.swift`

Fields:
- Name, nickname (optional)
- Color (8 options: red, orange, yellow, green, blue, purple, pink, gray)
- Photo (optional, stored as external data)
- Birthdate, gender (optional)
- Personal bests: dictionary keyed by "{startType}_{distance}" (e.g., "flying_30m": 3.45)
- Season bests: same format as personal bests
- Created/updated timestamps

### 13.2 Athlete List

Source: `Views/AthleteListView.swift`

- List of all athletes sorted by name
- Add new athlete
- Edit existing athlete
- Delete athlete
- Each athlete shows: name, color badge, photo

### 13.3 Athlete Selection

Source: `Views/SessionSetupView.swift` (athlete step), `Views/Components/AthleteChipSelector.swift`

- During session setup, select which athletes are training
- Chip selector UI for quick multi-select
- Can create new athlete inline during setup

### 13.4 Personal Best Tracking

- PR key format: `{startType}_{distance}m` (e.g., "flying_30m", "touchRelease_40m")
- Checks and updates after each run
- PR update returns: isNewPR, previousBest, improvement delta
- Season bests tracked separately (resettable)
- PR toast notification shown when new PR achieved (`Views/Components/PRToastView.swift`)

### 13.5 Stats

Source: `Views/History/StatsDetailView.swift`

- Personal bests grouped by start type
- Season bests
- Performance trends over time

---

## 14. Settings

Source: `Views/Settings/SettingsView.swift`, `UserSettings.swift`

### 14.1 Preferences Section
- **Save Crossing Frames** (toggle): Save frame sequences for multi-phone sessions
- **Frame Scrubbing** (toggle): Enable frame scrubbing calibration in multi-phone sessions
- **Camera FPS** (picker): 30, 60, or 120 fps for Photo Finish mode
- **Speed Unit** (picker): m/s, km/h, or mph
- **Start Voice** (picker): Select ElevenLabs AI voice (Adam, Josh, Arnold, Rachel, Bella, Elli)
- **Preview Voice** (button): Play sample of selected voice

### 14.2 Voice Start Timing Section
- **Marks to Set delay**: Min-max slider, 3-20 seconds (default 8-12s)
- **Set to GO! delay**: Min-max slider, 0.5-5.0 seconds (default 1.5-2.3s)
- Note: "International starters use 1.5-2.3s for Set to GO!"

### 14.3 Crossing Feedback Section
- **Crossing Beep** (toggle): Play beep sound on detection
- **Screen Flash** (toggle): Flash screen on detection
- **Announce Times** (toggle): AI voice reads out split times after crossing

### 14.4 Notifications Section
- Navigation to `NotificationSettingsView`
- In-app toasts and system reminders configuration

### 14.5 Connection Section
- Connection method selection: Auto, Multipeer, Bluetooth
- Wi-Fi Aware shown on iOS 26+
- Info button explaining each method

### 14.6 Data Section
- Storage used (calculated)
- Saved results count
- Clear All Results (destructive action with confirmation)

### 14.7 Appearance Section
- 4 theme options: System, Light, Dark, AthleteMindset
- AthleteMindset is a special dark theme with athletic styling
- Visual cards for each option

### 14.8 About Section
- App version
- "How It Works" link
- Feature Requests link (external: mytrackspeed.com/feedback)
- Privacy Policy link (external: mytrackspeed.com/privacy)
- Terms of Service link (external: mytrackspeed.com/terms)

### 14.9 Account Section
- Sign out option (with confirmation)
- Delete account option (with "DELETE" text confirmation)

### 14.10 Debug Settings (Debug/TestFlight only)
- Frame Calibration toggle
- Debug UI toggle
- Show paywall button
- Reset onboarding button
- Spin wheel test

---

## 15. Profile Screen

Source: `ContentView.swift` (ProfileView section)

### 15.1 Profile Header
- Tappable avatar (change/remove photo via Photos picker)
- Name field (editable inline)
- Account status: Apple ID email or "Guest Account"
- Sprint count ("X sprints recorded")

### 15.2 Premium Section (non-subscribers)
- "Try Pro for Free" card
- "Unlock all features free for 7 days"
- Links to PaywallView

### 15.3 Invite Friends
- Referral system: "Get 1 free month for each friend who subscribes"
- Shows referral stats (count of subscribed referrals)
- Links to referral view

### 15.4 Athletes Section
- Link to AthleteListView
- Shows athlete count

### 15.5 Sprint Calculators
- **Wind Adjustment**: Adjust times for wind conditions (research-backed)
- **Distance Converter**: Convert between 60m, 100m, 200m equivalent times

### 15.6 Settings Gear
- Settings button in top-right toolbar

---

## 16. Audio & Haptics

### 16.1 Crossing Beep
Source: `ContentView.swift` (CrossingSoundPlayer)
- Custom beep sound (`beep-401570.mp3`)
- Plays on every crossing detection (if enabled in settings)
- Audio session configured for playback + mixWithOthers (works during camera)
- Preloaded at app start to avoid delay

### 16.2 Start Sounds
Source: `UserSettings.swift` (StartSoundType)
- **Beep**: Simple electronic beep
- **Gunshot**: Realistic starting pistol (`gunshot.mp3`)
- **Whistle**: Coach whistle

### 16.3 Voice Start
Source: `VoiceStartService.swift`, `ElevenLabsService.swift`
- Two voice providers:
  - **System Voice**: iOS AVSpeechSynthesizer (free, offline)
  - **ElevenLabs AI Voice**: Ultra-realistic, 6 voice options (Adam, Josh, Arnold, Rachel, Bella, Elli)
- Voice sequence: "On your marks" > pause > "Set" > pause > "GO!" / gunshot
- Configurable delays between commands
- Audio cached for instant playback

### 16.4 Time Announcement
- After crossing, AI voice announces the split time
- Uses same ElevenLabs/system voice as start commands
- Toggleable in settings (announceTimesEnabled)

### 16.5 Screen Flash
- Brief white/bright flash overlay on crossing detection
- Toggleable in settings (crossingFlashEnabled)

---

## 17. Photo Finish & Frame Features

### 17.1 Frame Ring Buffer
Source: `SprintTimerViewModel.swift` (FrameRingBuffer)

- Circular buffer storing recent frames as lightweight thumbnails
- Each frame stores: image, frameNumber, ptsNanos, occupancy, longestRun, isTracking, torsoTop, torsoBottom, joints, frameHeight
- Pre-buffer captures frames before trigger
- Post-buffer captures frames after trigger

### 17.2 Composite Buffer
Source: `CompositeBuffer.swift`

- Photo-finish style composite image
- Collects vertical slit columns over time
- Creates a time-lapse image of the gate line

### 17.3 Photo Finish Store
Source: `ContentView.swift` (PhotoFinishStore)

- Temporary in-memory storage during review workflow
- Stores: frames, triggerFrameIndex, compositeImage, crossingOffsetMs
- Cleared after save/dismiss

### 17.4 Thumbnail Features
- Start gate thumbnail
- Finish gate thumbnail
- Lap gate thumbnails (for multi-gate sessions)
- Option to center thumbnails on runner's chest position
- Gate line overlay on thumbnails

---

## 18. Session Management

### 18.1 Create Session
- Host creates session through `CreateSessionFlowView`
- Steps: Connect phones > Track Setup > Athletes > Start
- Pairing code generated via Supabase for discovery
- Also supports direct peer discovery without code

### 18.2 During Session
Source: `Views/TimingSessionView.swift`

- Full-screen camera view with gate line overlay
- Run counter and timer display
- Results strip at bottom showing completed runs
- Session settings accessible (change distance, start type mid-session)
- Athlete cycling between runs

### 18.3 End Session
- "End Session" button
- Saves all runs to SwiftData
- Updates athlete PRs and season bests
- Generates session thumbnail from best run
- Schedules inactivity reminder (7 days)
- Increments completed session count
- May trigger App Store review request

### 18.4 Session Persistence
Source: `SessionPersistenceService.swift`

- Sessions saved to SwiftData (`TrainingSession` model)
- Runs saved with foreign key to session
- Images saved to disk via `ImageStorageService`
- Thumbnail and full-res images stored separately

---

## 19. Cloud Sync (Supabase)

### 19.1 What Syncs

| Data | Table | When |
|------|-------|------|
| Race events (crossings) | `race_events` | Real-time during multi-phone sessions |
| Pairing requests | `pairing_requests` | During device discovery |
| Session metadata | `sessions` | After session ends |
| Run results | `runs` | After session ends |
| Crossing details | `crossings` | After session ends |

### 19.2 Supabase Realtime

Source: `SupabaseBroadcastTransport.swift`, `SupabaseManager.swift`

- Used during active timing sessions for cross-device event delivery
- WebSocket connection for low-latency event broadcast
- Subscribes to channel scoped by session ID
- Messages include: crossing events, start signals, thumbnail sync

### 19.3 Upload Queue

Source: `RunUploadQueue.swift`, `ThumbnailUploadQueue.swift`

- Runs uploaded to Supabase after session ends
- Thumbnail images uploaded to Supabase Storage
- Queue handles retries for failed uploads
- Pending deletion queue for cleaning up cloud data

---

## 20. Subscription / Paywall

### 20.1 Subscription Products

Source: `SubscriptionManager.swift`

- **Monthly**: $8.99/month
- **Yearly**: $49.99/year (54% savings)
- Managed via RevenueCat

### 20.2 Free vs Pro Features

- **Free**: Solo Mode (single phone, practice/lap timing)
- **Pro**: Multi-phone timing (all presets except Solo Mode), all features

### 20.3 Paywall View

Source: `Views/PaywallView.swift`

- Shows subscription options with trial info
- "Try Pro for Free" (7-day trial)
- Feature highlights specific to context (e.g., multi-phone sync)
- Influencer codes for extended trial (30 days)
- Spin wheel gamification for discount offers

### 20.4 Referral System

Source: `ReferralService.swift`

- Each user gets a unique referral code
- Share with friends
- When friend subscribes, referrer gets 1 free month
- Stats: total referrals, subscribed referrals, free months earned

### 20.5 Guest Join Mode

- Users can join a timing session without subscribing
- After session ends, returns to welcome screen (not main app)
- Accessible from onboarding welcome screen

---

## 21. Video Overlay / Export

Source: `VideoOverlay/VideoSyncView.swift`, `VideoOverlay/ThumbnailExtractor.swift`

### 21.1 Video Sync View
- Scrub video to mark start crossing point
- Timer overlay preview on video
- Start marker badge indicator

### 21.2 Video Overlay Settings
- Show speed in video overlay (toggle)
- Show run type in video overlay (toggle, e.g., "Flying 20m")
- Speed uses user's preferred unit

---

## 22. Localization

### 22.1 Language Support
- System language by default
- App language override in settings
- `Localizable.xcstrings` for all user-facing strings
- Key strings use `String(localized:)` for proper localization

### 22.2 Localized Content
- All start type names and descriptions
- All sport discipline names
- Athlete color names
- Gender options
- UI labels and buttons

---

## 23. Persistence (SwiftData Models)

### 23.1 Models

| Model | Purpose | Key Fields |
|-------|---------|------------|
| `TrainingSession` | Session metadata | id, date, name, location, distance, startType, numberOfPhones, numberOfGates, runs[], thumbnailPath |
| `Run` | Individual timed run | id, athleteId, runNumber, time, distance, startType, startImagePath, finishImagePath, splitsJSON, isPersonalBest |
| `Athlete` | Athlete profile | id, name, nickname, color, personalBests, seasonBests, photoData |
| `UserProfile` | App user profile | id, fullName, role, primaryEvent, personalRecord, flyingPR |
| `PersistedCrossing` | Legacy single crossing results | timestamp, frameCount, triggerImage |

### 23.2 Relationships
- TrainingSession has many Runs (cascade delete)
- Runs reference Athlete by ID (snapshot of name/color at time of run)

### 23.3 Database Recovery
- Corruption recovery: tries on-disk fresh database, falls back to in-memory
- Warning banner if using in-memory store

---

## 24. Miscellaneous Features

### 24.1 Device Identity
Source: `DeviceIdentity.swift`
- Unique device ID (UUID, persisted)
- Device name from manufacturer + model

### 24.2 Device Capability
Source: `DeviceCapability.swift`
- Detects device capabilities (max FPS, etc.)
- Recommends optimal settings per device model

### 24.3 Performance Monitor
Source: `PerformanceMonitor.swift`
- FPS tracking
- Frame drop detection
- Thermal state monitoring

### 24.4 Crash Reporting
Source: `CrashReportingService.swift`
- Crash report collection and submission

### 24.5 Analytics
Source: `Services/AnalyticsService.swift`
- Event tracking for user actions

### 24.6 Push Notifications
Source: `PushNotificationManager.swift`
- Push notification support
- Inactivity reminder (7 days after last session)
- Promotional offers
- Trial end reminder

### 24.7 Remote Config
Source: `RemoteConfigService.swift`
- Server-side feature flags and configuration

### 24.8 App Review
- Prompted after completing timing sessions
- Tracked via `hasBeenAskedForReview` and `completedSessionCount`

### 24.9 Deep Links
- Promo offer deep links
- Referral code deep links

### 24.10 IMU Service
Source: `IMUService.swift`
- Core Motion integration for phone stability detection
- Gyroscope threshold for shake detection
- Used by Photo Finish and Experimental detection modes

### 24.11 Wind Adjustment Calculator
Source: `Models/WindAdjustmentCalculator.swift`
- Research-backed wind correction formula
- Adjusts sprint times for headwind/tailwind conditions

### 24.12 Distance Converter
Source: `Models/DistanceConverter.swift`
- Converts equivalent sprint times between distances (60m, 100m, 200m)

### 24.13 Setup Assistant
Source: `SetupAssistantView.swift`, `QuickCalibrateView.swift`
- Guided phone positioning help
- Quick calibration workflow

---

## 25. Appearance / Theming

Source: `AppTheme.swift`, `UserSettings.swift`

### 25.1 Themes
- **System**: Follows device setting
- **Light**: Always light
- **Dark**: Always dark
- **AthleteMindset**: Custom dark theme with athletic styling

### 25.2 Design System
- `AppColors`: Semantic color tokens (primary, secondary, accent, success, warning, error, etc.)
- `AppTypography`: Type scale
- `AppSpacing`: Spacing tokens (xxxs through xxxl)
- `AppRadius`: Corner radius tokens
- `AppShadows`: Shadow presets
- `AppSizes`: Size constants
- Glass morphism effects (frosted glass cards, tab bar)
- Gradient backgrounds

---

## 26. Authentication

### 26.1 Methods
- Apple Sign-In (primary)
- Google Sign-In
- Skip login (guest mode)

### 26.2 State
- Authenticated via Supabase
- User ID stored in `UserSettings.userId`
- `hasSkippedLogin` flag for guest users
- Session token management via `AuthService`

### 26.3 Account Management
- Sign out with confirmation
- Delete account with "DELETE" text confirmation
- Profile sync to cloud (retry on failure)
