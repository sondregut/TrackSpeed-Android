package com.trackspeed.android.ui.screens.onboarding.steps

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

@Composable
fun NotificationStep(onContinue: () -> Unit) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onContinue() }

    var appeared by remember { mutableStateOf(false) }

    // Bell swing animation
    val bellRotation by rememberInfiniteTransition(label = "bell").animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bell_swing"
    )

    LaunchedEffect(Unit) { appeared = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        // Bell icon with animation
        Icon(
            Icons.Default.Notifications,
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .graphicsLayer {
                    rotationZ = if (appeared) bellRotation else 0f
                    scaleX = if (appeared) 1f else 0.8f
                    scaleY = if (appeared) 1f else 0.8f
                },
            tint = AccentBlue
        )

        Spacer(Modifier.height(24.dp))

        // Title
        Text(
            stringResource(R.string.onboarding_notification_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        // Subtitle
        Text(
            stringResource(R.string.onboarding_notification_description),
            fontSize = 16.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(32.dp))

        // Bullet points
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NotificationBullet(
                icon = Icons.Default.Schedule,
                text = "Training reminders"
            )
            NotificationBullet(
                icon = Icons.Default.EmojiEvents,
                text = "Session results & personal bests"
            )
            NotificationBullet(
                icon = Icons.Default.Notifications,
                text = "Trial reminders before charges"
            )
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.weight(1f))

        // Allow Notifications button
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onContinue()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            Text(
                stringResource(R.string.onboarding_notification_enable),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(12.dp))

        // Not Now link
        TextButton(onClick = onContinue) {
            Text(
                stringResource(R.string.common_not_now),
                fontSize = 14.sp,
                color = TextSecondary
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun NotificationBullet(
    icon: ImageVector,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = text,
            fontSize = 16.sp,
            color = TextPrimary
        )
    }
}
