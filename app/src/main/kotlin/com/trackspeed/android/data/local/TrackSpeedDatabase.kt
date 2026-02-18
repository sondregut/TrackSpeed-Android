package com.trackspeed.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = false
)
abstract class TrackSpeedDatabase : RoomDatabase() {
    abstract fun trainingSessionDao(): TrainingSessionDao
    abstract fun runDao(): RunDao
    abstract fun athleteDao(): AthleteDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN athleteId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN athleteColor TEXT DEFAULT NULL")
            }
        }
    }
}
