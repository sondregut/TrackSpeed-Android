package com.trackspeed.android.analytics

import com.trackspeed.android.data.model.SportCategory
import com.trackspeed.android.ui.screens.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsServiceTest {

    @Test
    fun redactsEmailsAndUuidsFromStringProperties() {
        val sanitized = AnalyticsService.sanitizedProperties(
            mapOf(
                "message" to "user a@b.com id 123e4567-e89b-12d3-a456-426614174000",
                "count" to 3
            )
        )

        assertEquals("user [redacted] id [redacted]", sanitized["message"])
        assertEquals(3, sanitized["count"])
    }

    @Test
    fun dropsNullProperties() {
        val sanitized = AnalyticsService.sanitizedProperties(
            mapOf("present" to true, "missing" to null)
        )

        assertTrue(sanitized.containsKey("present"))
        assertFalse(sanitized.containsKey("missing"))
    }

    @Test
    fun sportCategoryAnalyticsValueIsStableSnakeCase() {
        assertEquals("middle_distance", AnalyticsService.sportCategoryRawValue(SportCategory.MIDDLE_DISTANCE))
        assertEquals("field_events", AnalyticsService.sportCategoryRawValue(SportCategory.FIELD_EVENTS))
    }

    @Test
    fun onboardingStepAnalyticsNamesMatchIosFunnel() {
        assertEquals("getStarted", OnboardingStep.WELCOME.analyticsName)
        assertEquals("flyingPB", OnboardingStep.FLYING_TIME.analyticsName)
        assertEquals("referralCodeEntry", OnboardingStep.ATTRIBUTION.analyticsName)
        assertEquals("paywallRecovery", OnboardingStep.PAYWALL_RECOVERY.analyticsName)
    }
}
