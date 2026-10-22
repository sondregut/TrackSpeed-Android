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
    @Test fun savedLegacyStepsResumeAtCurrentEquivalentWithoutRestartingTour() {
        val route = OnboardingRoute(false, true)
        assertEquals(OnboardingStep.ROLE_SELECTION, route.restore(OnboardingStep.FLYING_TIME))
        assertEquals(OnboardingStep.SPORT_SELECTION, route.restore(OnboardingStep.GOAL_TIME))
        assertEquals(OnboardingStep.SPORT_SELECTION, route.next(OnboardingStep.FLYING_TIME))
        assertEquals(OnboardingStep.HOW_IT_WORKS, route.restore(OnboardingStep.PAIN_SWIPE))
        assertEquals(OnboardingStep.PAYWALL, route.restore(OnboardingStep.TRIAL_INTRO))
        assertEquals(OnboardingStep.VALUE_PROPOSITION, route.restore(OnboardingStep.REFERRAL_WELCOME))
    }
    @Test fun interruptedCurrentFlowCanBeRestoredAndRevisitedRepeatedly() {
        for (referred in listOf(false, true)) for (early in listOf(false, true)) {
            val route = OnboardingRoute(referred, early)
            route.steps.forEach { step ->
                assertEquals(step, route.restore(step))
                assertEquals(route.restore(step), route.restore(route.restore(step)))
            }
            repeat(3) {
                assertEquals(OnboardingStep.CAMERA_PERMISSION,
                    route.previous(route.next(OnboardingStep.CAMERA_PERMISSION)))
            }
            assertEquals(OnboardingStep.GETTING_STARTED, route.next(OnboardingStep.GETTING_STARTED))
        }
    }
    @Test fun restoredAccessStatesKeepThePurchaseBoundary() {
        val paid = OnboardingRoute(false, false)
        assertEquals(OnboardingStep.PAYWALL_RECOVERY, paid.restore(OnboardingStep.PAYWALL_RECOVERY))
        assertEquals(OnboardingStep.PAYWALL, paid.next(OnboardingStep.PAYWALL_RECOVERY))
        assertEquals(OnboardingStep.PAYWALL,
            OnboardingRoute(false, true).restore(OnboardingStep.PAYWALL_RECOVERY))
    }

    @Test fun repeatedOutgoingContinueCannotSkipTheNextPage() {
        for (referred in listOf(false, true)) for (early in listOf(false, true)) {
            val route = OnboardingRoute(referred, early)
            route.steps.zipWithNext().forEach { (source, next) ->
                val current = requireNotNull(route.nextFrom(source, source))
                assertEquals(next, current)
                assertNull(route.nextFrom(current, source))
            }
        }
    }

    @Test fun repeatedBackFromOutgoingHeaderCannotSkipAnotherPage() {
        val route = OnboardingRoute(false, true)
        route.steps.zipWithNext().forEach { (previous, source) ->
            val current = requireNotNull(route.previousFrom(source, source))
            assertEquals(previous, current)
            assertNull(route.previousFrom(current, source))
        }
    }

    @Test fun oppositeActionsFromTheSameOldPageCannotUndoEachOther() {
        val route = OnboardingRoute(false, true)
        val source = OnboardingStep.CAMERA_PERMISSION
        val afterForward = requireNotNull(route.nextFrom(source, source))
        assertNull(route.previousFrom(afterForward, source))
        val afterBack = requireNotNull(route.previousFrom(source, source))
        assertNull(route.nextFrom(afterBack, source))
        // A newly rendered page still has an ordinary, reversible Back action.
        assertEquals(source, route.previousFrom(afterForward, afterForward))
    }

    @Test fun delayedAttributionCompletionCannotNavigateAfterBack() {
        val route = OnboardingRoute(false, false)
        val source = OnboardingStep.ATTRIBUTION
        val current = route.previous(source)
        assertNull(route.nextFrom(current, source))
        assertNull(route.destinationFrom(current, source, OnboardingStep.GETTING_STARTED))
        assertEquals(OnboardingStep.GETTING_STARTED,
            route.destinationFrom(source, source, OnboardingStep.GETTING_STARTED))
        // Purchase recovery remains at its explicit purchase boundary.
        assertEquals(OnboardingStep.PAYWALL,
            route.nextFrom(OnboardingStep.PAYWALL_RECOVERY, OnboardingStep.PAYWALL_RECOVERY))
    }

    @Test fun restoredPageAcceptsOnlyItsCurrentRenderedSource() {
        val route = OnboardingRoute(false, true)
        val restored = route.restore(OnboardingStep.FLYING_TIME)
        assertNull(route.nextFrom(restored, OnboardingStep.FLYING_TIME))
        assertEquals(OnboardingStep.SPORT_SELECTION, route.nextFrom(restored, restored))
    }
}
