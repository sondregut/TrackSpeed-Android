# Android history loading and recovery — 22 October 2026

The current iPhone history distinguishes an initial fetch from an empty history. Android previously initialized its date groups to an empty list, displayed the empty screen before Room emitted, and offered no recovery if the read flow failed.

The Android candidate now publishes one history state for data and loading/error status. Initial loading uses a progress indicator. A failed read offers Retry without exposing database payloads. A later failure retains the last successful results, including filtering/search, and labels them as retained results. Retry cancels the prior reader; navigating away cancels the screen-owned scope. The 3 new messages are present in all 31 existing translations; native-language visual review remains pending.

## Verification

- Full offline Gradle validation passed at **2026-10-22 21:57:59 UTC**: `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`.
- **316 unit tests passed**, zero failures/errors/skips. Nine new tests cover initial loading, genuine empty data, first failure, retained results, retry replacement, repeated retries, cancellation, factory failure and completion without data.
- Lint: **0 errors/fatal**, 894 warnings, 3 information. No warning suppression added.
- Two native history rendering fixtures compile. They were **not executed**, and no screenshot/native acceptance is claimed.
- APK SHA-256: `e04133f023e644984a9e2a1602632935f0cc959adf14d513af2aed00407ff23c`.
- Runtime source fingerprint: `e58b3e6b83331d2e`; package `com.trackspeed.android`, version 1.0.10 (11). Version is reused; use the hash/fingerprint for attribution.
- APK signature matches the preserved candidate signer. Source manifest matches the build inputs.
- Evidence: `validation/build-status.json`, `validation/build.log`, `artifact/manifest.json`, `artifact/source-manifest.json` in the task's `full-app-parity-20261022/history-state` folder.

The APK is install-ready, **not installed or freshly launched**. Physical timing and cross-platform radio acceptance are still unverified. This change does not alter camera/BLE/timing, the strict 10 ms safety bound, credentials, backend, device state or pairings. No phone or Sondre17 access, hosted CI, or cache cleanup occurred.
