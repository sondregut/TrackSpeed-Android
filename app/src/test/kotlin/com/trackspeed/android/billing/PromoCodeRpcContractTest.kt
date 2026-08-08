package com.trackspeed.android.billing

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PromoCodeRpcContractTest {

    @Test
    fun `redeemed free code grants permanent access when expiration is absent`() {
        val result = resolvePromoRedemption(successRow(codeType = "free"))

        assertEquals(PromoCodeType.FREE, result.type)
        assertNull(result.proExpiresAt)
    }

    @Test
    fun `redeemed free code preserves server expiration`() {
        val expiration = "2026-08-20T00:00:00Z"

        val result = resolvePromoRedemption(
            successRow(codeType = "free", proExpiresAt = expiration)
        )

        assertEquals(Instant.parse(expiration), result.proExpiresAt)
    }

    @Test
    fun `active repeat redemption remains successful`() {
        val result = resolvePromoRedemption(
            successRow(status = "already_redeemed_active", codeType = "free")
        )

        assertEquals(PromoCodeType.FREE, result.type)
    }

    @Test
    fun `trial code never grants direct promo Pro access`() {
        val result = resolvePromoRedemption(
            successRow(
                codeType = "trial",
                proExpiresAt = "2000-01-01T00:00:00Z",
                influencerId = "influencer-id"
            )
        )

        assertEquals(PromoCodeType.TRIAL, result.type)
        assertNull(result.proExpiresAt)
        assertEquals("influencer-id", result.influencerId)
    }

    @Test
    fun `discount code unlocks paywall without granting direct Pro access`() {
        val result = resolvePromoRedemption(
            successRow(
                codeType = "discount",
                proExpiresAt = "2026-08-20T00:00:00Z"
            )
        )

        assertEquals(PromoCodeType.DISCOUNT, result.type)
        assertNull(result.proExpiresAt)
    }

    @Test
    fun `server rejection statuses map to app errors`() {
        assertThrows(PromoCodeError.InvalidCode::class.java) {
            resolvePromoRedemption(successRow(status = "invalid_code"))
        }
        assertThrows(PromoCodeError.Expired::class.java) {
            resolvePromoRedemption(successRow(status = "expired"))
        }
        assertThrows(PromoCodeError.MaxUsesReached::class.java) {
            resolvePromoRedemption(successRow(status = "max_uses_reached"))
        }
        assertThrows(PromoCodeError.AlreadyRedeemed::class.java) {
            resolvePromoRedemption(successRow(status = "already_redeemed"))
        }
    }

    @Test
    fun `malformed success response is rejected`() {
        assertThrows(PromoCodeError.InvalidCode::class.java) {
            resolvePromoRedemption(successRow(codeId = null))
        }
        assertThrows(PromoCodeError.InvalidCode::class.java) {
            resolvePromoRedemption(successRow(codeType = "unknown"))
        }
    }

    private fun successRow(
        status: String = "redeemed",
        codeId: String? = "code-id",
        codeType: String = "free",
        proExpiresAt: String? = null,
        influencerId: String? = null
    ) = PromoCodeRedemptionRpcDto(
        id = "redemption-id",
        codeId = codeId,
        deviceId = "device-id",
        proExpiresAt = proExpiresAt,
        codeType = codeType,
        influencerId = influencerId,
        redemptionStatus = status
    )
}
