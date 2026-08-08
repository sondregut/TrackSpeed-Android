package com.trackspeed.android.cloud

import android.os.Build
import android.util.Log
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.cloud.dto.CrossingDto
import com.trackspeed.android.cloud.dto.CrossingDebugCaptureDto
import com.trackspeed.android.cloud.dto.CrossingDebugFrameMetadataDto
import com.trackspeed.android.cloud.dto.CrossingReviewMarkDto
import com.trackspeed.android.cloud.dto.PairingRequestDto
import com.trackspeed.android.cloud.dto.RaceEventDto
import com.trackspeed.android.cloud.dto.RaceEventWithoutRunIdDto
import com.trackspeed.android.cloud.dto.TimingSessionParticipantDto
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for interacting with Supabase race_events and pairing_requests tables.
 * Provides real-time subscriptions and CRUD operations for cross-device timing.
 */
@Singleton
class RaceEventService @Inject constructor(
    @ApplicationContext context: android.content.Context,
    private val supabase: SupabaseClient,
    private val authService: AuthService,
    private val deviceIdProvider: DeviceIdProvider,
    private val storageService: StorageService
) {

    companion object {
        private const val TAG = "RaceEventService"
        private const val TABLE_RACE_EVENTS = "race_events"
        private const val TABLE_CROSSINGS = "crossings"
        private const val TABLE_CROSSING_DEBUG_CAPTURES = "crossing_debug_captures"
        private const val TABLE_CROSSING_REVIEW_MARKS = "crossing_review_marks"
        private const val TABLE_PAIRING_REQUESTS = "pairing_requests"
        private const val TABLE_TIMING_SESSION_PARTICIPANTS = "timing_session_participants"
        private const val TABLE_DEVICE_TOKENS = "device_tokens"
        private val RACE_EVENT_RETRY_DELAYS_MS = longArrayOf(
            1_000L,
            2_000L,
            5_000L,
            10_000L,
            30_000L,
            60_000L
        )

        /**
         * Stable cross-platform identity used by iOS for durable race-event
         * retries. Setting UUID version/variant bits exactly like Swift keeps
         * Android and iOS idempotency semantics identical.
         */
        internal fun stableRaceEventId(
            sessionId: String,
            runId: String?,
            eventType: String,
            crossingTimeNanos: Long,
            deviceId: String
        ): String {
            val seed = listOf(
                "race_event",
                sessionId,
                runId?.lowercase() ?: "no-run",
                eventType.lowercase(),
                deviceId,
                crossingTimeNanos.toString()
            ).joinToString("|")
            return stableUuid(seed)
        }

        private fun stableUuid(seed: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray(Charsets.UTF_8))
                .copyOfRange(0, 16)
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(bytes)
            return UUID(buffer.long, buffer.long).toString()
        }
    }

    private val outboxFile = File(context.filesDir, "UploadQueues/race_event_outbox.json")
    private val outboxJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }
    private val outboxLock = Any()
    private val outboxLoadResult = loadRaceEventOutbox()
    private val pendingRaceEvents = outboxLoadResult.events.toMutableList()
    private var corruptOutboxNeedsArchiving = outboxLoadResult.corrupt
    private val outboxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var outboxFlushInProgress = false
    private var outboxRetryJob: Job? = null
    private var outboxRetryAttempt = 0

    init {
        if (pendingRaceEvents.isNotEmpty()) {
            outboxScope.launch { processPendingRaceEvents("service_init") }
        }
    }

    // Sessions for which this device has already upserted a participant row
    // for the current authenticated user. Reset when the user signs out.
    private val registeredSessions: MutableSet<String> = HashSet()

    /**
     * Upsert a row into `timing_session_participants` for (sessionId, userId, deviceId).
     *
     * RLS on race_events/crossings only allows authenticated users that have
     * joined the session, so this must be called before any read/write/subscribe
     * targeting either table. No-op if the same session was already registered
     * for the current authenticated user during this app session.
     */
    suspend fun ensureSessionParticipant(sessionId: String) {
        val userId = authService.currentUserId
        if (userId.isNullOrEmpty()) {
            Log.w(TAG, "Cannot register participant — not authenticated (sessionId=$sessionId)")
            return
        }

        synchronized(registeredSessions) {
            if (registeredSessions.contains(sessionId)) return
        }

        try {
            supabase.from(TABLE_TIMING_SESSION_PARTICIPANTS)
                .upsert(
                    TimingSessionParticipantDto(
                        sessionId = sessionId,
                        userId = userId,
                        deviceId = deviceIdProvider.deviceId
                    )
                ) {
                    onConflict = "session_id,user_id"
                }
            synchronized(registeredSessions) { registeredSessions.add(sessionId) }
            Log.d(TAG, "Registered timing session participant: session=$sessionId user=${userId.take(8)}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register timing session participant for $sessionId: ${e.safeCloudErrorCode()}")
            throw e
        }
    }

    /**
     * Forget all locally tracked participant registrations. Call on sign out so
     * the next user re-upserts under their own user_id.
     */
    fun clearRegisteredSessions() {
        synchronized(registeredSessions) { registeredSessions.clear() }
    }

    /**
     * Register a platform push token with the shared backend.
     *
     * iOS calls the same `register_device_token` RPC with platform `ios`.
     * Android callers should pass an FCM token; this method intentionally does
     * not synthesize one when Firebase is not configured.
     */
    suspend fun registerDeviceToken(token: String): Boolean {
        if (token.isBlank()) return false
        val userId = authService.currentUserId
        if (userId.isNullOrBlank()) {
            Log.i(TAG, "Skipping device token registration until authenticated")
            return false
        }

        return try {
            supabase.postgrest.rpc(
                "register_device_token",
                buildJsonObject {
                    put("p_device_id", deviceIdProvider.deviceId)
                    put("p_token", token)
                    put("p_platform", "android")
                    put("p_app_version", BuildConfig.VERSION_NAME)
                    put("p_os_version", Build.VERSION.RELEASE ?: "")
                }
            )
            Log.i(TAG, "Registered Android device token")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Android device token: ${e.safeCloudErrorCode()}")
            false
        }
    }

    /**
     * Remove this device's push token row before auth sign-out/delete.
     * Mirrors iOS `SupabaseService.unregisterDeviceToken`.
     */
    suspend fun unregisterDeviceToken() {
        try {
            supabase.postgrest[TABLE_DEVICE_TOKENS].delete {
                filter { eq("device_id", deviceIdProvider.deviceId) }
            }
            Log.i(TAG, "Unregistered Android device token")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister Android device token: ${e.safeCloudErrorCode()}")
        }
    }

    /**
     * Durably accept a race event and then attempt delivery. The stable ID and
     * local outbox make a response-lost retry safe and preserve events across
     * process death/offline periods, matching the current iOS behavior.
     */
    suspend fun insertRaceEvent(event: RaceEventDto) {
        val durableEvent = event.copy(
            id = event.id ?: stableRaceEventId(
                sessionId = event.sessionId,
                runId = event.runId,
                eventType = event.eventType,
                crossingTimeNanos = event.crossingTimeNanos,
                deviceId = event.deviceId
            )
        )
        synchronized(outboxLock) {
            val index = pendingRaceEvents.indexOfFirst { it.id == durableEvent.id }
            if (index >= 0) pendingRaceEvents[index] = durableEvent else pendingRaceEvents.add(durableEvent)
            persistRaceEventOutboxLocked()
        }
        processPendingRaceEvents("new_${durableEvent.eventType}")
    }

    suspend fun processPendingRaceEvents(reason: String = "maintenance") {
        synchronized(outboxLock) {
            if (outboxFlushInProgress) return
            outboxFlushInProgress = true
        }
        try {
            while (true) {
                val event = synchronized(outboxLock) { pendingRaceEvents.firstOrNull() } ?: break
                try {
                    uploadRaceEvent(event)
                    synchronized(outboxLock) {
                        pendingRaceEvents.removeAll { it.id == event.id }
                        persistRaceEventOutboxLocked()
                    }
                    outboxRetryAttempt = 0
                    Log.d(TAG, "Flushed race event ${event.id?.take(8)} ($reason)")
                } catch (e: Exception) {
                    Log.w(TAG, "Race event retained in durable outbox: ${e.safeCloudErrorCode()}")
                    scheduleRaceEventRetry()
                    return
                }
            }
            outboxRetryJob?.cancel()
            outboxRetryJob = null
        } finally {
            synchronized(outboxLock) { outboxFlushInProgress = false }
        }
        if (synchronized(outboxLock) { pendingRaceEvents.isNotEmpty() }) {
            scheduleRaceEventRetry()
        }
    }

    private suspend fun uploadRaceEvent(event: RaceEventDto) {
        ensureSessionParticipant(event.sessionId)
        try {
            supabase.from(TABLE_RACE_EVENTS).upsert(event) { onConflict = "id" }
        } catch (e: Exception) {
            if (isDuplicateRaceEventError(e)) {
                Log.d(TAG, "Race event already exists; retry considered delivered")
                return
            }
            if (event.runId == null || !isMissingRaceEventRunIdColumn(e)) throw e
            Log.w(TAG, "race_events.run_id unavailable; retrying upsert without run_id")
            try {
                supabase.from(TABLE_RACE_EVENTS).upsert(RaceEventWithoutRunIdDto(event)) {
                    onConflict = "id"
                }
            } catch (legacyError: Exception) {
                if (!isDuplicateRaceEventError(legacyError)) throw legacyError
            }
        }
    }

    private data class OutboxLoadResult(
        val events: List<RaceEventDto>,
        val corrupt: Boolean
    )

    private fun loadRaceEventOutbox(): OutboxLoadResult {
        return runCatching {
            if (!outboxFile.exists()) OutboxLoadResult(emptyList(), corrupt = false)
            else OutboxLoadResult(
                outboxJson.decodeFromString<List<RaceEventDto>>(outboxFile.readText()),
                corrupt = false
            )
        }.getOrElse { error ->
            Log.w(TAG, "Could not decode race-event outbox; preserving file", error)
            OutboxLoadResult(emptyList(), corrupt = true)
        }
    }

    private fun persistRaceEventOutboxLocked() {
        archiveCorruptOutboxLocked()
        if (pendingRaceEvents.isEmpty()) {
            outboxFile.delete()
            return
        }
        outboxFile.parentFile?.mkdirs()
        val temporary = File(outboxFile.parentFile, "${outboxFile.name}.tmp")
        temporary.writeText(outboxJson.encodeToString(pendingRaceEvents))
        if (!temporary.renameTo(outboxFile)) {
            temporary.copyTo(outboxFile, overwrite = true)
            temporary.delete()
        }
    }

    private fun archiveCorruptOutboxLocked() {
        if (!corruptOutboxNeedsArchiving) return
        corruptOutboxNeedsArchiving = false
        if (!outboxFile.exists()) return
        outboxFile.parentFile?.mkdirs()
        val archive = File(
            outboxFile.parentFile,
            "race_event_outbox.corrupt-${System.currentTimeMillis()}.json"
        )
        if (!outboxFile.renameTo(archive)) {
            outboxFile.copyTo(archive, overwrite = false)
            outboxFile.delete()
        }
        Log.w(TAG, "Archived unreadable race-event outbox as ${archive.name}")
    }

    private fun scheduleRaceEventRetry() {
        if (outboxRetryJob?.isActive == true) return
        val index = outboxRetryAttempt.coerceAtMost(RACE_EVENT_RETRY_DELAYS_MS.lastIndex)
        val retryDelay = RACE_EVENT_RETRY_DELAYS_MS[index]
        outboxRetryAttempt = (outboxRetryAttempt + 1).coerceAtMost(RACE_EVENT_RETRY_DELAYS_MS.lastIndex)
        outboxRetryJob = outboxScope.launch {
            delay(retryDelay)
            outboxRetryJob = null
            processPendingRaceEvents("retry_${retryDelay}ms")
        }
    }

    private fun isDuplicateRaceEventError(error: Exception): Boolean {
        val details = "${error.message.orEmpty()} ${error.localizedMessage.orEmpty()} $error".lowercase()
        return details.contains("23505") ||
            details.contains("race_events_unique_session_type_device") ||
            details.contains("race_events_pkey")
    }

    private fun isMissingRaceEventRunIdColumn(error: Exception): Boolean {
        val details = "${error.message.orEmpty()} ${error.localizedMessage.orEmpty()} ${error}".lowercase()
        return details.contains("run_id") &&
            (details.contains("schema cache") ||
                details.contains("could not find") ||
                details.contains("column"))
    }

    suspend fun catchUpRaceEvents(
        sessionId: String,
        sinceCreatedAt: String,
        excludingDeviceId: String
    ): List<RaceEventDto> {
        return try {
            ensureSessionParticipant(sessionId)
            supabase.from(TABLE_RACE_EVENTS)
                .select {
                    filter {
                        eq("session_id", sessionId)
                        gt("created_at", sinceCreatedAt)
                        neq("device_id", excludingDeviceId)
                    }
                    order("created_at", Order.ASCENDING)
                }
                .decodeList<RaceEventDto>()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to catch up race events for $sessionId since $sinceCreatedAt: ${e.safeCloudErrorCode()}")
            emptyList()
        }
    }

    /**
     * Subscribe to real-time race events for a specific session.
     * Returns a Flow that emits RaceEventDto whenever a new event is inserted
     * for the given sessionId.
     */
    fun subscribeToRaceEvents(sessionId: String): Flow<RaceEventDto> = flow {
        try {
            ensureSessionParticipant(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot subscribe to race events — participant registration failed: ${e.safeCloudErrorCode()}")
            return@flow
        }

        val channel = supabase.channel("race_events_$sessionId")

        val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(
            schema = "public"
        ) {
            table = TABLE_RACE_EVENTS
            filter("session_id", FilterOperator.EQ, sessionId)
        }

        channel.subscribe()
        Log.d(TAG, "Subscribed to race events for session: $sessionId")

        try {
            emitAll(
                changeFlow.mapNotNull { insertAction ->
                    try {
                        insertAction.decodeRecord<RaceEventDto>()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode race event from realtime: ${e.safeCloudErrorCode()}")
                        null
                    }
                }
            )
        } finally {
            supabase.realtime.removeChannel(channel)
            Log.d(TAG, "Unsubscribed from race events for session: $sessionId")
        }
    }

    /**
     * Upsert a crossing record into the crossings table.
     *
     * Mirrors iOS SupabaseService.upsertCrossingWithThumbnail so retrying a
     * thumbnail upload updates the same gate/device crossing instead of
     * creating duplicate rows or failing on the uniqueness constraint.
     */
    suspend fun insertCrossing(crossing: CrossingDto) {
        try {
            ensureSessionParticipant(crossing.sessionId)
            supabase.from(TABLE_CROSSINGS).upsert(crossing) {
                onConflict = "session_id,run_id,gate_role,device_id"
            }
            Log.d(TAG, "Upserted crossing: gate=${crossing.gateRole}, session=${crossing.sessionId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upsert crossing: ${e.safeCloudErrorCode()}")
            throw e
        }
    }

    suspend fun getCrossing(crossingId: String): CrossingDto? {
        return try {
            supabase.from(TABLE_CROSSINGS)
                .select {
                    filter {
                        eq("id", crossingId)
                    }
                }
                .decodeSingleOrNull<CrossingDto>()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch crossing ${crossingId.take(8)}: ${e.safeCloudErrorCode()}")
            null
        }
    }

    /**
     * Poll for thumbnail_url after a crossing insert that arrived before the
     * thumbnail update. Matches the iOS 10 x 500ms fallback for missed realtime
     * UPDATE events.
     */
    suspend fun pollForCrossingThumbnail(crossingId: String): CrossingDto? {
        repeat(10) { attempt ->
            delay(500L)
            val crossing = getCrossing(crossingId)
            if (crossing?.thumbnailUrl != null) {
                Log.d(TAG, "Poll found crossing thumbnail on attempt ${attempt + 1}")
                return crossing
            }
        }
        Log.d(TAG, "Poll gave up waiting for crossing thumbnail ${crossingId.take(8)}")
        return null
    }

    suspend fun catchUpCrossings(
        sessionId: String,
        sinceCreatedAt: String,
        excludingDeviceId: String
    ): List<CrossingDto> {
        return try {
            ensureSessionParticipant(sessionId)
            supabase.from(TABLE_CROSSINGS)
                .select {
                    filter {
                        eq("session_id", sessionId)
                        gt("created_at", sinceCreatedAt)
                        neq("device_id", excludingDeviceId)
                    }
                    order("created_at", Order.ASCENDING)
                }
                .decodeList<CrossingDto>()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to catch up crossings for $sessionId since $sinceCreatedAt: ${e.safeCloudErrorCode()}")
            emptyList()
        }
    }

    suspend fun insertCrossingReviewMark(
        sessionId: String?,
        runNumber: Int,
        gateLabel: String?,
        target: String?,
        mode: String?,
        crossingDirection: String?,
        issue: String?,
        actualX: Double?,
        actualY: Double?,
        detectorX: Double,
        detectorY: Double?,
        deltaX: Double?,
        deltaY: Double?,
        interpolationAlpha: Double? = null,
        framePick: String? = null,
        s0: Double? = null,
        s1: Double? = null,
        isFrontCamera: Boolean? = null,
        detectionDistance: String? = null,
        workWidth: Int?,
        exposureMs: Double? = null,
        iso: Int? = null,
        detectorTriggerFramePts: Long? = null,
        chosenThumbnailFramePts: Long? = null,
        savedThumbnailFramePts: Long? = null,
        note: String?,
        rawMessage: String?,
        rawImageData: ByteArray? = null,
        reviewImageData: ByteArray? = null,
        thumbnailStoragePath: String? = null,
        reviewSchema: Int = 4
    ): Boolean {
        authService.ensureAnonymousSession()
        val userId = authService.currentUserId
        if (userId.isNullOrEmpty()) {
            Log.w(TAG, "Cannot insert crossing review mark — no Supabase auth session")
            return false
        }
        val reviewMarkId = UUID.randomUUID().toString()
        var storedThumbnailPath = thumbnailStoragePath

        if (reviewImageData != null) {
            val basePath = "users/$userId/crossing_review_marks/$reviewMarkId"
            try {
                if (rawImageData != null) {
                    storageService.uploadObject(
                        bucket = "race-photos",
                        path = "$basePath/raw.jpg",
                        data = rawImageData
                    )
                }
                val reviewPath = "$basePath/review.jpg"
                val reviewUploaded = storageService.uploadObject(
                    bucket = "race-photos",
                    path = reviewPath,
                    data = reviewImageData
                )
                if (reviewUploaded) {
                    storedThumbnailPath = reviewPath
                }
            } catch (e: Exception) {
                Log.w(TAG, "Review artifact upload failed; inserting review row without image path: ${e.safeCloudErrorCode()}")
            }
        }

        val record = CrossingReviewMarkDto(
            id = reviewMarkId,
            deviceId = deviceIdProvider.deviceId,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            appVersion = BuildConfig.VERSION_NAME,
            sessionId = sessionId,
            runNumber = runNumber,
            gateLabel = gateLabel,
            target = target,
            mode = mode,
            crossingDirection = crossingDirection,
            issue = issue,
            actualX = actualX,
            actualY = actualY,
            detectorX = detectorX,
            detectorY = detectorY,
            deltaX = deltaX,
            deltaY = deltaY,
            interpolationAlpha = interpolationAlpha,
            framePick = framePick,
            s0 = s0,
            s1 = s1,
            isFrontCamera = isFrontCamera,
            detectionDistance = detectionDistance,
            workWidth = workWidth,
            exposureMs = exposureMs,
            iso = iso,
            detectorTriggerFramePts = detectorTriggerFramePts,
            chosenThumbnailFramePts = chosenThumbnailFramePts,
            savedThumbnailFramePts = savedThumbnailFramePts,
            thumbnailStoragePath = storedThumbnailPath,
            note = note,
            rawMessage = rawMessage,
            reviewSchema = reviewSchema
        )

        try {
            supabase.from(TABLE_CROSSING_REVIEW_MARKS).insert(record)
            Log.d(TAG, "Inserted crossing review mark for run $runNumber")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to insert crossing review mark: ${e.safeCloudErrorCode()}")
            return false
        }
    }

    suspend fun upsertCrossingDebugCapture(payload: CrossingDebugCapturePayload) {
        authService.ensureAnonymousSession()
        val userId = authService.currentUserId
            ?: throw IllegalStateException("Cannot upload crossing debug capture without Supabase auth session")
        val captureId = stableCrossingDebugCaptureId(payload.runId, payload.normalizedGateLabel)
        val basePath = "users/$userId/crossing_debug/$captureId"

        val thumbnailStoragePath = payload.thumbnailPath
            ?.let { path -> File(path).takeIf { it.exists() } }
            ?.let { file ->
                val storagePath = "$basePath/thumbnail.jpg"
                if (storageService.uploadObject("race-photos", storagePath, file.readBytes())) {
                    storagePath
                } else {
                    null
                }
            }

        val uploadedFramePaths = mutableMapOf<Int, String>()
        payload.frames.forEachIndexed { index, frame ->
            val file = File(frame.imagePath)
            if (!file.exists()) return@forEachIndexed
            val storagePath = "$basePath/$index.jpg"
            if (storageService.uploadObject("race-photos", storagePath, file.readBytes())) {
                uploadedFramePaths[index] = storagePath
            }
        }

        val framesMetadata = payload.frames.mapIndexed { index, frame ->
            CrossingDebugFrameMetadataDto(
                ptsNanos = frame.ptsNanos,
                chestX = frame.chestX,
                blobHeightFraction = frame.blobHeightFraction,
                velocityPxPerSec = frame.velocityPxPerSec,
                dtFromPrevMs = frame.dtFromPrevMs,
                anchorMode = frame.anchorMode,
                torsoLeadingEdgeX = frame.torsoLeadingEdgeX,
                legacyEdgeX = frame.legacyEdgeX,
                centroidX = frame.centroidX,
                torsoSegmentWidthPx = frame.torsoSegmentWidthPx,
                timingModel = frame.timingModel,
                exposureCompensationFactor = frame.exposureCompensationFactor,
                exposureDurationMs = frame.exposureDurationMs,
                contourRowsUsed = frame.contourRowsUsed,
                storagePath = uploadedFramePaths[index]
            )
        }

        val record = CrossingDebugCaptureDto(
            id = captureId,
            sessionId = payload.sessionId,
            runId = payload.runId,
            runNumber = payload.runNumber,
            gateLabel = payload.normalizedGateLabel,
            deviceId = deviceIdProvider.deviceId,
            appVersion = BuildConfig.VERSION_NAME,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            crossingTimeNanos = payload.crossingTimeNanos,
            detectorTriggerFramePtsNanos = payload.detectorTriggerFramePtsNanos,
            detectorChosenFramePtsNanos = payload.chosenThumbnailFramePtsNanos,
            savedThumbnailFramePtsNanos = payload.savedThumbnailFramePtsNanos,
            detectorToSavedFrameDeltaMs = deltaMs(
                from = payload.detectorTriggerFramePtsNanos,
                to = payload.savedThumbnailFramePtsNanos
            ),
            chosenToSavedFrameDeltaMs = deltaMs(
                from = payload.chosenThumbnailFramePtsNanos,
                to = payload.savedThumbnailFramePtsNanos
            ),
            configuredGatePosition = payload.configuredGatePosition,
            detectorPosition = payload.detectorPosition,
            interpolatedDisplayPosition = payload.interpolatedDisplayPosition,
            projectedDisplayPosition = payload.projectedDisplayPosition,
            algoInterpolationAlpha = payload.interpolationAlpha,
            algoVelocityPxPerSec = payload.velocityPxPerSec,
            algoBlobHeightFraction = payload.blobHeightFraction,
            algoBlobWidthFraction = payload.blobWidthFraction,
            algoGatePosition = payload.configuredGatePosition,
            algoFps = payload.detectorFrameDurationMs?.takeIf { it > 0f }?.let { 1000.0 / it } ?: 0.0,
            algoCrossingDirection = payload.crossingDirection,
            algoWorkWidth = payload.workWidth,
            algoS0 = payload.s0,
            algoS1 = payload.s1,
            algoDetectorFrameDurationMs = payload.detectorFrameDurationMs,
            thumbnailStoragePath = thumbnailStoragePath,
            framesMetadata = framesMetadata,
            updatedAt = Instant.now().toString()
        )

        supabase.from(TABLE_CROSSING_DEBUG_CAPTURES).upsert(record) {
            onConflict = "run_id,gate_label"
        }
        Log.d(TAG, "Upserted crossing debug capture run=${payload.runId.take(8)} gate=${payload.normalizedGateLabel}")
    }

    private fun deltaMs(from: Long?, to: Long?): Float? {
        if (from == null || to == null) return null
        return (to - from).toFloat() / 1_000_000f
    }

    private fun stableCrossingDebugCaptureId(runId: String, gateLabel: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$runId|${gateLabel.lowercase()}".toByteArray(Charsets.UTF_8))
        val bytes = digest.copyOfRange(0, 16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    /**
     * Subscribe to real-time crossing events for a specific session.
     * Returns a Flow that emits CrossingDto whenever a new crossing is inserted.
     */
    fun subscribeToCrossings(sessionId: String): Flow<CrossingDto> = flow {
        try {
            ensureSessionParticipant(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot subscribe to crossings — participant registration failed: ${e.safeCloudErrorCode()}")
            return@flow
        }

        val channel = supabase.channel("crossings_$sessionId")

        val changeFlow = channel.postgresChangeFlow<PostgresAction>(
            schema = "public"
        ) {
            table = TABLE_CROSSINGS
            filter("session_id", FilterOperator.EQ, sessionId)
        }

        channel.subscribe()
        Log.d(TAG, "Subscribed to crossings for session: $sessionId")

        try {
            emitAll(
                changeFlow.mapNotNull { action ->
                    try {
                        when (action) {
                            is PostgresAction.Insert -> action.decodeRecord<CrossingDto>()
                            is PostgresAction.Update -> action.decodeRecord<CrossingDto>()
                            else -> null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode crossing from realtime: ${e.safeCloudErrorCode()}")
                        null
                    }
                }
            )
        } finally {
            supabase.realtime.removeChannel(channel)
            Log.d(TAG, "Unsubscribed from crossings for session: $sessionId")
        }
    }

    /**
     * Create a new pairing request. The host device creates this and shares
     * the session code with the joining device.
     */
    suspend fun createPairingRequest(
        code: String,
        deviceId: String,
        deviceName: String
    ) {
        try {
            val request = PairingRequestDto(
                sessionCode = code,
                hostDeviceId = deviceId,
                hostDeviceName = deviceName,
                status = "waiting"
            )
            supabase.from(TABLE_PAIRING_REQUESTS).insert(request)
            Log.d(TAG, "Created pairing request with code: $code")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create pairing request: ${e.safeCloudErrorCode()}")
            throw e
        }
    }

    /**
     * Join an existing pairing request by updating it with the joiner's device info
     * and changing the status to "matched".
     */
    suspend fun joinPairingRequest(
        code: String,
        deviceId: String,
        deviceName: String
    ) {
        try {
            supabase.from(TABLE_PAIRING_REQUESTS).update(
                {
                    set("joiner_device_id", deviceId)
                    set("joiner_device_name", deviceName)
                    set("status", "matched")
                }
            ) {
                filter {
                    eq("session_code", code)
                    eq("status", "waiting")
                }
            }
            Log.d(TAG, "Joined pairing request with code: $code")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to join pairing request: ${e.safeCloudErrorCode()}")
            throw e
        }
    }

    /**
     * Watch a pairing request for changes (e.g., a joiner connecting).
     * Emits updated PairingRequestDto whenever the row is updated.
     */
    fun watchPairingRequest(code: String): Flow<PairingRequestDto> = flow {
        val channel = supabase.channel("pairing_$code")

        val changeFlow = channel.postgresChangeFlow<PostgresAction>(
            schema = "public"
        ) {
            table = TABLE_PAIRING_REQUESTS
        }

        channel.subscribe()
        Log.d(TAG, "Watching pairing request: $code")

        try {
            emitAll(
                changeFlow.mapNotNull { action ->
                    try {
                        val dto = when (action) {
                            is PostgresAction.Insert -> action.decodeRecord<PairingRequestDto>()
                            is PostgresAction.Update -> action.decodeRecord<PairingRequestDto>()
                            else -> null
                        }
                        // Filter to only this pairing code
                        if (dto?.sessionCode == code) dto else null
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode pairing request from realtime: ${e.safeCloudErrorCode()}")
                        null
                    }
                }
            )
        } finally {
            supabase.realtime.removeChannel(channel)
            Log.d(TAG, "Stopped watching pairing request: $code")
        }
    }

    /**
     * Fetch a pairing request by session code.
     */
    suspend fun getPairingRequest(code: String): PairingRequestDto? {
        return try {
            supabase.from(TABLE_PAIRING_REQUESTS)
                .select {
                    filter {
                        eq("session_code", code)
                    }
                }
                .decodeSingleOrNull<PairingRequestDto>()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch pairing request: ${e.safeCloudErrorCode()}")
            null
        }
    }

    /**
     * Update pairing request status to "connected" once both devices confirm.
     */
    suspend fun confirmPairingConnection(code: String) {
        try {
            supabase.from(TABLE_PAIRING_REQUESTS).update(
                {
                    set("status", "connected")
                }
            ) {
                filter {
                    eq("session_code", code)
                }
            }
            Log.d(TAG, "Confirmed pairing connection for code: $code")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to confirm pairing connection: ${e.safeCloudErrorCode()}")
            throw e
        }
    }
}
