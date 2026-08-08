# iOS–Android Parity Verification — July 21, 2026

## Scope and reference

The Android audit used iOS repository commit `ce8dbe5b` plus the current iOS
working-tree changes as the behavioral reference, and Android repository
baseline `c432332`. The Android worktree already contained a large,
uncommitted iOS-parity port; it was preserved and audited in place.

The most recent iOS behavior reviewed included peer/cloud failover, timing
diagnostics and offline log delivery, durable evidence and thumbnail retry,
the July bodyless scene-motion rejection changes, and the validated remote
`replica_v1` detection profile introduced in the current iOS working tree.
Swift source was treated as authoritative over older Android documentation.

## Result

The feasible software implementation and automated verification are complete:

- Android builds and launches on an API 36.1 emulator.
- All 87 Android JVM tests pass with no failures or skips.
- Android lint completes with zero errors.
- The selected 21 current iOS reference tests pass on an iPhone 17 simulator.
- The latest eight focused iOS detection-profile and scene-motion tests also
  pass after the final parity changes.
- Protocol 7 JSON, BLE framing, ACK/retry behavior, cloud fallback, clock sync,
  detector guards, camera timestamps/exposure, durable race-event delivery,
  and multi-gate calculations are implemented and covered as described below.

Physical BLE radio and real-camera validation could not be executed because no
Android phone was attached and every discoverable physical iOS device was
offline. Simulator success does not substitute for that acceptance matrix; the
required physical checks are listed at the end.

## Protocol and connectivity parity

| Contract | Android status | Evidence |
|---|---|---|
| Timing wire version | Version 7 | `TIMING_PROTOCOL_VERSION`; codec tests |
| Swift payload encoding | All 53 current iOS case keys and associated-value shapes | exhaustive codec round-trip test |
| BLE UUIDs/directions | Exact iOS service, TX, and RX UUIDs | `ClockSyncConfig`; service setup |
| BLE encryption | Encrypted TX read/notify and RX write; bond retry | GATT permissions and authentication recovery |
| Notification readiness | Connection ready only after CCC write succeeds | GATT descriptor callback |
| Large messages | Exact `STB1` + big-endian length framing, 1 MB cap | four framing tests plus iOS framing tests |
| Multi-client host | Per-peer MTU, framer, queue, sender/address map, targeted ACK | service implementation |
| Android↔Android discovery | Symmetric advertise+scan dual mode; deterministic token/device-ID election resolves simultaneous connections | service implementation and role-election tests |
| iOS↔Android discovery | Service-UUID discovery; no Android local-name dependency | service implementation and current iOS UUID audit |
| Critical delivery | Same immutable envelope retried; per-recipient ACK tracking | five retry tests |
| Cloud fallback | `timing_<session>`, `timing_message`, `{payload: message}` | two relay shape tests and current iOS transport audit |
| Reconnection | BLE rediscovery, cloud resubscribe, state/event replay paths | service and race-session audit |

Bluetooth clock-sync ping/pong remains local-only. Timing events can be sent
over BLE and Supabase together and are deduplicated by message/event identity.
On iOS, Bluetooth—not Apple Multipeer Connectivity—must be selected for an
Android peer.

Every Android transport, race, relay, persistence, and analytics path now uses
one canonical installation ID derived from `ANDROID_ID`; random per-service
identities can no longer make one phone appear as multiple peers. Joiner
readiness is based on both connected and clock-synchronized gates, so a race
cannot arm merely because GATT connected before timing synchronization became
usable. The race layer also converges on a host session already adopted by the
clock-sync collector, eliminating callback-order-dependent local session IDs.

## Clock synchronization parity

Android now follows the active iOS BLE estimator instead of the older
lowest-20%-median documentation:

- 80 full-sync samples at 20 Hz; minimum five accepted;
- 30 mini-sync samples at 10 Hz; minimum ten accepted;
- negative/high RTT, reference processing over 5 ms, and established-offset
  jumps over 2 ms are rejected;
- adaptive `max(2 × minimumRTT, 5 ms)` filtering, standard-deviation outlier
  rejection, then lowest 15% RTT selection;
- minimum-RTT offset cross-checked against inverse-RTT weighted offset;
- uncertainty equals minimum RTT/2 plus offset median absolute deviation;
- Poor is usable, Bad is rejected, and precision requires Fair or better;
- quality ordering is centralized in `SyncQuality.isAtLeast()` so best-to-worst
  enum order cannot be inverted.

Unit coverage verifies offset sign, latency/jump rejection, mini-sync sample
floor, precision quality ordering, and high-latency offset filtering.

## Detection and Camera2 parity

The active Android path is `DetectionEngine`, not the legacy
`PhotoFinishDetector` implementation. It is a compact Kotlin port of the
functional sections of current Swift; iOS-only diagnostic logging was not
duplicated where it does not affect a detection result.

Implemented current behavior includes:

- 180×320 work geometry, threshold-15 frame differencing, 8-neighbor CCL;
- strict/lenient component geometry and thick gate-band projection;
- four-pixel/eight-frame temporal direction inference;
- sparse-startup, flash, thin-row, broad shadow, and current incoherent
  bodyless scene-motion guards;
- future torso/body waits, limb wait/release, low-contrast fallback, and the
  current x-anchor selector ordering;
- torso-relative strip interpolation and the iOS low-light correction of
  `0.75 × exposureSeconds` above 2 ms;
- a 0.3-second cooldown and ten-frame warmup.

### Validated detector-profile parity

Android now implements the iOS `replica_detection_profile_v1` contract rather
than reading arbitrary detector values directly from remote JSON:

- schema version, `replica_v1` pipeline, revision, enablement, expiry, minimum
  and maximum app version, and percentage rollout are strictly validated;
- rollout uses the same deterministic FNV-1a bucket in the range 0–9,999;
- every supported parameter has the same type, bounds, relationship checks,
  and bundled default as Swift; unknown or mistyped parameters reject the
  entire profile;
- a valid cached profile is applied immediately, a valid network response
  replaces it, and an invalid response preserves the last known-good profile;
- refresh occurs at launch, eligible foreground transitions, and completed
  authentication, while live timing is left undisturbed;
- `DetectionEngine.start()` takes an immutable profile snapshot, so a remote
  refresh cannot change thresholds halfway through a run;
- both current leading-edge logic and the remotely selectable pre-§23 local
  support path are compiled and exercised;
- production timing explicitly keeps the iOS 0.3-second override, while the
  onboarding solo demo uses its profile snapshot like the current iOS app.

Eight profile-validation JVM tests cover overlays, strict rejection, version,
expiry, rollout, stable hashing, and last-known-good behavior. Integration
fixtures prove that profile values change the compiled scene-motion guard and
that both leading-edge and legacy crossing paths still detect a valid
synthetic athlete while rejecting flash motion.

Camera2 now supplies the detector with sensor capture time instead of image
callback time. `REALTIME` camera timestamps are used directly; `UNKNOWN`
sources calibrate against `elapsedRealtimeNanos()` for ten withheld frames.
Per-frame exposure comes from `SENSOR_EXPOSURE_TIME`, with a latest-valid
fallback for result/image callback ordering. Solo timing, race timing, and the
onboarding detector all forward this metadata.

Camera session setup now selects only stream-size/FPS combinations the active
camera actually advertises, prefers the 30 fps range nearest the iOS contract,
and ignores stale asynchronous callbacks after camera/session replacement.

Exact Android fixtures cover current iOS scene-motion false crossings and real
crossing controls, temporal direction, timestamp mapping, and exposure
correction. The matching iOS scene-motion suite also passes. Real sensor
sensitivity still requires the physical matrix below.

## Multi-gate and durable delivery

The Android race flow supports start, lap, finish, and control roles; per-peer
gate assignment; configurable gate distances; per-gate crossing collection;
ordering tolerance; partial-result timeout; segment and cumulative splits;
result broadcast; replay; persistence; and cloud ingestion. Six JVM tests
cover distance parsing/normalization/scaling and four-gate segment math.

Race events are persisted before network delivery with the same deterministic
SHA-256-derived UUID identity as iOS and are sent with idempotent upsert.
Malformed outbox metadata is archived instead of overwritten. Device and
detection-review logs use immutable byte snapshots with independent storage
and metadata stages, retry backoff, process-relaunch persistence, and live
timing deferral. Missing/corrupt snapshots are rejected rather than uploaded.

## Promo access for two-phone testing

Android now uses the same server-owned `redeem_promo_code` and
`get_active_promo_access` RPCs as current iOS. Redemption, usage-limit updates,
and duplicate handling are atomic; the obsolete client-side table update and
unauthenticated admin-notification attempt were removed. A repeated active
redemption is treated as success, and true network failures preserve the last
verified Pro cache for BLE-only testing instead of silently downgrading the
device while offline.

The shared Supabase project contains the dedicated code `ANDROIDTEST2026`. It
grants 30 days of free Pro per device, allows 20 distinct test devices, and
expires August 31, 2026 at 23:59:59 UTC. A public-anon-key contract probe
verified `redeemed`, active-access lookup, and `already_redeemed_active`
behavior. The complete two-phone procedure and installer are in
`docs/ANDROID_TWO_PHONE_E2E.md`.

## Commands and observed results

```text
./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease
BUILD SUCCESSFUL
Android tests: 87; failures: 0; errors: 0; skipped: 0
Android lint: 0 errors; 459 warnings; 2 informational findings
```

The rebuilt `app-debug.apk` installed successfully on the API 36.1 emulator;
`MainActivity` reached RESUMED/visible state and logcat contained no fatal app
exception during the launch smoke test.

```text
iOS TransportSecurityTests + DetectionSceneMotionGuardTests
13 tests; 0 failures

iOS MultiPhoneConnectionRegressionTests
8 tests; 0 failures

iOS ReplicaDetectionConfigurationTests + DetectionSceneMotionGuardTests
8 tests; xcodebuild exit 0
```

`git diff --check` also passes.

## Physical acceptance still required

Use release-equivalent builds and keep a timestamped log bundle for each run:

1. Android host → Android joiner, then reverse roles: pair, sync, arm, start,
   finish, repeat after disconnect/reconnect, and repeat with internet removed.
2. iOS Bluetooth host → Android joiner, then reverse roles: verify version-7
   handshake, role/gate assignment, sync quality, critical ACK, thumbnail, and
   cloud duplicate suppression.
3. Three or more phones: start + one or more lap gates + finish; verify every
   peer receives its assignment, every ACK is peer-specific, and segment totals
   equal the finish-minus-start time.
4. Camera positives: both travel directions, slow and fast athletes, daylight
   and low light, front and back camera.
5. Camera negatives: stationary athlete, phone shake, exposure snap, flash,
   broad moving shadow, background pedestrian, and an arm/hand crossing before
   the torso.
6. Compare Android/iOS crossing timestamps against the same recorded scene and
   flag any systematic bias above the combined reported uncertainty.

Until those physical runs pass, the correct release claim is “software parity
implemented and automated checks passing,” not “BLE and detection proven on
all phone hardware.”

## Known non-blocking hygiene

Lint still reports 459 repository-wide warnings and two informational
findings, dominated by unused resources, available dependency upgrades,
typography, and launcher icon checks. There are no `DefaultLocale`, `NewApi`,
`MissingPermission`, `UnsafeOptInUsageError`, or `WrongConstant` findings, and
lint has zero errors. Newly ported English strings intentionally use Android's
English fallback in existing locales until reviewed translations land;
existing translations were retained.
