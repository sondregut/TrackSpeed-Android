package com.trackspeed.android.analytics

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrashReportingService @Inject constructor(
    private val analyticsService: AnalyticsService
) {
    fun configure() {
        Log.i(TAG, "Crash reporting wired via PostHog (autocapture: ON)")
    }

    fun log(error: Throwable) {
        Log.e(TAG, "Non-fatal: ${error.message}", error)
        analyticsService.captureException(
            error,
            mapOf(
                "\$exception_type" to error::class.java.simpleName,
                "\$exception_message" to AnalyticsService.redactSensitiveString(error.message.orEmpty()),
                "\$exception_level" to "error"
            )
        )
    }

    fun log(message: String) {
        Log.i(TAG, AnalyticsService.redactSensitiveString(message))
        analyticsService.trackRaw(
            "\$breadcrumb",
            mapOf("message" to message)
        )
    }

    private companion object {
        const val TAG = "CrashReporting"
    }
}
