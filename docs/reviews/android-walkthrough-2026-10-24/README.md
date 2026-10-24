# TrackSpeed Android — actual screen walkthrough, 24 October 2026

This is a verified increment, not a claim of complete iPhone parity or physical timing acceptance. The gallery distinguishes actual app navigation, navigation using synthetic local training data, isolated production Composable renders, and decoded actual synthetic-video exports. Every screenshot retains its source fingerprint in screen-inventory.json.

## Changes and validation

Source fixes committed as `08548d3`; original published base: `8ad2b20d66f55576fd8af242d40fcd532e4be067`, branch `codex/android-onboarding-visual-parity-20261022`. Its three handshake corrections preserve pending configuration during provisional reference pings, preserve early reference completion through role assignment, and restart synchronization on a new link while keeping duplicate callbacks idempotent. The strict uncertainty <10.0 ms requirement remains unchanged. Physical iPhone↔Android and Android↔Android tests remain required; stale GATT-handle recovery remains separate.

The real walkthrough found and corrected three additional UI defects:
1. The unavailable-camera onboarding card drew the same error twice and overlapped a disabled camera-switch control. It now displays one padded, readable error and omits the unusable control.
2. Profile displayed a hardcoded v1.0.0. It now uses the installed version/build through the existing localized format.
3. Run details labelled every run 1-Phone. The mode now uses the recorded run phone count and existing localized plurals.

AGP now selects the isolated UI runner explicitly with `-Ptrackspeed.uiParity=true`. Default instrumentation keeps AndroidJUnitRunner. The onboarding guide test selects the visible pager button instead of matching retained offscreen pages; assertions were not weakened. A separate fixture is opt-in, requires an offline ranchu emulator and airplane mode, inserts only its named synthetic session/runs, and never clears existing data. The emulator must remain offline.

Final local verification: **332 unit tests passed**, 0 failures/errors/skips; lint **0 errors, 817 warnings, 3 information**; debug and instrumentation APKs built. Native execution: **19 UI/export regression checks passed plus one explicit fixture preparation check**, 20/20 total. The earlier 13/14 native run and its offscreen-selector failure remain in validation/native-ui-tests.log. Three current synthetic MP4 exports passed preview/export comparisons and safe-area assertions. No new remote CI was launched.

Final app version **1.0.10 (11)**, source fingerprint **7fe3fd06c2207dff**; APK SHA-256 **3ebccd12b501fa95555cc9adbcec77c257fd010cc77cd68c61977366121ee19b**. Full hashes and sizes are in artifact/final-manifest.json. This version number is reused; fingerprint and APK hash identify this candidate. The APK was installed as an update and cold-launched on task-owned `emulator-5580`, Android 16 / API 36. Pixel was unavailable; no physical installation or phone access occurred.

## Capture conditions

Final coverage: **75 actual runtime screenshots**, **18 native component renders** and **29 native overlay/export images**, plus **3 synthetic exported MP4s**. Every declared route is inventoried; partial state coverage remains explicit.

720×1280 headless task-owned AVD, airplane mode enabled and no active default network. Actual onboarding, guest flow, native camera and nearby-device prompts, guide Back/Continue, coachmarks, Home, Templates, session setup, host/join permission failures/help, solo camera-error/empty results, empty/populated History and filters, Stats, Profile, Settings, notifications-disabled, athlete list/form, calculators, result/session share previews, and video import/preview/unit selection were exercised. Results labelled `fixture` are actual app screens with synthetic values, not measured runs. No account, purchase, invite, code redemption, external share, or production upload was performed.

The native video player can prevent UiAutomator from becoming idle. The capture helper now refuses stale hierarchy data; such editor captures use actual pixels with an explicitly unavailable hierarchy. Earlier picker/transition XML can lag the screenshot and is not relied upon as evidence of the displayed editor state. No desktop or browser input was used.

The old AVD registration was incorrectly generated as target android-0 by installed command-line tools 12.0 despite an Android 36.1 image and newer XML metadata. Correcting that task-only registration removed the HVF warning but did not make that AVD usable. An existing task-owned TrackSpeedReview AVD with a correct target booted. No data wipe, global security change, cache deletion or physical-device reset was used.

## Remaining gaps

- Full current iOS visual/asset parity is unfinished: the current iOS tour sequence and new preview assets differ; those assets contain iOS chrome and cannot simply be pasted into Android. The route inventory records all 32 declared Screen objects / 28 registered destinations, with partial/conditional coverage rather than claiming every state.
- Physical BLE/config/sync readiness, accepted timing precision, reconnection/stale GATT handles, live camera crossings, recorded crossing-frame scrubber, and landscape-camera acceptance require real hardware. Headless camera failure is expected here.
- The current early-release configuration replaces promo/paywall routes with free-access messaging. Billing loading/unavailable components were rendered, but real offerings, discounts, purchases, signed-in account flows and backend errors were not forced.
- Join's denial screenshot still says “Scanning with Bluetooth” alongside the permission-required error; troubleshooting also uses older Location wording. These are now evidenced follow-up UX gaps, not proof scanning occurred.
- The compact final Home coachmark has cramped bottom controls. Settings top/inset appearance needs full-device visual comparison. Complete sharing-editor option interactions and landscape recording remain partial; actual export renderer/unit checks passed.
- Android dependency updates remain separate: current AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01 have not been upgraded by this increment. No premature transport replacement or unsupported Reaction Start claim was introduced.

## Delivery and scope

The HTML is self-contained for screenshots, searchable by screen and evidence class, with full-size image viewing. The ZIP contains screenshots, route inventory, report, source provenance and three synthetic exported MP4s. Install-ready APKs are preserved separately because of size. No Google Play release, production backend/security/credential changes, extra CI spending, iOS source mutation, or Sondre17 access occurred. Draft PR creation remains under its prior unresolved approval; no denied PR operation was retried.

## Original handshake diagnosis

1. iOS may send provisional reference-clock pings before authoritative session configuration. Android overwrote its pending handshake phase with `SYNCING`; its replay policy then rejected the first configuration and assignment handlers no longer accepted the pending role. Clock progress now preserves unfinished handshake phases.
2. Valid early reference synchronization was overwritten by the later role request. The validated clock result now survives until authoritative assignment; setup is still required before protocol readiness.
3. The host retained the last reference identity across disconnect. The same start phone reconnecting could therefore skip fresh synchronization. A new link/handshake now requires fresh sync even when the stable peer identity is unchanged; duplicate announcements on a completed link remain idempotent.

Nine focused policy tests pass, including six new regressions. Replacing the new policy transitions with the previous inline behavior causes five failures. Native full-unit compilation also passed. The estimator, four timestamps, monotonic clocks, and strict **less-than-10-ms** acceptance boundary are unchanged. The UI still requires configuration, authoritative role, start authorization and acceptable synchronization together.

These defects are demonstrated in source/tests. They are not proof that every earlier physical stall had this cause. The recorded ATT Invalid Handle issue remains separate: stale GATT characteristic recovery/onServiceChanged is not fixed or hardware-validated in this increment.
