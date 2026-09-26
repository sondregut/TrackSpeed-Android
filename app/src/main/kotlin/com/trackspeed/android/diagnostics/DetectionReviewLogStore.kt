package com.trackspeed.android.diagnostics

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.trackspeed.android.cloud.DeviceIdProvider
import com.trackspeed.android.cloud.dto.DeviceLogUploadRecordDto
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DetectionReviewLogStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceIdProvider: DeviceIdProvider,
    private val settingsRepository: SettingsRepository,
    private val durableUploadQueue: DurableDeviceLogUploadQueue
) {
    private val mutex = Mutex()
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeContext: LogContext? = null
    private var activeSessionEnded = false

    data class LogContext(
        val sessionId: String?,
        val mode: String,
        val role: String,
        val gateIndex: Int,
        val deviceId: String,
        val startedAtIso: String
    ) {
        val directoryName: String
            get() = sessionId?.lowercase(Locale.US) ?: "unscoped"

        val fileName: String
            get() {
                val safeMode = safePathComponent(mode)
                val safeRole = safePathComponent(role)
                return "${deviceId.take(8)}-$safeMode-$safeRole-gate$gateIndex.log"
            }
    }

    private data class UploadSnapshot(
        val bytes: ByteArray,
        val localFileName: String,
        val storagePath: String,
        val record: DeviceLogUploadRecordDto,
        val reason: String
    )

    suspend fun startSession(
        sessionId: String?,
        mode: String,
        role: String,
        gateIndex: Int
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            startSessionLocked(sessionId, mode, role, gateIndex)
        }
    }

    suspend fun endSession(finalMessage: String? = null) = withContext(Dispatchers.IO) {
        var shouldUpload = false
        mutex.withLock {
            val context = activeContext ?: return@withLock
            if (activeSessionEnded) return@withLock
            if (finalMessage != null) {
                appendLocked(finalMessage)
            }
            appendLocked(
                "[DETECTION-REVIEW-SESSION] event=end session=${context.sessionId ?: "unscoped"} " +
                    "mode=${context.mode} role=${context.role} gateIndex=${context.gateIndex} device=${context.deviceId}"
            )
            activeSessionEnded = true
            shouldUpload = true
        }
        if (shouldUpload && settingsRepository.detectionReviewAutoUploadEnabled.first()) {
            queueUpload("session-end")
        }
    }

    fun endSessionAsync(finalMessage: String? = null) {
        uploadScope.launch {
            endSession(finalMessage)
        }
    }

    fun append(message: String) {
        uploadScope.launch {
            appendInternal(message)
        }
    }

    suspend fun appendForContext(
        sessionId: String?,
        mode: String,
        role: String,
        gateIndex: Int,
        message: String
    ) = withContext(Dispatchers.IO) {
        var uploadReason: String? = null
        mutex.withLock {
            startSessionLocked(sessionId, mode, role, gateIndex)
            appendLocked(message)
            uploadReason = autoUploadReason(message)
        }
        val reason = uploadReason
        if (reason != null && settingsRepository.detectionReviewAutoUploadEnabled.first()) {
            queueUpload(reason)
        }
    }

    fun appendIfActive(message: String) {
        uploadScope.launch {
            mutex.withLock {
                if (activeContext != null && !activeSessionEnded) {
                    appendLocked(message)
                }
            }
        }
    }

    suspend fun exportCurrentLog(): Uri = withContext(Dispatchers.IO) {
        val exportFile = mutex.withLock {
            val logFile = currentOrLatestLogFileLocked()
                ?: throw IllegalStateException("No detection review log has been recorded yet.")
            val exportsDir = File(context.cacheDir, "exports").also { it.mkdirs() }
            val destination = File(
                exportsDir,
                "trackspeed-detection-review-${filenameTimestamp()}-${logFile.name}"
            )
            logFile.copyTo(destination, overwrite = true)
            destination
        }
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )
    }

    suspend fun uploadCurrentLog(): String = withContext(Dispatchers.IO) {
        val snapshot = mutex.withLock {
            makeUploadSnapshotLocked(reason = "manual")
        }
        uploadSnapshot(snapshot, createSignedUrl = true)
            ?: throw IllegalStateException("Detection review log upload did not return a signed URL.")
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val logsDir = logsDirectory()
            if (logsDir.exists()) {
                logsDir.deleteRecursively()
            }
            activeContext = null
            activeSessionEnded = false
        }
        durableUploadQueue.clearCategory(DETECTION_REVIEW_CATEGORY)
    }

    private suspend fun appendInternal(message: String) {
        var uploadReason: String? = null
        mutex.withLock {
            appendLocked(message)
            uploadReason = autoUploadReason(message)
        }
        val reason = uploadReason
        if (reason != null && settingsRepository.detectionReviewAutoUploadEnabled.first()) {
            queueUpload(reason)
        }
    }

    private fun startSessionLocked(
        sessionId: String?,
        mode: String,
        role: String,
        gateIndex: Int
    ) {
        val next = LogContext(
            sessionId = sessionId,
            mode = mode,
            role = role,
            gateIndex = gateIndex,
            deviceId = deviceIdProvider.deviceId,
            startedAtIso = timestamp()
        )
        val current = activeContext
        if (
            current != null &&
            !activeSessionEnded &&
            current.sessionId == next.sessionId &&
            current.mode == next.mode &&
            current.role == next.role &&
            current.gateIndex == next.gateIndex
        ) {
            return
        }

        activeContext = next
        activeSessionEnded = false
        appendLocked(
            "[DETECTION-REVIEW-SESSION] event=start session=${next.sessionId ?: "unscoped"} " +
                "mode=${next.mode} role=${next.role} gateIndex=${next.gateIndex} device=${next.deviceId}"
        )
    }

    private suspend fun queueUpload(reason: String) {
        val snapshot = try {
            mutex.withLock {
                makeUploadSnapshotLocked(reason).also {
                    appendLocked("[DETECTION-REVIEW-UPLOAD] event=queued reason=$reason path=${it.storagePath}")
                }
            }
        } catch (e: Exception) {
            mutex.withLock {
                appendLocked("[DETECTION-REVIEW-UPLOAD] event=skipped reason=$reason error=${logQuoted(e.message ?: e::class.java.simpleName)}")
            }
            return
        }

        uploadScope.launch {
            try {
                uploadSnapshot(snapshot, createSignedUrl = false)
                appendUploadEvent("[DETECTION-REVIEW-UPLOAD] event=complete reason=$reason path=${snapshot.storagePath}")
            } catch (e: Exception) {
                val event = if (e is DurableDeviceLogUploadQueue.QueuedForRetryException) {
                    "deferred"
                } else {
                    "failed"
                }
                appendUploadEvent(
                    "[DETECTION-REVIEW-UPLOAD] event=$event reason=$reason " +
                        "path=${snapshot.storagePath} error=${logQuoted(e.message ?: e::class.java.simpleName)}"
                )
            }
        }
    }

    private suspend fun appendUploadEvent(message: String) {
        mutex.withLock {
            appendLocked(message)
        }
    }

    private fun appendLocked(message: String) {
        runCatching {
            val logFile = currentLogFileLocked()
            logFile.parentFile?.mkdirs()
            if (!logFile.exists()) {
                logFile.writeText(logHeader(), Charsets.UTF_8)
            }
            logFile.appendText("${timestamp()} $message\n", Charsets.UTF_8)
        }
    }

    private suspend fun uploadSnapshot(snapshot: UploadSnapshot, createSignedUrl: Boolean): String? {
        return durableUploadQueue.uploadOrQueue(
            bytes = snapshot.bytes,
            storagePath = snapshot.storagePath,
            localFileName = snapshot.localFileName,
            category = DETECTION_REVIEW_CATEGORY,
            coalescingKey = snapshot.storagePath,
            record = snapshot.record,
            createSignedUrl = createSignedUrl
        )
    }

    private fun makeUploadSnapshotLocked(reason: String): UploadSnapshot {
        val logFile = currentOrLatestLogFileLocked()
            ?: throw IllegalStateException("No detection review log has been recorded yet.")
        val context = activeContext
        val sessionSegment = context?.sessionId?.lowercase(Locale.US) ?: "unscoped"
        val safeMode = safePathComponent(context?.mode ?: "unscoped")
        val safeRole = safePathComponent(context?.role ?: "unknown")
        val safeReason = safePathComponent(reason)
        val uploadFileName = listOf(
            filenameTimestamp(),
            sessionSegment.take(8),
            safeMode,
            safeRole,
            "gate${context?.gateIndex ?: -1}",
            safeReason,
            UUID.randomUUID().toString().take(8).lowercase(Locale.US)
        ).joinToString(separator = "-") + ".log"
        val storagePath = listOf(
            deviceIdProvider.deviceId,
            "detection-review",
            utcDatePath(),
            sessionSegment,
            uploadFileName
        ).joinToString(separator = "/")

        val uploadedAt = timestamp()
        val immutableBytes = logFile.readBytes()
        val record = DeviceLogUploadRecordDto(
            deviceId = deviceIdProvider.deviceId,
            sessionId = context?.sessionId,
            mode = context?.mode,
            role = context?.role,
            gateIndex = context?.gateIndex,
            reason = reason,
            storagePath = storagePath,
            localFileName = logFile.name,
            fileByteCount = immutableBytes.size,
            localSessionStartedAt = context?.startedAtIso,
            localUploadedAt = uploadedAt
        )

        return UploadSnapshot(
            bytes = immutableBytes,
            localFileName = logFile.name,
            storagePath = storagePath,
            record = record,
            reason = reason
        )
    }

    private fun currentLogFileLocked(): File {
        val context = activeContext
        val directory = File(logsDirectory(), context?.directoryName ?: "unscoped")
        return File(directory, context?.fileName ?: "detection-review-unscoped.log")
    }

    private fun currentOrLatestLogFileLocked(): File? {
        if (activeContext != null) {
            val current = currentLogFileLocked()
            if (current.exists()) return current
        }

        val logsDir = logsDirectory()
        if (!logsDir.exists()) return null
        return logsDir.walkTopDown()
            .filter { it.isFile && it.extension == "log" }
            .maxByOrNull { it.lastModified() }
    }

    private fun logHeader(): String {
        val context = activeContext
        return """
            # TrackSpeed detection review log
            # device: ${deviceIdProvider.deviceId}
            # session: ${context?.sessionId ?: "unscoped"}
            # mode: ${context?.mode ?: "unscoped"}
            # role: ${context?.role ?: "unknown"}
            # gateIndex: ${context?.gateIndex ?: "unknown"}
            # sessionStartedAt: ${context?.startedAtIso ?: "unknown"}
            # format: ISO8601 message

        """.trimIndent()
            .plus("\n")
    }

    private fun logsDirectory(): File {
        return File(context.filesDir, "detection_review_logs")
    }

    private fun autoUploadReason(message: String): String? {
        return when {
            message.contains("[DETECTION-MARK]") -> "manual-marker"
            message.contains("[DETECTION-NOTE]") -> "manual-note"
            message.contains("[DETECTION-SESSION-CONTEXT]") -> "session-context"
            else -> null
        }
    }

    private fun logQuoted(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private companion object {
        const val DETECTION_REVIEW_CATEGORY = "detection-review"

        fun safePathComponent(value: String): String {
            val allowed = "abcdefghijklmnopqrstuvwxyz0123456789-_".toSet()
            val sanitized = value.lowercase(Locale.US)
                .map { if (it in allowed) it else '-' }
                .joinToString(separator = "")
                .trim('-')
            return sanitized.ifBlank { "unknown" }
        }

        fun timestamp(): String {
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
        }

        fun filenameTimestamp(): String {
            return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        }

        fun utcDatePath(): String {
            return SimpleDateFormat("yyyy/MM/dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
        }
    }
}
