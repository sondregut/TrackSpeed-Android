package com.trackspeed.android.ui.screens.help

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The caller uses Context.noBackupFilesDir. At most one bounded draft per kind; no account data. */
internal class PrivateFeedbackDraftStore(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    suspend fun load(kind: PrivateFeedbackKind): PrivateFeedbackDraft? = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Opening any composer prunes expired drafts of all three kinds.
            PrivateFeedbackKind.entries.filter { it != kind }.forEach { runCatching { read(it) } }
            read(kind)
        }
    }
    suspend fun save(draft: PrivateFeedbackDraft) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(draft.details.length <= 4_000 && draft.email.length <= 254 && draft.attachments.size <= 3)
            require(draft.attachments.all { it.base64.length <= PrivateFeedbackDraft.MAX_ATTACHMENT_BASE64 })
            root.mkdirs()
            // A delayed persistence job must not replace a newer keystroke or submitted receipt.
            val current = read(draft.kind)
            if (current != null && current.updatedAtMillis > draft.updatedAtMillis) return@withLock
            val bytes = FeedbackJson.encodeToString(draft).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_DRAFT_BYTES)
            val target = file(draft.kind)
            val temporary = File(root, "${draft.kind.wireName}.pending")
            temporary.outputStream().use { it.write(bytes); it.flush() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
    suspend fun remove(kind: PrivateFeedbackKind, expectedId: String? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(kind)
            if (expectedId == null || current?.id == expectedId) {
                val target = file(kind)
                check(!target.exists() || target.delete())
            }
        }
    }
    private fun file(kind: PrivateFeedbackKind) = File(root, "${kind.wireName}.json")
    private fun read(kind: PrivateFeedbackKind): PrivateFeedbackDraft? {
        val file = file(kind)
        if (!file.exists()) return null
        require(file.length() <= MAX_DRAFT_BYTES) { "Feedback draft too large" }
        val draft = FeedbackJson.decodeFromString<PrivateFeedbackDraft>(file.readText())
        require(draft.kind == kind)
        if (now() - draft.updatedAtMillis > TTL_MILLIS) {
            check(file.delete()); return null
        }
        return draft
    }
    companion object {
        const val MAX_DRAFT_BYTES = 3_000_000
        const val TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}
