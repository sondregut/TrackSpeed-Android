package com.trackspeed.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.trackspeed.android.data.local.entities.AthleteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AthleteDao {

    @Query("SELECT * FROM athletes ORDER BY name")
    fun getAllAthletes(): Flow<List<AthleteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(athlete: AthleteEntity)

    @Update
    suspend fun update(athlete: AthleteEntity)

    @Delete
    suspend fun delete(athlete: AthleteEntity)
}
