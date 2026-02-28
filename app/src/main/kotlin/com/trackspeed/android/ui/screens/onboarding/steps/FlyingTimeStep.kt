package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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

@Composable
fun FlyingTimeStep(
    selectedDistance: FlyingDistance?,
    flyingPR: Double?,
    onDistanceSelected: (FlyingDistance) -> Unit,
    onTimeChanged: (Double?) -> Unit,
    onContinue: () -> Unit
) {
    var timeText by remember(flyingPR) { mutableStateOf(flyingPR?.let { String.format("%.2f", it) } ?: "") }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Speed calculation
    val speedText = remember(timeText, selectedDistance) {
        val time = timeText.replace(",", ".").toDoubleOrNull()
        if (time != null && time > 0 && selectedDistance != null) {
            String.format("%.1f", selectedDistance.meters / time)
        } else null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header at top
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.onboarding_flying_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_flying_subtitle),
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
            // Bolt icon
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = AccentNavy,
                modifier = Modifier.size(56.dp)
            )

            // Distance selector
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.onboarding_flying_distance_label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FlyingDistance.entries.forEach { distance ->
                        val isSelected = selectedDistance == distance
                        Button(
                            onClick = { onDistanceSelected(distance) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isSelected) AccentNavy else BorderSubtle),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) AccentNavy else SurfaceDark
                            )
                        ) {
                            Text(
                                distance.displayName,
                                color = if (isSelected) Color.White else TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Time input (iOS style: inline text + "seconds")
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.onboarding_flying_time_label),
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
                        value = timeText,
                        onValueChange = { value ->
                            timeText = value
                            onTimeChanged(value.replace(",", ".").toDoubleOrNull())
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { isFocused = it.isFocused },
                        textStyle = TextStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        cursorBrush = SolidColor(AccentNavy),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.Center) {
                                if (timeText.isEmpty()) {
                                    Text(
                                        "0.00",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
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
