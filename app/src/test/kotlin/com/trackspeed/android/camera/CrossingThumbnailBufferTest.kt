package com.trackspeed.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossingThumbnailBufferTest {

    @Test
    fun `closest frame uses detector selected timestamp`() {
        val timestamps = listOf(1_000L, 2_000L, 3_000L)

        assertEquals(0, closestTimestampIndex(timestamps, 1_100L))
        assertEquals(1, closestTimestampIndex(timestamps, 2_200L))
        assertEquals(2, closestTimestampIndex(timestamps, 2_900L))
    }

    @Test
    fun `tie keeps earlier buffered frame`() {
        assertEquals(0, closestTimestampIndex(listOf(1_000L, 2_000L), 1_500L))
    }

    @Test
    fun `empty buffer has no selected frame`() {
        assertNull(closestTimestampIndex(emptyList(), 1_000L))
    }

    @Test
    fun `unsupported live selector uses detector trigger frame for review`() {
        assertEquals(
            200L,
            reviewThumbnailTargetTimestamp(
                detectorTriggerFramePtsNanos = 200L,
                detectorSelectedFramePtsNanos = 190L,
                supportsLivePersonSelector = false
            )
        )
    }

    @Test
    fun `supported live selector keeps its selected frame`() {
        assertEquals(
            190L,
            reviewThumbnailTargetTimestamp(
                detectorTriggerFramePtsNanos = 200L,
                detectorSelectedFramePtsNanos = 190L,
                supportsLivePersonSelector = true
            )
        )
    }
}
