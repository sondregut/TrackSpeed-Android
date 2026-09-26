package com.trackspeed.android.ui.screens.race

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiGateConfigTest {
    @Test
    fun `default four gate session distributes intermediate gates evenly`() {
        assertEquals(
            mapOf(0 to 0.0, 1 to 40.0, 2 to 80.0, 3 to 120.0),
            defaultGateDistances(gateCount = 4, totalDistanceMeters = 120.0)
        )
    }

    @Test
    fun `custom gate distances preserve valid ascending layout`() {
        assertEquals(
            mapOf(0 to 0.0, 1 to 30.0, 2 to 70.0, 3 to 120.0),
            parseGateDistances("0,30,70,120", gateCount = 4, fallbackTotalDistanceMeters = 120.0)
        )
    }

    @Test
    fun `normalization repairs descending intermediate distance`() {
        assertEquals(
            mapOf(0 to 0.0, 1 to 50.0, 2 to 51.0, 3 to 120.0),
            normalizeGateDistances(
                gateDistances = mapOf(0 to 0.0, 1 to 50.0, 2 to 40.0, 3 to 120.0),
                gateCount = 4,
                fallbackTotalDistanceMeters = 120.0
            )
        )
    }

    @Test
    fun `changing total distance scales every non-start gate`() {
        assertEquals(
            mapOf(0 to 0.0, 1 to 40.0, 2 to 120.0, 3 to 200.0),
            scaledGateDistances(
                gateCount = 4,
                totalDistanceMeters = 200.0,
                currentGateDistances = mapOf(0 to 0.0, 1 to 20.0, 2 to 60.0, 3 to 100.0)
            )
        )
    }

    @Test
    fun `gate distance equality uses one millimeter tolerance`() {
        val baseline = mapOf(0 to 0.0, 1 to 50.0, 2 to 100.0)
        assertTrue(sameGateDistances(baseline, baseline + (1 to 50.0009), 3))
        assertFalse(sameGateDistances(baseline, baseline + (1 to 50.0011), 3))
    }

    @Test
    fun `four gate crossings produce segment and cumulative splits`() {
        val segments = buildMultiGateSegmentSplits(
            orderedCrossings = listOf(
                MultiGateCrossingTime("start", 0, 1_000_000_000L),
                MultiGateCrossingTime("split-1", 1, 2_000_000_000L),
                MultiGateCrossingTime("split-2", 2, 3_500_000_000L),
                MultiGateCrossingTime("finish", 3, 5_000_000_000L)
            ),
            gateDistances = mapOf(0 to 0.0, 1 to 30.0, 2 to 70.0, 3 to 100.0),
            gateCount = 4
        )

        assertEquals(3, segments.size)
        assertEquals(1_000_000_000L, segments[0].splitNanos)
        assertEquals(1_500_000_000L, segments[1].splitNanos)
        assertEquals(4_000_000_000L, segments[2].cumulativeSplitNanos)
        assertEquals(40.0, segments[1].distanceMeters, 0.0)
        assertEquals(100.0, segments[2].cumulativeDistanceMeters, 0.0)
    }
}
