package com.trackspeed.android

import android.app.Application
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.trackspeed.android.notifications.NotificationService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TrackSpeedApp : Application() {

    @Inject
    lateinit var notificationService: NotificationService

    override fun onCreate() {
        super.onCreate()

        // Initialize RevenueCat Purchases SDK
        if (BuildConfig.REVENUECAT_API_KEY.isNotEmpty()) {
            Purchases.logLevel = LogLevel.DEBUG
            Purchases.configure(
                PurchasesConfiguration.Builder(this, BuildConfig.REVENUECAT_API_KEY).build()
            )
        }

        // Create notification channel on app startup
        notificationService.createNotificationChannel()
    }
}
