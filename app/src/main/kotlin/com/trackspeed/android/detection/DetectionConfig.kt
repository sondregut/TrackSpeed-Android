package com.trackspeed.android.detection

/**
 * Detection algorithm configuration constants.
 * Ported from iOS Speed Swift precision detection mode.
 *
 * Reference: docs/architecture/DETECTION_ALGORITHM.md
 */
object DetectionConfig {
    // ============================================================
    // PRIMARY DETECTION THRESHOLDS (with hysteresis)
    // ============================================================

    /** First contact - triggers interest */
    const val ENTER_THRESHOLD = 0.22f

    /** Must reach for valid crossing */
    const val CONFIRM_THRESHOLD = 0.35f

    /** Must drop below to start clear timer */
    const val GATE_CLEAR_BELOW = 0.15f

    /** Going above resets clear timer */
    const val GATE_UNCLEAR_ABOVE = 0.25f

    /** Fallback threshold when no pose detected (higher bar) */
    const val FALLBACK_ARM_THRESHOLD = 0.65f

    /** Strong evidence bypasses proximity check */
    const val STRONG_EVIDENCE_THRESHOLD = 0.85f

    /** Frames above confirm threshold before trigger */
    const val PERSISTENCE_FRAMES = 2

    // ============================================================
    // TIMING DURATIONS
    // ============================================================

    /** Gate must be clear for this duration before arming (ms) */
    const val GATE_CLEAR_DURATION_MS = 200

    /** Capture frames after trigger for this duration (ms) */
    const val POSTROLL_DURATION_MS = 200

    /** Pause between crossings (ms) */
    const val COOLDOWN_DURATION_MS = 100

    // ============================================================
    // DISTANCE FILTERING
    // ============================================================

    /** Minimum torso height as fraction of frame height */
    const val MIN_TORSO_HEIGHT_FRACTION = 0.12f

    // ============================================================
    // CHEST BAND CONFIGURATION
    // ============================================================

    /** Band half-height as factor of torso height */
    const val BAND_HALF_HEIGHT_FACTOR = 0.03f

    /** Minimum band half-height in pixels */
    const val BAND_HALF_HEIGHT_MIN = 8

    /** Maximum band half-height in pixels */
    const val BAND_HALF_HEIGHT_MAX = 20

    /** Minimum run length as fraction of band height */
    const val MIN_RUN_CHEST_FRACTION = 0.55f

    /** Absolute minimum run length in pixels */
    const val ABSOLUTE_MIN_RUN_CHEST = 12

    // ============================================================
    // THREE-STRIP VALIDATION
    // ============================================================

    /** Adjacent strip threshold for torso-like validation */
    const val ADJACENT_STRIP_THRESHOLD = 0.55f

    // ============================================================
    // POSE PROXIMITY VALIDATION
    // ============================================================

    /** Max distance tolerance with full pose (shoulder+hip) */
    const val FULL_POSE_MAX_DISTANCE = 0.30f

    /** Max distance tolerance with partial pose (stricter) */
    const val PARTIAL_POSE_MAX_DISTANCE = 0.20f

    // ============================================================
    // BACKGROUND MODEL CONFIGURATION
    // ============================================================

    /** Number of frames for calibration */
    const val CALIBRATION_FRAMES = 30

    /** Minimum MAD threshold */
    const val MIN_MAD = 10.0f

    /** Threshold = MAD × multiplier */
    const val MAD_MULTIPLIER = 3.5f

    /** Fallback threshold if MAD too low */
    const val DEFAULT_THRESHOLD = 45.0f

    /** Pixels around gate line for sampling */
    const val SAMPLING_BAND_WIDTH = 5

    /** Very slow adaptation rate (0.2%) */
    const val ADAPTATION_RATE = 0.002f

    // ============================================================
    // ROLLING SHUTTER CORRECTION
    // ============================================================

    /** Default readout time estimate in ms */
    const val DEFAULT_READOUT_TIME_MS = 5.0

    // ============================================================
    // FRAME RATE
    // ============================================================

    /** Target frame rate for calculations */
    const val TARGET_FPS = 240
}
