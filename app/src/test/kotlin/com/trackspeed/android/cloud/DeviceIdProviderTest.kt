package com.trackspeed.android.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdProviderTest {
    @Test
    fun `new install persists generated identity`() {
        val result = DeviceIdProvider.resolveDeviceIdentity(null, null, "phone-a", "new-id")

        assertEquals("new-id", result.deviceId)
        assertTrue(result.mustPersist)
        assertFalse(result.rotatedAfterRestore)
    }

    @Test
    fun `legacy install keeps identity and adopts fingerprint`() {
        val result = DeviceIdProvider.resolveDeviceIdentity("existing", null, "phone-a", "new-id")

        assertEquals("existing", result.deviceId)
        assertTrue(result.mustPersist)
        assertFalse(result.rotatedAfterRestore)
    }

    @Test
    fun `same physical phone keeps identity without writing`() {
        val result = DeviceIdProvider.resolveDeviceIdentity("existing", "phone-a", "phone-a", "new-id")

        assertEquals("existing", result.deviceId)
        assertFalse(result.mustPersist)
    }

    @Test
    fun `cross device restore rotates duplicated identity`() {
        val result = DeviceIdProvider.resolveDeviceIdentity("restored", "phone-a", "phone-b", "new-id")

        assertEquals("new-id", result.deviceId)
        assertTrue(result.mustPersist)
        assertTrue(result.rotatedAfterRestore)
    }

    @Test
    fun `device fingerprint is deterministic and device specific`() {
        assertEquals(DeviceIdProvider.deviceFingerprint("a"), DeviceIdProvider.deviceFingerprint("a"))
        assertNotEquals(DeviceIdProvider.deviceFingerprint("a"), DeviceIdProvider.deviceFingerprint("b"))
        assertEquals(64, DeviceIdProvider.deviceFingerprint("a").length)
    }
}
