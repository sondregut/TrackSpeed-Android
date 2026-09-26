package com.trackspeed.android.sync

/** Shared, deterministic gate for the one terminal setup-failure envelope. */
internal object ClockSyncFailureNotificationPolicy {
    fun shouldNotify(
        protocolState: ClockSyncManager.ProtocolState,
        alreadyNotified: Boolean
    ): Boolean = !alreadyNotified && protocolState == ClockSyncManager.ProtocolState.SYNCING

    fun userFacingReason(): String =
        "Bluetooth stayed connected, but clock sync could not meet the required " +
            "±${ClockSyncConfig.SETUP_MAX_UNCERTAINTY_MS.toInt()} ms accuracy. " +
            "Keep the phones close and retry."
}
