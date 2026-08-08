package com.trackspeed.android.ui.screens.race

/**
 * A host may enter race-ready state only after every required physical phone
 * is connected and has completed the protocol handshake plus clock sync.
 */
internal fun pairingHasRequiredReadyDevices(
    connectedDeviceCount: Int,
    syncedDeviceCount: Int,
    requiredDeviceCount: Int
): Boolean {
    val required = requiredDeviceCount.coerceAtLeast(1)
    return connectedDeviceCount >= required && syncedDeviceCount >= required
}
