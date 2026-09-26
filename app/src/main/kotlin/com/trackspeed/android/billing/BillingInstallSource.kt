package com.trackspeed.android.billing

import android.content.Context
import android.os.Build

internal enum class BillingInstallSource {
    GOOGLE_PLAY,
    SIDELOADED_DEBUG,
    SIDELOADED_RELEASE;

    companion object {
        private const val GOOGLE_PLAY_INSTALLER = "com.android.vending"

        fun resolve(
            isDebugBuild: Boolean,
            installerPackageName: String?
        ): BillingInstallSource {
            if (installerPackageName == GOOGLE_PLAY_INSTALLER) return GOOGLE_PLAY
            return if (isDebugBuild) SIDELOADED_DEBUG else SIDELOADED_RELEASE
        }
    }
}

internal fun Context.billingInstallSource(isDebugBuild: Boolean): BillingInstallSource {
    val installerPackageName = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    return BillingInstallSource.resolve(
        isDebugBuild = isDebugBuild,
        installerPackageName = installerPackageName
    )
}
