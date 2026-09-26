package com.trackspeed.android.sync

/** A late Android callback must close its resource after its attempt expires. */
internal class ConnectionAttemptResources : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val cleanup = mutableListOf<() -> Unit>()

    fun track(close: () -> Unit): Boolean = synchronized(lock) {
        if (closed) {
            runCatching(close)
            false
        } else {
            cleanup += close
            true
        }
    }

    val isOpen: Boolean get() = synchronized(lock) { !closed }

    override fun close() {
        val actions = synchronized(lock) {
            if (closed) return
            closed = true
            cleanup.toList().also { cleanup.clear() }
        }
        actions.asReversed().forEach { runCatching(it) }
    }
}
