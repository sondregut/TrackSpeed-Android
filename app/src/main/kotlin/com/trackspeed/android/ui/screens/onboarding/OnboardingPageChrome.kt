package com.trackspeed.android.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

/** Photo pages own safe-area content; their photograph extends behind this header. */
internal val OnboardingStep.hasImmersiveContent: Boolean
    get() = this in setOf(OnboardingStep.WELCOME, OnboardingStep.VALUE_PROPOSITION,
        OnboardingStep.MULTI_DEVICE, OnboardingStep.CAMERA_ALIGNMENT, OnboardingStep.START_TYPES,
        OnboardingStep.AUTO_TIMING, OnboardingStep.PROOF_PROGRESS, OnboardingStep.ROLE_SELECTION,
        OnboardingStep.SPORT_SELECTION, OnboardingStep.HOW_IT_WORKS, OnboardingStep.CAMERA_PERMISSION,
        OnboardingStep.FLYING_TIME, OnboardingStep.GOAL_TIME, OnboardingStep.GETTING_STARTED)

@Composable
internal fun OnboardingPageChrome(
    step: OnboardingStep,
    progress: Float,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    if (step.hasImmersiveContent) {
        Box(Modifier.fillMaxSize().gradientBackground()) {
            content()
            if (step.showsProgressBar) OnboardingHeader(progress, onBack, onPhoto = true)
        }
    } else {
        Column(Modifier.fillMaxSize().gradientBackground()) {
            if (step.showsProgressBar) OnboardingHeader(progress, onBack, onPhoto = false)
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun OnboardingHeader(progress: Float, onBack: () -> Unit, onPhoto: Boolean) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back),
                tint = if (onPhoto) Color.White else TextPrimary)
        }
        val description = stringResource(R.string.parity_onboarding_progress)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(4.dp)
            .padding(horizontal = 8.dp).semantics { contentDescription = description },
            color = if (onPhoto) Color.White else AccentNavy,
            trackColor = if (onPhoto) Color.White.copy(alpha = 0.32f) else SurfaceDark)
        Spacer(Modifier.width(48.dp))
    }
}
