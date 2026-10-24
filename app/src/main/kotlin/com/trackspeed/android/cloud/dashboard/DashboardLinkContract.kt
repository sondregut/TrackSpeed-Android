package com.trackspeed.android.cloud.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URI
import java.time.Instant

internal enum class DashboardError {
    NOT_SIGNED_IN, OFFLINE, SESSION_EXPIRED, EMAIL_REQUIRED, RATE_LIMITED, UNAVAILABLE, FAILED, OPEN_UNAVAILABLE
}

/** Credentials and links are ephemeral and deliberately redact their string representation. */
internal class DashboardAccess(val userId: String?, val anonymous: Boolean, val token: String?) {
    val namedAccount: Boolean get() = !anonymous && !userId.isNullOrBlank()
    override fun toString() = "DashboardAccess([redacted])"
}

internal class DashboardLink private constructor(val url: String) {
    override fun toString() = "DashboardLink([redacted])"

    companion object {
        fun validated(raw: String): DashboardLink? {
            if (raw.length > 8192 || raw.any { it.isWhitespace() || it.code < 32 } || '\\' in raw) return null
            val uri = try { URI(raw) } catch (_: Exception) { return null }
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                !uri.host.equals("mytrackspeed.com", ignoreCase = true) ||
                uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
                uri.rawPath !in listOf("/dashboard", "/dashboard/") ||
                uri.rawQuery != null || uri.rawFragment.isNullOrBlank()) return null
            return DashboardLink(raw) // Preserve the token-bearing fragment byte-for-byte.
        }
    }
}

internal sealed interface DashboardResult {
    class Success(val link: DashboardLink) : DashboardResult {
        override fun toString() = "DashboardResult.Success([redacted])"
    }
    data class Failure(val error: DashboardError) : DashboardResult
}

internal class DashboardHttpResponse(val status: Int, val contentType: String?, val body: String) {
    override fun toString() = "DashboardHttpResponse(status=$status, body=[redacted])"
}

internal object DashboardResponseParser {
    fun parse(response: DashboardHttpResponse, now: Instant = Instant.now()): DashboardResult {
        val isJson = response.contentType?.substringBefore(';')?.trim()?.equals("application/json", true) == true
        val body = if (isJson && response.body.length <= 16384) {
            try { Json.parseToJsonElement(response.body) as? JsonObject } catch (_: Exception) { null }
        } else null
        fun value(key: String): String? = try { body?.get(key)?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
        when (value("error")) {
            "anonymous_user" -> return DashboardResult.Failure(DashboardError.NOT_SIGNED_IN)
            "email_required" -> return DashboardResult.Failure(DashboardError.EMAIL_REQUIRED)
        }
        val error = when (response.status) {
            in 200..299 -> {
                if (!isJson) return DashboardResult.Failure(DashboardError.UNAVAILABLE)
                val link = value("url")?.let(DashboardLink::validated)
                    ?: return DashboardResult.Failure(DashboardError.FAILED)
                value("expiresAt")?.let { raw ->
                    val expires = try { Instant.parse(raw) } catch (_: Exception) { null }
                    if (expires == null || !expires.isAfter(now)) return DashboardResult.Failure(DashboardError.FAILED)
                }
                return DashboardResult.Success(link)
            }
            401, 403 -> DashboardError.SESSION_EXPIRED
            404, 405, 501 -> DashboardError.UNAVAILABLE
            429 -> if (isJson) DashboardError.RATE_LIMITED else DashboardError.FAILED
            else -> DashboardError.FAILED
        }
        return DashboardResult.Failure(error)
    }
}

/** Fakeable transport boundary; never creates/refreshes an account or stores a returned URL. */
internal class DashboardLinkRequester(
    private val access: () -> DashboardAccess,
    private val post: suspend (String) -> DashboardHttpResponse,
    private val now: () -> Instant = Instant::now
) {
    suspend fun request(): DashboardResult {
        val before = access()
        if (!before.namedAccount) return DashboardResult.Failure(DashboardError.NOT_SIGNED_IN)
        val token = before.token?.takeIf { it.isNotBlank() }
            ?: return DashboardResult.Failure(DashboardError.SESSION_EXPIRED)
        return try {
            val response = post(token)
            currentCoroutineContext().ensureActive()
            val after = access()
            if (!after.namedAccount || after.userId != before.userId) {
                DashboardResult.Failure(DashboardError.SESSION_EXPIRED)
            } else DashboardResponseParser.parse(response, now())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            DashboardResult.Failure(DashboardError.OFFLINE)
        } catch (_: Exception) {
            DashboardResult.Failure(DashboardError.FAILED)
        }
    }
}
