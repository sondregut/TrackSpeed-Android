package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

private val GreenMarker = AccentGreen
private val RedMarker = TimerRed

@Composable
fun TrackPreviewStep(onContinue: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "runner")
    val runnerProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "runner_progress"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.onboarding_preview_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_preview_description),
            fontSize = 15.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(48.dp))

        // Linear track illustration
        LinearTrackIllustration(runnerProgress = runnerProgress)

        Spacer(Modifier.height(40.dp))

        // Info cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TrackInfoCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.onboarding_preview_one_phone_title),
                subtitle = stringResource(R.string.onboarding_preview_one_phone_subtitle),
                color = AccentBlue
            )
            TrackInfoCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.onboarding_preview_two_phones_title),
                subtitle = stringResource(R.string.onboarding_preview_two_phones_subtitle),
                color = GreenMarker
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun LinearTrackIllustration(runnerProgress: Float) {
    // Track container
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Gate labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GateLabel(text = "START", color = GreenMarker)
                GateLabel(text = "LAP", color = AccentBlue)
                GateLabel(text = "FINISH", color = RedMarker)
            }

            Spacer(Modifier.height(8.dp))

            // Track line with runner
            Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                // Track lane
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(3.dp))
                        .background(BorderSubtle)
                )

                // Progress fill (how far runner has gone)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = runnerProgress)
                        .height(6.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(3.dp))
                        .background(AccentBlue.copy(alpha = 0.5f))
                )

                // Gate markers
                GateMarker(
                    modifier = Modifier.align(Alignment.CenterStart),
                    color = GreenMarker
                )
                GateMarker(
                    modifier = Modifier.align(Alignment.Center),
                    color = AccentBlue
                )
                GateMarker(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    color = RedMarker
                )

                // Runner dot (animated)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = runnerProgress)
                        .height(48.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.CenterEnd)
                            .clip(RoundedCornerShape(50))
                            .background(AccentBlue)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(50))
                                .background(TextPrimary)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Phone icons at gate positions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PhoneIcon(color = GreenMarker)
                PhoneIcon(color = AccentBlue)
                PhoneIcon(color = RedMarker)
            }
        }
    }
}

@Composable
private fun GateLabel(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        letterSpacing = 1.sp
    )
}

@Composable
private fun GateMarker(modifier: Modifier = Modifier, color: Color) {
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

@Composable
private fun PhoneIcon(color: Color) {
    Box(
        modifier = Modifier
            .width(20.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(BorderSubtle)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@Composable
private fun TrackInfoCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = TextSecondary
            )
        }
    }
}
