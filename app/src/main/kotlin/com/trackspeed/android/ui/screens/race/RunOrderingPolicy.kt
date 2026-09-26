package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingRole
import com.trackspeed.android.protocol.TimingPayload

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

/** Validate ordering only. Tiny intentional test splits are still valid. */
internal fun orderedTimingSplit(startNanos: Long, finishNanos: Long): Long? =
    if (startNanos > 0 && finishNanos > startNanos) finishNanos - startNanos else null

internal fun acceptsReplacementStart(
    candidateNanos: Long,
    previousStartNanos: Long?,
    firstFinishNanos: Long?,
    allowsReplacement: Boolean
): Boolean = candidateNanos > 0 &&
    (previousStartNanos == null || (allowsReplacement && candidateNanos > previousStartNanos)) &&
    (firstFinishNanos == null || candidateNanos < firstFinishNanos)

internal fun validMultiGateResult(result: TimingPayload.MultiGateResult): Boolean {
    if (result.totalSplitNanos <= 0 || !result.uncertaintyMs.isFinite() || result.uncertaintyMs < 0 ||
        result.segments.isEmpty()) return false
    var cumulative = 0L
    var previousGate = 0
    for (segment in result.segments) {
        if (segment.fromGateIndex != previousGate || segment.toGateIndex <= previousGate ||
            segment.splitNanos <= 0 || segment.splitNanos > result.totalSplitNanos - cumulative) return false
        cumulative += segment.splitNanos
        if (segment.cumulativeSplitNanos != cumulative) return false
        previousGate = segment.toGateIndex
    }
    return cumulative == result.totalSplitNanos
}
