package com.trackspeed.android.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.trackspeed.android.cloud.CloudSyncService
import com.trackspeed.android.cloud.StorageService
import com.trackspeed.android.data.local.dao.RunDao
import com.trackspeed.android.data.local.dao.SessionBestTime
import com.trackspeed.android.data.local.dao.SessionSummary
import com.trackspeed.android.data.local.dao.TrainingSessionDao
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.ui.screens.timing.SoloLapResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.trackspeed.android.ui.components.ThumbnailUtils
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: TrainingSessionDao,
    private val runDao: RunDao,
    private val cloudSyncService: CloudSyncService,
    private val storageService: StorageService
) {

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
        sessionDao.deleteById(id)
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
            createdAt = now,
            updatedAt = now
        )
        sessionDao.insert(session)
        // Fire-and-forget cloud sync
        try { cloudSyncService.syncSession(session) } catch (_: Exception) { }

        // Save runs (skip lap 0 = START)
        val actualLaps = laps.filter { it.lapNumber > 0 }
        val bestTime = actualLaps.minOfOrNull { it.lapTimeSeconds }
        val seasonStart = getSeasonStartMillis()
        var currentSeasonBest = runDao.getSeasonBest(distance, seasonStart)

        for (lap in actualLaps) {
            // Cycle through athletes round-robin (1-indexed lap numbers)
            val athlete = if (athletes.isNotEmpty()) {
                athletes[(lap.lapNumber - 1) % athletes.size]
            } else {
                null
            }

            val isSeasonBest = currentSeasonBest == null || lap.lapTimeSeconds < currentSeasonBest

            val thumbnailPath = saveThumbnail(sessionId, lap.lapNumber, lap.thumbnail, lap.gatePosition)
            val thumbnailUrl = uploadThumbnail(sessionId, lap.lapNumber, thumbnailPath)
            val run = RunEntity(
                sessionId = sessionId,
                athleteId = athlete?.id,
                athleteName = athlete?.name,
                athleteColor = athlete?.color,
                runNumber = lap.lapNumber,
                timeSeconds = lap.lapTimeSeconds,
                distance = distance,
                startType = startType,
                isPersonalBest = lap.lapTimeSeconds == bestTime,
                isSeasonBest = isSeasonBest,
                thumbnailPath = thumbnailPath,
                createdAt = now
            )
            runDao.insert(run)
            if (isSeasonBest) {
                currentSeasonBest = lap.lapTimeSeconds
            }
            try { cloudSyncService.syncRun(run, thumbnailUrl) } catch (_: Exception) { }
        }

        return sessionId
    }

    /**
     * Save a multi-device race result (single run with known time).
     */
    suspend fun saveRaceResult(
        distance: Double,
        startType: String,
        timeSeconds: Double
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val session = TrainingSessionEntity(
            id = sessionId,
            date = now,
            name = null,
            distance = distance,
            startType = startType,
            createdAt = now,
            updatedAt = now
        )
        sessionDao.insert(session)
        // Fire-and-forget cloud sync
        try { cloudSyncService.syncSession(session) } catch (_: Exception) { }

        val run = RunEntity(
            sessionId = sessionId,
            runNumber = 1,
            timeSeconds = timeSeconds,
            distance = distance,
            startType = startType,
            createdAt = now
        )
        runDao.insert(run)
        try { cloudSyncService.syncRun(run) } catch (_: Exception) { }

        return sessionId
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
        runDao.deleteById(runId)
        // Clean up thumbnail file
        run?.thumbnailPath?.let { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    suspend fun updateRunDistance(runId: String, newDistance: Double) {
        runDao.updateDistance(runId, newDistance)
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
                Log.w("SessionRepository", "Thumbnail upload failed (non-critical)", e)
                null
            }
        }
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
}
