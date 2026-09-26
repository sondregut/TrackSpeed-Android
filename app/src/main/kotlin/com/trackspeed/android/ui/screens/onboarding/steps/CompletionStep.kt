package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

@Composable
fun CompletionStep(onTrySoloMode: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        // Camera icon in circle
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = AccentNavy.copy(alpha = 0.15f),
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = AccentNavy
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(R.string.onboarding_completion_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_completion_subtitle),
            fontSize = 15.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(40.dp))

        // Feature rows
        CompletionFeatureRow(
            title = stringResource(R.string.onboarding_completion_test_detection),
            description = stringResource(R.string.onboarding_completion_test_detection_desc),
            isFree = true
        )
        Spacer(Modifier.height(16.dp))
        CompletionFeatureRow(
            title = stringResource(R.string.onboarding_completion_solo_timing),
            description = stringResource(R.string.onboarding_completion_solo_timing_desc),
            isFree = true
        )
        Spacer(Modifier.height(16.dp))
        CompletionFeatureRow(
            title = stringResource(R.string.onboarding_completion_two_phone),
            description = stringResource(R.string.onboarding_completion_two_phone_desc),
            isFree = false
        )

        Spacer(Modifier.weight(1f))

        // Try Solo Mode button
        Button(
            onClick = onTrySoloMode,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNavy)
        ) {
            Text(
                stringResource(R.string.onboarding_completion_try_solo),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(12.dp))

        // Subscribe secondary button
        TextButton(onClick = onTrySoloMode) {
            Text(
                stringResource(R.string.onboarding_completion_subscribe),
                color = AccentNavy,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CompletionFeatureRow(
    title: String,
    description: String,
    isFree: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (isFree) Icons.Default.LockOpen else Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (isFree) AccentGreen else TextSecondary
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                fontSize = 14.sp,
                color = TextSecondary
            )
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isFree) AccentGreen.copy(alpha = 0.15f) else AccentNavy.copy(alpha = 0.15f)
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                if (isFree) stringResource(R.string.onboarding_completion_tier_free)
                else stringResource(R.string.onboarding_completion_tier_pro),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isFree) AccentGreen else AccentNavy,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
