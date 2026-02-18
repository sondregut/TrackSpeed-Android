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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: RunEntity)

    @Delete
    suspend fun delete(run: RunEntity)

    @Query("SELECT MIN(timeSeconds) FROM runs WHERE distance = :distance")
    suspend fun getPersonalBest(distance: Double): Double?

    @Query("SELECT COUNT(*) FROM runs")
    fun getTotalRunCount(): Flow<Int>

    @Query("SELECT * FROM runs ORDER BY timeSeconds ASC")
    fun getAllRunsSortedByTime(): Flow<List<RunEntity>>

    @Query("SELECT DISTINCT distance FROM runs ORDER BY distance ASC")
    fun getDistinctDistances(): Flow<List<Double>>

    @Query("SELECT MIN(timeSeconds) FROM runs WHERE distance = :distance")
    fun getPersonalBestFlow(distance: Double): Flow<Double?>

    @Query("SELECT sessionId, MIN(timeSeconds) AS timeSeconds FROM runs GROUP BY sessionId")
    fun getBestTimePerSession(): Flow<List<SessionBestTime>>
}

data class SessionBestTime(
    val sessionId: String,
    val timeSeconds: Double
)
