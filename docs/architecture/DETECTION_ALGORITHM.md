# TrackSpeed Android - Photo Finish Detection Algorithm

**Version:** 2.0
**Last Updated:** February 2026

This document details the Photo Finish detection mode as implemented in the Android app, ported from the iOS `PhotoFinishDetector.swift`.

> **Note:** This replaces the original Precision mode specification (v1.0) which described a 240fps + background model + ML Kit pose detection approach. That design was never implemented. The actual algorithm uses frame differencing with connected-component labeling at 30-120fps, matching the iOS Photo Finish app behavior.

---

## 1. Overview

The Photo Finish detection mode uses a single-loop architecture processing every camera frame:

```
┌──────────────────────────────────────────────────────────────┐
│                   PHOTO FINISH DETECTION MODE                 │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│   ┌─────────────────────┐      ┌─────────────────────┐      │
│   │   IMU (Gyroscope)   │      │   CAMERA MANAGER    │      │
│   │                     │      │   (30-120 fps)      │      │
│   │ • Stability gate    │      │ • YUV frames        │      │
│   │ • 0.35 rad/s thresh │      │ • Auto-exposure     │      │
│   │ • 0.5s arm delay    │      │ • Focus locked      │      │
│   └─────────┬───────────┘      └─────────┬───────────┘      │
│             │                            │                   │
│             │  isPhoneStable             │  Y-plane luma     │
│             ▼                            ▼                   │
│   ┌──────────────────────────────────────────────────────┐  │
│   │              PHOTO FINISH DETECTOR                    │  │
│   │                                                       │  │
│   │  1. Check IMU → reject if unstable                   │  │
│   │  2. Downsample luma to 160x284 work resolution       │  │
│   │  3. Frame differencing → motion mask                 │  │
│   │  4. CCL (connected component labeling) → blobs       │  │
│   │  5. Size filter (>=33% frame height)                 │  │
│   │  6. Compute chestX via column density scan           │  │
│   │  7. Velocity filter (>=60 px/s at work res)          │  │
│   │  8. Gate crossing check (sign flip)                  │  │
│   │  9. Trajectory regression for sub-frame timing       │  │
│   │  10. Rolling shutter correction                      │  │
│   └──────────────────────────────────────────────────────┘  │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. Configuration Constants

### 2.1 Work Resolution

```kotlin
const val WORK_W = 160   // Downsampled width (portrait)
const val WORK_H = 284   // Downsampled height (portrait)
```

All detection operates on the downsampled frame, not the full camera resolution. This keeps processing fast regardless of camera resolution (720p, 1080p, etc.).

### 2.2 IMU Stability

```kotlin
const val GYRO_THRESHOLD = 0.35            // rad/s - reject frames during shake
const val STABLE_DURATION_TO_ARM_S = 0.5   // Must be stable 0.5s before detection
```

### 2.3 Detection Thresholds

```kotlin
// Blob size
const val MIN_BLOB_HEIGHT_FOR_CROSSING = 0.33f  // 33% of frame height

// Motion mask
const val DEFAULT_DIFF_THRESHOLD = 14    // Pixel difference to count as motion
const val MIN_DIFF_THRESHOLD = 8         // Clamp floor after calibration
const val MAX_DIFF_THRESHOLD = 40        // Clamp ceiling after calibration

// Velocity
const val MIN_VELOCITY_PX_PER_SEC = 60.0f  // At work resolution (160x284)

// Column density for body validation
val MIN_COLUMN_DENSITY_FOR_BODY = (WORK_H * 0.15f).toInt()  // ~42 pixels
const val MIN_REGION_WIDTH_FOR_BODY = 8    // Consecutive dense columns
```

### 2.4 Timing

```kotlin
const val COOLDOWN_DURATION_S = 0.3         // Between crossings
const val WARMUP_DURATION_S = 0.30          // Frames before detection starts
const val REARM_DURATION_S = 0.2            // Frames at hysteresis before rearm
const val MIN_TIME_BETWEEN_CROSSINGS_S = 0.3
const val ARMING_GRACE_PERIOD_S = 0.20      // Ignore crossings right after arming
```

### 2.5 Rearm Hysteresis

```kotlin
const val HYSTERESIS_DISTANCE_FRACTION = 0.25f  // 25% of frame width
const val EXIT_ZONE_FRACTION = 0.35f            // Blob must exit 35% from gate
```

### 2.6 Trajectory

```kotlin
const val TRAJECTORY_BUFFER_SIZE = 6   // Points for linear regression
const val MIN_DIRECTION_CHANGE_PX = 2.0f
```

---

## 3. Adaptive Noise Calibration

During the warmup period (first ~10 frames), the detector collects luminance difference samples and computes an adaptive threshold using MAD (Median Absolute Deviation).

```kotlin
// Collect samples: subsample every 8th pixel from frame-to-frame difference
// After warmup:
val sorted = noiseCalibrationSamples.sorted()
val median = sorted[n / 2]
val absDeviations = sorted.map { abs(it - median) }.sorted()
val mad = absDeviations[n / 2]

// Threshold = median + 3.5 * MAD * 1.4826
// (1.4826 converts MAD to standard deviation equivalent)
val sigma = mad * 1.4826
val rawThreshold = median + 3.5 * sigma
val adaptiveDiffThreshold = rawThreshold.coerceIn(8.0, 40.0).toInt()
```

This adapts to different lighting conditions, camera sensors, and noise levels.

---

## 4. Frame Differencing + Motion Mask

For each frame after warmup:

```kotlin
// Downsample full resolution to 160x284
downsampleLuma(yPlane, width, height, rowStride, currLumaSmall)

// Build binary motion mask
for (idx in 0 until WORK_W * WORK_H) {
    val diff = abs(currLumaSmall[idx] - prevLumaSmall[idx])
    motionMask[idx] = if (diff > adaptiveDiffThreshold) 255 else 0
}
```

If fewer than 50 pixels have motion, the frame is skipped (no athlete present).

---

## 5. Connected Component Labeling (ZeroAllocCCL)

The `ZeroAllocCCL` class implements row-run connected component labeling with:
- **Union-find with path compression** for label equivalence
- **Pre-allocated buffers** for zero steady-state allocations
- **Row-run encoding** (efficient for sparse masks)
- **Blob statistics** (bounding box, centroid, area) accumulated during labeling

```kotlin
class ZeroAllocCCL(width: Int, height: Int, maxLabels: Int = 4096) {
    // Pre-allocated: equivalence table, label stats, run buffers
    // Returns: List<CCLBlob> with bbox, centroid, area, heightFrac
}

data class CCLBlob(
    val bboxMinX: Int, val bboxMinY: Int,
    val bboxWidth: Int, val bboxHeight: Int,
    val centroidX: Float, val centroidY: Float,
    val areaPixels: Int, val heightFrac: Float
)
```

---

## 6. Blob Filtering + Chest Position

### 6.1 Size Validation

The largest blob by area is selected. Its height must be at least 33% of the frame height (`MIN_BLOB_HEIGHT_FOR_CROSSING`). Smaller blobs indicate the athlete is too far away.

### 6.2 Chest X (Leading Edge) Computation

Rather than using the blob centroid, the detector finds the "body edge" -- the leading edge of the torso in the direction of motion.

Algorithm:
1. Determine direction of motion from centroid movement between frames
2. Scan columns from the leading edge inward
3. For each column, find the longest contiguous run of motion pixels
4. A column is "dense" if its longest run >= `MIN_COLUMN_DENSITY_FOR_BODY` (~42px)
5. Need `MIN_REGION_WIDTH_FOR_BODY` (8) consecutive dense columns
6. The first such column is the chest X position

This gives a more precise gate crossing position than the blob centroid.

### 6.3 Column Density Validation at Crossing

When a gate crossing is detected, the detector performs an additional validation:
- Extract the vertical column at the chest X position
- Find the longest contiguous run of motion pixels
- Reject if shorter than `MIN_COLUMN_DENSITY_FOR_BODY` (confirms solid body mass, not a stray arm/leg)

---

## 7. Velocity Filter

Velocity is computed between consecutive frames:

```kotlin
val dt = (ptsNanos - prevTimestamp) / 1_000_000_000.0f  // seconds
val velocityPxPerSec = abs(chestX - prevChestX) / dt
```

Must exceed `MIN_VELOCITY_PX_PER_SEC` (60 px/s at 160x284 work resolution) to trigger. This rejects:
- Static objects in the frame
- Slow-moving background changes
- Hands/arms moving through the gate slowly

---

## 8. Crossing Detection State Machine

### 8.1 States

```kotlin
enum class State {
    UNSTABLE,        // Phone shaking, detection blocked
    NO_ATHLETE,      // No motion or no valid blob
    ATHLETE_TOO_FAR, // Blob too small (< 33% height)
    READY,           // Valid blob, waiting for crossing
    TRIGGERED,       // Crossing just detected
    COOLDOWN         // Post-trigger cooldown (0.3s)
}
```

### 8.2 State Transitions

```
                    isPhoneStable && 0.5s elapsed
    ┌──────────────────────────────────────────────┐
    │                                              │
    ▼                                              │
┌───────────────────┐                      ┌───────────────────┐
│     UNSTABLE      │◄─── gyro > 0.35 ────│     ANY STATE     │
│ "Hold Steady"     │                      │                   │
└─────────┬─────────┘                      └───────────────────┘
          │ stable for 0.5s
          ▼
┌───────────────────┐
│    NO_ATHLETE     │◄── no motion / no blob
│    "Ready"        │
└─────────┬─────────┘
          │ valid blob found
          ▼
┌───────────────────┐
│ ATHLETE_TOO_FAR   │◄── blob < 33% height
│   "Too Far"       │
└─────────┬─────────┘
          │ blob >= 33% height
          ▼
┌───────────────────┐
│      READY        │◄──────────────────────────────────┐
│    "Ready"        │                                    │
└─────────┬─────────┘                                    │
          │ chestX crosses gate line                     │
          │ + velocity >= 60 px/s                        │
          │ + armed + body mass confirmed                │
          ▼                                              │
┌───────────────────┐                                    │
│    TRIGGERED      │                                    │
│                   │                                    │
│ • Compute time    │                                    │
│ • Emit callback   │                                    │
│ • Disarm          │                                    │
└─────────┬─────────┘                                    │
          │                                              │
          ▼                                              │
┌───────────────────┐                                    │
│     COOLDOWN      │                                    │
│     (0.3s)        │────────────────────────────────────┘
└───────────────────┘
```

### 8.3 Rearm Hysteresis

After a crossing, the detector disarms. To rearm:
1. The blob must exit the gate zone (`EXIT_ZONE_FRACTION` = 35% of frame width from gate)
2. Then the blob must move to `HYSTERESIS_DISTANCE_FRACTION` (25%) away from gate
3. Must stay there for `rearmFramesRequired` consecutive frames

Alternatively, if no valid blob is detected for `rearmFramesRequired` frames, the detector rearms automatically.

---

## 9. Sub-Frame Interpolation

### 9.1 Trajectory Regression (Primary)

A circular buffer of 6 `TrajectoryPoint` records (chestX, chestY, timestamp, blobWidth) is maintained. When a crossing triggers, linear regression is performed:

```kotlin
// Fit line: chestX(t) = velocity * t + intercept
// Using least squares on trajectory points
val n = points.size
// ... sum of t, x, t*x, t^2 ...
val velocity = (n * sumTX - sumT * sumX) / denominator
val intercept = (sumX - velocity * sumT) / n

// Solve for gate crossing time
val crossingTimeSeconds = (gateX - intercept) / velocity
val crossingTimeNanos = points[0].timestamp + (crossingTimeSeconds * 1e9).toLong()
```

The regression is rejected if:
- Velocity is too low (abs(velocity) <= 40)
- Crossing time is unreasonable (< -0.15s or >= 0.3s from buffer window)

### 9.2 Two-Frame Linear Interpolation (Fallback)

If fewer than 3 trajectory points or regression fails:

```kotlin
val d0 = abs(prevChestX - gateX)
val d1 = abs(chestX - gateX)
val alpha = d0 / (d0 + d1)
val interpolatedTime = prevTimestamp + (alpha * frameDuration)
```

---

## 10. Rolling Shutter Correction

Rolling shutter cameras read rows sequentially. If the athlete is detected at row Y, that row was captured slightly after the frame's nominal timestamp.

```kotlin
object RollingShutterCalculator {
    // Readout duration estimates (seconds)
    fun getReadoutDuration(isFrontCamera: Boolean, fps: Double): Double {
        return if (isFrontCamera) {
            if (fps >= 100) 0.008 else 0.018
        } else {
            when {
                fps >= 200 -> 0.003
                fps >= 100 -> 0.005
                else -> 0.012
            }
        }
    }

    // Compensation = readoutDuration * (chestY / frameHeight)
    // Added to the interpolated crossing timestamp
    fun calculateCompensationNanos(
        isFrontCamera: Boolean, fps: Double, chestYNormalized: Float
    ): Long
}
```

---

## 11. Complete Frame Processing Pipeline

```kotlin
fun processFrame(yPlane, width, height, rowStride, frameNumber, ptsNanos): DetectionResult {
    // 1. Paused check
    // 2. Frame skip at 120fps (thermal optimization: process every 2nd frame)
    // 3. Cooldown timer check
    // 4. IMU stability gate
    // 5. Early-exit on consecutive zero-motion frames (quick subsample check)
    // 6. Downsample luma to 160x284
    // 7. Warmup period (noise calibration)
    // 8. Build motion mask (frame differencing with adaptive threshold)
    // 9. Connected component labeling → blob list
    // 10. Select largest blob, check height >= 33%
    // 11. Compute chestX (leading edge via column density)
    // 12. Store trajectory point
    // 13. Compute velocity
    // 14. Check rearm hysteresis
    // 15. Check gate crossing (sign flip of distance)
    // 16. Validate crossing (column density, blob size, min time)
    // 17. Compute crossing time (trajectory regression or linear interpolation)
    // 18. Apply rolling shutter correction
    // 19. Emit crossing callback
    // 20. Enter cooldown state
}
```

---

## 12. Debug / UI State

The detector exposes these values for the UI:

```kotlin
data class DetectionResult(
    val triggered: Boolean,
    val blobHeightFraction: Float,
    val blobCenterX: Float?,
    val velocityPxPerSec: Float,
    val motionAmount: Float,       // Fraction of pixels with motion
    val cameraStable: Boolean,
    val rejection: RejectionReason, // NONE, CAMERA_SHAKING, TOO_FAR, TOO_SLOW, NO_BLOB, IN_COOLDOWN
    val state: State
)
```

---

## 13. Differences from Original Precision Mode Specification

The original v1.0 of this document described a "Precision mode" that was designed but never implemented:

| Feature | Precision Mode (v1.0, not built) | Photo Finish Mode (v2.0, actual) |
|---------|----------------------------------|----------------------------------|
| Frame rate | 240fps high-speed | 30-120fps standard |
| Detection | Background model + occupancy | Frame differencing + CCL blobs |
| Pose | ML Kit pose detection at 30Hz | None |
| Torso tracking | Shoulder/hip landmarks + EMA | Column density scan |
| Gate analysis | 3 vertical strips | Full-frame blob analysis |
| Threshold model | Per-row median + MAD background | Per-frame adaptive diff threshold |
| State machine | 5-state with occupancy levels | 6-state with velocity + size |
| Interpolation | Quadratic (3+ samples) | Linear regression (6 trajectory points) |
| Dependencies | ML Kit, high-speed Camera2 | Standard Camera2 only |
| Calibration | Required (30 frames, static scene) | Automatic (warmup noise calibration) |

---

## Appendix: iOS Source File Reference

| Android Component | iOS Source File |
|-------------------|-----------------|
| `PhotoFinishDetector` | `PhotoFinishDetector.swift` |
| `ZeroAllocCCL` | `ZeroAllocCCL.swift` |
| `RollingShutterCalculator` | `RollingShutterCalculator.swift` |
| `GateEngine` | `GateEngine.swift` |
| `CameraManager` | `CameraManager.swift` (Point & Shoot mode) |
