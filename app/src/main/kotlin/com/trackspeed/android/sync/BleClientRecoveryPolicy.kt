package com.trackspeed.android.sync

/** One public GATT reconnect per explicit transport start, with stale-work rejection. */
internal class BleClientRecoveryPolicy(private val maximumAttempts: Int = 1) {
    enum class Decision { IGNORE, RECONNECT, FAIL_CLOSED }

    private var generation = 0L
    private var attempts = 0
    private var protocolReady = false
    private var recovering = false
    private var failed = false

    @Synchronized fun generation(): Long = generation
    @Synchronized fun isRecovering(): Boolean = recovering

    /** Only an explicit new start gets another recovery budget. */
    @Synchronized fun reset() {
        generation++
        attempts = 0
        protocolReady = false
        recovering = false
        failed = false
    }

    @Synchronized fun invalidateConnection() {
        generation++
        protocolReady = false
    }

    @Synchronized fun recover(expectedGeneration: Long): Decision {
        if (expectedGeneration != generation || failed) return Decision.IGNORE
        invalidateConnection()
        if (attempts >= maximumAttempts) {
            failed = true
            recovering = false
            return Decision.FAIL_CLOSED
        }
        attempts++
        recovering = true
        return Decision.RECONNECT
    }

    @Synchronized fun fail() {
        invalidateConnection()
        recovering = false
        failed = true
    }

    /** A fresh CCC success is the only way to admit the replacement connection. */
    @Synchronized fun notificationsReady(expectedGeneration: Long): Boolean {
        if (expectedGeneration != generation || failed || protocolReady) return false
        protocolReady = true
        recovering = false
        return true
    }

    @Synchronized fun isProtocolReady(expectedGeneration: Long): Boolean =
        expectedGeneration == generation && protocolReady && !failed

    /** Serialize publication with invalidation so an old result cannot restore readiness. */
    @Synchronized fun runIfCurrent(expectedGeneration: Long, action: () -> Unit): Boolean {
        if (expectedGeneration != generation || failed) return false
        action()
        return true
    }
}
