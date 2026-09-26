package com.trackspeed.android.cloud

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates expensive background uploads with live timing.
 *
 * Mirrors iOS TimingWorkloadCoordinator: upload queues should not process while
 * the camera/detector is actively timing a run. The reference count supports
 * overlapping timing surfaces during lifecycle transitions.
 */
@Singleton
class TimingWorkloadCoordinator @Inject constructor() {
    private val lock = Any()
    private var liveTimingSessionCount = 0

    val isLiveTimingActive: Boolean
        get() = synchronized(lock) { liveTimingSessionCount > 0 }

    fun beginLiveTiming() {
        synchronized(lock) {
            liveTimingSessionCount += 1
            if (liveTimingSessionCount == 1) {
                Log.d(TAG, "Live timing workload gate enabled")
            }
        }
    }

    fun endLiveTiming() {
        synchronized(lock) {
            if (liveTimingSessionCount > 0) {
                liveTimingSessionCount -= 1
            }
            if (liveTimingSessionCount == 0) {
                Log.d(TAG, "Live timing workload gate disabled")
            }
        }
    }

    fun resetForTesting() {
        synchronized(lock) {
            liveTimingSessionCount = 0
        }
    }

    companion object {
        private const val TAG = "TimingWorkloadCoordinator"
    }
}
