package com.trackspeed.android.cloud

import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.trackspeed.android.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(
        val userId: String,
        val email: String?,
        val isAnonymous: Boolean = email.isNullOrBlank()
    ) : AuthState
    data class Error(val message: String) : AuthState
}

fun AuthState.isRealAuthenticated(): Boolean {
    return this is AuthState.Authenticated && !isAnonymous
}

@Singleton
class AuthService @Inject constructor(
    private val supabase: SupabaseClient,
    private val remoteConfigService: RemoteConfigService
) {
    private companion object {
        private const val TAG = "AuthService"
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val isAuthenticated: Boolean
        get() = _authState.value.isRealAuthenticated()

    /**
     * The authenticated user id, if any. Includes anonymous sessions created
     * by [ensureAnonymousSession]: those have no email but a valid `auth.uid()`
     * that the new RLS policies on `race_events`, `crossings`, and `athletes`
     * key off.
     */
    val currentUserId: String?
        get() = supabase.auth.currentUserOrNull()?.id
            ?: (_authState.value as? AuthState.Authenticated)?.userId

    suspend fun checkSession() {
        try {
            val session = supabase.auth.currentSessionOrNull()
            if (session != null) {
                val user = supabase.auth.currentUserOrNull()
                val email = user?.email
                _authState.value = AuthState.Authenticated(
                    userId = user?.id ?: "",
                    email = email,
                    isAnonymous = email.isNullOrBlank()
                )
            } else {
                _authState.value = AuthState.Unauthenticated
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Unauthenticated
        }
    }

    /**
     * Ensure every install has at least an anonymous Supabase session so
     * services that need `auth.uid()` for RLS work for guests too.
     *
     * No-op if a session already exists. A subsequent real sign-in
     * (Google / email) replaces this anonymous session via the SDK's normal
     * sign-in flow. UI auth-state remains Unauthenticated for anonymous —
     * `currentUserId` exposes the anonymous id for service-layer use only.
     *
     * Mirrors iOS `AuthService.ensureAnonymousSession()`. Run BEFORE any
     * Supabase write that hits an RLS-protected table.
     */
    suspend fun ensureAnonymousSession() {
        try {
            if (supabase.auth.currentSessionOrNull() != null) return
            supabase.auth.signInAnonymously()
            val user = supabase.auth.currentUserOrNull()
            Log.i(TAG, "Created anonymous Supabase session: ${user?.id?.take(8)}")
        } catch (e: Exception) {
            if (isAnonymousProviderDisabled(e)) {
                Log.i(TAG, "Anonymous Supabase sign-in is disabled; continuing as local guest")
            } else {
                Log.w(TAG, "Anonymous sign-in failed: ${safeAuthErrorCode(e)}")
            }
            _authState.value = AuthState.Unauthenticated
        }
    }

    @SuppressLint("CredentialManagerSignInWithGoogle") // Response type is explicitly validated below.
    suspend fun signInWithGoogle(context: Context) {
        try {
            _authState.value = AuthState.Loading

            val credentialManager = CredentialManager.create(context)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context, request)
            require(
                result.credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                "Credential Manager returned an unsupported credential type"
            }
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val idToken = googleIdTokenCredential.idToken

            supabase.auth.signInWith(IDToken) {
                provider = Google
                this.idToken = idToken
            }

            val user = supabase.auth.currentUserOrNull()
            _authState.value = AuthState.Authenticated(
                userId = user?.id ?: "",
                email = user?.email,
                isAnonymous = false
            )
            remoteConfigService.refreshIfStale()
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if ("No credentials available" in msg || "canceled" in msg.lowercase()) {
                _authState.value = AuthState.Unauthenticated
            } else {
                _authState.value = AuthState.Error(parseAuthError(e))
            }
        }
    }

    suspend fun signUpWithEmail(email: String, password: String) {
        try {
            _authState.value = AuthState.Loading
            supabase.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            val user = supabase.auth.currentUserOrNull()
            _authState.value = AuthState.Authenticated(
                userId = user?.id ?: "",
                email = user?.email,
                isAnonymous = false
            )
            remoteConfigService.refreshIfStale()
        } catch (e: Exception) {
            _authState.value = AuthState.Error(parseAuthError(e))
        }
    }

    suspend fun signInWithEmail(email: String, password: String) {
        try {
            _authState.value = AuthState.Loading
            supabase.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            val user = supabase.auth.currentUserOrNull()
            _authState.value = AuthState.Authenticated(
                userId = user?.id ?: "",
                email = user?.email,
                isAnonymous = false
            )
            remoteConfigService.refreshIfStale()
        } catch (e: Exception) {
            _authState.value = AuthState.Error(parseAuthError(e))
        }
    }

    suspend fun signOut() {
        try {
            supabase.auth.signOut()
        } catch (_: Exception) { }
        _authState.value = AuthState.Unauthenticated
    }

    suspend fun deleteAccount(deviceId: String) {
        try {
            _authState.value = AuthState.Loading
            supabase.postgrest.rpc(
                "delete_user_account",
                buildJsonObject { put("p_device_id", deviceId) }
            )
            try {
                supabase.auth.signOut()
            } catch (_: Exception) {
                // The RPC deletes the auth user; local sign-out may fail if the
                // session is already invalidated.
            }
            _authState.value = AuthState.Unauthenticated
        } catch (e: Exception) {
            _authState.value = AuthState.Error("Unable to delete account. Please try again.")
            throw e
        }
    }

    fun clearError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = AuthState.Unauthenticated
        }
    }

    private fun parseAuthError(e: Exception): String {
        val msg = e.message ?: return "Authentication failed"
        return when {
            "invalid_credentials" in msg || "Invalid login" in msg -> "Invalid email or password"
            "User already registered" in msg -> "An account with this email already exists"
            "Email not confirmed" in msg -> "Please check your email to confirm your account"
            "weak_password" in msg -> "Password must be at least 6 characters"
            "No credentials available" in msg || "canceled" in msg.lowercase() -> "Sign-in was cancelled"
            else -> "Authentication failed: ${safeAuthErrorCode(e)}"
        }
    }

    private fun isAnonymousProviderDisabled(error: Exception): Boolean {
        val details = "${error.message.orEmpty()} ${error.localizedMessage.orEmpty()}".lowercase()
        return details.contains("anonymous_provider_disabled") ||
            details.contains("anonymous sign-ins are disabled")
    }

    private fun safeAuthErrorCode(error: Exception): String {
        val message = error.message.orEmpty()
        return when {
            "anonymous_provider_disabled" in message -> "anonymous_provider_disabled"
            "invalid_credentials" in message -> "invalid_credentials"
            "Email not confirmed" in message -> "email_not_confirmed"
            "User already registered" in message -> "user_already_registered"
            "weak_password" in message -> "weak_password"
            message.contains("No credentials available", ignoreCase = true) -> "no_credentials"
            message.contains("canceled", ignoreCase = true) -> "cancelled"
            else -> error::class.java.simpleName.ifBlank { "unknown" }
        }
    }
}
