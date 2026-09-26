package com.trackspeed.android.detection

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ReplicaDetector — Photo Finish competitor replica built from 15 confirmed specs.
 *
 * 7-step detection pipeline:
 * 1. IMU stability gate (gyro 0.15 rad/s, 1s recovery)
 * 2. Motion extraction (max(N-1, N-2) diff, per-frame adaptive threshold, CCL)
 * 3. Global size check (configurable height/width fractions via distance preset)
 * 4. Leading edge + direction (all rows, no torso filter)
 * 5. Crossing detection (sign change on gate)
 * 6. Gate body check (configurable contiguous run via distance preset)
 * 7. Sub-frame interpolation + 0.75x exposure compensation
 *
 * Key design differences from PhotoFinishDetector:
 * - Combined max(N-1, N-2) frame differencing -> filled blobs with edges AND interior
 * - Two-stage architecture: global size check THEN gate contiguous check
 * - Contiguous vertical run at gate (not blob bbox height)
 * - No explicit velocity threshold
 * - On gate check failure, do NOT update prevChestX (allows torso re-fire)
 * - 0.2s cooldown
 *
 * Ported from iOS ReplicaDetector.swift.
 */
class ReplicaDetector(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "ReplicaDetector"

        // Work resolution (portrait only on Android)
        const val WORK_W = 160
        const val WORK_H = 284

        // IMU stability threshold (rad/s)
        private const val GYRO_THRESHOLD = 0.15

        // Duration phone must be stable before detection enabled
        private const val STABLE_DURATION_TO_ARM_S = 1.0

        // Minimum per-frame adaptive diff threshold floor
        private const val MIN_ADAPTIVE_THRESHOLD = 8

        // Number of columns to scan at gate (+/-3 = 7 total)
        private const val GATE_COLUMN_HALF_BAND = 3

        // Minimum centroid movement to update direction
        private const val MIN_DIRECTION_CHANGE_PX = 2.0f

        // Consecutive invalid frames before direction memory resets
        private const val DIRECTION_MEMORY_RESET_STREAK = 4

        // Cooldown duration after crossing
        private const val COOLDOWN_DURATION_S = 0.2

        // Warmup duration before detection
        private const val WARMUP_DURATION_S = 0.30

        // Exposure compensation factor (applied when exposure > 4ms)
        private const val DEFAULT_EXPOSURE_COMPENSATION = 0.75f

        // Debug log interval
        private const val DEBUG_LOG_DURATION_S = 10.0
    }

    // MARK: - Types

    enum class State(val label: String) {
        UNSTABLE("Hold Steady"),
        ATHLETE_TOO_FAR("Too Far"),
        READY("Ready"),
        TRIGGERED("Triggered"),
        COOLDOWN("Cooldown")
    }

    enum class DetectionDistance(val label: String) {
        CLOSE("close"),
        MEDIUM("medium"),
        FAR("far")
    }

    data class DetectionResult(
        val triggered: Boolean,
        val maxRunHeight: Int,
        val maxRunFraction: Float,
        val motionAmount: Float,
        val cameraStable: Boolean,
        val state: State,
        val chestX: Float?,
        val blobHeightFraction: Float?,
        val velocityPxPerSec: Float?
    )

    // MARK: - Public Configuration

    var gatePosition: Float = 0.5f
    var exposureCompensationFactor: Float = DEFAULT_EXPOSURE_COMPENSATION
    var sigmaMultiplier: Float = 3.5f
    var currentFPS: Double = 30.0
    var isFrontCamera: Boolean = false
    var gateRole: String = "finish"
    var startType: String = "flying"
    var isPaused: Boolean = false

    val requiresIMUStability: Boolean
        get() {
            if (gateRole == "start" && startType != "flying") {
                return false
            }
            return true
        }

    // FPS-dependent computed properties
    private val warmupFrames: Int get() = maxOf(1, (WARMUP_DURATION_S * currentFPS).toInt())
    private val debugLogInterval: Long get() = maxOf(1, (DEBUG_LOG_DURATION_S * currentFPS).toLong())

    // MARK: - IMU (Gyroscope)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    @Volatile private var currentGyroMagnitude: Double = 0.0
    @Volatile private var stableStartTime: Double? = null
    @Volatile var isPhoneStable: Boolean = false
        private set

    // MARK: - State

    var state: State = State.UNSTABLE
        private set

    // Frame dimensions (full resolution)
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0
    private var scaleX: Float = 1.0f
    private var scaleY: Float = 1.0f

    // MARK: - Preallocated Buffers (3 frames for max(N-1, N-2) differencing)

    private var currLumaSmall = ByteArray(WORK_W * WORK_H)
    private var prevLumaSmall = ByteArray(WORK_W * WORK_H)
    private var prevPrevLumaSmall = ByteArray(WORK_W * WORK_H)
    private var motionMask = ByteArray(WORK_W * WORK_H)

    private val cclEngine = ZeroAllocCCL(WORK_W, WORK_H)

    // MARK: - Distance Preset Thresholds

    private var minGlobalHeightFraction: Float = 0.20f
    private var minGlobalWidthFraction: Float = 0.08f
    private var minGateContiguousFraction: Float = 0.30f

    // MARK: - Tracking State

    /** Previous leading edge X. CRITICAL: NOT updated on gate check failure — allows torso re-fire. */
    private var prevChestX: Float? = null
    private var prevCentroidX: Float? = null
    private var lastConfirmedDirection: Boolean? = null // true = moving right
    private var prevTimestamp: Long = 0
    private var processedFrameCount: Int = 0
    private var cooldownStartTime: Double = 0.0
    private var frameSkipCounter: Int = 0
    private var didLogShakingRejection: Boolean = false
    private var invalidTrackingFrames: Int = 0

    // MARK: - Debug/UI State

    var lastMaxRunFraction: Float = 0f; private set
    var lastMotionAmount: Float = 0f; private set
    var lastRejection: String? = null; private set
    var lastVelocityPxPerSec: Float = 0f; private set
    var lastBlobCenterX: Float? = null; private set

    // MARK: - Callbacks

    /**
     * Called when crossing detected.
     * Parameters: adjustedPtsNanos, detectionFramePtsNanos, monotonicNanos, frameNumber, chestPositionNormalized
     */
    var onCrossingDetected: ((Long, Long, Long, Long, Float) -> Unit)? = null

    /** Called when post-roll completes (after cooldown). */
    var onPostrollComplete: (() -> Unit)? = null

    // MARK: - Histogram buffers (pre-allocated for per-frame adaptive threshold)

    private val diffHist = IntArray(256)
    private val madHist = IntArray(256)

    // MARK: - Configuration

    fun configure(fps: Double) {
        currentFPS = fps
        Log.i(TAG, "Configured for ${fps.toInt()} fps: warmup=$warmupFrames frames")
    }

    fun applyDistancePreset(distance: DetectionDistance) {
        when (distance) {
            DetectionDistance.CLOSE -> {
                minGlobalHeightFraction = 0.20f
                minGlobalWidthFraction = 0.08f
                minGateContiguousFraction = 0.30f
            }
            DetectionDistance.MEDIUM -> {
                minGlobalHeightFraction = 0.12f
                minGlobalWidthFraction = 0.05f
                minGateContiguousFraction = 0.18f
            }
            DetectionDistance.FAR -> {
                minGlobalHeightFraction = 0.08f
                minGlobalWidthFraction = 0.03f
                minGateContiguousFraction = 0.10f
            }
        }
        Log.i(TAG, "[REPLICA] Distance preset: ${distance.label} -> height>=$minGlobalHeightFraction, width>=$minGlobalWidthFraction, contiguous>=$minGateContiguousFraction")
    }

    // MARK: - IMU Management

    fun startMotionUpdates() {
        if (gyroscope == null) {
            Log.w(TAG, "Gyroscope not available")
            return
        }
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        Log.i(TAG, "Started IMU monitoring (gyro threshold: $GYRO_THRESHOLD rad/s)")
    }

    fun stopMotionUpdates() {
        sensorManager.unregisterListener(this)
        currentGyroMagnitude = 0.0
        stableStartTime = null
        isPhoneStable = false
        state = State.UNSTABLE
        Log.i(TAG, "Stopped IMU monitoring")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        val gx = event.values[0].toDouble()
        val gy = event.values[1].toDouble()
        val gz = event.values[2].toDouble()
        val magnitude = sqrt(gx * gx + gy * gy + gz * gz)

        currentGyroMagnitude = magnitude
        val currentTime = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0

        if (magnitude > GYRO_THRESHOLD) {
            isPhoneStable = false
            stableStartTime = null
            if (requiresIMUStability) {
                if (state != State.UNSTABLE && state != State.COOLDOWN) {
                    state = State.UNSTABLE
                }
            }
        } else {
            if (stableStartTime == null) {
                stableStartTime = currentTime
            }
            val stableDuration = currentTime - (stableStartTime ?: currentTime)
            if (stableDuration >= STABLE_DURATION_TO_ARM_S && !isPhoneStable) {
                isPhoneStable = true
                if (state == State.UNSTABLE) {
                    state = State.READY
                    Log.i(TAG, "Phone stable - detection ready (stable for ${String.format("%.2f", stableDuration)}s)")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // MARK: - Tracking Helpers

    private fun registerInvalidTrackingFrame() {
        invalidTrackingFrames++
        prevChestX = null
        prevCentroidX = null
        if (invalidTrackingFrames >= DIRECTION_MEMORY_RESET_STREAK) {
            lastConfirmedDirection = null
        }
    }

    private fun registerValidTrackingFrame() {
        invalidTrackingFrames = 0
    }

    // MARK: - Main Detection (7-Step Pipeline)

    /**
     * Process a single YUV frame.
     * @param yPlane Y (luminance) plane data
     * @param width Full frame width
     * @param height Full frame height
     * @param rowStride Row stride of Y plane
     * @param frameNumber Sequential frame number
     * @param ptsNanos Frame presentation timestamp in nanoseconds
     * @param exposureDuration Exposure duration in seconds (default 0.033)
     */
    fun processFrame(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        frameNumber: Long,
        ptsNanos: Long,
        exposureDuration: Double = 0.033
    ): DetectionResult {

        val frameCameraStable = isPhoneStable

        // ============================================================
        // Step 0: Paused / Thermal skip
        // ============================================================
        if (isPaused) {
            return makeResult(triggered = false, state = state, cameraStable = frameCameraStable)
        }

        // Thermal optimization: at >=100fps, skip every 2nd frame (except during cooldown)
        if (currentFPS >= 100) {
            frameSkipCounter++
            if (state != State.COOLDOWN && frameSkipCounter % 2 == 0) {
                return makeResult(triggered = false, state = state, cameraStable = frameCameraStable)
            }
        }

        val currentTime = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0

        // ============================================================
        // Step 1: IMU stability gate
        // ============================================================
        if (requiresIMUStability && !frameCameraStable) {
            state = State.UNSTABLE
            lastRejection = "cameraShaking"
            if (!didLogShakingRejection) {
                didLogShakingRejection = true
                Log.d(TAG, "[REPLICA] Phone shaking - detection paused (gyro=${String.format("%.2f", currentGyroMagnitude)} rad/s)")
            }
            return makeResult(triggered = false, state = State.UNSTABLE, cameraStable = false)
        }
        didLogShakingRejection = false
        if (state == State.UNSTABLE) {
            state = State.READY
        }

        // ============================================================
        // Step 2: Motion extraction (downsample -> max(N-1,N-2) diff -> mask -> CCL)
        // ============================================================
        downsampleLuma(yPlane, width, height, rowStride, currLumaSmall)

        // Handle cooldown (after downsample so buffers stay current)
        if (state == State.COOLDOWN) {
            if (currentTime - cooldownStartTime >= COOLDOWN_DURATION_S) {
                state = State.READY
                Log.d(TAG, "Cooldown complete")
            } else {
                rotateFrameBuffers()
                prevTimestamp = ptsNanos
                return makeResult(triggered = false, state = State.COOLDOWN, cameraStable = frameCameraStable)
            }
        }

        // Warmup period — let buffers fill before detecting
        processedFrameCount++
        if (processedFrameCount <= warmupFrames) {
            if (processedFrameCount == warmupFrames) {
                Log.i(TAG, "[REPLICA] ARMED at frame $frameNumber, using max(N-1, N-2) frame differencing, per-frame adaptive threshold")
            }
            rotateFrameBuffers()
            prevTimestamp = ptsNanos
            invalidTrackingFrames = 0
            prevChestX = null
            prevCentroidX = null
            lastConfirmedDirection = null
            return makeResult(triggered = false, state = State.READY, cameraStable = frameCameraStable)
        }

        // Per-frame adaptive threshold via histogram (median + sigma * MAD * 1.4826)
        val workSize = WORK_W * WORK_H
        val adaptiveThreshold = computeAdaptiveThreshold(workSize)

        // Apply motion mask using max(N-1, N-2) differencing
        // N-1 captures leading/trailing edges; N-2 has double displacement
        // so body interior that was invisible in N-1 becomes visible in N-2
        var motionCount = 0
        for (i in 0 until workSize) {
            val curr = currLumaSmall[i].toInt() and 0xFF
            val prev = prevLumaSmall[i].toInt() and 0xFF
            val prevPrev = prevPrevLumaSmall[i].toInt() and 0xFF
            val d1 = abs(curr - prev)
            val d2 = abs(curr - prevPrev)
            val diff = maxOf(d1, d2)
            if (diff > adaptiveThreshold) {
                motionMask[i] = 255.toByte()
                motionCount++
            } else {
                motionMask[i] = 0
            }
        }

        val motionRatio = motionCount.toFloat() / workSize
        lastMotionAmount = motionRatio

        // Need minimum motion pixels
        if (motionCount <= 50) {
            if (frameNumber % debugLogInterval == 0L) {
                Log.d(TAG, "[REPLICA] REJECT: lowMotion (pixels=$motionCount)")
            }
            rotateFrameBuffers()
            prevTimestamp = ptsNanos
            registerInvalidTrackingFrame()
            state = State.READY
            lastRejection = null
            return makeResult(triggered = false, state = State.READY, cameraStable = frameCameraStable)
        }

        // CCL — find blobs
        val blobs = cclEngine.process(motionMask, WORK_W, WORK_H)

        if (blobs.isEmpty()) {
            if (frameNumber % debugLogInterval == 0L) {
                Log.d(TAG, "[REPLICA] REJECT: noBlobs (motionPx=$motionCount)")
            }
            rotateFrameBuffers()
            prevTimestamp = ptsNanos
            registerInvalidTrackingFrame()
            state = State.READY
            lastRejection = null
            return makeResult(triggered = false, state = State.READY, cameraStable = frameCameraStable)
        }

        // Find largest blob by area
        val largest = blobs.maxByOrNull { it.areaPixels }!!
        val blobHeightFraction = largest.bboxHeight.toFloat() / WORK_H
        val blobWidthFraction = largest.bboxWidth.toFloat() / WORK_W
        lastMaxRunFraction = blobHeightFraction

        // ============================================================
        // Step 3: Global size check (two-stage architecture — Stage 1)
        // ============================================================
        if (blobHeightFraction < minGlobalHeightFraction || blobWidthFraction < minGlobalWidthFraction) {
            rotateFrameBuffers()
            prevTimestamp = ptsNanos
            registerInvalidTrackingFrame()
            state = State.ATHLETE_TOO_FAR
            lastRejection = "tooFar"

            if (frameNumber % debugLogInterval == 0L) {
                Log.d(TAG, "[REPLICA] REJECT: tooFar (height=${(blobHeightFraction * 100).toInt()}% need>=${(minGlobalHeightFraction * 100).toInt()}%, width=${(blobWidthFraction * 100).toInt()}% need>=${(minGlobalWidthFraction * 100).toInt()}%)")
            }
            return makeResult(triggered = false, state = State.ATHLETE_TOO_FAR, cameraStable = frameCameraStable)
        }

        state = State.READY

        // ============================================================
        // Step 4: Leading edge + direction (all rows, no torso filter)
        // ============================================================
        val gateX = WORK_W.toFloat() * gatePosition

        val leadingEdgeX = findLeadingEdge(largest, gateX)

        if (leadingEdgeX == null) {
            if (frameNumber % debugLogInterval == 0L) {
                Log.d(TAG, "[REPLICA] REJECT: noLeadingEdge (blob=${(blobHeightFraction * 100).toInt()}%h/${(blobWidthFraction * 100).toInt()}%w)")
            }
            rotateFrameBuffers()
            prevTimestamp = ptsNanos
            registerInvalidTrackingFrame()
            return makeResult(triggered = false, state = State.READY, cameraStable = frameCameraStable)
        }

        val chestX = leadingEdgeX
        lastBlobCenterX = chestX
        registerValidTrackingFrame()

        // Compute velocity for diagnostics (NOT used as a gate)
        var velocityPxPerSec = 0f
        val pX = prevChestX
        if (pX != null && prevTimestamp > 0 && ptsNanos > prevTimestamp) {
            val dt = (ptsNanos - prevTimestamp) / 1_000_000_000.0f
            if (dt > 0) {
                velocityPxPerSec = abs(chestX - pX) / dt
                lastVelocityPxPerSec = velocityPxPerSec
            }
        }

        // Debug log
        if (frameNumber % debugLogInterval == 0L) {
            val side = if (chestX < gateX) "L" else "R"
            Log.d(TAG, "[REPLICA] blob=${(blobHeightFraction * 100).toInt()}%h/${(blobWidthFraction * 100).toInt()}%w chestX=${chestX.toInt()} ($side) vel=${String.format("%.1f", velocityPxPerSec)}px/s gate=${gateX.toInt()} thr=$adaptiveThreshold")
        }

        // Timestamp continuity check
        if (prevTimestamp > 0 && ptsNanos <= prevTimestamp) {
            rotateFrameBuffers()
            prevTimestamp = ptsNanos
            registerInvalidTrackingFrame()
            state = State.READY
            lastRejection = "timeDiscontinuity"
            return makeResult(triggered = false, state = State.READY, cameraStable = frameCameraStable)
        }

        // ============================================================
        // Step 5: Crossing detection (sign change on gate)
        // ============================================================
        if (pX == null) {
            rotateFrameBuffers()
            prevTimestamp = ptsNanos
            prevChestX = chestX
            lastRejection = null
            return makeResult(triggered = false, state = State.READY, cameraStable = frameCameraStable)
        }

        val s0 = pX - gateX
        val s1 = chestX - gateX
        val crossedLeftToRight = s0 < 0 && s1 >= 0
        val crossedRightToLeft = s0 > 0 && s1 <= 0

        if (!crossedLeftToRight && !crossedRightToLeft) {
            // No crossing
            rotateFrameBuffers()
            prevTimestamp = ptsNanos
            prevChestX = chestX
            lastRejection = null
            return makeResult(triggered = false, state = State.READY, cameraStable = frameCameraStable)
        }

        val candidateDirection = if (crossedLeftToRight) "L->R" else "R->L"
        Log.d(TAG, "[REPLICA] CANDIDATE: $candidateDirection prev=${pX.toInt()} gate=${gateX.toInt()} curr=${chestX.toInt()}")

        // ============================================================
        // Step 6: Gate body check (>=minGateContiguousFraction contiguous run at gate columns)
        // ============================================================
        val gateCol = gateX.toInt()
        val colMin = maxOf(0, gateCol - GATE_COLUMN_HALF_BAND)
        val colMax = minOf(WORK_W - 1, gateCol + GATE_COLUMN_HALF_BAND)
        val requiredRunPx = (minGateContiguousFraction * WORK_H).toInt()

        var bestContiguousRun = 0
        for (col in colMin..colMax) {
            var currentRun = 0
            var maxRun = 0
            for (y in 0 until WORK_H) {
                if ((motionMask[y * WORK_W + col].toInt() and 0xFF) == 255) {
                    currentRun++
                    if (currentRun > maxRun) maxRun = currentRun
                } else {
                    currentRun = 0
                }
            }
            if (maxRun > bestContiguousRun) bestContiguousRun = maxRun
        }

        val contiguousFraction = bestContiguousRun.toFloat() / WORK_H
        val contiguousPct = (contiguousFraction * 100).toInt()
        Log.d(TAG, "[REPLICA] GATE-CHECK: contiguous=${bestContiguousRun}px/${contiguousPct}% threshold=${requiredRunPx}px/${(minGateContiguousFraction * 100).toInt()}% passed=${bestContiguousRun >= requiredRunPx}")

        if (bestContiguousRun < requiredRunPx) {
            rotateFrameBuffers()
            prevTimestamp = ptsNanos
            // CRITICAL: Do NOT update prevChestX on gate check failure.
            // This allows the crossing to re-fire when the torso arrives.
            lastRejection = "gateCheckFailed"
            return makeResult(triggered = false, state = State.READY, cameraStable = frameCameraStable)
        }

        // ============================================================
        // CROSSING DETECTED
        // ============================================================

        // ============================================================
        // Step 7: Sub-frame interpolation + exposure compensation
        // ============================================================
        val direction = if (crossedLeftToRight) "L->R" else "R->L"

        val d0 = abs(s0)
        val d1 = abs(s1)
        val denom = d0 + d1
        val alpha = if (denom > 0.0001f) (d0 / denom) else 0.5f
        val interpolationAlpha = alpha.coerceIn(0f, 1f)

        val frameDuration = if (ptsNanos > prevTimestamp) ptsNanos - prevTimestamp else 0L
        val interpolatedOffset = (interpolationAlpha * frameDuration).toLong()
        val rawCrossingTime = prevTimestamp + interpolatedOffset
        val thumbnailFramePts = if (interpolationAlpha < 0.5f) prevTimestamp else ptsNanos
        val frameChestX = if (interpolationAlpha < 0.5f) pX else chestX

        // Only apply exposure compensation when exposure > 4ms
        val effectiveFactor = if (exposureDuration > 0.004) exposureCompensationFactor else 0.0f
        val clampedCompensationFactor = effectiveFactor.coerceIn(0.0f, 1.25f)
        val exposureCompensationNanos = maxOf(0L, (clampedCompensationFactor.toDouble() * exposureDuration * 1_000_000_000.0).toLong())
        val adjustedCrossingTime = if (rawCrossingTime <= Long.MAX_VALUE - exposureCompensationNanos) {
            rawCrossingTime + exposureCompensationNanos
        } else {
            Long.MAX_VALUE
        }

        val exposureCompensationMs = exposureCompensationNanos / 1_000_000.0

        Log.i(TAG, "[REPLICA] CROSSING ($direction) at frame $frameNumber, blob=${(blobHeightFraction * 100).toInt()}%h, contiguous=${contiguousPct}%, chestX=${chestX.toInt()}, vel=${String.format("%.1f", velocityPxPerSec)}px/s, alpha=${String.format("%.2f", interpolationAlpha)}, compMs=${String.format("%.2f", exposureCompensationMs)}")

        // Update state
        state = State.COOLDOWN
        cooldownStartTime = currentTime
        lastRejection = null

        // Notify
        val chestPositionNormalized = frameChestX / WORK_W.toFloat()
        val monotonicNanos = SystemClock.elapsedRealtimeNanos()
        onCrossingDetected?.invoke(adjustedCrossingTime, thumbnailFramePts, monotonicNanos, frameNumber, chestPositionNormalized)

        // Post-roll complete after cooldown
        val postrollCallback = onPostrollComplete
        if (postrollCallback != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { postrollCallback.invoke() },
                (COOLDOWN_DURATION_S * 1000).toLong()
            )
        }

        rotateFrameBuffers()
        prevTimestamp = ptsNanos
        prevChestX = chestX

        return DetectionResult(
            triggered = true,
            maxRunHeight = bestContiguousRun,
            maxRunFraction = contiguousFraction,
            motionAmount = lastMotionAmount,
            cameraStable = frameCameraStable,
            state = State.TRIGGERED,
            chestX = chestPositionNormalized.coerceIn(0f, 1f),
            blobHeightFraction = blobHeightFraction,
            velocityPxPerSec = velocityPxPerSec
        )
    }

    // MARK: - Per-Frame Adaptive Threshold

    /**
     * Compute adaptive threshold from current frame diff histogram.
     * Algorithm: median + sigma * MAD * 1.4826 (floor=8, no upper clamp).
     */
    private fun computeAdaptiveThreshold(workSize: Int): Int {
        // 1. Build histogram of sampled diffs (every 8th pixel, N-1 only for threshold)
        diffHist.fill(0)
        var sampleCount = 0

        var i = 0
        while (i < workSize) {
            val diff = abs((currLumaSmall[i].toInt() and 0xFF) - (prevLumaSmall[i].toInt() and 0xFF))
            diffHist[diff]++
            sampleCount++
            i += 8
        }

        if (sampleCount == 0) return MIN_ADAPTIVE_THRESHOLD

        // 2. Find median from histogram
        val halfCount = sampleCount / 2
        var cumulative = 0
        var median = 0
        for (bin in 0 until 256) {
            cumulative += diffHist[bin]
            if (cumulative > halfCount) {
                median = bin
                break
            }
        }

        // 3. Compute MAD (Median Absolute Deviation)
        madHist.fill(0)
        for (bin in 0 until 256) {
            val count = diffHist[bin]
            if (count > 0) {
                val dev = abs(bin - median)
                if (dev < 256) {
                    madHist[dev] += count
                }
            }
        }

        var madCumulative = 0
        var mad = 0
        for (bin in 0 until 256) {
            madCumulative += madHist[bin]
            if (madCumulative > halfCount) {
                mad = bin
                break
            }
        }

        // 4. threshold = max(8, median + sigmaMultiplier * MAD * 1.4826)
        val sigma = mad * 1.4826
        val rawThreshold = median + sigmaMultiplier * sigma
        return maxOf(MIN_ADAPTIVE_THRESHOLD, minOf(255, rawThreshold.toInt()))
    }

    // MARK: - Leading Edge

    /**
     * Find leading edge X using contiguous vertical run filter.
     * Returns the most extreme X where a contiguous vertical run meets minGateContiguousFraction,
     * in the direction of motion. Returns null if no qualifying column found.
     */
    private fun findLeadingEdge(blob: CCLBlob, gateX: Float): Float? {
        val minX = maxOf(0, blob.bboxMinX)
        val maxX = minOf(WORK_W - 1, blob.bboxMinX + blob.bboxWidth - 1)
        val minY = maxOf(0, blob.bboxMinY)
        val maxY = minOf(WORK_H - 1, blob.bboxMinY + blob.bboxHeight - 1)
        if (maxX <= minX || maxY <= minY) return null

        // Determine direction (centroid-based hysteresis)
        val currentCentroid = blob.centroidX
        var isMovingRight: Boolean

        val lastCenter = prevCentroidX
        if (lastCenter != null) {
            val delta = currentCentroid - lastCenter
            if (abs(delta) >= MIN_DIRECTION_CHANGE_PX) {
                isMovingRight = delta > 0
                lastConfirmedDirection = isMovingRight
            } else {
                isMovingRight = lastConfirmedDirection ?: (currentCentroid < gateX)
            }
        } else {
            isMovingRight = currentCentroid < gateX
            lastConfirmedDirection = isMovingRight
        }
        prevCentroidX = currentCentroid

        val requiredRunPx = (minGateContiguousFraction * WORK_H).toInt()

        // Find leading edge: only columns with contiguous vertical run >= threshold
        var leadingEdge: Int? = null

        for (col in minX..maxX) {
            // Compute longest contiguous vertical run in this column
            var currentRun = 0
            var maxRun = 0
            for (y in minY..maxY) {
                val idx = y * WORK_W + col
                if ((motionMask[idx].toInt() and 0xFF) == 255) {
                    currentRun++
                    if (currentRun > maxRun) maxRun = currentRun
                } else {
                    currentRun = 0
                }
            }

            // Only accept columns with substantial body mass
            if (maxRun < requiredRunPx) continue

            val current = leadingEdge
            if (current != null) {
                if (isMovingRight) {
                    if (col > current) leadingEdge = col
                } else {
                    if (col < current) leadingEdge = col
                }
            } else {
                leadingEdge = col
            }
        }

        return leadingEdge?.toFloat()
    }

    // MARK: - Luma Extraction (Downsampled)

    private fun downsampleLuma(
        yPlane: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        rowStride: Int,
        buffer: ByteArray
    ) {
        if (frameWidth != srcWidth || frameHeight != srcHeight) {
            frameWidth = srcWidth
            frameHeight = srcHeight
            scaleX = srcWidth.toFloat() / WORK_W
            scaleY = srcHeight.toFloat() / WORK_H
        }

        for (dy in 0 until WORK_H) {
            val sy = (dy * scaleY).toInt().coerceIn(0, srcHeight - 1)
            val dstRowOffset = dy * WORK_W
            val srcRowOffset = sy * rowStride

            for (dx in 0 until WORK_W) {
                val sx = (dx * scaleX).toInt().coerceIn(0, srcWidth - 1)
                buffer[dstRowOffset + dx] = yPlane[srcRowOffset + sx]
            }
        }
    }

    // MARK: - Frame Rotation

    /** 3-buffer cyclic rotation: prevPrev <- prev <- curr <- (reuse prevPrev) */
    private fun rotateFrameBuffers() {
        val temp = prevPrevLumaSmall
        prevPrevLumaSmall = prevLumaSmall
        prevLumaSmall = currLumaSmall
        currLumaSmall = temp
    }

    // MARK: - State Management

    fun reset() {
        state = if (isPhoneStable) State.READY else State.UNSTABLE
        processedFrameCount = 0
        cooldownStartTime = 0.0
        prevTimestamp = 0
        prevChestX = null
        prevCentroidX = null
        lastConfirmedDirection = null
        invalidTrackingFrames = 0
        frameSkipCounter = 0
        didLogShakingRejection = false
        lastMaxRunFraction = 0f
        lastMotionAmount = 0f
        lastVelocityPxPerSec = 0f
        lastBlobCenterX = null
        lastRejection = null

        // Clear buffers
        val workSize = WORK_W * WORK_H
        currLumaSmall = ByteArray(workSize)
        prevLumaSmall = ByteArray(workSize)
        prevPrevLumaSmall = ByteArray(workSize)
        motionMask = ByteArray(workSize)

        Log.i(TAG, "[REPLICA] Reset complete")
    }

    val isReady: Boolean
        get() {
            if (!requiresIMUStability) {
                return state == State.READY || state == State.ATHLETE_TOO_FAR
            }
            return (state == State.READY || state == State.ATHLETE_TOO_FAR) && isPhoneStable
        }

    val stateDescription: String
        get() {
            return when (state) {
                State.UNSTABLE -> if (requiresIMUStability) "Hold Steady" else "Ready"
                State.ATHLETE_TOO_FAR -> "Too Far"
                State.READY -> "Ready"
                State.TRIGGERED -> "Triggered"
                State.COOLDOWN -> "Cooldown"
            }
        }

    // MARK: - Result Helper

    private fun makeResult(triggered: Boolean, state: State, cameraStable: Boolean): DetectionResult {
        return DetectionResult(
            triggered = triggered,
            maxRunHeight = (lastMaxRunFraction * WORK_H).toInt(),
            maxRunFraction = lastMaxRunFraction,
            motionAmount = lastMotionAmount,
            cameraStable = cameraStable,
            state = state,
            chestX = lastBlobCenterX?.let { (it / WORK_W.toFloat()).coerceIn(0f, 1f) },
            blobHeightFraction = lastMaxRunFraction,
            velocityPxPerSec = lastVelocityPxPerSec
        )
    }
}
