package com.trackspeed.android.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossingDebugUploadQueueTest {
    @Test
    fun `review frames select one complete five frame window around target`() {
        val firstCrossing = (0..4).map { index -> frame(index.toLong(), 100L + index * 10L) }
        val secondCrossing = (5..9).map { index -> frame(index.toLong(), 1_000L + (index - 5) * 10L) }

        val selected = canonicalCrossingReviewFrames(
            frames = firstCrossing + secondCrossing,
            targetPtsNanos = 1_020L
        )

        assertEquals(listOf(1_000L, 1_010L, 1_020L, 1_030L, 1_040L), selected.map { it.ptsNanos })
        assertEquals(listOf(-2, -1, 0, 1, 2), selected.map { it.relativeFrame })
    }

    @Test
    fun `review frames return a partial window at a capture boundary`() {
        val selected = canonicalCrossingReviewFrames(
            frames = listOf(frame(1L, 100L), frame(2L, 110L), frame(3L, 120L)),
            targetPtsNanos = 100L
        )

        assertEquals(listOf(0, 1, 2), selected.map { it.relativeFrame })
    }

    private fun frame(number: Long, pts: Long) = CrossingDebugFramePayload(
        imagePath = "/tmp/frame-$number.jpg",
        ptsNanos = pts,
        frameNumber = number,
        chestX = 0.5f,
        blobHeightFraction = 0.5f,
        velocityPxPerSec = 100f
    )
}
