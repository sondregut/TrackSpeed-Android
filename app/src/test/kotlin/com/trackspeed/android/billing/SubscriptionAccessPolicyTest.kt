package com.trackspeed.android.billing

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionAccessPolicyTest {

    @Test
    fun `unexpired cancelled and billing issue subscriptions retain access`() {
        val now = Instant.parse("2026-08-03T00:00:00Z")
        val expiration = "2026-08-04T00:00:00Z"

        assertTrue(isSupabaseSubscriptionActive("active", expiration, now))
        assertTrue(isSupabaseSubscriptionActive("cancelled", expiration, now))
        assertTrue(isSupabaseSubscriptionActive("billing_issue", expiration, now))
    }

    @Test
    fun `expired or inactive subscriptions do not retain access`() {
        val now = Instant.parse("2026-08-03T00:00:00Z")

        assertFalse(isSupabaseSubscriptionActive("cancelled", "2026-08-02T00:00:00Z", now))
        assertFalse(isSupabaseSubscriptionActive("expired", null, now))
        assertFalse(isSupabaseSubscriptionActive("active", "not-a-date", now))
    }

    @Test
    fun `verified Pro cache is retained while offline and live sources are unavailable`() {
        assertTrue(
            shouldPreserveOfflineProCache(
                cachedProActive = true,
                livePro = false,
                hasInternetConnectivity = false
            )
        )
    }

    @Test
    fun `cache cannot override a live online downgrade`() {
        assertFalse(
            shouldPreserveOfflineProCache(
                cachedProActive = true,
                livePro = false,
                hasInternetConnectivity = true
            )
        )
    }

    @Test
    fun `offline cache is irrelevant when live Pro is active`() {
        assertFalse(
            shouldPreserveOfflineProCache(
                cachedProActive = true,
                livePro = true,
                hasInternetConnectivity = false
            )
        )
    }

    @Test
    fun `offline mode never invents access without a verified cache`() {
        assertFalse(
            shouldPreserveOfflineProCache(
                cachedProActive = false,
                livePro = false,
                hasInternetConnectivity = false
            )
        )
    }
}
