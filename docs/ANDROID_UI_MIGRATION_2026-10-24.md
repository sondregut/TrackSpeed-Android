# Android UI dependency migration — 24 October 2026

This stage follows preserved checkpoint `456316a` on `codex/android-onboarding-visual-parity-20261022`. It upgrades the supported SDK 36 UI stack and corrects the Activity lookup defects exposed by its lint checks. It does not claim the latest major toolchain migration or full physical parity is complete.

## Implemented

| Family | Previous | Validated candidate |
|---|---|---|
| Core | 1.15.0 | 1.17.0 |
| Lifecycle | 2.8.7 | 2.9.4 |
| Activity Compose | 1.9.3 | 1.11.0 |
| Compose BOM | 2024.12.01 | 2025.11.01 |
| Compose UI / Foundation / Runtime | 1.7.6 | 1.9.5 |
| Material 3 | previous BOM | 1.4.0 |
| Navigation Compose | 2.8.5 | 2.9.8 |
| Hilt Compose | 1.2.0 | 1.3.0 |
| Core Splashscreen | 1.0.1 | 1.2.0 |

Hilt's documented `hiltViewModel` relocation uses `hilt-lifecycle-viewmodel-compose` and its new package in 35 existing call sites. ViewModel owners, keys and navigation state remain unchanged. Compile/target SDK 36, minimum 26, AGP 8.11.1 and Kotlin 2.2.21 are retained. The resolved graph, rather than catalog intent alone, was checked.

The first lint run found five `ContextCastToActivity` errors. Three production paywall routes now use `LocalActivity.current`; the recovery purchase callback is guarded and disabled without a host Activity. Two native capture fixtures explicitly require their ComponentActivity. No lint suppression or baseline was introduced.

Official sources: [Navigation](https://developer.android.com/jetpack/androidx/releases/navigation), [Compose UI](https://developer.android.com/jetpack/androidx/releases/compose-ui), [Hilt](https://developer.android.com/jetpack/androidx/releases/hilt), [Activity](https://developer.android.com/jetpack/androidx/releases/activity). Navigation 2.9.8 includes a predictive-back race correction. These are supported maintenance-line upgrades, not evidence that a library caused the earlier physical connection incident.

## Validation and runtime evidence

- 492 JVM tests passed, with no failures, errors or skips. Debug app and native test APK builds passed.
- The complete applicable offline native suite passed 94/94; physical capability probes and data-seeding helpers are excluded. It covers Room v9 preservation, navigation/onboarding, editor restoration, loading/retry, PNG capture and synthetic MP4 exports.
- The first native run passed 93/94: an Ideas composer callback assertion returned zero instead of one. The test originally combined three assertions on one line. Added visible-control assertions and separate callback messages preserve every assertion and save a failure screenshot. The isolated Ideas suite passed 5/5 and then the full suite passed 94/94. The original miss was not reproduced; its cause remains unproven. It is not described as a production fix.
- Lint passes with zero errors, 895 warnings and three hints. Warning debt remains; no claim of a warning-free app.
- APK permissions match `456316a` byte-for-byte and the signing certificate matches. Core camera/detection, clock, protocol, storage, backend and billing implementations were not changed.
- Emulator-installed APK bytes match SHA256 `5966855c8ba75cb50d000e5e2a9168b6b7bf5a8ed7b2e0d0f4d6e3527c25a59a`. Version is 1.0.10 (11), source fingerprint `a2d1286cbf865769`. MainActivity cold-launched successfully in 1,300 ms. The reused version alone is not artifact identity.
- Fifteen fresh real MainActivity captures cover Home, Profile, Settings, repeated Back including edge-swipe, guest sign-in/back with scroll retained, History, Session Detail, expanded run, Share Session and Share Result/options. Three synthetic sessions and 14 runs remained intact. Fixture data stays offline and is not measured performance. Earlier gallery images keep their source provenance.

## Remaining work and exact prerequisites

The final bounded device inventory contained only the task emulator; Pixel was absent. No physical phone was installed or accessed. Pixel update and camera/sensor timing, BLE Android↔iOS/Android↔Android in both host roles, reconnect/load and uncertainty acceptance remain pending. Timing thresholds were not relaxed. API 26–28 gallery behavior, live account/billing/backend and recipient handoff are also separate acceptance work.

The coordinated latest-major UI/toolchain stage requires SDK 37 and reviewed AGP/Kotlin changes. Official repository metadata lists `platforms;android-37.0` (~67 MB archive) and `platforms;android-37.2` (~68 MB), not the old candidate's `platforms;android-37`. The current Android SDK licence digest is absent from the host's accepted records using the installed SDK manager's exact hash algorithm. Owner review/acceptance is required before that installation; no install or legal acceptance was attempted and this is not an executor denial. Review minor-API configuration before applying the old major-stage patch. [Official SDK repository metadata](https://dl.google.com/android/repository/repository2-3.xml).

Other staged serialization/network, RevenueCat, Firebase and unused CameraX dependency changes remain independent implementation/validation work; they are not all blocked by the SDK licence or absent hardware. Full-app parity is not claimed.

One additional source-level accessibility concern was noted for follow-up: expanded session-run text actions are vertically closer than their automatically expanded touch bounds. Share Run opened the correct route in this check, so no misrouting was observed or claimed. Explicit nonoverlapping touch targets require a separately validated layout increment.

No Sondre17 access, desktop input, paid CI, cache deletion, production/security/credential change, Play publication or denied PR retry occurred. Prior iOS release/push/symbol approvals remain separate.
