package com.trackspeed.android.di

import android.content.Context
import com.trackspeed.android.sync.BleClockSyncService
import com.trackspeed.android.sync.ClockSyncManager
import com.trackspeed.android.sync.CloudTimingRelay
import com.trackspeed.android.cloud.DeviceIdProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideBleClockSyncService(
        @ApplicationContext context: Context,
        cloudTimingRelay: CloudTimingRelay,
        deviceIdProvider: DeviceIdProvider
    ): BleClockSyncService {
        return BleClockSyncService(context, cloudTimingRelay, deviceIdProvider)
    }

    @Provides
    @Singleton
    fun provideClockSyncManager(
        @ApplicationContext context: Context,
        bleClockSyncService: BleClockSyncService
    ): ClockSyncManager {
        return ClockSyncManager(context, bleClockSyncService)
    }
}
