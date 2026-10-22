package com.trackspeed.android.diagnostics

import android.content.Context
import android.os.Build
import com.posthog.PostHog
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.cloud.TimingWorkloadCoordinator
import com.trackspeed.android.cloud.safeCloudErrorCode
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standalone connection telemetry. No auth bootstrap, raw logs, or protocol work.
 * record() only offers to a bounded memory channel; durable state/receipt is in snapshot().
 * Call processQueue() on auth/resume/connectivity/idle changes when wiring this service.
 */
@Singleton
class ConnectionDiagnosticsService @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
    private val supabase: SupabaseClient,
    private val workload: TimingWorkloadCoordinator
) {
    enum class Submission { ACCEPTED_FOR_PERSISTENCE, DISABLED, NO_AUTH, BACKPRESSURE }
    private data class Pending(val event: ConnectionDiagnosticEvent, val ownerId: UUID)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val samplingGate = ConnectionDiagnosticSamplingGate { scope.launch { processQueue() } }
    private val events = Channel<Pending>(64)
    private val processing = Mutex()
    private val directory = File(context.noBackupFilesDir, "ConnectionDiagnostics")
    private val outbox by lazy { ConnectionDiagnosticOutbox(directory) }
    @Volatile private var collectionAllowed = false
    @Volatile private var autoUploadAllowed = false
    private var retryJob: Job? = null
    private val environment = ConnectionDiagnosticEnvironment(
        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.MODEL,
        Build.VERSION.RELEASE, Build.VERSION.SDK_INT, BuildConfig.BUILD_MARKER, BuildConfig.SOURCE_FINGERPRINT
    )

    init {
        scope.launch {
            combine(settings.detectionDiagnosticsEnabled, settings.detectionReviewAutoUploadEnabled) { collect, upload -> collect to upload }
                .collect { (collect, upload) ->
                    collectionAllowed = collect
                    autoUploadAllowed = upload
                    if (!collect) safely { outbox.discardPending() }
                    processQueue()
                }
        }
        scope.launch {
            for (pending in events) {
                if (!collectionAllowed || optedOut()) continue
                // Identity was captured at submission. Never reassign queued telemetry after account switching.
                safely {
                    outbox.enqueue(pending.event.bind(pending.ownerId, environment))
                }
                processQueue()
            }
        }
    }

    fun record(event: ConnectionDiagnosticEvent): Submission {
        if (!collectionAllowed || optedOut()) return Submission.DISABLED
        val owner = currentOwner()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return Submission.NO_AUTH
        return if (events.trySend(Pending(event, owner)).isSuccess) Submission.ACCEPTED_FOR_PERSISTENCE else Submission.BACKPRESSURE
    }

    suspend fun snapshot(): ConnectionDiagnosticOutbox.Snapshot? = withContext(Dispatchers.IO) {
        currentOwner()?.let { outbox.snapshot(it) }
    }

    suspend fun processQueue(): Unit = withContext(Dispatchers.IO) {
        processing.withLock {
            safely {
                outbox.expire()
                outbox.drain(makeUploader(), ::currentOwner, ::mayUpload)
                retryJob?.cancel()
                retryJob = null
                if (collectionAllowed && autoUploadAllowed && !optedOut()) {
                    val owner = currentOwner()
                    val due = owner?.let(outbox::nextAttemptAt)
                    if (due != null) {
                        val delayMs = if (workload.isLiveTimingActive || samplingGate.isActive) 5_000L
                            else (due - System.currentTimeMillis()).coerceIn(1_000L, 3_600_000L)
                        retryJob = scope.launch {
                            delay(delayMs)
                            // A separate scope child can safely cancel the completed timer it replaces.
                            scope.launch { processQueue() }
                        }
                    }
                }
            }
        }
    }

    private fun currentOwner(): String? = supabase.auth.currentSessionOrNull()?.user?.id
    private fun optedOut(): Boolean = runCatching { PostHog.isOptOut() }.getOrDefault(true)
    private fun mayUpload(): Boolean = collectionAllowed && autoUploadAllowed && !optedOut() &&
        !workload.isLiveTimingActive && !samplingGate.isActive

    private fun makeUploader() = object : ConnectionDiagnosticUploader {
        override suspend fun upload(record: ConnectionDiagnosticRecord): ConnectionDiagnosticServerReceipt {
            fun checkOwner() {
                if (currentOwner() != record.userId) throw ConnectionDiagnosticUploadException(ConnectionDiagnosticUploadError.AUTH_CHANGED)
            }
            checkOwner()
            try {
                // No upsert: the existing table permits INSERT/SELECT own, not UPDATE.
                val row = supabase.from(TABLE).insert(record) { select() }.decodeSingle<ServerRow>()
                return row.receiptFor(record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                checkOwner()
                // Handles a lost response or primary-key conflict without interpreting message text as proof.
                val existing = try {
                    supabase.from(TABLE).select {
                        filter { eq("id", record.id); eq("user_id", record.userId) }
                    }.decodeSingleOrNull<ServerRow>()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (existing != null) return existing.receiptFor(record)
                throw ConnectionDiagnosticUploadException(when (error.safeCloudErrorCode()) {
                    "timeout" -> ConnectionDiagnosticUploadError.TIMEOUT
                    "network_unavailable" -> ConnectionDiagnosticUploadError.NETWORK
                    "rls_denied", "auth_denied" -> ConnectionDiagnosticUploadError.DENIED
                    "auth_token_invalid" -> ConnectionDiagnosticUploadError.AUTH_UNAVAILABLE
                    "schema_cache", "missing_column" -> ConnectionDiagnosticUploadError.SCHEMA
                    else -> ConnectionDiagnosticUploadError.UNCONFIRMED
                })
            }
        }
    }

    @Serializable private data class ServerRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("attempt_id") val attemptId: String,
        val phase: String,
        val outcome: String,
        @SerialName("created_at") val createdAt: String
    ) {
        fun receiptFor(record: ConnectionDiagnosticRecord): ConnectionDiagnosticServerReceipt {
            if (id != record.id || userId != record.userId || attemptId != record.attemptId || phase != record.phase || outcome != record.outcome) {
                throw ConnectionDiagnosticUploadException(ConnectionDiagnosticUploadError.UNCONFIRMED)
            }
            return ConnectionDiagnosticServerReceipt(id, userId, createdAt)
        }
    }

    private suspend fun safely(action: suspend () -> Unit) {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Telemetry failure must not escape into timing; no raw error logging. */ }
    }

    private companion object { const val TABLE = "connection_diagnostics" }
}
