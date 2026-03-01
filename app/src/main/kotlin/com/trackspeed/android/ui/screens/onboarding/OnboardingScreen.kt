package com.trackspeed.android.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.ui.screens.onboarding.steps.*
import com.trackspeed.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onGuestJoinSession: () -> Unit = {},
    onSignIn: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
    ) {
        // Top bar with back button + progress
        if (state.currentStep.showsProgressBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.currentStep.showsBackButton) {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = TextPrimary
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                LinearProgressIndicator(
                    progress = { state.currentStep.progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .padding(horizontal = 8.dp),
                    color = AccentNavy,
                    trackColor = SurfaceDark
                )
                Spacer(Modifier.width(48.dp))
            }
        }

        // Step content with animated transitions
        AnimatedContent(
            targetState = state.currentStep,
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                slideInHorizontally { fullWidth -> direction * fullWidth } togetherWith
                    slideOutHorizontally { fullWidth -> -direction * fullWidth }
            },
            modifier = Modifier.fillMaxSize(),
            label = "onboarding_step"
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    onGetStarted = { viewModel.goForward() },
                    onJoinSession = onGuestJoinSession,
                    onSignIn = onSignIn,
                    onDebugSkip = {
                        viewModel.completeOnboarding()
                        onComplete()
                    },
                    onDebugPaywall = {
                        viewModel.goToStep(OnboardingStep.PAYWALL)
                    }
                )
                OnboardingStep.VALUE_PROPOSITION -> ValuePropositionStep(
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.HOW_IT_WORKS -> HowItWorksStep(
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.TRACK_PREVIEW -> TrackPreviewStep(
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.FLYING_TIME -> FlyingTimeStep(
                    selectedDistance = state.profile.flyingDistance,
                    flyingPR = state.profile.flyingPR,
                    onDistanceSelected = { viewModel.setFlyingDistance(it) },
                    onTimeChanged = { viewModel.setFlyingPR(it) },
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.GOAL_TIME -> GoalTimeStep(
                    discipline = state.profile.discipline,
                    personalRecord = state.profile.personalRecord,
                    goalTime = state.profile.goalTime,
                    onPersonalRecordChanged = { viewModel.setPersonalRecord(it) },
                    onGoalTimeChanged = { viewModel.setGoalTime(it) },
                    onContinue = { viewModel.goForward() },
                    flyingDistance = state.profile.flyingDistance
                )
                OnboardingStep.GOAL_MOTIVATION -> GoalMotivationStep(
                    flyingPR = state.profile.flyingPR,
                    goalTime = state.profile.goalTime,
                    flyingDistance = state.profile.flyingDistance,
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.START_TYPES -> StartTypesStep(
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.MULTI_DEVICE -> MultiDeviceStep(
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.ATTRIBUTION -> AttributionStep(
                    onAttributionSelected = { viewModel.setAttribution(it) },
                    promoCode = state.profile.promoCode ?: "",
                    onPromoCodeChanged = { viewModel.setPromoCode(it) },
                    onSubmitPromoCode = { code, source -> viewModel.submitPromoCode(code, source) },
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.RATING -> RatingStep(
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.COMPETITOR_COMPARISON -> CompetitorComparisonStep(
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.AUTH -> AuthStep(
                    onAuthenticated = { viewModel.goForward() }
                )
                OnboardingStep.CAMERA_PERMISSION -> CameraPermissionStep(
                    onContinue = { viewModel.goForward() },
                    onSkip = { viewModel.goForward() }
                )
                OnboardingStep.PROFILE_SETUP -> ProfileSetupStep(
                    displayName = state.profile.displayName ?: "",
                    teamName = state.profile.teamName ?: "",
                    onDisplayNameChanged = { viewModel.setDisplayName(it) },
                    onTeamNameChanged = { viewModel.setTeamName(it) },
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.TRIAL_INTRO -> TrialIntroStep(
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.TRIAL_REMINDER -> TrialReminderStep(
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.NOTIFICATION -> NotificationStep(
                    onContinue = { viewModel.goForward() }
                )
                OnboardingStep.PAYWALL -> PaywallStep(
                    onContinue = {
                        viewModel.completeOnboarding()
                        onComplete()
                    },
                    onSkip = { viewModel.goForward() }
                )
                OnboardingStep.SPIN_WHEEL -> SpinWheelStep(
                    onContinue = { viewModel.goForward() },
                    onClaimDiscount = { /* Discount preference handled in PaywallViewModel */ }
                )
                OnboardingStep.COMPLETION -> CompletionStep(
                    onTrySoloMode = {
                        viewModel.completeOnboarding()
                        onComplete()
                    }
                )
            }
        }
    }
}
