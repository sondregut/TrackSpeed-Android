package com.trackspeed.android.ui.screens.race

import androidx.annotation.StringRes
import com.trackspeed.android.R
import com.trackspeed.android.sync.BleClockSyncService

/** Display-only projection. Never used to accept a connection, start timing or retry. */
enum class RacePairingActivity {
    IDLE, SEARCHING, CONFIRMING_PAIRING, CONNECTING, CONNECTED, SYNCHRONIZING, ERROR
}

internal fun BleClockSyncService.State.toPairingActivity(): RacePairingActivity = when (this) {
    BleClockSyncService.State.Idle -> RacePairingActivity.IDLE
    BleClockSyncService.State.Scanning, BleClockSyncService.State.Pairing -> RacePairingActivity.SEARCHING
    BleClockSyncService.State.Bonding -> RacePairingActivity.CONFIRMING_PAIRING
    BleClockSyncService.State.Connecting -> RacePairingActivity.CONNECTING
    BleClockSyncService.State.Connected, BleClockSyncService.State.ClientReady -> RacePairingActivity.CONNECTED
    is BleClockSyncService.State.Syncing -> RacePairingActivity.SYNCHRONIZING
    // Clock sync alone does not establish protocol/session readiness.
    is BleClockSyncService.State.Synced -> RacePairingActivity.CONNECTED
    is BleClockSyncService.State.Error -> RacePairingActivity.ERROR
}

@StringRes
internal fun RacePairingActivity.titleRes(hostMode: Boolean = false): Int = when (this) {
    RacePairingActivity.IDLE -> R.string.race_join_ready_title
    RacePairingActivity.SEARCHING -> if (hostMode) R.string.race_pairing_status_waiting_joiners
        else R.string.race_join_scanning_title
    RacePairingActivity.CONFIRMING_PAIRING -> R.string.race_pairing_status_confirm_pairing
    RacePairingActivity.CONNECTING -> if (hostMode) R.string.race_pairing_status_connecting_phone
        else R.string.race_pairing_status_connecting_host
    RacePairingActivity.CONNECTED -> R.string.race_pairing_status_connected
    RacePairingActivity.SYNCHRONIZING -> R.string.race_pairing_synchronizing_phones
    RacePairingActivity.ERROR -> R.string.race_pairing_status_error
}
