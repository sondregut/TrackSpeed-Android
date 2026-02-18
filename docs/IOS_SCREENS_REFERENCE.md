# iOS Speed Swift - Complete Screens & Views Reference

**Generated:** February 2026
**Source:** `/Users/sondre/Documents/App/speed-swift/SprintTimer/SprintTimer/`

This document catalogs every screen and view in the iOS TrackSpeed (Speed Swift) app for reference when building the Android port.

---

## Table of Contents

1. [App Architecture & Navigation](#1-app-architecture--navigation)
2. [Main Tab Views](#2-main-tab-views)
3. [Home / Dashboard](#3-home--dashboard)
4. [Session Creation Flow](#4-session-creation-flow)
5. [Join Session Flow](#5-join-session-flow)
6. [Timing Session (Live)](#6-timing-session-live)
7. [Basic Mode (Solo Timing)](#7-basic-mode-solo-timing)
8. [Start Overlays](#8-start-overlays)
9. [Session Results (Live)](#9-session-results-live)
10. [History / Sessions](#10-history--sessions)
11. [Session Detail](#11-session-detail)
12. [Run Detail](#12-run-detail)
13. [Stats & Analytics](#13-stats--analytics)
14. [Templates](#14-templates)
15. [Profile](#15-profile)
16. [Settings](#16-settings)
17. [Athletes](#17-athletes)
18. [Calculators](#18-calculators)
19. [Paywall](#19-paywall)
20. [Onboarding](#20-onboarding)
21. [Camera & Detection Views](#21-camera--detection-views)
22. [Debug Views](#22-debug-views)
23. [Component Library](#23-component-library)
24. [Design System (AppTheme)](#24-design-system-apptheme)
25. [Video Overlay Views](#25-video-overlay-views)
26. [WiFi Aware Views](#26-wifi-aware-views)

---

## 1. App Architecture & Navigation

### Entry Point: `SprintTimerApp.swift`

- **Root:** `RootView` decides between `OnboardingContainerView` and `ContentView`
- **Condition:** Shows main content only if `settings.hasCompletedOnboarding` is true
- **Data:** SwiftData `ModelContainer` with schema: `PersistedCrossing`, `TrainingSession`, `Run`, `Athlete`, `UserProfile`
- **Deep Links:** `trackspeed://` scheme handles `promo`, `invite`, `settings`, `open`
- **Status Bar:** Hidden globally (`.statusBarHidden()`)
- **Theme:** Supports dark/light/AthleteMindset themes via `UserSettings.shared.appearanceMode`

### ContentView -> MainTabView

`ContentView` is a thin wrapper that just renders `MainTabView()`.

---

## 2. Main Tab Views

### `MainTabView.swift`

**File:** `Views/MainTabView.swift`

**4-tab layout:**

| Tab | Index | Label | Icon | View |
|-----|-------|-------|------|------|
| Home | 0 | "Home" | `house.fill` | `DashboardHomeView` (wrapped in `NavigationStack`) |
| Templates | 1 | "Templates" | `list.bullet.rectangle.portrait` | `TemplatesView` (wrapped in `NavigationStack`) |
| History | 2 | "History" | `clock.arrow.circlepath` | `SessionHistoryView` (wrapped in `NavigationStack`) |
| Profile | 3 | "Profile" | `person.circle.fill` | `ProfileView` (wrapped in `NavigationStack`) |

**Modifiers:**
- `.fullScreenCover` for `CreateSessionFlowView` and `PaywallView`
- Listens for `NotificationCenter` `.didReceivePromoOffer` to show paywall
- `.glassTabBar()` modifier applied (iOS 26 Liquid Glass style)

---

## 3. Home / Dashboard

### `DashboardHomeView.swift`

**File:** `Views/Home/DashboardHomeView.swift`

**Layout:** List-based with sections

**Sections (top to bottom):**

1. **Billing Issue Banner** (conditional) - Shows if `subscriptionManager.isInBillingGracePeriod`

2. **Header Section**
   - App icon image ("HomeIcon", 80pt height)
   - "TrackSpeed" title (AppTypography.title2)
   - "Precision sprint timing" subtitle

3. **Quick Start Section**
   - "QUICK START" section header (caption, tracked 0.5)
   - **Test Preset Grid** - 2-column `LazyVGrid` of `PresetCardButton` cards
     - Shows `settings.adaptiveFeaturedPresets` (adapts to user's most-used)
     - Practice is free, others require Pro
     - Cards: icon in circular gradient + preset name
   - **Custom Session Button** - Full-width card with slider icon
     - Opens `CreateSessionFlowView`
     - Gated behind Pro subscription
   - **Join Session Button** - Full-width card
     - Opens `JoinSessionView` (or WiFi Aware viewer for iOS 26+)
     - FREE for everyone (host pays)

4. **Recent Sessions Section**
   - Header with "See All" button (switches to History tab)
   - Shows up to 5 most recent `TrainingSession` items as `RecentSessionRow` cards
   - Empty state: `ContentUnavailableView` with "No Sessions Yet"
   - Each row navigates to `SessionDetailView`

5. **Debug Tools Section** (DEBUG only) - Collapsible dropdown

**Data:**
- `@Query` for `TrainingSession` sorted by date descending
- `UserSettings.shared` for user name, preferences
- `SubscriptionManager.shared` for Pro status

**Visual Design:**
- Gradient background (`.gradientBackground()`)
- Gunmetal card style in dark mode (gradient fill + 1px border)
- Liquid Glass on iOS 26+
- Light mode: system grouped backgrounds

### `RecentSessionRow`

**Layout:** HStack with:
- 56x56 thumbnail (rounded rectangle, gradient placeholder if none)
- Date (subheadline semibold), distance pill, runs pill, best time (green)
- Background: Liquid Glass (iOS 26+) or gunmetal gradient (dark) or system background (light)

### `PresetCardButton`

**Layout:** VStack with:
- 44x44 circular icon with gradient background
- Preset name (subheadline)
- Pro gate overlay if required
- Surface background with border and shadow

---

## 4. Session Creation Flow

### `CreateSessionFlowView.swift`

**File:** `Views/CreateSessionFlowView.swift`

**Presented as:** Full-screen cover from DashboardHomeView

**Multi-step wizard flow:**

| Step | Name | Description |
|------|------|-------------|
| 0 | Pairing | WiFi Aware device pairing (iOS 26+ only, skipped for Multipeer/BT) |
| 1 | Connect | Connect devices, assign host role (Finish Line or Control Only) |
| 2 | Track Setup | Configure start type + gate distances |
| 3 | Athletes | Select athletes for session (optional, can skip) |

**Step Indicator:** Circle numbers with connecting lines, filled when completed

**Connect Step Layout:**
- Header icon (transport-specific) + "Connect Devices" title
- Transport info banner (varies: WiFi Aware/Multipeer/Bluetooth)
- Host role selector: segmented capsule buttons (Finish Line / Control Only)
- Device cards: `GateDeviceCard` for each connected device
  - Role icon (colored, 56x56 rounded rect)
  - Gate name (e.g., "START GATE", "FINISH GATE")
  - Device name + host star badge
  - Status indicator (checkmark or spinner)
- Scanning indicator with spinner
- Connection count

**Athletes Step Layout:**
- "Who's running today?" title
- Header with count + "Add" button
- Skip option (checkbox card)
- Scrollable athlete list with checkboxes, avatars, PB display
- Empty state with "Add First Athlete" button

**Navigation Buttons:** Back (gray) + Next/Start (primary accent, disabled if can't proceed)

**State Management:**
- `TransportWrapper` for connection management
- `BluetoothManager` and `WiFiManager` for state monitoring
- `RaceSession` created on "Start Session" and passed to `TimingSessionView`
- Extensive caching of all `@State` values before showing `TimingSessionView` to prevent SwiftUI re-creation

**Key Data:**
- `connectedDevices: [ConnectedDevice]` - name, role, gateIndex, deviceId
- `gateDistances: [Int: Double]` - gate index to cumulative distance
- `selectedAthleteIds: Set<UUID>`
- `selectedStartType: StartType` (default: `.flying`)
- `selectedDistance: Double` (default: 30)

---

## 5. Join Session Flow

### `JoinSessionView.swift`

**File:** `JoinSessionView.swift`

**Presented as:** Full-screen cover

**Flow:** Pairing (WiFi Aware) -> Scanning -> Connected -> TimingSessionView

**States:**
- `disconnected` - Shows "Join" button and instructions
- `searching` - Shows searching animation with rotating tips
- `connecting` - Shows connecting indicator
- `connected(peerName)` - Shows "Connected to [host]", waiting for host to start
- `error(message)` - Shows error with retry

**Visual distinction:** Uses `.joinerGradientBackground()` (green-tinted) to visually distinguish from host

**Key behavior:**
- Auto-discovers hosts via transport
- Receives `roleAssigned`, `gateAssigned`, `sessionConfig` from host
- Creates `RaceSession` here (not in TimingSessionView) to survive re-renders
- Transitions to `TimingSessionView` when host sends `startTiming`

---

## 6. Timing Session (Live)

### `TimingSessionView.swift`

**File:** `Views/TimingSessionView.swift`

**Presented as:** Full-screen cover from CreateSessionFlowView or JoinSessionView

**This is the core screen during an active timing session.**

**Parameters:** isHost, role, distance, startType, numberOfGates, transport, selectedAthleteIds, preAssignedRoles, existingRaceSession

**Session States:**
- `setupPreview` - Camera visible, positioning phone
- `calibrating` - Building background model
- `calibrated` - Background ready
- `waitingForCrossing` - Armed for detection
- `running` - Timer active
- `finished` - Run completed

**Tab Layout (LaserSpeed style):**
- `record` tab - Live camera + timing
- `results` tab - Run results list

**Record Tab Layout:**
- Camera preview (small or fullscreen)
- Gate line overlay
- Calibration status indicator
- Timer display (large monospaced digits)
- Detection state indicators
- Athlete chip selector (if athletes selected)
- Start type menu (dropdown to change mid-session)
- Run counter
- Gate thumbnails strip (shows start/finish/lap images)

**Results Tab:**
- `ExpandableResultsView` - table of runs sorted by time
- Tap to expand and see gate thumbnails
- Athlete filter chips
- Best time highlighting (green)

**Overlays (conditional):**
- `TouchStartOverlay` - for touch release start
- `VoiceStartOverlay` - for voice command start
- `CountdownStartOverlay` - for countdown start
- `ConnectionDebugOverlay` - DEBUG triple-tap
- `DetectionDebugOverlay` - DEBUG
- `PhotoFinishDebugOverlay` - DEBUG
- `PRToastView` - celebration for personal records

**Key State:**
- `@State var raceSession: RaceSession` - manages all timing logic
- `@State var viewModel = SprintTimerViewModel()` - camera/detection
- `runs: [TimingRun]` - completed runs
- `elapsedTime: TimeInterval` - current timer
- Various UI flags for overlays, sheets, alerts

---

## 7. Basic Mode (Solo Timing)

### `BasicModeView.swift`

**File:** `BasicModeView.swift`

**Presented as:** Full-screen cover

**Single-phone stopwatch-style timing with camera detection.**

**States:**
- `voiceStart` - Showing voice start overlay
- `calibrating` - Building background model
- `armed` - Ready to detect
- `running` - Timer active, counting crossings
- `stopped` - Session ended
- `waitingInFrame` - Waiting for athlete to enter frame (inFrame start)
- `inFrameReady` - Athlete detected, ready to start on exit

**Layout:**
- Camera preview (fullscreen background)
- Gate line overlay
- State-dependent overlay (calibrating spinner, armed indicator, timer)
- Crossing list (scrolling, newest first)
- Each crossing shows: lap number, cumulative time, lap split, thumbnail

**Session Management:**
- `BasicModeSession` (Observable) - manages crossings, timing, cooldown
- `CrossingRecord` - per-crossing data with ptsNanos and thumbnailData
- 1-second cooldown between crossings

---

## 8. Start Overlays

### `TouchStartOverlay.swift`

**File:** `Views/TouchStartOverlay.swift`

**Full-screen immersive overlay for Touch Release start.**

**States:**
- `waiting` - Dark screen, "Touch anywhere" text, pulsing circle
- `holding` - Amber/orange background, edge glow effect, hold counter
- `released` - Green flash, timer started

**Interaction:** Touch down -> hold -> lift finger = start (passes UInt64 nanosecond timestamp)
**Cancel:** Swipe up gesture

### `VoiceStartOverlay.swift`

**File:** `Views/VoiceStartOverlay.swift`

**Full-screen overlay for AI voice command start.**

**Phases (synced with `VoiceStartService`):**
- "On your marks" - Blue/dark background
- "Set" - Amber transition
- "GO!" - Green flash, timer starts

**Features:**
- Settings button for voice selection
- Back/cancel button
- Visual cues synchronized with ElevenLabs voice audio

### `CountdownStartOverlay.swift`

**File:** `Views/CountdownStartOverlay.swift`

**3-2-1-BEEP countdown start with visual countdown and sound.**

### `TouchStartReadyView.swift`

**File:** `Views/TouchStartReadyView.swift`

**Info/ready screen shown before TouchStartOverlay** - explains the touch release method.

---

## 9. Session Results (Live)

### `ExpandableResultsView.swift`

**File:** `Views/ExpandableResultsView.swift`

**Compact table of runs during a live session.**

**Layout:**
- Athlete chip filter bar (if multiple athletes)
- Run rows sorted by time (best time highlighted green)
- Tap row to expand: reveals gate thumbnails, splits, speed
- Each row: run number, time, speed, athlete color dot

### `LiveSessionResultsView.swift`

**File:** `Views/LiveSessionResultsView.swift`

**Alternative results view with more stats during timing.**
- Sort options: Recent, Fastest, Slowest
- Athlete filter
- Session stats header (total runs, best time, average)

### `SessionRunsStrip.swift` / `SessionRunsList.swift`

**File:** `Views/SessionRunsStrip.swift`, `Views/SessionRunsList.swift`

**Compact horizontal/vertical strips of run results.**

### `SoloLapsView.swift`

**File:** `Views/SoloLapsView.swift`

**Lap list for solo/practice mode.**

**Layout:**
- Header with lap count and best lap indicator
- Scrollable list of `LapCard` entries (newest first)
- Each card: lap number, cumulative time, lap split time, thumbnail
- Best lap highlighted
- Thumbnail tap opens fullscreen or calibration scrubber

---

## 10. History / Sessions

### `SessionHistoryView.swift`

**File:** `Views/History/SessionHistoryView.swift`

**Main history screen (Tab 2).**

**Layout:**
- **Stats header:** 4 stat boxes (Sessions, Runs, Best Time, This Week)
- **Filter bar:** Segmented picker (All/This Week/This Month) + sort menu
- **Active filters indicator** (with clear button)
- **Grouped sessions:** LazyVStack grouped by Today/Yesterday/This Week/Earlier
- **Session cards:** `SessionCard` with thumbnail, date, distance pill, runs pill, best time

**Features:**
- Searchable (sessions, athletes, locations)
- Sort: Newest, Oldest, Fastest, Most Runs
- Advanced filters sheet: Distance (40m/60m/100m/200m), Start Type, Mode (1-Phone/2-Phone)
- Context menu: Delete, Export as CSV
- Export all as CSV, Share summary
- Pull-to-refresh
- "View Detailed Stats" link to `StatsDetailView`

**Session Card Design (Gunmetal):**
- 72x72 thumbnail (rounded, gradient placeholder)
- Date (subheadline semibold)
- Pills: distance + runs count + start type icon
- Best time (title3 bold green)
- Chevron
- Background: gunmetal gradient (dark) with top highlight radial gradient
- 1px border, shadow

### `HistoryFilterSheet`

**Presented as:** Sheet (medium detent)
- Distance filter grid (3 columns)
- Start type filter grid (2 columns)
- Mode filter grid (3 columns)
- Clear all button
- `FilterChip` component: text in rounded rect, filled accent when selected

---

## 11. Session Detail

### `SessionDetailView.swift`

**File:** `Views/History/SessionDetailView.swift`

**Navigation:** Pushed from SessionHistoryView or DashboardHomeView

**Layout:**
- **Session info bar:** Date, distance, start type, phone count
- **Athlete filter bar** (if multiple athletes in session)
- **Runs list:** Sorted by time (fastest first)
  - Compact rows with expand-on-tap
  - Expanded: gate thumbnails, splits, speed, share button
  - Best time highlighted green
  - Context menu: Edit Distance, Video Overlay, Delete

**Features:**
- Share results (shareable card image)
- Video overlay (import/export with timing overlay)
- Edit run distance
- Delete individual runs

---

## 12. Run Detail

### `RunDetailView.swift`

**File:** `Views/History/RunDetailView.swift`

**Navigation:** Pushed from SessionDetailView

**Layout:**
- Athlete header (if assigned)
- Main stats card (time, speed, distance)
- Splits card (if available - start, lap, finish times)
- Gate images gallery (horizontal scroll of start/lap/finish images)
- Frame scrubber (if local gate frames saved)

### `RunFramesScrubberView.swift`

**File:** `Views/History/RunFramesScrubberView.swift`

**Frame-by-frame scrubber for reviewing crossing detection.**

---

## 13. Stats & Analytics

### `StatsDetailView.swift`

**File:** `Views/History/StatsDetailView.swift`

**Navigation:** Pushed from SessionHistoryView "View Detailed Stats" link

**Layout:**
- Test type selector (segmented, auto-detected from user's sessions)
- Minimized stats card (best time, average time)
- Session progress chart (Swift Charts) - best time per session over time
- Uses `TestTypeClassifier` to categorize sessions

---

## 14. Templates

### `TemplatesView.swift`

**File:** `Views/TemplatesView.swift`

**Tab 1 content.**

**Layout:** List grouped by category

**Categories:** Acceleration, Max Speed, Agility, Combine

**Each template (`TemplateRow`):**
- Circular icon with category color gradient
- Template name (headline)
- Description (caption)
- Pro badge if required
- Chevron

**Features:**
- Searchable by name, short name, category
- Launches `PresetSessionView` on tap
- Pro gate overlay for paid templates

### `PresetSessionView.swift`

**File:** `Views/PresetSessionView.swift`

**Full-screen cover for preset-based session setup.**

**Steps (varies by preset):**
1. Info - Preset info, tips, distance selector
2. Start Type - If multiple options available
3. Athletes - Select athletes
4. Pairing - WiFi Aware (if needed)
5. Connect - Connect devices

**Key behavior:**
- Single phone presets skip pairing/connect steps
- Auto-selects start type and distance from preset defaults
- Launches `BasicModeView` for single-phone or `TimingSessionView` for multi-phone

---

## 15. Profile

### `ProfileView` (in ContentView.swift)

**Tab 3 content.**

**Layout:** List with insetGrouped style

**Sections:**

1. **Profile Header**
   - Tappable avatar (70x70, circle, photo or initial)
   - Camera badge overlay
   - Editable name (`TextField`)
   - Account status (Apple ID email or "Guest Account")
   - Sprint count

2. **Premium Section** (non-subscribers only)
   - Crown icon + "Try Pro for Free" card
   - Opens PaywallView

3. **Invite Friends Section**
   - Gift icon + "Invite Friends" card
   - Referral stats badge
   - Opens `SettingsReferralView`

4. **Athletes Section**
   - NavigationLink to `AthleteListView`
   - Athlete count badge

5. **Sprint Calculators Section**
   - Wind Adjustment (NavigationLink)
   - Distance Converter (NavigationLink)

**Toolbar:** Settings gear icon (NavigationLink to SettingsView)

---

## 16. Settings

### `SettingsView.swift`

**File:** `Views/Settings/SettingsView.swift`

**Navigation:** Pushed from ProfileView toolbar

**Sections:**

1. **Preferences**
   - Frame Calibration toggle (DEBUG)
   - Save Crossing Frames toggle
   - Frame Scrubbing toggle
   - Camera FPS picker (PhotoFinishFPSPreference)
   - Speed Unit picker (m/s, km/h, mph)
   - Start Voice picker (ElevenLabs voices)
   - Preview Voice button

2. **Voice Start Timing**
   - Configurable delays for voice start phases

3. **Connection**
   - Transport method picker (Auto/Multipeer/WiFi Aware/Bluetooth)
   - Connection info sheet

4. **Appearance**
   - Appearance mode (System/Light/Dark/AthleteMindset)
   - Language picker

5. **Notifications**
   - Link to NotificationSettingsView

6. **Account**
   - Sign In with Apple (if not authenticated)
   - Sign Out button (if authenticated)
   - Delete Account (destructive)

7. **Data**
   - Clear all data button
   - Storage usage display

8. **About**
   - App version
   - Privacy Policy link
   - Terms of Service link

9. **Debug** (DEBUG only)
   - Various debug toggles and tools

### `NotificationSettingsView.swift`

**File:** `Views/Settings/NotificationSettingsView.swift`

**Notification preference toggles.**

### `SettingsReferralView.swift`

**File:** `Views/Settings/SettingsReferralView.swift`

**Referral program management - code, share link, stats.**

---

## 17. Athletes

### `AthleteListView.swift`

**File:** `Views/AthleteListView.swift`

**Navigation:** Pushed from ProfileView

**Layout:**
- Empty state: icon + "No Athletes Yet" + "Add First Athlete" button
- Athlete list (searchable)
- Each row: avatar, name, nickname, personal bests
- Swipe to delete
- Toolbar: + button to add

**Sheets:**
- Add athlete form
- Edit athlete form

### `AthleteFormView` (part of AthleteListView)

**Sheet for adding/editing athletes:**
- Name field
- Nickname field (optional)
- Color picker (athlete color)
- Photo picker
- Birthdate (optional)
- Gender (optional)

### `AthleteChipSelector.swift`

**File:** `Views/Components/AthleteChipSelector.swift`

**Horizontal scroll of athlete chips for selecting active athlete during timing.**
- Color-coded circles with initials
- Tap to select, selected chip is highlighted
- "+None" option

---

## 18. Calculators

### `WindAdjustmentView.swift`

**File:** `Views/Calculators/WindAdjustmentView.swift`

**Navigation:** Pushed from ProfileView

**Layout:** List with sections
- Event picker (100m, 200m)
- Time input (decimal pad)
- Wind speed input (signed decimal)
- Results card: adjusted time, wind effect, legal status

### `DistanceConverterView.swift`

**File:** `Views/Calculators/DistanceConverterView.swift`

**Navigation:** Pushed from ProfileView

**Modes (segmented picker):**
- Distance - Convert between 60m, 100m, 200m
- Flying Sprint - Flying sprint conversions
- Predictor - 100m prediction from splits
- Lane Draw - Lane stagger calculator

---

## 19. Paywall

### `PaywallView.swift`

**File:** `Views/PaywallView.swift`

**Presented as:** Full-screen cover

**Layout:**
- Hero image with gradient fade to background
- Close button (X, top-right, delayed appearance)
- "TrackSpeed Pro" title
- Feature list (varies based on `highlightedFeature`)
- Subscription package selector
- Subscribe CTA button
- Restore purchases link
- "Have a code?" promo code entry
- Legal text (terms, privacy)

**Features:**
- RevenueCat integration
- Multiple packages (weekly, monthly, annual)
- Promotional offer support
- Spin wheel gamification (shown once if paywall skipped)

---

## 20. Onboarding

### `OnboardingContainerView.swift`

**File:** `Views/Onboarding/OnboardingContainerView.swift`

**19-step onboarding flow:**

| Step | View | Purpose |
|------|------|---------|
| 0 | Welcome | App intro, Join/Skip/Sign In options |
| 1 | Value Proposition | "Turn your phone into a professional sprint timer" |
| 2 | How It Works | 3-step overview |
| 3 | Athlete Details | Sport discipline, role (athlete/coach) |
| 4 | Flying Time | Current PR entry |
| 5 | Goal Time | Target time entry |
| 6 | Goal Motivation | "Great goal!" + progress visualization |
| 7 | Start Types | 5 ways to start a race |
| 8 | Multi Device | Advanced multi-phone feature |
| 9 | Attribution | "Where did you hear about us?" + promo code |
| 10 | Rating | Social proof / testimonials |
| 11 | Competitor Comparison | Price anchoring vs competitors |
| 12 | Auth | Sign in with Apple |
| 13 | Trial Intro | "Try TrackSpeed for free" |
| 14 | Trial Reminder | "Reminder 2 days before trial ends" |
| 15 | Notification | Push notification permission |
| 16 | Paywall | Subscription |
| 17 | Spin Wheel | Gamified discount (shown once if paywall skipped) |
| 18 | Completion | "You're all set!" |

**Navigation:**
- Back button + progress bar (shown on all except first/last)
- Forward/backward slide transitions
- Swipe gesture support on some screens
- Can skip to guest mode

**Individual Onboarding Views:**
- `OnboardingWelcomeView` (getStarted)
- `OnboardingValuePropositionView`
- `OnboardingHowItWorksView`
- `OnboardingAthleteDetailsView`
- `OnboardingFlyingTimeView`
- `OnboardingGoalTimeView`
- `OnboardingGoalMotivationView`
- `OnboardingStartTypesView`
- `OnboardingMultiDeviceView`
- `OnboardingAttributionView`
- `OnboardingRatingView`
- `OnboardingCompetitorComparisonView`
- `OnboardingAuthView`
- `OnboardingTrialIntroView`
- `OnboardingTrialReminderView`
- `OnboardingNotificationView`
- `OnboardingPaywallView`
- `OnboardingSpinWheelView` / `SpinWheelCanvasView`
- `OnboardingCameraPermissionView`
- `OnboardingProfileSetupView`
- `OnboardingPromoCodeView`

---

## 21. Camera & Detection Views

### `CameraPreviewView.swift`

**File:** `CameraPreviewView.swift`

**UIViewRepresentable wrapping AVCaptureVideoPreviewLayer.**
- Displays live camera feed
- Supports gate line overlay

### `QuickCalibrateView.swift`

**File:** `QuickCalibrateView.swift`

**Calibration UI for building background model.**

### `SetupAssistantView.swift`

**File:** `SetupAssistantView.swift`

**Guided setup for phone positioning and gate placement.**

### `BubbleLevelView.swift`

**File:** `BubbleLevelView.swift`

**Visual bubble level for ensuring phone is level during setup.**

### `PerpendicularDialView.swift`

**File:** `PerpendicularDialView.swift`

**Dial showing phone angle relative to perpendicular (for gate alignment).**

### `CalibrationFrameScrubberView.swift`

**File:** `Views/CalibrationFrameScrubberView.swift`

**Frame-by-frame scrubber for reviewing calibration data.**

---

## 22. Debug Views

### `DetectionDebugOverlay.swift`

**File:** `Views/DetectionDebugOverlay.swift`

**Overlay showing real-time detection values:**
- Occupancy ratios (left, center, right strips)
- Run lengths
- Torso bounds
- Detection state
- Validation results
- Rejection reasons

### `PhotoFinishDebugOverlay.swift`

**File:** `Views/PhotoFinishDebugOverlay.swift`

**Photo Finish specific debug overlay.**

### `ConnectionDebugOverlay.swift`

**File:** `Views/ConnectionDebugOverlay.swift`

**Shows transport connection state, peer info, message counts.**

### `MultipeerDebugView.swift`

**File:** `Views/MultipeerDebugView.swift`

**Detailed Multipeer Connectivity debug info.**

### `ThermalDebugView.swift`

**File:** `Views/ThermalDebugView.swift`

**Device thermal state monitoring.**

### `WiFiAwareTestView.swift`

**File:** `Views/WiFiAwareTestView.swift`

**WiFi Aware connection testing.**

---

## 23. Component Library

### Cards & Containers

| Component | File | Description |
|-----------|------|-------------|
| `GateDeviceCard` | CreateSessionFlowView.swift | Device card with role icon, name, status |
| `GateThumbnailCell` | Views/GateThumbnailCell.swift | Single gate crossing thumbnail |
| `GateThumbnailStrip` | Views/GateThumbnailStrip.swift | Horizontal strip of gate thumbnails |
| `DeviceRoleCard` | Views/Components/DeviceRoleCard.swift | Card showing device role assignment |
| `TrackVisualizationCard` | Views/Components/TrackVisualizationCard.swift | Visual track layout diagram |
| `TrackSetupStepView` | Views/Components/TrackSetupStepView.swift | Track configuration step |
| `ShareableResultCard` | Views/Components/ShareableResultCard.swift | Shareable result image card |

### Selectors & Inputs

| Component | File | Description |
|-----------|------|-------------|
| `AthleteChipSelector` | Views/Components/AthleteChipSelector.swift | Horizontal athlete selector chips |
| `StartTypeSelectorRow` | Views/Components/StartTypeSelectorRow.swift | Start type selector |
| `ConnectAndAssignView` | Views/Components/ConnectAndAssignView.swift | Device connection + role assignment |
| `EditRunDistanceSheet` | Views/Components/EditRunDistanceSheet.swift | Edit run distance bottom sheet |

### Overlays & Feedback

| Component | File | Description |
|-----------|------|-------------|
| `PRToastView` | Views/Components/PRToastView.swift | Personal record celebration toast |
| `ToastView` | Views/ToastView.swift | Generic toast notification |
| `BillingIssueBanner` | Views/Components/BillingIssueBanner.swift | Subscription billing warning |
| `ConnectionHealthIndicator` | Views/Components/ConnectionHealthIndicator.swift | Connection quality indicator |
| `PermissionDeniedView` | Views/PermissionDeniedView.swift | Camera/BT permission denied |

### Sharing

| Component | File | Description |
|-----------|------|-------------|
| `ShareSheet` | Views/Components/ShareSheet.swift | UIActivityViewController wrapper |
| `ShareSheetView` | Views/Components/ShareSheetView.swift | Extended share sheet with content |

---

## 24. Design System (AppTheme)

### `AppTheme.swift`

**File:** `AppTheme.swift`

### Color Palette

**Three themes:**
1. **Light mode** - Cal AI-inspired warm design
2. **Dark mode** - LaserSpeed gunmetal design
3. **AthleteMindset** - Chase Sapphire Reserve premium dark

**Key Semantic Colors:**

| Token | Light | Dark | Usage |
|-------|-------|------|-------|
| `background` | #F5F5F7 (cool gray) | #191919 (deep charcoal) | Page background |
| `surface` | #FFFFFF (white) | #2B2E32 (blue-gray) | Card background |
| `text` / `primary` | #1D1312 (warm black) | #FDFDFD (off-white) | Primary text |
| `textSecondary` | #8E8C8B | #9B9A97 | Secondary text |
| `textMuted` | #ACABAA | #787774 | Muted/disabled text |
| `border` | #E8E8EA | #3D3D3D | Borders |
| `primaryAccent` | #1D1A23 (charcoal) | #5C8DB8 (blue) | CTAs, active states |
| `success` | #34A47A | #22C55E | Success, best times |
| `error` | #D96D6D | #FC0726 | Errors |
| `warning` | #EC9732 | #FEAA21 | Warnings, host badge |
| `info` | #68A1D6 | #68A1D6 | Info |

**Gunmetal Card Gradient (Dark Mode):**
- Top: #2C3033
- Bottom: #272B2E
- Edge: #191D21
- Border: white @ 4% opacity
- Shadow: black @ 40% opacity, 16 radius, 8 y-offset

### Typography (`AppTypography`)

| Token | Spec |
|-------|------|
| `displayLarge` | system 56 bold |
| `displayMedium` | system 48 bold |
| `displaySmall` | system 36 bold |
| `title2` | .title2 bold |
| `title3Regular` | .title3 |
| `headline` / `headlineRegular` | .headline |
| `subheadline` | .subheadline bold |
| `body` | .body |
| `callout` | .callout |
| `caption` | .caption bold |
| `captionRegular` | .caption |
| `caption2Regular` | .caption2 |

### Spacing (`AppSpacing`)

| Token | Value |
|-------|-------|
| `xxxs` | 2 |
| `xxs` | 4 |
| `xs` | 6 |
| `sm` | 8 |
| `smMd` | 10 |
| `md` | 12 |
| `lg` | 16 |
| `xl` | 20 |
| `xxl` | 24 |
| `section` | 32 |
| `sectionLg` | 40 |

### Corner Radius (`AppRadius`)

| Token | Value |
|-------|-------|
| `xs` | 4 |
| `sm` | 6 |
| `smMd` | 8 |
| `md` | 10 |
| `lg` | 12 |
| `xl` | 16 |
| `xxl` | 20 |
| `xxxl` | 24 |

### View Modifiers

| Modifier | Description |
|----------|-------------|
| `.gradientBackground()` | App background gradient |
| `.joinerGradientBackground()` | Green-tinted gradient for joiner screens |
| `.adaptiveGlassCard(cornerRadius:)` | Glass card (iOS 26) or solid card (older) |
| `.glassTabBar()` | Liquid Glass tab bar styling |
| `.nativeGlassProminentButton()` | Glass-style prominent button |
| `.proGateOverlay(isPro:)` | Pro subscription gate overlay |
| `.iconGlassCircle(color:)` | Glass circle behind icon |

---

## 25. Video Overlay Views

### `VideoImportView.swift`

**File:** `VideoOverlay/VideoImportView.swift`

**Import external video for timing overlay.**

### `VideoOverlayPreviewView.swift`

**File:** `VideoOverlay/VideoOverlayPreviewView.swift`

**Preview video with timing overlay before export.**

### `VideoSyncView.swift`

**File:** `VideoOverlay/VideoSyncView.swift`

**Sync timing data with video frames.**

---

## 26. WiFi Aware Views

### `SimulationView.swift`

**File:** `WiFiAware/SimulationView.swift`

**iOS 26+ WiFi Aware session view (host or viewer mode).**

### `DeviceDiscoveryPairingView.swift`

**File:** `WiFiAware/DeviceDiscoveryPairingView.swift`

**WiFi Aware device discovery and pairing UI.**

### `PairedDevicesView.swift`

**File:** `WiFiAware/PairedDevicesView.swift`

**List of paired WiFi Aware devices.**

---

## Appendix: Complete File Index

### View Files (alphabetical)

| File | Path | Type |
|------|------|------|
| `AthleteListView.swift` | Views/ | Screen |
| `BasicModeView.swift` | Root | Screen (full-screen) |
| `BubbleLevelView.swift` | Root | Component |
| `CalibrationFrameScrubberView.swift` | Views/ | Component |
| `CameraPreviewView.swift` | Root | Component (UIViewRepresentable) |
| `ConnectionDebugOverlay.swift` | Views/ | Debug overlay |
| `ContentView.swift` | Root | Contains HomeView, TestView, ResultsView, ProfileView |
| `CountdownStartOverlay.swift` | Views/ | Overlay |
| `CreateSessionFlowView.swift` | Views/ | Screen (full-screen) |
| `DashboardHomeView.swift` | Views/Home/ | Screen (Tab 0) |
| `DetectionDebugOverlay.swift` | Views/ | Debug overlay |
| `DeviceDiscoveryPairingView.swift` | WiFiAware/ | Component |
| `DistanceConverterView.swift` | Views/Calculators/ | Screen |
| `ExpandableResultsView.swift` | Views/ | Component |
| `FlyingSetupView.swift` | Root | Screen (legacy two-phone setup) |
| `HomePrototypePicker.swift` | Root | Legacy prototype |
| `HomeScreenPrototype*.swift` | Root | Legacy prototypes (1-4) |
| `HomeScreenV1a-V1o.swift` | Root | Legacy iterations (a-o) |
| `JoinSessionView.swift` | Root | Screen (full-screen) |
| `LiveGateMonitor.swift` | Views/ | Component |
| `LiveSessionResultsView.swift` | Views/ | Component |
| `LoginView.swift` | Root | Screen (legacy) |
| `MainTabView.swift` | Views/ | Navigation container |
| `MultipeerDebugView.swift` | Views/ | Debug screen |
| `OnboardingContainerView.swift` | Views/Onboarding/ | Navigation container |
| `Onboarding*.swift` | Views/Onboarding/ | 20+ onboarding screens |
| `PairedDevicesView.swift` | WiFiAware/ | Component |
| `PaywallView.swift` | Views/ | Screen (full-screen) |
| `PerpendicularDialView.swift` | Root | Component |
| `PermissionDeniedView.swift` | Views/ | Screen |
| `PhotoFinishDebugOverlay.swift` | Views/ | Debug overlay |
| `PresetSessionView.swift` | Views/ | Screen (full-screen) |
| `QuickCalibrateView.swift` | Root | Component |
| `RaceModeView.swift` | Root | Legacy screen |
| `ResultDetailViews.swift` | Views/ | Screen + components |
| `RunDetailView.swift` | Views/History/ | Screen |
| `RunFramesScrubberView.swift` | Views/History/ | Component |
| `SessionDetailView.swift` | Views/History/ | Screen |
| `SessionHistoryView.swift` | Views/History/ | Screen (Tab 2) |
| `SessionRunsList.swift` | Views/ | Component |
| `SessionRunsStrip.swift` | Views/ | Component |
| `SessionSettingsSheet.swift` | Views/ | Sheet |
| `SessionSetupView.swift` | Views/ | Legacy screen |
| `SetupAssistantView.swift` | Root | Component |
| `SettingsReferralView.swift` | Views/Settings/ | Sheet |
| `SettingsView.swift` | Views/Settings/ | Screen |
| `SimulationView.swift` | WiFiAware/ | Screen (iOS 26+) |
| `SoloLapsView.swift` | Views/ | Component |
| `StatsDetailView.swift` | Views/History/ | Screen |
| `TemplatesView.swift` | Views/ | Screen (Tab 1) |
| `ThermalDebugView.swift` | Views/ | Debug screen |
| `TimingSessionView.swift` | Views/ | Screen (full-screen, core) |
| `ToastView.swift` | Views/ | Component |
| `TouchStartOverlay.swift` | Views/ | Overlay |
| `TouchStartReadyView.swift` | Views/ | Screen |
| `VideoImportView.swift` | VideoOverlay/ | Screen |
| `VideoOverlayPreviewView.swift` | VideoOverlay/ | Screen |
| `VideoSyncView.swift` | VideoOverlay/ | Screen |
| `VoiceStartOverlay.swift` | Views/ | Overlay |
| `WiFiAwareTestView.swift` | Views/ | Debug screen |
| `WindAdjustmentView.swift` | Views/Calculators/ | Screen |

### Non-View Files Referenced by Views

| File | Purpose |
|------|---------|
| `SprintTimerViewModel.swift` | Camera + detection view model |
| `RaceSession.swift` | Session state machine, timing, peer communication |
| `UserSettings.swift` | App preferences (UserDefaults wrapper) |
| `AppTheme.swift` | Colors, typography, spacing, modifiers |
| `AuthService.swift` | Apple Sign In authentication |
| `SubscriptionManager.swift` | RevenueCat subscription management |
| `ElevenLabsService.swift` | AI voice synthesis for voice start |
| `VoiceStartService.swift` | Voice start state machine |
| `ImageStorageService.swift` | Photo storage (thumbnails, crossing frames) |
| `SessionPersistenceService.swift` | SwiftData session save/delete |
| `Models/TrainingSession.swift` | SwiftData model |
| `Models/Athlete.swift` | SwiftData model |
| `Models/StartType.swift` | Start type enum |
| `Models/TestPreset.swift` | Test preset definitions |
| `PersistedCrossing.swift` | SwiftData crossing data |

---

## Navigation Flow Summary

```
App Launch
├── Onboarding (if !hasCompletedOnboarding)
│   └── 19-step flow → Main App
│
└── Main App (MainTabView)
    ├── Tab 0: Home (DashboardHomeView)
    │   ├── Quick Start Presets → PresetSessionView → TimingSessionView
    │   ├── Custom Session → CreateSessionFlowView → TimingSessionView
    │   ├── Join Session → JoinSessionView → TimingSessionView
    │   └── Recent Sessions → SessionDetailView → RunDetailView
    │
    ├── Tab 1: Templates (TemplatesView)
    │   └── Template → PresetSessionView → TimingSessionView
    │
    ├── Tab 2: History (SessionHistoryView)
    │   ├── Session → SessionDetailView → RunDetailView
    │   └── Stats → StatsDetailView
    │
    └── Tab 3: Profile (ProfileView)
        ├── Athletes → AthleteListView
        ├── Wind Adjustment → WindAdjustmentView
        ├── Distance Converter → DistanceConverterView
        ├── Settings → SettingsView
        ├── Invite Friends → SettingsReferralView
        └── Try Pro → PaywallView

TimingSessionView (core timing screen)
├── Touch Start → TouchStartOverlay
├── Voice Start → VoiceStartOverlay
├── Countdown → CountdownStartOverlay
├── Results tab → ExpandableResultsView
├── Settings → SessionSettingsSheet
└── Photo Finish → CalibrationFrameScrubberView
```
