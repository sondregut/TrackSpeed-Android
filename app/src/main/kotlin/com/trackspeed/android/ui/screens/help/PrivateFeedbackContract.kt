package com.trackspeed.android.ui.screens.help

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class PrivateFeedbackKind { BUG, IMPROVEMENT, QUESTION;
    val wireName: String get() = name.lowercase()
}

@Serializable
data class PrivateFeedbackAttachment(val id: String = UUID.randomUUID().toString(), val base64: String)

@Serializable
data class PrivateFeedbackDraft(
    val id: String = UUID.randomUUID().toString(),
    val kind: PrivateFeedbackKind,
    val details: String = "",
    val email: String = "",
    val includeDiagnostics: Boolean = true,
    val publicationRequested: Boolean = false,
    val attachments: List<PrivateFeedbackAttachment> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val attempted: Boolean = false,
    val attemptedMetadata: Map<String, String> = emptyMap()
) {
    val validEmail: Boolean get() = email.trim().let { it.isEmpty() || (it.length <= 254 && Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(it)) }
    val canSend: Boolean get() = details.trim().isNotEmpty() && details.length <= 4_000 && validEmail &&
        attachments.size <= 3 && attachments.all { it.base64.length <= MAX_ATTACHMENT_BASE64 }
    val isEmpty: Boolean get() = details.isBlank() && email.isBlank() && attachments.isEmpty()

    /** Retrying unchanged content reuses the receipt ID; editing attempted content gets a new ID. */
    fun edited(candidate: PrivateFeedbackDraft, now: Long): PrivateFeedbackDraft {
        val clean = candidate.copy(details = candidate.details.take(4_000), email = candidate.email.take(254),
            publicationRequested = kind == PrivateFeedbackKind.IMPROVEMENT && candidate.publicationRequested,
            attachments = candidate.attachments.take(3))
        val changed = details != clean.details || email != clean.email || includeDiagnostics != clean.includeDiagnostics ||
            publicationRequested != clean.publicationRequested || attachments != clean.attachments
        if (!changed) return this
        return clean.copy(id = if (attempted) UUID.randomUUID().toString() else id, kind = kind,
            updatedAtMillis = maxOf(now, updatedAtMillis + 1), attempted = false, attemptedMetadata = emptyMap())
    }

    fun beginAttempt(metadata: Map<String, String>, now: Long): PrivateFeedbackDraft = if (attempted) this else copy(
        attempted = true, updatedAtMillis = maxOf(now, updatedAtMillis + 1),
        attemptedMetadata = if (includeDiagnostics) sanitizeFeedbackMetadata(metadata) else emptyMap())

    companion object { const val MAX_ATTACHMENT_BASE64 = 866_668 }
}

@Serializable
data class PrivateFeedbackPayload(
    val action: String = "submit", val id: String, val appID: String = "trackspeed", val kind: String,
    val details: String, val email: String, val metadata: Map<String, String>,
    val publicationRequested: Boolean, val attachments: List<PrivateFeedbackAttachment>
)

@Serializable
data class PrivateFeedbackReceipt(val id: String, val received: Boolean)

internal val FeedbackJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val AllowedMetadata = setOf("appVersion", "buildNumber", "osVersion", "device", "screen", "event")
internal fun sanitizeFeedbackMetadata(metadata: Map<String, String>): Map<String, String> =
    metadata.filterKeys(AllowedMetadata::contains).mapValues { (_, value) -> value.take(100) }

internal fun privateFeedbackPayload(draft: PrivateFeedbackDraft): PrivateFeedbackPayload {
    require(draft.canSend && draft.attempted)
    return PrivateFeedbackPayload(id = draft.id, kind = draft.kind.wireName, details = draft.details.trim(),
        email = draft.email.trim(), metadata = if (draft.includeDiagnostics) sanitizeFeedbackMetadata(draft.attemptedMetadata) else emptyMap(),
        publicationRequested = draft.kind == PrivateFeedbackKind.IMPROVEMENT && draft.publicationRequested,
        attachments = draft.attachments)
}

internal enum class PrivateFeedbackFailure { UNAVAILABLE, RATE_LIMITED, INVALID_RECEIPT, IMAGE, PERSISTENCE, TIMING_ACTIVE }
internal class PrivateFeedbackException(val reason: PrivateFeedbackFailure) : Exception(reason.name)
internal data class PrivateFeedbackHttpResponse(val status: Int, val body: String)
internal fun interface PrivateFeedbackTransport { suspend fun post(body: String): PrivateFeedbackHttpResponse }

internal suspend fun submitPrivateFeedback(transport: PrivateFeedbackTransport, draft: PrivateFeedbackDraft): PrivateFeedbackReceipt {
    val response = transport.post(FeedbackJson.encodeToString(privateFeedbackPayload(draft)))
    if (response.status == 429) throw PrivateFeedbackException(PrivateFeedbackFailure.RATE_LIMITED)
    if (response.status !in 200..299 || response.body.length > 65_536) throw PrivateFeedbackException(PrivateFeedbackFailure.UNAVAILABLE)
    val receipt = try { FeedbackJson.decodeFromString<PrivateFeedbackReceipt>(response.body) }
    catch (_: Exception) { throw PrivateFeedbackException(PrivateFeedbackFailure.INVALID_RECEIPT) }
    if (!receipt.received || receipt.id != draft.id) throw PrivateFeedbackException(PrivateFeedbackFailure.INVALID_RECEIPT)
    return receipt
}
