package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trackspeed.android.R
import com.trackspeed.android.ui.screens.onboarding.OnboardingStep
import kotlinx.coroutines.launch

private data class GuidePage(@DrawableRes val photo: Int, @StringRes val title: Int, @StringRes val detail: Int)
private val guidePages = listOf(
    GuidePage(R.drawable.onboarding_setup_photo, R.string.guide_setup_title, R.string.guide_setup_detail),
    GuidePage(R.drawable.onboarding_camera_alignment_photo, R.string.guide_alignment_title, R.string.guide_alignment_detail),
    GuidePage(R.drawable.onboarding_run_live_photo, R.string.guide_run_title, R.string.guide_run_detail),
    GuidePage(R.drawable.onboarding_photo_finish_photo, R.string.guide_review_title, R.string.guide_review_detail)
)

/** The post-signup tutorial retains its own copy, independent of the feature tour. */
@Composable
fun GettingStartedGuide(onSkip: () -> Unit, onStartSession: () -> Unit) {
    val pager = rememberPagerState(pageCount = { guidePages.size })
    val scope = rememberCoroutineScope()
    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
        else onSkip()
    }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
        PhotoGuidePage(guidePages[index], pageIndex = index,
            onSkip = onSkip,
            onBack = if (index > 0) ({ scope.launch { pager.animateScrollToPage(index - 1) }; Unit }) else null,
            onContinue = {
                if (index == guidePages.lastIndex) onStartSession()
                else scope.launch { pager.animateScrollToPage(index + 1) }
            })
    }
}

@Composable
fun FeaturePhotoStep(step: OnboardingStep, onContinue: () -> Unit) {
    when (step) {
        OnboardingStep.MULTI_DEVICE -> OnboardingFeaturePage(R.string.parity_tour_connect_title,
            R.string.parity_tour_connect_detail, onContinue, R.drawable.onboarding_connect_devices_photo)
        OnboardingStep.CAMERA_ALIGNMENT -> OnboardingFeaturePage(R.string.parity_tour_alignment_title,
            R.string.parity_tour_alignment_detail, onContinue, R.drawable.onboarding_camera_alignment_photo)
        OnboardingStep.AUTO_TIMING -> OnboardingFeaturePage(R.string.parity_tour_auto_title,
            R.string.parity_tour_auto_detail, onContinue, R.drawable.onboarding_photo_finish_photo)
        OnboardingStep.PROOF_PROGRESS -> OnboardingFeaturePage(R.string.parity_tour_proof_title,
            R.string.parity_tour_proof_detail, onContinue, button = R.string.parity_tour_personalize) {
            Image(painterResource(R.drawable.proof_flying_10m),
                contentDescription = stringResource(R.string.parity_tour_proof_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 529.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)))
        }
        else -> OnboardingFeaturePage(R.string.parity_tour_setup_title,
            R.string.parity_tour_setup_detail, onContinue, R.drawable.onboarding_setup_photo)
    }
}

@Composable
private fun PhotoGuidePage(page: GuidePage, pageIndex: Int,
    onSkip: () -> Unit, onBack: (() -> Unit)?, onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        OnboardingPhotoBackdrop(page.photo)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.2f), 0.4f to Color.Black.copy(alpha = 0.2f),
            0.68f to Color.Black.copy(alpha = 0.8f), 1f to Color.Black.copy(alpha = 0.92f))))
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (onBack != null) TextButton(onClick = onBack) {
                    Text(stringResource(R.string.common_back), color = Color.White)
                } else Spacer(Modifier.width(48.dp))
                TextButton(onClick = onSkip) { Text(stringResource(R.string.common_skip), color = Color.White) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Bottom) {
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineLarge,
                    color = Color.White, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(page.detail), style = MaterialTheme.typography.bodyLarge, color = Color.White)
            }
            Spacer(Modifier.height(24.dp))
            val description = stringResource(R.string.guide_page, pageIndex + 1, guidePages.size)
            Row(Modifier.align(Alignment.CenterHorizontally).semantics { contentDescription = description },
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(guidePages.size) { index ->
                    Box(Modifier.width(if (pageIndex == index) 22.dp else 8.dp).height(8.dp)
                        .background(Color.White.copy(alpha = if (pageIndex == index) 1f else 0.4f), CircleShape))
                }
            }
            Spacer(Modifier.height(12.dp))
            OnboardingPhotoContinueButton(stringResource(
                if (pageIndex == guidePages.lastIndex) R.string.guide_start_session else R.string.common_continue), onContinue)
        }
    }
}
