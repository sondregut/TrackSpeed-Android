package com.trackspeed.android.ui.screens.onboarding.steps

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Timer
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
import kotlinx.coroutines.delay

@Composable
fun NotificationStep(onContinue: () -> Unit) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onContinue() }

    // Animated bell swing
    val bellRotation by rememberInfiniteTransition(label = "bell").animateFloat(
        initialValue = -18f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bell_swing"
    )

    // Staggered bullet point visibility
    var showBullet1 by remember { mutableStateOf(false) }
    var showBullet2 by remember { mutableStateOf(false) }
    var showBullet3 by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(300)
        showBullet1 = true
        delay(200)
        showBullet2 = true
        delay(200)
        showBullet3 = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        // Animated bell icon
        Icon(
            Icons.Default.Notifications,
            contentDescription = null,
            modifier = Modifier
                .size(88.dp)
                .graphicsLayer { rotationZ = bellRotation },
            tint = AccentNavy
        )

        Spacer(Modifier.height(32.dp))

        Text(
            stringResource(R.string.onboarding_notification_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NotificationBullet(
                icon = Icons.Default.Timer,
                text = "Training reminders",
                visible = showBullet1
            )
            NotificationBullet(
                icon = Icons.Default.EmojiEvents,
                text = "Session results & personal bests",
                visible = showBullet2
            )
            NotificationBullet(
                icon = Icons.Default.Notifications,
                text = "Trial reminders before charges",
                visible = showBullet3
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onContinue()
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNavy)
        ) {
            Text(stringResource(R.string.onboarding_notification_enable), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onContinue) {
            Text(stringResource(R.string.common_not_now), color = TextSecondary)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun NotificationBullet(
    icon: ImageVector,
    text: String,
    visible: Boolean
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "bullet_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = AccentNavy.copy(alpha = 0.15f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = AccentNavy,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = text,
            fontSize = 15.sp,
            color = TextPrimary,
            lineHeight = 20.sp
        )
    }
}
