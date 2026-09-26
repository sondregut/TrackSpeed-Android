package com.trackspeed.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncSetupProgressTest {
    @Test
    fun `reference progress moves as soon as the first timing sample arrives`() {
        assertEquals(0f, ClockSyncSetupProgress.fromCompletedSamples(0), 0.0001f)
        assertTrue(ClockSyncSetupProgress.fromCompletedSamples(1) > 0f)
    }

    @Test
    fun `optional second measurement never resets or reaches complete before validation`() {
        val firstMeasurement = ClockSyncSetupProgress.fromCompletedSamples(80)
        val retryStarted = ClockSyncSetupProgress.fromCompletedSamples(81)
        val allMeasurements = ClockSyncSetupProgress.fromCompletedSamples(160)
        val extraSamples = ClockSyncSetupProgress.fromCompletedSamples(240)

        assertEquals(0.80f, firstMeasurement, 0.0001f)
        assertTrue(retryStarted > firstMeasurement)
        assertEquals(0.95f, allMeasurements, 0.0001f)
        assertEquals(0.95f, extraSamples, 0.0001f)
    }
}
