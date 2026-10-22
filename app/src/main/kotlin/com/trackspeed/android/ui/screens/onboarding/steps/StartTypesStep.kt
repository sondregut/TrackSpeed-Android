package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.model.StartType
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
    OnboardingFeaturePage(R.string.parity_tour_starts_title,
        R.string.parity_tour_starts_detail, onContinue) {
        // The owned iPhone screenshot includes Reaction Start. Render supported native
        // cards instead so this tutorial neither advertises it nor bakes in English text.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StartType.localStartTypes.forEach { type ->
                Surface(color = Color(0xFF2C3036), shape = RoundedCornerShape(16.dp),
                    border = if (type == StartType.FLYING) BorderStroke(1.5.dp, Color.White) else null) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(type.onboardingIcon(), contentDescription = null, tint = Color.White,
                                modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(type.localizedDisplayName(), fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(type.localizedDescription(), fontSize = 14.sp, lineHeight = 19.sp,
                                color = Color.White.copy(alpha = 0.65f))
                        }
                    }
                }
            }
        }
    }
}
