package com.trackspeed.android.cloud

import com.trackspeed.android.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed class PhoneVerificationError(message: String) : Exception(message) {
    data object InvalidPhoneNumber : PhoneVerificationError("Please enter a valid phone number")
    data object InvalidCode : PhoneVerificationError("Invalid verification code")
    data object RateLimited : PhoneVerificationError("Too many attempts. Please wait before trying again.")
    data object NotAuthenticated : PhoneVerificationError("Not authenticated")
    data class VerificationFailed(val reason: String) : PhoneVerificationError("Verification failed: $reason")
    data class ServerError(val reason: String) : PhoneVerificationError("Server error: $reason")
    data class NetworkError(val code: String) : PhoneVerificationError("Network error: $code")
}

data class PhoneVerificationState(
    val isLoading: Boolean = false,
    val lastError: PhoneVerificationError? = null
)

@Singleton
class PhoneVerificationService @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val httpClient = HttpClient(Android)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(PhoneVerificationState())
    val state: StateFlow<PhoneVerificationState> = _state.asStateFlow()

    private var lastSendAtMillis: Long? = null

    suspend fun sendVerificationCode(phone: String): Boolean {
        enforceSendCooldown()

        return runWithState {
            val normalizedPhone = normalizePhoneNumber(phone)
            if (!isValidPhoneNumber(normalizedPhone)) {
                throw PhoneVerificationError.InvalidPhoneNumber
            }

            val response = callVerificationFunction(
                functionName = "send-verification",
                body = SendVerificationRequest(phone = normalizedPhone)
            )

            when (response.statusCode) {
                200 -> {
                    lastSendAtMillis = System.currentTimeMillis()
                    response.body.success ?: true
                }
                429 -> throw PhoneVerificationError.RateLimited
                in 400..499 -> throw PhoneVerificationError.VerificationFailed(
                    response.body.error ?: "Request failed"
                )
                else -> throw PhoneVerificationError.ServerError(response.body.error ?: "Server error")
            }
        }
    }

    suspend fun verifyCode(phone: String, code: String): Boolean {
        return runWithState {
            val normalizedPhone = normalizePhoneNumber(phone)
            if (!isValidPhoneNumber(normalizedPhone)) {
                throw PhoneVerificationError.InvalidPhoneNumber
            }
            if (code.length != 6 || !code.all { it.isDigit() }) {
                throw PhoneVerificationError.InvalidCode
            }

            val response = callVerificationFunction(
                functionName = "verify-code",
                body = VerifyCodeRequest(phone = normalizedPhone, code = code)
            )

            when (response.statusCode) {
                200 -> response.body.valid ?: false
                429 -> throw PhoneVerificationError.RateLimited
                in 400..499 -> throw PhoneVerificationError.VerificationFailed(
                    response.body.error ?: "Verification failed"
                )
                else -> throw PhoneVerificationError.ServerError(response.body.error ?: "Server error")
            }
        }
    }

    private fun enforceSendCooldown() {
        val lastSend = lastSendAtMillis ?: return
        val elapsedMillis = System.currentTimeMillis() - lastSend
        if (elapsedMillis < SEND_COOLDOWN_MILLIS) {
            val error = PhoneVerificationError.RateLimited
            _state.value = PhoneVerificationState(isLoading = false, lastError = error)
            throw error
        }
    }

    private suspend fun <T> runWithState(block: suspend () -> T): T {
        _state.value = PhoneVerificationState(isLoading = true, lastError = null)
        return try {
            block()
        } catch (error: PhoneVerificationError) {
            _state.value = PhoneVerificationState(isLoading = false, lastError = error)
            throw error
        } catch (error: Exception) {
            val safeError = PhoneVerificationError.NetworkError(error.safeCloudErrorCode())
            _state.value = PhoneVerificationState(isLoading = false, lastError = safeError)
            throw safeError
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }

    private suspend inline fun <reified T> callVerificationFunction(
        functionName: String,
        body: T
    ): VerificationFunctionResponse = withContext(Dispatchers.IO) {
        val accessToken = supabase.auth.currentSessionOrNull()?.accessToken
            ?: throw PhoneVerificationError.NotAuthenticated
        val response = httpClient.post("${BuildConfig.SUPABASE_URL}/functions/v1/$functionName") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $accessToken")
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            setBody(json.encodeToString(body))
        }
        val responseBody = runCatching {
            json.decodeFromString<VerificationResponse>(response.bodyAsText())
        }.getOrDefault(VerificationResponse())
        VerificationFunctionResponse(
            statusCode = response.status.value,
            body = responseBody
        )
    }

    private fun normalizePhoneNumber(phone: String): String {
        var cleaned = phone.filter { it.isDigit() || it == '+' }
        if (!cleaned.startsWith("+")) {
            cleaned = if (cleaned.length == 10) {
                "+1$cleaned"
            } else {
                "+$cleaned"
            }
        }
        return cleaned
    }

    private fun isValidPhoneNumber(phone: String): Boolean {
        if (!phone.startsWith("+")) return false
        val digits = phone.drop(1).filter { it.isDigit() }
        return digits.length in 8..15
    }

    @Serializable
    private data class SendVerificationRequest(val phone: String)

    @Serializable
    private data class VerifyCodeRequest(
        val phone: String,
        val code: String
    )

    @Serializable
    private data class VerificationResponse(
        val success: Boolean? = null,
        val valid: Boolean? = null,
        val status: String? = null,
        val error: String? = null
    )

    private data class VerificationFunctionResponse(
        val statusCode: Int,
        val body: VerificationResponse
    )

    private companion object {
        private const val SEND_COOLDOWN_MILLIS = 30_000L
    }
}
