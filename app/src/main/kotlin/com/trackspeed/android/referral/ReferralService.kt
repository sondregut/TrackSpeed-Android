package com.trackspeed.android.referral

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.trackspeed.android.billing.PromoCodeService
import com.trackspeed.android.R
import com.trackspeed.android.cloud.DeviceIdProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Referral stats — backed by Supabase with local cache fallback.
 */
data class ReferralStats(
    val friendsJoined: Int = 0,
    val freeMonthsEarned: Int = 0,
    val bonusPassDaysRemaining: Int = 0
)

/**
 * Service for managing referral codes and sharing.
 *
 * Uses Supabase RPC to get/create referral codes and track stats.
 * Falls back to local device-ID-based code generation if Supabase is unavailable.
 */
@Singleton
class ReferralService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val promoCodeService: PromoCodeService,
    private val deviceIdProvider: DeviceIdProvider
) {
    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    companion object {
        private const val TAG = "ReferralService"
        private const val REFERRAL_BASE_URL = "https://mytrackspeed.com/invite/"
        private const val CODE_LENGTH = 6
        private const val APP_STORE_URL = "https://apps.apple.com/app/trackspeed/id6757509163"
        private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.trackspeed.android"
        private const val PREFS_NAME = "trackspeed"
        private const val KEY_PENDING_REFERRAL_CODE = "pendingReferralCode"
        private const val KEY_WAS_REFERRED = "wasReferred"
        private val HOME_INVITE_CARD_COOLDOWN_MS = TimeUnit.DAYS.toMillis(30)

        fun getPendingReferralCode(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_PENDING_REFERRAL_CODE, null)
        }

        fun clearPendingReferralCode(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PENDING_REFERRAL_CODE).apply()
        }

        fun storePendingReferralCode(context: Context, code: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_PENDING_REFERRAL_CODE, code).apply()
        }
    }

    private object Keys {
        val REFERRAL_CODE = stringPreferencesKey("referral_code")
        val FRIENDS_JOINED = intPreferencesKey("referral_friends_joined")
        val FREE_MONTHS_EARNED = intPreferencesKey("referral_free_months_earned")
        val BONUS_PASS_DAYS_REMAINING = intPreferencesKey("referral_bonus_pass_days_remaining")
        val LAST_SEEN_BONUS_DAYS = intPreferencesKey("lastSeenReferralBonusDays")
        val HOME_INVITE_DISMISSED_AT_MS = longPreferencesKey("homeInviteCardDismissedAt")
    }

    private val _newlyEarnedBonusDays = MutableStateFlow(0)
    val newlyEarnedBonusDays: StateFlow<Int> = _newlyEarnedBonusDays.asStateFlow()

    /** Flow of the current referral code. */
    val referralCode: Flow<String> = preferences.map { prefs ->
        prefs[Keys.REFERRAL_CODE] ?: ""
    }

    /** Flow of referral stats. */
    val stats: Flow<ReferralStats> = preferences.map { prefs ->
        ReferralStats(
            friendsJoined = prefs[Keys.FRIENDS_JOINED] ?: 0,
            freeMonthsEarned = prefs[Keys.FREE_MONTHS_EARNED] ?: 0,
            bonusPassDaysRemaining = prefs[Keys.BONUS_PASS_DAYS_REMAINING] ?: 0
        )
    }

    val shouldShowHomeInviteCard: Flow<Boolean> = preferences.map { prefs ->
        val dismissedAt = prefs[Keys.HOME_INVITE_DISMISSED_AT_MS] ?: 0L
        dismissedAt <= 0L || System.currentTimeMillis() - dismissedAt >= HOME_INVITE_CARD_COOLDOWN_MS
    }

    /**
     * Get or create the user's referral code.
     * Tries Supabase first, falls back to local hash-based generation.
     */
    suspend fun getOrCreateReferralCode(): String {
        // Check local cache first
        val existing = preferences.first()[Keys.REFERRAL_CODE]
        if (!existing.isNullOrEmpty()) {
            return existing
        }

        // Try Supabase RPC
        val supabaseCode = promoCodeService.getOrCreateReferralCodeFromSupabase()
        if (supabaseCode != null) {
            dataStore.edit { prefs ->
                prefs[Keys.REFERRAL_CODE] = supabaseCode
            }
            Log.i(TAG, "Got referral code from Supabase: $supabaseCode")
            return supabaseCode
        }

        // Fallback: generate locally from device ID hash
        val deviceId = getDeviceId()
        val code = generateCodeFromDeviceId(deviceId)

        dataStore.edit { prefs ->
            prefs[Keys.REFERRAL_CODE] = code
        }

        Log.i(TAG, "Generated local referral code: $code")
        return code
    }

    /**
     * Get the full referral link for sharing.
     */
    suspend fun getReferralLink(): String {
        val code = getOrCreateReferralCode()
        return REFERRAL_BASE_URL + code
    }

    /**
     * Generate the share message for inviting friends.
     */
    suspend fun getShareMessage(): String {
        val code = getOrCreateReferralCode()
        val link = getReferralLink()
        return context.getString(
            R.string.referral_share_message,
            code,
            APP_STORE_URL,
            PLAY_STORE_URL,
            link
        )
    }

    /**
     * Track a referral signup in Supabase (when a referred user enters a referrer's code).
     */
    suspend fun trackReferralSignup(referrerCode: String): Boolean {
        val normalizedCode = referrerCode.trim().uppercase(Locale.US)
        if (normalizedCode.isEmpty()) {
            clearPendingReferralCode(context)
            return false
        }

        val ownCode = preferences.first()[Keys.REFERRAL_CODE]?.uppercase(Locale.US)
        if (!ownCode.isNullOrEmpty() && ownCode == normalizedCode) {
            Log.w(TAG, "Attempted self-referral with code: $normalizedCode")
            setWasReferred(false)
            clearPendingReferralCode(context)
            return false
        }

        if (!promoCodeService.validateReferralCode(normalizedCode)) {
            Log.w(TAG, "Invalid referral code: $normalizedCode")
            clearPendingReferralCode(context)
            return false
        }

        val success = promoCodeService.trackReferralSignup(normalizedCode)
        if (success) {
            setWasReferred(true)
            clearPendingReferralCode(context)
        }
        return success
    }

    /**
     * Refresh referral stats from Supabase and update local cache.
     */
    suspend fun refreshStats() {
        val code = preferences.first()[Keys.REFERRAL_CODE] ?: return

        val stats = promoCodeService.getReferralStats(code)
        if (stats != null) {
            val currentPrefs = preferences.first()
            val previousBonusDays = currentPrefs[Keys.LAST_SEEN_BONUS_DAYS] ?: 0
            val bonusDaysRemaining = stats.bonusPassDaysRemaining
            val newlyEarnedDays = (bonusDaysRemaining - previousBonusDays).coerceAtLeast(0).coerceAtMost(30)

            dataStore.edit { prefs ->
                prefs[Keys.FRIENDS_JOINED] = stats.friendsJoinedCount
                prefs[Keys.FREE_MONTHS_EARNED] = stats.freeMonthsEarned
                prefs[Keys.BONUS_PASS_DAYS_REMAINING] = bonusDaysRemaining
                prefs[Keys.LAST_SEEN_BONUS_DAYS] = bonusDaysRemaining
            }

            if (newlyEarnedDays > 0) {
                _newlyEarnedBonusDays.value = newlyEarnedDays
            }

            Log.i(TAG, "Refreshed referral stats: ${stats.friendsJoinedCount} friends, ${stats.freeMonthsEarned} months, $bonusDaysRemaining bonus days")
        }
    }

    suspend fun dismissHomeInviteCard() {
        dataStore.edit { prefs ->
            prefs[Keys.HOME_INVITE_DISMISSED_AT_MS] = System.currentTimeMillis()
        }
    }

    fun acknowledgeBonusCelebration() {
        _newlyEarnedBonusDays.value = 0
    }

    /**
     * Drop the locally cached referral code and stats. Call on sign-out so the
     * next account that signs in starts clean and re-fetches its own code from
     * Supabase (mirrors iOS `ReferralService.clearCache`).
     */
    suspend fun clearCache() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.REFERRAL_CODE)
            prefs.remove(Keys.FRIENDS_JOINED)
            prefs.remove(Keys.FREE_MONTHS_EARNED)
            prefs.remove(Keys.BONUS_PASS_DAYS_REMAINING)
            prefs.remove(Keys.LAST_SEEN_BONUS_DAYS)
        }
        acknowledgeBonusCelebration()
        clearPendingReferralCode(context)
        setWasReferred(false)
    }

    private fun setWasReferred(wasReferred: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WAS_REFERRED, wasReferred)
            .apply()
    }

    /**
     * Generate a 6-character referral code from the device ID using SHA-256 hash.
     */
    private fun generateCodeFromDeviceId(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(deviceId.toByteArray())
        // Convert first bytes to alphanumeric characters
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // No I, O, 0, 1 to avoid confusion
        return buildString {
            for (i in 0 until CODE_LENGTH) {
                val index = (hash[i].toInt() and 0xFF) % chars.length
                append(chars[index])
            }
        }
    }

    /**
     * Get or create a persistent device ID.
     */
    private fun getDeviceId(): String {
        return deviceIdProvider.deviceId
    }
}
