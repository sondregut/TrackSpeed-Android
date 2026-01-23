package com.trackspeed.android.detection.experimental

/**
 * Configuration constants for Experimental detection mode.
 * All values are tunable based on field testing.
 *
 * Reference: docs/architecture/EXPERIMENTAL_MODE_KOTLIN.md
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
