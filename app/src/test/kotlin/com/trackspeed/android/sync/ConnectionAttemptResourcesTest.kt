package com.trackspeed.android.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ConnectionAttemptResourcesTest {
    @Test fun timeoutClosesExistingAndLateResourcesExactlyOnce() {
        val resources = ConnectionAttemptResources()
        val closed = AtomicInteger()
        assertTrue(resources.track { closed.incrementAndGet() })
        resources.close()
        assertFalse(resources.track { closed.incrementAndGet() })
        resources.close()
        assertEquals(2, closed.get())
    }

    @Test fun cleanupContinuesAfterOneResourceThrows() {
        val resources = ConnectionAttemptResources()
        var closed = false
        resources.track { closed = true }
        resources.track { error("already closed by Android") }
        resources.close()
        assertTrue(closed)
    }

    @Test fun timeoutRacingCallbacksDoesNotLeakResources() {
        repeat(100) {
            val resources = ConnectionAttemptResources()
            val barrier = CountDownLatch(1)
            val closed = AtomicInteger()
            val callback = Thread { barrier.await(); repeat(20) { resources.track { closed.incrementAndGet() } } }
            callback.start()
            barrier.countDown()
            resources.close()
            callback.join()
            assertEquals(20, closed.get())
        }
    }
}
