package com.trackspeed.android

import android.app.Application
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TrackSpeedApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize RevenueCat Purchases SDK
        if (BuildConfig.REVENUECAT_API_KEY.isNotEmpty()) {
            Purchases.logLevel = LogLevel.DEBUG
            Purchases.configure(
                PurchasesConfiguration.Builder(this, BuildConfig.REVENUECAT_API_KEY).build()
            )
        }
    }
}
