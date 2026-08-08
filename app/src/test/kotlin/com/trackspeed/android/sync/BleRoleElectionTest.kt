package com.trackspeed.android.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleRoleElectionTest {
    @Test
    fun `opposite peers always elect complementary roles`() {
        val first = BleRoleElection.tokenForDeviceId("first-phone")
        val second = BleRoleElection.tokenForDeviceId("second-phone")

        val firstBecomesClient = BleRoleElection.shouldBecomeClient(first, second)
        val secondBecomesClient = BleRoleElection.shouldBecomeClient(second, first)

        assertTrue(firstBecomesClient != secondBecomesClient)
    }

    @Test
    fun `unsigned lexicographic ordering handles high bytes`() {
        val lower = ByteArray(BleRoleElection.TOKEN_BYTES).also { it[0] = 0x7f }
        val higher = ByteArray(BleRoleElection.TOKEN_BYTES).also { it[0] = 0x80.toByte() }
        assertFalse(BleRoleElection.shouldBecomeClient(lower, higher)!!)
        assertTrue(BleRoleElection.shouldBecomeClient(higher, lower)!!)
    }

    @Test
    fun `identical or malformed tokens do not force either role`() {
        val token = BleRoleElection.tokenForDeviceId("same")
        assertNull(BleRoleElection.shouldBecomeClient(token, token))
        assertNull(BleRoleElection.shouldBecomeClient(byteArrayOf(), token))
    }
}
