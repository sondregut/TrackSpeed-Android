package com.trackspeed.android.detection

/**
 * Contiguous run filter for chest band analysis.
 *
 * Finds the longest contiguous run of foreground pixels within
 * a chest band region. This helps reject scattered noise and
 * ensures we're detecting a solid torso.
 *
 * Reference: docs/architecture/DETECTION_ALGORITHM.md Section 6
 */
object ContiguousRunFilter {

    /**
     * Calculate the chest band region based on torso bounds.
     *
     * @param torsoBounds Torso bounds from pose detection (may be null)
     * @param frameHeight Total frame height in pixels
     * @return ChestBand region to analyze
     */
    fun calculateChestBand(torsoBounds: TorsoBounds?, frameHeight: Int): ChestBand {
        if (torsoBounds == null) {
            // No pose - use full frame
            return ChestBand(
                top = 0,
                bottom = frameHeight - 1,
                height = frameHeight
            )
        }

        // Torso bounds are normalized (0-1)
        val torsoTopPx = (torsoBounds.yTop * frameHeight).toInt()
        val torsoBottomPx = (torsoBounds.yBottom * frameHeight).toInt()
        val torsoHeightPx = torsoBottomPx - torsoTopPx

        // Chest is approximately 1/3 from top of torso
        val chestCenterPx = torsoTopPx + (torsoHeightPx * 0.33f).toInt()

        // Band half-height based on torso size
        val bandHalfHeight = (DetectionConfig.BAND_HALF_HEIGHT_FACTOR * torsoHeightPx)
            .toInt()
            .coerceIn(DetectionConfig.BAND_HALF_HEIGHT_MIN, DetectionConfig.BAND_HALF_HEIGHT_MAX)

        return ChestBand(
            top = (chestCenterPx - bandHalfHeight).coerceAtLeast(0),
            bottom = (chestCenterPx + bandHalfHeight).coerceAtMost(frameHeight - 1),
            height = bandHalfHeight * 2
        )
    }

    /**
     * Find the longest contiguous run of foreground pixels in a mask.
     *
     * @param foregroundMask Boolean array from background subtraction
     * @param band Chest band region to analyze
     * @return RunResult with longest run length and occupancy
     */
    fun findLongestRun(foregroundMask: BooleanArray, band: ChestBand): RunResult {
        var longestRun = 0
        var longestStart = band.top
        var longestEnd = band.top

        var currentRun = 0
        var currentStart = band.top

        for (row in band.top..minOf(band.bottom, foregroundMask.size - 1)) {
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

        val bandHeight = (band.bottom - band.top + 1).coerceAtLeast(1)

        return RunResult(
            longestRun = longestRun,
            occupancy = longestRun.toFloat() / bandHeight,
            runStartRow = longestStart,
            runEndRow = longestEnd
        )
    }

    /**
     * Analyze three strips for torso-like shape validation.
     *
     * A torso-like crossing has:
     * - Center strip with high occupancy (>= CONFIRM_THRESHOLD)
     * - At least one adjacent strip (left or right) also solid (>= ADJACENT_STRIP_THRESHOLD)
     *
     * This rejects thin objects like arms or legs.
     *
     * @param leftMask Foreground mask for left strip
     * @param centerMask Foreground mask for center strip
     * @param rightMask Foreground mask for right strip
     * @param band Chest band region
     * @return ThreeStripResult with analysis
     */
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
        val isTorsoLike = centerResult.occupancy >= DetectionConfig.CONFIRM_THRESHOLD &&
            centerResult.longestRun >= DetectionConfig.ABSOLUTE_MIN_RUN_CHEST &&
            (leftResult.occupancy >= DetectionConfig.ADJACENT_STRIP_THRESHOLD ||
             rightResult.occupancy >= DetectionConfig.ADJACENT_STRIP_THRESHOLD)

        return ThreeStripResult(
            leftOccupancy = leftResult.occupancy,
            centerOccupancy = centerResult.occupancy,
            rightOccupancy = rightResult.occupancy,
            leftRun = leftResult.longestRun,
            centerRun = centerResult.longestRun,
            rightRun = rightResult.longestRun,
            isTorsoLike = isTorsoLike,
            centerRunResult = centerResult
        )
    }
}

/**
 * Chest band region for analysis.
 */
data class ChestBand(
    val top: Int,
    val bottom: Int,
    val height: Int
)

/**
 * Result of longest contiguous run analysis.
 */
data class RunResult(
    val longestRun: Int,
    val occupancy: Float,
    val runStartRow: Int,
    val runEndRow: Int
)

/**
 * Result of three-strip torso validation.
 */
data class ThreeStripResult(
    val leftOccupancy: Float,
    val centerOccupancy: Float,
    val rightOccupancy: Float,
    val leftRun: Int,
    val centerRun: Int,
    val rightRun: Int,
    val isTorsoLike: Boolean,
    val centerRunResult: RunResult
)

/**
 * Torso bounds from pose detection.
 */
data class TorsoBounds(
    val yTop: Float,        // Normalized 0-1 (shoulder Y)
    val yBottom: Float,     // Normalized 0-1 (hip Y)
    val confidence: Float,  // Detection confidence
    val hasFullPose: Boolean // Both shoulder and hip detected
)
