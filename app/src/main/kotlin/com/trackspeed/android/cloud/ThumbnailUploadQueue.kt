package com.trackspeed.android.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.trackspeed.android.cloud.dto.CrossingDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Durable retry queue for crossing thumbnails.
 *
 * Mirrors the iOS ThumbnailUploadQueue behavior at the Android service layer:
 * failed thumbnail uploads are persisted on disk and later retried with a
 * crossing upsert keyed by session/run/gate/device, so retries update the
 * original crossing instead of creating duplicates.
 */
@Singleton
class ThumbnailUploadQueue @Inject constructor(
    @ApplicationContext context: Context,
    private val storageService: StorageService,
    private val raceEventService: RaceEventService,
    private val deviceIdProvider: DeviceIdProvider,
    private val workloadCoordinator: TimingWorkloadCoordinator
) {
    private val appContext = context.applicationContext
    private val queueDir = File(appContext.filesDir, "UploadQueues/thumbnail_queue")
    private val metadataFile = File(queueDir, "queue.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private val queue: MutableList<QueuedThumbnailUpload> = loadPersistedQueue().toMutableList()

    @Volatile
    private var isProcessing = false

    suspend fun enqueue(
        sessionId: String,
        runId: String,
        gateRole: String,
        crossingTimeNanos: Long,
        imageData: ByteArray
    ) {
        withContext(Dispatchers.IO) {
            queueDir.mkdirs()
            synchronized(lock) {
                dropSupersededStartUploadLocked(sessionId, runId, gateRole, crossingTimeNanos)
                trimQueueIfNeededLocked()
            }

            val filename = "${UUID.randomUUID()}.jpg"
            File(queueDir, filename).writeBytes(imageData)
            val entry = QueuedThumbnailUpload(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                runId = runId,
                gateRole = gateRole,
                tempFilename = filename,
                queuedAtMillis = System.currentTimeMillis(),
                retryCount = 0,
                nextAttemptAtMillis = System.currentTimeMillis(),
                crossingTimeNanos = crossingTimeNanos,
                deviceId = deviceIdProvider.deviceId
            )

            synchronized(lock) {
                queue.add(entry)
                persistQueueLocked()
            }
            Log.i(TAG, "Enqueued crossing thumbnail retry gate=$gateRole run=${runId.take(8)}")
        }
    }

    suspend fun processQueue() {
        if (isProcessing) return
        if (workloadCoordinator.isLiveTimingActive) return
        if (!hasNetwork()) return
        isProcessing = true

        try {
            withContext(Dispatchers.IO) {
                pruneExpiredAndMissingFiles()

                while (true) {
                    if (workloadCoordinator.isLiveTimingActive) return@withContext
                    if (!hasNetwork()) return@withContext
                    val entry = synchronized(lock) {
                        queue.firstOrNull { it.nextAttemptAtMillis <= System.currentTimeMillis() }
                    } ?: return@withContext

                    val file = File(queueDir, entry.tempFilename)
                    if (!file.exists()) {
                        removeEntry(entry, deleteFile = false)
                        continue
                    }

                    try {
                        val path = "crossings/${entry.sessionId}/${UUID.randomUUID()}.jpg"
                        val uploaded = storageService.uploadObject(
                            bucket = "race-photos",
                            path = path,
                            data = file.readBytes()
                        )
                        if (!uploaded) {
                            throw IllegalStateException("Storage upload returned false")
                        }

                        raceEventService.insertCrossing(
                            CrossingDto(
                                sessionId = entry.sessionId,
                                runId = entry.runId,
                                gateRole = entry.gateRole,
                                deviceId = entry.deviceId,
                                crossingTimeNanos = entry.crossingTimeNanos,
                                thumbnailUrl = path
                            )
                        )

                        removeEntry(entry, deleteFile = true)
                        Log.i(TAG, "Uploaded queued crossing thumbnail gate=${entry.gateRole} run=${entry.runId.take(8)}")
                    } catch (e: Exception) {
                        handleRetryFailure(entry, e)
                        return@withContext
                    }
                }
            }
        } finally {
            isProcessing = false
        }
    }

    fun removeUploadsForRun(runId: String) {
        synchronized(lock) {
            val removed = queue.filter { it.runId == runId }
            removed.forEach { File(queueDir, it.tempFilename).delete() }
            if (removed.isNotEmpty()) {
                queue.removeAll(removed.toSet())
                persistQueueLocked()
            }
        }
    }

    fun clearQueue() {
        synchronized(lock) {
            queue.forEach { File(queueDir, it.tempFilename).delete() }
            queue.clear()
            if (queueDir.exists()) {
                queueDir.deleteRecursively()
            }
        }
        Log.i(TAG, "Cleared thumbnail upload queue")
    }

    private fun handleRetryFailure(entry: QueuedThumbnailUpload, error: Exception) {
        synchronized(lock) {
            val index = queue.indexOfFirst { it.id == entry.id }
            if (index == -1) return

            val retryCount = entry.retryCount + 1
            if (retryCount >= MAX_RETRY_ATTEMPTS) {
                val removed = queue.removeAt(index)
                File(queueDir, removed.tempFilename).delete()
                Log.w(TAG, "Dropping queued thumbnail after $retryCount attempts: ${error.safeCloudErrorCode()}")
            } else {
                queue[index] = entry.copy(
                    retryCount = retryCount,
                    nextAttemptAtMillis = System.currentTimeMillis() + retryDelayMillis(retryCount)
                )
                Log.w(TAG, "Queued thumbnail upload failed; retry=$retryCount: ${error.safeCloudErrorCode()}")
            }
            persistQueueLocked()
        }
    }

    private fun pruneExpiredAndMissingFiles() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val expiredOrMissing = queue.filter { entry ->
                now - entry.queuedAtMillis > MAX_QUEUE_AGE_MILLIS ||
                    !File(queueDir, entry.tempFilename).exists()
            }
            if (expiredOrMissing.isNotEmpty()) {
                expiredOrMissing.forEach { File(queueDir, it.tempFilename).delete() }
                queue.removeAll(expiredOrMissing.toSet())
                persistQueueLocked()
            }
        }
    }

    private fun removeEntry(entry: QueuedThumbnailUpload, deleteFile: Boolean) {
        synchronized(lock) {
            queue.removeAll { it.id == entry.id }
            if (deleteFile) {
                File(queueDir, entry.tempFilename).delete()
            }
            persistQueueLocked()
        }
    }

    private fun dropSupersededStartUploadLocked(
        sessionId: String,
        runId: String,
        gateRole: String,
        crossingTimeNanos: Long
    ) {
        if (gateRole != "start") return
        val staleEntries = queue.filter { entry ->
            entry.sessionId == sessionId &&
                entry.runId == runId &&
                entry.gateRole == gateRole &&
                entry.crossingTimeNanos < crossingTimeNanos
        }
        staleEntries.forEach { File(queueDir, it.tempFilename).delete() }
        queue.removeAll(staleEntries.toSet())
    }

    private fun trimQueueIfNeededLocked() {
        while (queue.size >= MAX_QUEUE_SIZE) {
            val removed = queue.removeAt(0)
            File(queueDir, removed.tempFilename).delete()
        }
        persistQueueLocked()
    }

    private fun loadPersistedQueue(): List<QueuedThumbnailUpload> {
        return runCatching {
            if (!metadataFile.exists()) return emptyList()
            json.decodeFromString<List<QueuedThumbnailUpload>>(metadataFile.readText())
        }.getOrElse { error ->
            Log.w(TAG, "Failed to load thumbnail upload queue: ${error.safeCloudErrorCode()}")
            emptyList()
        }
    }

    private fun persistQueueLocked() {
        queueDir.mkdirs()
        metadataFile.writeText(json.encodeToString(queue))
    }

    private fun hasNetwork(): Boolean {
        return try {
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: SecurityException) {
            true
        }
    }

    private fun retryDelayMillis(retryCount: Int): Long {
        val index = min((retryCount - 1).coerceAtLeast(0), RETRY_DELAYS_MS.lastIndex)
        return RETRY_DELAYS_MS[index]
    }

    @Serializable
    private data class QueuedThumbnailUpload(
        val id: String,
        val sessionId: String,
        val runId: String,
        val gateRole: String,
        val tempFilename: String,
        val queuedAtMillis: Long,
        val retryCount: Int,
        val nextAttemptAtMillis: Long,
        val crossingTimeNanos: Long,
        val deviceId: String
    )

    companion object {
        private const val TAG = "ThumbnailUploadQueue"
        private const val MAX_RETRY_ATTEMPTS = 12
        private const val MAX_QUEUE_SIZE = 50
        private const val MAX_QUEUE_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private val RETRY_DELAYS_MS = longArrayOf(
            2_000L,
            5_000L,
            15_000L,
            30_000L,
            60_000L,
            120_000L,
            300_000L,
            600_000L,
            900_000L,
            1_800_000L,
            3_600_000L,
            7_200_000L
        )
    }
}
