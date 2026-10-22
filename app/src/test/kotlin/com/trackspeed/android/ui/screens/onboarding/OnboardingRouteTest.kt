package com.trackspeed.android.ui.screens.onboarding

import org.junit.Assert.*
import org.junit.Test

class OnboardingRouteTest {
    @Test fun currentRouteSkipsRemovedPainAndGoalPagesAndIncludesGuide() {
        val route = OnboardingRoute(false, true)
        assertFalse(route.steps.contains(OnboardingStep.PAIN_POINTS))
        assertFalse(route.steps.contains(OnboardingStep.PAIN_SWIPE))
        assertFalse(route.steps.contains(OnboardingStep.GOAL_MOTIVATION))
        assertEquals(OnboardingStep.MULTI_DEVICE, route.next(OnboardingStep.VALUE_PROPOSITION))
        assertEquals(OnboardingStep.GETTING_STARTED, route.next(OnboardingStep.AUTH))
        assertEquals(route.steps.size, route.steps.distinct().size)
    }
    @Test fun conditionalRoutesNavigateBackAndForwardSymmetrically() {
        for (referred in listOf(false, true)) for (early in listOf(false, true)) {
            val route = OnboardingRoute(referred, early)
            route.steps.zipWithNext().forEach { (a,b) ->
                assertEquals(b, route.next(a)); assertEquals(a, route.previous(b))
                assertTrue(route.progress(b) >= route.progress(a))
            }
            assertEquals(referred, route.steps.contains(OnboardingStep.REFERRAL_WELCOME))
            assertEquals(!early, route.steps.contains(OnboardingStep.COMPETITOR_COMPARISON))
            assertEquals(1f, route.progress(OnboardingStep.PAYWALL))
        }
    }
    @Test fun recoveryReturnsToPaywallRatherThanRemovedTrialPages() {
        assertEquals(OnboardingStep.PAYWALL, OnboardingRoute(false,true).previous(OnboardingStep.PAYWALL_RECOVERY))
    }
}
