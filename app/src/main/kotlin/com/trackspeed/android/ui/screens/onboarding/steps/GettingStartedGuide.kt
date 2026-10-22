package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.onboarding.OnboardingStep

private data class GuidePage(@DrawableRes val photo: Int, @StringRes val title: Int, @StringRes val detail: Int)
private val guidePages = listOf(
    GuidePage(R.drawable.onboarding_setup_photo, R.string.guide_setup_title, R.string.guide_setup_detail),
    GuidePage(R.drawable.onboarding_camera_alignment_photo, R.string.guide_alignment_title, R.string.guide_alignment_detail),
    GuidePage(R.drawable.onboarding_run_live_photo, R.string.guide_run_title, R.string.guide_run_detail),
    GuidePage(R.drawable.onboarding_photo_finish_photo, R.string.guide_review_title, R.string.guide_review_detail)
)

@Composable
fun GettingStartedGuide(onSkip: () -> Unit, onStartSession: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler { if (page > 0) page-- else onSkip() }
    PhotoGuidePage(guidePages[page],
        button = if (page == guidePages.lastIndex) R.string.guide_start_session else R.string.common_continue,
        progress = stringResource(R.string.guide_page, page + 1, guidePages.size),
        onSkip = onSkip,
        onBack = if (page > 0) ({ page-- }) else null,
        onContinue = { if (page == guidePages.lastIndex) onStartSession() else page++ })
}

@Composable
fun FeaturePhotoStep(step: OnboardingStep, onContinue: () -> Unit) {
    val page = when (step) {
        OnboardingStep.MULTI_DEVICE -> GuidePage(R.drawable.onboarding_connect_devices_photo,
            R.string.guide_connect_title, R.string.guide_connect_detail)
        OnboardingStep.CAMERA_ALIGNMENT -> guidePages[1]
        OnboardingStep.AUTO_TIMING -> guidePages[2]
        OnboardingStep.PROOF_PROGRESS -> guidePages[3]
        else -> guidePages[0]
    }
    PhotoGuidePage(page, onContinue = onContinue)
}

@Composable
private fun PhotoGuidePage(page: GuidePage, @StringRes button: Int = R.string.common_continue,
    progress: String? = null, onSkip: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null, onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(painterResource(page.photo), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.4f), Color.Black.copy(alpha = 0.95f)))))
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Color.White) }
                else Spacer(Modifier.width(48.dp))
                if (onSkip != null) TextButton(onClick = onSkip) { Text(stringResource(R.string.common_skip), color = Color.White) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Bottom) {
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineLarge,
                    color = Color.White, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Text(stringResource(page.detail), style = MaterialTheme.typography.bodyLarge, color = Color.White)
            }
            Spacer(Modifier.height(24.dp))
            progress?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally)); Spacer(Modifier.height(12.dp)) }
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Text(stringResource(button))
            }
        }
    }
}
