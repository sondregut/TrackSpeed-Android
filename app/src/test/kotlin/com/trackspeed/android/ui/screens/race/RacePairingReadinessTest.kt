package com.trackspeed.android.ui.screens.race

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RacePairingReadinessTest {
    @Test
    fun `host is ready only when every required phone is connected and synced`() {
        assertFalse(pairingHasRequiredReadyDevices(4, 1, 4))
        assertFalse(pairingHasRequiredReadyDevices(3, 3, 4))
        assertTrue(pairingHasRequiredReadyDevices(4, 4, 4))
        assertTrue(pairingHasRequiredReadyDevices(5, 4, 4))
    }

    @Test
    fun `joiner waits for config role host barrier and acceptable clock in any order`() {
        assertFalse(joinerCanEnterTiming(false, true, true, true))
        assertFalse(joinerCanEnterTiming(true, false, true, true))
        assertFalse(joinerCanEnterTiming(true, true, false, true))
        assertFalse(joinerCanEnterTiming(true, true, true, false))
        assertTrue(joinerCanEnterTiming(true, true, true, true))
    }

    @Test
    fun `peer ending during setup is shown as a connection failure`() {
        assertTrue(shouldPresentPreRaceConnectionFailure(RacePhase.PAIRING, 0, false, false))
        assertTrue(shouldPresentPreRaceConnectionFailure(RacePhase.SYNCING, 0, false, false))
        assertTrue(shouldPresentPreRaceConnectionFailure(RacePhase.RACE_READY, 0, false, true))
    }

    @Test
    fun `invalid clock cannot fall through to active session summary`() {
        assertTrue(shouldPresentPreRaceConnectionFailure(RacePhase.ACTIVE_RACE, 0, false, false))
    }

    @Test
    fun `validated active timing and existing results keep the normal session summary`() {
        assertFalse(shouldPresentPreRaceConnectionFailure(RacePhase.ACTIVE_RACE, 0, false, true))
        assertFalse(shouldPresentPreRaceConnectionFailure(RacePhase.RESULT, 0, true, false))
        assertFalse(shouldPresentPreRaceConnectionFailure(RacePhase.RACE_READY, 1, false, false))
    }

    @Test
    fun `iOS failure reason keeps accuracy but removes unavailable Direct Wi-Fi advice`() {
        val reason = "Bluetooth stayed connected, but clock sync could not meet the required " +
            "accuracy (±14 ms). Keep the phones close and retry, or use Direct Wi-Fi."

        assertEquals(
            "Bluetooth stayed connected, but clock sync could not meet the required accuracy " +
                "(±14 ms). Keep the phones close and retry.",
            userFacingPreRaceConnectionFailureReason(reason, fallback = "Try again.")
        )
    }

    @Test
    fun `internal session end reason uses connection failure fallback`() {
        assertEquals(
            "Bluetooth setup failed.",
            userFacingPreRaceConnectionFailureReason(
                reason = "hostLeft",
                fallback = "Bluetooth setup failed."
            )
        )
    }
}
