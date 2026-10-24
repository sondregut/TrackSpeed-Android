package com.trackspeed.android.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.trackspeed.android.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.ui.screens.onboarding.steps.*
import com.trackspeed.android.billing.EarlyReleaseAccess
import com.trackspeed.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onStartSession: () -> Unit = onComplete,
    onGuestJoinSession: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val route = OnboardingRoute(state.isReferred, EarlyReleaseAccess.isEnabled)
    // Capture this composition's step instead of reading a newer state inside an old callback.
    val renderedStep = state.currentStep
    BackHandler(enabled = renderedStep != OnboardingStep.WELCOME &&
        renderedStep != OnboardingStep.GETTING_STARTED) { viewModel.goBackFrom(renderedStep) }

    LaunchedEffect(state.currentStep) {
        viewModel.trackStepViewed(state.currentStep)
    }

    OnboardingPageChrome(
        step = state.currentStep,
        progress = route.progress(state.currentStep),
        onBack = { viewModel.goBackFrom(renderedStep) }
    ) {
        // Step content with animated transitions
        AnimatedContent(
            targetState = state.currentStep,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            modifier = Modifier.fillMaxSize(),
            label = "onboarding_step"
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    selectedLanguage = state.appLanguage,
                    onGetStarted = { viewModel.advanceAfterWelcome() },
                    onJoinSession = onGuestJoinSession,
                    onSignIn = {
                        if (viewModel.completeOnboardingIfPro()) {
                            onComplete()
                        }
                    },
                    onLanguageSelected = { viewModel.setAppLanguage(it) },
                    onDebugSkip = {
                        if (viewModel.completeOnboardingIfPro()) {
                            onComplete()
                        }
                    },
                    onDebugPaywall = {
                        viewModel.goToStep(OnboardingStep.PAYWALL)
                    }
                )
                OnboardingStep.REFERRAL_WELCOME -> ReferralWelcomeStep(
                    onContinue = { viewModel.goToStepFrom(step, OnboardingStep.VALUE_PROPOSITION) }
                )
                OnboardingStep.VALUE_PROPOSITION, OnboardingStep.CAMERA_ALIGNMENT,
                OnboardingStep.AUTO_TIMING, OnboardingStep.PROOF_PROGRESS -> FeaturePhotoStep(
                    step = step, onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.GETTING_STARTED -> GettingStartedGuide(
                    onSkip = { viewModel.completeOnboarding(onComplete) },
                    onStartSession = { viewModel.completeOnboarding(onStartSession) }
                )
                OnboardingStep.PAIN_POINTS -> PainPointsStep(
                    selectedPainPoints = state.selectedPainPoints,
                    onTogglePainPoint = { viewModel.togglePainPoint(it) },
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.PAIN_SWIPE -> PainSwipeStep(
                    selectedPainPoints = state.selectedPainPoints,
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.HOW_IT_WORKS -> HowItWorksStep(
                    selectedPainPoints = state.selectedPainPoints,
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.SOLO_DEMO -> SoloDemoStep(
                    onContinue = { viewModel.goForwardFrom(step) },
                    onSkip = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.FLYING_TIME, OnboardingStep.ROLE_SELECTION -> RoleSelectionStep(
                    selectedRole = state.profile.role,
                    onRoleSelected = viewModel::setRole,
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.GOAL_TIME, OnboardingStep.SPORT_SELECTION -> SportSelectionStep(
                    selectedDiscipline = state.profile.discipline,
                    onDisciplineSelected = viewModel::setDiscipline,
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.GOAL_MOTIVATION -> GoalMotivationStep(
                    flyingPR = state.profile.flyingPR,
                    goalTime = state.profile.goalTime,
                    flyingDistance = state.profile.flyingDistance,
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.START_TYPES -> StartTypesStep(
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.MULTI_DEVICE -> FeaturePhotoStep(
                    step = step, onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.ATTRIBUTION -> AttributionStep(
                    onAttributionSelected = { viewModel.setAttribution(it) },
                    promoCode = state.profile.promoCode ?: "",
                    onPromoCodeChanged = { viewModel.setPromoCode(it) },
                    onSubmitPromoCode = { code, source -> viewModel.submitPromoCode(code, source) },
                    isLoading = state.promoRedemptionState is PromoRedemptionState.Loading,
                    onContinue = {
                        viewModel.handleAttributionContinue(step)
                    }
                )
                OnboardingStep.RATING -> RatingStep(
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.COMPETITOR_COMPARISON -> CompetitorComparisonStep(
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.AUTH -> AuthStep(
                    onAuthenticated = {
                        if (viewModel.completeOnboardingIfPro()) {
                            onComplete()
                        }
                    },
                    onSkipAuth = {
                        if (viewModel.completeOnboardingIfPro()) {
                            onComplete()
                        }
                    }
                )
                OnboardingStep.CAMERA_PERMISSION -> CameraPermissionStep(
                    onContinue = { viewModel.goForwardFrom(step) },
                    onSkip = { viewModel.goForwardFrom(step) },
                    onPermissionResult = { granted -> viewModel.trackCameraPermissionResult(granted) }
                )
                OnboardingStep.PROFILE_SETUP -> ProfileSetupStep(
                    displayName = state.profile.displayName ?: "",
                    teamName = state.profile.teamName ?: "",
                    onDisplayNameChanged = { viewModel.setDisplayName(it) },
                    onTeamNameChanged = { viewModel.setTeamName(it) },
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.TRIAL_INTRO -> TrialIntroStep(
                    trialDays = requireNotNull(state.annualFreeTrialDays),
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.TRIAL_REMINDER -> TrialReminderStep(
                    onContinue = { viewModel.goForwardFrom(step) }
                )
                OnboardingStep.NOTIFICATION -> NotificationStep(
                    onContinue = { viewModel.goForwardFrom(step) },
                    onPermissionResult = { granted -> viewModel.trackNotificationPermissionResult(granted) },
                    showTrialReminder = state.annualFreeTrialDays != null
                )
                OnboardingStep.PAYWALL -> if (EarlyReleaseAccess.isEnabled) EarlyReleaseWelcomeStep(
                    onContinue = {
                        if (state.isAuthenticated) {
                            if (viewModel.completeOnboardingIfPro()) onComplete()
                        } else {
                            viewModel.goToStep(OnboardingStep.AUTH)
                        }
                    }
                ) else PaywallStep(
                    onContinue = {
                        if (state.isAuthenticated) {
                            if (viewModel.completeOnboardingIfPro()) {
                                onComplete()
                            }
                        } else {
                            viewModel.goToStep(OnboardingStep.AUTH)
                        }
                    },
                    onSkip = { viewModel.goToStep(OnboardingStep.PAYWALL_RECOVERY) }
                )
                OnboardingStep.PAYWALL_RECOVERY -> PaywallRecoveryStep(
                    onClaim = {
                        if (state.isAuthenticated) {
                            if (viewModel.completeOnboardingIfPro()) {
                                onComplete()
                            }
                        } else {
                            viewModel.goToStep(OnboardingStep.AUTH)
                        }
                    },
                    onFullPrice = { viewModel.goToStep(OnboardingStep.PAYWALL) }
                )
                OnboardingStep.SPIN_WHEEL -> SpinWheelStep(
                    onContinue = { viewModel.goForwardFrom(step) },
                    onClaimDiscount = { /* Discount preference handled in PaywallViewModel */ }
                )
                OnboardingStep.COMPLETION -> CompletionStep(
                    onTrySoloMode = {
                        if (viewModel.completeOnboardingIfPro()) {
                            onComplete()
                        }
                    }
                )
            }
        }
    }
}
