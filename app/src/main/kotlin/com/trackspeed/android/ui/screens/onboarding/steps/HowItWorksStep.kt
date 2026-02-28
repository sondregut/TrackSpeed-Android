package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*
import kotlinx.coroutines.launch

private data class HowItWorksPage(
    val imageRes: Int,
    val stepRes: Int,
    val titleRes: Int,
    val descriptionRes: Int
)

private val pages = listOf(
    HowItWorksPage(R.drawable.onboarding_connect, R.string.onboarding_howitworks_step1_label, R.string.onboarding_howitworks_step1_title, R.string.onboarding_howitworks_step1_description),
    HowItWorksPage(R.drawable.onboarding_countdownstart, R.string.onboarding_howitworks_step2_label, R.string.onboarding_howitworks_step2_title, R.string.onboarding_howitworks_step2_description),
    HowItWorksPage(R.drawable.onboarding_setup, R.string.onboarding_howitworks_step3_label, R.string.onboarding_howitworks_step3_title, R.string.onboarding_howitworks_step3_description),
    HowItWorksPage(R.drawable.onboarding_tracksetup, R.string.onboarding_howitworks_step4_label, R.string.onboarding_howitworks_step4_title, R.string.onboarding_howitworks_step4_description),
)

@Composable
fun HowItWorksStep(onContinue: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            text = stringResource(R.string.onboarding_howitworks_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { pageIndex ->
            val page = pages[pageIndex]
            val title = stringResource(page.titleRes)
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(page.imageRes),
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(page.stepRes),
                    fontSize = 14.sp,
                    color = AccentNavy,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(page.descriptionRes),
                    fontSize = 15.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Page indicator dots
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            repeat(pages.size) { index ->
                val isSelected = pagerState.currentPage == index
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isSelected) 10.dp else 8.dp),
                    shape = CircleShape,
                    color = if (isSelected) AccentNavy else BorderSubtle
                ) {}
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (pagerState.currentPage < pages.size - 1) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else {
                    onContinue()
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNavy)
        ) {
            Text(
                if (pagerState.currentPage < pages.size - 1) stringResource(R.string.common_next) else stringResource(R.string.common_continue),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}
