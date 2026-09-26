package com.trackspeed.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trackspeed.android.data.local.entities.RunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {

    @Query("SELECT * FROM runs WHERE sessionId = :sessionId ORDER BY runNumber")
    fun getRunsForSession(sessionId: String): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE id = :runId")
    suspend fun getRunById(runId: String): RunEntity?

    @Query("SELECT * FROM runs WHERE cloudRunId = :cloudRunId LIMIT 1")
    suspend fun getRunByCloudRunId(cloudRunId: String): RunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: RunEntity)

    @Delete
    suspend fun delete(run: RunEntity)

    @Query("DELETE FROM runs WHERE id = :runId")
    suspend fun deleteById(runId: String)

    @Query("UPDATE runs SET distance = :newDistance, isPersonalBest = 0, isSeasonBest = 0 WHERE id = :runId")
    suspend fun updateDistance(runId: String, newDistance: Double)

    @Query("""SELECT MIN(timeSeconds) FROM runs WHERE distance = :distance
        AND startType = :startType AND (numberOfPhones > 1) = (:numberOfPhones > 1)
        AND athleteId IS :athleteId AND numberOfPhones > 0 AND (cloudSyncPolicy IS NULL OR cloudSyncPolicy NOT IN ('localOnlyWatchBeta', 'localOnlyOnePhoneSprint')) AND timeSeconds > 0 AND distance > 0""")
    suspend fun getPersonalBest(distance: Double, startType: String, numberOfPhones: Int, athleteId: String?): Double?

    @Query("SELECT COUNT(*) FROM runs")
    fun getTotalRunCount(): Flow<Int>

    @Query("SELECT * FROM runs ORDER BY timeSeconds ASC")
    fun getAllRunsSortedByTime(): Flow<List<RunEntity>>

    @Query("""SELECT CASE WHEN COUNT(DISTINCT distance || ':' || startType || ':' || (numberOfPhones > 1)) = 1
        THEN MIN(timeSeconds) ELSE NULL END FROM runs WHERE timeSeconds > 0 AND distance > 0 AND numberOfPhones > 0 AND (cloudSyncPolicy IS NULL OR cloudSyncPolicy NOT IN ('localOnlyWatchBeta', 'localOnlyOnePhoneSprint'))""")
    fun getGlobalBestTime(): Flow<Double?>

    @Query("SELECT DISTINCT distance FROM runs ORDER BY distance ASC")
    fun getDistinctDistances(): Flow<List<Double>>

    @Query("""SELECT CASE WHEN COUNT(DISTINCT startType || ':' || (numberOfPhones > 1)) = 1
        THEN MIN(timeSeconds) ELSE NULL END FROM runs WHERE distance = :distance
        AND timeSeconds > 0 AND distance > 0 AND numberOfPhones > 0 AND (cloudSyncPolicy IS NULL OR cloudSyncPolicy NOT IN ('localOnlyWatchBeta', 'localOnlyOnePhoneSprint'))""")
    fun getPersonalBestFlow(distance: Double): Flow<Double?>

    @Query("""SELECT sessionId, MIN(timeSeconds) AS timeSeconds FROM runs
        WHERE timeSeconds > 0 AND distance > 0 AND numberOfPhones > 0 AND (cloudSyncPolicy IS NULL OR cloudSyncPolicy NOT IN ('localOnlyWatchBeta', 'localOnlyOnePhoneSprint')) GROUP BY sessionId
        HAVING COUNT(DISTINCT distance || ':' || startType || ':' || (numberOfPhones > 1)) = 1""")
    fun getBestTimePerSession(): Flow<List<SessionBestTime>>

    @Query("""SELECT sessionId, COUNT(*) AS runCount,
        CASE WHEN COUNT(DISTINCT distance || ':' || startType || ':' || (numberOfPhones > 1)) = 1
            AND MIN(timeSeconds) > 0 AND MIN(distance) > 0 AND MIN(numberOfPhones) > 0
            AND SUM(CASE WHEN cloudSyncPolicy IN ('localOnlyWatchBeta', 'localOnlyOnePhoneSprint') THEN 1 ELSE 0 END) = 0
        THEN MIN(timeSeconds) ELSE NULL END AS bestTime FROM runs GROUP BY sessionId""")
    fun getSessionSummaries(): Flow<List<SessionSummary>>

    @Query("SELECT DISTINCT startType FROM runs ORDER BY startType ASC")
    fun getDistinctStartTypes(): Flow<List<String>>

    @Query("""
        SELECT MIN(timeSeconds) FROM runs
        WHERE distance = :distance
        AND startType = :startType AND (numberOfPhones > 1) = (:numberOfPhones > 1)
        AND athleteId IS :athleteId AND numberOfPhones > 0 AND (cloudSyncPolicy IS NULL OR cloudSyncPolicy NOT IN ('localOnlyWatchBeta', 'localOnlyOnePhoneSprint')) AND timeSeconds > 0 AND distance > 0
        AND createdAt >= :seasonStartMillis
    """)
    suspend fun getSeasonBest(distance: Double, seasonStartMillis: Long, startType: String, numberOfPhones: Int, athleteId: String?): Double?

    @Query("DELETE FROM runs")
    suspend fun deleteAll()
}

data class SessionBestTime(
    val sessionId: String,
    val timeSeconds: Double
)

data class SessionSummary(
    val sessionId: String,
    val runCount: Int,
    val bestTime: Double?
)
