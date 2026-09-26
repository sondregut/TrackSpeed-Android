package com.trackspeed.android.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RaceEventIdentityTest {

    @Test
    fun stableIdentityMatchesIosSha256UuidAlgorithm() {
        val id = RaceEventService.stableRaceEventId(
            sessionId = "session",
            runId = "run",
            eventType = "START",
            crossingTimeNanos = 123L,
            deviceId = "device"
        )

        assertEquals("5ced119c-9589-41cf-9e67-2d5073ecfa5e", id)
    }

    @Test
    fun identityKeepsLegitimateRunsAndCrossingsDistinct() {
        val first = RaceEventService.stableRaceEventId("session", "run-a", "start", 123L, "device")
        val otherRun = RaceEventService.stableRaceEventId("session", "run-b", "start", 123L, "device")
        val otherTime = RaceEventService.stableRaceEventId("session", "run-a", "start", 124L, "device")

        assertNotEquals(first, otherRun)
        assertNotEquals(first, otherTime)
    }
}
