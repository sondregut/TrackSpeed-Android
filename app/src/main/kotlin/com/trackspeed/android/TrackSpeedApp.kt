package com.trackspeed.android

import android.app.Application
import android.util.Log
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.trackspeed.android.analytics.AnalyticsService
import com.trackspeed.android.analytics.CrashReportingService
import com.trackspeed.android.cloud.CrossingDebugUploadQueue
import com.trackspeed.android.cloud.RaceEventService
import com.trackspeed.android.cloud.ThumbnailUploadQueue
import com.trackspeed.android.diagnostics.DurableDeviceLogUploadQueue
import com.trackspeed.android.notifications.NotificationService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class TrackSpeedApp : Application() {

    @Inject
    lateinit var notificationService: NotificationService

    @Inject
    lateinit var thumbnailUploadQueue: ThumbnailUploadQueue

    @Inject
    lateinit var crossingDebugUploadQueue: CrossingDebugUploadQueue

    @Inject
    lateinit var raceEventService: RaceEventService

    @Inject
    lateinit var durableDeviceLogUploadQueue: DurableDeviceLogUploadQueue

    @Inject
    lateinit var analyticsService: AnalyticsService

    @Inject
    lateinit var crashReportingService: CrashReportingService

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        analyticsService.configure(this)
        crashReportingService.configure()

        // Initialize RevenueCat Purchases SDK
        if (BuildConfig.REVENUECAT_API_KEY.isNotEmpty()) {
            val revenueCatDebugLoggingEnabled = getSharedPreferences("trackspeed", MODE_PRIVATE)
                .getBoolean("revenueCatDebugLoggingEnabled", false)
            Purchases.logLevel = if (BuildConfig.DEBUG && revenueCatDebugLoggingEnabled) {
                LogLevel.DEBUG
            } else {
                LogLevel.WARN
            }
            Purchases.configure(
                PurchasesConfiguration.Builder(this, BuildConfig.REVENUECAT_API_KEY).build()
            )
            syncRevenueCatPostHogDistinctId()
        }

        // Create notification channel on app startup
        notificationService.createNotificationChannel()

        applicationScope.launch {
            runCatching {
                thumbnailUploadQueue.processQueue()
                crossingDebugUploadQueue.processQueue()
                durableDeviceLogUploadQueue.processQueue()
                raceEventService.processPendingRaceEvents("app_start")
            }.onFailure {
                Log.w(TAG, "Startup upload queue processing skipped", it)
            }
        }
    }

    private fun syncRevenueCatPostHogDistinctId() {
        val distinctId = analyticsService.distinctId()
        if (distinctId.isBlank()) return
        runCatching {
            Purchases.sharedInstance.setAttributes(
                mapOf("\$posthogUserId" to distinctId)
            )
        }.onFailure {
            Log.w(TAG, "Failed to set RevenueCat PostHog attribute", it)
        }
    }

    private companion object {
        const val TAG = "TrackSpeedApp"
    }
}
