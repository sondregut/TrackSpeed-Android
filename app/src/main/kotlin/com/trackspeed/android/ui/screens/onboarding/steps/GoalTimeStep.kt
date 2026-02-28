package com.trackspeed.android.ui.screens.onboarding.steps

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
import com.trackspeed.android.data.model.SportDiscipline

@Composable
fun GoalTimeStep(
    discipline: SportDiscipline?,
    personalRecord: Double?,
    goalTime: Double?,
    onPersonalRecordChanged: (Double?) -> Unit,
    onGoalTimeChanged: (Double?) -> Unit,
    onContinue: () -> Unit
) {
    var prText by remember(personalRecord) { mutableStateOf(personalRecord?.let { String.format("%.2f", it) } ?: "") }
    var goalText by remember(goalTime) { mutableStateOf(goalTime?.let { String.format("%.2f", it) } ?: "") }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.onboarding_goal_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            discipline?.displayName?.let { stringResource(R.string.onboarding_goal_subtitle_event, it) } ?: stringResource(R.string.onboarding_goal_subtitle_default),
            fontSize = 17.sp, color = Color(0xFFAEAEB2), textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = prText,
            onValueChange = { prText = it; onPersonalRecordChanged(it.toDoubleOrNull()) },
            label = { Text(stringResource(R.string.onboarding_goal_pr_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0A84FF), unfocusedBorderColor = Color(0xFF3A3A3C),
                focusedLabelColor = Color(0xFF0A84FF), unfocusedLabelColor = Color(0xFF8E8E93)
            ),
            singleLine = true
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = goalText,
            onValueChange = { goalText = it; onGoalTimeChanged(it.toDoubleOrNull()) },
            label = { Text(stringResource(R.string.onboarding_goal_time_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0A84FF), unfocusedBorderColor = Color(0xFF3A3A3C),
                focusedLabelColor = Color(0xFF0A84FF), unfocusedLabelColor = Color(0xFF8E8E93)
            ),
            singleLine = true
        )

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
