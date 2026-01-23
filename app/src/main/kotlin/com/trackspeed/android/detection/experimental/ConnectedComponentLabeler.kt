package com.trackspeed.android.detection.experimental

import android.graphics.PointF
import android.graphics.RectF

/**
 * Row-run connected component labeling with Union-Find.
 *
 * Algorithm:
 * 1. Scan rows for runs of foreground pixels
 * 2. Assign labels, connect overlapping runs via Union-Find
 * 3. Compute blob statistics (bbox, centroid, area)
 * 4. Output blob list with filters applied
 *
 * ZERO ALLOCATIONS during steady-state processing.
 * Uses pre-allocated blob pool - do NOT store blob references across frames!
 */
class ConnectedComponentLabeler(
    private val maxWidth: Int,
    private val maxHeight: Int,
    private val maxLabels: Int = 4096,
    private val maxBlobs: Int = 32
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

        /** Copy from another blob */
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
    private val blobPool = Array(maxBlobs) { Blob() }
    private var activeBlobCount = 0

    /**
     * Get the list of active blobs from the last findBlobs() call.
     *
     * WARNING: Returns a view into the internal pool. Valid only until
     * the next findBlobs() call. Do NOT store these references.
     */
    fun getActiveBlobs(): List<Blob> = blobPool.take(activeBlobCount)

    /**
     * Find connected components in binary mask.
     *
     * ZERO ALLOCATIONS: Uses pre-allocated blob pool.
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

        // Build output blobs using pool
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
     * Reset the labeler.
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
        while (equivalence[i] != i) {
            equivalence[i] = equivalence[equivalence[i]]  // Path compression
            i = equivalence[i]
        }
        return i
    }

    private fun union(a: Int, b: Int) {
        val rootA = resolve(a)
        val rootB = resolve(b)
        if (rootA != rootB) {
            equivalence[rootB] = rootA
        }
    }
}
