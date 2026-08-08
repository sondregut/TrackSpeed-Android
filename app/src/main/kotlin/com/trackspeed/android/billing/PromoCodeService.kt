package com.trackspeed.android.billing

import android.util.Log
import com.trackspeed.android.cloud.DeviceIdProvider
import com.trackspeed.android.cloud.safeCloudErrorCode
import com.trackspeed.android.data.repository.SettingsRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// ---- DTOs matching Supabase tables ----

@Serializable
data class PromoRedemptionDto(
    val id: String? = null,
    @SerialName("code_id") val codeId: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("pro_expires_at") val proExpiresAt: String? = null,
    @SerialName("redeemed_at") val redeemedAt: String? = null,
    @SerialName("attribution_source") val attributionSource: String? = null
)

@Serializable
private data class DiscountPaywallAccessDto(
    val unlocked: Boolean = false
)

@Serializable
data class InfluencerReferralInsertDto(
    @SerialName("influencer_id") val influencerId: String,
    @SerialName("app_user_id") val appUserId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("trial_expires_at") val trialExpiresAt: String
)

@Serializable
internal data class PromoCodeRedemptionRpcDto(
    val id: String? = null,
    @SerialName("code_id") val codeId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("pro_expires_at") val proExpiresAt: String? = null,
    @SerialName("redeemed_at") val redeemedAt: String? = null,
    @SerialName("code_type") val codeType: String? = null,
    @SerialName("influencer_id") val influencerId: String? = null,
    @SerialName("redemption_status") val redemptionStatus: String
)

@Serializable
data class ReferralCodeDto(
    val code: String
)

@Serializable
data class ReferralStatsDto(
    @SerialName("total_referrals") val totalReferrals: Int = 0,
    @SerialName("pending_referrals") val pendingReferrals: Int = 0,
    @SerialName("subscribed_referrals") val subscribedReferrals: Int = 0,
    @SerialName("rewarded_referrals") val rewardedReferrals: Int = 0,
    // Backward-compatible with older RPC responses from the Android prototype.
    @SerialName("successful_referrals") val successfulReferrals: Int = 0,
    @SerialName("free_months_earned") val freeMonthsEarned: Int = 0,
    @SerialName("bonus_pass_days_remaining") val bonusPassDaysRemaining: Int = 0
) {
    val friendsJoinedCount: Int
        get() = when {
            totalReferrals > 0 -> totalReferrals
            successfulReferrals > 0 -> successfulReferrals
            subscribedReferrals > 0 -> subscribedReferrals
            rewardedReferrals > 0 -> rewardedReferrals
            else -> 0
        }
}

@Serializable
data class UserReferralInsertDto(
    @SerialName("referrer_code") val referrerCode: String,
    @SerialName("referred_device_id") val referredDeviceId: String,
    @SerialName("referred_user_id") val referredUserId: String? = null,
    val status: String = "pending"
)

// ---- Error types ----

sealed class PromoCodeError(message: String) : Exception(message) {
    data object InvalidCode : PromoCodeError("Invalid or inactive promo code")
    data object Expired : PromoCodeError("This promo code has expired")
    data object MaxUsesReached : PromoCodeError("This promo code has reached its maximum uses")
    data object AlreadyRedeemed : PromoCodeError("You've already redeemed this code")
    data object RateLimited : PromoCodeError("Please wait before trying again")
    data class NetworkError(override val cause: Throwable) : PromoCodeError("Network error: ${cause.safeCloudErrorCode()}")
}

// ---- Result type ----

enum class PromoCodeType(val wireValue: String) {
    FREE("free"),
    TRIAL("trial"),
    DISCOUNT("discount");

    companion object {
        fun fromWireValue(value: String?): PromoCodeType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class PromoRedemptionResult(
    val type: PromoCodeType,
    val proExpiresAt: Instant?, // null = forever
    val influencerId: String? = null
)

internal fun resolvePromoRedemption(row: PromoCodeRedemptionRpcDto): PromoRedemptionResult {
    when (row.redemptionStatus) {
        "invalid_code" -> throw PromoCodeError.InvalidCode
        "expired" -> throw PromoCodeError.Expired
        "max_uses_reached" -> throw PromoCodeError.MaxUsesReached
        "already_redeemed" -> throw PromoCodeError.AlreadyRedeemed
        "redeemed", "already_redeemed_active" -> Unit
        else -> throw PromoCodeError.InvalidCode
    }

    // Match the iOS decoder's guard: a success status is not accepted unless
    // it also contains a complete redemption identity and known code type.
    if (row.id.isNullOrBlank() || row.codeId.isNullOrBlank() || row.deviceId.isNullOrBlank()) {
        throw PromoCodeError.InvalidCode
    }
    val type = PromoCodeType.fromWireValue(row.codeType)
        ?: throw PromoCodeError.InvalidCode
    val expiration = row.proExpiresAt?.let(Instant::parse)

    return PromoRedemptionResult(
        type = type,
        proExpiresAt = expiration.takeIf { type == PromoCodeType.FREE },
        influencerId = row.influencerId
    )
}

// ---- Service ----

@Singleton
class PromoCodeService @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val deviceIdProvider: DeviceIdProvider,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "PromoCodeService"
        private const val RATE_LIMIT_MS = 10_000L
    }

    private var lastRedemptionAttempt = 0L
    private val redemptionMutex = Mutex()

    /**
     * Redeem a promo code. Matches iOS logic exactly.
     *
     * @param code The promo code string
     * @param source Where the code was entered (e.g. "onboarding_promo", "paywall", "onboarding_attribution")
     * @return PromoRedemptionResult on success
     * @throws PromoCodeError on failure
     */
    suspend fun redeemPromoCode(code: String, source: String): PromoRedemptionResult =
        withContext(Dispatchers.IO) {
            redemptionMutex.withLock {
                val now = System.currentTimeMillis()
                if (now - lastRedemptionAttempt < RATE_LIMIT_MS) {
                    throw PromoCodeError.RateLimited
                }
                lastRedemptionAttempt = now

                val normalizedCode = code.trim().uppercase(Locale.US)
                val deviceId = getDeviceId()

                try {
                    val userName = settingsRepository.userName.first().trim().ifBlank { null }
                    val userEmail = supabaseClient.auth.currentUserOrNull()?.email
                    val rows = supabaseClient.postgrest.rpc(
                        "redeem_promo_code",
                        buildJsonObject {
                            put("p_promo_code", normalizedCode)
                            put("p_device_id", deviceId)
                            put("p_user_name", userName)
                            put("p_user_email", userEmail)
                            put("p_attribution_source", source)
                        }
                    ).decodeList<PromoCodeRedemptionRpcDto>()

                    val row = rows.firstOrNull() ?: throw PromoCodeError.InvalidCode
                    val result = resolvePromoRedemption(row)

                    if (row.redemptionStatus == "redeemed" && row.influencerId != null) {
                        createInfluencerReferral(row.influencerId, deviceId)
                    }

                    Log.i(
                        TAG,
                        "Promo code handled: status=${row.redemptionStatus}, type=${result.type.wireValue}"
                    )
                    result
                } catch (e: PromoCodeError) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to redeem promo code: ${e.safeCloudErrorCode()}")
                    throw PromoCodeError.NetworkError(e)
                }
            }
        }

    private suspend fun createInfluencerReferral(influencerId: String, deviceId: String) {
        try {
            supabaseClient.postgrest["influencer_referrals"]
                .insert(
                    InfluencerReferralInsertDto(
                        influencerId = influencerId,
                        appUserId = deviceId,
                        deviceId = deviceId,
                        trialExpiresAt = Instant.now().plusSeconds(30L * 86_400L).toString()
                    )
                )

            supabaseClient.postgrest.rpc(
                "increment_influencer_signups",
                buildJsonObject { put("influencer_uuid", influencerId) }
            )

            if (com.revenuecat.purchases.Purchases.isConfigured) {
                com.revenuecat.purchases.Purchases.sharedInstance.setAttributes(
                    mapOf("\$influencerId" to influencerId)
                )
            }
        } catch (e: Exception) {
            // Redemption is already committed atomically by the server. Match
            // iOS by treating influencer attribution as a non-fatal side effect.
            Log.w(TAG, "Influencer referral side effect failed: ${e.safeCloudErrorCode()}")
        }
    }

    /**
     * Check if this device has active promo access (non-expired redemption).
     */
    suspend fun getActivePromoAccess(): PromoRedemptionDto? = withContext(Dispatchers.IO) {
        try {
            supabaseClient.postgrest.rpc(
                "get_active_promo_access",
                buildJsonObject { put("p_device_id", getDeviceId()) }
            ).decodeList<PromoRedemptionDto>().firstOrNull()
        } catch (e: Exception) {
            if (isMissingActivePromoAccessRpc(e)) {
                // Match iOS: a deployment/schema-cache miss means no live
                // promo result; real network failures must propagate so the
                // subscription layer can retain its last verified cache.
                Log.w(TAG, "Active promo RPC unavailable: ${e.safeCloudErrorCode()}")
                null
            } else {
                throw e
            }
        }
    }

    /**
     * Restore a previously redeemed discount-paywall entitlement. A null
     * result means the compatibility RPC has not reached this backend yet;
     * callers must preserve their last verified local value in that case.
     */
    suspend fun getDiscountPaywallAccess(): Boolean? = withContext(Dispatchers.IO) {
        try {
            supabaseClient.postgrest.rpc(
                "get_discount_paywall_access",
                buildJsonObject { put("p_device_id", getDeviceId()) }
            ).decodeList<DiscountPaywallAccessDto>().firstOrNull()?.unlocked ?: false
        } catch (e: Exception) {
            if (isMissingDiscountPaywallAccessRpc(e)) {
                Log.w(TAG, "Discount paywall RPC unavailable: ${e.safeCloudErrorCode()}")
                null
            } else {
                throw e
            }
        }
    }

    private fun isMissingActivePromoAccessRpc(error: Throwable): Boolean {
        val details = "${error::class.java.name} ${error.message.orEmpty()}".lowercase(Locale.US)
        return details.contains("get_active_promo_access") &&
            (
                details.contains("schema cache") ||
                    details.contains("could not find") ||
                    details.contains("pgrst202")
                )
    }

    private fun isMissingDiscountPaywallAccessRpc(error: Throwable): Boolean {
        val details = "${error::class.java.name} ${error.message.orEmpty()}".lowercase(Locale.US)
        return details.contains("get_discount_paywall_access") &&
            (
                details.contains("schema cache") ||
                    details.contains("could not find") ||
                    details.contains("pgrst202")
                )
    }

    /**
     * Get or create a referral code from Supabase using the RPC function.
     */
    suspend fun getOrCreateReferralCodeFromSupabase(): String? = withContext(Dispatchers.IO) {
        try {
            val deviceId = getDeviceId()
            val userId = supabaseClient.auth.currentUserOrNull()?.id.orEmpty()
            val result = supabaseClient.postgrest.rpc(
                "get_or_create_referral_code",
                buildJsonObject {
                    put("p_user_id", userId)
                    put("p_device_id", deviceId)
                }
            ).decodeAs<String>()
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get/create referral code from Supabase: ${e.safeCloudErrorCode()}")
            null
        }
    }

    /**
     * Track a referral signup (when a referred user enters a referrer's code).
     */
    suspend fun trackReferralSignup(referrerCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val deviceId = getDeviceId()
            val userId = supabaseClient.auth.currentUserOrNull()?.id
            supabaseClient.postgrest["user_referrals"]
                .insert(
                    UserReferralInsertDto(
                        referrerCode = referrerCode.trim().uppercase(Locale.US),
                        referredDeviceId = deviceId,
                        referredUserId = userId
                    )
                )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to track referral signup: ${e.safeCloudErrorCode()}")
            false
        }
    }

    suspend fun validateReferralCode(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalized = code.trim().uppercase(Locale.US)
            if (normalized.isEmpty()) return@withContext false
            val matches = supabaseClient.postgrest["user_referral_codes"]
                .select {
                    filter {
                        eq("code", normalized)
                    }
                    limit(1)
                }
                .decodeList<ReferralCodeDto>()
            matches.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to validate referral code: ${e.safeCloudErrorCode()}")
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
                buildJsonObject { put("p_referral_code", code) }
            ).decodeList<ReferralStatsDto>()
            result.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get referral stats: ${e.safeCloudErrorCode()}")
            null
        }
    }

    fun getDeviceId(): String {
        return deviceIdProvider.deviceId
    }

}
