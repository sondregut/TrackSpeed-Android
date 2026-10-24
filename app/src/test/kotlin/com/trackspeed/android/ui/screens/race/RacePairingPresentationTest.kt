package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.R
import com.trackspeed.android.sync.BleClockSyncService
import com.trackspeed.android.sync.ClockSyncCalculator
import com.trackspeed.android.sync.SyncQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RacePairingPresentationTest {
    @Test fun `only active discovery is presented as searching`() {
        assertEquals(RacePairingActivity.SEARCHING, BleClockSyncService.State.Scanning.toPairingActivity())
        assertEquals(RacePairingActivity.SEARCHING, BleClockSyncService.State.Pairing.toPairingActivity())
        assertEquals(RacePairingActivity.CONFIRMING_PAIRING, BleClockSyncService.State.Bonding.toPairingActivity())
        assertEquals(RacePairingActivity.CONNECTING, BleClockSyncService.State.Connecting.toPairingActivity())
        assertEquals(RacePairingActivity.ERROR, BleClockSyncService.State.Error("synthetic failure").toPairingActivity())
        assertEquals(RacePairingActivity.IDLE, BleClockSyncService.State.Idle.toPairingActivity())
    }

    @Test fun `clock sync does not advertise protocol readiness or alter its accuracy gate`() {
        for (uncertainty in listOf(0.5, 12.0)) {
            val result = ClockSyncCalculator.SyncResult(0, uncertainty, 5, 5, SyncQuality.FAIR,
                1.0, 4.0, 2.0, 2.0, 4.0)
            val display = BleClockSyncService.State.Synced(result).toPairingActivity()
            assertEquals(RacePairingActivity.CONNECTED, display)
            assertNotEquals(R.string.race_pairing_ready_to_time, display.titleRes())
        }
        assertEquals(RacePairingActivity.CONNECTED, BleClockSyncService.State.ClientReady.toPairingActivity())
        assertEquals(RacePairingActivity.SYNCHRONIZING, BleClockSyncService.State.Syncing(.5f).toPairingActivity())
    }
}
