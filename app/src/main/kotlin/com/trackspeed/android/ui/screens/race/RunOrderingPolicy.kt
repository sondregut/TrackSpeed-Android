package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingRole

/** Latest start wins; earliest finish/lap wins. */
internal fun crossingCandidateWins(
    role: TimingRole,
    existingTimestampNanos: Long,
    candidateTimestampNanos: Long
): Boolean = when (role) {
    TimingRole.START_LINE -> candidateTimestampNanos > existingTimestampNanos
    TimingRole.FINISH_LINE, TimingRole.LAP_GATE -> candidateTimestampNanos < existingTimestampNanos
    TimingRole.CONTROL_ONLY -> false
}
