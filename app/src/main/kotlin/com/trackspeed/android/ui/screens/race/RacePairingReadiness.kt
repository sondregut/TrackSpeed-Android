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

/**
 * A joiner must not open timing from clock sync alone. Session config, the
 * authoritative (non-bootstrap) role assignment, and the host's startTiming
 * barrier may arrive in any order.
 */
internal fun joinerCanEnterTiming(
    hasSessionConfig: Boolean,
    hasRoleAssignment: Boolean,
    hasStartTiming: Boolean,
    hasAcceptableClockSync: Boolean
): Boolean = hasSessionConfig &&
    hasRoleAssignment &&
    hasStartTiming &&
    hasAcceptableClockSync

/** A terminal setup/sync failure is not a completed race, even if phase state arrived out of order. */
internal fun shouldPresentPreRaceConnectionFailure(
    phase: RacePhase,
    completedRunCount: Int,
    hasCurrentResult: Boolean,
    hasValidatedClockSync: Boolean
): Boolean = (phase in setOf(RacePhase.PAIRING, RacePhase.SYNCING, RacePhase.RACE_READY) ||
    !hasValidatedClockSync) &&
    completedRunCount == 0 &&
    !hasCurrentResult

internal fun userFacingPreRaceConnectionFailureReason(
    reason: String,
    fallback: String
): String {
    val normalized = reason.trim()
    if (normalized.isBlank() || normalized.lowercase() in setOf(
            "hostleft",
            "partnerleft",
            "userexit",
            "cancelled",
            "canceled"
        )
    ) {
        return fallback
    }

    // The iOS host can suggest its Direct Wi-Fi route. That route is not yet a
    // supported recovery action in the Android app, so do not offer a button or
    // instruction that this phone cannot fulfill.
    val directWifiSuffix = ", or use Direct Wi-Fi"
    val suffixStart = normalized.indexOf(directWifiSuffix, ignoreCase = true)
    val applicableMessage = if (suffixStart >= 0) {
        normalized.substring(0, suffixStart).trimEnd()
    } else {
        normalized
    }
    return if (applicableMessage.lastOrNull() in setOf('.', '!', '?')) {
        applicableMessage
    } else {
        "$applicableMessage."
    }
}
