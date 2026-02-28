package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportDiscipline

@Composable
fun GoalTimeStep(
    discipline: SportDiscipline?,
    personalRecord: Double?,
    goalTime: Double?,
    onPersonalRecordChanged: (Double?) -> Unit,
    onGoalTimeChanged: (Double?) -> Unit,
    onContinue: () -> Unit,
    flyingDistance: FlyingDistance? = null
) {
    var goalText by remember(goalTime) { mutableStateOf(goalTime?.let { String.format("%.2f", it) } ?: "") }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Distance in meters for speed calculation
    val distanceMeters = flyingDistance?.meters

    // Speed calculation
    val speedText = remember(goalText, distanceMeters) {
        val time = goalText.replace(",", ".").toDoubleOrNull()
        if (time != null && time > 0 && distanceMeters != null) {
            String.format("%.1f", distanceMeters / time)
        } else null
    }

    // Distance display name for subtitle
    val distanceLabel = flyingDistance?.displayName ?: discipline?.displayName

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header at top
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.onboarding_goal_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (distanceLabel != null) stringResource(R.string.onboarding_goal_subtitle_event, distanceLabel)
            else stringResource(R.string.onboarding_goal_subtitle_default),
            fontSize = 15.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        // Centered content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Target icon
            Icon(
                imageVector = Icons.Default.GpsFixed,
                contentDescription = null,
                tint = AccentNavy,
                modifier = Modifier.size(56.dp)
            )

            // Goal time input (iOS style)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (distanceLabel != null) "${stringResource(R.string.onboarding_goal_time_label)} for $distanceLabel"
                    else stringResource(R.string.onboarding_goal_time_label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark, RoundedCornerShape(12.dp))
                        .border(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) AccentNavy else BorderSubtle,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = goalText,
                        onValueChange = { value ->
                            goalText = value
                            onGoalTimeChanged(value.replace(",", ".").toDoubleOrNull())
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { isFocused = it.isFocused },
                        textStyle = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        cursorBrush = SolidColor(AccentNavy),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.Center) {
                                if (goalText.isEmpty()) {
                                    Text(
                                        "0.00",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextMuted,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    Text(
                        "seconds",
                        fontSize = 16.sp,
                        color = TextSecondary
                    )
                }

                // Speed feedback
                if (speedText != null) {
                    Text(
                        "That's $speedText m/s",
                        fontSize = 15.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Continue button
        Button(
            onClick = {
                focusManager.clearFocus()
                onContinue()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNavy)
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }
}
