package com.trackspeed.android.sync

/** Keep the peer's existing processing-latency guard; never hide a local stall. */
internal object ClockSyncPongSubmissionPolicy {
    fun isFresh(receivedAtNanos: Long, sentAtNanos: Long): Boolean =
        receivedAtNanos >= 0L && sentAtNanos > receivedAtNanos &&
            sentAtNanos - receivedAtNanos <= ClockSyncConfig.MAX_PROCESSING_LATENCY_NANOS
}
