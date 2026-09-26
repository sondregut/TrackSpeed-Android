# Experimental Detection Mode - Android Design Document

> **STATUS: DESIGN ONLY -- NOT IMPLEMENTED**
>
> This document describes a 60fps blob tracking detection mode that was designed but never implemented in source code. The actual app uses **Photo Finish mode** (see `DETECTION_ALGORITHM.md`). This document is preserved for reference in case the experimental mode is implemented in the future.
>
> **Key differences from actual codebase:**
> - References `HighSpeedCameraManager` which does not exist (the app uses `CameraManager` with standard sessions)
> - References `CrossingDetector` and `Precision mode` which were never built
> - References `ExperimentalCrossingDetector` and related classes that do not exist in the codebase
> - The Photo Finish mode already implements many of the concepts described here (CCL blob detection, IMU stability gating, frame differencing) but with different architecture

A new "Experimental" detection mode using vertical-blob motion detection at 60fps with automatic scene recovery. This was designed as a third option alongside the Photo Finish mode.

## Overview

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Mode name** | `EXPERIMENTAL` | Indicates it's new and being tested |
| **Target FPS** | 60fps | Low thermal, relies on interpolation for timing |
| **Gate position** | Fixed at frame center | No user setup needed |
| **Interpolation** | Linear sub-frame | Simple, robust |
| **Rolling shutter** | No correction | Simpler, ~few-10ms additional uncertainty |
| **Phone movement** | Auto-recovery | Stabilize → rebuild reference → resume |

---

## Core Algorithm

### Frame Pipeline

```
For each frame:
1. IMU check → if shaking, block detection + show "Hold Steady"
2. If just stabilized → rebuild background reference (scene reset)
3. Extract ROI slab (±12% around center gate line)
4. Frame diff: abs(curr - prev) → threshold → morphology (close/open)
5. Connected components → find blobs
6. Filter blobs: aspect ratio >1.6, height 15-55% of frame
7. Track single "active blob" (nearest centroid match)
8. Compute chest point: densest row in upper 20-60% of blob (horizontal projection)
9. Compute signed distance to gate line
10. Trigger when: sign flips + moving toward line + valid for 3+ frames
11. Linear interpolation: t = t0 + d0/(d0-d1) * (t1-t0)
```

### Key Algorithm Details

#### ROI Slab Extraction
- Gate line is fixed at frame center (x = width/2)
- Slab extends ±12% of frame width around the gate
- Everything outside the slab is ignored (primary background person rejection)

```kotlin
fun extractROISlab(
    luminanceBuffer: ByteArray,
    frameWidth: Int,
    frameHeight: Int,
    roiBuffer: FloatArray  // Pre-allocated
): Int {
    val slabHalfWidth = (frameWidth * ROI_SLAB_WIDTH_FRACTION).toInt()
    val gateX = frameWidth / 2
    val roiStartX = gateX - slabHalfWidth
    val roiEndX = gateX + slabHalfWidth
    val roiWidth = roiEndX - roiStartX

    var idx = 0
    for (y in 0 until frameHeight) {
        for (x in roiStartX until roiEndX) {
            val pixel = luminanceBuffer[y * frameWidth + x].toInt() and 0xFF
            roiBuffer[idx++] = pixel.toFloat()
        }
    }

    return roiWidth
}
```

#### Motion Mask Computation (Adaptive Threshold)

**Problem with fixed threshold:** A hardcoded `threshold = 20` fails across:
- Different ISO/exposure settings
- Cloudy vs sunny conditions
- Indoor vs outdoor tracks
- Different device processing pipelines

**Solution:** Compute adaptive threshold per-frame based on ROI statistics.

```kotlin
data class MotionMaskResult(
    val mask: ByteArray,  // UInt8: 0 = no motion, 255 = motion
    val threshold: Float,
    val coverage: Float   // Fraction of pixels with motion
)

fun computeMotionMask(
    current: FloatArray,
    previous: FloatArray,
    diffBuffer: FloatArray,    // Pre-allocated
    maskBuffer: ByteArray      // Pre-allocated
): MotionMaskResult {
    val size = current.size

    // 1. Compute per-pixel difference and statistics in one pass
    var sum = 0f
    var sumSq = 0f
    for (i in 0 until size) {
        val diff = abs(current[i] - previous[i])
        diffBuffer[i] = diff
        sum += diff
        sumSq += diff * diff
    }

    val mean = sum / size
    val variance = (sumSq / size) - (mean * mean)
    val std = sqrt(variance)

    // 2. Adaptive threshold: mean + k * std
    val k = 2.5f
    var adaptiveThreshold = mean + k * std

    // 3. Clamp to sensible range
    adaptiveThreshold = adaptiveThreshold.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)

    // 4. Apply threshold
    var motionCount = 0
    for (i in 0 until size) {
        if (diffBuffer[i] > adaptiveThreshold) {
            maskBuffer[i] = 255.toByte()
            motionCount++
        } else {
            maskBuffer[i] = 0
        }
    }

    val coverage = motionCount.toFloat() / size

    return MotionMaskResult(maskBuffer, adaptiveThreshold, coverage)
}

companion object {
    const val THRESHOLD_MIN = 10f
    const val THRESHOLD_MAX = 50f
}
```

#### Performance: Downsampled Morphology

**Problem:** Naïve 5×5 close+open on a mask array is expensive. At 60fps with a large ROI, this can be a bottleneck.

**Solution:** Downsample the ROI before processing, then map blob bbox back up.

```kotlin
object MorphologyConfig {
    const val DOWNSAMPLE_FACTOR = 2
    const val KERNEL_SIZE = 3  // 3x3 at half-res ≈ 6x6 at full-res
}

fun processROIWithDownsampling(
    roiPixels: FloatArray,
    roiWidth: Int,
    roiHeight: Int
): List<TrackedBlob> {
    // 1. Downsample ROI (2x2 block averaging)
    val dsWidth = roiWidth / MorphologyConfig.DOWNSAMPLE_FACTOR
    val dsHeight = roiHeight / MorphologyConfig.DOWNSAMPLE_FACTOR

    for (dy in 0 until dsHeight) {
        for (dx in 0 until dsWidth) {
            var sum = 0f
            for (by in 0 until MorphologyConfig.DOWNSAMPLE_FACTOR) {
                for (bx in 0 until MorphologyConfig.DOWNSAMPLE_FACTOR) {
                    val sx = dx * MorphologyConfig.DOWNSAMPLE_FACTOR + bx
                    val sy = dy * MorphologyConfig.DOWNSAMPLE_FACTOR + by
                    sum += roiPixels[sy * roiWidth + sx]
                }
            }
            downsampledBuffer[dy * dsWidth + dx] = sum / 4f
        }
    }

    // 2. Compute motion mask at reduced resolution
    // ... (use pre-allocated buffers)

    // 3. Morphology at reduced resolution (much faster!)
    applyMorphology(maskBuffer, dsWidth, dsHeight, MorphologyConfig.KERNEL_SIZE)

    // 4. Find blobs at reduced resolution
    val dsBlobs = findConnectedComponents(maskBuffer, dsWidth, dsHeight)

    // 5. Scale bboxes back to full resolution
    return dsBlobs.map { blob ->
        TrackedBlob(
            bbox = RectF(
                blob.bbox.left * MorphologyConfig.DOWNSAMPLE_FACTOR,
                blob.bbox.top * MorphologyConfig.DOWNSAMPLE_FACTOR,
                blob.bbox.right * MorphologyConfig.DOWNSAMPLE_FACTOR,
                blob.bbox.bottom * MorphologyConfig.DOWNSAMPLE_FACTOR
            ),
            centroid = PointF(
                blob.centroid.x * MorphologyConfig.DOWNSAMPLE_FACTOR,
                blob.centroid.y * MorphologyConfig.DOWNSAMPLE_FACTOR
            ),
            aspectRatio = blob.aspectRatio,
            heightFraction = blob.heightFraction
        )
    }
}
```

#### Thermal Management (Keep Phone Cool)

**Why this matters:** Continuous camera processing makes the CPU/GPU work hard → phone heats up → Android throttles performance → frame drops, laggy detection.

**Solution: Pre-allocate fixed buffers, reuse every frame**

```kotlin
class ExperimentalCrossingDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // MARK: - Pre-allocated Buffers (allocated ONCE, reused every frame)

    /** ROI luma buffer (full resolution) */
    private lateinit var roiBuffer: FloatArray

    /** Downsampled ROI buffer */
    private lateinit var downsampledBuffer: FloatArray

    /** Previous frame for differencing */
    private lateinit var previousFrameBuffer: FloatArray

    /** Motion mask buffer (UInt8 for efficiency) */
    private lateinit var maskBuffer: ByteArray

    /** Temporary buffer for morphology operations */
    private lateinit var morphTempBuffer: ByteArray

    /** Horizontal projection buffer for chest detection */
    private lateinit var projectionBuffer: IntArray

    /** Diff buffer for adaptive threshold calculation */
    private lateinit var diffBuffer: FloatArray

    /** Background reference (averaged frames) */
    private lateinit var backgroundReference: FloatArray

    /** Background accumulator for averaging */
    private lateinit var backgroundAccumulator: FloatArray

    /** Actual ROI dimensions */
    private var roiWidth: Int = 0
    private var roiHeight: Int = 0
    private var buffersAllocated = false

    /**
     * Allocate buffers based on ACTUAL capture dimensions.
     * Called once on first frame.
     */
    fun allocateBuffersIfNeeded(frameWidth: Int, frameHeight: Int) {
        if (buffersAllocated) return

        // Compute actual ROI size (±12% = 24% total width)
        roiWidth = (frameWidth * ROI_SLAB_WIDTH_FRACTION * 2).toInt()
        roiHeight = frameHeight

        val roiSize = roiWidth * roiHeight
        val downsampledSize = (roiWidth / 2) * (roiHeight / 2)

        // Allocate all buffers ONCE
        roiBuffer = FloatArray(roiSize)
        downsampledBuffer = FloatArray(downsampledSize)
        previousFrameBuffer = FloatArray(roiSize)
        maskBuffer = ByteArray(downsampledSize)
        morphTempBuffer = ByteArray(downsampledSize)
        diffBuffer = FloatArray(downsampledSize)
        projectionBuffer = IntArray(roiHeight)
        backgroundReference = FloatArray(roiSize)
        backgroundAccumulator = FloatArray(roiSize)

        buffersAllocated = true
        Log.i(TAG, "Buffers allocated: ROI ${roiWidth}×${roiHeight}")
    }

    fun processFrame(frameData: HighSpeedCameraManager.FrameData) {
        // Clear mask buffer (fast memset via Arrays.fill)
        Arrays.fill(maskBuffer, 0)

        // Use pre-allocated buffers throughout pipeline
        // NO: val diff = FloatArray(size)  ← BAD (allocates)
        // YES: use this.diffBuffer          ← GOOD (reuses)
    }
}
```

**Buffer reuse checklist:**

| Buffer | Purpose | Reused? |
|--------|---------|---------|
| `roiBuffer` | Extract luma from frame | ✅ Yes |
| `downsampledBuffer` | 2× downsampled ROI | ✅ Yes |
| `previousFrameBuffer` | Previous frame for diff | ✅ Yes |
| `maskBuffer` | Motion mask (ByteArray) | ✅ Yes |
| `morphTempBuffer` | Morphology temp storage | ✅ Yes |
| `diffBuffer` | Frame difference values | ✅ Yes |
| `projectionBuffer` | Horizontal projection for chest | ✅ Yes |
| `backgroundReference` | Background average | ✅ Yes |
| `backgroundAccumulator` | Sum for averaging | ✅ Yes |

**Result:** Near-zero allocations during steady-state detection → CPU stays cool → no throttling → stable 60fps.

#### Blob Filtering

For each connected component (blob):

```kotlin
data class TrackedBlob(
    val bbox: RectF,
    val centroid: PointF,
    val aspectRatio: Float,
    val heightFraction: Float,
    val area: Int
)

fun filterBlobs(blobs: List<RawBlob>, frameHeight: Int): List<TrackedBlob> {
    return blobs.mapNotNull { blob ->
        val width = blob.bbox.width()
        val height = blob.bbox.height()
        val aspectRatio = height / width
        val heightFraction = height / frameHeight

        // Filter criteria
        val validAspect = aspectRatio in MIN_ASPECT_RATIO..MAX_ASPECT_RATIO
        val validHeight = heightFraction in MIN_HEIGHT_FRACTION..MAX_HEIGHT_FRACTION
        val validArea = blob.area >= MIN_BLOB_AREA

        if (validAspect && validHeight && validArea) {
            TrackedBlob(
                bbox = blob.bbox,
                centroid = blob.centroid,
                aspectRatio = aspectRatio,
                heightFraction = heightFraction,
                area = blob.area
            )
        } else null
    }
}

companion object {
    const val MIN_ASPECT_RATIO = 1.6f   // Reject horizontal shapes
    const val MAX_ASPECT_RATIO = 4.0f   // Reject thin vertical lines
    const val MIN_HEIGHT_FRACTION = 0.16f  // ~10ft max distance
    const val MAX_HEIGHT_FRACTION = 0.55f  // Reject too close / shake
    const val MIN_BLOB_AREA = 100       // Reject tiny noise
}
```

#### Chest Point Calculation (Density-Based)

```kotlin
/**
 * Compute chest point using horizontal projection.
 * Finds densest band in upper 20-60% of blob (torso/chest area).
 */
fun computeChestPoint(
    dsMask: ByteArray,
    dsBbox: RectF,
    dsWidth: Int,
    projectionBuffer: IntArray,  // Pre-allocated
    downsampleFactor: Int
): PointF {
    // 1. Compute horizontal projection on downsampled mask
    val bboxHeight = dsBbox.height().toInt()
    Arrays.fill(projectionBuffer, 0, bboxHeight, 0)

    for (row in 0 until bboxHeight) {
        val globalRow = dsBbox.top.toInt() + row
        for (col in dsBbox.left.toInt() until dsBbox.right.toInt()) {
            val idx = globalRow * dsWidth + col
            if (dsMask[idx] != 0.toByte()) {
                projectionBuffer[row]++
            }
        }
    }

    // 2. Search upper 20-60% of blob for densest row
    val searchStart = (0.20f * bboxHeight).toInt()
    val searchEnd = maxOf(searchStart + 1, (0.60f * bboxHeight).toInt())

    var densestRow = searchStart
    var maxDensity = 0
    for (row in searchStart until searchEnd) {
        if (projectionBuffer[row] > maxDensity) {
            maxDensity = projectionBuffer[row]
            densestRow = row
        }
    }

    // 3. Return chest point SCALED BACK to full resolution
    val dsChestY = dsBbox.top + densestRow
    val dsChestX = dsBbox.centerX()

    return PointF(
        dsChestX * downsampleFactor,
        dsChestY * downsampleFactor
    )
}
```

#### Crossing Detection (Direction-Aware)

```kotlin
enum class ApproachDirection {
    FROM_LEFT,   // Runner starts with negative distance (left of gate)
    FROM_RIGHT   // Runner starts with positive distance (right of gate)
}

private var approachDirection: ApproachDirection? = null

/** Called when transitioning ACQUIRE → TRACKING */
fun determineApproachDirection(initialDistance: Float) {
    approachDirection = if (initialDistance < 0) {
        ApproachDirection.FROM_LEFT
    } else {
        ApproachDirection.FROM_RIGHT
    }
}

fun checkSignFlip(previousDistance: Float, currentDistance: Float): Boolean {
    val direction = approachDirection ?: return false

    return when (direction) {
        ApproachDirection.FROM_LEFT ->
            previousDistance < 0 && currentDistance >= 0
        ApproachDirection.FROM_RIGHT ->
            previousDistance > 0 && currentDistance <= 0
    }
}

// Trigger conditions (ALL must be true):
fun checkTriggerConditions(): Boolean {
    val signFlipped = checkSignFlip(previousDistance, currentDistance)
    val movingToward = abs(velocityNorm) > MIN_VELOCITY_NORM
    val validBlob = consecutiveValidFrames >= MIN_VALID_FRAMES
    val notInCooldown = SystemClock.elapsedRealtimeNanos() - lastTriggerTime > COOLDOWN_NANOS

    return signFlipped && movingToward && validBlob && notInCooldown
}
```

#### Velocity Definition (Resolution-Independent)

```kotlin
/**
 * Velocity in pixels per second.
 */
fun computeVelocityPixelsPerSec(
    previousDistance: Float,
    currentDistance: Float,
    dtSeconds: Float
): Float {
    return (currentDistance - previousDistance) / dtSeconds
}

/**
 * Normalized velocity in ROI-widths per second (resolution-independent).
 */
fun computeNormalizedVelocity(
    velocityPixelsPerSec: Float,
    roiWidth: Float
): Float {
    return velocityPixelsPerSec / roiWidth
}

// Usage:
val dtSeconds = 1f / 60f  // 60fps
val vPixels = computeVelocityPixelsPerSec(d0, d1, dtSeconds)
val vNorm = computeNormalizedVelocity(vPixels, roiWidth.toFloat())

// Check: abs(vNorm) > MIN_VELOCITY_NORM (e.g., 0.25 ROI-widths/sec)
val movingToward = abs(vNorm) > MIN_VELOCITY_NORM

companion object {
    const val MIN_VELOCITY_NORM = 0.25f  // ROI-widths per second
}
```

#### Linear Sub-Frame Interpolation

```kotlin
/**
 * Compute interpolated crossing time.
 *
 * @param t0 Timestamp of previous frame (nanos)
 * @param t1 Timestamp of current frame (nanos)
 * @param d0 Distance at t0 (signed)
 * @param d1 Distance at t1 (signed, opposite sign)
 * @return Interpolated crossing timestamp (nanos)
 */
fun interpolateCrossingTime(t0: Long, t1: Long, d0: Float, d1: Float): Long {
    // Where d crosses zero: alpha = d0 / (d0 - d1)
    val alpha = d0 / (d0 - d1)
    val crossingTime = t0 + (alpha * (t1 - t0)).toLong()
    return crossingTime
}
```

---

## State Machine

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│    MOVING (phone shaking)                                       │
│    └─► show "Hold Steady", block detection, clear tracker       │
│         │                                                       │
│         ▼ (IMU stable for 0.4s)                                │
│                                                                 │
│    STABILIZING                                                  │
│    └─► rebuild background reference, wait for quiet scene       │
│         │                                                       │
│         ▼ (motion mask < 20% of ROI for 5+ frames)             │
│                                                                 │
│    ACQUIRE                                                      │
│    └─► looking for valid vertical blob in slab                  │
│         │                                                       │
│         ▼ (blob found + passes filters)                        │
│                                                                 │
│    TRACKING                                                     │
│    └─► computing d(t) each frame, checking velocity             │
│         │                                                       │
│         ▼ (sign change + valid N frames + toward-line velocity) │
│                                                                 │
│    TRIGGERED                                                    │
│    └─► compute interpolated time, fire callback                 │
│         │                                                       │
│         ▼ (after 1.5s cooldown)                                │
│                                                                 │
│    COOLDOWN ──────────────────────────────────► ACQUIRE         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

State transitions on phone movement:
- ANY state + IMU shake detected → MOVING
```

### State Definitions

```kotlin
enum class DetectionState {
    MOVING,       // Phone is shaking, all detection blocked
    STABILIZING,  // Phone just stopped, rebuilding background
    ACQUIRE,      // Looking for valid blob
    TRACKING,     // Tracking blob, checking for crossing
    TRIGGERED,    // Crossing detected, computing time
    COOLDOWN      // Post-trigger cooldown
}
```

**Explicit MOVING behavior:**
When phone is moving:
1. **Block all crossing detection**
2. **Clear tracker completely** - `activeBlob = null`, `distanceHistory.clear()`, `approachDirection = null`
3. **Ignore frames** - do not process for motion mask or blob detection
4. **Reset all counters** - `consecutiveValidFrames = 0`, `quietSceneFrameCount = 0`

---

## Algorithm Parameters

### Starting Values

| Parameter | Value | Purpose | Tune Range |
|-----------|-------|---------|------------|
| **Target FPS** | 60 | Balance thermal vs precision | 60-120 |
| **ROI slab width** | ±12% frame | Focus on gate area | 8-18% |
| **Adaptive threshold k** | 2.5 | Multiplier for std dev | 2.0-3.5 |
| **Threshold min clamp** | 10 | Minimum adaptive threshold | 8-15 |
| **Threshold max clamp** | 50 | Maximum adaptive threshold | 40-60 |
| **Morphology kernel** | 3×3 | Noise cleanup (at downsampled res) | 3×3 to 5×5 |
| **Morphology downsample** | 2 | Process at 1/2 resolution | 1-4 |
| **Min aspect ratio** | 1.6 | Reject horizontal shapes | 1.4-2.0 |
| **Max aspect ratio** | 4.0 | Reject thin vertical lines | 3.0-5.0 |
| **Min height fraction** | 0.16 | Distance gate (~10ft max) | 0.12-0.20 |
| **Max height fraction** | 0.55 | Reject camera shake / too close | 0.50-0.65 |
| **Tracking max distance** | 0.06 | Blob association (fraction of ROI) | 0.03-0.10 |
| **Min valid frames** | 4 | Frames blob must pass filters | 3-6 |
| **Stabilize duration** | 0.4s | Time phone must be still | 0.3-0.8s |
| **Quiet scene threshold** | 20% | Max motion mask coverage | 15-30% |
| **Cooldown duration** | 1.5s | Ignore detection after trigger | 1.0-2.0s |
| **Gyro shake threshold** | 0.5 rad/s | Gyroscope for "shaking" (PRIMARY) | 0.3-0.8 |
| **Accel shake threshold** | 0.25g | Accelerometer (SECONDARY) | 0.15-0.35g |
| **IMU stable readings** | 24 | Consecutive stable (at 60Hz) | 15-30 |
| **Background frames** | 15 | Frames for background average | 10-20 |
| **Chest search start** | 0.20 | Start of density search | 0.15-0.25 |
| **Chest search end** | 0.60 | End of density search | 0.50-0.70 |
| **Min velocity (vNorm)** | 0.25 | ROI-widths per second | 0.15-0.50 |

---

## IMU Shake Detection (Android)

Uses Android `SensorManager` with gyroscope (primary) and accelerometer (secondary).

```kotlin
@Singleton
class IMUShakeDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    companion object {
        private const val TAG = "IMUShakeDetector"
        const val GYRO_SHAKE_THRESHOLD = 0.5f      // rad/s - PRIMARY
        const val ACCEL_SHAKE_THRESHOLD = 2.45f    // m/s² (0.25g) - SECONDARY
        const val MIN_STABLE_READINGS = 24         // At 60Hz = 0.4s
        const val SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME  // ~60Hz
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private var consecutiveStableReadings = 0

    private val _isShaking = MutableStateFlow(true)  // Start as shaking
    val isShaking: StateFlow<Boolean> = _isShaking.asStateFlow()

    private var onShakeDetected: (() -> Unit)? = null
    private var onStabilized: (() -> Unit)? = null

    private var lastGyroMagnitude = 0f
    private var lastAccelMagnitude = 0f

    fun start(onShake: () -> Unit, onStable: () -> Unit) {
        onShakeDetected = onShake
        onStabilized = onStable

        gyroscope?.let {
            sensorManager.registerListener(this, it, SENSOR_DELAY)
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SENSOR_DELAY)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                lastGyroMagnitude = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                lastAccelMagnitude = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
            }
        }

        // Phone is shaking if EITHER threshold exceeded
        val isCurrentlyShaking = lastGyroMagnitude > GYRO_SHAKE_THRESHOLD ||
                                 lastAccelMagnitude > ACCEL_SHAKE_THRESHOLD

        if (isCurrentlyShaking) {
            consecutiveStableReadings = 0
            if (!_isShaking.value) {
                _isShaking.value = true
                onShakeDetected?.invoke()
            }
        } else {
            consecutiveStableReadings++
            if (consecutiveStableReadings >= MIN_STABLE_READINGS && _isShaking.value) {
                _isShaking.value = false
                onStabilized?.invoke()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
```

---

## Scene Reset Logic

When transitioning from MOVING → STABILIZING:

```kotlin
private var backgroundAccumulator = FloatArray(0)
private var backgroundFrameCount = 0
private var backgroundReference = FloatArray(0)
private var quietSceneFrameCount = 0

companion object {
    const val BACKGROUND_FRAMES_NEEDED = 15
    const val MAX_MOTION_FOR_LEARNING = 0.10f  // 10%
    const val QUIET_SCENE_THRESHOLD = 0.20f    // 20%
    const val QUIET_SCENE_FRAMES_NEEDED = 5
}

fun performSceneReset() {
    // 1. Clear all tracking state
    activeBlob = null
    distanceHistory.clear()
    velocityHistory.clear()
    consecutiveValidFrames = 0
    approachDirection = null

    // 2. Reset background accumulator
    Arrays.fill(backgroundAccumulator, 0f)
    backgroundFrameCount = 0
    Arrays.fill(backgroundReference, 0f)

    // 3. Reset quiet scene check
    quietSceneFrameCount = 0
}

/**
 * Called each frame during STABILIZING state.
 * Only accumulate QUIET frames (no moving people in slab).
 */
fun accumulateBackground(roiPixels: FloatArray, motionCoverage: Float) {
    // Guard: Don't learn moving people into background!
    if (motionCoverage > MAX_MOTION_FOR_LEARNING) {
        return  // Skip this frame
    }

    if (backgroundFrameCount == 0) {
        // First quiet frame - initialize accumulator
        System.arraycopy(roiPixels, 0, backgroundAccumulator, 0, roiPixels.size)
        backgroundFrameCount = 1
    } else if (backgroundFrameCount < BACKGROUND_FRAMES_NEEDED) {
        // Accumulate into running sum
        for (i in roiPixels.indices) {
            backgroundAccumulator[i] += roiPixels[i]
        }
        backgroundFrameCount++
    }

    // Check if we have enough QUIET frames
    if (backgroundFrameCount >= BACKGROUND_FRAMES_NEEDED && !isBackgroundReady) {
        // Compute average
        for (i in backgroundAccumulator.indices) {
            backgroundReference[i] = backgroundAccumulator[i] / backgroundFrameCount
        }
        Log.i(TAG, "Background reference built from $backgroundFrameCount quiet frames")
    }
}

val isBackgroundReady: Boolean
    get() = backgroundFrameCount >= BACKGROUND_FRAMES_NEEDED

fun checkQuietScene(motionCoverage: Float): Boolean {
    if (!isBackgroundReady) return false

    if (motionCoverage < QUIET_SCENE_THRESHOLD) {
        quietSceneFrameCount++
    } else {
        quietSceneFrameCount = 0
    }
    return quietSceneFrameCount >= QUIET_SCENE_FRAMES_NEEDED
}
```

---

## Implementation Plan

### Files to Create

| File | Purpose |
|------|---------|
| `detection/ExperimentalCrossingDetector.kt` | Main detector class |
| `detection/ExperimentalConfig.kt` | Configuration constants |
| `detection/IMUShakeDetector.kt` | IMU-based shake detection |
| `detection/BlobTracker.kt` | Blob tracking logic |
| `detection/MorphologyUtils.kt` | Morphology operations |
| `ui/components/ExperimentalDebugOverlay.kt` | Debug visualization |

### Files to Modify

| File | Changes |
|------|---------|
| `detection/GateEngine.kt` | Add `EXPERIMENTAL` mode, integrate detector |
| `camera/HighSpeedCameraManager.kt` | Add 60fps configuration |
| `ui/screens/timing/BasicTimingScreen.kt` | Add mode picker |
| `detection/DetectionConfig.kt` | Add experimental constants |

### ExperimentalCrossingDetector.kt Structure

```kotlin
@Singleton
class ExperimentalCrossingDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imuShakeDetector: IMUShakeDetector
) {
    companion object {
        private const val TAG = "ExperimentalDetector"
    }

    // MARK: - Types
    enum class State {
        MOVING,
        STABILIZING,
        ACQUIRE,
        TRACKING,
        TRIGGERED,
        COOLDOWN
    }

    data class TrackedBlob(
        val bbox: RectF,
        val chestPoint: PointF,
        val aspectRatio: Float,
        val heightFraction: Float,
        val centroid: PointF,
        val area: Int
    )

    data class CrossingEvent(
        val timestamp: Long,
        val frameIndex: Long,
        val interpolatedTimestamp: Long
    )

    // MARK: - State
    private val _state = MutableStateFlow(State.MOVING)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _crossingEvents = MutableSharedFlow<CrossingEvent>()
    val crossingEvents: SharedFlow<CrossingEvent> = _crossingEvents.asSharedFlow()

    // MARK: - Pre-allocated Buffers
    private lateinit var roiBuffer: FloatArray
    private lateinit var downsampledBuffer: FloatArray
    // ... etc (see Thermal Management section)

    // MARK: - Tracking State
    private var activeBlob: TrackedBlob? = null
    private val distanceHistory = ArrayDeque<Pair<Long, Float>>(10)
    private var approachDirection: ApproachDirection? = null
    private var consecutiveValidFrames = 0
    private var lastTriggerTimeNanos = 0L

    // MARK: - Lifecycle
    fun start()
    fun stop()
    fun reset()

    // MARK: - Main Processing
    fun processFrame(frameData: HighSpeedCameraManager.FrameData)

    // MARK: - Pipeline Steps
    private fun extractROISlab(luminance: ByteArray, width: Int, height: Int): Int
    private fun computeMotionMask(current: FloatArray, previous: FloatArray): MotionMaskResult
    private fun applyMorphology(mask: ByteArray, width: Int, height: Int)
    private fun findConnectedComponents(mask: ByteArray, width: Int, height: Int): List<RawBlob>
    private fun filterBlobs(blobs: List<RawBlob>): List<TrackedBlob>
    private fun trackBlob(candidates: List<TrackedBlob>): TrackedBlob?
    private fun computeChestPoint(mask: ByteArray, bbox: RectF, roiWidth: Int): PointF
    private fun computeSignedDistance(chestPoint: PointF): Float
    private fun checkTriggerConditions(): Boolean
    private fun interpolateCrossingTime(t0: Long, t1: Long, d0: Float, d1: Float): Long

    // MARK: - Scene Reset
    private fun performSceneReset()
    private fun accumulateBackground(roiPixels: FloatArray, motionCoverage: Float)
    private fun checkQuietScene(motionCoverage: Float): Boolean
}
```

---

## Comparison: All Three Modes

| Feature | Precision | Simple | Experimental |
|---------|-----------|--------|--------------|
| **Algorithm** | Background model + pose | Frame diff + blob | Frame diff + blob tracking |
| **Pose detection** | Yes (ML Kit) | No | No |
| **Calibration** | Required (~1.5s) | None | None |
| **FPS** | 240 | 240 | **60** |
| **ROI handling** | 3 strips (narrow) | 8px band | **±12% slab** |
| **Blob tracking** | Via pose keypoints | None (single frame) | **Cross-frame tracking** |
| **Aspect filter** | Via pose shape | None | **Yes (>1.6)** |
| **Interpolation** | Quadratic | None | **Linear** |
| **Rolling shutter** | Yes (sub-ms) | No | No |
| **Scene reset** | Manual re-calibrate | Manual | **Automatic** |
| **Thermal load** | High | High | **Low** |
| **Timing precision** | Best (few ms) | Frame-level (~4ms) | **~10-20ms typical** |
| **Best for** | Max accuracy | Quick & simple | Low thermal + auto-recovery |

---

## Testing Checklist

### Basic Functionality
- [ ] Build succeeds with no warnings
- [ ] No regressions in Precision mode
- [ ] No regressions in Simple mode (if exists)
- [ ] Mode picker shows all options

### Detection Accuracy
- [ ] Triggers at 2ft distance
- [ ] Triggers at 5ft distance
- [ ] Triggers at 8ft distance
- [ ] Triggers at 10ft distance
- [ ] Rejects at 12ft+ distance (too far)

### False Positive Rejection
- [ ] Ignores background people outside slab
- [ ] Ignores horizontal arm movements
- [ ] Does NOT trigger during phone shake
- [ ] Does NOT trigger on lighting changes

### Scene Recovery
- [ ] Shows "Hold Steady" while phone moving
- [ ] Automatically recovers after phone placed down
- [ ] Stabilization takes ~0.4-0.8s
- [ ] Detection works correctly after scene change

### Thermal & Performance
- [ ] Camera runs at 60fps (not higher)
- [ ] Phone stays cool after 5 minutes
- [ ] Frame processing completes in <10ms

### Timing Accuracy
- [ ] Compare timing vs Precision mode
- [ ] Linear interpolation produces reasonable times
- [ ] Timing consistent across multiple runs

---

## Debug Overlay Features

The debug overlay should show:

**Visual Elements:**
1. ROI slab boundaries - Two vertical lines showing ±12% zone
2. Gate line - Center line where crossing is detected
3. Detected blob bbox - Rectangle around current blob
4. Chest point - Dot at calculated chest position
5. Motion mask (toggle) - Visualize what's being detected

**Numeric Metrics:**
6. State indicator - Current state (MOVING/STABILIZING/etc)
7. Signed distance - Number showing distance to gate
8. Blob metrics - Height fraction, aspect ratio
9. Adaptive threshold - Current computed threshold value
10. IMU readings - Gyro (rad/s), Accel (m/s²)
11. Background progress - "Building: 8/15 frames"
12. FPS - Actual processing frame rate

---

## Android-Specific Considerations

### Camera Configuration for 60fps

```kotlin
// In HighSpeedCameraManager.kt
fun configure60fps(): Boolean {
    // Find 60fps configuration (not high-speed)
    val configMap = characteristics.get(
        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
    ) ?: return false

    // Check for 60fps in regular (non-constrained) mode
    val fpsRanges = characteristics.get(
        CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
    )

    val range60fps = fpsRanges?.find { it.upper == 60 }
    if (range60fps != null) {
        selectedFpsRange = range60fps
        isHighSpeedSupported = false  // Use regular session
        return true
    }

    return false
}
```

### Thread Safety

```kotlin
// Use AtomicReference for state
private val stateRef = AtomicReference(State.MOVING)

// Use synchronized for buffer access if needed
@Synchronized
private fun swapBuffers() {
    val temp = roiBuffer
    roiBuffer = previousFrameBuffer
    previousFrameBuffer = temp
}
```

### Memory Management

```kotlin
// Clear references on stop to help GC
fun stop() {
    imuShakeDetector.stop()

    // Don't deallocate buffers (keep for restart)
    // Just clear tracking state
    activeBlob = null
    distanceHistory.clear()
}
```

---

## References

- iOS design document: `speed-swift/docs/EXPERIMENTAL_DETECTION_MODE.md`
- Photo Finish app behavior for scene recovery model
- Existing `GateEngine.kt` for callback patterns
- Existing `CrossingDetector.kt` for state machine patterns
- Existing `HighSpeedCameraManager.kt` for camera patterns
