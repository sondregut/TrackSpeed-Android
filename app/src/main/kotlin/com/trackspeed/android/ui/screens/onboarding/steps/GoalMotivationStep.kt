package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

@Composable
fun GoalMotivationStep(
    personalRecord: Double?,
    goalTime: Double?,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.onboarding_motivation_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_motivation_subtitle), fontSize = 17.sp, color = TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))

        if (personalRecord != null && goalTime != null && goalTime < personalRecord) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.onboarding_motivation_current, String.format("%.2f", personalRecord)), color = TimerRed, fontSize = 15.sp)
                Text(stringResource(R.string.onboarding_motivation_goal, String.format("%.2f", goalTime)), color = AccentGreen, fontSize = 15.sp)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(0f, h * 0.2f)
                    cubicTo(w * 0.3f, h * 0.3f, w * 0.6f, h * 0.6f, w, h * 0.8f)
                }
                drawPath(path, AccentNavy, style = Stroke(width = 3f))
                drawCircle(TimerRed, 8f, Offset(0f, h * 0.2f))
                drawCircle(AccentGreen, 8f, Offset(w, h * 0.8f))
            }
            Spacer(Modifier.height(16.dp))
            val improvement = personalRecord - goalTime
            Text(
                stringResource(R.string.onboarding_motivation_improvement, String.format("%.2f", improvement)),
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AccentGreen
            )
        } else {
            Text(stringResource(R.string.onboarding_motivation_empty), fontSize = 17.sp, color = TextSecondary, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.weight(1f))

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
