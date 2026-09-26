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

    @Test
    fun sendsOnlyStableAcquisitionAndValueEventsToFirebase() {
        assertEquals(
            "onboarding_completed",
            AnalyticsService.firebaseEventName(AnalyticsEvent.ONBOARDING_COMPLETED)
        )
        assertEquals(
            "session_completed",
            AnalyticsService.firebaseEventName(AnalyticsEvent.SESSION_COMPLETED)
        )
        assertEquals(null, AnalyticsService.firebaseEventName(AnalyticsEvent.AUTH_FAILED))
        assertEquals(null, AnalyticsService.firebaseEventName(AnalyticsEvent.PAYWALL_PURCHASE_FAILED))
    }

    @Test
    fun firebasePurchasePropertiesUseConversionValueAndDropDisplayOrUnknownFields() {
        val properties = AnalyticsService.firebaseEventProperties(
            AnalyticsEvent.PAYWALL_PURCHASE_COMPLETED,
            mapOf(
                "plan" to "yearly",
                "price" to 49.99,
                "currency" to "USD",
                "price_display" to "$49.99",
                "email" to "athlete@example.com",
                "is_discount" to true
            )
        )

        assertEquals("yearly", properties["plan"])
        assertEquals(49.99, properties["value"])
        assertEquals("USD", properties["currency"])
        assertEquals(1L, properties["is_discount"])
        assertFalse(properties.containsKey("price"))
        assertFalse(properties.containsKey("price_display"))
        assertFalse(properties.containsKey("email"))
    }
}
