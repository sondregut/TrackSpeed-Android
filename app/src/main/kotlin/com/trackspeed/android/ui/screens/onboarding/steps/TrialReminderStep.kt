package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*
import kotlinx.coroutines.delay

private val AccentYellow = AccentGold

@Composable
fun TrialReminderStep(onContinue: () -> Unit) {
    // Bell swing animation
    var startAnimation by remember { mutableStateOf(false) }
    val bellRotation by animateFloatAsState(
        targetValue = if (startAnimation) 0f else 0f,
        label = "bell_idle"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "bell_swing")
    val swingAngle by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bell_swing_angle"
    )

    LaunchedEffect(Unit) {
        delay(600)
        startAnimation = true
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        // Animated bell icon
        Icon(
            Icons.Default.NotificationsActive,
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .graphicsLayer {
                    rotationZ = if (startAnimation) swingAngle else 0f
                },
            tint = AccentYellow
        )

        Spacer(Modifier.height(32.dp))

        Text(
            stringResource(R.string.onboarding_reminder_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            lineHeight = 36.sp
        )

        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.onboarding_reminder_description),
            fontSize = 17.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }
}
