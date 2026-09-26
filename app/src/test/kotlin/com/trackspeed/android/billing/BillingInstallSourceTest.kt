package com.trackspeed.android.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class BillingInstallSourceTest {

    @Test
    fun `Google Play installer is recognized for debug and release builds`() {
        assertEquals(
            BillingInstallSource.GOOGLE_PLAY,
            BillingInstallSource.resolve(
                isDebugBuild = true,
                installerPackageName = "com.android.vending"
            )
        )
        assertEquals(
            BillingInstallSource.GOOGLE_PLAY,
            BillingInstallSource.resolve(
                isDebugBuild = false,
                installerPackageName = "com.android.vending"
            )
        )
    }

    @Test
    fun `ADB debug install explains the license tester requirement`() {
        assertEquals(
            BillingInstallSource.SIDELOADED_DEBUG,
            BillingInstallSource.resolve(
                isDebugBuild = true,
                installerPackageName = null
            )
        )
        assertEquals(
            BillingInstallSource.SIDELOADED_DEBUG,
            BillingInstallSource.resolve(
                isDebugBuild = true,
                installerPackageName = "com.android.shell"
            )
        )
    }

    @Test
    fun `non Play release install directs the user back to Google Play`() {
        assertEquals(
            BillingInstallSource.SIDELOADED_RELEASE,
            BillingInstallSource.resolve(
                isDebugBuild = false,
                installerPackageName = null
            )
        )
        assertEquals(
            BillingInstallSource.SIDELOADED_RELEASE,
            BillingInstallSource.resolve(
                isDebugBuild = false,
                installerPackageName = "com.android.shell"
            )
        )
    }
}
