package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.data.model.FlyingDistance

@Composable
fun FlyingTimeStep(
    selectedDistance: FlyingDistance?,
    flyingPR: Double?,
    onDistanceSelected: (FlyingDistance) -> Unit,
    onTimeChanged: (Double?) -> Unit,
    onContinue: () -> Unit
) {
    var timeText by remember(flyingPR) { mutableStateOf(flyingPR?.let { String.format("%.2f", it) } ?: "") }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.onboarding_flying_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_flying_subtitle), fontSize = 17.sp, color = Color(0xFFAEAEB2), textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        // Distance chips
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlyingDistance.entries.forEach { distance ->
                OutlinedButton(
                    onClick = { onDistanceSelected(distance) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    border = BorderStroke(1.dp, if (selectedDistance == distance) Color(0xFF0A84FF) else Color(0xFF3A3A3C)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selectedDistance == distance) Color(0xFF0A84FF).copy(alpha = 0.15f) else Color.Transparent
                    )
                ) {
                    Text(distance.displayName, color = if (selectedDistance == distance) Color(0xFF0A84FF) else Color.White, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = timeText,
            onValueChange = { value ->
                timeText = value
                onTimeChanged(value.toDoubleOrNull())
            },
            label = { Text(stringResource(R.string.onboarding_flying_time_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0A84FF),
                unfocusedBorderColor = Color(0xFF3A3A3C),
                focusedLabelColor = Color(0xFF0A84FF),
                unfocusedLabelColor = Color(0xFF8E8E93)
            ),
            singleLine = true
        )

        if (flyingPR != null && selectedDistance != null) {
            Spacer(Modifier.height(16.dp))
            val speed = selectedDistance.meters / flyingPR
            Text(
                text = stringResource(R.string.onboarding_flying_speed_format, speed, speed * 3.6),
                fontSize = 15.sp,
                color = Color(0xFF30D158)
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }
}
