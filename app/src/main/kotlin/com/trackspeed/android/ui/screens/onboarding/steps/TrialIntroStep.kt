package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Cancel
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

private val AccentBlue = AccentNavy

@Composable
fun TrialIntroStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        // App logo
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = stringResource(R.string.onboarding_trial_logo_description),
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(22.dp))
        )

        Spacer(Modifier.height(32.dp))

        Text(
            stringResource(R.string.onboarding_trial_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            lineHeight = 34.sp
        )

        Spacer(Modifier.height(40.dp))

        // Three benefit rows
        TrialBenefitRow(
            icon = Icons.Outlined.AccessTime,
            title = stringResource(R.string.onboarding_trial_benefit1_title),
            description = stringResource(R.string.onboarding_trial_benefit1_desc)
        )
        Spacer(Modifier.height(20.dp))
        TrialBenefitRow(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.onboarding_trial_benefit2_title),
            description = stringResource(R.string.onboarding_trial_benefit2_desc)
        )
        Spacer(Modifier.height(20.dp))
        TrialBenefitRow(
            icon = Icons.Outlined.Cancel,
            title = stringResource(R.string.onboarding_trial_benefit3_title),
            description = stringResource(R.string.onboarding_trial_benefit3_desc)
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

@Composable
private fun TrialBenefitRow(icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = AccentBlue
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(description, fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp)
        }
    }
}
