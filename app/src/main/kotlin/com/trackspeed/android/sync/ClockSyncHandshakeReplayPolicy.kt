package com.trackspeed.android.sync

/** Keeps authoritative config replays idempotent instead of restarting setup. */
internal object ClockSyncHandshakeReplayPolicy {
    fun shouldRequestRoleForConfig(state: ClockSyncManager.ProtocolState): Boolean =
        when (state) {
            ClockSyncManager.ProtocolState.IDLE,
            ClockSyncManager.ProtocolState.CONNECTED,
            ClockSyncManager.ProtocolState.AWAITING_CONFIG -> true
            ClockSyncManager.ProtocolState.AWAITING_ROLE,
            ClockSyncManager.ProtocolState.AWAITING_ASSIGNMENT,
            ClockSyncManager.ProtocolState.HANDSHAKE_COMPLETE,
            ClockSyncManager.ProtocolState.SYNCING,
            ClockSyncManager.ProtocolState.READY -> false
        }

    fun shouldReplayConfigForIdentity(existingSenderId: String?): Boolean =
        existingSenderId == null

    fun isCompletedDuplicateRoleRequest(
        handshakeComplete: Boolean,
        existingSenderId: String?,
        incomingSenderId: String
    ): Boolean = handshakeComplete &&
        existingSenderId?.equals(incomingSenderId, ignoreCase = true) == true
}
