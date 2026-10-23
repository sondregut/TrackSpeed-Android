package com.trackspeed.android.sync

import org.junit.Assert.*
import org.junit.Test

class BlePeerIdentityBindingsTest {
    @Test fun announcementRetainsOneStableIdentityForAnEncryptedRoute() {
        val b = BlePeerIdentityBindings()
        assertTrue(b.bind("route-a", "stable-a", "host"))
        assertTrue(b.bind("route-a", "STABLE-A", "host"))
        assertEquals("stable-a", b.stableId("route-a"))
        assertFalse(b.bind("route-a", "other", "host"))
        assertFalse(b.bind("route-b", "stable-a", "host"))
        assertTrue(b.bind("route-b", "stable-b", "host"))
    }
    @Test fun localIdentityAndEmptyClaimsCannotBeBound() {
        val b = BlePeerIdentityBindings()
        assertFalse(b.bind("route", "HOST", "host"))
        assertFalse(b.bind("route", "", "host"))
        assertFalse(b.bind("", "peer", "host"))
        assertNull(b.stableId("route"))
    }
    @Test fun disconnectedRouteCanRejoinWithoutStealingAnotherLivePeer() {
        val b = BlePeerIdentityBindings()
        assertTrue(b.bind("old-route", "stable-a", "host"))
        assertFalse(b.bind("new-route", "stable-a", "host"))
        b.remove("old-route")
        assertNull(b.stableId("old-route"))
        assertTrue(b.bind("new-route", "stable-a", "host"))
        b.clear()
        assertNull(b.stableId("new-route"))
    }
}
