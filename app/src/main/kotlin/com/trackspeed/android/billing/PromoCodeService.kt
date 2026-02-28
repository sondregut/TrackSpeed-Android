package com.trackspeed.android.billing

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

// ---- DTOs matching Supabase tables ----

@Serializable
data class PromoCodeDto(
    val id: String? = null,
    val code: String,
    val type: String, // "free" or "trial"
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("max_uses") val maxUses: Int? = null,
    @SerialName("current_uses") val currentUses: Int = 0,
    @SerialName("duration_days") val durationDays: Int? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("influencer_id") val influencerId: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class PromoRedemptionInsertDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("promo_code_id") val promoCodeId: String,
    @SerialName("code") val code: String,
    @SerialName("type") val type: String,
    val status: String = "active",
    @SerialName("pro_expires_at") val proExpiresAt: String? = null,
    val source: String? = null
)

@Serializable
data class PromoRedemptionDto(
    val id: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("promo_code_id") val promoCodeId: String? = null,
    val code: String? = null,
    val type: String? = null,
    val status: String,
    @SerialName("pro_expires_at") val proExpiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class InfluencerReferralInsertDto(
    @SerialName("influencer_id") val influencerId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("promo_code") val promoCode: String,
    val platform: String = "android"
)

@Serializable
data class ReferralCodeDto(
    val code: String
)

@Serializable
data class ReferralStatsDto(
    @SerialName("total_referrals") val totalReferrals: Int = 0,
    @SerialName("successful_referrals") val successfulReferrals: Int = 0,
    @SerialName("free_months_earned") val freeMonthsEarned: Int = 0
)

@Serializable
data class UserReferralInsertDto(
    @SerialName("referrer_code") val referrerCode: String,
    @SerialName("referred_device_id") val referredDeviceId: String,
    val platform: String = "android"
)

// ---- Error types ----

sealed class PromoCodeError(message: String) : Exception(message) {
    data object InvalidCode : PromoCodeError("Invalid or inactive promo code")
    data object Expired : PromoCodeError("This promo code has expired")
    data object MaxUsesReached : PromoCodeError("This promo code has reached its maximum uses")
    data object AlreadyRedeemed : PromoCodeError("You've already redeemed this code")
    data object RateLimited : PromoCodeError("Please wait before trying again")
    data class NetworkError(override val cause: Throwable) : PromoCodeError("Network error: ${cause.message}")
}

// ---- Result type ----

data class PromoRedemptionResult(
    val type: String, // "free" or "trial"
    val proExpiresAt: Instant?, // null = forever
    val influencerId: String? = null
)

// ---- Service ----

@Singleton
class PromoCodeService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabaseClient: SupabaseClient
) {
    companion object {
        private const val TAG = "PromoCodeService"
        private const val RATE_LIMIT_MS = 10_000L
        private const val DISCORD_NOTIFY_URL = "https://mytrackspeed.com/api/admin/redemptions/notify"
    }

    private var lastRedemptionAttempt = 0L

    /**
     * Redeem a promo code. Matches iOS logic exactly.
     *
     * @param code The promo code string
     * @param source Where the code was entered (e.g. "onboarding_promo", "paywall", "onboarding_attribution")
     * @return PromoRedemptionResult on success
     * @throws PromoCodeError on failure
     */
    suspend fun redeemPromoCode(code: String, source: String): PromoRedemptionResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // 1. Rate limit (10s cooldown)
        if (now - lastRedemptionAttempt < RATE_LIMIT_MS) {
            throw PromoCodeError.RateLimited
        }
        lastRedemptionAttempt = now

        val deviceId = getDeviceId()

        try {
            // 2. Fetch promo code from Supabase
            val promoCodes = supabaseClient.postgrest["promo_codes"]
                .select {
                    filter {
                        eq("code", code.uppercase().trim())
                        eq("is_active", true)
                    }
                }
                .decodeList<PromoCodeDto>()

            val promoCode = promoCodes.firstOrNull()
                ?: throw PromoCodeError.InvalidCode

            // 3. Check expiry
            promoCode.expiresAt?.let { expiresAt ->
                val expiry = Instant.parse(expiresAt)
                if (Instant.now().isAfter(expiry)) {
                    throw PromoCodeError.Expired
                }
            }

            // 4. Check max uses
            promoCode.maxUses?.let { maxUses ->
                if (promoCode.currentUses >= maxUses) {
                    throw PromoCodeError.MaxUsesReached
                }
            }

            // 5. Check existing redemption for this device
            val existingRedemptions = supabaseClient.postgrest["promo_redemptions"]
                .select {
                    filter {
                        eq("device_id", deviceId)
                        eq("promo_code_id", promoCode.id!!)
                    }
                }
                .decodeList<PromoRedemptionDto>()

            if (existingRedemptions.isNotEmpty()) {
                throw PromoCodeError.AlreadyRedeemed
            }

            // 6. Compute pro_expires_at based on type
            val proExpiresAt: Instant? = when (promoCode.type) {
                "free" -> {
                    promoCode.durationDays?.let { days ->
                        Instant.now().plusSeconds(days.toLong() * 86400)
                    } // null = forever
                }
                "trial" -> {
                    // For trial/influencer codes, set to distant past so RevenueCat handles the actual trial
                    Instant.parse("2000-01-01T00:00:00Z")
                }
                else -> null
            }

            val proExpiresAtStr = proExpiresAt?.let {
                DateTimeFormatter.ISO_INSTANT.format(it)
            }

            // 7. Insert promo_redemptions row
            supabaseClient.postgrest["promo_redemptions"]
                .insert(
                    PromoRedemptionInsertDto(
                        deviceId = deviceId,
                        promoCodeId = promoCode.id!!,
                        code = promoCode.code,
                        type = promoCode.type,
                        status = "active",
                        proExpiresAt = proExpiresAtStr,
                        source = source
                    )
                )

            // 8. Increment current_uses via RPC
            try {
                supabaseClient.postgrest.rpc(
                    "increment_promo_uses",
                    buildJsonObject { put("code_id", promoCode.id) }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to increment promo uses: ${e.message}")
            }

            // 9. If influencer code: insert influencer_referrals + set RevenueCat attribute
            if (promoCode.influencerId != null) {
                try {
                    supabaseClient.postgrest["influencer_referrals"]
                        .insert(
                            InfluencerReferralInsertDto(
                                influencerId = promoCode.influencerId,
                                deviceId = deviceId,
                                promoCode = promoCode.code
                            )
                        )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to insert influencer referral: ${e.message}")
                }

                try {
                    supabaseClient.postgrest.rpc(
                        "increment_influencer_signups",
                        buildJsonObject { put("inf_id", promoCode.influencerId) }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to increment influencer signups: ${e.message}")
                }

                // Set RevenueCat attribute
                try {
                    if (com.revenuecat.purchases.Purchases.isConfigured) {
                        com.revenuecat.purchases.Purchases.sharedInstance.setAttributes(
                            mapOf("\$influencerId" to promoCode.influencerId)
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set RevenueCat influencer attribute: ${e.message}")
                }
            }

            // 10. Fire-and-forget Discord notification
            try {
                notifyDiscord(promoCode.code, deviceId, source, promoCode.type)
            } catch (e: Exception) {
                Log.w(TAG, "Discord notification failed: ${e.message}")
            }

            Log.i(TAG, "Promo code redeemed: ${promoCode.code} (type=${promoCode.type})")

            PromoRedemptionResult(
                type = promoCode.type,
                proExpiresAt = if (promoCode.type == "free") proExpiresAt else null,
                influencerId = promoCode.influencerId
            )
        } catch (e: PromoCodeError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to redeem promo code", e)
            throw PromoCodeError.NetworkError(e)
        }
    }

    /**
     * Check if this device has active promo access (non-expired redemption).
     */
    suspend fun getActivePromoAccess(): PromoRedemptionDto? = withContext(Dispatchers.IO) {
        try {
            val deviceId = getDeviceId()
            val redemptions = supabaseClient.postgrest["promo_redemptions"]
                .select {
                    filter {
                        eq("device_id", deviceId)
                        eq("status", "active")
                    }
                }
                .decodeList<PromoRedemptionDto>()

            // Find a redemption that hasn't expired
            redemptions.firstOrNull { redemption ->
                val expiresAt = redemption.proExpiresAt
                if (expiresAt == null) {
                    true // No expiry = forever
                } else {
                    try {
                        val expiry = Instant.parse(expiresAt)
                        // Distant past (trial type) doesn't grant pro access
                        expiry.isAfter(Instant.now()) && expiry.isAfter(Instant.parse("2001-01-01T00:00:00Z"))
                    } catch (e: Exception) {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check promo access: ${e.message}")
            null
        }
    }

    /**
     * Check if this device has an influencer offer (trial type redemption).
     */
    suspend fun hasInfluencerOffer(): Boolean = withContext(Dispatchers.IO) {
        try {
            val deviceId = getDeviceId()
            val redemptions = supabaseClient.postgrest["promo_redemptions"]
                .select {
                    filter {
                        eq("device_id", deviceId)
                        eq("type", "trial")
                        eq("status", "active")
                    }
                }
                .decodeList<PromoRedemptionDto>()
            redemptions.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check influencer offer: ${e.message}")
            false
        }
    }

    /**
     * Get or create a referral code from Supabase using the RPC function.
     */
    suspend fun getOrCreateReferralCodeFromSupabase(): String? = withContext(Dispatchers.IO) {
        try {
            val deviceId = getDeviceId()
            val result = supabaseClient.postgrest.rpc(
                "get_or_create_referral_code",
                buildJsonObject { put("p_device_id", deviceId) }
            ).decodeAs<String>()
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get/create referral code from Supabase: ${e.message}")
            null
        }
    }

    /**
     * Track a referral signup (when a referred user enters a referrer's code).
     */
    suspend fun trackReferralSignup(referrerCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val deviceId = getDeviceId()
            supabaseClient.postgrest["user_referrals"]
                .insert(
                    UserReferralInsertDto(
                        referrerCode = referrerCode,
                        referredDeviceId = deviceId
                    )
                )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to track referral signup: ${e.message}")
            false
        }
    }

    /**
     * Get referral stats for a given code from Supabase.
     */
    suspend fun getReferralStats(code: String): ReferralStatsDto? = withContext(Dispatchers.IO) {
        try {
            val result = supabaseClient.postgrest.rpc(
                "get_referral_stats",
                buildJsonObject { put("p_code", code) }
            ).decodeAs<ReferralStatsDto>()
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get referral stats: ${e.message}")
            null
        }
    }

    fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("trackspeed", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: run {
            val newId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", newId).apply()
            newId
        }
    }

    private fun notifyDiscord(code: String, deviceId: String, source: String, type: String) {
        // Fire-and-forget HTTP POST to Discord webhook endpoint
        Thread {
            try {
                val url = java.net.URL(DISCORD_NOTIFY_URL)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val body = """{"code":"$code","device_id":"$deviceId","source":"$source","type":"$type","platform":"android"}"""
                conn.outputStream.write(body.toByteArray())
                conn.responseCode // trigger the request
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Discord notify failed: ${e.message}")
            }
        }.start()
    }
}
