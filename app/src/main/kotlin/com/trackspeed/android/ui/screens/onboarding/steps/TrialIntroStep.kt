package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

@Composable
fun TrialIntroStep(trialDays: Int, onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            stringResource(R.string.onboarding_trial_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(28.dp))

        // Bullet points
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TrialBullet(
                icon = Icons.Outlined.LockOpen,
                text = stringResource(R.string.onboarding_trial_access_days, trialDays)
            )
            TrialBullet(
                icon = Icons.Outlined.Notifications,
                text = stringResource(R.string.onboarding_trial_benefit2_title)
            )
            TrialBullet(
                icon = Icons.Outlined.Cancel,
                text = stringResource(R.string.onboarding_trial_benefit3_title)
            )
        }

        Spacer(Modifier.weight(1f))

        // App logo - large and centered
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = stringResource(R.string.onboarding_trial_logo_description),
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(28.dp))
        )

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.weight(1f))

        // Continue button
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            Text(
                stringResource(R.string.common_continue),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TrialBullet(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = AccentBlue
        )
        Text(
            text,
            fontSize = 16.sp,
            color = TextSecondary
        )
    }
}
