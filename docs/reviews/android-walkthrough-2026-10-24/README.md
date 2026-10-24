# TrackSpeed Android: connection fix and screen-review checkpoint

24 October 2026. This is a verified software increment, **not full iPhone parity or a completed actual-screen walkthrough**.

## Candidate and validation

- Existing branch: `codex/android-onboarding-visual-parity-20261022`; continued from `1db4621d89c09306e3310491ad3b8828ee05fdf4`. No rewrite, reset, unrelated staging or iOS changes.
- Version remains `1.0.10 (11)`. Distinguish the build by source fingerprint **`6bd08d717e672e4e`** and APK SHA-256 **`3d58bfd215141fbff33a26325a557144e0b2ed14a24012f96e030e6de2412565`**.
- `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`: **passed**, cached/offline Gradle, 1m51s. **332 tests, zero failures/errors/skips**. Lint: zero errors, 817 warning entries, 3 information entries.
- Debug APK and instrumentation APK preserved with hashes and all main-source input hashes in the sibling `artifact/` folder. Native screenshot tests compiled but **did not execute**.
- No remote CI launched, no installation, no physical connection test, no publication to a store, and no change to iOS 134 approvals.

## Diagnosed and corrected

1. iOS may send provisional reference-clock pings before authoritative session configuration. Android overwrote its pending handshake phase with `SYNCING`; its replay policy then rejected the first configuration and assignment handlers no longer accepted the pending role. Clock progress now preserves unfinished handshake phases.
2. Valid early reference synchronization was overwritten by the later role request. The validated clock result now survives until authoritative assignment; setup is still required before protocol readiness.
3. The host retained the last reference identity across disconnect. The same start phone reconnecting could therefore skip fresh synchronization. A new link/handshake now requires fresh sync even when the stable peer identity is unchanged; duplicate announcements on a completed link remain idempotent.

Nine focused policy tests pass, including six new regressions. Replacing the new policy transitions with the previous inline behavior causes five failures. Native full-unit compilation also passed. The estimator, four timestamps, monotonic clocks, and strict **less-than-10-ms** acceptance boundary are unchanged. The UI still requires configuration, authoritative role, start authorization and acceptable synchronization together.

These defects are demonstrated in source/tests. They are not proof that every earlier physical stall had this cause. The recorded ATT Invalid Handle issue remains separate: stale GATT characteristic recovery/onServiceChanged is not fixed or hardware-validated in this increment.

## Screen walkthrough status

- **0 current-build runtime screenshots.** ADB found no connected Pixel. The task-owned headless `codex_trackspeed_parity_20261022` emulator remained offline and logged `qemu_mprotect__osdep: mprotect failed: Permission denied`. Its process was stopped and verified absent host-side. No security settings or emulator/user data were changed to bypass the failure.
- There are **28 registered NavHost destinations and 32 declared Screen objects**. Calibration, active timing, device pairing and legacy results declarations are not independent registered routes; several states are embedded in solo/race flows. `screen-inventory.json` names each route and required conditional/empty/error states.
- New `ScreenWalkthroughRenderTest` covers production Composables with explicit synthetic offline state: welcome, role/sport, how-it-works, attribution, camera preprompt, early-release, home empty, settings/units, tools/calculators and paywall loading/unavailable-pricing. It uses `UiParityTestRunner` to avoid analytics, billing and cloud SDK initialization. It is **compiled, not run**, and would provide component renders rather than real navigation/hardware acceptance.
- The HTML gallery contains **8 historical native captures/decoded exports from baseline `498e862`, 22 October**, visibly labelled as older evidence. They are not counted toward current coverage. No mockup is presented as an app screenshot.
- A usable emulator or connected/unlocked Pixel is the immediate blocker for fresh captures. Pixel capture must first confirm no active session, preserve app data, and obtain runtime/source identity before walkthrough actions. No Sondre17 monitoring, screenshots, mirroring, polling or control occurred.

## Fresh iPhone comparison and remaining parity

Read-only iOS reference: checkout HEAD `e545b471e` with concurrent uncommitted edits. No assertion that this entire tree is released. Its feature tour now includes solo laps, connected gates, sport tests and proof/review, plus conditional one-camera/Watch stories. Android still uses the older setup/connect/alignment/start/automatic/proof tour.

The current tour references four new iPhone screen-preview assets. The inspected solo-preview image includes iOS status/navigation chrome; these previews need Android adaptation and were not copied as purported Android screenshots. The asset audit found only one exact byte match among 17 Android onboarding/proof resources against the **current** iOS asset tree; the earlier eleven-match claim belonged to the older reference. Lack of a byte match alone does not prove different visual content, but current asset parity has not been re-established.

Still required: current onboarding sequence/story layout/assets; full home/setup/roles/calibration/live/results/history/auth/settings/paywall/media/share editor visual comparisons; saved-result and failure-state walkthroughs; native unit-option persistence and actual exports on the candidate; stale-handle recovery diagnosis and both host-role hardware retests. Apple Watch and unsupported Apple transport APIs must stay platform-specific. Reaction Start remains unadvertised until measured onset validation exists.

The dependency stack is not all current: AGP 8.7.3/Kotlin 2.0.21/Compose 2024.12.01 remain. Prior compatible SDK updates are preserved; a broad toolchain/camera migration was not combined with this handshake correction. Hardware BLE interoperability, clock accuracy and camera performance are still acceptance requirements.

## Safe next test

1. Connect/unlock Pixel; confirm its identity/ADB authorization and no active timing session. Install the exact candidate as a data-preserving update only if authorized, then verify installed hash/fingerprint and a fresh process.
2. With the user coordinating a named iPhone (Sondre17 remains off limits), test existing pairings first, both host roles. Record link → notification readiness → config → authoritative role → sync uncertainty → start/ready. Preserve a failure before retrying. Do not relax timing bounds.
3. Capture real reachable routes and conditional states. Use an isolated offline emulator/test fixture for synthetic results/paywall states; do not alter real purchases, pairing data or saved sessions to manufacture screens.
4. Regenerate the gallery with exact capture build and provenance. Keep unavailable states explicit until captured.

No cache or user files were deleted. The build used the shared native slot after checking competing processes; it released the slot on completion. Last measured free space before validation was about 52 GiB.
