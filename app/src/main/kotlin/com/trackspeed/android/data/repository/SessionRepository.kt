package com.trackspeed.android.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.trackspeed.android.data.local.dao.RunDao
import com.trackspeed.android.data.local.dao.SessionBestTime
import com.trackspeed.android.data.local.dao.TrainingSessionDao
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.ui.screens.timing.SoloLapResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: TrainingSessionDao,
    private val runDao: RunDao
) {

    fun getAllSessions(): Flow<List<TrainingSessionEntity>> {
        return sessionDao.getAllSessions()
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
        laps: List<SoloLapResult>
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

        // Save runs (skip lap 0 = START)
        val actualLaps = laps.filter { it.lapNumber > 0 }
        val bestTime = actualLaps.minOfOrNull { it.lapTimeSeconds }

        for (lap in actualLaps) {
            val thumbnailPath = saveThumbnail(sessionId, lap.lapNumber, lap.thumbnail)
            val run = RunEntity(
                sessionId = sessionId,
                runNumber = lap.lapNumber,
                timeSeconds = lap.lapTimeSeconds,
                distance = distance,
                startType = startType,
                isPersonalBest = lap.lapTimeSeconds == bestTime,
                thumbnailPath = thumbnailPath,
                createdAt = now
            )
            runDao.insert(run)
        }

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

    fun getDistinctDistances(): Flow<List<Double>> {
        return runDao.getDistinctDistances()
    }

    fun getPersonalBestFlow(distance: Double): Flow<Double?> {
        return runDao.getPersonalBestFlow(distance)
    }

    fun getBestTimePerSession(): Flow<List<SessionBestTime>> {
        return runDao.getBestTimePerSession()
    }

    private suspend fun saveThumbnail(
        sessionId: String,
        lapNumber: Int,
        bitmap: Bitmap?
    ): String? {
        bitmap ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "thumbnails/$sessionId")
                dir.mkdirs()
                val file = File(dir, "lap_$lapNumber.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                file.absolutePath
            } catch (e: Exception) {
                null
            }
        }
    }
}
