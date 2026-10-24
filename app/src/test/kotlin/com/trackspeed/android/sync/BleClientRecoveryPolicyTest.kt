package com.trackspeed.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleClientRecoveryPolicyTest {
    @Test fun `invalid handle requires fresh CCC before admitting another handshake`() {
        val policy = BleClientRecoveryPolicy()
        val oldConnection = policy.generation()
        assertTrue(policy.notificationsReady(oldConnection))
        assertTrue(policy.isProtocolReady(oldConnection))

        assertEquals(BleClientRecoveryPolicy.Decision.RECONNECT, policy.recover(oldConnection))
        val replacement = policy.generation()
        assertTrue(policy.isRecovering())
        assertFalse(policy.isProtocolReady(oldConnection))
        assertFalse(policy.isProtocolReady(replacement))
        assertFalse(policy.notificationsReady(oldConnection))
        assertTrue(policy.notificationsReady(replacement))
        assertTrue(policy.isProtocolReady(replacement))
        assertFalse(policy.isRecovering())
        assertFalse(policy.notificationsReady(replacement))
    }

    @Test fun `duplicate invalidations from the old link do not consume another attempt`() {
        val policy = BleClientRecoveryPolicy()
        val oldConnection = policy.generation()
        assertEquals(BleClientRecoveryPolicy.Decision.RECONNECT, policy.recover(oldConnection))
        val replacement = policy.generation()
        assertEquals(BleClientRecoveryPolicy.Decision.IGNORE, policy.recover(oldConnection))
        assertEquals(replacement, policy.generation())
        assertTrue(policy.notificationsReady(replacement))
        assertEquals(BleClientRecoveryPolicy.Decision.IGNORE, policy.recover(oldConnection))
        assertTrue(policy.isProtocolReady(replacement))
    }

    @Test fun `successful CCC does not replenish the bounded recovery budget`() {
        val policy = BleClientRecoveryPolicy()
        assertEquals(BleClientRecoveryPolicy.Decision.RECONNECT, policy.recover(policy.generation()))
        val replacement = policy.generation()
        assertTrue(policy.notificationsReady(replacement))
        assertEquals(BleClientRecoveryPolicy.Decision.FAIL_CLOSED, policy.recover(replacement))
        assertFalse(policy.isProtocolReady(policy.generation()))
        assertFalse(policy.notificationsReady(policy.generation()))
        assertEquals(BleClientRecoveryPolicy.Decision.IGNORE, policy.recover(policy.generation()))

        policy.reset()
        assertEquals(BleClientRecoveryPolicy.Decision.RECONNECT, policy.recover(policy.generation()))
    }

    @Test fun `failed rediscovery cannot be rescued by a late CCC callback`() {
        val policy = BleClientRecoveryPolicy()
        policy.recover(policy.generation())
        val replacement = policy.generation()
        policy.fail()
        assertFalse(policy.notificationsReady(replacement))
        assertFalse(policy.notificationsReady(policy.generation()))
        assertFalse(policy.isProtocolReady(policy.generation()))
        assertFalse(policy.isRecovering())
    }

    @Test fun `old sync results and progress cannot publish after invalidation`() {
        val policy = BleClientRecoveryPolicy()
        val oldMeasurement = policy.generation()
        var state = "syncing"
        assertTrue(policy.runIfCurrent(oldMeasurement) { state = "old progress" })
        policy.recover(oldMeasurement)
        state = "connecting"
        assertFalse(policy.runIfCurrent(oldMeasurement) { state = "old synced result" })
        assertEquals("connecting", state)

        val replacement = policy.generation()
        policy.notificationsReady(replacement)
        assertFalse(policy.runIfCurrent(oldMeasurement) { state = "old failure" })
        assertTrue(policy.runIfCurrent(replacement) { state = "new handshake" })
        assertEquals("new handshake", state)
    }

    @Test fun `disconnect and explicit restart also reject prior clock work`() {
        val policy = BleClientRecoveryPolicy()
        val connected = policy.generation()
        policy.notificationsReady(connected)
        policy.invalidateConnection()
        assertFalse(policy.isProtocolReady(connected))
        assertFalse(policy.runIfCurrent(connected) { error("stale work ran") })
        val disconnected = policy.generation()
        policy.reset()
        assertFalse(policy.runIfCurrent(disconnected) { error("previous session ran") })
        assertTrue(policy.runIfCurrent(policy.generation()) {})
    }
}
