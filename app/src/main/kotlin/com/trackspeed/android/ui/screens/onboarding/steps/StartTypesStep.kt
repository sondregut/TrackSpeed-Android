package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.model.StartType
import com.trackspeed.android.ui.theme.*
import com.trackspeed.android.ui.util.localizedDescription
import com.trackspeed.android.ui.util.localizedDisplayName

private fun StartType.onboardingIcon(): ImageVector = when (this) {
    StartType.FLYING -> Icons.AutoMirrored.Filled.DirectionsRun
    StartType.TOUCH_RELEASE -> Icons.Default.TouchApp
    StartType.COUNTDOWN -> Icons.Default.Timer
    StartType.VOICE_COMMAND -> Icons.Default.Mic
    StartType.IN_FRAME, StartType.MOTION_START, StartType.REACTION_START -> Icons.Default.CenterFocusStrong
}

@Composable
fun StartTypesStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.onboarding_starttypes_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_starttypes_subtitle), fontSize = 17.sp, color = TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(StartType.localStartTypes, key = { it.rawValue }) { type ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(type.onboardingIcon(), contentDescription = null, modifier = Modifier.size(32.dp), tint = AccentNavy)
                        Spacer(Modifier.width(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(type.localizedDisplayName(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(type.localizedDescription(), fontSize = 13.sp, color = TextSecondary)
                        }
                    }
                }
            }
        }

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNavy)
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }
}
