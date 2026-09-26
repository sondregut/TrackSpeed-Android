# Android audit — 6 September 2026

This is the final code phase of Sondre's iPhone, Watch, timing, solo-drill and Android review. The native Android checkout is `/Users/sondre/Documents/App/TrackSpeed-Android`, branch `main`, baseline `931f0624af4be2eaf97fa8db345b47b6cf0bb667`. Existing modified and untracked work was preserved. No commit, push, store upload, remote migration or service deployment is part of this review.

The baseline inventory covers 210 main Kotlin files (about 88,000 lines), plus tests, resources and relevant architecture/protocol documentation. Review combined source inspection of the timing, camera, transport, queues, auth teardown, persistence, history and setup paths with full-module compiler/lint/unit checks. It is not a claim of line-by-line proof of every file or of physical accuracy. `baseline-manifest.json` identifies the exact starting files; the final patch isolates this review from the pre-existing dirty work.

## Findings and changes

| ID | Failure and consequence | Implemented change / evidence boundary |
| --- | --- | --- |
| A01 | Missing sensor timestamps fell back to callback time, making an invalid frame look current. | Reject nonpositive, repeated, regressing, old and invalid future timestamps. UNKNOWN-source calibration stays frozen; checked addition prevents overflow. A callback bridge is still an estimate, not a hardware accuracy claim. |
| A02 | Closing the camera could race `acquireLatestImage`, a nullable `imageReader`, or capture-session creation. | Retain the generation guard, use local reader references and handle closed-resource exceptions. Physical background/switch stress remains required. |
| A03 | Incomplete BLE media frames had no expiry and could consume the next control message. | 1.5 s without progress expires a frame; active progress may continue up to 60 s. Allocation is bounded before concatenation. No wire-format change. |
| A04 | Two queue workers could pass a volatile Boolean check concurrently. | Serialize thumbnail/debug queue processing, enqueue and clearing with a coroutine mutex. |
| A05 | Thumbnail retry paths created a new random storage object on every attempt. | Use a stable object path per durable queue entry. |
| A06 | First-attempt media work was not durable, including immediate debug uploads. | Persist before the first attempt; thumbnail encoding leaves Main and live timing defers upload work. |
| A07 | Queue metadata could be truncated by interruption during overwrite. | Use Android `AtomicFile` and recover its backup when opening the queue. |
| A08 | Clearing/deleting a run could race its outstanding media writes. | Drain/remove queued writes before deleting server rows; serialize queue clear with its active worker. Account-switch/delete under network failure still needs physical B02 testing. |
| A09 | Coroutine cancellation was swallowed as an upload retry/failure. | Propagate cancellation through storage and queued workers. |
| A10 | Partial debug-frame uploads could be recorded as complete and removed from the queue. | Require every requested image before upserting the evidence record. Retry paths remain deterministic. |
| A11 | A delayed older start could be appended after a newer pending start thumbnail. | Reject superseded starts when enqueueing and retain the latest pending start image. |
| A12 | Anonymous PB/SB queries mixed start methods and phone arrangements. | Query by distance, method, athlete and one-phone versus multiple-phone timing. Ignore invalid or unknown-method evidence. |
| A13 | Solo sessions awarded the fastest run in that session a personal-best badge regardless of earlier sessions. | Compare each saved run with the actual matching historical cohort. |
| A14 | Profile and session summaries compared mixed distances/methods as one best time. | Group profile bests by method/course/athlete; omit a shared session/global best when its cohort is mixed. Preserve run counts. |
| A15 | Decimal PB keys changed with the phone language; 10.9 m could update a 10 m PR. | Use a locale-independent persisted decimal key and match configured flying distances exactly. |
| A16 | Cloud records without phone topology were presented as known one-phone measurements. | Represent absent topology as unknown and exclude it from comparison claims. No remote schema was changed. |
| A17 | Editing distance or correcting elapsed time retained stale PB/SB badges. | Clear those awards on correction; keep the corrected result and media. |
| A18 | Offered 5-10-5/L-drill presets used a generic single return crossing, which cannot determine drill completion. | Remove them from available/default presets. Android's generic lap timer remains available. The new landscape agility implementation belongs to the iOS debug beta; it is not silently represented as an Android drill. |
| A19 | Setup steps moved horizontally when selecting session options. | Remove the sliding content transition. |
| A20 | Several timing paths rejected valid splits below 1 ms or inferred invalidity from duration. | Validate positive, ordered timestamps; retain tiny intentional tests. A test split is not evidence of a human sprint. |
| A21 | A repeated finish could overwrite the first while waiting for a delayed start message. | Preserve the first finish. Repeated starts revise the same open run ID. |
| A22 | Multi-gate ordering could be "repaired" by clamping a timestamp to its predecessor. | Reject invalid ordering instead of fabricating a simultaneous gate crossing. |
| A23 | Raw sender time and reference time were mixed when the start phone was not the host. | Normalize canonical crossings and replay records to the reference clock; map legacy raw starts using the known sender offset. Missing offsets remain unavailable. |
| A24 | Timing could proceed when freezing the clock mapping failed. | Block the active camera start until a mapping is available. |
| A25 | Old cancellation messages could cancel a new run, and canceled timing messages could revive the old one. | Keep bounded run-scoped cancellation tombstones; ignore canceled events and scope remote cancel to its run. |
| A26 | Android dropped iPhone timing context when decoding and relaying messages. | Preserve additive context, start references and Watch/source clock uncertainty. Preserve legacy clock packet bytes when context is absent. The first regression run caught this encoding issue and it was corrected. |
| A27 | Watch-start results could be labeled as ordinary flying PBs or uploaded without their method evidence. | Persist context locally, label the Watch beta, exclude it from PB comparison and retain a local-only policy until cloud round-trip metadata is supported. A Watch still pairs with one iPhone; that iPhone is the host/start bridge. Mixed Watch/Android radio acceptance is unverified. |
| A28 | Delayed JPEG decode/download could overwrite the winning start/finish image. | Track per-run/gate image revisions before and after decoding; serialize saved-run media/calibration read-modify-write operations. |
| A29 | Receivers displayed multi-gate results without saving them to local history. | Persist the received run/segments with a frozen run number. Validate segment ordering and totals; apply late start corrections to the same result. |
| A30 | A Wi-Fi Aware attach/discovery/socket callback could arrive after timeout and leak resources or throw on Main. | Own resources per attempt, close late acquisitions immediately, and turn asynchronous callback exceptions into attempt failures. Tests race callback registration with cleanup. |
| A31 | Concurrent exports shared mutable `SimpleDateFormat` instances and lacked an exact source marker. | Use immutable formatters and include version, build marker, source fingerprint and capture scope. |
| A32 | A saved APK/version alone could be mistaken for the code under review. | Add a fingerprinted build script, artifact manifest and an installed-APK hash check in the physical-capture script. |

## Documentation checks and rejected hypotheses

Camera2 REALTIME sensor timestamps use the elapsed-realtime clock; UNKNOWN timestamps do not promise a shared clock. The mapper changes preserve that distinction. See [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics).

The missing Android 12 fine-location permission hypothesis was rejected for the current Aware implementation: the service is explicitly gated to Android 13/API 33 and checks `NEARBY_WIFI_DEVICES`. Devices below that version use the existing fallback. Aware depends on actual hardware capability and availability; an emulator or advertised capability is not radio proof. See [Android Wi-Fi Aware](https://developer.android.com/develop/connectivity/wifi/wifi-aware) and [Android 13 nearby Wi-Fi permissions](https://developer.android.com/about/versions/13/behavior-changes-13).

The current Aware implementation is an Android-to-Android clock lane bootstrapped by BLE. BLE and the cloud relay continue to carry session traffic. An iPhone/Android session must be tested on its actual selected route; this audit does not claim an Apple/Android Aware data-path pairing has passed.

The baseline lint report had 869 non-error items, mostly dependency-version suggestions, typography, unused resources and icons. This review does not perform an unrelated dependency/toolchain upgrade or delete translated resources. A successful lint task is not a zero-warning claim.

No Replica classifier or geometry threshold was promoted from this code audit. Physical crossings reviewed in this phase: **0**. Any newly suspected false positive or rejected real crossing remains `pending_owner_review` until Sondre reviews its temporal evidence.

## Validation and reproduction

Baseline `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` succeeded using the existing Gradle cache (167 tests, no recorded failures). Subsequent candidate failures, fixes, final results and artifact hashes are retained in this folder; `validation.json` is the final status authority.

Build one source-fingerprinted APK with the bundled Android Studio JDK:

```bash
python3 scripts/build-audit-apk.py --output docs/reviews/android-audit-2026-09-06/final-build
```

The script runs unit tests, lint, builds the app and instrumentation APK, verifies that source did not change during the build, and writes `artifact.json`. It neither installs nor publishes. Do not run an additional Gradle build concurrently.

Run only the local history tests on the reusable Android emulator; the physical Aware probes require separate named invocations on compatible phones:

```bash
adb -s <emulator-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <emulator-serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <emulator-serial> shell am instrument -w \
  -e class com.trackspeed.android.data.RunHistoryDatabaseTest \
  com.trackspeed.android.test/androidx.test.runner.AndroidJUnitRunner
```

## Physical tests and logging

The complete shared checklist and PASS/FAIL/INCOMPLETE scorer are in the iOS workspace at `docs/physical-tests/2026-09-06/README.md`. Complete A01–A04 as well as F/C/H/B cases on Android, with both host directions and the exact same app artifact on each Android phone. Complete Watch W cases with the paired iPhone as the Watch bridge; mixed Watch/Android results are a separate field cohort.

For every Android phone, install the reviewed APK, record `pm path`, version, signature and a live PID, then run a separate capture terminal:

```bash
python3 scripts/capture-android-field-test.py \
  --serial <physical-serial> --test-id A01-baseline \
  --artifact docs/reviews/android-audit-2026-09-06/final-build/artifact.json \
  --output outputs/physical-tests
```

The script refuses emulator serials and mismatched installed APK hashes. It keeps scoped logcat across process restarts, an extracted event ledger, package/permission state, build identity and an operator-mark file. It does **not** infer pass from log presence. End capture with Ctrl-C; add each expected action, run ID, displayed time, reference video and owner classification to the shared review JSON. Include both devices' files and run the shared scorer.

Use at least 30 baseline trials, 20 recovery/reordering trials and 50 repeat-run trials as specified in the shared checklist. Required outcomes are no missing/extra runs, identical canonical IDs and elapsed nanoseconds on participating phones, correct winning images, no canceled-run resurrection, and successful offline history recovery. Radio, camera accuracy, Watch movement timing and cone touches need actual physical/reference evidence. A missing device/log/reference remains INCOMPLETE.

The in-app log export contains the current process only. For force-stop/relaunch tests, continuous `adb` capture is required; a new PID must not erase the preceding evidence. Do not upload logs containing credentials or publicize signed download URLs.
