package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R

@Composable
fun CompletionStep(onTrySoloMode: () -> Unit) {
    val features = listOf(
        Triple(R.string.onboarding_completion_solo_timing, R.string.onboarding_completion_tier_free, true),
        Triple(R.string.onboarding_completion_photo_finish, R.string.onboarding_completion_tier_free, true),
        Triple(R.string.onboarding_completion_multi_phone, R.string.onboarding_completion_tier_pro, false),
        Triple(R.string.onboarding_completion_unlimited_history, R.string.onboarding_completion_tier_pro, false),
        Triple(R.string.onboarding_completion_athlete_profiles, R.string.onboarding_completion_tier_pro, false),
        Triple(R.string.onboarding_completion_video_export, R.string.onboarding_completion_tier_pro, false)
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = stringResource(R.string.onboarding_completion_logo_description),
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(16.dp))
        )
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_completion_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        // Feature grid
        features.forEach { (featureRes, tierRes, isFree) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isFree) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isFree) Color(0xFF30D158) else Color(0xFF8E8E93)
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(featureRes), fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isFree) Color(0xFF30D158).copy(alpha = 0.15f) else Color(0xFF3A3A3C)
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        stringResource(tierRes),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isFree) Color(0xFF30D158) else Color(0xFF8E8E93),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onTrySoloMode,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
        ) {
            Text(stringResource(R.string.onboarding_completion_try_solo), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }
}
