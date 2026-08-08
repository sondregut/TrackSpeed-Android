package com.trackspeed.android.diagnostics

import android.content.Context
import android.util.Log
import com.trackspeed.android.cloud.AuthService
import com.trackspeed.android.cloud.StorageService
import com.trackspeed.android.cloud.TimingWorkloadCoordinator
import com.trackspeed.android.cloud.dto.DeviceLogUploadRecordDto
import com.trackspeed.android.cloud.safeCloudErrorCode
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Durable, immutable upload queue for device and detection-review logs.
 *
 * The bytes are copied into the queue before the first network request. An
 * interrupted upload therefore survives process death and app relaunch, and a
 * retry always sends the exact snapshot that was originally queued rather than
 * a mutable log file that may have grown in the meantime.
 */
@Singleton
class DurableDeviceLogUploadQueue @Inject constructor(
    @ApplicationContext context: Context,
    private val authService: AuthService,
    private val storageService: StorageService,
    private val supabase: SupabaseClient,
    private val workloadCoordinator: TimingWorkloadCoordinator
) {
    class QueuedForRetryException(cause: Throwable? = null) : IOException(
        "The log was saved on this phone and will upload automatically when connectivity returns.",
        cause
    )

    private val queueDir = File(context.filesDir, "UploadQueues/device_log_upload_queue")
    private val snapshotsDir = File(queueDir, "snapshots")
    private val metadataFile = File(queueDir, "queue.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }
    private val lock = Any()
    private val queueLoadResult = loadQueue()
    private val queue = queueLoadResult.entries.toMutableList()
    private var corruptMetadataNeedsArchiving = queueLoadResult.corrupt
    private val isProcessing = AtomicBoolean(false)

    suspend fun uploadOrQueue(
        bytes: ByteArray,
        storagePath: String,
        localFileName: String,
        category: String,
        coalescingKey: String,
        record: DeviceLogUploadRecordDto? = null,
        createSignedUrl: Boolean
    ): String? = withContext(Dispatchers.IO) {
        val entry = persistImmutableSnapshot(
            bytes = bytes,
            storagePath = storagePath,
            localFileName = localFileName,
            category = category,
            coalescingKey = coalescingKey,
            record = record
        )

        if (workloadCoordinator.isLiveTimingActive || !isProcessing.compareAndSet(false, true)) {
            throw QueuedForRetryException()
        }
        try {
            processEntry(entry.id, createSignedUrl)
                ?: throw QueuedForRetryException()
        } finally {
            isProcessing.set(false)
        }
    }

    suspend fun processQueue() {
        if (workloadCoordinator.isLiveTimingActive) return
        if (!isProcessing.compareAndSet(false, true)) return

        try {
            withContext(Dispatchers.IO) {
                while (!workloadCoordinator.isLiveTimingActive) {
                    val entry = synchronized(lock) {
                        queue.firstOrNull { it.nextAttemptAtMillis <= System.currentTimeMillis() }
                    } ?: return@withContext
                    if (processEntry(entry.id, createSignedUrl = false) == null) {
                        return@withContext
                    }
                }
            }
        } finally {
            isProcessing.set(false)
        }
    }

    fun clearCategory(category: String) {
        synchronized(lock) {
            val removed = queue.filter { it.category == category }
            removed.forEach { snapshotFile(it).delete() }
            if (removed.isNotEmpty()) {
                queue.removeAll(removed.toSet())
                persistQueueLocked()
            }
        }
    }

    internal fun pendingCount(): Int = synchronized(lock) { queue.size }

    private fun persistImmutableSnapshot(
        bytes: ByteArray,
        storagePath: String,
        localFileName: String,
        category: String,
        coalescingKey: String,
        record: DeviceLogUploadRecordDto?
    ): QueuedLogUpload {
        require(bytes.isNotEmpty()) { "Cannot queue an empty device log" }
        require(bytes.size <= MAX_LOG_BYTES) { "Device log exceeds the $MAX_LOG_BYTES byte limit" }

        queueDir.mkdirs()
        snapshotsDir.mkdirs()
        val id = UUID.randomUUID().toString()
        val filename = "$id.log"
        val snapshot = File(snapshotsDir, filename)
        val temporary = File(snapshotsDir, "$filename.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(snapshot)) {
            temporary.copyTo(snapshot, overwrite = true)
            temporary.delete()
        }
        if (snapshot.length() != bytes.size.toLong()) {
            snapshot.delete()
            throw IOException("Failed to persist an immutable device-log snapshot")
        }

        val entry = QueuedLogUpload(
            id = id,
            snapshotFilename = filename,
            storagePath = storagePath,
            localFileName = localFileName,
            category = category,
            coalescingKey = coalescingKey,
            expectedByteCount = bytes.size,
            record = record,
            queuedAtMillis = System.currentTimeMillis(),
            retryCount = 0,
            nextAttemptAtMillis = System.currentTimeMillis()
        )

        synchronized(lock) {
            val superseded = queue.filter { it.coalescingKey == coalescingKey }
            superseded.forEach { snapshotFile(it).delete() }
            queue.removeAll(superseded.toSet())
            queue.add(entry)
            persistQueueLocked()
        }
        Log.i(TAG, "Queued immutable $category log snapshot path=$storagePath")
        return entry
    }

    private suspend fun processEntry(entryId: String, createSignedUrl: Boolean): String? {
        val initial = synchronized(lock) { queue.firstOrNull { it.id == entryId } } ?: return ""
        val file = snapshotFile(initial)
        if (!file.exists() || file.length() != initial.expectedByteCount.toLong()) {
            Log.e(TAG, "Dropping invalid device-log snapshot ${initial.snapshotFilename}")
            removeEntry(initial, deleteFile = true)
            return ""
        }

        return try {
            authService.ensureAnonymousSession()
            var current = initial
            if (!current.storageUploaded) {
                if (!storageService.uploadDeviceLog(current.storagePath, file.readBytes())) {
                    throw IOException("device-logs storage upload failed")
                }
                current = updateEntry(current.copy(storageUploaded = true))
            }

            if (current.record != null && !current.metadataUploaded) {
                supabase.from("device_log_uploads").insert(current.record)
                current = updateEntry(current.copy(metadataUploaded = true))
            }

            val signedUrl = if (createSignedUrl) {
                storageService.signedDeviceLogUrl(current.storagePath)
            } else {
                null
            }
            removeEntry(current, deleteFile = true)
            Log.i(TAG, "Uploaded queued ${current.category} log path=${current.storagePath}")
            signedUrl ?: ""
        } catch (error: Exception) {
            synchronized(lock) {
                val index = queue.indexOfFirst { it.id == entryId }
                if (index >= 0) {
                    val retryCount = queue[index].retryCount + 1
                    queue[index] = queue[index].copy(
                        retryCount = retryCount,
                        nextAttemptAtMillis = System.currentTimeMillis() + retryDelayMillis(retryCount),
                        lastErrorCode = error.safeCloudErrorCode()
                    )
                    persistQueueLocked()
                }
            }
            Log.w(TAG, "Device-log upload deferred: ${error.safeCloudErrorCode()}")
            null
        }
    }

    private fun updateEntry(updated: QueuedLogUpload): QueuedLogUpload {
        synchronized(lock) {
            val index = queue.indexOfFirst { it.id == updated.id }
            if (index >= 0) {
                queue[index] = updated
                persistQueueLocked()
            }
        }
        return updated
    }

    private fun removeEntry(entry: QueuedLogUpload, deleteFile: Boolean) {
        synchronized(lock) {
            queue.removeAll { it.id == entry.id }
            if (deleteFile) snapshotFile(entry).delete()
            persistQueueLocked()
        }
    }

    private fun snapshotFile(entry: QueuedLogUpload): File {
        return File(snapshotsDir, File(entry.snapshotFilename).name)
    }

    private data class QueueLoadResult(
        val entries: List<QueuedLogUpload>,
        val corrupt: Boolean
    )

    private fun loadQueue(): QueueLoadResult {
        return runCatching {
            if (!metadataFile.exists()) return QueueLoadResult(emptyList(), corrupt = false)
            QueueLoadResult(
                entries = json.decodeFromString<List<QueuedLogUpload>>(metadataFile.readText())
                    .filter { entry ->
                    val safeName = File(entry.snapshotFilename).name
                    safeName == entry.snapshotFilename &&
                        File(snapshotsDir, safeName).let { file ->
                            file.exists() && file.length() == entry.expectedByteCount.toLong()
                        }
                    },
                corrupt = false
            )
        }.getOrElse { error ->
            Log.w(TAG, "Failed to load durable device-log queue: ${error.safeCloudErrorCode()}")
            QueueLoadResult(emptyList(), corrupt = true)
        }
    }

    private fun persistQueueLocked() {
        queueDir.mkdirs()
        archiveCorruptMetadataLocked()
        val temporary = File(queueDir, "queue.json.tmp")
        temporary.writeText(json.encodeToString(queue))
        if (!temporary.renameTo(metadataFile)) {
            temporary.copyTo(metadataFile, overwrite = true)
            temporary.delete()
        }
    }

    private fun archiveCorruptMetadataLocked() {
        if (!corruptMetadataNeedsArchiving) return
        corruptMetadataNeedsArchiving = false
        if (!metadataFile.exists()) return
        val archive = File(queueDir, "queue.corrupt-${System.currentTimeMillis()}.json")
        if (!metadataFile.renameTo(archive)) {
            metadataFile.copyTo(archive, overwrite = false)
            metadataFile.delete()
        }
        Log.w(TAG, "Archived unreadable device-log queue metadata as ${archive.name}")
    }

    private fun retryDelayMillis(retryCount: Int): Long {
        val index = min((retryCount - 1).coerceAtLeast(0), RETRY_DELAYS_MS.lastIndex)
        return RETRY_DELAYS_MS[index]
    }

    @Serializable
    private data class QueuedLogUpload(
        val id: String,
        val snapshotFilename: String,
        val storagePath: String,
        val localFileName: String,
        val category: String,
        val coalescingKey: String,
        val expectedByteCount: Int,
        val record: DeviceLogUploadRecordDto? = null,
        val queuedAtMillis: Long,
        val retryCount: Int,
        val nextAttemptAtMillis: Long,
        val storageUploaded: Boolean = false,
        val metadataUploaded: Boolean = false,
        val lastErrorCode: String? = null
    )

    private companion object {
        const val TAG = "DeviceLogUploadQueue"
        const val MAX_LOG_BYTES = 10_000_000
        val RETRY_DELAYS_MS = longArrayOf(
            2_000L,
            5_000L,
            15_000L,
            30_000L,
            60_000L,
            120_000L,
            300_000L,
            600_000L,
            1_800_000L,
            3_600_000L
        )
    }
}
