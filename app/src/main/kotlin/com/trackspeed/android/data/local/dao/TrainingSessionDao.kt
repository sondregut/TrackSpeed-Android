package com.trackspeed.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingSessionDao {

    @Query("SELECT * FROM training_sessions ORDER BY date DESC")
    fun getAllSessions(): Flow<List<TrainingSessionEntity>>

    @Query("SELECT * FROM training_sessions WHERE id = :id")
    suspend fun getSession(id: String): TrainingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: TrainingSessionEntity)

    @Update
    suspend fun update(session: TrainingSessionEntity)

    @Delete
    suspend fun delete(session: TrainingSessionEntity)

    @Query("DELETE FROM training_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM runs WHERE sessionId = :sessionId")
    suspend fun getRunCount(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM training_sessions")
    fun getTotalSessionCount(): Flow<Int>
}
