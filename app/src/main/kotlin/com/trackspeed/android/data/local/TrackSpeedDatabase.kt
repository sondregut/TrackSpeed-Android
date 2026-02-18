package com.trackspeed.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.dao.RunDao
import com.trackspeed.android.data.local.dao.TrainingSessionDao
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity

@Database(
    entities = [
        TrainingSessionEntity::class,
        RunEntity::class,
        AthleteEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TrackSpeedDatabase : RoomDatabase() {
    abstract fun trainingSessionDao(): TrainingSessionDao
    abstract fun runDao(): RunDao
    abstract fun athleteDao(): AthleteDao
}
