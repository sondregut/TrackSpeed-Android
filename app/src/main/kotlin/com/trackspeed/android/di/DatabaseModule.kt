package com.trackspeed.android.di

import android.content.Context
import androidx.room.Room
import com.trackspeed.android.data.local.TrackSpeedDatabase
import com.trackspeed.android.data.local.dao.AthleteDao
import com.trackspeed.android.data.local.dao.RunDao
import com.trackspeed.android.data.local.dao.TrainingSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): TrackSpeedDatabase {
        return Room.databaseBuilder(
            context,
            TrackSpeedDatabase::class.java,
            "trackspeed.db"
        ).build()
    }

    @Provides
    fun provideTrainingSessionDao(database: TrackSpeedDatabase): TrainingSessionDao {
        return database.trainingSessionDao()
    }

    @Provides
    fun provideRunDao(database: TrackSpeedDatabase): RunDao {
        return database.runDao()
    }

    @Provides
    fun provideAthleteDao(database: TrackSpeedDatabase): AthleteDao {
        return database.athleteDao()
    }
}
