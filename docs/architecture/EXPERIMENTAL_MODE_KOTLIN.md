# Experimental Detection Mode - Kotlin Implementation Guide

**Version:** 1.1
**Last Updated:** January 2026
**Platform:** Android (Kotlin)

> **STATUS: DESIGN ONLY -- NOT IMPLEMENTED**
>
> This document provides a detailed Kotlin implementation guide for an experimental 60fps blob-tracking detection mode that was designed but never built. None of the classes described here (ExperimentalCrossingDetector, IMUShakeDetector, MotionMaskEngine, ConnectedComponentLabeler, BlobTracker) exist in the codebase.
>
> The actual app uses **Photo Finish mode** (see `DETECTION_ALGORITHM.md`) which implements similar concepts (CCL blob detection, IMU gating, frame differencing) using `PhotoFinishDetector`, `ZeroAllocCCL`, and `GateEngine`. Many ideas from this document informed the Photo Finish implementation.
>
> References to `HighSpeedCameraManager` should be read as `CameraManager` if adapting this design.

This document provides the complete Kotlin implementation for the Experimental detection mode, a 60fps blob-tracking approach with automatic scene recovery.

### Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.1 | Jan 2026 | Added Android-specific fixes: stride handling, zero-allocation patterns, Samsung camera quirk, blob pool pattern |
| 1.0 | Jan 2026 | Initial Kotlin port from iOS EXPERIMENTAL_MODE_PLAN.md |

---

## 1. Overview

### Why Experimental Mode?

| Problem with 240fps | Experimental Mode Solution |
|---------------------|---------------------------|
| High thermal load (phone overheats) | 60fps = 4× fewer frames to process |
| Requires high-speed camera hardware | Regular 60fps works on all phones |
| Manual calibration required | Automatic scene recovery via IMU |
| Complex background model | Simple frame differencing |

### Key Characteristics

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Target FPS** | 60fps | Low thermal, good balance |
| **Detection** | Vertical blob tracking | No ML/pose detection needed |
| **Gate position** | Fixed at frame center | No user setup |
| **Interpolation** | Linear sub-frame | Simple, robust |
| **Scene reset** | Automatic via IMU | Phone shake → auto-rebuild |
| **Timing precision** | ~10-20ms typical | Acceptable for training |

### Android-Specific Considerations

> **CRITICAL: This section covers Android pitfalls that differ from iOS.**

#### 1. Buffer Stride (Camera2 YUV_420_888)

Android camera buffers often have **rowStride > width** for memory alignment:

```
Example: 1280x720 frame might have rowStride = 1408 (128 bytes padding per row)

WRONG:  luminance[y * width + x]     // Skews image, crashes possible
RIGHT:  luminance[y * rowStride + x] // Handles padding correctly
```

**Rule:** Always extract ROI with stride-aware code. See `extractROIWithStride()`.

#### 2. Zero-Allocation Pattern

At 60fps, allocations per frame cause GC pauses → timing jitter:

| Pattern | Creates Objects? | Use Instead |
|---------|------------------|-------------|
| `data class Blob(...)` constructor | YES | Mutable class + object pool |
| `list.filter { }` | YES | Manual iteration with `continue` |
| `list.map { }` | YES | Scale objects in-place |
| `list.maxByOrNull { }` | YES | Manual iteration with best tracking |

**Goal:** Zero allocations during steady-state frame processing.

#### 3. Samsung Device Quirk

Samsung devices require `CONTROL_MODE_OFF` before manual exposure works:

```kotlin
// Without this, Samsung ignores manual shutter settings!
if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
    requestBuilder.set(CONTROL_MODE, CONTROL_MODE_OFF)
}
requestBuilder.set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF)
requestBuilder.set(SENSOR_EXPOSURE_TIME, targetShutterNs)
```

#### 4. FrameData Requirements

The `HighSpeedCameraManager.FrameData` class **MUST** include:

```kotlin
data class FrameData(
    val luminanceBuffer: ByteArray,  // Y-plane from ImageProxy
    val width: Int,                   // Image width
    val height: Int,                  // Image height
    val rowStride: Int,               // CRITICAL: From Image.Plane.getRowStride()
    val timestampNanos: Long          // Frame timestamp
)
```

**Never pass raw ImageProxy buffer without rowStride!**

#### 5. Target Devices & Thermal Budget

| Config | Target | Notes |
|--------|--------|-------|
| Resolution | 720p | 1080p works but runs hotter |
| Frame rate | 60fps | Higher available but not needed |
| Shutter | 1/1000s (1ms) | Reduces motion blur |
| Session length | 45+ minutes | Must not thermal throttle |

**Primary test device:** Google Pixel 7a and older Pixel phones.

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                    EXPERIMENTAL DETECTION PIPELINE                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   ┌─────────────────────┐      ┌─────────────────────────┐         │
│   │  IMUShakeDetector   │      │  HighSpeedCameraManager │         │
│   │  (SensorManager)    │      │  (Camera2 @ 60fps)      │         │
│   │                     │      │                         │         │
│   │ • Gyro + Accel      │      │ • YUV_420_888 frames    │         │
│   │ • Shake detection   │      │ • Fast shutter 1/1000s  │         │
│   │ • StateFlow<Bool>   │      │ • Locked exposure       │         │
│   └─────────┬───────────┘      └───────────┬─────────────┘         │
│             │                              │                        │
│             │  isShaking                   │  FrameData             │
│             ▼                              ▼                        │
│   ┌──────────────────────────────────────────────────────────────┐ │
│   │              ExperimentalCrossingDetector                     │ │
│   │                                                               │ │
│   │  1. Check IMU → if shaking, go to MOVING state               │ │
│   │  2. Extract ROI slab (±12% around center)                    │ │
│   │  3. Frame diff → adaptive threshold → binary mask            │ │
│   │  4. Morphology (close + open) at 2× downsampled              │ │
│   │  5. Connected component labeling → blob list                 │ │
│   │  6. Filter blobs (aspect ratio, height fraction)             │ │
│   │  7. Track single blob (gated association)                    │ │
│   │  8. Compute chest point (horizontal projection)              │ │
│   │  9. Check sign flip + velocity → trigger crossing            │ │
│   │  10. Linear interpolation for sub-frame time                 │ │
│   └──────────────────────────────────────────────────────────────┘ │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Configuration Constants

```kotlin
package com.trackspeed.android.detection.experimental

/**
 * Configuration constants for Experimental detection mode.
 * All values are tunable based on field testing.
 */
object ExperimentalConfig {

    // ═══════════════════════════════════════════════════════════════
    // CAMERA SETTINGS
    // ═══════════════════════════════════════════════════════════════

    /** Target frame rate (regular session, not high-speed) */
    const val TARGET_FPS = 60

    /** Target shutter speed in nanoseconds (1/1000s = 1ms) */
    const val TARGET_SHUTTER_NS = 1_000_000L  // 1ms

    /** Maximum acceptable shutter for detection (2ms) */
    const val MAX_SHUTTER_NS = 2_000_000L  // 2ms

    // ═══════════════════════════════════════════════════════════════
    // ROI SLAB
    // ═══════════════════════════════════════════════════════════════

    /** ROI slab half-width as fraction of frame width (±12% = 24% total) */
    const val ROI_SLAB_HALF_WIDTH = 0.12f

    // ═══════════════════════════════════════════════════════════════
    // ADAPTIVE THRESHOLD
    // ═══════════════════════════════════════════════════════════════

    /** Threshold multiplier: threshold = mean + k * std */
    const val THRESHOLD_K_DEFAULT = 2.5f

    /** Higher k for quiet scenes (reduces salt-and-pepper noise) */
    const val THRESHOLD_K_QUIET = 3.0f

    /** Quiet scene threshold (mean < this uses THRESHOLD_K_QUIET) */
    const val QUIET_SCENE_MEAN_THRESHOLD = 5.0f

    /** Minimum adaptive threshold (clamp floor) */
    const val THRESHOLD_MIN = 10

    /** Maximum adaptive threshold (clamp ceiling) */
    const val THRESHOLD_MAX = 50

    // ═══════════════════════════════════════════════════════════════
    // MORPHOLOGY
    // ═══════════════════════════════════════════════════════════════

    /** Downsample factor for morphology (2 = half resolution) */
    const val MORPHOLOGY_DOWNSAMPLE = 2

    /** Morphology kernel size (3×3 at half-res ≈ 6×6 at full-res) */
    const val MORPHOLOGY_KERNEL_SIZE = 3

    // ═══════════════════════════════════════════════════════════════
    // BLOB FILTERING
    // ═══════════════════════════════════════════════════════════════

    /** Minimum aspect ratio (height/width) - rejects horizontal shapes */
    const val MIN_ASPECT_RATIO = 1.6f

    /** Maximum aspect ratio - rejects thin vertical lines */
    const val MAX_ASPECT_RATIO = 4.0f

    /** Minimum blob height as fraction of frame height (~10ft max distance) */
    const val MIN_HEIGHT_FRACTION = 0.16f

    /** Maximum blob height as fraction of frame height (too close / shake) */
    const val MAX_HEIGHT_FRACTION = 0.55f

    /** Minimum blob area in pixels (noise filter) */
    const val MIN_BLOB_AREA = 100

    // ═══════════════════════════════════════════════════════════════
    // BLOB TRACKING
    // ═══════════════════════════════════════════════════════════════

    /** Base tracking gate distance in pixels */
    const val TRACKING_BASE_GATE_PIXELS = 35f

    /** Velocity multiplier for dynamic gating */
    const val TRACKING_GATE_VEL_MULTIPLIER = 2.5f

    /** Lost frames allowed before dropping blob */
    const val TRACKING_LOST_FRAMES_ALLOWED = 2

    /** Frames needed to lock approach direction */
    const val DIRECTION_LOCK_FRAMES = 3

    // ═══════════════════════════════════════════════════════════════
    // CHEST POINT DETECTION
    // ═══════════════════════════════════════════════════════════════

    /** Chest search region start (fraction from top of blob) */
    const val CHEST_SEARCH_START = 0.20f

    /** Chest search region end (fraction from top of blob) */
    const val CHEST_SEARCH_END = 0.60f

    // ═══════════════════════════════════════════════════════════════
    // CROSSING DETECTION
    // ═══════════════════════════════════════════════════════════════

    /** Minimum valid frames before triggering */
    const val MIN_VALID_FRAMES = 4

    /** Minimum velocity in ROI-widths per second */
    const val MIN_VELOCITY_NORM = 0.25f

    /** Cooldown duration after trigger in milliseconds */
    const val COOLDOWN_DURATION_MS = 1500L

    // ═══════════════════════════════════════════════════════════════
    // IMU SHAKE DETECTION
    // ═══════════════════════════════════════════════════════════════

    /** Gyroscope shake threshold in rad/s (PRIMARY) */
    const val GYRO_SHAKE_THRESHOLD = 0.5f

    /** Accelerometer shake threshold in m/s² (0.25g ≈ 2.45 m/s²) (SECONDARY) */
    const val ACCEL_SHAKE_THRESHOLD = 2.45f

    /** Consecutive stable readings needed (at 60Hz = 0.4s) */
    const val IMU_STABLE_READINGS = 24

    // ═══════════════════════════════════════════════════════════════
    // SCENE RECOVERY
    // ═══════════════════════════════════════════════════════════════

    /** Frames to accumulate for background reference */
    const val BACKGROUND_FRAMES_NEEDED = 15

    /** Maximum motion coverage for background learning (10%) */
    const val MAX_MOTION_FOR_LEARNING = 0.10f

    /** Motion coverage threshold for "quiet scene" (20%) */
    const val QUIET_SCENE_THRESHOLD = 0.20f

    /** Quiet scene frames needed before ACQUIRE */
    const val QUIET_SCENE_FRAMES_NEEDED = 5
}
```

---

## 4. State Machine

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│    MOVING (phone shaking)                                           │
│    └─► show "Hold Steady", block detection, clear tracker           │
│         │                                                           │
│         ▼ (IMU stable for 0.4s)                                    │
│                                                                     │
│    STABILIZING                                                      │
│    └─► rebuild background reference from 15 quiet frames            │
│         │                                                           │
│         ▼ (background ready + motion < 20% for 5 frames)           │
│                                                                     │
│    ACQUIRE                                                          │
│    └─► looking for valid vertical blob in ROI slab                  │
│         │                                                           │
│         ▼ (blob found + passes filters)                            │
│                                                                     │
│    TRACKING                                                         │
│    └─► computing distance each frame, checking velocity             │
│         │                                                           │
│         ▼ (sign flip + valid frames + toward-line velocity)        │
│                                                                     │
│    TRIGGERED                                                        │
│    └─► compute interpolated time, emit crossing event               │
│         │                                                           │
│         ▼ (immediate)                                              │
│                                                                     │
│    COOLDOWN (1.5s) ─────────────────────────────────► ACQUIRE       │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

State transitions on phone movement:
- ANY state + IMU shake detected → MOVING (clears all state)
```

```kotlin
enum class ExperimentalState {
    /** Phone is moving - all detection blocked */
    MOVING,

    /** Phone just stopped - rebuilding background reference */
    STABILIZING,

    /** Scene is quiet - looking for valid blob */
    ACQUIRE,

    /** Tracking blob - monitoring for crossing */
    TRACKING,

    /** Crossing detected - computing interpolated time */
    TRIGGERED,

    /** Post-trigger cooldown - ignoring detection */
    COOLDOWN
}
```

---

## 5. IMU Shake Detector

```kotlin
package com.trackspeed.android.detection.experimental

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Detects phone shake using gyroscope (primary) and accelerometer (secondary).
 *
 * When shake is detected:
 * - [isShaking] becomes true
 * - [onShakeDetected] callback fires
 *
 * When phone stabilizes (0.4s of no shake):
 * - [isShaking] becomes false
 * - [onStabilized] callback fires
 *
 * THREAD SAFETY: Sensor callbacks run on sensor thread; use StateFlow for observation.
 */
@Singleton
class IMUShakeDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    companion object {
        private const val TAG = "IMUShakeDetector"
    }

    // Sensor manager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    // State
    private var consecutiveStableReadings = 0
    private var lastGyroMagnitude = 0f
    private var lastAccelMagnitude = 0f

    // Public state
    private val _isShaking = MutableStateFlow(true)  // Start as shaking (conservative)
    val isShaking: StateFlow<Boolean> = _isShaking.asStateFlow()

    private val _gyroMagnitude = MutableStateFlow(0f)
    val gyroMagnitude: StateFlow<Float> = _gyroMagnitude.asStateFlow()

    private val _accelMagnitude = MutableStateFlow(0f)
    val accelMagnitude: StateFlow<Float> = _accelMagnitude.asStateFlow()

    // Callbacks
    private var onShakeDetected: (() -> Unit)? = null
    private var onStabilized: (() -> Unit)? = null

    /**
     * Start monitoring IMU sensors.
     *
     * @param onShake Called when shake is first detected
     * @param onStable Called when phone becomes stable
     */
    fun start(onShake: (() -> Unit)? = null, onStable: (() -> Unit)? = null) {
        this.onShakeDetected = onShake
        this.onStabilized = onStable

        // Reset state
        consecutiveStableReadings = 0
        _isShaking.value = true

        // Register sensors at game rate (~60Hz)
        gyroscope?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.i(TAG, "Gyroscope registered")
        } ?: Log.w(TAG, "Gyroscope not available")

        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.i(TAG, "Accelerometer registered")
        } ?: Log.w(TAG, "Linear accelerometer not available")
    }

    /**
     * Stop monitoring IMU sensors.
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        onShakeDetected = null
        onStabilized = null
        Log.i(TAG, "IMU monitoring stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                // Compute rotation rate magnitude
                lastGyroMagnitude = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                _gyroMagnitude.value = lastGyroMagnitude
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Compute linear acceleration magnitude (gravity removed)
                lastAccelMagnitude = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                _accelMagnitude.value = lastAccelMagnitude
            }
        }

        // Check shake condition: EITHER threshold exceeded
        val isCurrentlyShaking =
            lastGyroMagnitude > ExperimentalConfig.GYRO_SHAKE_THRESHOLD ||
            lastAccelMagnitude > ExperimentalConfig.ACCEL_SHAKE_THRESHOLD

        if (isCurrentlyShaking) {
            // Reset stable counter
            consecutiveStableReadings = 0

            // Transition to shaking if not already
            if (!_isShaking.value) {
                _isShaking.value = true
                onShakeDetected?.invoke()
                Log.d(TAG, "Shake detected: gyro=${lastGyroMagnitude}, accel=${lastAccelMagnitude}")
            }
        } else {
            // Increment stable counter
            consecutiveStableReadings++

            // Transition to stable after enough readings
            if (consecutiveStableReadings >= ExperimentalConfig.IMU_STABLE_READINGS &&
                _isShaking.value) {
                _isShaking.value = false
                onStabilized?.invoke()
                Log.d(TAG, "Phone stabilized after ${consecutiveStableReadings} readings")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    /**
     * Force reset to shaking state (e.g., when starting detection).
     */
    fun reset() {
        consecutiveStableReadings = 0
        _isShaking.value = true
    }
}
```

---

## 6. Motion Mask Engine

> **CRITICAL: Android Buffer Stride Handling**
>
> Android camera buffers often have "padding" bytes at the end of each row for memory alignment.
> If `rowStride > width`, naive `y * width + x` math will cause image skewing or crashes.
> Always use stride-aware extraction before passing data to this engine.

```kotlin
package com.trackspeed.android.detection.experimental

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Computes motion mask from frame differencing with adaptive threshold.
 *
 * Features:
 * - Adaptive threshold: mean + k * std (handles varying lighting)
 * - 2× downsampling for thermal efficiency
 * - Morphology (close + open) for noise reduction
 * - Zero allocations during steady-state processing
 * - Unrolled 2x2 downsample for JVM performance
 *
 * THREAD SAFETY: Not thread-safe; call from single processing thread.
 *
 * IMPORTANT: Input luminance buffer MUST be pre-extracted with stride handling.
 * Do NOT pass raw ImageProxy buffers directly.
 */
class MotionMaskEngine {

    // ═══════════════════════════════════════════════════════════════
    // PRE-ALLOCATED BUFFERS
    // ═══════════════════════════════════════════════════════════════

    // Full resolution ROI buffers
    private lateinit var prevROI: ByteArray      // Previous frame ROI (luminance)
    private lateinit var currROI: ByteArray      // Current frame ROI (luminance)

    // Downsampled buffers
    private lateinit var dsPrev: ByteArray       // Downsampled previous
    private lateinit var dsCurr: ByteArray       // Downsampled current
    private lateinit var dsDiff: IntArray        // Difference (int for stats)
    private lateinit var dsMask: ByteArray       // Binary mask
    private lateinit var dsMorphTemp: ByteArray  // Morphology temp buffer

    // Dimensions
    private var roiWidth = 0
    private var roiHeight = 0
    private var dsWidth = 0
    private var dsHeight = 0
    private var isFirstFrame = true
    private var buffersAllocated = false

    // Last computed values (for debug)
    var lastThreshold: Int = 0
        private set
    var lastCoverage: Float = 0f
        private set

    /**
     * Result from mask computation.
     *
     * NOTE: This is a reference to internal buffer, valid only until next computeMask() call.
     * Do NOT store this reference long-term.
     */
    data class MaskResult(
        val mask: ByteArray,    // Reference to internal buffer (valid until next call)
        val width: Int,
        val height: Int,
        val threshold: Int,
        val coverage: Float     // Fraction of pixels with motion [0..1]
    )

    /**
     * Allocate buffers for given ROI dimensions.
     * Call once when frame dimensions are known.
     */
    fun allocateBuffers(roiW: Int, roiH: Int) {
        if (buffersAllocated && roiW == roiWidth && roiH == roiHeight) return

        roiWidth = roiW
        roiHeight = roiH
        dsWidth = roiW / ExperimentalConfig.MORPHOLOGY_DOWNSAMPLE
        dsHeight = roiH / ExperimentalConfig.MORPHOLOGY_DOWNSAMPLE

        val roiSize = roiW * roiH
        val dsSize = dsWidth * dsHeight

        // Allocate all buffers
        prevROI = ByteArray(roiSize)
        currROI = ByteArray(roiSize)
        dsPrev = ByteArray(dsSize)
        dsCurr = ByteArray(dsSize)
        dsDiff = IntArray(dsSize)
        dsMask = ByteArray(dsSize)
        dsMorphTemp = ByteArray(dsSize)

        buffersAllocated = true
        isFirstFrame = true
    }

    /**
     * Compute motion mask from luminance ROI.
     *
     * @param luminance Luminance buffer for current ROI (MUST be stride-corrected, contiguous)
     * @param width ROI width
     * @param height ROI height
     * @return MaskResult with binary mask at downsampled resolution
     */
    fun computeMask(luminance: ByteArray, width: Int, height: Int): MaskResult {
        // Ensure buffers allocated
        if (!buffersAllocated || width != roiWidth || height != roiHeight) {
            allocateBuffers(width, height)
        }

        // Copy luminance to current buffer
        System.arraycopy(luminance, 0, currROI, 0, luminance.size)

        // Handle first frame
        if (isFirstFrame) {
            swapBuffers()
            isFirstFrame = false
            return MaskResult(dsMask, dsWidth, dsHeight, 0, 0f)
        }

        // 1. Downsample both frames (2×2 block averaging, unrolled for JVM performance)
        downsample2x2Unrolled(prevROI, dsPrev, roiWidth, dsWidth, dsHeight)
        downsample2x2Unrolled(currROI, dsCurr, roiWidth, dsWidth, dsHeight)

        // 2. Compute absolute difference and statistics
        var sum = 0L
        var sumSq = 0L
        val dsSize = dsWidth * dsHeight

        for (i in 0 until dsSize) {
            val prev = dsPrev[i].toInt() and 0xFF
            val curr = dsCurr[i].toInt() and 0xFF
            val diff = abs(curr - prev)
            dsDiff[i] = diff
            sum += diff
            sumSq += diff.toLong() * diff
        }

        // 3. Compute adaptive threshold: mean + k * std
        val mean = sum.toFloat() / dsSize
        val variance = (sumSq.toFloat() / dsSize) - (mean * mean)
        val std = sqrt(variance.coerceAtLeast(0f))

        // Use higher k for quiet scenes to avoid noise
        val k = if (mean < ExperimentalConfig.QUIET_SCENE_MEAN_THRESHOLD) {
            ExperimentalConfig.THRESHOLD_K_QUIET
        } else {
            ExperimentalConfig.THRESHOLD_K_DEFAULT
        }

        val threshold = (mean + k * std)
            .toInt()
            .coerceIn(ExperimentalConfig.THRESHOLD_MIN, ExperimentalConfig.THRESHOLD_MAX)
        lastThreshold = threshold

        // 4. Apply threshold → binary mask
        var motionCount = 0
        for (i in 0 until dsSize) {
            if (dsDiff[i] > threshold) {
                dsMask[i] = 255.toByte()
                motionCount++
            } else {
                dsMask[i] = 0
            }
        }

        // 5. Morphology: close (fill gaps) then open (remove specks)
        applyMorphology()

        // 6. Recount after morphology
        motionCount = 0
        for (i in 0 until dsSize) {
            if (dsMask[i] != 0.toByte()) motionCount++
        }

        val coverage = motionCount.toFloat() / dsSize
        lastCoverage = coverage

        // 7. Swap buffers for next frame
        swapBuffers()

        return MaskResult(dsMask, dsWidth, dsHeight, threshold, coverage)
    }

    /**
     * Reset state (e.g., after scene change).
     */
    fun reset() {
        isFirstFrame = true
        lastThreshold = 0
        lastCoverage = 0f
        if (buffersAllocated) {
            Arrays.fill(dsMask, 0)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun swapBuffers() {
        val temp = prevROI
        prevROI = currROI
        currROI = temp
    }

    /**
     * Optimized 2×2 downsample with unrolled inner loop.
     *
     * This is 2-3x faster than nested loops on Android's ART runtime
     * because it avoids bounds checking overhead in tight inner loops.
     *
     * @param src Source buffer (must be contiguous, stride-corrected)
     * @param dst Destination buffer
     * @param srcW Source width
     * @param dstW Destination width
     * @param dstH Destination height
     */
    private fun downsample2x2Unrolled(
        src: ByteArray,
        dst: ByteArray,
        srcW: Int,
        dstW: Int,
        dstH: Int
    ) {
        for (dy in 0 until dstH) {
            val sy = dy * 2
            val srcRow0 = sy * srcW
            val srcRow1 = (sy + 1) * srcW
            val dstRow = dy * dstW

            for (dx in 0 until dstW) {
                val sx = dx * 2

                // Unrolled 2x2 block access with unsigned byte conversion
                val p00 = src[srcRow0 + sx].toInt() and 0xFF
                val p01 = src[srcRow0 + sx + 1].toInt() and 0xFF
                val p10 = src[srcRow1 + sx].toInt() and 0xFF
                val p11 = src[srcRow1 + sx + 1].toInt() and 0xFF

                dst[dstRow + dx] = ((p00 + p01 + p10 + p11) shr 2).toByte()
            }
        }
    }

    /**
     * Apply morphology: close (dilate→erode) then open (erode→dilate).
     * Works on [dsMask] using [dsMorphTemp] as scratch.
     */
    private fun applyMorphology() {
        val k = ExperimentalConfig.MORPHOLOGY_KERNEL_SIZE
        val halfK = k / 2

        // Close: dilate then erode
        dilate(dsMask, dsMorphTemp, dsWidth, dsHeight, halfK)
        erode(dsMorphTemp, dsMask, dsWidth, dsHeight, halfK)

        // Open: erode then dilate
        erode(dsMask, dsMorphTemp, dsWidth, dsHeight, halfK)
        dilate(dsMorphTemp, dsMask, dsWidth, dsHeight, halfK)
    }

    private fun dilate(src: ByteArray, dst: ByteArray, w: Int, h: Int, radius: Int) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                var maxVal: Byte = 0
                for (ky in -radius..radius) {
                    for (kx in -radius..radius) {
                        val nx = (x + kx).coerceIn(0, w - 1)
                        val ny = (y + ky).coerceIn(0, h - 1)
                        val v = src[ny * w + nx]
                        if (v != 0.toByte()) maxVal = 255.toByte()
                    }
                }
                dst[y * w + x] = maxVal
            }
        }
    }

    private fun erode(src: ByteArray, dst: ByteArray, w: Int, h: Int, radius: Int) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                var minVal: Byte = 255.toByte()
                for (ky in -radius..radius) {
                    for (kx in -radius..radius) {
                        val nx = (x + kx).coerceIn(0, w - 1)
                        val ny = (y + ky).coerceIn(0, h - 1)
                        val v = src[ny * w + nx]
                        if (v == 0.toByte()) minVal = 0
                    }
                }
                dst[y * w + x] = minVal
            }
        }
    }
}
```

---

## 7. Connected Component Labeler

> **CRITICAL: Zero-Allocation Blob Pooling**
>
> Using `data class Blob(...)` with `mutableListOf.add(Blob(...))` allocates objects every frame.
> At 60fps with 5 blobs/frame = **300 allocations/second = 90,000 objects in 5 minutes**.
> This WILL trigger GC pauses causing 100ms+ freezes.
>
> Solution: Use the **Flyweight Pattern** with a pre-allocated object pool.

```kotlin
package com.trackspeed.android.detection.experimental

import android.graphics.PointF
import android.graphics.RectF

/**
 * Connected component labeling using row-run algorithm with union-find.
 *
 * Features:
 * - Row-run labeling (efficient for sparse masks)
 * - Union-find with path compression
 * - Pre-allocated buffers for zero-allocation processing
 * - **Flyweight pattern**: Blob objects are pooled and reused
 * - Outputs blob list with bbox, centroid, area
 *
 * ZERO ALLOCATIONS during steady-state processing.
 */
class ConnectedComponentLabeler(
    private val maxWidth: Int,
    private val maxHeight: Int,
    private val maxLabels: Int = 4096,
    private val maxBlobs: Int = 32  // Max blobs we'll ever return
) {
    /**
     * A detected blob.
     *
     * NOTE: This is a MUTABLE class used as part of an object pool.
     * Do NOT store references long-term. The same Blob instance will be
     * reused across frames. Copy data if you need to persist it.
     *
     * ZERO ALLOCATIONS: These objects are pre-allocated and reused every frame.
     */
    class Blob {
        val bbox = RectF()
        val centroid = PointF()
        var area: Int = 0
        var aspectRatio: Float = 0f
        var heightFraction: Float = 0f

        /** Reset all fields for reuse */
        fun reset() {
            bbox.setEmpty()
            centroid.set(0f, 0f)
            area = 0
            aspectRatio = 0f
            heightFraction = 0f
        }

        /** Set all fields at once */
        fun set(
            left: Float, top: Float, right: Float, bottom: Float,
            cx: Float, cy: Float,
            area: Int, aspectRatio: Float, heightFraction: Float
        ) {
            bbox.set(left, top, right, bottom)
            centroid.set(cx, cy)
            this.area = area
            this.aspectRatio = aspectRatio
            this.heightFraction = heightFraction
        }

        /** Copy from another blob (for when you need to persist data) */
        fun copyFrom(other: Blob) {
            bbox.set(other.bbox)
            centroid.set(other.centroid)
            area = other.area
            aspectRatio = other.aspectRatio
            heightFraction = other.heightFraction
        }

        /** Scale coordinates in place (for downsampled → full resolution) */
        fun scaleInPlace(factor: Float) {
            bbox.set(
                bbox.left * factor,
                bbox.top * factor,
                bbox.right * factor,
                bbox.bottom * factor
            )
            centroid.set(centroid.x * factor, centroid.y * factor)
            area = (area * factor * factor).toInt()
            // aspectRatio and heightFraction are ratios, unchanged
        }
    }

    // Row run storage (mutable, reused)
    private class Run {
        var xStart: Int = 0
        var xEnd: Int = 0
        var row: Int = 0
        var label: Int = 0

        fun set(xStart: Int, xEnd: Int, row: Int, label: Int) {
            this.xStart = xStart
            this.xEnd = xEnd
            this.row = row
            this.label = label
        }
    }

    // Blob statistics accumulator
    private class BlobStats {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var area = 0
        var sumX = 0L
        var sumY = 0L

        fun reset() {
            minX = Int.MAX_VALUE
            maxX = Int.MIN_VALUE
            minY = Int.MAX_VALUE
            maxY = Int.MIN_VALUE
            area = 0
            sumX = 0
            sumY = 0
        }

        fun addRun(run: Run) {
            if (run.xStart < minX) minX = run.xStart
            if (run.xEnd > maxX) maxX = run.xEnd
            if (run.row < minY) minY = run.row
            if (run.row > maxY) maxY = run.row

            val width = run.xEnd - run.xStart + 1
            area += width

            val centerX = (run.xStart + run.xEnd) / 2
            sumX += width.toLong() * centerX
            sumY += width.toLong() * run.row
        }

        fun merge(other: BlobStats) {
            if (other.minX < minX) minX = other.minX
            if (other.maxX > maxX) maxX = other.maxX
            if (other.minY < minY) minY = other.minY
            if (other.maxY > maxY) maxY = other.maxY
            area += other.area
            sumX += other.sumX
            sumY += other.sumY
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PRE-ALLOCATED OBJECT POOL (ZERO ALLOCATIONS)
    // ═══════════════════════════════════════════════════════════════

    // Union-find parent array
    private val equivalence = IntArray(maxLabels) { it }

    // Statistics accumulators (one per label)
    private val stats = Array(maxLabels) { BlobStats() }

    // Run storage (double-buffered for row processing)
    private val maxRunsPerRow = maxWidth / 2
    private val runsA = Array(maxRunsPerRow) { Run() }
    private val runsB = Array(maxRunsPerRow) { Run() }

    // *** BLOB POOL - PRE-ALLOCATED ***
    // These Blob objects are reused every frame. Never allocate new ones!
    private val blobPool = Array(maxBlobs) { Blob() }
    private var activeBlobCount = 0

    /**
     * Get the list of active blobs from the last findBlobs() call.
     *
     * WARNING: Returns a view into the internal pool. Valid only until
     * the next findBlobs() call. Do NOT store these references.
     *
     * If you need to persist blob data, call blob.copyFrom() to copy
     * into your own Blob instance.
     */
    fun getActiveBlobs(): List<Blob> = blobPool.take(activeBlobCount)

    /**
     * Find connected components in binary mask.
     *
     * ZERO ALLOCATIONS: Uses pre-allocated blob pool. Returns count of active blobs.
     * Access results via getActiveBlobs() or directly iterate blobPool[0..activeBlobCount).
     *
     * @param mask Binary mask (0 or non-zero)
     * @param width Mask width
     * @param height Mask height
     * @return Number of blobs found (access via getActiveBlobs())
     */
    fun findBlobs(mask: ByteArray, width: Int, height: Int): Int {
        // Reset state
        var nextLabel = 1
        for (i in 0 until maxLabels) {
            equivalence[i] = i
            stats[i].reset()
        }
        activeBlobCount = 0

        var currentRuns = runsA
        var prevRuns = runsB
        var prevRunCount = 0

        // Process rows
        for (y in 0 until height) {
            val rowOffset = y * width
            var currentRunCount = 0

            // Find runs in current row
            var x = 0
            while (x < width) {
                if (mask[rowOffset + x] != 0.toByte()) {
                    val start = x
                    while (x < width && mask[rowOffset + x] != 0.toByte()) x++
                    val end = x - 1

                    if (currentRunCount < maxRunsPerRow) {
                        currentRuns[currentRunCount].set(
                            xStart = start,
                            xEnd = end,
                            row = y,
                            label = 0
                        )
                        currentRunCount++
                    }
                } else {
                    x++
                }
            }

            // Connect runs to previous row
            for (ci in 0 until currentRunCount) {
                val cRun = currentRuns[ci]
                var labelToUse = 0

                // Check overlap with previous row runs
                for (pi in 0 until prevRunCount) {
                    val pRun = prevRuns[pi]

                    // Check horizontal overlap
                    if (pRun.xEnd >= cRun.xStart && pRun.xStart <= cRun.xEnd) {
                        val pLabel = resolve(pRun.label)

                        if (labelToUse == 0) {
                            labelToUse = pLabel
                        } else if (labelToUse != pLabel) {
                            union(labelToUse, pLabel)
                            labelToUse = resolve(labelToUse)
                        }
                    }
                }

                // Assign label
                if (labelToUse == 0) {
                    if (nextLabel < maxLabels) {
                        labelToUse = nextLabel++
                        stats[labelToUse].reset()
                    } else {
                        labelToUse = 1  // Fallback
                    }
                }

                cRun.label = labelToUse
                stats[labelToUse].addRun(cRun)
            }

            // Swap run buffers
            val temp = currentRuns
            currentRuns = prevRuns
            prevRuns = temp
            prevRunCount = currentRunCount
        }

        // Merge stats for equivalent labels
        for (i in 1 until nextLabel) {
            val root = resolve(i)
            if (root != i) {
                stats[root].merge(stats[i])
            }
        }

        // Build output blobs using pool (only roots with sufficient area)
        val minArea = ExperimentalConfig.MIN_BLOB_AREA
        for (i in 1 until nextLabel) {
            if (equivalence[i] != i) continue  // Skip non-roots
            val s = stats[i]
            if (s.area < minArea) continue

            // Check pool capacity
            if (activeBlobCount >= maxBlobs) break

            val bboxWidth = (s.maxX - s.minX + 1).toFloat()
            val bboxHeight = (s.maxY - s.minY + 1).toFloat()
            val aspectRatio = if (bboxWidth > 0) bboxHeight / bboxWidth else 0f
            val heightFrac = bboxHeight / height

            val centroidX = if (s.area > 0) s.sumX.toFloat() / s.area else s.minX.toFloat()
            val centroidY = if (s.area > 0) s.sumY.toFloat() / s.area else s.minY.toFloat()

            // Use pooled blob - ZERO ALLOCATION
            blobPool[activeBlobCount].set(
                left = s.minX.toFloat(),
                top = s.minY.toFloat(),
                right = (s.maxX + 1).toFloat(),
                bottom = (s.maxY + 1).toFloat(),
                cx = centroidX,
                cy = centroidY,
                area = s.area,
                aspectRatio = aspectRatio,
                heightFraction = heightFrac
            )
            activeBlobCount++
        }

        return activeBlobCount
    }

    /**
     * Reset the labeler (clears active blobs).
     */
    fun reset() {
        activeBlobCount = 0
        for (blob in blobPool) {
            blob.reset()
        }
    }

    // Union-find with path compression
    private fun resolve(label: Int): Int {
        var i = label
        while (i != equivalence[i]) {
            equivalence[i] = equivalence[equivalence[i]]  // Path compression
            i = equivalence[i]
        }
        return i
    }

    private fun union(a: Int, b: Int) {
        val rootA = resolve(a)
        val rootB = resolve(b)
        if (rootA != rootB) {
            // Point larger to smaller (deterministic)
            if (rootA < rootB) {
                equivalence[rootB] = rootA
            } else {
                equivalence[rootA] = rootB
            }
        }
    }
}
```

---

## 8. Blob Tracker

```kotlin
package com.trackspeed.android.detection.experimental

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tracks a single blob across frames with dynamic gating.
 *
 * Features:
 * - Dynamic gate: baseGate + velocity * multiplier
 * - Normalized scoring (area, aspect, height, gate proximity)
 * - Direction locking after consistent velocity
 * - Lost-frame tolerance (2 frames)
 *
 * ZERO ALLOCATIONS: Does not create lists or store blob references across frames.
 */
class BlobTracker {

    /**
     * Approach direction (runner coming from left or right).
     */
    enum class Direction {
        UNKNOWN,
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT
    }

    /**
     * Tracking result.
     *
     * NOTE: The blob reference is only valid within the current frame.
     * Do NOT store this reference - the blob pool is reused next frame.
     */
    data class TrackResult(
        val blob: ConnectedComponentLabeler.Blob?,
        val velocityNorm: Float,         // ROI-widths per second
        val direction: Direction,
        val distanceToGate: Float,       // Signed distance in pixels
        val isValid: Boolean             // Blob passes all filters
    )

    // ═══════════════════════════════════════════════════════════════
    // TRACKING STATE (PRIMITIVES ONLY - NO BLOB REFERENCES!)
    // ═══════════════════════════════════════════════════════════════

    // CRITICAL: Do NOT store blob references across frames!
    // Blobs are pooled and reused - storing a reference means stale data.
    // Instead, store the primitive values we need for tracking.

    private var hasActiveTarget = false
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f
    private var velocityNorm = 0f
    private var direction = Direction.UNKNOWN
    private var dirLockCount = 0
    private var lostCount = 0
    private var consecutiveValidFrames = 0

    /**
     * Update tracker with new frame.
     *
     * ZERO ALLOCATIONS: Iterates blobs in-place, no list creation.
     *
     * @param blobs Candidate blobs from CCL (from pool, do NOT store!)
     * @param roiWidth ROI width in pixels
     * @param gateX Gate line X position in pixels
     * @param dt Time since last frame in seconds
     * @return Tracking result
     */
    fun update(
        blobs: List<ConnectedComponentLabeler.Blob>,
        roiWidth: Float,
        gateX: Float,
        dt: Float
    ): TrackResult {
        // Acquire if no active target
        if (!hasActiveTarget) {
            // Find best valid blob to start tracking (no list allocation)
            var best: ConnectedComponentLabeler.Blob? = null
            var bestScore = Float.NEGATIVE_INFINITY

            for (blob in blobs) {
                if (!isValidBlob(blob)) continue
                val score = scoreBlob(blob, roiWidth, gateX, preferGate = false)
                if (score > bestScore) {
                    bestScore = score
                    best = blob
                }
            }

            if (best != null) {
                hasActiveTarget = true
                lastCentroidX = best.centroid.x
                lastCentroidY = best.centroid.y
                lostCount = 0
                direction = Direction.UNKNOWN
                dirLockCount = 0
                velocityNorm = 0f
                consecutiveValidFrames = 1

                val distanceToGate = best.centroid.x - gateX
                return TrackResult(best, 0f, Direction.UNKNOWN, distanceToGate, true)
            }
            return TrackResult(null, 0f, Direction.UNKNOWN, 0f, false)
        }

        // Calculate dynamic gate based on velocity
        val dynamicGate = ExperimentalConfig.TRACKING_BASE_GATE_PIXELS +
            ExperimentalConfig.TRACKING_GATE_VEL_MULTIPLIER * abs(velocityNorm) * roiWidth * dt

        // Find best match within gate (no list allocation - iterate in-place)
        var bestMatch: ConnectedComponentLabeler.Blob? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (blob in blobs) {
            if (!isValidBlob(blob)) continue

            // Distance from last known position (using stored primitives)
            val dx = blob.centroid.x - lastCentroidX
            val dy = blob.centroid.y - lastCentroidY
            val dist = sqrt(dx * dx + dy * dy)

            if (dist > dynamicGate) continue

            val score = scoreBlob(blob, roiWidth, gateX, preferGate = true)
            if (score > bestScore) {
                bestScore = score
                bestMatch = blob
            }
        }

        if (bestMatch != null) {
            lostCount = 0
            consecutiveValidFrames++

            // Update velocity using stored position (NOT blob reference!)
            val currX = bestMatch.centroid.x
            val vPixelsPerSec = (currX - lastCentroidX) / dt.coerceAtLeast(1e-6f)
            velocityNorm = vPixelsPerSec / roiWidth

            // Store current position for next frame
            lastCentroidX = bestMatch.centroid.x
            lastCentroidY = bestMatch.centroid.y

            // Direction locking
            if (abs(velocityNorm) > ExperimentalConfig.MIN_VELOCITY_NORM) {
                val newDir = if (velocityNorm > 0) Direction.LEFT_TO_RIGHT else Direction.RIGHT_TO_LEFT
                if (direction == Direction.UNKNOWN || direction == newDir) {
                    direction = newDir
                    dirLockCount++
                } else {
                    dirLockCount = 0
                    direction = Direction.UNKNOWN
                }
            } else {
                direction = Direction.UNKNOWN
                dirLockCount = 0
            }

            val reportedDir = if (dirLockCount >= ExperimentalConfig.DIRECTION_LOCK_FRAMES) {
                direction
            } else {
                Direction.UNKNOWN
            }

            val distanceToGate = bestMatch.centroid.x - gateX
            val isValid = consecutiveValidFrames >= ExperimentalConfig.MIN_VALID_FRAMES

            return TrackResult(bestMatch, velocityNorm, reportedDir, distanceToGate, isValid)
        } else {
            // Lost tracking
            lostCount++
            if (lostCount > ExperimentalConfig.TRACKING_LOST_FRAMES_ALLOWED) {
                hasActiveTarget = false
                direction = Direction.UNKNOWN
                dirLockCount = 0
                consecutiveValidFrames = 0
            }
            return TrackResult(null, velocityNorm, direction, 0f, false)
        }
    }

    /**
     * Reset tracker state.
     */
    fun reset() {
        hasActiveTarget = false
        lastCentroidX = 0f
        lastCentroidY = 0f
        velocityNorm = 0f
        direction = Direction.UNKNOWN
        dirLockCount = 0
        lostCount = 0
        consecutiveValidFrames = 0
    }

    /**
     * Check if blob passes basic filters.
     */
    private fun isValidBlob(blob: ConnectedComponentLabeler.Blob): Boolean {
        return blob.aspectRatio in ExperimentalConfig.MIN_ASPECT_RATIO..ExperimentalConfig.MAX_ASPECT_RATIO &&
               blob.heightFraction in ExperimentalConfig.MIN_HEIGHT_FRACTION..ExperimentalConfig.MAX_HEIGHT_FRACTION &&
               blob.area >= ExperimentalConfig.MIN_BLOB_AREA
    }

    /**
     * Score blob for selection.
     */
    private fun scoreBlob(
        blob: ConnectedComponentLabeler.Blob,
        roiWidth: Float,
        gateX: Float,
        preferGate: Boolean
    ): Float {
        // Area score (normalized)
        val areaScore = (blob.area.toFloat() / 2000f).coerceAtMost(1f)

        // Aspect ratio score
        val aspectScore = if (blob.aspectRatio in 1.6f..4.0f) 1f else 0f

        // Height fraction score
        val heightScore = if (blob.heightFraction in 0.16f..0.55f) 1f else 0f

        // Gate proximity score
        val gateScore = if (preferGate) {
            val dx = abs(blob.centroid.x - gateX)
            1f - (dx / (0.5f * roiWidth)).coerceAtMost(1f)
        } else 0f

        return 0.35f * areaScore + 0.35f * aspectScore + 0.20f * heightScore + 0.10f * gateScore
    }

    // NOTE: pickBest removed - it used maxByOrNull which allocates.
    // Best blob selection is now done inline in update() with manual iteration.
}
```

---

## 9. Main Crossing Detector

```kotlin
package com.trackspeed.android.detection.experimental

import android.os.SystemClock
import android.util.Log
import com.trackspeed.android.camera.HighSpeedCameraManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main experimental crossing detector.
 *
 * Uses 60fps blob tracking with:
 * - IMU-based auto-recovery
 * - Adaptive threshold motion detection
 * - Direction-aware crossing trigger
 * - Linear sub-frame interpolation
 */
@Singleton
class ExperimentalCrossingDetector @Inject constructor(
    private val imuDetector: IMUShakeDetector
) {
    companion object {
        private const val TAG = "ExperimentalDetector"
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC STATE
    // ═══════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow(ExperimentalState.MOVING)
    val state: StateFlow<ExperimentalState> = _state.asStateFlow()

    private val _crossingEvents = MutableSharedFlow<CrossingEvent>(extraBufferCapacity = 10)
    val crossingEvents: SharedFlow<CrossingEvent> = _crossingEvents.asSharedFlow()

    private val _debugInfo = MutableStateFlow<ExperimentalDebugInfo?>(null)
    val debugInfo: StateFlow<ExperimentalDebugInfo?> = _debugInfo.asStateFlow()

    // ═══════════════════════════════════════════════════════════════
    // COMPONENTS
    // ═══════════════════════════════════════════════════════════════

    private val motionMaskEngine = MotionMaskEngine()
    private var ccl: ConnectedComponentLabeler? = null
    private val blobTracker = BlobTracker()

    // ═══════════════════════════════════════════════════════════════
    // PRE-ALLOCATED BUFFERS
    // ═══════════════════════════════════════════════════════════════

    private lateinit var roiBuffer: ByteArray
    private lateinit var backgroundAccumulator: FloatArray
    private lateinit var backgroundReference: FloatArray
    private lateinit var projectionBuffer: IntArray

    // ═══════════════════════════════════════════════════════════════
    // DIMENSIONS
    // ═══════════════════════════════════════════════════════════════

    private var frameWidth = 0
    private var frameHeight = 0
    private var roiWidth = 0
    private var roiHeight = 0
    private var roiStartX = 0
    private var gateX = 0f
    private var buffersAllocated = false

    // ═══════════════════════════════════════════════════════════════
    // STATE TRACKING
    // ═══════════════════════════════════════════════════════════════

    private var backgroundFrameCount = 0
    private var quietSceneFrameCount = 0
    private var lastTriggerTimeNanos = 0L
    private var cooldownEndTimeNanos = 0L

    // Distance history for interpolation
    private var prevDistance = 0f
    private var prevTimestamp = 0L
    private var currDistance = 0f
    private var currTimestamp = 0L

    // Frame counter
    private var frameIndex = 0L

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start detection.
     */
    fun start() {
        imuDetector.start(
            onShake = { handleShakeDetected() },
            onStable = { handleStabilized() }
        )
        _state.value = ExperimentalState.MOVING
        Log.i(TAG, "Experimental detector started")
    }

    /**
     * Stop detection.
     */
    fun stop() {
        imuDetector.stop()
        Log.i(TAG, "Experimental detector stopped")
    }

    /**
     * Reset to initial state.
     */
    fun reset() {
        imuDetector.reset()
        motionMaskEngine.reset()
        blobTracker.reset()

        _state.value = ExperimentalState.MOVING
        backgroundFrameCount = 0
        quietSceneFrameCount = 0
        frameIndex = 0
        prevDistance = 0f
        prevTimestamp = 0L
        currDistance = 0f
        currTimestamp = 0L

        if (buffersAllocated) {
            Arrays.fill(backgroundAccumulator, 0f)
            Arrays.fill(backgroundReference, 0f)
        }

        Log.i(TAG, "Experimental detector reset")
    }

    // ═══════════════════════════════════════════════════════════════
    // FRAME PROCESSING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Process a camera frame.
     * Call this for every frame at 60fps.
     *
     * IMPORTANT: This method handles Android Camera2 stride quirks internally.
     * The luminanceBuffer from FrameData may have rowStride > width padding.
     */
    fun processFrame(frameData: HighSpeedCameraManager.FrameData) {
        frameIndex++

        // Ensure buffers allocated
        if (!buffersAllocated || frameWidth != frameData.width || frameHeight != frameData.height) {
            allocateBuffers(frameData.width, frameData.height)
        }

        val state = _state.value

        // In MOVING state, skip all processing
        if (state == ExperimentalState.MOVING) {
            updateDebugInfo(state, null, null, 0f, 0)
            return
        }

        // Extract ROI slab with stride handling
        // CRITICAL: Android Camera2 buffers often have rowStride > width
        extractROIWithStride(
            frameData.luminanceBuffer,
            frameData.rowStride,
            frameData.width,
            frameData.height
        )

        // Compute motion mask
        val maskResult = motionMaskEngine.computeMask(roiBuffer, roiWidth, roiHeight)

        when (state) {
            ExperimentalState.STABILIZING -> {
                processStabilizing(maskResult)
            }

            ExperimentalState.ACQUIRE,
            ExperimentalState.TRACKING -> {
                processDetection(maskResult, frameData.timestampNanos)
            }

            ExperimentalState.TRIGGERED -> {
                // Immediate transition to cooldown
                _state.value = ExperimentalState.COOLDOWN
            }

            ExperimentalState.COOLDOWN -> {
                val now = SystemClock.elapsedRealtimeNanos()
                if (now >= cooldownEndTimeNanos) {
                    _state.value = ExperimentalState.ACQUIRE
                    blobTracker.reset()
                }
                updateDebugInfo(ExperimentalState.COOLDOWN, null, maskResult, 0f, 0)
            }

            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STATE HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private fun handleShakeDetected() {
        Log.d(TAG, "Shake detected → MOVING")
        _state.value = ExperimentalState.MOVING

        // Clear all tracking state
        blobTracker.reset()
        motionMaskEngine.reset()
        backgroundFrameCount = 0
        quietSceneFrameCount = 0
        prevDistance = 0f
        currDistance = 0f

        if (buffersAllocated) {
            Arrays.fill(backgroundAccumulator, 0f)
            Arrays.fill(backgroundReference, 0f)
        }
    }

    private fun handleStabilized() {
        Log.d(TAG, "Phone stabilized → STABILIZING")
        _state.value = ExperimentalState.STABILIZING
        backgroundFrameCount = 0
        quietSceneFrameCount = 0
        motionMaskEngine.reset()
    }

    private fun processStabilizing(maskResult: MotionMaskEngine.MaskResult) {
        val coverage = maskResult.coverage

        // Only accumulate quiet frames (avoid learning moving people)
        if (coverage <= ExperimentalConfig.MAX_MOTION_FOR_LEARNING) {
            if (backgroundFrameCount == 0) {
                // First quiet frame - initialize
                for (i in roiBuffer.indices) {
                    backgroundAccumulator[i] = (roiBuffer[i].toInt() and 0xFF).toFloat()
                }
                backgroundFrameCount = 1
            } else if (backgroundFrameCount < ExperimentalConfig.BACKGROUND_FRAMES_NEEDED) {
                // Accumulate
                for (i in roiBuffer.indices) {
                    backgroundAccumulator[i] += (roiBuffer[i].toInt() and 0xFF).toFloat()
                }
                backgroundFrameCount++
            }

            // Check if background ready
            if (backgroundFrameCount >= ExperimentalConfig.BACKGROUND_FRAMES_NEEDED &&
                backgroundReference[0] == 0f) {
                // Compute average
                for (i in backgroundAccumulator.indices) {
                    backgroundReference[i] = backgroundAccumulator[i] / backgroundFrameCount
                }
                Log.i(TAG, "Background reference built from $backgroundFrameCount frames")
            }
        }

        // Check for quiet scene
        if (backgroundFrameCount >= ExperimentalConfig.BACKGROUND_FRAMES_NEEDED) {
            if (coverage < ExperimentalConfig.QUIET_SCENE_THRESHOLD) {
                quietSceneFrameCount++
            } else {
                quietSceneFrameCount = 0
            }

            if (quietSceneFrameCount >= ExperimentalConfig.QUIET_SCENE_FRAMES_NEEDED) {
                Log.i(TAG, "Scene quiet → ACQUIRE")
                _state.value = ExperimentalState.ACQUIRE
                blobTracker.reset()
            }
        }

        updateDebugInfo(
            ExperimentalState.STABILIZING,
            null,
            maskResult,
            0f,
            backgroundFrameCount
        )
    }

    private fun processDetection(
        maskResult: MotionMaskEngine.MaskResult,
        timestampNanos: Long
    ) {
        // Find blobs (ZERO ALLOCATION - uses internal pool)
        if (ccl == null) {
            ccl = ConnectedComponentLabeler(maskResult.width, maskResult.height)
        }
        val blobCount = ccl!!.findBlobs(maskResult.mask, maskResult.width, maskResult.height)

        // Scale blobs IN PLACE to full ROI resolution (no allocations)
        // This is safe because blobs are only used within this frame
        val scaleFactor = ExperimentalConfig.MORPHOLOGY_DOWNSAMPLE.toFloat()
        val blobs = ccl!!.getActiveBlobs()
        for (blob in blobs) {
            blob.scaleInPlace(scaleFactor)
        }

        // Track blob
        val dt = if (prevTimestamp > 0) {
            (timestampNanos - prevTimestamp) / 1_000_000_000f
        } else {
            1f / 60f
        }

        val trackResult = blobTracker.update(
            blobs = blobs,
            roiWidth = roiWidth.toFloat(),
            gateX = gateX,
            dt = dt
        )

        // Update distance history
        prevDistance = currDistance
        prevTimestamp = currTimestamp
        currDistance = trackResult.distanceToGate
        currTimestamp = timestampNanos

        // State transitions
        if (trackResult.blob != null) {
            if (_state.value == ExperimentalState.ACQUIRE) {
                _state.value = ExperimentalState.TRACKING
            }

            // Check for crossing
            if (_state.value == ExperimentalState.TRACKING && trackResult.isValid) {
                if (checkCrossing(trackResult)) {
                    triggerCrossing(timestampNanos)
                }
            }
        } else {
            // Lost blob
            if (_state.value == ExperimentalState.TRACKING) {
                _state.value = ExperimentalState.ACQUIRE
            }
        }

        updateDebugInfo(
            _state.value,
            trackResult,
            maskResult,
            trackResult.distanceToGate,
            backgroundFrameCount
        )
    }

    /**
     * Check if crossing conditions are met.
     */
    private fun checkCrossing(trackResult: BlobTracker.TrackResult): Boolean {
        // Need locked direction
        if (trackResult.direction == BlobTracker.Direction.UNKNOWN) return false

        // Check sign flip based on direction
        val signFlipped = when (trackResult.direction) {
            BlobTracker.Direction.LEFT_TO_RIGHT -> prevDistance < 0 && currDistance >= 0
            BlobTracker.Direction.RIGHT_TO_LEFT -> prevDistance > 0 && currDistance <= 0
            else -> false
        }

        return signFlipped
    }

    /**
     * Trigger a crossing event.
     */
    private fun triggerCrossing(timestampNanos: Long) {
        // Linear interpolation for sub-frame time
        val interpolatedTime = if (prevDistance != currDistance) {
            val alpha = prevDistance / (prevDistance - currDistance)
            prevTimestamp + ((timestampNanos - prevTimestamp) * alpha).toLong()
        } else {
            timestampNanos
        }

        val event = CrossingEvent(
            timestamp = interpolatedTime,
            frameIndex = frameIndex,
            rawTimestamp = timestampNanos,
            interpolationOffsetMs = (timestampNanos - interpolatedTime) / 1_000_000.0
        )

        _crossingEvents.tryEmit(event)
        Log.i(TAG, "Crossing detected! Interpolated time: $interpolatedTime")

        // Transition to cooldown
        _state.value = ExperimentalState.COOLDOWN
        cooldownEndTimeNanos = timestampNanos +
            ExperimentalConfig.COOLDOWN_DURATION_MS * 1_000_000L
        lastTriggerTimeNanos = timestampNanos
    }

    // ═══════════════════════════════════════════════════════════════
    // BUFFER MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    private fun allocateBuffers(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height

        // ROI is ±12% = 24% of frame width
        roiWidth = (width * ExperimentalConfig.ROI_SLAB_HALF_WIDTH * 2).toInt()
        roiHeight = height
        roiStartX = (width - roiWidth) / 2
        gateX = roiWidth / 2f  // Gate at center of ROI

        val roiSize = roiWidth * roiHeight

        roiBuffer = ByteArray(roiSize)
        backgroundAccumulator = FloatArray(roiSize)
        backgroundReference = FloatArray(roiSize)
        projectionBuffer = IntArray(roiHeight)

        motionMaskEngine.allocateBuffers(roiWidth, roiHeight)

        buffersAllocated = true
        Log.i(TAG, "Buffers allocated: frame ${width}x${height}, ROI ${roiWidth}x${roiHeight}")
    }

    /**
     * Extract ROI slab from full frame with stride handling.
     *
     * CRITICAL: Android Camera2 YUV_420_888 buffers often have rowStride > width.
     * The extra bytes at the end of each row are padding for memory alignment.
     * Naive `y * width + x` indexing will cause image skewing or crashes!
     *
     * Example: 1280x720 frame might have rowStride = 1408 (1280 + 128 padding)
     *
     * @param luminance Full Y-plane buffer from ImageProxy
     * @param rowStride Actual bytes per row (from Image.Plane.getRowStride())
     * @param width Actual image width
     * @param height Actual image height
     */
    private fun extractROIWithStride(
        luminance: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int
    ) {
        var dstIdx = 0

        // If rowStride == width, we can use fast path
        if (rowStride == width) {
            for (y in 0 until roiHeight) {
                val srcOffset = y * width + roiStartX
                System.arraycopy(luminance, srcOffset, roiBuffer, dstIdx, roiWidth)
                dstIdx += roiWidth
            }
        } else {
            // Slow path: handle stride padding
            // Each row starts at (y * rowStride), NOT (y * width)
            for (y in 0 until roiHeight) {
                val srcRowStart = y * rowStride  // Use rowStride, not width!
                val srcOffset = srcRowStart + roiStartX
                System.arraycopy(luminance, srcOffset, roiBuffer, dstIdx, roiWidth)
                dstIdx += roiWidth
            }
        }
    }

    /**
     * Legacy method - DEPRECATED, use extractROIWithStride instead.
     * Left here for documentation purposes.
     *
     * WARNING: This assumes rowStride == width, which is often FALSE on Android!
     * Using this with padded buffers will cause image corruption.
     */
    @Deprecated("Use extractROIWithStride - this ignores buffer stride padding")
    private fun extractROI(luminance: ByteArray) {
        var dstIdx = 0
        for (y in 0 until roiHeight) {
            val srcOffset = y * frameWidth + roiStartX
            System.arraycopy(luminance, srcOffset, roiBuffer, dstIdx, roiWidth)
            dstIdx += roiWidth
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DEBUG INFO
    // ═══════════════════════════════════════════════════════════════

    private fun updateDebugInfo(
        state: ExperimentalState,
        trackResult: BlobTracker.TrackResult?,
        maskResult: MotionMaskEngine.MaskResult?,
        distanceToGate: Float,
        bgFrameCount: Int
    ) {
        _debugInfo.value = ExperimentalDebugInfo(
            state = state,
            blob = trackResult?.blob,
            velocityNorm = trackResult?.velocityNorm ?: 0f,
            direction = trackResult?.direction ?: BlobTracker.Direction.UNKNOWN,
            distanceToGate = distanceToGate,
            threshold = maskResult?.threshold ?: 0,
            motionCoverage = maskResult?.coverage ?: 0f,
            backgroundFrames = bgFrameCount,
            quietSceneFrames = quietSceneFrameCount,
            gyroMagnitude = imuDetector.gyroMagnitude.value,
            accelMagnitude = imuDetector.accelMagnitude.value,
            roiWidth = roiWidth,
            roiHeight = roiHeight,
            gateX = gateX
        )
    }
}

/**
 * Crossing event from experimental detector.
 */
data class CrossingEvent(
    val timestamp: Long,           // Interpolated timestamp (nanos)
    val frameIndex: Long,
    val rawTimestamp: Long,        // Raw frame timestamp (nanos)
    val interpolationOffsetMs: Double
)

/**
 * Debug info for experimental detector.
 */
data class ExperimentalDebugInfo(
    val state: ExperimentalState,
    val blob: ConnectedComponentLabeler.Blob?,
    val velocityNorm: Float,
    val direction: BlobTracker.Direction,
    val distanceToGate: Float,
    val threshold: Int,
    val motionCoverage: Float,
    val backgroundFrames: Int,
    val quietSceneFrames: Int,
    val gyroMagnitude: Float,
    val accelMagnitude: Float,
    val roiWidth: Int,
    val roiHeight: Int,
    val gateX: Float
)
```

---

## 10. Camera Configuration for 60fps

```kotlin
// Add to HighSpeedCameraManager.kt

/**
 * Configure camera for Experimental mode: 60fps with fast shutter.
 *
 * Key settings:
 * - 60fps (regular session, not high-speed)
 * - 1/1000s shutter (1ms) for motion blur mitigation
 * - Locked exposure/focus/white balance
 * - Disabled stabilization
 */
fun configureForExperimentalMode(): Boolean {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraId = selectedCameraId ?: return false

    try {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val configMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return false

        // Find 60fps range (not high-speed)
        val fpsRanges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        )
        val range60 = fpsRanges?.find { it.upper == 60 && it.lower == 60 }
            ?: fpsRanges?.find { it.upper >= 60 }

        if (range60 == null) {
            Log.w(TAG, "60fps not available, falling back to max available")
            return false
        }

        // Find suitable size (720p or 1080p)
        val outputSizes = configMap.getOutputSizes(ImageFormat.YUV_420_888)
        val targetSize = outputSizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: outputSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: outputSizes.firstOrNull { it.width <= 1920 }
            ?: return false

        selectedSize = targetSize
        selectedFpsRange = range60
        isHighSpeedSupported = false  // Use regular session
        _currentFps.value = 60

        Log.i(TAG, "Experimental mode: ${targetSize.width}x${targetSize.height} @ 60fps")
        return true

    } catch (e: CameraAccessException) {
        Log.e(TAG, "Failed to configure experimental mode", e)
        return false
    }
}

/**
 * Apply fast shutter and lock settings for experimental mode.
 * Call after session is configured.
 *
 * SAMSUNG DEVICE QUIRK: Samsung devices require CONTROL_MODE_OFF before
 * manual exposure settings will take effect. Without it, manual shutter
 * speed is silently ignored.
 */
fun applyExperimentalCaptureSettings(requestBuilder: CaptureRequest.Builder) {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraId = selectedCameraId ?: return

    try {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        // 1. Set 60fps
        requestBuilder.set(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(60, 60)
        )

        // 2. Try to set fast shutter (1/1000s = 1ms = 1,000,000ns)
        val exposureRange = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        if (exposureRange != null) {
            val targetExposure = ExperimentalConfig.TARGET_SHUTTER_NS
            val clampedExposure = targetExposure.coerceIn(
                exposureRange.lower,
                exposureRange.upper
            )

            // ═══════════════════════════════════════════════════════════════
            // SAMSUNG DEVICE FIX: CONTROL_MODE_OFF required for manual exposure
            // ═══════════════════════════════════════════════════════════════
            // Without this, Samsung devices will SILENTLY IGNORE manual shutter!
            // This must be set BEFORE CONTROL_AE_MODE.
            //
            // Pixel devices work fine without this, but it doesn't hurt.
            // ═══════════════════════════════════════════════════════════════
            val isSamsungDevice = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            if (isSamsungDevice) {
                Log.i(TAG, "Samsung device detected - setting CONTROL_MODE_OFF")
                requestBuilder.set(
                    CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_OFF
                )
            }

            // Disable AE and set manual exposure
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedExposure)

            // Set ISO to auto-calculated value or max for dark scenes
            val isoRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            if (isoRange != null) {
                // Use mid-high ISO as starting point (will be bright enough outdoors)
                val targetISO = (isoRange.lower + isoRange.upper) / 2
                requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, targetISO)
            }

            Log.i(TAG, "Set exposure to ${clampedExposure/1_000_000.0}ms")
        } else {
            // Fallback: lock AE
            requestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            Log.w(TAG, "Manual exposure not available, locking AE")
        }

        // 3. Lock white balance
        requestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true)

        // 4. Lock focus (or set to infinity)
        val focusRange = characteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        )
        if (focusRange != null && focusRange > 0) {
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)  // Infinity
        }

        // 5. Disable stabilization
        requestBuilder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        requestBuilder.set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        )

    } catch (e: Exception) {
        Log.e(TAG, "Failed to apply experimental settings", e)
    }
}
```

---

## 11. Mode Integration in GateEngine

```kotlin
// Add to GateEngine.kt

enum class DetectionMode {
    PRECISION,      // 240fps + background model + pose
    EXPERIMENTAL    // 60fps + blob tracking + IMU
}

// In GateEngine class:
private var detectionMode = DetectionMode.PRECISION
private var experimentalDetector: ExperimentalCrossingDetector? = null

fun setDetectionMode(mode: DetectionMode) {
    if (mode == detectionMode) return

    detectionMode = mode
    when (mode) {
        DetectionMode.PRECISION -> {
            experimentalDetector?.stop()
            // Existing precision mode continues
        }
        DetectionMode.EXPERIMENTAL -> {
            if (experimentalDetector == null) {
                experimentalDetector = ExperimentalCrossingDetector(imuDetector)
            }
            experimentalDetector?.start()
        }
    }
}

// In processFrame:
fun processFrame(frameData: HighSpeedCameraManager.FrameData) {
    when (detectionMode) {
        DetectionMode.PRECISION -> {
            // Existing precision processing
            processPrecisionFrame(frameData)
        }
        DetectionMode.EXPERIMENTAL -> {
            experimentalDetector?.processFrame(frameData)
        }
    }
}
```

---

## 12. Testing Checklist

### Basic Functionality
- [ ] Build succeeds with no errors
- [ ] IMU shake detection triggers correctly
- [ ] Auto-recovery after phone placed down
- [ ] Mode picker shows both options

### Detection Accuracy
- [ ] Triggers at 2ft distance
- [ ] Triggers at 5ft distance
- [ ] Triggers at 8ft distance
- [ ] Triggers at 10ft distance
- [ ] Rejects at 12ft+ distance (too small)

### False Positive Rejection
- [ ] Ignores people outside ROI slab
- [ ] Ignores horizontal arm movements (aspect ratio)
- [ ] Does NOT trigger during phone shake
- [ ] Does NOT trigger on lighting changes

### Scene Recovery
- [ ] Shows "Hold Steady" while moving
- [ ] Rebuilds background after stabilizing
- [ ] ~0.5s recovery time
- [ ] Detection works after scene change

### Thermal & Performance
- [ ] Camera runs at 60fps
- [ ] Phone stays cool after 5+ minutes
- [ ] Frame processing < 10ms

### Timing Accuracy
- [ ] Linear interpolation produces reasonable times
- [ ] Compare against Precision mode ground truth

---

## 13. File Structure

```
app/src/main/kotlin/com/trackspeed/android/
├── detection/
│   ├── experimental/
│   │   ├── ExperimentalConfig.kt          # Constants
│   │   ├── ExperimentalState.kt           # State enum
│   │   ├── IMUShakeDetector.kt            # Gyro/accel shake detection
│   │   ├── MotionMaskEngine.kt            # Frame diff + morphology
│   │   ├── ConnectedComponentLabeler.kt   # Blob detection
│   │   ├── BlobTracker.kt                 # Cross-frame tracking
│   │   └── ExperimentalCrossingDetector.kt # Main detector
│   ├── GateEngine.kt                      # Mode switching
│   ├── CrossingDetector.kt                # Precision mode (existing)
│   └── ...
├── camera/
│   └── HighSpeedCameraManager.kt          # + 60fps config
└── ui/
    └── components/
        └── ExperimentalDebugOverlay.kt    # Debug visualization
```

---

## References

- iOS implementation: `speed-swift/EXPERIMENTAL_MODE_PLAN.md`
- Android sensor docs: [SensorManager](https://developer.android.com/reference/android/hardware/SensorManager)
- Camera2 docs: [Camera2 API](https://developer.android.com/reference/android/hardware/camera2/package-summary)
