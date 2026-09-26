package com.trackspeed.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAwareClockSyncServiceTest {
    @Test
    fun attemptKeyIsDeterministicAndIdentityScoped() {
        val first = WifiAwareClockSyncService.deriveAttemptKey(
            sessionId = "SESSION-A",
            requesterId = "REQUESTER",
            referenceId = "REFERENCE"
        )
        val replay = WifiAwareClockSyncService.deriveAttemptKey(
            sessionId = "session-a",
            requesterId = "requester",
            referenceId = "reference"
        )
        val anotherSession = WifiAwareClockSyncService.deriveAttemptKey(
            sessionId = "SESSION-B",
            requesterId = "REQUESTER",
            referenceId = "REFERENCE"
        )

        assertEquals(first, replay)
        assertNotEquals(first, anotherSession)
        assertEquals(24, first.attemptId.length)
        assertEquals(32, first.passphrase.length)
        assertTrue(first.attemptId.all(Char::isLetterOrDigit))
        assertTrue(first.passphrase.all(Char::isLetterOrDigit))
    }

    @Test
    fun requesterAndReferenceOrderCannotBeSwappedSilently() {
        val expected = WifiAwareClockSyncService.deriveAttemptKey(
            sessionId = "SESSION",
            requesterId = "REQUESTER",
            referenceId = "REFERENCE"
        )
        val swapped = WifiAwareClockSyncService.deriveAttemptKey(
            sessionId = "SESSION",
            requesterId = "REFERENCE",
            referenceId = "REQUESTER"
        )

        assertNotEquals(expected, swapped)
    }
}
