package com.trackspeed.android.cloud

import kotlinx.coroutines.sync.Mutex

/** Explicit saves wait for an active writer; routine writes keep their queues.
 * Revalidate the local ticket, workload, and captured account after lock wait.
 */
internal suspend fun Mutex.withProfileWriteLock(
    waitForTurn: Boolean,
    expectedUserId: String,
    currentUserId: () -> String?,
    canWrite: suspend () -> Boolean = { true },
    write: suspend () -> Boolean
): Boolean {
    if (expectedUserId.isBlank()) return false
    if (waitForTurn) lock() else if (!tryLock()) return false
    return try {
        if (currentUserId() != expectedUserId || !canWrite() || currentUserId() != expectedUserId) false
        else write()
    } finally {
        unlock()
    }
}
