package com.trackspeed.android.cloud.dashboard

import com.trackspeed.android.cloud.AuthService
import com.trackspeed.android.cloud.AuthState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDashboardService @Inject constructor(
    private val authService: AuthService,
    private val supabase: SupabaseClient
) {
    // No logging/cache plugins. Never forward Authorization to an HTTP redirect.
    private val httpClient = HttpClient(Android) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }
    private val requester = DashboardLinkRequester(access = ::currentAccess, post = { token ->
        try {
            val response = httpClient.post("https://mytrackspeed.com/api/dashboard/link") {
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                header("Authorization", "Bearer $token")
                header("Cache-Control", "no-store")
                setBody("{}")
            }
            DashboardHttpResponse(response.status.value, response.headers["Content-Type"], response.bodyAsText())
        } catch (_: HttpRequestTimeoutException) {
            throw IOException("Dashboard request timed out")
        }
    })

    internal suspend fun requestLink(): DashboardResult = requester.request()

    private fun currentAccess(): DashboardAccess {
        val account = authService.authState.value as? AuthState.Authenticated
        val session = supabase.auth.currentSessionOrNull()
        // A changed/expired SDK session cannot borrow the previous account's UI identity.
        val token = session?.accessToken?.takeIf { session.user?.id == account?.userId }
        return DashboardAccess(account?.userId, account?.isAnonymous != false, token)
    }
}
