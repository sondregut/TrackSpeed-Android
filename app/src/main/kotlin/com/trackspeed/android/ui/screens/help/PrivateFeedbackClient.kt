package com.trackspeed.android.ui.screens.help

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Existing released TrackSpeed private-feedback destination; no auth header, redirects or automatic sends. */
@Singleton
class PrivateFeedbackClient @Inject constructor() {
    private val http by lazy { HttpClient(Android) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000 }
    } }
    suspend fun send(draft: PrivateFeedbackDraft): PrivateFeedbackReceipt = try {
        submitPrivateFeedback(PrivateFeedbackTransport { body ->
            val response = http.post(ENDPOINT) { contentType(ContentType.Application.Json); setBody(body) }
            val channel = response.bodyAsChannel()
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count == -1) break
                if (count == 0) continue
                if (bytes.size() + count > 65_536) {
                    channel.cancel(null)
                    throw PrivateFeedbackException(PrivateFeedbackFailure.UNAVAILABLE)
                }
                bytes.write(buffer, 0, count)
            }
            PrivateFeedbackHttpResponse(response.status.value, bytes.toString("UTF-8"))
        }, draft)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (known: PrivateFeedbackException) { throw known }
    catch (_: Exception) { throw PrivateFeedbackException(PrivateFeedbackFailure.UNAVAILABLE) }

    companion object {
        const val ENDPOINT = "https://us-central1-fabled-emissary-476021-g0.cloudfunctions.net/appFeedback"
        const val SUPPORT_EMAIL = "support@mytrackspeed.com"
    }
}
