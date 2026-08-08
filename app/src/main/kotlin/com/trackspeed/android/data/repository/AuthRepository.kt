package com.trackspeed.android.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.trackspeed.android.analytics.AnalyticsEvent
import com.trackspeed.android.analytics.AnalyticsService
import com.trackspeed.android.audio.ElevenLabsService
import com.trackspeed.android.billing.PromoCodeError
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.cloud.AuthService
import com.trackspeed.android.cloud.AuthState
import com.trackspeed.android.cloud.ProfileService
import com.trackspeed.android.cloud.RaceEventService
import com.trackspeed.android.cloud.dto.ProfileDto
import com.trackspeed.android.cloud.isRealAuthenticated
import com.trackspeed.android.cloud.safeCloudErrorCode
import com.trackspeed.android.data.recovery.SessionStateRecoveryService
import com.trackspeed.android.notifications.NotificationService
import com.trackspeed.android.referral.ReferralService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authService: AuthService,
    private val profileService: ProfileService,
    private val dataStore: DataStore<Preferences>,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val referralService: ReferralService,
    private val raceEventService: RaceEventService,
    private val sessionStateRecoveryService: SessionStateRecoveryService,
    private val subscriptionManager: SubscriptionManager,
    private val notificationService: NotificationService,
    private val elevenLabsService: ElevenLabsService,
    private val analyticsService: AnalyticsService
) {
    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val USER_NAME = stringPreferencesKey("user_name")
    }

    val authState: StateFlow<AuthState> = authService.authState

    val isAuthenticated: Boolean get() = authService.isAuthenticated
    val currentUserId: String? get() = authService.currentUserId

    val userName: Flow<String?> = preferences.map { it[Keys.USER_NAME] }
    val userEmail: Flow<String?> = preferences.map { it[Keys.USER_EMAIL] }

    suspend fun checkSession() {
        authService.checkSession()
        syncAnalyticsIdentityFromState()
        syncRevenueCatIdentity()
    }

    suspend fun signInWithGoogle(context: Context) {
        analyticsService.track(AnalyticsEvent.AUTH_METHOD_SELECTED, mapOf("method" to AUTH_METHOD_GOOGLE))
        authService.signInWithGoogle(context)
        completeAuthFlow(method = AUTH_METHOD_GOOGLE)
    }

    suspend fun signUpWithEmail(email: String, password: String) {
        analyticsService.track(AnalyticsEvent.AUTH_METHOD_SELECTED, mapOf("method" to AUTH_METHOD_EMAIL))
        authService.signUpWithEmail(email, password)
        completeAuthFlow(method = AUTH_METHOD_EMAIL, isSignUp = true)
    }

    suspend fun signInWithEmail(email: String, password: String) {
        analyticsService.track(AnalyticsEvent.AUTH_METHOD_SELECTED, mapOf("method" to AUTH_METHOD_EMAIL))
        authService.signInWithEmail(email, password)
        completeAuthFlow(method = AUTH_METHOD_EMAIL, isSignUp = false)
    }

    suspend fun signOut() {
        runCatching { raceEventService.unregisterDeviceToken() }
            .onFailure { Log.w("AuthRepository", "Device token unregister failed", it) }
        authService.signOut()
        subscriptionManager.logOut()
        teardownLocalUserData()
    }

    suspend fun deleteAccount(deviceId: String) {
        runCatching { raceEventService.unregisterDeviceToken() }
            .onFailure { Log.w("AuthRepository", "Device token unregister failed", it) }
        authService.deleteAccount(deviceId)
        subscriptionManager.logOut()
        teardownLocalUserData()
    }

    /**
     * Purge every trace of the previous user from the device so the next
     * account starts clean. Mirrors iOS `AuthService.signOut` teardown:
     * Room rows (sessions/runs/athletes), local thumbnail/avatar files,
     * cached referral code + stats, all user-scoped DataStore keys, the
     * RaceEventService participant cache, and the persisted auth identity.
     *
     * Without this, a second account on the same device sees the first
     * account's runs and inherits their referral code.
     */
    private suspend fun teardownLocalUserData() {
        runCatching { sessionRepository.clearAllLocalData() }
            .onFailure { Log.w("AuthRepository", "Local data wipe failed", it) }
        runCatching { referralService.clearCache() }
            .onFailure { Log.w("AuthRepository", "Referral cache wipe failed", it) }
        runCatching { settingsRepository.clearUserScopedFields() }
            .onFailure { Log.w("AuthRepository", "Settings teardown failed", it) }
        runCatching { sessionRepository.clearPendingCloudDeletions() }
            .onFailure { Log.w("AuthRepository", "Pending deletion queue wipe failed", it) }
        runCatching { elevenLabsService.clearCache() }
            .onFailure { Log.w("AuthRepository", "ElevenLabs cache wipe failed", it) }
        raceEventService.clearRegisteredSessions()
        sessionStateRecoveryService.clearActiveSession()
        notificationService.cancelAllNotifications()
        deleteAvatarPhotoFile()
        clearPersistedUser()
    }

    private suspend fun deleteAvatarPhotoFile() {
        // Resolve the cached avatar path (if any) and unlink the file. Then
        // sweep the avatars directory in case earlier-version files leaked.
        val path = runCatching { settingsRepository.avatarPhotoPath.first() }.getOrNull()
        withContext(Dispatchers.IO) {
            path?.let { runCatching { File(it).delete() } }
            listOf("avatar", "avatars").forEach { directoryName ->
                runCatching {
                    val dir = File(context.filesDir, directoryName)
                    if (dir.exists()) dir.deleteRecursively()
                }
            }
        }
        runCatching { settingsRepository.setAvatarPhotoPath(null) }
    }

    private suspend fun processPendingReferralCode() {
        val pendingCode = ReferralService.getPendingReferralCode(context) ?: return
        Log.d("AuthRepository", "Processing pending referral/promo code after auth")

        try {
            subscriptionManager.redeemPromoCode(pendingCode, "pending_referral")
            persistReferralCodeForProfile(pendingCode)
            ReferralService.clearPendingReferralCode(context)
            Log.d("AuthRepository", "Pending code redeemed as promo code")
            return
        } catch (e: PromoCodeError) {
            if (e is PromoCodeError.AlreadyRedeemed) {
                Log.d("AuthRepository", "Pending promo code already redeemed; clearing")
                persistReferralCodeForProfile(pendingCode)
                ReferralService.clearPendingReferralCode(context)
                return
            }
            Log.d("AuthRepository", "Pending code is not a promo code: ${e.safeCloudErrorCode()}")
        } catch (e: Exception) {
            Log.d("AuthRepository", "Pending code promo redemption failed: ${e.safeCloudErrorCode()}")
        }

        try {
            val success = referralService.trackReferralSignup(pendingCode)
            if (success) {
                persistReferralCodeForProfile(pendingCode)
                Log.d("AuthRepository", "Pending code tracked as referral")
            } else {
                Log.w("AuthRepository", "Pending code was not accepted as referral")
                ReferralService.clearPendingReferralCode(context)
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Failed to process pending referral code: ${e.safeCloudErrorCode()}")
            ReferralService.clearPendingReferralCode(context)
        }
    }

    private suspend fun persistReferralCodeForProfile(code: String) {
        normalizedReferralCode(code)?.let { settingsRepository.setReferralCode(it) }
    }

    fun clearError() = authService.clearError()

    suspend fun setUserName(name: String) {
        dataStore.edit { it[Keys.USER_NAME] = name }
    }

    private fun clearProfileRlsBlockAfterRealAuth() {
        val state = authService.authState.value
        if (state is AuthState.Authenticated && state.isRealAuthenticated()) {
            profileService.clearRlsBlock()
        }
    }

    suspend fun processPendingProfileSync(force: Boolean = false): Boolean {
        val state = authService.authState.value
        if (state !is AuthState.Authenticated || state.isAnonymous || state.userId.isBlank()) {
            return false
        }

        val pending = settingsRepository.pendingProfileSync.first()
        val onboardingCompleted = settingsRepository.onboardingCompleted.first()
        if (!force && !pending) return false

        val profile = buildLocalProfileDto(state, onboardingCompleted)
        val succeeded = profileService.syncProfile(profile)
        if (succeeded) {
            settingsRepository.setPendingProfileSync(false)
        } else if (onboardingCompleted || pending) {
            settingsRepository.setPendingProfileSync(true)
        }
        return succeeded
    }

    private suspend fun syncProfileAfterAuth() {
        val state = authService.authState.value
        if (state is AuthState.Authenticated && !state.isAnonymous) {
            processPendingProfileSync(force = true)

            try {
                sessionRepository.processPendingCloudDeletions()
                sessionRepository.processPendingCloudUploads()
                sessionRepository.processPendingFlyingPrSync()
                sessionRepository.syncAthletesFromCloud()
                sessionRepository.syncSessionsFromCloud()
            } catch (e: Exception) {
                Log.w("AuthRepository", "Cloud import after auth failed (non-critical): ${e.safeCloudErrorCode()}")
            }
        }
    }

    private suspend fun buildLocalProfileDto(
        state: AuthState.Authenticated,
        onboardingCompleted: Boolean
    ): ProfileDto {
        val displayName = settingsRepository.displayName.first()?.takeIf { it.isNotBlank() }
        val userName = settingsRepository.userName.first().takeIf { it.isNotBlank() }
        val referralCode = normalizedReferralCode(ReferralService.getPendingReferralCode(context))
            ?: normalizedReferralCode(settingsRepository.referralCode.first())

        return ProfileDto(
            supabaseUserId = state.userId,
            fullName = displayName ?: userName,
            email = state.email,
            role = settingsRepository.userRole.first(),
            primaryEvent = settingsRepository.primaryEvent.first(),
            personalRecord = settingsRepository.personalRecord.first(),
            flyingPrDistance = settingsRepository.flyingDistance.first(),
            flyingPr = settingsRepository.flyingPR.first(),
            onboardingCompleted = onboardingCompleted,
            referralCode = referralCode
        )
    }

    private fun normalizedReferralCode(code: String?): String? {
        return code
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.US)
    }

    private suspend fun persistAuthUser() {
        val state = authService.authState.value
        if (state is AuthState.Authenticated && !state.isAnonymous) {
            dataStore.edit { prefs ->
                prefs[Keys.USER_ID] = state.userId
                state.email?.let { prefs[Keys.USER_EMAIL] = it }
            }
        }
    }

    private suspend fun completeAuthFlow(method: String, isSignUp: Boolean? = null) {
        val state = authService.authState.value
        if (state is AuthState.Authenticated && state.isRealAuthenticated() && state.userId.isNotBlank()) {
            clearProfileRlsBlockAfterRealAuth()
            persistAuthUser()
            settingsRepository.setHasSkippedLogin(false)
            analyticsService.identify(
                state.userId,
                properties = mapOf("auth_method" to method)
            )
            syncRevenueCatIdentity()
            syncProfileAfterAuth()
            processPendingReferralCode()
            analyticsService.track(
                AnalyticsEvent.AUTH_COMPLETED,
                authEventProperties(method, isSignUp)
            )
        } else {
            trackAuthFailure(method, isSignUp, state)
        }
    }

    private fun syncAnalyticsIdentityFromState() {
        val state = authService.authState.value
        if (state is AuthState.Authenticated && state.isRealAuthenticated() && state.userId.isNotBlank()) {
            analyticsService.identify(state.userId)
        }
    }

    private fun trackAuthFailure(method: String, isSignUp: Boolean?, state: AuthState) {
        val error = when (state) {
            is AuthState.Error -> state.message
            AuthState.Unauthenticated -> if (method == AUTH_METHOD_GOOGLE) "cancelled" else "not_authenticated"
            AuthState.Loading -> "still_loading"
            is AuthState.Authenticated -> "anonymous_or_missing_user"
        }
        analyticsService.track(
            AnalyticsEvent.AUTH_FAILED,
            authEventProperties(method, isSignUp) + mapOf("error" to error)
        )
    }

    private fun authEventProperties(method: String, isSignUp: Boolean?): Map<String, Any?> {
        return buildMap {
            put("method", method)
            if (isSignUp != null) {
                put("is_signup", isSignUp)
            }
        }
    }

    private fun syncRevenueCatIdentity() {
        val state = authService.authState.value
        if (state is AuthState.Authenticated && state.isRealAuthenticated() && state.userId.isNotBlank()) {
            subscriptionManager.logIn(state.userId)
        } else {
            subscriptionManager.refreshProStatus()
        }
    }

    private suspend fun clearPersistedUser() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.USER_EMAIL)
            prefs.remove(Keys.USER_NAME)
        }
    }

    private companion object {
        const val AUTH_METHOD_EMAIL = "email"
        const val AUTH_METHOD_GOOGLE = "google"
    }
}
