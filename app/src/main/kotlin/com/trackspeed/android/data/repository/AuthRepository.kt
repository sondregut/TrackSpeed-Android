package com.trackspeed.android.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.trackspeed.android.cloud.AuthService
import com.trackspeed.android.cloud.AuthState
import com.trackspeed.android.cloud.ProfileService
import com.trackspeed.android.cloud.dto.ProfileDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authService: AuthService,
    private val profileService: ProfileService,
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val USER_NAME = stringPreferencesKey("user_name")
    }

    val authState: StateFlow<AuthState> = authService.authState

    val isAuthenticated: Boolean get() = authService.isAuthenticated
    val currentUserId: String? get() = authService.currentUserId

    val userName: Flow<String?> = dataStore.data.map { it[Keys.USER_NAME] }
    val userEmail: Flow<String?> = dataStore.data.map { it[Keys.USER_EMAIL] }

    suspend fun checkSession() = authService.checkSession()

    suspend fun signInWithGoogle(context: Context) {
        authService.signInWithGoogle(context)
        persistAuthUser()
        syncProfileAfterAuth()
    }

    suspend fun signUpWithEmail(email: String, password: String) {
        authService.signUpWithEmail(email, password)
        persistAuthUser()
        syncProfileAfterAuth()
    }

    suspend fun signInWithEmail(email: String, password: String) {
        authService.signInWithEmail(email, password)
        persistAuthUser()
        syncProfileAfterAuth()
    }

    suspend fun signOut() {
        authService.signOut()
        clearPersistedUser()
    }

    suspend fun deleteAccount(deviceId: String) {
        authService.deleteAccount(deviceId)
        clearPersistedUser()
    }

    fun clearError() = authService.clearError()

    suspend fun setUserName(name: String) {
        dataStore.edit { it[Keys.USER_NAME] = name }
    }

    private suspend fun syncProfileAfterAuth() {
        val state = authService.authState.value
        if (state is AuthState.Authenticated) {
            try {
                profileService.syncProfile(
                    ProfileDto(
                        supabaseUserId = state.userId,
                        email = state.email
                    )
                )
            } catch (e: Exception) {
                Log.w("AuthRepository", "Profile sync after auth failed (non-critical)", e)
            }
        }
    }

    private suspend fun persistAuthUser() {
        val state = authService.authState.value
        if (state is AuthState.Authenticated) {
            dataStore.edit { prefs ->
                prefs[Keys.USER_ID] = state.userId
                state.email?.let { prefs[Keys.USER_EMAIL] = it }
            }
        }
    }

    private suspend fun clearPersistedUser() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.USER_EMAIL)
            prefs.remove(Keys.USER_NAME)
        }
    }
}
