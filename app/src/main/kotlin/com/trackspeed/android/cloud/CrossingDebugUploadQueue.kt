package com.trackspeed.android.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.trackspeed.android.data.local.entities.RunEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

@Serializable
data class CrossingDebugCapturePayload(
    val sessionId: String?,
    val runId: String,
    val runNumber: Int,
    val gateLabel: String,
    val crossingTimeNanos: Long,
    val configuredGatePosition: Float,
    val detectorPosition: Float,
    val interpolatedDisplayPosition: Float? = null,
    val projectedDisplayPosition: Float? = null,
    val detectorTriggerFramePtsNanos: Long? = null,
    val chosenThumbnailFramePtsNanos: Long? = null,
    val savedThumbnailFramePtsNanos: Long? = null,
    val interpolationAlpha: Float,
    val velocityPxPerSec: Float,
    val blobHeightFraction: Float,
    val blobWidthFraction: Float? = null,
    val crossingDirection: String? = null,
    val workWidth: Int? = null,
    val s0: Float? = null,
    val s1: Float? = null,
    val detectorFrameDurationMs: Float? = null,
    val thumbnailPath: String? = null,
    val frames: List<CrossingDebugFramePayload> = emptyList()
) {
    val normalizedGateLabel: String
        get() = gateLabel.trim().lowercase()
}

@Serializable
data class CrossingDebugFramePayload(
    val imagePath: String,
    val ptsNanos: Long,
    val frameNumber: Long,
    val relativeFrame: Int? = null,
    val chestX: Float,
    val blobHeightFraction: Float,
    val velocityPxPerSec: Float,
    val dtFromPrevMs: Float? = null,
    val anchorMode: String? = null,
    val torsoLeadingEdgeX: Float? = null,
    val legacyEdgeX: Float? = null,
    val centroidX: Float? = null,
    val torsoSegmentWidthPx: Int? = null,
    val timingModel: String? = null,
    val exposureCompensationFactor: Float? = null,
    val exposureDurationMs: Float? = null,
    val contourRowsUsed: Int? = null
)

/**
 * Keep one event-scoped five-frame review window around the saved thumbnail.
 * A late enrichment pass can otherwise combine multiple crossings in the same
 * persisted frame array and upload an ambiguous slate under one run identity.
 */
internal fun canonicalCrossingReviewFrames(
    frames: List<CrossingDebugFramePayload>,
    targetPtsNanos: Long?
): List<CrossingDebugFramePayload> {
    if (frames.isEmpty()) return emptyList()
    val ordered = frames
        .distinctBy { it.frameNumber to it.ptsNanos }
        .sortedBy { it.ptsNanos }
    val target = targetPtsNanos ?: ordered.last().ptsNanos
    val centerIndex = ordered.indices.minWithOrNull(
        compareBy<Int> { index -> ptsDistance(ordered[index].ptsNanos, target) }
            .thenBy { index -> ordered[index].ptsNanos }
    ) ?: return emptyList()

    val firstIndex = (centerIndex - 2).coerceAtLeast(0)
    val lastIndex = (centerIndex + 2).coerceAtMost(ordered.lastIndex)
    return (firstIndex..lastIndex).map { index ->
        ordered[index].copy(relativeFrame = index - centerIndex)
    }
}

private fun ptsDistance(left: Long, right: Long): ULong = if (left >= right) {
    (left - right).toULong()
} else {
    (right - left).toULong()
}

@Singleton
class CrossingDebugUploadQueue @Inject constructor(
    @ApplicationContext context: Context,
    private val raceEventService: RaceEventService,
    private val workloadCoordinator: TimingWorkloadCoordinator
) {
    private val appContext = context.applicationContext
    private val queueDir = File(appContext.filesDir, "UploadQueues/crossing_debug_upload_queue")
    private val metadataFile = File(queueDir, "queue.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private val queue: MutableList<QueuedCapture> = loadPersistedQueue().toMutableList()

    @Volatile
    private var isProcessing = false

    suspend fun submit(payload: CrossingDebugCapturePayload) {
        if (payload.thumbnailPath.isNullOrBlank() && payload.frames.isEmpty()) return

        if (!workloadCoordinator.isLiveTimingActive && hasNetwork()) {
            try {
                raceEventService.upsertCrossingDebugCapture(payload)
                removeQueuedCapture(payload.runId, payload.normalizedGateLabel)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Immediate crossing debug upload failed; queueing: ${e.safeCloudErrorCode()}")
            }
        }

        enqueue(payload)
    }

    suspend fun submitRunCapture(run: RunEntity, gateLabel: String? = null) {
        val payload = buildPayload(run, gateLabel) ?: return
        submit(payload)
    }

    suspend fun processQueue() {
        if (isProcessing) return
        if (workloadCoordinator.isLiveTimingActive) return
        if (!hasNetwork()) return
        isProcessing = true

        try {
            withContext(Dispatchers.IO) {
                pruneInvalidEntries()
                while (true) {
                    if (workloadCoordinator.isLiveTimingActive) return@withContext
                    if (!hasNetwork()) return@withContext
                    val entry = synchronized(lock) {
                        queue.firstOrNull { it.nextAttemptAtMillis <= System.currentTimeMillis() }
                    } ?: return@withContext

                    try {
                        raceEventService.upsertCrossingDebugCapture(entry.payload)
                        removeQueuedCapture(entry.payload.runId, entry.payload.normalizedGateLabel)
                        Log.i(TAG, "Uploaded queued crossing debug run=${entry.payload.runId.take(8)} gate=${entry.payload.normalizedGateLabel}")
                    } catch (e: Exception) {
                        handleFailure(entry, e)
                        return@withContext
                    }
                }
            }
        } finally {
            isProcessing = false
        }
    }

    fun removeQueuedCapturesForRun(runId: String) {
        synchronized(lock) {
            val removed = queue.filter { it.payload.runId == runId }
            removed.forEach(::cleanupFiles)
            if (removed.isNotEmpty()) {
                queue.removeAll(removed.toSet())
                persistQueueLocked()
            }
        }
    }

    fun clearQueue() {
        synchronized(lock) {
            queue.forEach(::cleanupFiles)
            queue.clear()
            if (queueDir.exists()) {
                queueDir.deleteRecursively()
            }
        }
        Log.i(TAG, "Cleared crossing debug upload queue")
    }

    private suspend fun enqueue(payload: CrossingDebugCapturePayload) {
        withContext(Dispatchers.IO) {
            queueDir.mkdirs()
            val entryId = UUID.randomUUID().toString()
            val copiedThumbnailPath = payload.thumbnailPath
                ?.let { copySourceFile(it, "$entryId-thumbnail.jpg") }
            val copiedFrames = payload.frames.mapIndexedNotNull { index, frame ->
                copySourceFile(frame.imagePath, "$entryId-frame-$index.jpg")?.let { copiedPath ->
                    frame.copy(imagePath = copiedPath)
                }
            }

            val queuedPayload = payload.copy(
                thumbnailPath = copiedThumbnailPath,
                frames = copiedFrames
            )
            if (queuedPayload.thumbnailPath.isNullOrBlank() && queuedPayload.frames.isEmpty()) return@withContext

            synchronized(lock) {
                removeQueuedCaptureLocked(queuedPayload.runId, queuedPayload.normalizedGateLabel)
                while (queue.size >= MAX_QUEUE_SIZE) {
                    val removed = queue.removeAt(0)
                    cleanupFiles(removed)
                }
                queue.add(
                    QueuedCapture(
                        id = entryId,
                        payload = queuedPayload,
                        queuedAtMillis = System.currentTimeMillis(),
                        retryCount = 0,
                        nextAttemptAtMillis = System.currentTimeMillis()
                    )
                )
                persistQueueLocked()
            }
            Log.i(TAG, "Enqueued crossing debug capture run=${payload.runId.take(8)} gate=${payload.normalizedGateLabel}")
        }
    }

    private fun buildPayload(run: RunEntity, preferredGateLabel: String?): CrossingDebugCapturePayload? {
        val gateLabel = preferredGateLabel ?: gateLabelForRun(run) ?: return null
        val gatePosition = gatePositionForRun(run, gateLabel)
        val debug = parseThumbnailDebug(
            when (gateLabel.lowercase()) {
                "start" -> run.startThumbnailDebugJson
                else -> run.finishThumbnailDebugJson
            }
        )
        val rawFrames = parseFramePayloads(
            rawValue = run.localGateFramesDataJson,
            fallbackChestX = debug?.detectorPosition ?: gatePosition,
            fallbackVelocity = velocityForRun(run, gateLabel),
            fallbackBlobHeight = debug?.blobHeightFraction ?: 0f
        )
        val thumbnailPath = thumbnailPathForRun(run, gateLabel)

        val detectorTriggerPts = debug?.detectorTriggerFramePtsNanos ?: ptsForRun(run, gateLabel, PtsKind.TRIGGER)
        val chosenPts = debug?.chosenThumbnailFramePtsNanos ?: ptsForRun(run, gateLabel, PtsKind.CHOSEN)
        val savedPts = debug?.savedThumbnailFramePtsNanos ?: ptsForRun(run, gateLabel, PtsKind.SAVED)
        val crossingTime = run.crossingTimestampNanos
            ?: detectorTriggerPts
            ?: savedPts
            ?: chosenPts
            ?: return null
        val alpha = debug?.interpolationAlpha ?: interpolationAlphaForRun(run, gateLabel) ?: 0f
        val velocity = debug?.velocityPxPerSec ?: velocityForRun(run, gateLabel)
        val frames = canonicalCrossingReviewFrames(
            frames = rawFrames,
            targetPtsNanos = savedPts ?: chosenPts ?: detectorTriggerPts ?: crossingTime
        )
        if (thumbnailPath.isNullOrBlank() && frames.isEmpty()) return null
        val blobHeight = frames.maxOfOrNull { it.blobHeightFraction } ?: debug?.blobHeightFraction ?: 0f

        return CrossingDebugCapturePayload(
            sessionId = run.sessionId,
            runId = run.id,
            runNumber = run.runNumber,
            gateLabel = gateLabel,
            crossingTimeNanos = crossingTime,
            configuredGatePosition = debug?.configuredGatePosition ?: gatePosition,
            detectorPosition = debug?.detectorPosition ?: gatePosition,
            interpolatedDisplayPosition = debug?.interpolatedDisplayPosition,
            projectedDisplayPosition = debug?.projectedDisplayPosition,
            detectorTriggerFramePtsNanos = detectorTriggerPts,
            chosenThumbnailFramePtsNanos = chosenPts,
            savedThumbnailFramePtsNanos = savedPts,
            interpolationAlpha = alpha,
            velocityPxPerSec = velocity,
            blobHeightFraction = blobHeight,
            blobWidthFraction = debug?.blobWidthFraction,
            crossingDirection = crossingDirectionForRun(run, gateLabel),
            workWidth = debug?.workWidth ?: workWidthForRun(run, gateLabel),
            s0 = debug?.s0 ?: s0ForRun(run, gateLabel),
            s1 = debug?.s1 ?: s1ForRun(run, gateLabel),
            detectorFrameDurationMs = debug?.detectorFrameDurationMs,
            thumbnailPath = thumbnailPath,
            frames = frames.ifEmpty {
                thumbnailPath?.let {
                    listOf(
                        CrossingDebugFramePayload(
                            imagePath = it,
                            ptsNanos = savedPts ?: chosenPts ?: crossingTime,
                            frameNumber = 0L,
                            relativeFrame = 0,
                            chestX = debug?.detectorPosition ?: gatePosition,
                            blobHeightFraction = blobHeight,
                            velocityPxPerSec = velocity,
                            anchorMode = "saved_thumbnail_fallback",
                            torsoLeadingEdgeX = debug?.detectorPosition ?: gatePosition,
                            timingModel = "saved_thumbnail_fallback"
                        )
                    )
                } ?: emptyList()
            }
        )
    }

    private fun parseFramePayloads(
        rawValue: String?,
        fallbackChestX: Float,
        fallbackVelocity: Float,
        fallbackBlobHeight: Float
    ): List<CrossingDebugFramePayload> {
        if (rawValue.isNullOrBlank()) return emptyList()
        return runCatching {
            var previousPts: Long? = null
            json.parseToJsonElement(rawValue)
                .jsonArray
                .mapNotNull { element ->
                    val obj = element.jsonObject
                    val imagePath = obj["imagePath"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!File(imagePath).exists()) return@mapNotNull null
                    val pts = obj["timestampInterval"]?.jsonPrimitive?.doubleOrNull
                        ?.let { (it * 1_000_000_000.0).toLong() }
                        ?: obj["ptsNanos"]?.jsonPrimitive?.longOrNull
                        ?: 0L
                    val dt = previousPts?.let { (pts - it).toFloat() / 1_000_000f }
                    previousPts = pts
                    val blobHeight = obj["occupancy"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: fallbackBlobHeight
                    CrossingDebugFramePayload(
                        imagePath = imagePath,
                        ptsNanos = pts,
                        frameNumber = obj["frameNumber"]?.jsonPrimitive?.longOrNull ?: 0L,
                        chestX = fallbackChestX,
                        blobHeightFraction = blobHeight,
                        velocityPxPerSec = fallbackVelocity,
                        dtFromPrevMs = dt,
                        anchorMode = "android_local_gate_frame",
                        torsoLeadingEdgeX = fallbackChestX,
                        torsoSegmentWidthPx = obj["longestRun"]?.jsonPrimitive?.intOrNull,
                        timingModel = "android_photo_finish",
                        contourRowsUsed = obj["torsoBottom"]?.jsonPrimitive?.intOrNull?.let { bottom ->
                            obj["torsoTop"]?.jsonPrimitive?.intOrNull?.let { top -> (bottom - top).coerceAtLeast(0) }
                        }
                    )
                }
        }.getOrDefault(emptyList())
    }

    private fun parseThumbnailDebug(rawValue: String?): ThumbnailDebugData? {
        if (rawValue.isNullOrBlank()) return null
        return runCatching {
            val obj = json.parseToJsonElement(rawValue).jsonObject
            ThumbnailDebugData(
                configuredGatePosition = obj.floatFor("configuredGatePosition"),
                detectorPosition = obj.floatFor("detectorPosition"),
                interpolatedDisplayPosition = obj.floatFor("interpolatedDisplayPosition"),
                projectedDisplayPosition = obj.floatFor("projectedDisplayPosition"),
                detectorTriggerFramePtsNanos = obj.longFor("detectorTriggerFramePtsNanos"),
                chosenThumbnailFramePtsNanos = obj.longFor("chosenThumbnailFramePtsNanos"),
                savedThumbnailFramePtsNanos = obj.longFor("savedThumbnailFramePtsNanos"),
                interpolationAlpha = obj.floatFor("interpolationAlpha"),
                velocityPxPerSec = obj.floatFor("velocityPxPerSec"),
                blobHeightFraction = obj.floatFor("blobHeightFraction"),
                blobWidthFraction = obj.floatFor("blobWidthFraction"),
                workWidth = obj.intFor("workBufferW", "workWidth"),
                s0 = obj.floatFor("s0"),
                s1 = obj.floatFor("s1"),
                detectorFrameDurationMs = obj.floatFor("detectorFrameDurationMs")
            )
        }.getOrNull()
    }

    private fun gateLabelForRun(run: RunEntity): String? {
        val role = run.localGateRole?.lowercase()
        return when {
            role?.contains("start") == true -> "start"
            role?.contains("finish") == true -> if (run.numberOfPhones <= 1) "crossing" else "finish"
            role?.contains("lap") == true -> "lap"
            run.numberOfPhones <= 1 -> "crossing"
            else -> "finish"
        }
    }

    private fun thumbnailPathForRun(run: RunEntity, gateLabel: String): String? {
        return when (gateLabel.lowercase()) {
            "start" -> run.startImagePath ?: run.thumbnailPath
            else -> run.finishImagePath ?: run.thumbnailPath
        }?.takeIf { File(it).exists() }
    }

    private fun gatePositionForRun(run: RunEntity, gateLabel: String): Float {
        val value = when (gateLabel.lowercase()) {
            "start" -> run.startGatePosition ?: run.gatePosition
            else -> run.finishGatePosition ?: run.gatePosition
        }
        return value.toFloat().coerceIn(0f, 1f)
    }

    private fun velocityForRun(run: RunEntity, gateLabel: String): Float {
        val value = when (gateLabel.lowercase()) {
            "start" -> run.startCrossingVelocity ?: run.crossingVelocity
            else -> run.finishCrossingVelocity ?: run.crossingVelocity
        }
        return value?.toFloat() ?: 0f
    }

    private fun crossingDirectionForRun(run: RunEntity, gateLabel: String): String? {
        return when (gateLabel.lowercase()) {
            "start" -> run.startCrossingDirection
            else -> run.finishCrossingDirection ?: run.startCrossingDirection
        }
    }

    private fun workWidthForRun(run: RunEntity, gateLabel: String): Int? {
        return when (gateLabel.lowercase()) {
            "start" -> run.startWorkResolutionWidth ?: run.workResolutionWidth
            else -> run.finishWorkResolutionWidth ?: run.workResolutionWidth
        }
    }

    private enum class PtsKind { TRIGGER, CHOSEN, SAVED }

    private fun ptsForRun(run: RunEntity, gateLabel: String, kind: PtsKind): Long? {
        if (gateLabel.lowercase() == "start") return null
        return when (kind) {
            PtsKind.TRIGGER -> run.finishDetectorTriggerFramePts
            PtsKind.CHOSEN -> run.finishChosenThumbnailFramePts
            PtsKind.SAVED -> run.finishSavedThumbnailFramePts
        }
    }

    private fun interpolationAlphaForRun(run: RunEntity, gateLabel: String): Float? {
        return if (gateLabel.lowercase() == "start") null else run.finishInterpolationAlpha?.toFloat()
    }

    private fun s0ForRun(run: RunEntity, gateLabel: String): Float? {
        return if (gateLabel.lowercase() == "start") null else run.finishS0?.toFloat()
    }

    private fun s1ForRun(run: RunEntity, gateLabel: String): Float? {
        return if (gateLabel.lowercase() == "start") null else run.finishS1?.toFloat()
    }

    private fun copySourceFile(sourcePath: String, targetFilename: String): String? {
        return runCatching {
            val source = File(sourcePath)
            if (!source.exists()) return null
            queueDir.mkdirs()
            val target = File(queueDir, targetFilename)
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }.getOrNull()
    }

    private fun pruneInvalidEntries() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val invalid = queue.filter { entry ->
                now - entry.queuedAtMillis > MAX_QUEUE_AGE_MILLIS ||
                    !entry.payload.localFilesExist()
            }
            invalid.forEach(::cleanupFiles)
            if (invalid.isNotEmpty()) {
                queue.removeAll(invalid.toSet())
                persistQueueLocked()
            }
        }
    }

    private fun handleFailure(entry: QueuedCapture, error: Exception) {
        synchronized(lock) {
            val index = queue.indexOfFirst { it.id == entry.id }
            if (index == -1) return
            val retryCount = entry.retryCount + 1
            if (retryCount >= MAX_RETRY_ATTEMPTS || !entry.payload.localFilesExist()) {
                val removed = queue.removeAt(index)
                cleanupFiles(removed)
                Log.w(TAG, "Dropping crossing debug capture after retry=$retryCount: ${error.safeCloudErrorCode()}")
            } else {
                queue[index] = entry.copy(
                    retryCount = retryCount,
                    nextAttemptAtMillis = System.currentTimeMillis() + retryDelayMillis(retryCount)
                )
                Log.w(TAG, "Crossing debug upload failed; retry=$retryCount: ${error.safeCloudErrorCode()}")
            }
            persistQueueLocked()
        }
    }

    private fun removeQueuedCapture(runId: String, gateLabel: String) {
        synchronized(lock) {
            removeQueuedCaptureLocked(runId, gateLabel)
            persistQueueLocked()
        }
    }

    private fun removeQueuedCaptureLocked(runId: String, gateLabel: String) {
        val normalized = gateLabel.trim().lowercase()
        val removed = queue.filter {
            it.payload.runId == runId && it.payload.normalizedGateLabel == normalized
        }
        removed.forEach(::cleanupFiles)
        queue.removeAll(removed.toSet())
    }

    private fun cleanupFiles(entry: QueuedCapture) {
        entry.payload.thumbnailPath?.let { File(it).delete() }
        entry.payload.frames.forEach { File(it.imagePath).delete() }
    }

    private fun CrossingDebugCapturePayload.localFilesExist(): Boolean {
        val thumbnailOk = thumbnailPath?.let { File(it).exists() } ?: true
        return thumbnailOk && frames.all { File(it.imagePath).exists() }
    }

    private fun loadPersistedQueue(): List<QueuedCapture> {
        return runCatching {
            if (!metadataFile.exists()) return emptyList()
            json.decodeFromString<List<QueuedCapture>>(metadataFile.readText())
        }.getOrElse { error ->
            Log.w(TAG, "Failed to load crossing debug queue: ${error.safeCloudErrorCode()}")
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
    private data class QueuedCapture(
        val id: String,
        val payload: CrossingDebugCapturePayload,
        val queuedAtMillis: Long,
        val retryCount: Int,
        val nextAttemptAtMillis: Long
    )

    private data class ThumbnailDebugData(
        val configuredGatePosition: Float?,
        val detectorPosition: Float?,
        val interpolatedDisplayPosition: Float?,
        val projectedDisplayPosition: Float?,
        val detectorTriggerFramePtsNanos: Long?,
        val chosenThumbnailFramePtsNanos: Long?,
        val savedThumbnailFramePtsNanos: Long?,
        val interpolationAlpha: Float?,
        val velocityPxPerSec: Float?,
        val blobHeightFraction: Float?,
        val blobWidthFraction: Float?,
        val workWidth: Int?,
        val s0: Float?,
        val s1: Float?,
        val detectorFrameDurationMs: Float?
    )

    private fun JsonObject.floatFor(vararg keys: String): Float? {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.doubleOrNull?.toFloat()
                ?: this[key]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
        }
    }

    private fun JsonObject.intFor(vararg keys: String): Int? {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.intOrNull
                ?: this[key]?.jsonPrimitive?.doubleOrNull?.toInt()
                ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        }
    }

    private fun JsonObject.longFor(vararg keys: String): Long? {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.longOrNull
                ?: this[key]?.jsonPrimitive?.doubleOrNull?.toLong()
                ?: this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        }
    }

    companion object {
        private const val TAG = "CrossingDebugUploadQueue"
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
