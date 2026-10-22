package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.onboarding.OnboardingPainPoint

@Composable
fun HowItWorksStep(selectedPainPoints: Set<OnboardingPainPoint>, onContinue: () -> Unit) {
    // Keep older saved choices useful without bringing back the retired pain-point question.
    val selected = OnboardingPainPoint.entries.filter { it in selectedPainPoints }
    val defaults = listOf(OnboardingPainPoint.STOPWATCH_INACCURATE,
        OnboardingPainPoint.PROGRESS_BLIND, OnboardingPainPoint.NO_COACH)
    val benefits = (selected + defaults.filterNot { it in selected }).take(3)

    Box(Modifier.fillMaxSize()) {
        OnboardingPhotoBackdrop(R.drawable.onboarding_sync_quiet_photo)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(stringResource(R.string.parity_onboarding_benefits_title),
                    fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, modifier = Modifier.semantics { heading() })
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    benefits.forEach { BenefitRow(it) }
                }
            }
            OnboardingPhotoContinueButton(
                stringResource(R.string.common_continue), onContinue,
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun BenefitRow(benefit: OnboardingPainPoint) {
    Row(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(12.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center) {
            Icon(benefitIcon(benefit), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(benefitTitle(benefit)), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@StringRes
private fun benefitTitle(benefit: OnboardingPainPoint): Int = when (benefit) {
    OnboardingPainPoint.STOPWATCH_INACCURATE -> R.string.parity_onboarding_benefit_timing
    OnboardingPainPoint.PROGRESS_BLIND -> R.string.parity_onboarding_benefit_progress
    OnboardingPainPoint.HARDWARE_EXPENSIVE -> R.string.parity_onboarding_benefit_phone
    OnboardingPainPoint.NO_COACH -> R.string.parity_onboarding_benefit_solo
    OnboardingPainPoint.WASTED_SESSIONS -> R.string.parity_onboarding_benefit_saved
    OnboardingPainPoint.SPLITS_BLACK_BOX -> R.string.parity_onboarding_benefit_splits
}

private fun benefitIcon(benefit: OnboardingPainPoint): ImageVector = when (benefit) {
    OnboardingPainPoint.STOPWATCH_INACCURATE -> Icons.Default.CameraAlt
    OnboardingPainPoint.PROGRESS_BLIND -> Icons.Default.ShowChart
    OnboardingPainPoint.HARDWARE_EXPENSIVE -> Icons.Default.Smartphone
    OnboardingPainPoint.NO_COACH -> Icons.Default.DirectionsRun
    OnboardingPainPoint.WASTED_SESSIONS -> Icons.Default.PhotoLibrary
    OnboardingPainPoint.SPLITS_BLACK_BOX -> Icons.Default.Flag
}
