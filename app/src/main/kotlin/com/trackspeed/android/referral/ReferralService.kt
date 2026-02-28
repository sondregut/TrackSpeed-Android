package com.trackspeed.android.referral

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Referral stats tracked locally.
 * In a full implementation these would come from Supabase.
 */
data class ReferralStats(
    val friendsJoined: Int = 0,
    val freeMonthsEarned: Int = 0
)

/**
 * Service for managing referral codes and sharing.
 *
 * Generates a unique referral code based on the device ID hash (6 characters).
 * Stores the code in DataStore for persistence.
 * In the full implementation, referral tracking would be backed by Supabase
 * (matching the iOS ReferralService which uses SupabaseService).
 */
@Singleton
class ReferralService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val TAG = "ReferralService"
        private const val REFERRAL_BASE_URL = "https://mytrackspeed.com/invite/"
        private const val CODE_LENGTH = 6
    }

    private object Keys {
        val REFERRAL_CODE = stringPreferencesKey("referral_code")
        val FRIENDS_JOINED = intPreferencesKey("referral_friends_joined")
        val FREE_MONTHS_EARNED = intPreferencesKey("referral_free_months_earned")
    }

    /** Flow of the current referral code. */
    val referralCode: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.REFERRAL_CODE] ?: ""
    }

    /** Flow of referral stats. */
    val stats: Flow<ReferralStats> = dataStore.data.map { prefs ->
        ReferralStats(
            friendsJoined = prefs[Keys.FRIENDS_JOINED] ?: 0,
            freeMonthsEarned = prefs[Keys.FREE_MONTHS_EARNED] ?: 0
        )
    }

    /**
     * Get or create the user's referral code.
     * Generates a 6-character alphanumeric code derived from the device ID.
     */
    suspend fun getOrCreateReferralCode(): String {
        val existing = dataStore.data.first()[Keys.REFERRAL_CODE]
        if (!existing.isNullOrEmpty()) {
            return existing
        }

        val deviceId = getDeviceId()
        val code = generateCodeFromDeviceId(deviceId)

        dataStore.edit { prefs ->
            prefs[Keys.REFERRAL_CODE] = code
        }

        Log.i(TAG, "Generated referral code: $code")
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
        val link = REFERRAL_BASE_URL + code
        return "Try TrackSpeed for sprint timing! Use my code: $code $link"
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
        val prefs = context.getSharedPreferences("trackspeed", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", newId).apply()
            newId
        }
    }
}
