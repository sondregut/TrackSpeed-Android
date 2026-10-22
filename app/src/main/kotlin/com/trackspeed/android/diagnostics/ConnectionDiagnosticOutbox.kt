package com.trackspeed.android.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Transport returns a server row receipt; a local enqueue is never an upload receipt. */
interface ConnectionDiagnosticUploader {
    suspend fun upload(record: ConnectionDiagnosticRecord): ConnectionDiagnosticServerReceipt
}

data class ConnectionDiagnosticServerReceipt(val id: String, val userId: String, val createdAt: String)
@Serializable enum class ConnectionDiagnosticUploadError { AUTH_UNAVAILABLE, AUTH_CHANGED, NETWORK, TIMEOUT, DENIED, SCHEMA, UNCONFIRMED, UNKNOWN }
class ConnectionDiagnosticUploadException(val code: ConnectionDiagnosticUploadError) : IOException(code.name)

/** Standalone bounded outbox. All callers must execute file operations on an IO dispatcher. */
class ConnectionDiagnosticOutbox(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limits: Limits = Limits()
) {
    data class Limits(
        val maxEvents: Int = 128,
        val maxBytes: Int = 512 * 1024,
        val maxEventBytes: Int = 4 * 1024,
        val ttlMs: Long = 7L * 24 * 60 * 60 * 1000,
        val maxReceipts: Int = 128
    ) {
        init { require(maxEvents > 0 && maxBytes > 0 && maxEventBytes > 0 && ttlMs > 0 && maxReceipts > 0) }
    }
    enum class EnqueueResult { QUEUED, DUPLICATE, TOO_LARGE, FULL, INVALID }
    @Serializable enum class Stage { PENDING, IN_FLIGHT }
    @Serializable enum class ReceiptStatus { ACCEPTED, EXPIRED, EVICTED, DISABLED }
    @Serializable data class Entry(
        val record: ConnectionDiagnosticRecord, val queuedAtMs: Long, val bytes: Int,
        val stage: Stage = Stage.PENDING, val retryCount: Int = 0, val nextAttemptAtMs: Long,
        val lastError: ConnectionDiagnosticUploadError? = null
    )
    @Serializable data class Receipt(
        val eventId: String, val ownerId: String, val status: ReceiptStatus,
        val observedAtMs: Long, val serverCreatedAt: String? = null
    )
    @Serializable private data class State(
        val version: Int = 1, val entries: List<Entry> = emptyList(),
        val receipts: List<Receipt> = emptyList(), val droppedCount: Long = 0
    )
    data class Snapshot(val entries: List<Entry>, val receipts: List<Receipt>, val droppedCount: Long)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val lock = Any()
    private val processing = AtomicBoolean(false)
    private val activeIds = mutableSetOf<String>()
    private val file = File(directory, "outbox.json")
    private var state = load()

    fun snapshot(ownerId: String): Snapshot = synchronized(lock) {
        Snapshot(state.entries.filter { it.record.userId == ownerId }, state.receipts.filter { it.ownerId == ownerId }, state.droppedCount)
    }

    fun nextAttemptAt(ownerId: String): Long? = synchronized(lock) {
        state.entries.filter { it.record.userId == ownerId }.minOfOrNull { it.nextAttemptAtMs }
    }

    fun expire() = synchronized(lock) {
        val now = clock()
        val corrupt = File(directory, "outbox.corrupt")
        if (corrupt.isFile && now >= corrupt.lastModified() && now - corrupt.lastModified() >= limits.ttlMs) corrupt.delete()
        val updated = prune(state, now)
        if (updated != state) commit(updated)
    }

    fun enqueue(record: ConnectionDiagnosticRecord): EnqueueResult = synchronized(lock) {
        if (!record.isSanitized()) return EnqueueResult.INVALID
        if (state.entries.any { it.record.id == record.id } || state.receipts.any { it.eventId == record.id }) return EnqueueResult.DUPLICATE
        val bytes = json.encodeToString(record).toByteArray(Charsets.UTF_8).size
        if (bytes > limits.maxEventBytes || bytes > limits.maxBytes) return EnqueueResult.TOO_LARGE
        val now = clock()
        var updated = prune(state, now)
        while (updated.entries.size >= limits.maxEvents || updated.entries.sumOf { it.bytes } + bytes > limits.maxBytes) {
            val oldest = updated.entries.firstOrNull { it.record.id !in activeIds } ?: return EnqueueResult.FULL
            updated = drop(updated, oldest, ReceiptStatus.EVICTED, now)
        }
        commit(updated.copy(entries = updated.entries + Entry(record, now, bytes, nextAttemptAtMs = now)))
        EnqueueResult.QUEUED
    }

    /** Disable means stop sending and discard only this category; an executing request remains durable until it ends. */
    fun discardPending() = synchronized(lock) {
        val now = clock()
        var updated = state
        state.entries.filter { it.record.id !in activeIds }.forEach { updated = drop(updated, it, ReceiptStatus.DISABLED, now) }
        commit(updated)
    }

    suspend fun drain(
        uploader: ConnectionDiagnosticUploader,
        currentOwner: () -> String?,
        uploadsAllowed: () -> Boolean,
        maxBatch: Int = 16
    ) {
        if (!processing.compareAndSet(false, true)) return
        try {
            repeat(maxBatch.coerceIn(0, limits.maxEvents)) {
                if (!uploadsAllowed()) return
                val owner = currentOwner() ?: return
                val entry = synchronized(lock) {
                    val now = clock()
                    val cleaned = prune(state, now)
                    if (cleaned != state) commit(cleaned)
                    val due = state.entries.firstOrNull { it.record.userId == owner && it.nextAttemptAtMs <= now } ?: return
                    val claimed = due.copy(stage = Stage.IN_FLIGHT)
                    commit(state.copy(entries = state.entries.map { if (it.record.id == due.record.id) claimed else it }))
                    activeIds.add(claimed.record.id)
                    claimed
                }
                try {
                    if (currentOwner() != owner) throw ConnectionDiagnosticUploadException(ConnectionDiagnosticUploadError.AUTH_CHANGED)
                    if (!uploadsAllowed()) return
                    val receipt = uploader.upload(entry.record)
                    if (receipt.id != entry.record.id || receipt.userId != owner || receipt.createdAt.isBlank()) {
                        throw ConnectionDiagnosticUploadException(ConnectionDiagnosticUploadError.UNCONFIRMED)
                    }
                    try { Instant.parse(receipt.createdAt) } catch (_: Exception) {
                        throw ConnectionDiagnosticUploadException(ConnectionDiagnosticUploadError.UNCONFIRMED)
                    }
                    synchronized(lock) {
                        commit(state.copy(
                            entries = state.entries.filterNot { it.record.id == entry.record.id },
                            receipts = boundedReceipts(state.receipts + Receipt(entry.record.id, owner, ReceiptStatus.ACCEPTED, clock(), receipt.createdAt), clock())
                        ))
                    }
                } catch (cancelled: CancellationException) {
                    // IN_FLIGHT stays durable. The identical UUID is retried after restart/wakeup.
                    throw cancelled
                } catch (error: Exception) {
                    synchronized(lock) {
                        val count = (entry.retryCount + 1).coerceAtMost(30)
                        val code = (error as? ConnectionDiagnosticUploadException)?.code ?: ConnectionDiagnosticUploadError.UNKNOWN
                        commit(state.copy(entries = state.entries.map {
                            if (it.record.id == entry.record.id) it.copy(stage = Stage.PENDING, retryCount = count,
                                nextAttemptAtMs = clock() + retryDelayMs(count), lastError = code) else it
                        }))
                    }
                } finally {
                    synchronized(lock) { activeIds.remove(entry.record.id) }
                }
            }
        } finally { processing.set(false) }
    }

    private fun prune(initial: State, now: Long): State {
        var updated = initial.copy(receipts = boundedReceipts(initial.receipts, now))
        initial.entries.filter { it.record.id !in activeIds && now >= it.queuedAtMs && now - it.queuedAtMs >= limits.ttlMs }
            .forEach { updated = drop(updated, it, ReceiptStatus.EXPIRED, now) }
        return updated
    }

    private fun drop(initial: State, entry: Entry, reason: ReceiptStatus, now: Long): State = initial.copy(
        entries = initial.entries.filterNot { it.record.id == entry.record.id },
        receipts = boundedReceipts(initial.receipts + Receipt(entry.record.id, entry.record.userId, reason, now), now),
        droppedCount = initial.droppedCount + 1
    )

    private fun boundedReceipts(receipts: List<Receipt>, now: Long): List<Receipt> = receipts
        .filter { now < it.observedAtMs || now - it.observedAtMs < limits.ttlMs }.takeLast(limits.maxReceipts)

    private fun commit(updated: State) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("diagnostic_outbox_directory")
        val bytes = json.encodeToString(updated).toByteArray(Charsets.UTF_8)
        val temporary = File(directory, "outbox.tmp")
        FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        state = updated
    }

    private fun load(): State {
        if (!file.isFile) return State()
        // Bound reads too. Keep a single bounded corrupt copy rather than endlessly accumulating archives.
        return try {
            if (file.length() > limits.maxBytes.toLong() + limits.maxEvents * 1024L + limits.maxReceipts * 512L + 4096L) throw IOException("diagnostic_outbox_size")
            val loaded = json.decodeFromString<State>(file.readText())
            require(loaded.version == 1 && loaded.entries.size <= limits.maxEvents)
            require(loaded.entries.sumOf { it.bytes.toLong() } <= limits.maxBytes)
            require(loaded.entries.all { it.record.isSanitized() && it.bytes in 1..limits.maxEventBytes && json.encodeToString(it.record).toByteArray(Charsets.UTF_8).size == it.bytes })
            require(loaded.entries.all { it.retryCount in 0..30 })
            require(loaded.entries.map { it.record.id }.distinct().size == loaded.entries.size)
            loaded.copy(receipts = loaded.receipts.takeLast(limits.maxReceipts))
        } catch (_: Exception) {
            // No payload or exception text is logged. The owned diagnostic file is bounded disposable telemetry.
            if (file.length() <= limits.maxBytes.toLong() * 2) Files.move(file.toPath(), File(directory, "outbox.corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING)
            else file.delete()
            State()
        }
    }

    companion object {
        internal fun retryDelayMs(retry: Int): Long = (2_000L * (1L shl (retry - 1).coerceIn(0, 10))).coerceAtMost(3_600_000L)
    }
}
