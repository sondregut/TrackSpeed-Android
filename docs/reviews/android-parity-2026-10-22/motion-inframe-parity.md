# Android Motion and connected In-Frame start parity

Source reference: iPhone release commit `16733b928cb77d0eb014f2ccbbfe7380c5ef36af`,
`CameraStartOnsetDetector.swift`, `InFrameStartArming.swift`, and `Views/CameraStartOverlay.swift`.
Android base before this change: `498e8624bcd8250242b27b66b7124bc706b54ac8`.

## Implemented native behavior

- Motion Start is selectable locally and uses the start phone’s front camera. Finish/split
  phones retain their normal rear-camera line detector. Wire identities remain `motionStart`
  and `inFrame`; no backend or protocol field changes.
- ML Kit streaming pose runs on one newest-frame worker (bounded memory). The detector
  consumes mapped Camera2 frame timestamps, never callback/Compose receipt timestamps.
  Upright pixels are divided by height on both axes, matching iPhone’s Euclidean units.
  ML Kit has no independent neck landmark; it is deliberately omitted rather than
  synthesized from shoulders (which would double-count a single shoulder tracking glitch).
  Race is portrait locked by `app/src/main/AndroidManifest.xml`; display rotation is 0 here.
  The existing single-camera landscape route retains its own rotation handling.
- At least eight confident joints including a shoulder and hip; 0.8 s stillness;
  torso-normalized median baseline; second-largest joint displacement; three consecutive
  threshold samples with core movement. Noise-band interpolation retains the original
  movement onset and rejects isolated joint glitches and hand-only motion.
- Motion fires once at that confirmed onset. In-Frame waits for median trunk travel of
  half a torso or verified absence after the confirmed movement. Weight shifts time out
  and re-arm. In-Frame sends the original onset, not the later commitment time.
- Gate readiness for the pose start phone checks a functioning front-camera inference
  pipeline instead of requiring an empty line. The athlete can settle while waiting;
  starts arm only during an accepting session. Cancellation, pause, capture errors,
  geometry restart, failed inference, and run changes invalidate stale work.
- UI shows step-in, hold-still progress, waiting, ready, confirming and started states.
  A local system-TTS Ready cue is informational; it does not define start time and never
  requests cloud speech. The line overlay is hidden for pose starts.
- The obsolete translated line-crossing instructions were removed only for the affected
  In-Frame description keys. Those keys and new pose-state labels deliberately use the
  updated English fallback pending translation, not claimed completed localization.

## Verification status

New deterministic tests: `CameraStartOnsetTest` (14 cases),
`CameraStartRolePolicyTest` (2 cases), updated `StartTypeTest`.
They cover timestamp interpolation, no premature arming, repeated/reordered frames,
noisy/hand-only movement, pose loss, nonfinite/low-confidence input, commitment/timeout,
failed capture, rejected starts, one-shot/run-close behavior, and per-role capability.

After the parent’s iPhone compile released the shared native build slot, both serial
commands passed using `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.trackspeed.android.camerastart.*' --tests 'com.trackspeed.android.model.StartTypeTest' --max-workers=1
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --max-workers=1
```

- Focused run: **23 tests passed**, 0 failures/errors/skips (14 onset, 2 role policy,
  7 StartType); Kotlin compilation passed. Gradle completed in 1m 45s.
- Full run: **252 JVM tests across 52 suites passed**, 0 failures/errors/skips.
  The suite increased from the 236-test pre-change baseline by the 16 new cases.
- `assembleDebug`: **passed**. APK: `app/build/outputs/apk/debug/app-debug.apk`,
  147,367,154 bytes; SHA-256
  `4eb473a42124f2da3ef2a99cd1a55259aa906d6060841a1b3a6cc6e138cdb896`.
- `lintDebug`: **passed**, 0 errors, 869 warnings, 3 information. Warning count is
  unchanged from baseline; no lint issues point to the new camera-start files.
  Full combined command completed in 2m 45s.
- `git diff --check` passed. All 32 string-resource XML files parsed successfully.
- Local logs and machine-readable totals are in the parent task workspace at
  `/Users/sondre/Documents/Codex/2026-10-21/task-10/android-start-stage/`:
  `focused-tests.log`, `focused-results.json`, `full-validation.log`, `full-results.json`.
  Gradle XML tests and lint reports remain in `app/build/test-results/testDebugUnitTest/`
  and `app/build/reports/lint-results-debug.{xml,html}`.

The prior 10 native-device tests were **not rerun**; they do not validate the new pose
capture route. No physical device, emulator, foreground UI, store publication, or cache
deletion was performed. Observed disk headroom was 1.3 GiB initially, 1.0 GiB at the
lowest check, and 1.2 GiB after validation. No code fixes were needed during validation.

## Remaining acceptance and platform limits

- Reaction Start stays guarded on Android’s local start phone. Android finish/split phones
  preserve remote Reaction events. Accurate audible-cue onset, signed reaction time,
  false-start classification and result persistence require a dedicated contract;
  a scheduled/receipt-clock estimate would not be equivalent.
- ML Kit and Apple Vision differ. Real front cameras need field checks for latency,
  effective pose FPS, confidence, exposure/rolling shutter, low light, athlete framing,
  thermal conditions and false triggers. This is software parity, not a claim of measured
  timing accuracy. Only one athlete should occupy the frame; identity across occlusion
  or multiple entrants is not guaranteed by ML Kit’s single-person stream.
- Camera2 UNKNOWN timestamp-source devices retain the existing calibrated callback bridge
  and its uncertainty; no wall clock is substituted. Verify elapsed-realtime mapping and
  BLE radio permutations with Android-to-Android and Android-to-iPhone physical trials.
- Per-onset diagnostic frame interval is computed by the engine but does not add a new
  wire/result uncertainty field. Start-photo/video parity and richer diagnostics remain
  separate work. Do not infer physical accuracy from the displayed decimal precision.
- Accessibility/compact-screen review, translations, ready-cue audibility and complete
  pause/resume/disconnect/re-arm scenarios still need device acceptance.
