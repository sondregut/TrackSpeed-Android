package com.trackspeed.android.detection

/**
 * A horizontal run of "on" pixels in a single row.
 */
data class CCLRun(
    var xStart: Short,
    var xEnd: Short,
    var row: Short,
    var label: UShort
)

/**
 * Accumulator for blob statistics (pre-allocated, reset per frame).
 */
class BlobStat {
    var minX: Short = Short.MAX_VALUE
    var maxX: Short = Short.MIN_VALUE
    var minY: Short = Short.MAX_VALUE
    var maxY: Short = Short.MIN_VALUE
    var area: Int = 0
    var sumX: Int = 0
    var sumY: Int = 0

    fun reset() {
        minX = Short.MAX_VALUE; maxX = Short.MIN_VALUE
        minY = Short.MAX_VALUE; maxY = Short.MIN_VALUE
        area = 0; sumX = 0; sumY = 0
    }

    fun addRun(run: CCLRun) {
        if (run.xStart < minX) minX = run.xStart
        if (run.xEnd > maxX) maxX = run.xEnd
        if (run.row < minY) minY = run.row
        if (run.row > maxY) maxY = run.row

        val width = (run.xEnd - run.xStart + 1).toInt()
        area += width

        val centerX = (run.xStart + run.xEnd).toFloat() * 0.5f
        sumX += (width.toFloat() * centerX).toInt()
        sumY += width * run.row.toInt()
    }

    fun merge(other: BlobStat) {
        if (other.minX < minX) minX = other.minX
        if (other.maxX > maxX) maxX = other.maxX
        if (other.minY < minY) minY = other.minY
        if (other.maxY > maxY) maxY = other.maxY
        area += other.area
        sumX += other.sumX
        sumY += other.sumY
    }
}

/**
 * Output blob for detection pipeline.
 */
data class CCLBlob(
    val bboxMinX: Int,
    val bboxMinY: Int,
    val bboxWidth: Int,
    val bboxHeight: Int,
    val centroidX: Float,
    val centroidY: Float,
    val areaPixels: Int,
    val heightFrac: Float
)

/**
 * Row-run connected component labeling with zero steady-state allocations.
 * Ported from iOS ZeroAllocCCL.swift.
 */
class ZeroAllocCCL(
    private val width: Int,
    private val height: Int,
    private val maxLabels: Int = 4096
) {
    private val maxRunsPerRow = minOf(width / 2, 512)

    // Pre-allocated buffers
    private val equivalenceTable = UShortArray(maxLabels) { it.toUShort() }
    private val labelStats = Array(maxLabels) { BlobStat() }
    private val runsA = Array(maxRunsPerRow) { CCLRun(0, 0, 0, 0u) }
    private val runsB = Array(maxRunsPerRow) { CCLRun(0, 0, 0, 0u) }

    // Output buffer
    private val outputBlobs = Array(MAX_BLOB_CANDIDATES) {
        CCLBlob(0, 0, 0, 0, 0f, 0f, 0, 0f)
    }

    companion object {
        const val MAX_BLOB_CANDIDATES = 32
    }

    /**
     * Process binary mask and return blobs.
     * @param mask UInt8 mask (0 or 255), size = maskWidth * maskHeight
     * @param maskWidth Width of mask
     * @param maskHeight Height of mask
     * @return List of detected blobs
     */
    fun process(
        mask: ByteArray,
        maskWidth: Int,
        maskHeight: Int
    ): List<CCLBlob> {
        // 1. Reset state
        var nextLabel: UShort = 1u

        // Fast reset of equivalence table (i -> i)
        for (i in 0 until maxLabels) {
            equivalenceTable[i] = i.toUShort()
        }

        // Reset stats
        for (i in 0 until maxLabels) {
            labelStats[i].reset()
        }

        var currentRuns = runsA
        var prevRuns = runsB
        var prevRunCount = 0

        // 2. Process rows
        for (y in 0 until maskHeight) {
            val rowOffset = y * maskWidth
            var currentRunCount = 0

            // 2a. Find runs in current row
            var x = 0
            while (x < maskWidth) {
                if ((mask[rowOffset + x].toInt() and 0xFF) > 0) {
                    val start = x
                    while (x < maskWidth && (mask[rowOffset + x].toInt() and 0xFF) > 0) x++
                    val end = x - 1

                    if (currentRunCount < maxRunsPerRow) {
                        val run = currentRuns[currentRunCount]
                        run.xStart = start.toShort()
                        run.xEnd = end.toShort()
                        run.row = y.toShort()
                        run.label = 0u
                        currentRunCount++
                    }
                } else {
                    x++
                }
            }

            // 2b. Connect runs to previous row
            for (cIdx in 0 until currentRunCount) {
                val cRun = currentRuns[cIdx]
                var labelToUse: UShort = 0u

                for (pIdx in 0 until prevRunCount) {
                    val pRun = prevRuns[pIdx]

                    // Check horizontal overlap
                    if (pRun.xEnd >= cRun.xStart && pRun.xStart <= cRun.xEnd) {
                        val pLabel = resolve(pRun.label)

                        if (labelToUse == (0).toUShort()) {
                            labelToUse = pLabel
                        } else if (labelToUse != pLabel) {
                            union(labelToUse, pLabel)
                            labelToUse = resolve(labelToUse)
                        }
                    }
                }

                // 2c. Assign label (new or from prev)
                if (labelToUse == (0).toUShort()) {
                    if (nextLabel.toInt() < maxLabels) {
                        labelToUse = nextLabel
                        nextLabel = (nextLabel.toInt() + 1).toUShort()
                        labelStats[labelToUse.toInt()].reset()
                    } else {
                        labelToUse = 1u // Fallback
                    }
                }

                cRun.label = labelToUse

                // 2d. Accumulate stats
                labelStats[labelToUse.toInt()].addRun(cRun)
            }

            // Swap run buffers
            val temp = currentRuns
            currentRuns = prevRuns
            prevRuns = temp
            prevRunCount = currentRunCount
        }

        // 3. Merge stats for equivalent labels
        for (i in 1 until nextLabel.toInt()) {
            val root = resolve(i.toUShort()).toInt()
            if (root != i) {
                labelStats[root].merge(labelStats[i])
            }
        }

        // 4. Output blobs (only roots with sufficient area)
        var blobCount = 0
        val minArea = 10

        for (i in 1 until nextLabel.toInt()) {
            if (equivalenceTable[i].toInt() != i) continue // Only roots
            val s = labelStats[i]
            if (s.area < minArea) continue
            if (blobCount >= MAX_BLOB_CANDIDATES) break

            val cx = s.sumX.toFloat() / s.area
            val cy = s.sumY.toFloat() / s.area
            val bboxW = (s.maxX - s.minX + 1).toInt()
            val bboxH = (s.maxY - s.minY + 1).toInt()

            outputBlobs[blobCount] = CCLBlob(
                bboxMinX = s.minX.toInt(),
                bboxMinY = s.minY.toInt(),
                bboxWidth = bboxW,
                bboxHeight = bboxH,
                centroidX = cx,
                centroidY = cy,
                areaPixels = s.area,
                heightFrac = bboxH.toFloat() / maskHeight
            )
            blobCount++
        }

        return outputBlobs.take(blobCount)
    }

    // MARK: - Union-Find with Path Compression

    private fun resolve(label: UShort): UShort {
        var i = label.toInt()
        while (i != equivalenceTable[i].toInt()) {
            equivalenceTable[i] = equivalenceTable[equivalenceTable[i].toInt()] // Path compression
            i = equivalenceTable[i].toInt()
        }
        return i.toUShort()
    }

    private fun union(a: UShort, b: UShort) {
        val rootA = resolve(a).toInt()
        val rootB = resolve(b).toInt()
        if (rootA != rootB) {
            if (rootA < rootB) {
                equivalenceTable[rootB] = rootA.toUShort()
            } else {
                equivalenceTable[rootA] = rootB.toUShort()
            }
        }
    }
}
