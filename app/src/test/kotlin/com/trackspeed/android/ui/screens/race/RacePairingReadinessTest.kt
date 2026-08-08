package com.trackspeed.android.ui.screens.race

import org.junit.Assert.assertFalse
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
}
