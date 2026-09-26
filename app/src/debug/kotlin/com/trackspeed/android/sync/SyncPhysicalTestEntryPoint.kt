package com.trackspeed.android.sync

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access to the real singleton graph for two-phone physical probes. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncPhysicalTestEntryPoint {
    fun clockSyncManager(): ClockSyncManager
}
