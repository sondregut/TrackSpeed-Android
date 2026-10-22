# Pixel 7a / iPhone 13 sync follow-up (2026-10-22)

## Evidence and scope

Android baseline `55cdfbd4`, focused branch `codex/android-ios-ble-clock-sync-20261022`.
Installed Pixel 7a: Android 17/API 37, TrackSpeed 1.0.10 (11). Installed iPhone 13:
TrackSpeed 1.0.20 (123). Both were connected over BLE. Pixel was the **start clock
reference and GATT client**; iPhone was the **finish requester and GATT server**.

At 14:06 UTC the iPhone refined uncertainty from 12.170740 to 11.859443 ms, then
accepted it against its 15 ms BLE limit. The Pixel rejected that exact completion
against its strict 10 ms limit. SessionConfig/role/StartTiming messages still
arrived; the Android join barrier remained `clock=false`. This was not a failure
to establish BLE. The installed build-123 historical source `cbc0c2f58` and its
diagnostic ledger both confirm BLE15/non-BLE10. Android retains **<10 ms**, as
explicitly required by the user; changing the threshold would not improve accuracy.

The selected iPhone estimator was bidirectional one-way bounds: 75 samples, 75
reverse probes, 171 receive-boundary timestamps, min RTT23.931549 ms, median RTT
36.043060 ms, bound width22.288683 ms over10.755 s. Reverse lower bound dominated
the forward lower bound by1.332 ms. A later attempt remained11.361787 ms; later
resyncs exceeded15 ms and reported `gate_clock_unachievable`. Neither a successful
radio connection nor simply adopting15 ms establishes reliable timing.

## Change

Android captured pong t3 before JSON serialization and ordinary ATT queueing,
despite already having a timestamp template. Now it serializes first, acquires
an idle single-packet GATT lane, then stamps and submits t3. Both GATT client writes
and server notifications use this path. Busy, fragmented or stale pongs are skipped;
the existing peer processing guard `(0,5 ms]` is preserved. Timestamped packets are
not replayed after a bond retry. Ordinary message queues retain their delivery
behavior and are drained when a direct submission fails. Independent reverse
probes continue after a received ping even if its pong could not be submitted.

Clock values remain monotonic nanoseconds; t1/t2, requester/ping/session IDs and
wire fields are unchanged. Estimator, uncertainty margins, launch jitter, pooled
two-run retry policy and precision thresholds remain unchanged. Local, peer and
UI setup checks share finite/nonnegative strict <10 ms validation, preventing NaN
or negative peer uncertainty from marking a clock ready.

This removes a source of inflated forward RTT and avoids queued pongs competing
with reverse probes. Because the observed iPhone result selected its **reverse**
lower bound, improved end-to-end convergence is **not yet proved**. A skipped
reply can also reduce sample count; a slow/busy link must fail honestly. Binder,
controller and radio delays after submission remain in the measured bound.

## Verification

Focused JVM verification **passed: 32 tests, 0 failures/errors/skips** (5 new
pong regressions, 14 codec, 8 calculator, 5 one-way-bound tests). Gradle completed
in 63.89 s. Checks cover actual UUID pong size, busy/fragmented lane refusal,
exact5 ms processing boundary, synthetic NTP truth with delayed serialization,
strict10 ms boundaries, invalid uncertainty and existing retry-pooling behavior.

Command: `./gradlew :app:testDebugUnitTest --offline --no-daemon --max-workers=2
--no-configuration-cache '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'`
with `--tests` filters for `ClockSyncPongSubmissionTest`, `TimingMessageCodecTest`,
`ClockSyncCalculatorTest` and `ClockSyncOneWayBoundTest` (full package names in the
task log). JAVA_HOME was Android Studio's bundled JBR. Local logs/results:
`android-clock-sync-investigation/focused-tests.log` and
`focused-test-results.json`. `git diff --check` passed. Disk free before/after was
2,871,521,280 / 3,070,799,872 bytes; this task performed no cleanup.

Five matching existing iPhone build123 clock-bound tests also passed in a small
Darwin XCTest harness using exact extracted production types and unchanged test
bodies from cbc0c2f58. This was not a full iPhone app build. Its rounded20ppm
holdover reproduces the observed11.859443ms bound exactly.

Offline `:app:lintDebug :app:assembleDebug` passed in174.34s: lint0errors,
869warnings,3information (same counts as the earlier candidate). APK package
com.trackspeed.android, version1.0.10(11),148032065bytes, SHA256
`9a903d3a4d431705dfb745b273e6da84693f01d926c8f4711c788f5bdbd3cd8a`.
Signature verification passed with the same certificate as the installed Pixel
build. The corrective APK is prepared, not installed; coordinate the physical
retest after the requested phone-action pause.

No device changes, install, launch, UI input, log reset, iOS edits or remote CI
were performed for this follow-up. Redacted local evidence lives in the task
workspace `android-clock-sync-investigation/`; device identifiers and logs are
not committed to this repository.

Physical acceptance remains required: coordinate an install/retest separately,
capture versions/clock and GATT roles, sample and reverse-probe counts, selected
estimator/min RTT/uncertainty, strict <10 ms completion and a ready join barrier.
Repeat with roles reversed. Do not claim phone-level accuracy from JVM tests.
