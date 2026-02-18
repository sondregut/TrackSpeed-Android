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
 * Photo Finish Mode detector - exact replication of iOS Photo Finish app behavior.
 *
 * Architecture (per-frame processing order):
 * 1. Timestamp - frame PTS
 * 2. IMU gate - if unstable -> return immediately
 * 3. Downsample luma -> motion mask (diff vs prev)
 * 4. Largest blob via CCL
 * 5. Stage 1: Size check (>=33% height)
 * 6. Compute chestX (gate-band centroid)
 * 7. Velocity filter (>=60 px/s at work-res)
 * 8. Crossing check + interpolation + rolling shutter compensation
 * 9. Update trackers + rearm hysteresis
 *
 * Ported from iOS PhotoFinishDetector.swift.
 */
class PhotoFinishDetector(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "PhotoFinishDetector"

        // Work resolution for downsampled processing (portrait)
        const val WORK_W = 160
        const val WORK_H = 284

        // IMU stability threshold (rad/s) - gyroscope based
        // iOS uses 0.15 - allows slow movement, rejects shakes (~0.3-0.5 rad/s)
        private const val GYRO_THRESHOLD = 0.3
        // Duration phone must be stable before detection enabled (~1.0s matches iOS)
        private const val STABLE_DURATION_TO_ARM_S = 1.0

        // Minimum blob height for crossing confirmation
        private const val MIN_BLOB_HEIGHT_FOR_CROSSING = 0.33f

        // Default motion threshold - pixel difference to count as "changed" (0-255)
        private const val DEFAULT_DIFF_THRESHOLD: Int = 14
        private const val MIN_DIFF_THRESHOLD: Int = 8
        private const val MAX_DIFF_THRESHOLD: Int = 40

        // Minimum velocity in work pixels/sec to allow trigger
        private const val MIN_VELOCITY_PX_PER_SEC = 60.0f

        // Minimum centroid movement to update direction
        private const val MIN_DIRECTION_CHANGE_PX = 2.0f

        // Cooldown duration after trigger
        private const val COOLDOWN_DURATION_S = 0.3

        // Rearm hysteresis
        private const val HYSTERESIS_DISTANCE_FRACTION = 0.25f

        // Column density for body detection
        private val MIN_COLUMN_DENSITY_FOR_BODY: Int get() = (WORK_H * 0.15f).toInt()
        private const val MIN_REGION_WIDTH_FOR_BODY = 8

        // Warmup duration before detection
        private const val WARMUP_DURATION_S = 0.30
        // Rearm duration
        private const val REARM_DURATION_S = 0.2
        // Minimum time between crossings
        private const val MIN_TIME_BETWEEN_CROSSINGS_S = 0.3
        // Exit zone fraction
        private const val EXIT_ZONE_FRACTION = 0.35f

        // Debug log interval
        private const val DEBUG_LOG_DURATION_S = 10.0

        // Grace period after arming
        private const val ARMING_GRACE_PERIOD_S = 0.20

        // Trajectory buffer size
        private const val TRAJECTORY_BUFFER_SIZE = 6
    }

    // MARK: - Types

    enum class State(val label: String) {
        UNSTABLE("Hold Steady"),
        NO_ATHLETE("Ready"),
        ATHLETE_TOO_FAR("Too Far"),
        READY("Ready"),
        TRIGGERED("Triggered"),
        COOLDOWN("Cooldown")
    }

    enum class RejectionReason {
        NONE, CAMERA_SHAKING, TOO_FAR, TOO_SLOW, NO_BLOB, IN_COOLDOWN
    }

    data class DetectionResult(
        val triggered: Boolean,
        val blobHeightFraction: Float,
        val blobCenterX: Float?,
        val velocityPxPerSec: Float,
        val motionAmount: Float,
        val cameraStable: Boolean,
        val rejection: RejectionReason,
        val state: State
    )

    // MARK: - Configuration

    var gatePosition: Float = 0.5f
    var minBlobHeightFraction: Float = MIN_BLOB_HEIGHT_FOR_CROSSING
    var isFrontCamera: Boolean = false
    var currentFPS: Double = 30.0

    /** Current gate role (start, finish, lap) */
    var gateRole: String = "finish"

    /** Current start type */
    var startType: String = "flying"

    /**
     * Whether this detector requires IMU stability to arm.
     * Start gate with non-flying starts doesn't need IMU stability
     * because it triggers via UI touch or countdown, not crossing detection.
     * Matches iOS requiresIMUStability logic.
     */
    val requiresIMUStability: Boolean
        get() {
            // Start gate with non-flying start doesn't need IMU stability
            if (gateRole == "start" && startType != "flying") {
                return false
            }
            return true
        }

    // FPS-dependent computed properties
    private val warmupFrames: Int get() = maxOf(1, (WARMUP_DURATION_S * currentFPS).toInt())
    private val rearmFramesRequired: Int get() = maxOf(1, (REARM_DURATION_S * currentFPS).toInt())
    private val debugLogInterval: Long get() = maxOf(1, (DEBUG_LOG_DURATION_S * currentFPS).toLong())

    // MARK: - IMU (Gyroscope)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var currentGyroMagnitude: Double = 0.0
    private var stableStartTime: Double? = null
    var isPhoneStable: Boolean = false
        private set

    // MARK: - State

    var state: State = State.UNSTABLE
        private set
    var isPaused: Boolean = false

    // Frame dimensions (full resolution)
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0
    private var scaleX: Float = 1.0f
    private var scaleY: Float = 1.0f

    // MARK: - Preallocated Buffers

    private var currLumaSmall = ByteArray(WORK_W * WORK_H)
    private var prevLumaSmall = ByteArray(WORK_W * WORK_H)
    private var motionMask = ByteArray(WORK_W * WORK_H)

    private val cclEngine = ZeroAllocCCL(WORK_W, WORK_H)

    // MARK: - Trajectory Tracking

    private data class TrajectoryPoint(
        val chestX: Float,
        val chestY: Float,
        val timestamp: Long, // nanoseconds
        val blobWidth: Float
    )

    private val trajectoryBuffer = ArrayList<TrajectoryPoint>(TRAJECTORY_BUFFER_SIZE)
    private var trajectoryIndex: Int = 0

    // MARK: - Adaptive Noise Calibration

    private val noiseCalibrationSamples = ArrayList<Int>()
    private var adaptiveDiffThreshold: Int = DEFAULT_DIFF_THRESHOLD
    private var isNoiseCalibrated: Boolean = false

    // MARK: - Tracking State

    private var prevChestX: Float? = null
    private var prevCentroidX: Float? = null
    private var lastConfirmedDirection: Boolean? = null // true = moving right
    private var prevTimestamp: Long = 0
    private var isArmed: Boolean = true
    private var armedAtTime: Double = 0.0
    private var framesAtHysteresis: Int = 0
    private var processedFrameCount: Int = 0
    private var frameSkipCounter: Int = 0
    private var consecutiveNoMotionFrames: Int = 0
    private var cooldownStartTime: Double = 0.0
    private var lastCrossingTime: Double = 0.0
    private var blobExitedSinceLastCrossing: Boolean = true
    private var framesWithNoValidBlob: Int = 0

    // MARK: - Debug/UI State

    var lastBlobHeightFraction: Float = 0f; private set
    var lastBlobCenterX: Float? = null; private set
    var lastVelocityPxPerSec: Float = 0f; private set
    var lastMotionAmount: Float = 0f; private set
    var lastRejection: RejectionReason = RejectionReason.NONE; private set

    // MARK: - Callbacks

    /**
     * Called when crossing detected.
     * Parameters: adjustedPtsNanos, detectionFramePtsNanos, monotonicNanos, frameNumber, chestPositionNormalized
     * - adjustedPtsNanos: Interpolated crossing time for accurate timing
     * - detectionFramePtsNanos: Actual frame PTS where detection fired (for thumbnail selection)
     * - chestPositionNormalized: Normalized X position (0-1) of chest in the selected thumbnail frame
     */
    var onCrossingDetected: ((Long, Long, Long, Long, Float) -> Unit)? = null

    fun configure(fps: Double) {
        currentFPS = fps
        Log.i(TAG, "Configured for ${fps.toInt()} fps: warmup=$warmupFrames frames, rearm=$rearmFramesRequired frames")
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
            // Phone rotating/shaking - immediately unstable
            isPhoneStable = false
            stableStartTime = null
            // Only set state to unstable if IMU stability is required
            // Start gate with non-flying start doesn't need stability
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

    // MARK: - Main Detection (Per-Frame Processing)

    /**
     * Process a single YUV frame.
     * @param yPlane Y (luminance) plane data
     * @param width Full frame width
     * @param height Full frame height
     * @param rowStride Row stride of Y plane
     * @param frameNumber Sequential frame number
     * @param ptsNanos Frame presentation timestamp in nanoseconds
     */
    fun processFrame(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        frameNumber: Long,
        ptsNanos: Long
    ): DetectionResult {

        // Step 1: Check if paused
        if (isPaused) {
            return makeResult(triggered = false, rejection = RejectionReason.NONE)
        }

        // Thermal optimization: at 120fps, skip every 2nd frame
        if (currentFPS >= 100) {
            frameSkipCounter++
            if (frameSkipCounter % 2 == 0) {
                return makeResult(triggered = false, rejection = lastRejection)
            }
        }

        val currentTime = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0

        // Step 2: Handle cooldown
        if (state == State.COOLDOWN) {
            if (currentTime - cooldownStartTime >= COOLDOWN_DURATION_S) {
                state = State.READY
            } else {
                lastRejection = RejectionReason.IN_COOLDOWN
                return makeResult(triggered = false, rejection = RejectionReason.IN_COOLDOWN)
            }
        }

        // Step 3: IMU stability gate (first-class hard gate)
        // Skip for start gate with non-flying start (triggers via UI, not crossing)
        if (requiresIMUStability && !isPhoneStable) {
            lastRejection = RejectionReason.CAMERA_SHAKING
            return makeResult(triggered = false, rejection = RejectionReason.CAMERA_SHAKING)
        }

        // Step 4: Early-exit on zero-motion frames
        if (consecutiveNoMotionFrames >= 2 && processedFrameCount > warmupFrames) {
            downsampleLuma(yPlane, width, height, rowStride, currLumaSmall)
            var quickMotion = 0
            val workSize = WORK_W * WORK_H
            var i = 0
            while (i < workSize) {
                if (abs((currLumaSmall[i].toInt() and 0xFF) - (prevLumaSmall[i].toInt() and 0xFF)) > adaptiveDiffThreshold) {
                    quickMotion++
                    if (quickMotion > 5) break
                }
                i += 20
            }
            if (quickMotion <= 5) {
                val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
                prevTimestamp = ptsNanos
                consecutiveNoMotionFrames++
                state = State.NO_ATHLETE
                lastRejection = RejectionReason.NO_BLOB
                return makeResult(triggered = false, rejection = RejectionReason.NO_BLOB)
            }
            consecutiveNoMotionFrames = 0
        }

        // Step 5: Downsample luma to work resolution
        downsampleLuma(yPlane, width, height, rowStride, currLumaSmall)

        // Warmup period - also collects noise samples
        processedFrameCount++
        if (processedFrameCount <= warmupFrames) {
            if (processedFrameCount > 1 && !isNoiseCalibrated) {
                collectNoiseSamples()
            }
            if (processedFrameCount == warmupFrames) {
                finalizeNoiseCalibration()
                isArmed = true
                armedAtTime = currentTime
            }
            val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
            prevTimestamp = ptsNanos
            prevChestX = null
            prevCentroidX = null
            lastConfirmedDirection = null
            return makeResult(triggered = false, rejection = RejectionReason.NONE)
        }

        // Step 5b: Build motion mask (frame differencing)
        var motionCount = 0
        val workSize = WORK_W * WORK_H

        for (idx in 0 until workSize) {
            val diff = abs((currLumaSmall[idx].toInt() and 0xFF) - (prevLumaSmall[idx].toInt() and 0xFF))
            if (diff > adaptiveDiffThreshold) {
                motionMask[idx] = 255.toByte()
                motionCount++
            } else {
                motionMask[idx] = 0
            }
        }

        val motionRatio = motionCount.toFloat() / workSize
        lastMotionAmount = motionRatio

        // Need minimum motion pixels
        if (motionCount <= 50) {
            consecutiveNoMotionFrames++
            checkNoValidBlobRearm(currentTime)
            val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
            prevTimestamp = ptsNanos
            prevChestX = null
            prevCentroidX = null
            lastConfirmedDirection = null
            trajectoryBuffer.clear()
            trajectoryIndex = 0
            state = State.NO_ATHLETE
            lastRejection = RejectionReason.NO_BLOB
            return makeResult(triggered = false, rejection = RejectionReason.NO_BLOB)
        }

        consecutiveNoMotionFrames = 0

        // Step 6: Largest blob via CCL
        val blobs = cclEngine.process(motionMask, WORK_W, WORK_H)

        if (blobs.isEmpty()) {
            checkNoValidBlobRearm(currentTime)
            val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
            prevTimestamp = ptsNanos
            prevChestX = null
            prevCentroidX = null
            lastConfirmedDirection = null
            trajectoryBuffer.clear()
            trajectoryIndex = 0
            state = State.NO_ATHLETE
            lastRejection = RejectionReason.NO_BLOB
            return makeResult(triggered = false, rejection = RejectionReason.NO_BLOB)
        }

        // Find largest blob by area
        val largest = blobs.maxByOrNull { it.areaPixels }!!
        val blobHeightFraction = largest.bboxHeight.toFloat() / WORK_H
        lastBlobHeightFraction = blobHeightFraction

        // Step 7: Stage 1 - Global size validation
        if (blobHeightFraction < minBlobHeightFraction) {
            checkNoValidBlobRearm(currentTime)
            val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
            prevTimestamp = ptsNanos
            prevChestX = null
            prevCentroidX = null
            lastConfirmedDirection = null
            trajectoryBuffer.clear()
            trajectoryIndex = 0
            state = State.ATHLETE_TOO_FAR
            lastRejection = RejectionReason.TOO_FAR
            return makeResult(triggered = false, rejection = RejectionReason.TOO_FAR)
        }

        state = State.READY
        framesWithNoValidBlob = 0

        // Step 8: Compute chestX (gate-band centroid or blob centroid)
        val gateX = WORK_W.toFloat() * gatePosition
        val chestX = computeChestX(largest, gateX)
        lastBlobCenterX = chestX

        val chestY = largest.centroidY

        // Store trajectory point
        val trajectoryPoint = TrajectoryPoint(
            chestX = chestX,
            chestY = chestY,
            timestamp = ptsNanos,
            blobWidth = largest.bboxWidth.toFloat()
        )
        if (trajectoryBuffer.size < TRAJECTORY_BUFFER_SIZE) {
            trajectoryBuffer.add(trajectoryPoint)
        } else {
            trajectoryBuffer[trajectoryIndex] = trajectoryPoint
        }
        trajectoryIndex = (trajectoryIndex + 1) % TRAJECTORY_BUFFER_SIZE

        // Step 9: Velocity check
        var velocityPxPerSec = 0f

        val pX = prevChestX
        if (pX != null && prevTimestamp > 0) {
            val dt = (ptsNanos - prevTimestamp) / 1_000_000_000.0f
            if (dt > 0) {
                velocityPxPerSec = abs(chestX - pX) / dt
                lastVelocityPxPerSec = velocityPxPerSec
            }
        }

        // Step 10: Rearm hysteresis check
        if (!isArmed) {
            val distanceFromGate = abs(chestX - gateX)

            if (!blobExitedSinceLastCrossing) {
                val exitDistance = WORK_W.toFloat() * EXIT_ZONE_FRACTION
                if (distanceFromGate > exitDistance) {
                    blobExitedSinceLastCrossing = true
                }
            } else {
                val hysteresisDistance = WORK_W.toFloat() * HYSTERESIS_DISTANCE_FRACTION
                if (distanceFromGate > hysteresisDistance) {
                    framesAtHysteresis++
                    if (framesAtHysteresis >= rearmFramesRequired) {
                        isArmed = true
                        armedAtTime = currentTime
                        framesAtHysteresis = 0
                    }
                } else {
                    framesAtHysteresis = 0
                }
            }
        }

        // Step 11: Gate crossing detection
        if (pX == null) {
            val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
            prevTimestamp = ptsNanos
            prevChestX = chestX
            lastRejection = RejectionReason.NONE
            return makeResult(triggered = false, rejection = RejectionReason.NONE)
        }

        // Check velocity threshold
        if (velocityPxPerSec < MIN_VELOCITY_PX_PER_SEC) {
            val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
            prevTimestamp = ptsNanos
            prevChestX = chestX
            lastRejection = RejectionReason.TOO_SLOW
            return makeResult(triggered = false, rejection = RejectionReason.TOO_SLOW)
        }

        // Check if armed
        if (!isArmed) {
            val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
            prevTimestamp = ptsNanos
            prevChestX = chestX
            lastRejection = RejectionReason.NONE
            return makeResult(triggered = false, rejection = RejectionReason.NONE)
        }

        // Check grace period
        val timeSinceArmed = currentTime - armedAtTime
        if (timeSinceArmed < ARMING_GRACE_PERIOD_S) {
            val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
            prevTimestamp = ptsNanos
            prevChestX = chestX
            lastRejection = RejectionReason.NONE
            return makeResult(triggered = false, rejection = RejectionReason.NONE)
        }

        // Check crossing (both directions)
        val s0 = pX - gateX
        val s1 = chestX - gateX
        val crossedLeftToRight = s0 < 0 && s1 >= 0
        val crossedRightToLeft = s0 > 0 && s1 <= 0

        if (crossedLeftToRight || crossedRightToLeft) {
            // CROSSING DETECTED - Validate with vertical slice at chestX
            val checkX = maxOf(0, minOf(chestX.toInt(), WORK_W - 1))
            var longestRun = 0
            var currentRun = 0
            for (y in 0 until WORK_H) {
                val idx = y * WORK_W + checkX
                if ((motionMask[idx].toInt() and 0xFF) == 255) {
                    currentRun++
                    if (currentRun > longestRun) longestRun = currentRun
                } else {
                    currentRun = 0
                }
            }

            // Reject if no solid body mass
            if (longestRun < MIN_COLUMN_DENSITY_FOR_BODY) {
                val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
                prevTimestamp = ptsNanos
                prevChestX = chestX
                lastRejection = RejectionReason.TOO_FAR
                return makeResult(triggered = false, rejection = RejectionReason.TOO_FAR)
            }

            // Check blob size for crossing (stricter)
            if (blobHeightFraction < MIN_BLOB_HEIGHT_FOR_CROSSING) {
                val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
                prevTimestamp = ptsNanos
                prevChestX = chestX
                lastRejection = RejectionReason.TOO_FAR
                return makeResult(triggered = false, rejection = RejectionReason.TOO_FAR)
            }

            // Check minimum time since last crossing
            if (currentTime - lastCrossingTime < MIN_TIME_BETWEEN_CROSSINGS_S) {
                val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
                prevTimestamp = ptsNanos
                prevChestX = chestX
                lastRejection = RejectionReason.IN_COOLDOWN
                return makeResult(triggered = false, rejection = RejectionReason.IN_COOLDOWN)
            }

            Log.i(TAG, "TRIGGERED at frame $frameNumber, blob=${(blobHeightFraction * 100).toInt()}%, " +
                    "chestX=${chestX.toInt()}, gateX=${gateX.toInt()}, " +
                    "vel=${String.format("%.1f", velocityPxPerSec)}px/s")

            // BODY MASS CONFIRMED - Use trajectory regression for accuracy
            val trajectoryResult = calculateCrossingFromTrajectory(gateX)

            val rawCrossingTime: Long
            val thumbnailFramePts: Long
            val finalVelocity: Float
            val frameChestX: Float

            if (trajectoryResult != null) {
                rawCrossingTime = trajectoryResult.crossingTime
                thumbnailFramePts = trajectoryResult.bestFramePts
                finalVelocity = trajectoryResult.velocity

                // Find chest position for the selected frame
                frameChestX = trajectoryBuffer.firstOrNull { it.timestamp == thumbnailFramePts }?.chestX ?: chestX
            } else {
                // Fallback to 2-frame linear interpolation
                val d0 = abs(s0)
                val d1 = abs(s1)
                val alpha = d0 / (d0 + d1)

                val frameDuration = ptsNanos - prevTimestamp
                val interpolatedOffset = (alpha * frameDuration).toLong()
                rawCrossingTime = prevTimestamp + interpolatedOffset
                thumbnailFramePts = if (alpha < 0.5f) prevTimestamp else ptsNanos
                frameChestX = if (alpha < 0.5f) pX else chestX
                finalVelocity = velocityPxPerSec
            }

            // Rolling shutter compensation
            val chestYNormalized = chestY / WORK_H.toFloat()
            val compensationNanos = RollingShutterCalculator.calculateCompensationNanos(
                isFrontCamera = isFrontCamera,
                fps = currentFPS,
                chestYNormalized = chestYNormalized
            )
            val adjustedCrossingTime = rawCrossingTime + compensationNanos

            // Update state
            state = State.COOLDOWN
            cooldownStartTime = currentTime
            lastCrossingTime = currentTime
            isArmed = false
            framesAtHysteresis = 0
            blobExitedSinceLastCrossing = false
            lastRejection = RejectionReason.NONE

            // Calculate normalized chest position for the selected frame
            // This is used to crop/shift the thumbnail so chest appears exactly at center
            val chestPositionNormalized = frameChestX / WORK_W.toFloat()

            // Emit callback
            val monotonicNanos = SystemClock.elapsedRealtimeNanos()
            onCrossingDetected?.invoke(adjustedCrossingTime, thumbnailFramePts, monotonicNanos, frameNumber, chestPositionNormalized)

            // Update for next frame
            val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
            prevTimestamp = ptsNanos
            prevChestX = chestX

            return DetectionResult(
                triggered = true,
                blobHeightFraction = blobHeightFraction,
                blobCenterX = chestX,
                velocityPxPerSec = finalVelocity,
                motionAmount = lastMotionAmount,
                cameraStable = true,
                rejection = RejectionReason.NONE,
                state = State.TRIGGERED
            )
        }

        // No crossing - update for next frame
        val tmp = prevLumaSmall; prevLumaSmall = currLumaSmall; currLumaSmall = tmp
        prevTimestamp = ptsNanos
        prevChestX = chestX
        lastRejection = RejectionReason.NONE

        return makeResult(triggered = false, rejection = RejectionReason.NONE)
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

    // MARK: - Chest Position (Column Density Scan)

    private fun computeChestX(blob: CCLBlob, gateX: Float): Float {
        val currentCentroid = blob.centroidX
        var isMovingRight = true

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

        return findBodyEdgeX(blob, isMovingRight)
    }

    private fun findBodyEdgeX(blob: CCLBlob, isMovingRight: Boolean): Float {
        val startX: Int
        val endX: Int
        val step: Int

        if (isMovingRight) {
            startX = blob.bboxMinX + blob.bboxWidth - 1
            endX = blob.bboxMinX
            step = -1
        } else {
            startX = blob.bboxMinX
            endX = blob.bboxMinX + blob.bboxWidth - 1
            step = 1
        }

        val minY = blob.bboxMinY
        val maxY = blob.bboxMinY + blob.bboxHeight - 1

        var consecutiveDense = 0
        var bodyStartX: Int? = null

        var x = startX
        while ((step > 0 && x <= endX) || (step < 0 && x >= endX)) {
            // Find longest contiguous run of motion pixels in this column
            var longestRun = 0
            var currentRun = 0
            for (y in minY..maxY) {
                val idx = y * WORK_W + x
                if (idx in motionMask.indices && (motionMask[idx].toInt() and 0xFF) == 255) {
                    currentRun++
                    if (currentRun > longestRun) longestRun = currentRun
                } else {
                    currentRun = 0
                }
            }

            val isDense = longestRun >= MIN_COLUMN_DENSITY_FOR_BODY

            if (isDense) {
                if (bodyStartX == null) bodyStartX = x
                consecutiveDense++
                if (consecutiveDense >= MIN_REGION_WIDTH_FOR_BODY) {
                    return bodyStartX!!.toFloat()
                }
            } else {
                consecutiveDense = 0
                bodyStartX = null
            }

            x += step
        }

        // Fallback: use centroid
        return blob.centroidX
    }

    // MARK: - Trajectory Analysis

    private data class TrajectoryResult(
        val crossingTime: Long,
        val velocity: Float,
        val bestFramePts: Long
    )

    private fun getOrderedTrajectoryPoints(): List<TrajectoryPoint> {
        if (trajectoryBuffer.isEmpty()) return emptyList()

        if (trajectoryBuffer.size < TRAJECTORY_BUFFER_SIZE) {
            return trajectoryBuffer.sortedBy { it.timestamp }
        }

        val ordered = ArrayList<TrajectoryPoint>(TRAJECTORY_BUFFER_SIZE)
        for (i in 0 until TRAJECTORY_BUFFER_SIZE) {
            val idx = (trajectoryIndex + i) % TRAJECTORY_BUFFER_SIZE
            ordered.add(trajectoryBuffer[idx])
        }
        return ordered
    }

    private fun calculateCrossingFromTrajectory(gateX: Float): TrajectoryResult? {
        if (trajectoryBuffer.size < 3) return null

        val points = getOrderedTrajectoryPoints()

        val n = points.size.toFloat()
        var sumT = 0f
        var sumX = 0f
        var sumTX = 0f
        var sumT2 = 0f

        val t0 = points[0].timestamp

        for (p in points) {
            val deltaNanos = p.timestamp - t0
            val t = deltaNanos / 1_000_000_000.0f
            val x = p.chestX
            sumT += t
            sumX += x
            sumTX += t * x
            sumT2 += t * t
        }

        val denominator = n * sumT2 - sumT * sumT
        if (abs(denominator) <= 0.0001f) return null

        val velocity = (n * sumTX - sumT * sumX) / denominator
        val intercept = (sumX - velocity * sumT) / n

        if (abs(velocity) <= 40) return null

        val crossingTimeSeconds = (gateX - intercept) / velocity

        if (crossingTimeSeconds < -0.15f || crossingTimeSeconds >= 0.3f) return null

        val crossingTimeNanos: Long = if (crossingTimeSeconds >= 0) {
            points[0].timestamp + (crossingTimeSeconds * 1_000_000_000).toLong()
        } else {
            val negativeOffsetNanos = (abs(crossingTimeSeconds) * 1_000_000_000).toLong()
            if (points[0].timestamp > negativeOffsetNanos) {
                points[0].timestamp - negativeOffsetNanos
            } else {
                points[0].timestamp
            }
        }

        // Find frame closest to crossing time for thumbnail
        var bestFrame = points[0]
        var bestDist = Long.MAX_VALUE
        for (p in points) {
            val dist = abs(crossingTimeNanos - p.timestamp)
            if (dist < bestDist) {
                bestDist = dist
                bestFrame = p
            }
        }

        return TrajectoryResult(crossingTimeNanos, abs(velocity), bestFrame.timestamp)
    }

    // MARK: - Helpers

    private fun checkNoValidBlobRearm(currentTime: Double) {
        if (isArmed) return

        framesWithNoValidBlob++
        if (framesWithNoValidBlob >= rearmFramesRequired) {
            isArmed = true
            armedAtTime = currentTime
            blobExitedSinceLastCrossing = true
            framesWithNoValidBlob = 0
            framesAtHysteresis = 0
        }
    }

    private fun makeResult(triggered: Boolean, rejection: RejectionReason): DetectionResult {
        return DetectionResult(
            triggered = triggered,
            blobHeightFraction = lastBlobHeightFraction,
            blobCenterX = lastBlobCenterX,
            velocityPxPerSec = lastVelocityPxPerSec,
            motionAmount = lastMotionAmount,
            cameraStable = isPhoneStable,
            rejection = rejection,
            state = state
        )
    }

    // MARK: - Noise Calibration

    private fun collectNoiseSamples() {
        val sampleStep = 8
        val workSize = WORK_W * WORK_H

        var i = 0
        while (i < workSize) {
            val diff = abs((currLumaSmall[i].toInt() and 0xFF) - (prevLumaSmall[i].toInt() and 0xFF))
            noiseCalibrationSamples.add(diff)
            i += sampleStep
        }
    }

    private fun finalizeNoiseCalibration() {
        if (noiseCalibrationSamples.size < 1000) {
            adaptiveDiffThreshold = DEFAULT_DIFF_THRESHOLD
            isNoiseCalibrated = true
            Log.i(TAG, "Noise calibration: insufficient samples (${noiseCalibrationSamples.size}), using default: $DEFAULT_DIFF_THRESHOLD")
            return
        }

        val sorted = noiseCalibrationSamples.sorted()
        val n = sorted.size
        val median = sorted[n / 2]

        // Calculate MAD
        val absDeviations = sorted.map { abs(it - median) }.sorted()
        val mad = absDeviations[n / 2]

        // threshold = median + 3.5 * MAD * 1.4826
        val sigma = mad * 1.4826
        val rawThreshold = median + 3.5 * sigma

        val clampedThreshold = rawThreshold.coerceIn(MIN_DIFF_THRESHOLD.toDouble(), MAX_DIFF_THRESHOLD.toDouble())
        adaptiveDiffThreshold = clampedThreshold.toInt()
        isNoiseCalibrated = true

        noiseCalibrationSamples.clear()
        Log.i(TAG, "Noise calibration complete: threshold=$adaptiveDiffThreshold, median=$median, MAD=$mad")
    }

    // MARK: - State Management

    fun reset() {
        state = if (isPhoneStable) State.READY else State.UNSTABLE
        processedFrameCount = 0
        cooldownStartTime = 0.0
        lastCrossingTime = 0.0
        prevChestX = null
        prevCentroidX = null
        lastConfirmedDirection = null
        prevTimestamp = 0
        isArmed = true
        framesAtHysteresis = 0
        armedAtTime = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
        blobExitedSinceLastCrossing = true
        framesWithNoValidBlob = 0
        lastBlobHeightFraction = 0f
        lastBlobCenterX = null
        lastVelocityPxPerSec = 0f
        lastMotionAmount = 0f
        lastRejection = RejectionReason.NONE

        trajectoryBuffer.clear()
        trajectoryIndex = 0
        frameSkipCounter = 0
        consecutiveNoMotionFrames = 0

        noiseCalibrationSamples.clear()
        adaptiveDiffThreshold = DEFAULT_DIFF_THRESHOLD
        isNoiseCalibrated = false

        currLumaSmall = ByteArray(WORK_W * WORK_H)
        prevLumaSmall = ByteArray(WORK_W * WORK_H)
    }

    val stateDescription: String
        get() {
            return when (state) {
                State.UNSTABLE -> if (requiresIMUStability) "Hold Steady" else "Ready"
                State.NO_ATHLETE -> "Ready"
                State.ATHLETE_TOO_FAR -> "Too Far"
                State.READY -> "Ready"
                State.TRIGGERED -> "Triggered"
                State.COOLDOWN -> "Cooldown"
            }
        }

    val isReady: Boolean
        get() {
            // If IMU stability not required, just check state (skip isPhoneStable)
            if (!requiresIMUStability) {
                return state == State.READY || state == State.NO_ATHLETE || state == State.ATHLETE_TOO_FAR
            }
            return state == State.READY && isPhoneStable
        }
}
