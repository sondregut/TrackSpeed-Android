# TrackSpeed Detection Engine

**Implementation:** `detection/DetectionEngine.kt` coordinated by
`detection/GateEngine.kt`

**iOS reference:** current `DetectionEngine.swift`

**Last verified:** July 21, 2026

The active detector is the geometry-based 30 fps engine, not the legacy
`PhotoFinishDetector`/`ZeroAllocCCL` pipeline described by earlier project
documents. The legacy class remains only for a UI state enum.

## Camera contract

`CameraManager` requests a standard Camera2 YUV session locked at 30 fps with
automatic exposure/white balance, fixed focus, HDR/scene mode disabled, and
video stabilization disabled.

Each `FrameData` contains copied Y/U/V planes plus:

- a Camera2 sensor timestamp mapped into `elapsedRealtimeNanos()`;
- `SENSOR_EXPOSURE_TIME` for the frame (or the latest valid exposure as a
  metadata-order fallback);
- the actual row/pixel strides and monotonically increasing frame index.

For cameras whose timestamp source is `REALTIME`, the sensor timestamp is used
directly. For `UNKNOWN`, the minimum sensor-to-callback delta is calibrated for
ten frames, those frames are withheld, and the frozen mapping is then used.
This removes callback-queue latency from crossing interpolation and keeps race
timestamps in the same domain as BLE clock sync.

## Stability gate

`GateEngine` blocks detection while the phone moves:

- preferred sensor: linear acceleration, threshold 0.15 g;
- fallback: gyroscope, threshold 0.35 rad/s;
- recovery: 750 ms below threshold before stable again.

Motion blocking resets detector warmup so an exposure or viewpoint snap after
movement cannot immediately fire.

## Processing geometry and baseline thresholds

```text
process size                 180 × 320 portrait
absolute frame-diff          > 15 luma levels
minimum component height     35% of process height
minimum component width      8% of process width
strict fill / aspect         ≥ 20% / ≤ 1.2
lenient fill / aspect        ≥ 12% / ≤ 1.7
torso sampling row           minY + 30% of component height
gate band                    center column ±2
thick gate projection        center column ±4
warmup                       10 delivered frames
cooldown                     0.3 seconds
```

The internal detection gate is the center process column, matching the current
iOS engine. The displayed/recorded gate position is retained for calibration
and post-hoc line adjustment; dragging it does not move the live detector's
internal center column.

## Validated runtime profile

The table above lists bundled defaults. Current iOS can distribute a validated
data-only profile under the Remote Config key
`replica_detection_profile_v1`; Android implements the same schema and
pipeline (`schemaVersion: 1`, `pipeline: replica_v1`).

`ReplicaDetectionConfiguration` strictly validates the revision, enablement,
expiry, app-version range, deterministic percentage rollout, parameter names,
JSON types, numeric bounds, and cross-parameter relationships. Any unknown or
invalid value rejects the complete candidate. A bad network response never
replaces a valid cached profile, and an absent or ineligible candidate selects
the bundled profile.

`DetectionEngine.start()` copies the current profile into a session-local
snapshot. A foreground/config refresh therefore affects the next timing run,
not a run already in progress. Production timing retains the explicit iOS
0.3-second cooldown override. The onboarding solo demo intentionally uses the
profile cooldown. The `useLeadingEdgeTrigger` flag selects either current §23
leading-edge support or the compiled pre-§23 local-support branch; both paths
remain subject to the shared safety and scene-motion guards.

## Per-frame pipeline

1. Normalize portrait/landscape Y-plane orientation and downsample to 180×320.
2. Compute absolute luma difference against the previous frame and build the
   binary mask at threshold 15.
3. Run allocation-controlled 8-neighbor connected-component labeling with
   union-find.
4. Reject flash/full-frame motion and prefilter components by size, fill, and
   aspect. A component with qualifying vertical gate support can use the
   lenient shape tier.
5. Pick the largest qualifying component and compute its gate-band projection.
   The merged vertical run must be at least
   `max(30, 0.25 × componentHeight)`; gaps up to two pixels are merged.
6. Infer direction from recent component centers. A movement of at least four
   process pixels is required, using at most the previous eight frames.
7. Apply temporal body/torso waits, limb-release logic, sparse-startup guard,
   head-snag handling, low-contrast body fallback, and incoherent scene-motion
   rejection in the same order as current iOS.
8. Select the body x-anchor using current, detection-row, bounding-box, torso,
   and vertically substantial body-front candidates. Rule order is significant
   and is covered by parity fixtures.
9. Use local strip width at the torso-relative row to interpolate the crossing
   fraction between the previous and current frame.
10. For exposure above 2 ms, add `0.75 × exposureSeconds`, matching the iOS
    low-light timing correction.

## Important rejection behavior

The current iOS regression guards are intentionally part of functional logic:

- broad, fragmented, position-independent scene motion is rejected;
- tall but one-pixel-thin gate-row motion is rejected during weak buildup;
- sparse startup motion without a credible torso is rejected;
- a future torso/body-front candidate can defer firing until the body reaches
  the line;
- short leading arms/hands do not replace a vertically substantial torso/body
  front;
- the low-contrast fallback requires a multi-frame sequence and strong merged
  gate-band support.

Do not reorder these checks during cleanup. Several rules distinguish a true
fast crossing from a single-frame lighting/shadow transition using the state
built by earlier rules.

## Timing result

The detector returns:

- interpolated monotonic crossing time;
- interpolation fraction and previous/current frame choice;
- direction and process-space velocity/geometry diagnostics;
- the x-anchor rule selected for the crossing.

`GateEngine` uses the interpolation fraction to choose the previous or current
Y frame for the grayscale crossing thumbnail and adds the slit to the
photo-finish composite. Multi-device code then converts the local monotonic
timestamp with the established clock offset.

## Verification

The Android unit suite includes exact fixtures for:

- the July 16 incoherent scene-motion rejection and real-crossing control;
- temporal direction inference;
- Camera2 timestamp-domain mapping;
- low-light exposure correction;
- clock-offset sign and estimator guards;
- strict remote-profile validation and stable rollout hashing;
- profile-driven current/legacy detector integration with flash rejection.

The future-body/torso waits, low-contrast fallback, and x-anchor selector order
were source-audited against current Swift. They should gain additional direct
fixture coverage as new iOS production fixtures become available.

The same iOS `DetectionSceneMotionGuardTests` are run as a reference check.
Synthetic/unit tests cannot prove sensitivity on every camera sensor. Physical
acceptance still requires recorded and live passes on representative Android
devices in daylight, low light, both directions, and against camera shake and
broad-shadow negative controls.
