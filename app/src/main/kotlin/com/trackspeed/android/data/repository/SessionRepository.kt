package com.trackspeed.android.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.trackspeed.android.billing.SubscriptionManager
import com.trackspeed.android.cloud.CloudSyncService
import com.trackspeed.android.cloud.CrossingDebugUploadQueue
import com.trackspeed.android.cloud.ProfileService
import com.trackspeed.android.cloud.StorageService
import com.trackspeed.android.cloud.ThumbnailUploadQueue
import com.trackspeed.android.cloud.TimingWorkloadCoordinator
import com.trackspeed.android.cloud.dto.RunDto
import com.trackspeed.android.cloud.dto.SessionDto
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.dao.RunDao
import com.trackspeed.android.data.local.dao.SessionBestTime
import com.trackspeed.android.data.local.dao.SessionSummary
import com.trackspeed.android.data.local.dao.TrainingSessionDao
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.protocol.SegmentSplit
import com.trackspeed.android.protocol.TimingRole
import com.trackspeed.android.notifications.NotificationService
import com.trackspeed.android.notifications.NotificationTiming
import com.trackspeed.android.ui.screens.timing.SoloLapResult
import com.trackspeed.android.util.ImageDownloadValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.trackspeed.android.ui.components.ThumbnailUtils
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val CLOUD_UPLOAD_RETRY_PREFS = "cloud_upload_retry_queue"
private const val PENDING_SESSION_UPLOADS = "pending_session_uploads"
private const val PENDING_RUN_UPLOADS = "pending_run_uploads"
private const val FIRST_DISCOUNT_MILESTONE_SESSION_COUNT = 4
private const val SECOND_DISCOUNT_MILESTONE_SESSION_COUNT = 8
private const val DISCOUNT_PAYWALL_MAX_SHOWS = 2
private val DISCOUNT_PAYWALL_GAP_MILLIS = TimeUnit.DAYS.toMillis(7)

data class LocalGateFrameSnapshot(
    val bitmap: Bitmap,
    val frameNumber: Long,
    val timestampNanos: Long,
    val occupancy: Float,
    val longestRun: Int,
    val isTracking: Boolean,
    val torsoTop: Int,
    val torsoBottom: Int,
    val frameHeight: Int,
    val leftShoulderY: Float? = null,
    val rightShoulderY: Float? = null,
    val leftHipY: Float? = null,
    val rightHipY: Float? = null,
    val runStartY: Int = 0,
    val runEndY: Int = 0
)

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: TrainingSessionDao,
    private val runDao: RunDao,
    private val athleteDao: AthleteDao,
    private val cloudSyncService: CloudSyncService,
    private val storageService: StorageService,
    private val thumbnailUploadQueue: ThumbnailUploadQueue,
    private val crossingDebugUploadQueue: CrossingDebugUploadQueue,
    private val workloadCoordinator: TimingWorkloadCoordinator,
    private val profileService: ProfileService,
    private val settingsRepository: SettingsRepository,
    private val notificationService: NotificationService,
    private val subscriptionManager: SubscriptionManager
) {
    private val uploadRetryPrefs by lazy {
        context.getSharedPreferences(CLOUD_UPLOAD_RETRY_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Wipe every locally persisted training session, run, athlete, and the
     * thumbnails directory on disk. Used by the sign-out / delete-account
     * teardown to ensure the next account doesn't see the previous user's
     * runs (mirrors iOS `AuthService.signOut` / `deleteAccount` teardown).
     */
    suspend fun clearAllLocalData() {
        // Delete child tables first to avoid orphan-row warnings even though
        // CASCADE would handle it. Athletes have no FK so order doesn't matter.
        runDao.deleteAll()
        sessionDao.deleteAll()
        athleteDao.deleteAll()
        clearPendingCloudUploads()
        thumbnailUploadQueue.clearQueue()
        crossingDebugUploadQueue.clearQueue()
        withContext(Dispatchers.IO) {
            try {
                listOf(
                    File(context.filesDir, "thumbnails"),
                    File(context.filesDir, "athletes")
                ).forEach { dir ->
                    if (dir.exists()) dir.deleteRecursively()
                }
            } catch (_: Exception) { }
        }
    }

    fun clearPendingCloudDeletions() {
        cloudSyncService.clearPendingCloudDeletions()
    }

    suspend fun syncAthletesFromCloud(): Int {
        val remoteAthletes = cloudSyncService.fetchAthletes()
        var imported = 0

        for (remote in remoteAthletes) {
            val id = remote.id ?: continue
            val existing = athleteDao.getAthleteById(id)
            val remoteUpdatedAt = parseCloudInstantMillis(remote.updatedAt)
                ?: parseCloudInstantMillis(remote.createdAt)
                ?: System.currentTimeMillis()

            if (existing != null && existing.updatedAt > remoteUpdatedAt) {
                continue
            }

            val photoPath = cacheRemoteAthletePhoto(id, remote.photoUrl)
                ?: existing?.photoPath
                ?: remote.photoUrl

            val createdAt = parseCloudInstantMillis(remote.createdAt)
                ?: existing?.createdAt
                ?: remoteUpdatedAt

            athleteDao.insert(
                AthleteEntity(
                    id = id,
                    name = remote.name,
                    nickname = remote.nickname,
                    color = remote.color ?: existing?.color ?: "blue",
                    photoPath = photoPath,
                    birthdate = parseCloudDateMillis(remote.birthdate) ?: existing?.birthdate,
                    gender = remote.gender ?: existing?.gender,
                    personalBestsJson = AthleteEntity.encodeBestMap(remote.personalBests ?: emptyMap()),
                    seasonBestsJson = AthleteEntity.encodeBestMap(remote.seasonBests ?: emptyMap()),
                    createdAt = createdAt,
                    updatedAt = remoteUpdatedAt
                )
            )
            imported++
        }

        return imported
    }

    suspend fun syncSessionsFromCloud(limit: Int = 50): Int {
        val remoteSessions = cloudSyncService.fetchSessions(limit)
        var imported = 0

        for (remoteSession in remoteSessions) {
            val sessionId = remoteSession.id ?: continue
            val existing = sessionDao.getSession(sessionId)
            val remoteUpdatedAt = parseCloudInstantMillis(remoteSession.updatedAt)
                ?: parseCloudInstantMillis(remoteSession.createdAt)
                ?: System.currentTimeMillis()
            val createdAt = parseCloudInstantMillis(remoteSession.createdAt)
                ?: existing?.createdAt
                ?: remoteUpdatedAt

            val shouldUpdateSession = existing == null || existing.updatedAt <= remoteUpdatedAt
            if (shouldUpdateSession) {
                sessionDao.insert(remoteSession.toEntity(existing, createdAt, remoteUpdatedAt))
                imported++
            }

            val importedThumbnail = syncRunsFromCloudForSession(sessionId)
            if (importedThumbnail != null) {
                val current = sessionDao.getSession(sessionId)
                if (current != null && current.thumbnailPath == null) {
                    sessionDao.insert(current.copy(thumbnailPath = importedThumbnail))
                }
            }
        }

        return imported
    }

    suspend fun processPendingAthleteDeletions(): Int {
        return cloudSyncService.processPendingAthleteDeletions()
    }

    suspend fun processPendingCloudDeletions(): Int {
        return cloudSyncService.processPendingCloudDeletions()
    }

    suspend fun processPendingCloudUploads(): Int {
        if (workloadCoordinator.isLiveTimingActive) return 0

        var syncedCount = 0

        for (sessionId in pendingUploadIds(PENDING_SESSION_UPLOADS)) {
            if (workloadCoordinator.isLiveTimingActive) return syncedCount
            val session = sessionDao.getSession(sessionId)
            if (session == null) {
                removePendingUpload(PENDING_SESSION_UPLOADS, sessionId)
                continue
            }

            if (cloudSyncService.syncSession(session)) {
                removePendingUpload(PENDING_SESSION_UPLOADS, sessionId)
                syncedCount++
            }
        }

        for (runId in pendingUploadIds(PENDING_RUN_UPLOADS)) {
            if (workloadCoordinator.isLiveTimingActive) return syncedCount
            val run = runDao.getRunById(runId)
            if (run == null) {
                removePendingUpload(PENDING_RUN_UPLOADS, runId)
                continue
            }

            val thumbnailPath = run.thumbnailPath
            val thumbnailUrl = uploadThumbnail(
                sessionId = run.sessionId,
                lapNumber = run.runNumber,
                localPath = thumbnailPath
            )

            if (syncRunWithRetry(run, thumbnailUrl, thumbnailPath)) {
                syncedCount++
            }
        }

        return syncedCount
    }

    suspend fun processPendingFlyingPrSync(): Boolean {
        val pending = settingsRepository.pendingFlyingPrSync.first()
        if (pending.isBlank()) return false

        val parts = pending.split(":")
        if (parts.size != 2) {
            settingsRepository.setPendingFlyingPrSync("")
            return false
        }

        val distance = FlyingDistance.fromRawValue(parts[0])
        val timeSeconds = parts[1].toDoubleOrNull()
        if (distance == null || timeSeconds == null) {
            settingsRepository.setPendingFlyingPrSync("")
            return false
        }

        return if (profileService.updateFlyingPr(distance, timeSeconds)) {
            settingsRepository.setPendingFlyingPrSync("")
            true
        } else {
            false
        }
    }

    fun getAllSessions(): Flow<List<TrainingSessionEntity>> {
        return sessionDao.getAllSessions()
    }

    fun getRecentSessions(limit: Int = 3): Flow<List<TrainingSessionEntity>> {
        return sessionDao.getRecentSessions(limit)
    }

    fun getRunsForSession(sessionId: String): Flow<List<RunEntity>> {
        return runDao.getRunsForSession(sessionId)
    }

    suspend fun getSession(id: String): TrainingSessionEntity? {
        return sessionDao.getSession(id)
    }

    suspend fun deleteSession(id: String) {
        val session = sessionDao.getSession(id)
        val cloudSessionId = session?.cloudId ?: session?.id ?: id
        val runs = runDao.getRunsForSession(id).first()

        cloudSyncService.deleteSession(cloudSessionId)
        runs.mapNotNull { it.cloudRunId }
            .forEach { cloudSyncService.deleteRun(it) }
        removePendingUpload(PENDING_SESSION_UPLOADS, id)
        runs.forEach {
            removePendingUpload(PENDING_RUN_UPLOADS, it.id)
            thumbnailUploadQueue.removeUploadsForRun(it.id)
            crossingDebugUploadQueue.removeQueuedCapturesForRun(it.id)
            it.cloudRunId?.let(thumbnailUploadQueue::removeUploadsForRun)
            it.cloudRunId?.let(crossingDebugUploadQueue::removeQueuedCapturesForRun)
        }

        sessionDao.deleteById(id)
        // Clean up thumbnail directory for this session
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "thumbnails/$id")
                if (dir.exists()) dir.deleteRecursively()
            } catch (_: Exception) { }
        }
    }

    suspend fun saveSession(
        name: String?,
        distance: Double,
        startType: String,
        laps: List<SoloLapResult>,
        athletes: List<AthleteEntity> = emptyList()
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val session = TrainingSessionEntity(
            id = sessionId,
            date = now,
            name = name,
            distance = distance,
            startType = startType,
            numberOfPhones = 1,
            numberOfGates = 1,
            createdAt = now,
            updatedAt = now
        )
        sessionDao.insert(session)
        syncSessionWithRetry(session)

        // Save runs (skip lap 0 = START)
        val actualLaps = laps.filter { it.lapNumber > 0 }
        val bestTime = actualLaps.minOfOrNull { it.lapTimeSeconds }
        val seasonStart = getSeasonStartMillis()
        var currentSeasonBest = runDao.getSeasonBest(distance, seasonStart)

        for (lap in actualLaps) {
            val lapAthlete = lap.athleteId?.let { athleteId ->
                athletes.firstOrNull { it.id == athleteId } ?: athleteDao.getAthleteById(athleteId)
            }
            // Modern solo timing stores the active athlete on each lap. Keep
            // the old round-robin fallback for any caller that still provides
            // selected athletes but no per-lap athlete snapshot.
            val athlete = lapAthlete ?: if (athletes.isNotEmpty()) {
                athletes[(lap.lapNumber - 1) % athletes.size]
            } else {
                null
            }
            val athleteId = athlete?.id ?: lap.athleteId
            val athleteName = lap.athleteName ?: athlete?.name
            val athleteColor = lap.athleteColor ?: athlete?.color

            val globalSeasonBest = currentSeasonBest == null || lap.lapTimeSeconds < currentSeasonBest
            val athleteBestResult = updateAthleteBests(
                athlete = athlete,
                timeSeconds = lap.lapTimeSeconds,
                distance = distance,
                startType = startType
            )
            val isPersonalBest = if (athleteBestResult.isTracked) {
                athleteBestResult.isPersonalBest
            } else {
                lap.lapTimeSeconds == bestTime
            }
            val isSeasonBest = if (athleteBestResult.isTracked) {
                athleteBestResult.isSeasonBest
            } else {
                globalSeasonBest
            }

            val thumbnailPath = saveThumbnail(sessionId, lap.lapNumber, lap.thumbnail, lap.gatePosition)
            val thumbnailUrl = uploadThumbnail(sessionId, lap.lapNumber, thumbnailPath)
            val run = RunEntity(
                sessionId = sessionId,
                athleteId = athleteId,
                athleteName = athleteName,
                athleteColor = athleteColor,
                runNumber = lap.lapNumber,
                timeSeconds = lap.lapTimeSeconds,
                distance = distance,
                startType = startType,
                isPersonalBest = isPersonalBest,
                isSeasonBest = isSeasonBest,
                thumbnailPath = thumbnailPath,
                finishImagePath = thumbnailPath,
                gatePosition = lap.gatePosition.toDouble(),
                crossingVelocity = lap.crossingVelocityPxPerSec?.toDouble(),
                finishGatePosition = lap.gatePosition.toDouble(),
                finishCrossingVelocity = lap.crossingVelocityPxPerSec?.toDouble(),
                finishCrossingDirection = lap.crossingDirection,
                finishWorkResolutionWidth = lap.workWidth,
                workResolutionWidth = lap.workWidth,
                finishDetectorY = lap.detectorYPosition?.toDouble(),
                finishInterpolationAlpha = lap.interpolationAlpha,
                finishFramePick = lap.framePick,
                finishS0 = lap.s0?.toDouble(),
                finishS1 = lap.s1?.toDouble(),
                finishIsFrontCamera = lap.isFrontCamera,
                finishDetectorTriggerFramePts = lap.detectorTriggerFramePts,
                finishChosenThumbnailFramePts = lap.chosenThumbnailFramePts,
                finishSavedThumbnailFramePts = lap.savedThumbnailFramePts,
                localGateRole = TimingRole.FINISH_LINE.value,
                crossingTimestampNanos = lap.crossingTimestampNanos,
                createdAt = now
            )
            runDao.insert(run)
            crossingDebugUploadQueue.submitRunCapture(run, gateLabel = "crossing")
            if (!athleteBestResult.isTracked && isSeasonBest) {
                currentSeasonBest = lap.lapTimeSeconds
            }
            syncRunWithRetry(run, thumbnailUrl, thumbnailPath)
            checkAndUpdateFlyingPr(
                timeSeconds = lap.lapTimeSeconds,
                distance = distance,
                startType = startType
            )
        }

        schedulePostSessionNotifications()
        return sessionId
    }

    /**
     * Save a multi-device race result (single run with known time).
     */
    suspend fun saveRaceResult(
        sessionId: String? = null,
        runNumber: Int? = null,
        runId: String? = null,
        distance: Double,
        startType: String,
        numberOfGates: Int = 2,
        gateDistances: Map<Int, Double> = emptyMap(),
        timeSeconds: Double,
        thumbnail: Bitmap? = null,
        gatePosition: Float = 0.5f,
        athleteId: String? = null,
        athleteName: String? = null,
        athleteColor: String? = null,
        crossingTimestampNanos: Long? = null,
        segments: List<SegmentSplit> = emptyList(),
        localGateRole: TimingRole? = null,
        crossingVelocityPxPerSec: Float? = null,
        crossingDirection: String? = null,
        workWidth: Int? = null,
        thumbnailDebugJson: String? = null,
        localGateFrames: List<LocalGateFrameSnapshot> = emptyList(),
        runCreatedAtMillis: Long? = null
    ): String {
        val effectiveSessionId = sessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val gateConfigJson = gateDistances
            .takeIf { it.isNotEmpty() }
            ?.toSortedMap()
            ?.mapKeys { it.key.toString() }
            ?.let { Json.encodeToString(it) }

        val existingSession = sessionDao.getSession(effectiveSessionId)
        val session = existingSession?.copy(
            distance = distance,
            startType = startType,
            numberOfPhones = numberOfGates.coerceAtLeast(2),
            numberOfGates = numberOfGates.coerceAtLeast(2),
            gateConfigJson = gateConfigJson,
            updatedAt = now
        ) ?: TrainingSessionEntity(
            id = effectiveSessionId,
            date = now,
            name = null,
            distance = distance,
            startType = startType,
            numberOfPhones = numberOfGates.coerceAtLeast(2),
            numberOfGates = numberOfGates.coerceAtLeast(2),
            gateConfigJson = gateConfigJson,
            createdAt = now,
            updatedAt = now
        )
        sessionDao.insert(session)

        // PB/SB tracking. iOS tracks athlete bests by start type + distance;
        // keep the previous global fallback for unassigned runs.
        val athlete = athleteId?.let { athleteDao.getAthleteById(it) }
        val athleteBestResult = updateAthleteBests(
            athlete = athlete,
            timeSeconds = timeSeconds,
            distance = distance,
            startType = startType
        )
        val personalBest = runDao.getPersonalBest(distance)
        val seasonStart = getSeasonStartMillis()
        val seasonBest = runDao.getSeasonBest(distance, seasonStart)
        val isPersonalBest = if (athleteBestResult.isTracked) {
            athleteBestResult.isPersonalBest
        } else {
            personalBest == null || timeSeconds < personalBest
        }
        val isSeasonBest = if (athleteBestResult.isTracked) {
            athleteBestResult.isSeasonBest
        } else {
            seasonBest == null || timeSeconds < seasonBest
        }

        val effectiveRunNumber = runNumber
            ?.takeIf { it > 0 }
            ?: (sessionDao.getRunCount(effectiveSessionId) + 1).coerceAtLeast(1)

        // Save thumbnail locally
        val thumbnailPath = saveThumbnail(effectiveSessionId, effectiveRunNumber, thumbnail, gatePosition)
        val sessionForSync = if (session.thumbnailPath == null && thumbnailPath != null) {
            session.copy(thumbnailPath = thumbnailPath, updatedAt = now)
        } else {
            session
        }
        if (sessionForSync != session) {
            sessionDao.insert(sessionForSync)
        }

        val finalRunId = runId ?: UUID.randomUUID().toString()
        val localGateFramesDataJson = persistLocalGateFrames(
            sessionId = effectiveSessionId,
            runId = finalRunId,
            frames = localGateFrames
        )

        val run = RunEntity(
            id = finalRunId,
            sessionId = effectiveSessionId,
            runNumber = effectiveRunNumber,
            timeSeconds = timeSeconds,
            distance = distance,
            startType = startType,
            numberOfPhones = numberOfGates.coerceAtLeast(2),
            athleteId = athleteId,
            athleteName = athleteName ?: athlete?.name,
            athleteColor = athleteColor ?: athlete?.color,
            isPersonalBest = isPersonalBest,
            isSeasonBest = isSeasonBest,
            thumbnailPath = thumbnailPath,
            finishImagePath = thumbnailPath,
            splitsJson = segments.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
            gatePosition = gatePosition.toDouble(),
            crossingVelocity = crossingVelocityPxPerSec?.toDouble(),
            startGatePosition = if (localGateRole == TimingRole.START_LINE) gatePosition.toDouble() else null,
            finishGatePosition = if (localGateRole == TimingRole.FINISH_LINE) gatePosition.toDouble() else null,
            startCrossingVelocity = if (localGateRole == TimingRole.START_LINE) crossingVelocityPxPerSec?.toDouble() else null,
            finishCrossingVelocity = if (localGateRole == TimingRole.FINISH_LINE) crossingVelocityPxPerSec?.toDouble() else null,
            startCrossingDirection = if (localGateRole == TimingRole.START_LINE) crossingDirection else null,
            finishCrossingDirection = if (localGateRole == TimingRole.FINISH_LINE) crossingDirection else null,
            startWorkResolutionWidth = if (localGateRole == TimingRole.START_LINE) workWidth else null,
            finishWorkResolutionWidth = if (localGateRole == TimingRole.FINISH_LINE) workWidth else null,
            workResolutionWidth = workWidth,
            startThumbnailDebugJson = if (localGateRole == TimingRole.START_LINE) thumbnailDebugJson else null,
            finishThumbnailDebugJson = if (localGateRole == TimingRole.FINISH_LINE) thumbnailDebugJson else null,
            localGateFramesDataJson = localGateFramesDataJson,
            localGateRole = localGateRole?.value,
            cloudRunId = runId,
            crossingTimestampNanos = crossingTimestampNanos,
            createdAt = runCreatedAtMillis ?: now
        )
        runDao.insert(run)
        crossingDebugUploadQueue.submitRunCapture(run)

        // Local persistence is the completion boundary. Queue cloud work before
        // attempting any network call so closing the timing screen cannot lose
        // a saved run or strand it without a later sync retry.
        enqueuePendingUpload(PENDING_SESSION_UPLOADS, sessionForSync.id)
        enqueuePendingUpload(PENDING_RUN_UPLOADS, run.id)
        val thumbnailUrl = uploadThumbnail(effectiveSessionId, effectiveRunNumber, thumbnailPath)
        syncSessionWithRetry(sessionForSync)
        syncRunWithRetry(run, thumbnailUrl, thumbnailPath)
        checkAndUpdateFlyingPr(
            timeSeconds = timeSeconds,
            distance = distance,
            startType = startType
        )

        schedulePostSessionNotifications()
        return effectiveSessionId
    }

    fun getTotalSessionCount(): Flow<Int> {
        return sessionDao.getTotalSessionCount()
    }

    fun getTotalRunCount(): Flow<Int> {
        return runDao.getTotalRunCount()
    }

    fun getAllRunsSortedByTime(): Flow<List<RunEntity>> {
        return runDao.getAllRunsSortedByTime()
    }

    fun getGlobalBestTime(): Flow<Double?> {
        return runDao.getGlobalBestTime()
    }

    fun getDistinctDistances(): Flow<List<Double>> {
        return runDao.getDistinctDistances()
    }

    fun getPersonalBestFlow(distance: Double): Flow<Double?> {
        return runDao.getPersonalBestFlow(distance)
    }

    fun getBestTimePerSession(): Flow<List<SessionBestTime>> {
        return runDao.getBestTimePerSession()
    }

    fun getSessionSummaries(): Flow<List<SessionSummary>> {
        return runDao.getSessionSummaries()
    }

    fun getDistinctStartTypes(): Flow<List<String>> {
        return sessionDao.getDistinctStartTypes()
    }

    fun getSessionCountSince(sinceMillis: Long): Flow<Int> {
        return sessionDao.getSessionCountSince(sinceMillis)
    }

    suspend fun getRunById(runId: String): RunEntity? {
        return runDao.getRunById(runId)
    }

    suspend fun deleteRun(runId: String) {
        val run = runDao.getRunById(runId)
        run?.let {
            cloudSyncService.deleteRun(it.cloudRunId ?: it.id)
            removePendingUpload(PENDING_RUN_UPLOADS, it.id)
            thumbnailUploadQueue.removeUploadsForRun(it.id)
            crossingDebugUploadQueue.removeQueuedCapturesForRun(it.id)
            it.cloudRunId?.let(thumbnailUploadQueue::removeUploadsForRun)
            it.cloudRunId?.let(crossingDebugUploadQueue::removeQueuedCapturesForRun)
        }
        runDao.deleteById(runId)
        // Clean up thumbnail file
        run?.thumbnailPath?.let { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    suspend fun updateRunDistance(runId: String, newDistance: Double) {
        runDao.updateDistance(runId, newDistance)
        runDao.getRunById(runId)?.let { updated ->
            val thumbnailUrl = uploadThumbnail(updated.sessionId, updated.runNumber, updated.thumbnailPath)
            syncRunWithRetry(updated, thumbnailUrl, updated.thumbnailPath)
        }
    }

    /**
     * Correct a result that was saved before a newer replacement start arrived.
     * The run identity and media stay stable so delayed thumbnail updates cannot
     * create a duplicate history row.
     */
    suspend fun correctSavedRaceResult(
        runId: String,
        correctedTimeSeconds: Double,
        crossingTimestampNanos: Long? = null
    ): Boolean {
        val existing = runDao.getRunById(runId) ?: runDao.getRunByCloudRunId(runId) ?: return false
        val updated = existing.copy(
            timeSeconds = correctedTimeSeconds,
            crossingTimestampNanos = crossingTimestampNanos ?: existing.crossingTimestampNanos
        )
        runDao.insert(updated)
        val thumbnailUrl = uploadThumbnail(updated.sessionId, updated.runNumber, updated.thumbnailPath)
        syncRunWithRetry(updated, thumbnailUrl, updated.thumbnailPath)
        return true
    }

    suspend fun applyRemoteGateAdjustment(
        runId: String,
        gateLabel: String,
        newGatePosition: Double,
        correctedTimeSeconds: Double?,
        splitsJson: String?
    ): Boolean {
        val run = runDao.getRunById(runId) ?: runDao.getRunByCloudRunId(runId) ?: return false
        val label = gateLabel.trim().lowercase()
        val updated = when {
            label.contains("start") -> run.copy(
                startGatePosition = newGatePosition,
                timeSeconds = correctedTimeSeconds ?: run.timeSeconds,
                splitsJson = splitsJson ?: run.splitsJson
            )
            label.contains("finish") || label.contains("crossing") -> run.copy(
                finishGatePosition = newGatePosition,
                gatePosition = newGatePosition,
                timeSeconds = correctedTimeSeconds ?: run.timeSeconds,
                splitsJson = splitsJson ?: run.splitsJson
            )
            else -> run.copy(
                gatePosition = newGatePosition,
                timeSeconds = correctedTimeSeconds ?: run.timeSeconds,
                splitsJson = splitsJson ?: run.splitsJson
            )
        }

        runDao.insert(updated)
        val thumbnailUrl = uploadThumbnail(updated.sessionId, updated.runNumber, updated.thumbnailPath)
        syncRunWithRetry(updated, thumbnailUrl, updated.thumbnailPath)
        return true
    }

    suspend fun applyRemoteGateCalibration(
        runId: String,
        role: TimingRole,
        gateIndex: Int?,
        gatePosition: Float,
        velocityPxPerSec: Float,
        crossingDirection: String?,
        workWidth: Int?,
        thumbnailDebugJson: String?
    ): Boolean {
        val run = runDao.getRunById(runId) ?: runDao.getRunByCloudRunId(runId) ?: return false
        val updated = when (role) {
            TimingRole.START_LINE -> run.copy(
                startGatePosition = gatePosition.toDouble(),
                startCrossingVelocity = velocityPxPerSec.toDouble(),
                startCrossingDirection = crossingDirection,
                startWorkResolutionWidth = workWidth,
                startThumbnailDebugJson = thumbnailDebugJson ?: run.startThumbnailDebugJson
            )
            TimingRole.FINISH_LINE -> run.copy(
                finishGatePosition = gatePosition.toDouble(),
                finishCrossingVelocity = velocityPxPerSec.toDouble(),
                finishCrossingDirection = crossingDirection,
                finishWorkResolutionWidth = workWidth,
                finishThumbnailDebugJson = thumbnailDebugJson ?: run.finishThumbnailDebugJson
            )
            TimingRole.LAP_GATE -> run.copy(
                localGateFramesDataJson = updatedLapGateCalibrationJson(
                    existingJson = run.localGateFramesDataJson,
                    snapshot = GateCalibrationSnapshot(
                        gateIndex = gateIndex ?: 1,
                        role = role.value,
                        gatePosition = gatePosition.toDouble(),
                        velocityPxPerSec = velocityPxPerSec.toDouble(),
                        crossingDirection = crossingDirection,
                        workWidth = workWidth,
                        thumbnailDebugJson = thumbnailDebugJson
                    )
                )
            )
            TimingRole.CONTROL_ONLY -> return false
        }

        runDao.insert(updated)
        val thumbnailUrl = uploadThumbnail(updated.sessionId, updated.runNumber, updated.thumbnailPath)
        syncRunWithRetry(updated, thumbnailUrl, updated.thumbnailPath)
        return true
    }

    suspend fun applyRemoteThumbnail(
        runId: String,
        role: TimingRole,
        gateIndex: Int?,
        thumbnail: Bitmap
    ): Boolean {
        val run = runDao.getRunById(runId) ?: runDao.getRunByCloudRunId(runId) ?: return false
        val thumbnailPath = cacheRemoteGateThumbnail(
            sessionId = run.sessionId,
            runId = run.id,
            role = role,
            gateIndex = gateIndex,
            thumbnail = thumbnail
        ) ?: return false

        val updated = when (role) {
            TimingRole.START_LINE -> run.copy(
                startImagePath = thumbnailPath,
                thumbnailPath = run.thumbnailPath ?: thumbnailPath
            )
            TimingRole.FINISH_LINE -> run.copy(
                finishImagePath = thumbnailPath,
                thumbnailPath = thumbnailPath
            )
            TimingRole.LAP_GATE -> run.copy(
                lapImagePathsJson = updatedLapImagePathsJson(
                    existingJson = run.lapImagePathsJson,
                    gateIndex = gateIndex ?: 1,
                    thumbnailPath = thumbnailPath
                ),
                thumbnailPath = run.thumbnailPath ?: thumbnailPath
            )
            TimingRole.CONTROL_ONLY -> return false
        }

        runDao.insert(updated)
        return true
    }

    private fun updatedLapGateCalibrationJson(
        existingJson: String?,
        snapshot: GateCalibrationSnapshot
    ): String {
        val existing = existingJson
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching { Json.decodeFromString<List<GateCalibrationSnapshot>>(raw) }
                    .getOrDefault(emptyList())
            }
            ?: emptyList()

        val updated = existing
            .filterNot { it.gateIndex == snapshot.gateIndex && it.role == snapshot.role }
            .plus(snapshot)
            .sortedWith(compareBy<GateCalibrationSnapshot> { it.gateIndex }.thenBy { it.role })

        return Json.encodeToString(updated)
    }

    private fun updatedLapImagePathsJson(
        existingJson: String?,
        gateIndex: Int,
        thumbnailPath: String
    ): String {
        val existing = existingJson
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching { Json.decodeFromString<Map<String, String>>(raw) }
                    .getOrDefault(emptyMap())
            }
            ?: emptyMap()

        val updated = existing.toMutableMap()
        updated[gateIndex.toString()] = thumbnailPath
        return Json.encodeToString(updated.toSortedMap())
    }

    private suspend fun cacheRemoteAthletePhoto(athleteId: String, photoUrl: String?): String? {
        if (photoUrl.isNullOrBlank()) return null
        if (!photoUrl.startsWith("http://") && !photoUrl.startsWith("https://")) {
            return photoUrl
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(photoUrl).openConnection()
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                val bytes = ImageDownloadValidator.readValidatedImageBytes(connection)
                    ?: return@withContext null
                val dir = File(context.filesDir, "athletes")
                dir.mkdirs()
                val file = File(dir, "$athleteId-remote.jpg")
                file.writeBytes(bytes)
                file.absolutePath
            } catch (e: Exception) {
                Log.w("SessionRepository", "Athlete photo cache failed for $athleteId: ${e.safeLogCode()}")
                null
            }
        }
    }

    private suspend fun syncRunsFromCloudForSession(sessionId: String): String? {
        val remoteRuns = cloudSyncService.fetchRuns(sessionId)
        var firstThumbnailPath: String? = null

        for (remoteRun in remoteRuns) {
            val runId = remoteRun.id ?: continue
            val existing = runDao.getRunById(runId) ?: runDao.getRunByCloudRunId(runId)
            val createdAt = parseCloudInstantMillis(remoteRun.createdAt)
                ?: existing?.createdAt
                ?: System.currentTimeMillis()

            if (existing != null && existing.createdAt > createdAt) {
                continue
            }

            val thumbnailPath = cacheRemoteRunThumbnail(
                sessionId = sessionId,
                runId = runId,
                thumbnailUrl = remoteRun.thumbnailUrl
            ) ?: existing?.thumbnailPath

            if (firstThumbnailPath == null) {
                firstThumbnailPath = thumbnailPath
            }

            runDao.insert(remoteRun.toEntity(sessionId, runId, createdAt, thumbnailPath, existing))
        }

        return firstThumbnailPath
    }

    private suspend fun syncSessionWithRetry(session: TrainingSessionEntity): Boolean {
        if (workloadCoordinator.isLiveTimingActive) {
            enqueuePendingUpload(PENDING_SESSION_UPLOADS, session.id)
            return false
        }

        val synced = cloudSyncService.syncSession(session)
        if (synced) {
            removePendingUpload(PENDING_SESSION_UPLOADS, session.id)
        } else {
            enqueuePendingUpload(PENDING_SESSION_UPLOADS, session.id)
        }
        return synced
    }

    private suspend fun syncRunWithRetry(
        run: RunEntity,
        thumbnailUrl: String?,
        thumbnailPath: String? = run.thumbnailPath
    ): Boolean {
        if (workloadCoordinator.isLiveTimingActive) {
            enqueuePendingUpload(PENDING_RUN_UPLOADS, run.id)
            return false
        }

        val synced = cloudSyncService.syncRun(run, thumbnailUrl)
        val thumbnailStillPending = localFileExists(thumbnailPath) && thumbnailUrl == null

        if (synced && !thumbnailStillPending) {
            removePendingUpload(PENDING_RUN_UPLOADS, run.id)
            return true
        }

        enqueuePendingUpload(PENDING_RUN_UPLOADS, run.id)
        return false
    }

    private fun localFileExists(path: String?): Boolean {
        return !path.isNullOrBlank() && File(path).exists()
    }

    private fun enqueuePendingUpload(key: String, id: String) {
        val normalized = normalizedUuidString(id) ?: return
        val updated = pendingUploadIds(key) + normalized
        uploadRetryPrefs.edit().putStringSet(key, updated).apply()
    }

    private fun removePendingUpload(key: String, id: String) {
        val normalized = normalizedUuidString(id) ?: id
        val updated = pendingUploadIds(key) - normalized
        uploadRetryPrefs.edit().putStringSet(key, updated).apply()
    }

    private fun pendingUploadIds(key: String): Set<String> {
        val stored = uploadRetryPrefs.getStringSet(key, emptySet()) ?: emptySet()
        val sanitized = stored.mapNotNull { normalizedUuidString(it) }.toSet()
        if (sanitized != stored) {
            uploadRetryPrefs.edit().putStringSet(key, sanitized).apply()
        }
        return sanitized
    }

    private fun clearPendingCloudUploads() {
        uploadRetryPrefs.edit()
            .remove(PENDING_SESSION_UPLOADS)
            .remove(PENDING_RUN_UPLOADS)
            .apply()
    }

    private fun normalizedUuidString(id: String): String? {
        return runCatching { UUID.fromString(id).toString() }.getOrNull()
    }

    private suspend fun cacheRemoteRunThumbnail(
        sessionId: String,
        runId: String,
        thumbnailUrl: String?
    ): String? {
        if (thumbnailUrl.isNullOrBlank()) return null
        val url = if (thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://")) {
            thumbnailUrl
        } else {
            storageService.getPublicUrl("race-photos", thumbnailUrl)
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection()
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                val bytes = ImageDownloadValidator.readValidatedImageBytes(connection)
                    ?: return@withContext null
                val dir = File(context.filesDir, "thumbnails/$sessionId")
                dir.mkdirs()
                val file = File(dir, "cloud_$runId.jpg")
                file.writeBytes(bytes)
                file.absolutePath
            } catch (e: Exception) {
                Log.w("SessionRepository", "Run thumbnail cache failed for $runId: ${e.safeLogCode()}")
                null
            }
        }
    }

    private suspend fun cacheRemoteGateThumbnail(
        sessionId: String,
        runId: String,
        role: TimingRole,
        gateIndex: Int?,
        thumbnail: Bitmap
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "thumbnails/$sessionId")
                dir.mkdirs()
                val gateSuffix = gateIndex?.let { "_$it" }.orEmpty()
                val file = File(dir, "cloud_${runId}_${role.value}$gateSuffix.jpg")
                FileOutputStream(file).use { out ->
                    thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                file.absolutePath
            } catch (e: Exception) {
                Log.w("SessionRepository", "Remote gate thumbnail cache failed for $runId: ${e.safeLogCode()}")
                null
            }
        }
    }

    private fun SessionDto.toEntity(
        existing: TrainingSessionEntity?,
        createdAt: Long,
        updatedAt: Long
    ): TrainingSessionEntity {
        return TrainingSessionEntity(
            id = id ?: existing?.id ?: UUID.randomUUID().toString(),
            date = createdAt,
            name = name,
            location = location,
            notes = notes,
            distance = distance,
            startType = startType,
            numberOfPhones = existing?.numberOfPhones ?: 1,
            numberOfGates = existing?.numberOfGates ?: 1,
            gateConfigJson = existing?.gateConfigJson,
            thumbnailPath = existing?.thumbnailPath,
            createdAt = createdAt,
            updatedAt = updatedAt,
            cloudId = id,
            lastSyncedAt = updatedAt
        )
    }

    private fun RunDto.toEntity(
        sessionId: String,
        runId: String,
        createdAt: Long,
        thumbnailPath: String?,
        existing: RunEntity?
    ): RunEntity {
        return RunEntity(
            id = runId,
            sessionId = sessionId,
            athleteId = athleteId,
            athleteName = athleteName,
            athleteColor = athleteColor,
            runNumber = runNumber,
            timeSeconds = timeSeconds,
            distance = distance,
            startType = startType,
            numberOfPhones = existing?.numberOfPhones ?: 1,
            reactionTime = reactionTime,
            isPersonalBest = isPersonalBest,
            isSeasonBest = isSeasonBest,
            thumbnailPath = thumbnailPath,
            finishImagePath = thumbnailPath,
            splitsJson = splitsJson,
            gatePosition = existing?.gatePosition ?: 0.5,
            crossingVelocity = existing?.crossingVelocity,
            startGatePosition = existing?.startGatePosition,
            finishGatePosition = existing?.finishGatePosition,
            startCrossingVelocity = existing?.startCrossingVelocity,
            finishCrossingVelocity = existing?.finishCrossingVelocity,
            startCrossingDirection = existing?.startCrossingDirection,
            finishCrossingDirection = existing?.finishCrossingDirection,
            startWorkResolutionWidth = existing?.startWorkResolutionWidth,
            finishWorkResolutionWidth = existing?.finishWorkResolutionWidth,
            workResolutionWidth = existing?.workResolutionWidth,
            startThumbnailDebugJson = existing?.startThumbnailDebugJson,
            finishThumbnailDebugJson = existing?.finishThumbnailDebugJson,
            timingDiagnosticsJson = existing?.timingDiagnosticsJson,
            localGateFramesDataJson = existing?.localGateFramesDataJson,
            finishDetectorY = existing?.finishDetectorY,
            finishInterpolationAlpha = existing?.finishInterpolationAlpha,
            finishFramePick = existing?.finishFramePick,
            finishS0 = existing?.finishS0,
            finishS1 = existing?.finishS1,
            finishIsFrontCamera = existing?.finishIsFrontCamera,
            finishDetectorTriggerFramePts = existing?.finishDetectorTriggerFramePts,
            finishChosenThumbnailFramePts = existing?.finishChosenThumbnailFramePts,
            finishSavedThumbnailFramePts = existing?.finishSavedThumbnailFramePts,
            localGateRole = existing?.localGateRole,
            cloudRunId = runId,
            cloudSyncPolicy = existing?.cloudSyncPolicy,
            crossingTimestampNanos = existing?.crossingTimestampNanos,
            createdAt = createdAt
        )
    }

    private fun parseCloudDateMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            LocalDate.parse(raw.take(10))
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }.getOrElse {
            runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        }
    }

    private fun parseCloudInstantMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }
            .getOrElse { parseCloudDateMillis(raw) }
    }

    private suspend fun updateAthleteBests(
        athlete: AthleteEntity?,
        timeSeconds: Double,
        distance: Double,
        startType: String
    ): AthleteBestResult {
        athlete ?: return AthleteBestResult.None

        val key = AthleteEntity.prKey(distance, startType)
        val personalBests = athlete.personalBests().toMutableMap()
        val seasonBests = athlete.seasonBests().toMutableMap()

        val previousPersonal = personalBests[key]
        val previousSeason = seasonBests[key]
        val isPersonalBest = previousPersonal == null || timeSeconds < previousPersonal
        val isSeasonBest = previousSeason == null || timeSeconds < previousSeason

        if (isPersonalBest) personalBests[key] = timeSeconds
        if (isSeasonBest) seasonBests[key] = timeSeconds

        if (isPersonalBest || isSeasonBest) {
            val updatedAthlete = athlete.copy(
                personalBestsJson = AthleteEntity.encodeBestMap(personalBests),
                seasonBestsJson = AthleteEntity.encodeBestMap(seasonBests),
                updatedAt = System.currentTimeMillis()
            )
            athleteDao.update(updatedAthlete)
            try { cloudSyncService.syncAthlete(updatedAthlete) } catch (_: Exception) { }
        }

        return AthleteBestResult(
            isTracked = true,
            isPersonalBest = isPersonalBest,
            isSeasonBest = isSeasonBest
        )
    }

    private suspend fun checkAndUpdateFlyingPr(
        timeSeconds: Double,
        distance: Double,
        startType: String
    ) {
        if (startType.lowercase() != "flying") return

        val matchedDistance = when (distance.toInt()) {
            10 -> FlyingDistance.METERS_10
            20 -> FlyingDistance.METERS_20
            30 -> FlyingDistance.METERS_30
            else -> return
        }

        val trackedDistance = settingsRepository.flyingDistance.first()
            ?.let(FlyingDistance::fromRawValue)
        val currentPr = settingsRepository.flyingPR.first()

        if (trackedDistance != matchedDistance) {
            if (trackedDistance == null) {
                settingsRepository.setFlyingDistance(matchedDistance.rawValue)
                settingsRepository.setFlyingPR(timeSeconds)
                syncFlyingPrToCloud(matchedDistance, timeSeconds)
            }
            return
        }

        if (currentPr == null || timeSeconds < currentPr) {
            settingsRepository.setFlyingDistance(matchedDistance.rawValue)
            settingsRepository.setFlyingPR(timeSeconds)
            syncFlyingPrToCloud(matchedDistance, timeSeconds)
        }
    }

    private suspend fun syncFlyingPrToCloud(distance: FlyingDistance, timeSeconds: Double) {
        if (profileService.updateFlyingPr(distance, timeSeconds)) {
            settingsRepository.setPendingFlyingPrSync("")
        } else {
            settingsRepository.setPendingFlyingPrSync("${distance.rawValue}:$timeSeconds")
        }
    }

    private fun getSeasonStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private suspend fun uploadThumbnail(
        sessionId: String,
        lapNumber: Int,
        localPath: String?
    ): String? {
        localPath ?: return null
        if (workloadCoordinator.isLiveTimingActive) return null
        return withContext(Dispatchers.IO) {
            try {
                val file = File(localPath)
                if (file.exists()) {
                    storageService.uploadThumbnail(
                        bucket = "race-photos",
                        path = "sessions/$sessionId/run_$lapNumber.jpg",
                        imageData = file.readBytes()
                    )
                } else null
            } catch (e: Exception) {
                Log.w("SessionRepository", "Thumbnail upload failed (non-critical): ${e.safeLogCode()}")
                null
            }
        }
    }

    private suspend fun schedulePostSessionNotifications() {
        runCatching {
            notificationService.scheduleTrainingReminder()

            val completedSessionCount = sessionDao.getTotalSessionCount().first()
            if (!subscriptionManager.isProUser.value) {
                when (completedSessionCount) {
                    1 -> notificationService.scheduleFirstSessionNudge()
                    3 -> notificationService.scheduleMilestoneNudge()
                }
            }

            queuePendingDiscountMilestoneIfEligible(completedSessionCount)

            if (completedSessionCount == NotificationTiming.RATING_PROMPT_SESSION_COUNT) {
                notificationService.scheduleRatingPrompt()
            }
        }.onFailure { error ->
            Log.w("SessionRepository", "Post-session notification scheduling failed: ${error.safeLogCode()}")
        }
    }

    private suspend fun queuePendingDiscountMilestoneIfEligible(completedSessionCount: Int) {
        if (
            completedSessionCount != FIRST_DISCOUNT_MILESTONE_SESSION_COUNT &&
            completedSessionCount != SECOND_DISCOUNT_MILESTONE_SESSION_COUNT
        ) {
            return
        }
        if (subscriptionManager.isProUser.value) return
        if (completedSessionCount <= settingsRepository.lastDiscountMilestoneFired.first()) return
        if (!settingsRepository.hasDismissedStandardPaywall.first()) return
        if (settingsRepository.discountPaywallShowCount.first() >= DISCOUNT_PAYWALL_MAX_SHOWS) return

        val now = System.currentTimeMillis()
        val lastShownAt = settingsRepository.discountPaywallLastShownAtMillis.first()
        if (lastShownAt > 0L && now - lastShownAt < DISCOUNT_PAYWALL_GAP_MILLIS) return

        settingsRepository.setPendingDiscountMilestone(completedSessionCount)
    }

    private suspend fun saveThumbnail(
        sessionId: String,
        lapNumber: Int,
        bitmap: Bitmap?,
        gatePosition: Float = 0.5f
    ): String? {
        bitmap ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val burned = ThumbnailUtils.burnGateLine(
                    source = bitmap,
                    normalizedX = gatePosition,
                    accentColor = 0xFFFF0000.toInt() // Red, matching existing overlay
                )
                val dir = File(context.filesDir, "thumbnails/$sessionId")
                dir.mkdirs()
                val file = File(dir, "lap_$lapNumber.jpg")
                FileOutputStream(file).use { out ->
                    burned.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                burned.recycle()
                file.absolutePath
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun persistLocalGateFrames(
        sessionId: String,
        runId: String,
        frames: List<LocalGateFrameSnapshot>
    ): String? {
        if (frames.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "thumbnails/$sessionId")
                dir.mkdirs()
                val persisted = frames.mapIndexedNotNull { index, frame ->
                    val file = File(dir, "gateframe_${index}_${runId}.jpg")
                    FileOutputStream(file).use { out ->
                        frame.bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    PersistedLocalGateFrameData(
                        imagePath = file.absolutePath,
                        frameNumber = frame.frameNumber,
                        timestampInterval = frame.timestampNanos.toDouble() / 1_000_000_000.0,
                        occupancy = frame.occupancy,
                        longestRun = frame.longestRun,
                        isTracking = frame.isTracking,
                        torsoTop = frame.torsoTop,
                        torsoBottom = frame.torsoBottom,
                        frameHeight = frame.frameHeight,
                        leftShoulderY = frame.leftShoulderY,
                        rightShoulderY = frame.rightShoulderY,
                        leftHipY = frame.leftHipY,
                        rightHipY = frame.rightHipY,
                        runStartY = frame.runStartY,
                        runEndY = frame.runEndY
                    )
                }
                persisted.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
            } catch (e: Exception) {
                Log.w("SessionRepository", "Failed to persist local gate frames for $runId: ${e.safeLogCode()}")
                null
            }
        }
    }
}

private fun Throwable.safeLogCode(): String {
    return this::class.java.simpleName.ifBlank { "error" }
}

private data class AthleteBestResult(
    val isTracked: Boolean,
    val isPersonalBest: Boolean,
    val isSeasonBest: Boolean
) {
    companion object {
        val None = AthleteBestResult(
            isTracked = false,
            isPersonalBest = false,
            isSeasonBest = false
        )
    }
}

@Serializable
private data class GateCalibrationSnapshot(
    val gateIndex: Int,
    val role: String,
    val gatePosition: Double,
    val velocityPxPerSec: Double,
    val crossingDirection: String?,
    val workWidth: Int?,
    val thumbnailDebugJson: String?
)

@Serializable
private data class PersistedLocalGateFrameData(
    val imagePath: String,
    val frameNumber: Long,
    val timestampInterval: Double,
    val occupancy: Float,
    val longestRun: Int,
    val isTracking: Boolean,
    val torsoTop: Int,
    val torsoBottom: Int,
    val frameHeight: Int,
    val leftShoulderY: Float?,
    val rightShoulderY: Float?,
    val leftHipY: Float?,
    val rightHipY: Float?,
    val runStartY: Int,
    val runEndY: Int
)
