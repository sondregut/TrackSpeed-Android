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

    /** Clock samples can arrive during iOS's provisional start-reference setup. */
    fun stateDuringClockMeasurement(state: ClockSyncManager.ProtocolState): ClockSyncManager.ProtocolState =
        when (state) {
            ClockSyncManager.ProtocolState.HANDSHAKE_COMPLETE,
            ClockSyncManager.ProtocolState.SYNCING,
            ClockSyncManager.ProtocolState.READY -> ClockSyncManager.ProtocolState.SYNCING
            else -> state
        }

    /** Validated clock quality does not replace the authoritative config and role handshake. */
    fun stateAfterReferenceValidation(state: ClockSyncManager.ProtocolState): ClockSyncManager.ProtocolState =
        when (state) {
            ClockSyncManager.ProtocolState.HANDSHAKE_COMPLETE,
            ClockSyncManager.ProtocolState.SYNCING,
            ClockSyncManager.ProtocolState.READY -> ClockSyncManager.ProtocolState.READY
            else -> state
        }

    fun shouldStartReferenceSync(
        handshakeCompleteOnCurrentLink: Boolean,
        previousReferenceDeviceId: String?,
        incomingReferenceDeviceId: String
    ): Boolean = !handshakeCompleteOnCurrentLink ||
        !incomingReferenceDeviceId.equals(previousReferenceDeviceId, ignoreCase = true)
}
