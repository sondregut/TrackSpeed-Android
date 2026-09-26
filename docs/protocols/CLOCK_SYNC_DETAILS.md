# Clock Synchronization Contract

**Reference:** current iOS `ClockSyncService.swift` BLE behavior

**Last verified:** August 22, 2026

TrackSpeed uses an NTP-style exchange to express a non-reference phone's local
monotonic timestamps in the start phone's monotonic clock domain. BLE
central/peripheral roles do not choose the reference: the assigned start gate
is the reference even when it connected as the GATT client.

```text
t_remote = t_local + offset
```

A positive offset means the local clock is behind the remote clock. Android
uses `SystemClock.elapsedRealtimeNanos()` for ping/pong receipt, send, race,
audio, and camera-frame timestamps.

## Sample exchange

```text
joiner/local                         host/reference
    t1  ───── syncPing(t1) ─────────────►
                     receive t2; send t3
    t4  ◄── syncPong(t1,t2,t3) ─────────
```

For a sample:

```text
rtt    = (t4 - t1) - (t3 - t2)
offset = ((t2 - t1) + (t3 - t4)) / 2
```

## Current BLE parameters

| Parameter | Full sync | Mini sync |
|---|---:|---:|
| Requested samples | 80 | 30 |
| Interval | 50 ms (20 Hz) | 100 ms (10 Hz) |
| Maximum RTT | 200 ms | 350 ms |
| Minimum valid samples | 5 | 10 |
| Lowest-RTT fraction | 15% | 15% |
| Full-sync timeout | 8 s | — |

A mini-sync receives the previously applied offset as its baseline. It must
not mutate the established result if it fails.

## Per-sample rejection

A sample is rejected when any of these conditions holds:

- computed RTT is negative or above the mode's limit;
- reference processing time `t3 - t2` is negative or greater than 5 ms;
- during a mini-sync, the candidate offset differs from the applied baseline
  by more than 2 ms;
- the pong does not match the outstanding ping ID, original `t1`, or expected
  reference sender.

These checks prevent delayed/spoofed pongs and reference-side stalls from
moving a healthy offset.

## Baseline estimator

The Android estimator mirrors current iOS BLE selection:

1. Require the mode's minimum number of accepted samples.
2. Find the minimum RTT and retain samples at or below
   `max(2 × minimumRTT, 5 ms)`.
3. With at least ten remaining samples, remove RTT values above
   `medianRTT + 2 × standardDeviation`.
4. Sort again by RTT and keep the lowest 15%, with at least three samples.
5. Use the minimum-RTT sample's offset unless it differs from the inverse-RTT
   weighted offset by more than half that sample's RTT; in that case use the
   weighted offset.
6. Estimate uncertainty as
   `minimumRTT / 2 + medianAbsoluteOffsetDeviation`.

If adaptive filtering leaves fewer than three samples, the estimator falls
back to the lowest full-sync minimum sample count and still requires at least
three.

## Bidirectional one-way BLE bound

BLE replies frequently miss the connection event that delivered their request,
which creates an asymmetric response delay and a roughly ±18 ms floor for
`minimumRTT / 2`. Android now mirrors the iOS Bluetooth-only one-way estimator
without adding a new wire payload:

```text
forward ping  (local -> reference): offset <= t2 - t1
reverse probe (reference -> local): offset >= remoteSend - localReceive
```

The reference emits ordinary `syncPing` reverse probes at randomized 60–140 ms
spacing while forward pings remain active. The requester randomizes every
launch phase by 0–30 ms. At least five accepted forward intervals and five
reverse probes are required. The intersection must be non-empty, agree with the
baseline within their combined uncertainty, and be strictly tighter than the
baseline. Otherwise the code keeps the RTT/MAD result.

Selected uncertainty is half the bracket width plus 0.5 ms timestamp/scheduling
margin and a conservative 20 ppm drift allowance across the measurement
window. Mini-sync validation against a selected bound checks whether the full
sample interval contains the applied offset; it does not compare the biased BLE
midpoint to that offset.

## Quality and validation

| Quality | Uncertainty |
|---|---:|
| Excellent | < 3 ms |
| Good | < 5 ms |
| Fair | < 10 ms |
| Poor | < 15 ms |
| Bad | ≥ 15 ms |

A minimum RTT above 25 ms caps an otherwise Excellent/Good result at Fair.
The connection/setup gate is stricter than the legacy quality helper: full and
mini sync must report uncertainty below ±10 ms, and at most two full measurement
runs are attempted. Poor and Bad results remain visible for diagnostics but do
not unlock pairing or timing. The separate precision diagnostic additionally
requires:

- minimum RTT < 30 ms;
- RTT jitter (`p95 - p50`) < 10 ms;
- quality Fair or better.

Quality enum order is best-to-worst. Code must use `SyncQuality.isAtLeast()`
rather than direct enum comparisons so Excellent is never mistaken for worse
than Fair.

## Applying timestamps

The joiner converts local events for the host with:

```kotlin
val hostTimestamp = localTimestamp + offsetNanos
```

The inverse conversion is:

```kotlin
val localTimestamp = hostTimestamp - offsetNanos
```

Never mix wall clock, `System.nanoTime()`, image-callback receipt time, or Unix
milliseconds into a race event. Camera2 sensor timestamps must first be mapped
to the elapsed-realtime domain as described in
[CROSS_PLATFORM_PROTOCOL.md](CROSS_PLATFORM_PROTOCOL.md).

## Physical Android↔iPhone evidence

### 2026-08-22 — encrypted BLE bootstrap reaches the clock-quality gate

- Question: Does the reported Android↔iPhone failure occur in discovery,
  authentication, protocol negotiation, or clock synchronization?
- Test ID: `android-ios-ble-clock-20260822-f2`; observed session prefix
  `0B04E732`; iPhone sender prefix `332B22C6`.
- Devices: Pixel 7a on Android 17/API 37 and the operator's iPhone. The Android
  ledger did not prove which of the two registered iPhones ran this attempt, so
  the iPhone model is intentionally not inferred.
- Android build: debug `1.0.10` (`versionCode 11`) from the dirty parity
  checkout before the transmit-boundary/2M-PHY candidate below. The exact APK
  hash for this baseline attempt was not preserved.
- Active route: encrypted BLE GATT, Android as local start/reference and iPhone
  as remote finish/requester; negotiated MTU 517. Wi-Fi Aware was not active.
- Evidence: live Android `BleClockSync`, `ClockSyncManager`, and
  `RaceModeViewModel` logcat captured during the attempt. Discovery, encrypted
  notifications, identity mapping, role negotiation, and bidirectional sync
  packet exchange all completed. Android emitted 160 reverse probes before the
  iPhone ended the session.
- Result: FAIL at the iPhone setup gate with reported uncertainty `±12 ms`,
  which is above the required strict `<10 ms`. No armed timing event followed,
  so event delivery has a zero-sample denominator and must not be classified as
  a transport failure.
- Comparison: the prior observed Android↔iPhone attempt stopped at `±16 ms`;
  the low-latency connection-priority candidate improved the visible result by
  4 ms, but still missed the gate by 2 ms. Per-device pooled metrics are not
  available from a single Android/iPhone pair.
- Limitations: the raw live capture was not retained as a durable bundle, the
  exact iPhone identity was not present in the Android log, and this attempt did
  not test reconnection, clock drift, event ACKs, result identity, or offline
  recovery.
- Decision: `shadow`; runtime change: **no** for this result. Do not lower the
  iPhone accuracy gate and do not call the failure discovery or pairing.

### 2026-08-22 — transmit-boundary and LE 2M Android candidate

- Question: Can Android remove avoidable reverse-probe queue delay and request
  a lower-latency BLE link strongly enough for the unchanged iPhone `<10 ms`
  gate?
- Test ID: `android-ios-ble-clock-20260822-f3`.
- Candidate: when Android is the GATT client/reference, capture the reverse
  probe timestamp at the direct ATT submission boundary and skip a randomized
  interval if the single-write lane is busy rather than queueing a stale
  timestamp. Also request high connection priority and LE 2M PHY, while logging
  the controller's actual PHY callback.
- Build marker: debug APK SHA-256
  `8387101a650e08327fd0dcebb1ecc017b536ff6d72b2a3480b5a62052232d8c6`;
  installed on Pixel 7a as `1.0.10` (`versionCode 11`) at
  `2026-08-22 20:05:08` local time, launched, and verified with a live process.
- Static verification: focused one-way-bound tests and Kotlin compilation,
  complete debug unit tests, debug assembly, Android lint, and
  `git diff --check` passed. The LE 2M request is advisory; only the physical
  `onPhyUpdate` log can prove what the controller selected.
- Physical result: PENDING. iPhone Mirroring was switched to the USB-connected
  Sondre13, but Apple requires one on-device unlock/approval before the retry
  can be driven and captured.
- Decision: `shadow`; runtime change: **yes**, Android debug candidate only. No
  commit, push, store build, or release was performed.

### 2026-08-22 — lightweight sync codec physical retry

- Question: Does removing Android's generic polymorphic JSON path from the
  sync-ping and sync-pong hot path let the existing iPhone `<10 ms` setup gate
  pass without changing the protocol bytes or the iPhone app?
- Test ID: `android-ios-ble-clock-20260822-f4`; passed host session
  `0e803815-e024-4d44-b750-3e4bf1de01b0`; later failed host session prefix
  `80FECB38`.
- Devices: Pixel 7a on Android 17/API 37 as the start/reference gate and the
  USB-resolved Sondre13 iPhone 13 (`00008110-000C49EE22DA801E`) as the
  finish/requesting host.
- Build marker: debug APK SHA-256
  `921f31eeedc4125c0fdbf5f5848355ecd8596930d9584080393720e0046e2acf`,
  installed as `1.0.10` (`versionCode 11`) at `2026-08-22 20:49:51` local
  time. The codec has semantic-equivalence tests that compare its parsed JSON
  with the canonical serializer and round-trip both paths.
- Active configuration: encrypted BLE GATT, MTU 517, requested high connection
  priority, actual controller callback `tx=2M rx=2M status=0`, direct reverse
  transmit-boundary timestamps, randomized busy-lane skipping, and unchanged
  iPhone setup gate.
- Baseline comparison: the immediately preceding direct-boundary/2M build
  (`674b74a82c7cb15f559e28eb75d5145a99910abd484abb79570acebaa2c78110`)
  failed its two setup runs at `±12.767984 ms` and `±11.378037 ms`.
- Candidate result, setup 1: run 1 was rejected (`±16.840341 ms`; refinement
  `±15.452021 ms`). The automatic second run selected a bidirectional bound
  from 33 forward and 53 reverse observations, width `18.06 ms`, uncertainty
  `±9.667646 ms`; refinement retained the better baseline (`±9.744466 ms`
  candidate). The unchanged iPhone gate accepted it.
- Post-gate proof: the iPhone sent sync complete, session config, role, and gate
  assignment. Android adopted session `0E803815…`, completed the authoritative
  `START_LINE` handshake, received `StartTiming`, calibrated its local gate,
  sent calibration/gate status plus an armed ACK, and received the finish
  phone's armed ACK. The host later ended the session with zero completed runs,
  so timing-event delivery still has a zero-sample denominator.
- Candidate result, setup 2: the same APK again negotiated encrypted MTU 517
  and actual 2M PHY, but both allowed runs missed the gate. Run 1 selected
  `±13.390063 ms` (refinement retained it); run 2 selected `±14.702154 ms`
  (refinement retained it), after which the iPhone sent the terminal sync
  quality failure. Candidate setup pass rate is therefore `1/2`, not a
  reliability pass.
- Evidence limitations: the live two-device logs were inspected and correlated
  during the run but were not retained as a complete field-test bundle. This
  entry preserves the exact build, devices, sessions, estimator metrics, and
  handshake outcome, but it is insufficient for promotion and does not test a
  canonical start/finish event, result equality, reconnect recovery, or
  Android-to-Android behavior.
- Decision: `shadow`; runtime change: **yes**, Android debug candidate only.
  Keep the codec and transport candidate for further measurement; do not lower
  the iPhone threshold and do not claim the cross-platform path is reliable.

#### Next experiment and gate

Run five cold Android↔iPhone Bluetooth setups with the same candidate APK and
retain both-device logs. Each setup must prove encrypted identity, actual PHY,
at least five accepted forward intervals and five accepted reverse probes, and
strict uncertainty below 10 ms; require a median uncertainty at or below 7 ms.
Then arm one intentional start/finish and require exactly one matching canonical
run on both phones, followed by one disconnect/reconnect recovery. Five of five
setup passes plus the matching run unlock promotion. Any setup at or above
10 ms keeps the candidate in shadow and the captured estimator/bound widths
choose the next Android-only change.

### 2026-08-22 — monotonic setup UI and true reverse-probe transmit boundary

- Question: Can Android show truthful progress while it acts as the start-clock
  reference, avoid presenting a failed setup as a completed race, and remove
  the remaining app-side delay between a reverse probe's `t1` and its GATT
  submission?
- Test ID: `android-ios-ble-clock-20260822-f5-ui-boundary`.
- Devices in scope: Pixel 7a on Android 17/API 37 and USB-resolved Sondre13
  iPhone 13 (`00008110-000C49EE22DA801E`). Only Android source was changed.
- Build marker: debug APK SHA-256
  `a3ceb40ceadd52e46612158bcc23c97f3920eb0dff49fd7cf016f258bc0a10ca`,
  installed on the Pixel as `1.0.10` (`versionCode 11`) at
  `2026-08-22 21:38:46` local time, launched, and verified with a live process.
- Baseline: the prior lightweight-codec candidate passed only `1/2` observed
  setups. Its failed setup measured `±13.390063 ms` and `±14.702154 ms` across
  the two allowed runs. Android nevertheless remained at `0%` because only the
  requester-side loop published progress; a terminal pre-session
  `sessionEnded` was then routed through the normal save/`Session complete`
  presentation.
- Candidate behavior: reference-side pings now advance one monotonic setup
  window. The first 80 samples map to `0–80%`, an optional second run maps to
  `80–95%`, and only an accepted `SyncComplete` may show `100%`. Accepted sync
  closes the setup-progress emitter so a delayed ping cannot return the UI to
  synchronizing. A peer end received in Pairing, Syncing, or Race Ready with no
  result now shows `Phones didn't sync`, preserves an applicable accuracy
  reason, states that no timing session or run was created, and never enters
  the saving/completed flow. iOS-only Direct Wi-Fi advice is removed because
  that recovery route is not available in the Android runtime.
- Timing candidate: Android previously captured reverse-probe `t1` before JSON
  serialization, BLE framing, and idle-lane acquisition even though the method
  was named transmit-boundary. The candidate pre-encodes a fixed-width JSON
  template, acquires the idle GATT write lane, stamps `t1`, frames one packet,
  and immediately submits it. Whitespace padding before the JSON integer is
  standards-valid and was decoded successfully by both Android's canonical
  codec and Foundation `JSONDecoder`.
- Static verification: `./gradlew :app:testDebugUnitTest :app:lintDebug
  :app:assembleDebug`, focused progress/presentation/codec tests, Kotlin
  compilation, `git diff --check`, and a Swift/Foundation padded-integer decode
  all passed. Existing detector/icon deprecation warnings were unchanged.
- Physical result: **PENDING**. A monitored pre-boundary UI build reached the
  Android join screen and refreshed its BLE scan normally, but the iPhone
  remained unlocked/in use and never entered Create Session → Connect. The
  capture therefore contains no peer, ping, uncertainty, gate, event, or ACK
  denominator and cannot score connection quality. That incomplete capture is
  under
  `/tmp/trackspeed-connection-tests/20260822_android-sync-progress-ui-f5/`;
  it is not evidence for promotion.
- Evidence limitations: the new transmit-boundary APK has install/launch and
  static proof only. Android-to-Android remains untested because no second
  Android device was available. Wi-Fi Aware remains a separate probe, not an
  enabled production route.
- Decision: `shadow`; runtime change: **yes**, Android debug candidate only. No
  commit, push, Play upload, release, backend mutation, or iOS edit occurred.

#### Next experiment and gate

Run five cold Android↔iPhone setups with this exact APK and both-device logs.
Require Android progress to become non-zero within one second of its first
logged reference ping, never decrease, and never reach 100% before
`SyncComplete`. Require reverse-probe boundary preparation p95 at or below
1 ms, five of five strict uncertainty results below 10 ms, and median
uncertainty at or below 7 ms. Force one terminal accuracy failure and require
the Android connection-failure screen within one second with no saving or
`Session complete` state. Then arm one intentional start/finish and require
exactly one matching canonical run on both phones. Those results unlock
promotion; any miss keeps the transport candidate in shadow.

### 2026-08-22 — explicit connected state and terminal-summary guard

- Question: Why could Android still look unconnected while the iPhone was
  exchanging clock samples, and could an out-of-order phase update still let a
  failed pre-session sync fall through to the normal `Session complete` UI?
- Test ID: `android-ios-ble-clock-20260822-f6-connected-terminal`.
- Devices in scope: Pixel 7a on Android 17/API 37 and the available Sondre13
  iPhone 13 as the pending cross-platform peer. Only Android source was edited.
- Input evidence: the APK installed at `2026-08-22 21:38:46` had no BLE
  connection attempt in Android logcat after that install, so the owner report
  correlated with the immediately preceding physical attempt rather than the
  f5 APK. Code inspection nevertheless reproduced a deterministic UI race:
  `BleClockSyncService.State` is a conflated `StateFlow`, and the observer could
  receive `Syncing` without ever receiving the short-lived `Connected` state.
  The `Syncing` and `Synced` branches did not set `isDeviceConnected`, while
  gate count intentionally stayed zero until validated `SyncComplete`.
- Recovered iPhone ledger evidence: read-only pull of Sondre13's
  `connection-diagnostics.jsonl` showed the two latest reported sessions were
  `EAF89F10-621E-4DC2-BC58-8D6C2A06B551` and
  `4841D9CA-18B3-44B9-A0FE-F547457878C0`. The first run/retry measured
  `±12.032476 ms` then `±11.420740 ms`; the second measured `±14.973041 ms`
  then `±12.969265 ms`. Both remained above the strict `<10 ms` gate. The
  ledger recorded continuous `connected` state throughout sampling and only
  recorded `peer_disconnected_during_setup` about four seconds after terminal
  quality rejection. Therefore the iOS `Sync reconnecting` label represented
  an automatic clock-measurement retry, not an actual BLE reconnect.
- Candidate behavior: Android now marks the BLE link connected in `Connected`,
  `Syncing`, and `Synced`, and marks it disconnected in actual scan,
  connecting, or idle states. The clock screen presents `Bluetooth connected`
  separately from `Measuring clock accuracy between phones`; a real link loss
  becomes an explicit amber `Reconnecting Bluetooth` state. The top status pill
  also reports `Connected` while measurement is running.
- Terminal guard: peer `sessionEnded` handling now logs phase, physical-link
  state, progress, uncertainty, validated-sync state, run count, result state,
  and reason. With no completed run/result, either a setup phase or the absence
  of validated clock sync always selects `Phones didn't sync`; it cannot enter
  the saving/completed presentation solely because a phase arrived out of
  order. A validated active session retains the normal completion summary.
- Verification: focused readiness/progress/codec tests passed, followed by
  `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with
  `144/144` unit tests passing. The configured lint task and `git diff --check`
  passed. APK SHA-256
  `a94e258ac612dfe34ed2f9043db34ae6d0d04114586b620f94080a10df98dbec`
  was installed on the Pixel as `1.0.10` (`versionCode 11`) at
  `2026-08-22 21:50:44` local time. Device-side APK hash matched exactly; the
  activity was resumed after a delayed check with PID `925` and no fatal
  runtime line.
- Physical result: **PENDING**. Install/launch proof does not establish clock
  uncertainty, cross-platform state agreement, terminal presentation, timing
  delivery, or reconnect recovery.
- Evidence limitations: the f6 candidate has not yet produced a BLE attempt;
  the recovered ledger belongs to the preceding Android build and cannot score
  the f6 transmit-boundary or UI changes. There is still only one available
  Android phone, so Android-to-Android remains untested. The prior `1/2` strict
  setup pass rate remains the current physical baseline.
- Decision: `shadow`; runtime change: **yes**, Android debug candidate only. No
  iOS edit, commit, push, Play upload, release, backend mutation, or threshold
  relaxation occurred.

#### Next experiment and gate

Run one immediate Android↔iPhone setup while capturing both-device logs. Within
one second of the first accepted timing sample, Android must simultaneously show
`Bluetooth connected`, non-zero monotonic progress, and no session summary.
Either accepted setup must produce strict uncertainty below 10 ms and advance
both phones to the ready flow, or terminal rejection must show `Phones didn't
sync` on Android within one second with no saving/`Session complete` state. Then
repeat five cold setups and require five of five below 10 ms with median at or
below 7 ms before promotion. Preserve exact session IDs, forward/reverse sample
counts, estimator bounds, disconnects, terminal reason, and one canonical
start/finish result in the retained field bundle.

### 2026-08-22 — two-Pixel encrypted bootstrap, clock floor, and terminal parity

- Question: Do two Android phones fully connect, and if the physical link is
  healthy but clock quality fails, do both phones leave setup with the same
  truthful result?
- Test ID: `android-android-ble-clock-20260822-a2g`; the ephemeral session UUID
  was not retained and is therefore intentionally not inferred.
- Devices: two Pixel 7a phones (`lynx`): `34131JEHN00014` on API 37 and
  `33181JEHN19813` on API 36. The second phone's wall clock was months stale;
  clock-sync calculations used elapsed-realtime monotonic clocks, but that
  wall-clock error limits cloud-log correlation.
- Active route and configuration: passphrase-free BLE discovery followed by
  Android system bonding, encrypted characteristics, CCC admission, MTU 517,
  actual LE 2M PHY, one protocol-ready peer on each phone, randomized
  bidirectional one-way probes, and an unchanged strict uncertainty gate below
  10 ms. Android requested the documented low-latency connection priority, but
  controller logs repeatedly returned interval `24` (30 ms) after each DCK
  request; the API request did not change this physical link.
- Connection result: PASS through discovery, system authentication, encrypted
  notification readiness, stable identity/config/role negotiation, and clock
  packet exchange. The host showed `2 connected` and `2/2 phones`; the joiner
  showed `Bluetooth connected`. Only protocol-ready peers contributed to those
  counts. The earlier 555-byte configuration crash was removed by the 512-byte
  GATT value cap and `512 + 51` framing, and the config/role-request replay loop
  was removed by idempotent handshake replay handling.
- Clock candidate 1: APK SHA-256
  `23372ef1b2ff3ae6a7579a0c61078bc6e8147247d82faee2b6a64d821dec950b`
  retained the quiet reverse-probe tail before scoring. Round 1 improved to
  baseline `±20.56 ms`, one-way `±10.87 ms`, 50 forward and 64 reverse
  observations; round 2 measured baseline `±20.63 ms`, one-way `±12.08 ms`, 64
  forward and 64 reverse observations. Both failed the unchanged gate.
- Clock candidate 2: APK SHA-256
  `ca72b156ce8fe722ddebc30ba8792375b0f51efe732fa17d00a7b1df497ef642`
  pooled both rounds inside one drift-accounted measurement window rather than
  discarding the first round. Round 1 measured `±12.50 ms` from 52 forward and
  58 reverse observations; the pooled result measured `±12.08 ms` from 115
  forward and 122 reverse observations. Pooling did not clear the gate.
- Fault isolation: diagnostic APK SHA-256
  `19f8d65780403701ff0003dbe2db7994e328c877711ac2c4e051adc5b53c4648`
  showed a pooled best forward upper bound of `-371525151.571 ms` and reverse
  lower bound of `-371525174.816 ms`, a roughly 23.25 ms bracket before margin.
  Moving pong `t3` past JSON serialization reduced accepted samples and did not
  improve the selected reverse lower bound; that BLE candidate was rejected and
  reverted. The observed floor is physical one-way link scheduling on this
  30-ms connection interval, not discovery, encryption, role assignment, or a
  hidden reconnect.
- UI result from the measured candidates: progress was monotonic across both
  rounds instead of resetting to zero. The requester correctly showed clock
  failure, but the reference still remained at 95% because Android did not send
  a terminal peer envelope after exhausting its own measurements.
- Final Android correction: the requester now emits the same reserved sequence
  `9002` `sessionEnded` failure envelope as iOS, exactly once while protocol
  state is `SYNCING`. The reference routes that into `Phones didn't sync`; it
  cannot remain at 95% or enter `Session complete`. This behavior is covered by
  deterministic notification and pre-race presentation tests. Both Pixels were
  reconnected for final deployment, but `34131JEHN00014` returned to its PIN
  lock after the Wi-Fi Aware instrumentation run, so the final two-phone BLE UI
  observation remains pending rather than being inferred from the tests.
- Final build marker: debug APK SHA-256
  `0bc615011dd7ad11c3a6a960e095b00834d647be45e2b02ed6cd84011cc5478f`,
  `1.0.10` (`versionCode 11`), 66,058,996 bytes. It was installed, read back,
  launched, and process-verified on both USB-resolved Pixels. Device-side APK
  hashes matched exactly; the final process readback reported PID `24493` on
  `34131JEHN00014` and PID `15776` on `33181JEHN19813`.
- Static verification: `./gradlew testDebugUnitTest lintDebug assembleDebug
  assembleDebugAndroidTest` passed with 156 tests, zero skipped, zero failures,
  and zero errors. `git diff --check` passed for the touched transport, protocol,
  test, and evidence files.
- Evidence limitations: the live BLE logs were correlated during each run but
  were not retained as a complete field-test bundle; no setup passed the strict
  accuracy gate, so there is no armed crossing, timing-event, ACK, result, or
  reconnect denominator. The terminal-envelope correction has static and
  install/launch proof, not a final two-phone UI observation.
- Decision: `shadow`; runtime change: **yes**, Android debug candidate only.
  Keep the strict gate and the truthful connection/UI corrections. Do not call
  this a fully working timing connection, do not lower the threshold, and do
  not promote BLE precision on this device pair from this result.

#### Next experiment and gate

Unlock `34131JEHN00014` and run one forced terminal BLE failure with the exact
installed APK. Require the reference phone to leave 95% within one second of
the requester's sequence-9002 log and show no saving/completed state. The
secured Wi-Fi Aware NDP/TCP probe recorded in `../WIFI_AWARE_FIELD_LOG.md` has
now passed 256/256 ordered messages with zero duplicates and `±3.004 ms`
uncertainty; implement that route behind an Android capability gate, then
require five cold setup, event-delivery, and path-loss recovery passes before it
can replace BLE for Android-to-Android clock sync.

### 2026-08-23 — Android BLE bootstrap plus secured Aware clock lane

- Question: Can Android-to-Android clear the physical BLE clock floor without
  weakening the strict setup gate or exposing the Android extension to iOS?
- Test ID: `aware-production-hybrid-pixel7a-20260823-p1`; devices and final APK
  marker match `../WIFI_AWARE_FIELD_LOG.md`.
- Baseline: the same Pixel pair previously discovered, bonded, encrypted, and
  completed the handshake over BLE, but pooled clock bounds remained around
  `±12.08 ms` on the controller's 30-ms connection interval. That was a healthy
  link and a failed timing-ready gate, not a fully connected timing session.
- Candidate: current Android peers exchange
  `androidTransportCapabilities` only after the Android advertisement token is
  verified and only over local BLE. The clock requester and reference derive a
  per-session/per-identity passphrase, move the 256 NTP-style samples to a
  secured Wi-Fi Aware NDP socket, retain BLE for every shared protocol message,
  and fall back to BLE on any unavailable or rejected Aware result. Progress is
  monotonic and 100% remains reserved for validated `SyncComplete`.
- Final physical result: PASS. The requester measured minimum RTT `6.513 ms`
  and `±3.437 ms` uncertainty (`GOOD`, 3 selected of 90 valid samples) across
  256 ordered messages with zero duplicates. It sent `SyncComplete`; the host
  reached two connected and two synced gates. Host and join instrumentation
  both ended `OK` against final target SHA-256
  `559964aede2e4f1a859e5f24208cecb9420a6255e07e1d8a15d3ea464eda84d4`.
- Static verification: `lintDebug` passed; 160 unit tests passed with zero
  failures, errors, or skips; `assembleDebug`, `assembleDebugAndroidTest`, and
  `git diff --check` passed.
- Cross-platform boundary: iPhone-to-Android remains on the corrected encrypted
  BLE path. Both Pixels report Wi-Fi Aware pairing unsupported, so this Android
  route is deliberately not advertised as Apple-compatible.
- Evidence limitations: no timing crossing/result, disconnect recovery, or
  final reference-screen visual assertion was captured in this pass. The
  existing terminal sequence-9002 correction remains unit-tested but still
  needs one unlocked two-screen forced-failure observation.
- Decision: `promote` to the installed Android debug candidate; runtime change:
  **yes**. No iOS edit, commit, push, Play upload, release, backend mutation, or
  threshold relaxation occurred.

#### Next experiment and gate

Require five of five cold Android-to-Android setups below 10 ms with median at
or below 5 ms, one matching canonical start/finish result and ACK ledger on both
phones, and one forced Aware-loss recovery. Separately run one unlocked
Android-to-iPhone terminal failure and require both screens to leave sync
progress coherently with no `Session complete` presentation.

### 2026-08-23 — five-cold-run batched Aware qualification

- Question: Does batching the production 256-message secured Aware exchange in
  groups of eight satisfy the five-run clock qualification without weakening
  the estimator or setup gate?
- Test ID and durable evidence:
  `android-aware-batched-20260823-b1`, retained under
  `../../outputs/android-connection-field-tests/android-aware-batched-20260823-b1/`.
  The bundle contains a manifest, reference/requester ledgers, structured and
  Markdown summaries, review key events, and both device logs for every round.
- Devices and configuration: Pixel 7a `34131JEHN00014` (API 37,
  reference/host) and Pixel 7a `33181JEHN19813` (API 36,
  requester/joiner); bonded encrypted BLE bootstrap, 2.4-GHz NCS-SK secured
  Aware NDP, network-bound socket, 256 monotonic NTP tuples, batch size eight,
  strict uncertainty below 10 ms, and median at or below 5 ms. The joiner's
  stale wall clock was excluded; every scored time used
  `elapsedRealtimeNanos`.
- Baseline/control: the unbatched 128-tuple candidate produced four passes at
  `±8.728`, `±7.027`, `±5.280`, and `±7.649 ms`, then failed with an Aware
  result of `±11.275 ms` and BLE retry at `±19.0 ms`. It was rejected without
  changing the accuracy gate.
- Candidate result: PASS, five of five. Uncertainty was `±3.881`, `±5.298`,
  `±4.307`, `±2.890`, and `±4.971 ms`; median `±4.307 ms`, maximum
  `±5.298 ms`. The pair delivered 1,280/1,280 ordered messages with zero
  duplicates. Requester setup took `12.278`, `14.890`, `12.055`, `13.287`,
  and `14.179 s`; reference setup took `14.502`, `17.059`, `14.551`,
  `16.969`, and `19.021 s`.
- Exact sync-UI artifact control: APK SHA-256
  `1d43606195ac169e551982c71ebe5a6895709f8ecf14ad3757716c4f166024d2`
  separately passed 256/256 ordered messages, zero duplicates, and
  `±4.078 ms` GOOD quality (`12.561 s` requester, `14.968 s` reference).
- Per-device quality: reference ledgers confirmed 256 messages and zero
  duplicates in every round. Requester qualities were GOOD, FAIR, GOOD,
  EXCELLENT, and GOOD; no round was hidden or excluded.
- Decision: `promote` the batched Android-to-Android clock route in the
  installed debug candidate; runtime change: **yes**. Do not promote Wi-Fi
  Aware as an iPhone route: the tested Pixels report Aware pairing unsupported,
  and iPhone remains on the corrected encrypted BLE protocol.
- Evidence limitations: this qualifies cold setup precision on one model pair,
  not range, multiple simultaneous peers, forced NDP loss, or a final canonical
  start-then-finish result. The later portrait-camera/active-screen APK is
  installed on both phones but could not be taken through a new pair because
  `34131JEHN00014` re-locked and the owner was unavailable to unlock it.

#### Next experiment and gate

Force one Aware data-path loss and require a bounded explicit recovery or BLE
fallback without a false ready state. Then deliver one event-time ordered start
followed by finish and require the same event IDs, ACKs, one run ID, one result,
and matching total on both phones. Separately, run one iPhone-to-Android BLE
setup and require both UIs to converge on Connected/Ready or the same terminal
error without progress resets or `Session complete` during setup.
