# TrackSpeed Android - Precision Detection Algorithm

**Version:** 1.0
**Last Updated:** January 2026

This document details the Precision detection mode algorithm, ported from the iOS Speed Swift app.

---

## 1. Overview

The Precision detection mode uses a **dual-loop architecture**:
- **Slow Loop (30Hz)**: ML Kit pose detection for torso tracking
- **Fast Loop (240Hz)**: Background subtraction + crossing detection

The algorithm follows a "gate line is king" philosophy - the 1-pixel vertical slit at the gate position is the authoritative detection zone.

```
┌──────────────────────────────────────────────────────────────┐
│                    PRECISION DETECTION MODE                   │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│   ┌─────────────────────┐      ┌─────────────────────┐      │
│   │   POSE SERVICE      │      │   CAMERA MANAGER    │      │
│   │   (30 Hz)           │      │   (240 Hz)          │      │
│   │                     │      │                     │      │
│   │ • ML Kit detection  │      │ • High-speed capture│      │
│   │ • Shoulder/hip Y    │      │ • YUV frames        │      │
│   │ • EMA smoothing     │      │ • Exposure lock     │      │
│   └─────────┬───────────┘      └─────────┬───────────┘      │
│             │                            │                   │
│             │  Torso bounds              │  Frames           │
│             │  (thread-safe)             │                   │
│             ▼                            ▼                   │
│   ┌──────────────────────────────────────────────────────┐  │
│   │                   GATE ENGINE                         │  │
│   │                                                       │  │
│   │  1. Extract 3 vertical slits (left, center, right)   │  │
│   │  2. Background subtraction → foreground mask          │  │
│   │  3. Filter to torso/chest band                        │  │
│   │  4. Calculate occupancy ratios                        │  │
│   │  5. Validate with 3-strip torso check                 │  │
│   │  6. Run crossing detector state machine               │  │
│   │  7. Sub-frame interpolation on trigger                │  │
│   └──────────────────────────────────────────────────────┘  │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. Threshold Constants

### 2.1 Primary Detection Thresholds

```kotlin
object DetectionConfig {
    // Occupancy thresholds (with hysteresis)
    const val ENTER_THRESHOLD = 0.22f        // First contact - triggers interest
    const val CONFIRM_THRESHOLD = 0.35f      // Must reach for valid crossing
    const val GATE_CLEAR_BELOW = 0.15f       // Must drop below to start clear timer
    const val GATE_UNCLEAR_ABOVE = 0.25f     // Going above resets clear timer

    // Fallback thresholds (when no pose detected)
    const val FALLBACK_ARM_THRESHOLD = 0.65f // Higher bar without pose guidance

    // Strong evidence override
    const val STRONG_EVIDENCE_THRESHOLD = 0.85f // Bypasses proximity check

    // Persistence filtering
    const val PERSISTENCE_FRAMES = 2         // Frames above confirm threshold

    // Timing durations
    const val GATE_CLEAR_DURATION_MS = 200   // 0.2s clear before arming
    const val POSTROLL_DURATION_MS = 200     // 0.2s capture after trigger
    const val COOLDOWN_DURATION_MS = 100     // 0.1s between crossings
}
```

### 2.2 Distance Filtering

```kotlin
object DistanceFilterConfig {
    // Reject objects that are too far away (appear small)
    const val MIN_TORSO_HEIGHT_FRACTION = 0.12f  // 12% of frame height

    // Applied BEFORE evidence accumulation to prevent far-away triggers
}
```

### 2.3 Chest Band Configuration

```kotlin
object ChestBandConfig {
    // Band sizing (relative to torso)
    const val BAND_HALF_HEIGHT_FACTOR = 0.03f  // 3% of torso height
    const val BAND_HALF_HEIGHT_MIN = 8         // Minimum 8 pixels
    const val BAND_HALF_HEIGHT_MAX = 20        // Maximum 20 pixels

    // Minimum run requirements
    const val MIN_RUN_CHEST_FRACTION = 0.55f   // 55% of band height
    const val ABSOLUTE_MIN_RUN_CHEST = 12      // At least 12 pixels
}
```

### 2.4 Three-Strip Validation

```kotlin
object ThreeStripConfig {
    // Adjacent strip threshold for torso-like validation
    const val ADJACENT_STRIP_THRESHOLD = 0.55f

    // Validates crossing is from a wide object (torso), not thin (arm/leg)
    // At least one adjacent strip (left or right) must have 55% occupancy
}
```

### 2.5 Pose Proximity Validation

```kotlin
object ProximityConfig {
    // Quality-dependent distance tolerance
    const val FULL_POSE_MAX_DISTANCE = 0.30f    // Full pose (shoulder+hip)
    const val PARTIAL_POSE_MAX_DISTANCE = 0.20f // Shoulder-only (stricter)

    // Crossing must be near detected pose position
    // Prevents triggering on objects far from athlete
}
```

---

## 3. Background Model

### 3.1 Calibration Parameters

```kotlin
object BackgroundModelConfig {
    const val CALIBRATION_FRAMES = 30        // Frames to collect
    const val MIN_MAD = 10.0f                // Minimum MAD threshold
    const val MAD_MULTIPLIER = 3.5f          // Threshold = MAD × multiplier
    const val DEFAULT_THRESHOLD = 45.0f      // Fallback if MAD too low
    const val SAMPLING_BAND_WIDTH = 5        // Pixels around gate line
    const val ADAPTATION_RATE = 0.002f       // Very slow adaptation (0.2%)
}
```

### 3.2 Algorithm

```kotlin
class BackgroundModel {
    // Per-row statistics
    private lateinit var medians: FloatArray      // Background luminance
    private lateinit var mads: FloatArray         // Median Absolute Deviation
    private lateinit var thresholds: FloatArray   // Adaptive per-row threshold

    // Calibration buffer
    private val calibrationFrames = mutableListOf<ByteArray>()

    fun addCalibrationFrame(luminanceColumn: ByteArray) {
        if (calibrationFrames.size < CALIBRATION_FRAMES) {
            calibrationFrames.add(luminanceColumn.copyOf())
        }
    }

    fun finishCalibration(): Boolean {
        if (calibrationFrames.size < CALIBRATION_FRAMES) return false

        val height = calibrationFrames[0].size
        medians = FloatArray(height)
        mads = FloatArray(height)
        thresholds = FloatArray(height)

        for (row in 0 until height) {
            // Collect all values for this row across frames
            val values = calibrationFrames.map {
                it[row].toInt() and 0xFF  // Unsigned byte
            }.sorted()

            // Calculate median
            medians[row] = values[values.size / 2].toFloat()

            // Calculate MAD (Median Absolute Deviation)
            val deviations = values.map { abs(it - medians[row]) }.sorted()
            mads[row] = deviations[deviations.size / 2]

            // Calculate adaptive threshold
            thresholds[row] = maxOf(
                MIN_MAD,
                MAD_MULTIPLIER * mads[row],
                DEFAULT_THRESHOLD  // Fallback for low-variance regions
            )
        }

        calibrationFrames.clear()
        return true
    }

    fun getForegroundMask(luminanceColumn: ByteArray): BooleanArray {
        return BooleanArray(luminanceColumn.size) { row ->
            val pixel = (luminanceColumn[row].toInt() and 0xFF).toFloat()
            abs(pixel - medians[row]) > thresholds[row]
        }
    }

    // Slow adaptation for gradual lighting changes
    fun adaptBackground(luminanceColumn: ByteArray) {
        for (row in luminanceColumn.indices) {
            val pixel = (luminanceColumn[row].toInt() and 0xFF).toFloat()
            medians[row] = medians[row] * (1 - ADAPTATION_RATE) + pixel * ADAPTATION_RATE
        }
    }
}
```

### 3.3 Three-Strip Extraction

For torso thickness validation, extract 3 vertical strips:

```kotlin
fun extractThreeStrips(
    frame: Image,
    gatePosition: Float,  // 0.0 - 1.0
    stripDelta: Int = maxOf(3, frame.width / 100)
): ThreeStrips {
    val centerX = (gatePosition * frame.width).toInt()
    val leftX = maxOf(0, centerX - stripDelta)
    val rightX = minOf(frame.width - 1, centerX + stripDelta)

    return ThreeStrips(
        left = extractColumn(frame, leftX),
        center = extractColumn(frame, centerX),
        right = extractColumn(frame, rightX)
    )
}
```

---

## 4. Crossing Detector State Machine

### 4.1 States

```kotlin
enum class DetectionState {
    WAITING_FOR_CLEAR,  // Gate must be empty for 0.2s
    ARMED,              // Ready to detect crossing
    POSTROLL,           // Capturing frames after trigger (0.2s)
    COOLDOWN,           // Brief pause before next detection
    PAUSED              // Detection suspended (e.g., after start phone triggers)
}
```

### 4.2 State Transitions

```
                         occupancy < GATE_CLEAR_BELOW
                         for GATE_CLEAR_DURATION
    ┌───────────────────────────────────────────────┐
    │                                               │
    ▼                                               │
┌───────────────────┐                       ┌───────────────────┐
│  WAITING_FOR_CLEAR │──────────────────────│      PAUSED       │
│                   │                       │                   │
│ • Monitor gate    │                       │ • Detection off   │
│ • Clear timer     │                       │ • Manual resume   │
└─────────┬─────────┘                       └───────────────────┘
          │
          │ Gate clear for 0.2s
          ▼
┌───────────────────┐
│       ARMED       │◄─────────────────────────────────────┐
│                   │                                      │
│ • Ready to detect │                                      │
│ • Monitoring occ. │                                      │
└─────────┬─────────┘                                      │
          │                                                │
          │ occupancy >= ENTER_THRESHOLD                   │
          │ AND persistence >= 2 frames                    │
          │ AND distance filter passes                     │
          │ AND 3-strip validation passes                  │
          ▼                                                │
┌───────────────────┐                                      │
│     POSTROLL      │                                      │
│                   │                                      │
│ • Trigger event!  │                                      │
│ • Capture 0.2s    │                                      │
│ • Sub-frame interp│                                      │
└─────────┬─────────┘                                      │
          │                                                │
          │ Postroll complete (0.2s)                       │
          ▼                                                │
┌───────────────────┐                                      │
│     COOLDOWN      │                                      │
│                   │                                      │
│ • Prevent double  │                                      │
│ • 0.1s pause      │──────────────────────────────────────┘
└───────────────────┘
```

### 4.3 Implementation

```kotlin
class CrossingDetector {
    // State
    var state: DetectionState = DetectionState.WAITING_FOR_CLEAR
        private set

    // Timers (in frame counts at 240fps)
    private var clearFrameCount = 0
    private var postrollFrameCount = 0
    private var cooldownFrameCount = 0

    // Persistence tracking
    private var framesAboveConfirm = 0

    // History for interpolation
    private val occupancyHistory = RingBuffer<OccupancySample>(capacity = 5)

    data class OccupancySample(
        val occupancy: Float,
        val timestamp: Long,  // Nanoseconds
        val frameIndex: Int
    )

    data class CrossingResult(
        val interpolatedTimestamp: Long,  // Nanoseconds
        val triggerFrameIndex: Int,
        val occupancyAtTrigger: Float,
        val interpolationOffsetMs: Double
    )

    fun processFrame(
        occupancy: Float,
        timestamp: Long,
        frameIndex: Int,
        torsoBounds: TorsoBounds?,
        threeStripResult: ThreeStripResult
    ): CrossingResult? {

        // Store history for interpolation
        occupancyHistory.add(OccupancySample(occupancy, timestamp, frameIndex))

        when (state) {
            DetectionState.WAITING_FOR_CLEAR -> {
                if (occupancy < GATE_CLEAR_BELOW) {
                    clearFrameCount++
                    val clearDurationMs = clearFrameCount * (1000.0 / 240.0)
                    if (clearDurationMs >= GATE_CLEAR_DURATION_MS) {
                        state = DetectionState.ARMED
                        clearFrameCount = 0
                    }
                } else if (occupancy > GATE_UNCLEAR_ABOVE) {
                    clearFrameCount = 0  // Reset timer
                }
            }

            DetectionState.ARMED -> {
                if (occupancy >= ENTER_THRESHOLD) {
                    // Check persistence
                    if (occupancy >= CONFIRM_THRESHOLD) {
                        framesAboveConfirm++
                    } else {
                        framesAboveConfirm = 0
                    }

                    // Validate crossing
                    if (framesAboveConfirm >= PERSISTENCE_FRAMES &&
                        validateCrossing(occupancy, torsoBounds, threeStripResult)) {

                        // TRIGGER!
                        state = DetectionState.POSTROLL
                        postrollFrameCount = 0
                        framesAboveConfirm = 0

                        return interpolateCrossingTime()
                    }
                } else {
                    framesAboveConfirm = 0
                }
            }

            DetectionState.POSTROLL -> {
                postrollFrameCount++
                val postrollDurationMs = postrollFrameCount * (1000.0 / 240.0)
                if (postrollDurationMs >= POSTROLL_DURATION_MS) {
                    state = DetectionState.COOLDOWN
                    cooldownFrameCount = 0
                }
            }

            DetectionState.COOLDOWN -> {
                cooldownFrameCount++
                val cooldownDurationMs = cooldownFrameCount * (1000.0 / 240.0)
                if (cooldownDurationMs >= COOLDOWN_DURATION_MS) {
                    state = DetectionState.WAITING_FOR_CLEAR
                }
            }

            DetectionState.PAUSED -> {
                // Do nothing, wait for manual resume
            }
        }

        return null
    }

    private fun validateCrossing(
        occupancy: Float,
        torsoBounds: TorsoBounds?,
        threeStripResult: ThreeStripResult
    ): Boolean {
        // 1. Distance filter - reject far-away objects
        if (torsoBounds != null) {
            val torsoHeightFraction = torsoBounds.yBottom - torsoBounds.yTop
            if (torsoHeightFraction < MIN_TORSO_HEIGHT_FRACTION) {
                return false  // Too far away
            }
        }

        // 2. Three-strip validation - must be torso-like (wide)
        val isTorsoLike = threeStripResult.centerOccupancy >= CONFIRM_THRESHOLD &&
            threeStripResult.centerRun >= ABSOLUTE_MIN_RUN_CHEST &&
            (threeStripResult.leftOccupancy >= ADJACENT_STRIP_THRESHOLD ||
             threeStripResult.rightOccupancy >= ADJACENT_STRIP_THRESHOLD)

        if (!isTorsoLike && occupancy < STRONG_EVIDENCE_THRESHOLD) {
            return false  // Likely arm/leg, not torso
        }

        // 3. Proximity check (if pose available)
        if (torsoBounds != null && occupancy < STRONG_EVIDENCE_THRESHOLD) {
            val maxDistance = if (torsoBounds.hasFullPose) {
                FULL_POSE_MAX_DISTANCE
            } else {
                PARTIAL_POSE_MAX_DISTANCE
            }
            // Check crossing is near pose position
            // (Implementation depends on how you track crossing Y position)
        }

        return true
    }

    fun pause() { state = DetectionState.PAUSED }
    fun resume() { state = DetectionState.WAITING_FOR_CLEAR }
    fun reset() {
        state = DetectionState.WAITING_FOR_CLEAR
        clearFrameCount = 0
        framesAboveConfirm = 0
        occupancyHistory.clear()
    }
}
```

---

## 5. Sub-Frame Interpolation

### 5.1 Quadratic Interpolation (Preferred)

When we have 3+ samples below threshold followed by one above:

```kotlin
fun interpolateCrossingTime(): CrossingResult {
    val samples = occupancyHistory.toList()

    // Find samples below threshold (for curve fitting)
    val belowThreshold = samples.filter { it.occupancy < ENTER_THRESHOLD }
    val aboveThreshold = samples.lastOrNull { it.occupancy >= ENTER_THRESHOLD }
        ?: return linearFallback(samples)

    if (belowThreshold.size >= 3) {
        // Quadratic interpolation
        return quadraticInterpolation(belowThreshold.takeLast(3), aboveThreshold)
    } else if (belowThreshold.isNotEmpty()) {
        // Linear interpolation fallback
        return linearInterpolation(belowThreshold.last(), aboveThreshold)
    }

    // No interpolation possible
    return CrossingResult(
        interpolatedTimestamp = aboveThreshold.timestamp,
        triggerFrameIndex = aboveThreshold.frameIndex,
        occupancyAtTrigger = aboveThreshold.occupancy,
        interpolationOffsetMs = 0.0
    )
}

private fun quadraticInterpolation(
    belowSamples: List<OccupancySample>,
    aboveSample: OccupancySample
): CrossingResult {
    // Fit quadratic: r(u) = au² + bu + c
    // Where u is normalized time (0 = first sample, 1 = last sample)

    val t0 = belowSamples[0].timestamp
    val tRange = (aboveSample.timestamp - t0).toDouble()

    // Normalize times to [0, 1]
    val points = belowSamples.map { sample ->
        val u = (sample.timestamp - t0).toDouble() / tRange
        Pair(u, sample.occupancy.toDouble())
    }

    // Solve for coefficients using least squares
    // (Simplified - use proper matrix math in production)
    val (a, b, c) = fitQuadratic(points)

    // Solve au² + bu + (c - threshold) = 0
    val threshold = ENTER_THRESHOLD.toDouble()
    val discriminant = b * b - 4 * a * (c - threshold)

    if (discriminant < 0) {
        return linearInterpolation(belowSamples.last(), aboveSample)
    }

    val u = (-b + sqrt(discriminant)) / (2 * a)
    val interpolatedTime = t0 + (u * tRange).toLong()

    // Calculate offset from trigger frame
    val offsetNanos = aboveSample.timestamp - interpolatedTime
    val offsetMs = offsetNanos / 1_000_000.0

    return CrossingResult(
        interpolatedTimestamp = interpolatedTime,
        triggerFrameIndex = aboveSample.frameIndex,
        occupancyAtTrigger = aboveSample.occupancy,
        interpolationOffsetMs = offsetMs
    )
}
```

### 5.2 Linear Interpolation (Fallback)

```kotlin
private fun linearInterpolation(
    below: OccupancySample,
    above: OccupancySample
): CrossingResult {
    // Linear interpolation between two samples
    val r0 = below.occupancy
    val r1 = above.occupancy
    val t0 = below.timestamp
    val t1 = above.timestamp

    // Solve: r0 + (r1-r0) * alpha = threshold
    val alpha = (ENTER_THRESHOLD - r0) / (r1 - r0)
    val interpolatedTime = t0 + ((t1 - t0) * alpha).toLong()

    val offsetMs = (above.timestamp - interpolatedTime) / 1_000_000.0

    return CrossingResult(
        interpolatedTimestamp = interpolatedTime,
        triggerFrameIndex = above.frameIndex,
        occupancyAtTrigger = above.occupancy,
        interpolationOffsetMs = offsetMs
    )
}
```

---

## 6. Contiguous Run Filter

### 6.1 Chest Band Calculation

```kotlin
data class ChestBand(
    val top: Int,      // Pixel row
    val bottom: Int,   // Pixel row
    val height: Int    // Band height in pixels
)

fun calculateChestBand(torsoBounds: TorsoBounds, frameHeight: Int): ChestBand {
    // Torso bounds are normalized (0-1)
    val torsoTopPx = (torsoBounds.yTop * frameHeight).toInt()
    val torsoBottomPx = (torsoBounds.yBottom * frameHeight).toInt()
    val torsoHeightPx = torsoBottomPx - torsoTopPx

    // Chest is approximately 1/3 from top of torso
    val chestCenterPx = torsoTopPx + (torsoHeightPx * 0.33f).toInt()

    // Band half-height based on torso size
    val bandHalfHeight = (BAND_HALF_HEIGHT_FACTOR * torsoHeightPx)
        .toInt()
        .coerceIn(BAND_HALF_HEIGHT_MIN, BAND_HALF_HEIGHT_MAX)

    return ChestBand(
        top = (chestCenterPx - bandHalfHeight).coerceAtLeast(0),
        bottom = (chestCenterPx + bandHalfHeight).coerceAtMost(frameHeight - 1),
        height = bandHalfHeight * 2
    )
}
```

### 6.2 Longest Contiguous Run

```kotlin
data class RunResult(
    val longestRun: Int,           // Pixels
    val occupancy: Float,          // longestRun / bandHeight
    val runStartRow: Int,
    val runEndRow: Int
)

fun findLongestRun(
    foregroundMask: BooleanArray,
    band: ChestBand
): RunResult {
    var longestRun = 0
    var longestStart = band.top
    var longestEnd = band.top

    var currentRun = 0
    var currentStart = band.top

    for (row in band.top..band.bottom) {
        if (foregroundMask[row]) {
            if (currentRun == 0) {
                currentStart = row
            }
            currentRun++
        } else {
            if (currentRun > longestRun) {
                longestRun = currentRun
                longestStart = currentStart
                longestEnd = row - 1
            }
            currentRun = 0
        }
    }

    // Check final run
    if (currentRun > longestRun) {
        longestRun = currentRun
        longestStart = currentStart
        longestEnd = band.bottom
    }

    return RunResult(
        longestRun = longestRun,
        occupancy = longestRun.toFloat() / band.height,
        runStartRow = longestStart,
        runEndRow = longestEnd
    )
}
```

### 6.3 Three-Strip Analysis

```kotlin
data class ThreeStripResult(
    val leftOccupancy: Float,
    val centerOccupancy: Float,
    val rightOccupancy: Float,
    val leftRun: Int,
    val centerRun: Int,
    val rightRun: Int,
    val isTorsoLike: Boolean
)

fun analyzeThreeStrips(
    leftMask: BooleanArray,
    centerMask: BooleanArray,
    rightMask: BooleanArray,
    band: ChestBand
): ThreeStripResult {
    val leftResult = findLongestRun(leftMask, band)
    val centerResult = findLongestRun(centerMask, band)
    val rightResult = findLongestRun(rightMask, band)

    // Torso-like: center is solid AND at least one side is solid
    val isTorsoLike = centerResult.occupancy >= CONFIRM_THRESHOLD &&
        centerResult.longestRun >= ABSOLUTE_MIN_RUN_CHEST &&
        (leftResult.occupancy >= ADJACENT_STRIP_THRESHOLD ||
         rightResult.occupancy >= ADJACENT_STRIP_THRESHOLD)

    return ThreeStripResult(
        leftOccupancy = leftResult.occupancy,
        centerOccupancy = centerResult.occupancy,
        rightOccupancy = rightResult.occupancy,
        leftRun = leftResult.longestRun,
        centerRun = centerResult.longestRun,
        rightRun = rightResult.longestRun,
        isTorsoLike = isTorsoLike
    )
}
```

---

## 7. Pose Service Integration

### 7.1 Torso Bounds

```kotlin
data class TorsoBounds(
    val yTop: Float,        // Normalized 0-1 (shoulder Y)
    val yBottom: Float,     // Normalized 0-1 (hip Y)
    val confidence: Float,  // Detection confidence
    val hasFullPose: Boolean // Both shoulder and hip detected
)
```

### 7.2 EMA Smoothing

```kotlin
class TorsoBoundsStore {
    private var smoothedTop: Float? = null
    private var smoothedBottom: Float? = null

    private val alpha = 0.3f  // Smoothing factor

    @Volatile
    var currentBounds: TorsoBounds? = null
        private set

    fun update(rawTop: Float, rawBottom: Float, confidence: Float, hasFullPose: Boolean) {
        smoothedTop = smoothedTop?.let { alpha * rawTop + (1 - alpha) * it } ?: rawTop
        smoothedBottom = smoothedBottom?.let { alpha * rawBottom + (1 - alpha) * it } ?: rawBottom

        currentBounds = TorsoBounds(
            yTop = smoothedTop!!,
            yBottom = smoothedBottom!!,
            confidence = confidence,
            hasFullPose = hasFullPose
        )
    }

    fun reset() {
        smoothedTop = null
        smoothedBottom = null
        currentBounds = null
    }
}
```

### 7.3 ML Kit Integration (30Hz)

```kotlin
class PoseService(private val context: Context) {
    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val boundsStore = TorsoBoundsStore()
    val torsoBounds: TorsoBounds? get() = boundsStore.currentBounds

    private var frameSkipCounter = 0
    private val frameSkipInterval = 8  // Process every 8th frame (30Hz at 240fps)

    fun processFrame(image: InputImage): Boolean {
        frameSkipCounter++
        if (frameSkipCounter < frameSkipInterval) {
            return false  // Skip this frame
        }
        frameSkipCounter = 0

        // Async pose detection
        poseDetector.process(image)
            .addOnSuccessListener { pose -> handlePoseResult(pose, image.height) }
            .addOnFailureListener { /* Log error */ }

        return true
    }

    private fun handlePoseResult(pose: Pose, imageHeight: Int) {
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)

        val shoulderConfidence = minOf(
            leftShoulder?.inFrameLikelihood ?: 0f,
            rightShoulder?.inFrameLikelihood ?: 0f
        )
        val hipConfidence = minOf(
            leftHip?.inFrameLikelihood ?: 0f,
            rightHip?.inFrameLikelihood ?: 0f
        )

        val hasShoulders = shoulderConfidence > 0.2f
        val hasHips = hipConfidence > 0.2f

        if (hasShoulders) {
            val shoulderY = (leftShoulder!!.position.y + rightShoulder!!.position.y) / 2
            val normalizedTop = shoulderY / imageHeight

            val hipY = if (hasHips) {
                (leftHip!!.position.y + rightHip!!.position.y) / 2
            } else {
                // Estimate hip from shoulder (torso is ~50% of body height)
                shoulderY + (imageHeight * 0.25f)
            }
            val normalizedBottom = hipY / imageHeight

            boundsStore.update(
                rawTop = normalizedTop,
                rawBottom = normalizedBottom,
                confidence = if (hasHips) hipConfidence else shoulderConfidence * 0.7f,
                hasFullPose = hasShoulders && hasHips
            )
        }
    }

    fun reset() = boundsStore.reset()
}
```

---

## 8. Rolling Shutter Correction

### 8.1 Overview

Rolling shutter cameras read rows sequentially, not simultaneously. For a 4.7ms readout time (iPhone 15 Pro), top and bottom rows are captured 4.7ms apart.

### 8.2 Correction Formula

```kotlin
object RollingShutterConfig {
    // Device-specific readout times (milliseconds)
    // TODO: Calibrate per Android device model
    const val DEFAULT_READOUT_TIME_MS = 5.0  // Conservative estimate

    fun getReadoutTime(deviceModel: String): Double {
        return when {
            deviceModel.contains("Pixel 8") -> 4.5
            deviceModel.contains("Pixel 7") -> 4.8
            deviceModel.contains("Samsung S24") -> 4.3
            else -> DEFAULT_READOUT_TIME_MS
        }
    }
}

fun correctForRollingShutter(
    timestamp: Long,          // Frame timestamp (top row)
    crossingRow: Int,         // Row where crossing was detected
    frameHeight: Int,
    readoutTimeMs: Double
): Long {
    val rowFraction = crossingRow.toDouble() / frameHeight
    val correctionNanos = (rowFraction * readoutTimeMs * 1_000_000).toLong()
    return timestamp + correctionNanos
}
```

---

## 9. Complete Frame Processing Pipeline

```kotlin
class GateEngine(
    private val cameraManager: CameraManager,
    private val backgroundModel: BackgroundModel,
    private val crossingDetector: CrossingDetector,
    private val poseService: PoseService,
    private val compositeBuffer: CompositeBuffer
) {
    var gatePosition: Float = 0.5f  // Normalized 0-1

    fun processFrame(image: Image, timestamp: Long, frameIndex: Int) {
        // 1. Extract three strips at gate position
        val strips = extractThreeStrips(image, gatePosition)

        // 2. Get foreground masks via background subtraction
        val leftMask = backgroundModel.getForegroundMask(strips.left)
        val centerMask = backgroundModel.getForegroundMask(strips.center)
        val rightMask = backgroundModel.getForegroundMask(strips.right)

        // 3. Add center strip to composite buffer (photo-finish)
        compositeBuffer.addSlit(strips.center, timestamp)

        // 4. Get current torso bounds (updated at 30Hz by pose service)
        val torsoBounds = poseService.torsoBounds

        // 5. Calculate chest band (or use full frame if no pose)
        val band = if (torsoBounds != null) {
            calculateChestBand(torsoBounds, image.height)
        } else {
            ChestBand(0, image.height - 1, image.height)
        }

        // 6. Analyze three strips for torso-like shape
        val threeStripResult = analyzeThreeStrips(leftMask, centerMask, rightMask, band)

        // 7. Calculate center strip occupancy (primary detection value)
        val centerRunResult = findLongestRun(centerMask, band)
        val occupancy = centerRunResult.occupancy

        // 8. Run crossing detector state machine
        val crossingResult = crossingDetector.processFrame(
            occupancy = occupancy,
            timestamp = timestamp,
            frameIndex = frameIndex,
            torsoBounds = torsoBounds,
            threeStripResult = threeStripResult
        )

        // 9. Handle crossing if detected
        if (crossingResult != null) {
            // Apply rolling shutter correction
            val correctedTimestamp = correctForRollingShutter(
                timestamp = crossingResult.interpolatedTimestamp,
                crossingRow = centerRunResult.runStartRow,
                frameHeight = image.height,
                readoutTimeMs = RollingShutterConfig.getReadoutTime(Build.MODEL)
            )

            // Mark trigger frame in composite
            compositeBuffer.markTrigger(frameIndex)

            // Emit crossing event
            onCrossingDetected(crossingResult.copy(
                interpolatedTimestamp = correctedTimestamp
            ))
        }

        // 10. Trigger pose detection (every 8th frame)
        poseService.processFrame(image.toInputImage())

        // 11. Slow background adaptation (if armed and gate clear)
        if (crossingDetector.state == DetectionState.ARMED && occupancy < GATE_CLEAR_BELOW) {
            backgroundModel.adaptBackground(strips.center)
        }
    }
}
```

---

## 10. Debug Overlay Data

For development/debugging, expose these values:

```kotlin
data class DetectionDebugInfo(
    // Occupancy values
    val rLeft: Float,
    val rCenter: Float,
    val rRight: Float,

    // Run lengths
    val runLeft: Int,
    val runCenter: Int,
    val runRight: Int,

    // Torso bounds
    val torsoTop: Float?,
    val torsoBottom: Float?,
    val chestBandTop: Int,
    val chestBandBottom: Int,

    // State
    val state: DetectionState,
    val framesAboveConfirm: Int,

    // Validation results
    val isTorsoLike: Boolean,
    val passesDistanceFilter: Boolean,
    val passesProximityCheck: Boolean,

    // Rejection reason (if any)
    val rejectionReason: String?
)
```

---

## Appendix: iOS Source File References

| Android Component | iOS Source File |
|-------------------|-----------------|
| `DetectionConfig` | `CrossingDetector.swift` lines 12-45 |
| `BackgroundModel` | `BackgroundModel.swift` |
| `CrossingDetector` | `CrossingDetector.swift` lines 256-450 |
| `ContiguousRunFilter` | `ContiguousRunFilter.swift` |
| `Sub-frame interpolation` | `CrossingDetector.swift` lines 734-889 |
| `TorsoBoundsStore` | `TorsoBoundsStore.swift` |
| `PoseService` | `PoseService.swift` |
| `GateEngine` | `GateEngine.swift` |
