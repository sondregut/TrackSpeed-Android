package com.trackspeed.android.cloud

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val userId: String, val email: String?) : AuthState
    data class Error(val message: String) : AuthState
}

@Singleton
class AuthService @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val isAuthenticated: Boolean
        get() = _authState.value is AuthState.Authenticated

    val currentUserId: String?
        get() = (_authState.value as? AuthState.Authenticated)?.userId

    suspend fun checkSession() {
        try {
            val session = supabase.auth.currentSessionOrNull()
            if (session != null) {
                val user = supabase.auth.currentUserOrNull()
                _authState.value = AuthState.Authenticated(
                    userId = user?.id ?: "",
                    email = user?.email
                )
            } else {
                _authState.value = AuthState.Unauthenticated
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Unauthenticated
        }
    }

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
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val idToken = googleIdTokenCredential.idToken

            supabase.auth.signInWith(IDToken) {
                provider = Google
                this.idToken = idToken
            }

            val user = supabase.auth.currentUserOrNull()
            _authState.value = AuthState.Authenticated(
                userId = user?.id ?: "",
                email = user?.email
            )
        } catch (e: Exception) {
            _authState.value = AuthState.Error(parseAuthError(e))
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
                email = user?.email
            )
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
                email = user?.email
            )
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
            supabase.auth.signOut()
        } catch (_: Exception) { }
        _authState.value = AuthState.Unauthenticated
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
            else -> "Authentication failed: ${msg.take(100)}"
        }
    }
}
