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
    version = 9,
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN cloudId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN lastSyncedAt INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN isSeasonBest INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Normalize legacy start type values to match iOS conventions
                db.execSQL("UPDATE training_sessions SET startType = 'flying' WHERE startType IN ('standing', 'block')")
                db.execSQL("UPDATE training_sessions SET startType = 'touchRelease' WHERE startType = 'touch'")
                db.execSQL("UPDATE training_sessions SET startType = 'voiceCommand' WHERE startType = 'voice'")
                db.execSQL("UPDATE training_sessions SET startType = 'inFrame' WHERE startType = 'inframe'")

                db.execSQL("UPDATE runs SET startType = 'flying' WHERE startType IN ('standing', 'block')")
                db.execSQL("UPDATE runs SET startType = 'touchRelease' WHERE startType = 'touch'")
                db.execSQL("UPDATE runs SET startType = 'voiceCommand' WHERE startType = 'voice'")
                db.execSQL("UPDATE runs SET startType = 'inFrame' WHERE startType = 'inframe'")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN crossingTimestampNanos INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN numberOfPhones INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN numberOfGates INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN gateConfigJson TEXT DEFAULT NULL")

                db.execSQL("ALTER TABLE runs ADD COLUMN numberOfPhones INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE runs ADD COLUMN startImagePath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishImagePath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN lapImagePathsJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN splitsJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN gatePosition REAL NOT NULL DEFAULT 0.5")
                db.execSQL("ALTER TABLE runs ADD COLUMN crossingVelocity REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN startGatePosition REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishGatePosition REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN startCrossingVelocity REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishCrossingVelocity REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN startCrossingDirection TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishCrossingDirection TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN startWorkResolutionWidth INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishWorkResolutionWidth INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN workResolutionWidth INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN startThumbnailDebugJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishThumbnailDebugJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN timingDiagnosticsJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN localGateFramesDataJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN localGateRole TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN cloudRunId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN cloudSyncPolicy TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN finishDetectorY REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishInterpolationAlpha REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishFramePick TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishS0 REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishS1 REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishIsFrontCamera INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishDetectorTriggerFramePts INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishChosenThumbnailFramePts INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN finishSavedThumbnailFramePts INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE athletes ADD COLUMN birthdate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE athletes ADD COLUMN gender TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE athletes ADD COLUMN personalBestsJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE athletes ADD COLUMN seasonBestsJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE athletes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE athletes SET updatedAt = createdAt WHERE updatedAt = 0")
            }
        }
    }
}
