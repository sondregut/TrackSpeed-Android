package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.ui.screens.tools.FlyingTimeEstimator
import com.trackspeed.android.ui.theme.AccentBlue
import com.trackspeed.android.ui.theme.BorderSubtle
import com.trackspeed.android.ui.theme.SurfaceDark
import com.trackspeed.android.ui.theme.TextMuted
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlyingTimeStep(
    selectedDistance: FlyingDistance?,
    flyingPR: Double?,
    usesEventPrEstimate: Boolean,
    eventDiscipline: SportDiscipline?,
    eventPR: Double?,
    onDistanceSelected: (FlyingDistance) -> Unit,
    onTimeChanged: (Double?) -> Unit,
    onUseEventPrEstimateChanged: (Boolean) -> Unit,
    onEventDisciplineSelected: (SportDiscipline) -> Unit,
    onEventPrChanged: (Double?) -> Unit,
    onContinue: () -> Unit
) {
    val distance = selectedDistance ?: FlyingDistance.METERS_10
    val selectedEvent = eventDiscipline ?: SportDiscipline.SPRINT_100M
    var timeText by remember {
        mutableStateOf(if (!usesEventPrEstimate) flyingPR?.let { String.format(java.util.Locale.getDefault(), "%.3f", it) } ?: "" else "")
    }
    var eventPrText by remember {
        mutableStateOf(eventPR?.let { String.format(java.util.Locale.getDefault(), "%.2f", it) } ?: "")
    }
    var focusedField by remember { mutableStateOf<FlyingTimeField?>(null) }
    val focusManager = LocalFocusManager.current

    val directFlyingTime = parseTimeInput(timeText)
    val eventPrValue = parseTimeInput(eventPrText)
    val estimate = eventPrValue?.let {
        FlyingTimeEstimator.estimateFlyingTime(
            eventTime = it,
            event = selectedEvent,
            targetDistance = distance
        )
    }
    val displayedTime = if (usesEventPrEstimate) estimate else directFlyingTime
    val speedText = displayedTime
        ?.takeIf { it > 0.0 }
        ?.let { String.format(java.util.Locale.getDefault(), "%.1f", distance.meters / it) }

    val hasInvalidFlyingTime = timeText.trim().isNotEmpty() && directFlyingTime == null
    val hasInvalidEventPr = eventPrText.trim().isNotEmpty() && eventPrValue == null
    val canContinue = if (usesEventPrEstimate) {
        estimate != null && !hasInvalidEventPr
    } else {
        !hasInvalidFlyingTime
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.onboarding_flying_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_flying_explainer),
                fontSize = 15.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(56.dp)
            )

            Spacer(Modifier.height(24.dp))
            DistanceSelector(
                selectedDistance = distance,
                onDistanceSelected = onDistanceSelected
            )

            Spacer(Modifier.height(24.dp))
            if (usesEventPrEstimate) {
                EventPrEstimateForm(
                    selectedEvent = selectedEvent,
                    eventPrText = eventPrText,
                    eventPrValue = eventPrValue,
                    hasInvalidEventPr = hasInvalidEventPr,
                    estimate = estimate,
                    distance = distance,
                    speedText = speedText,
                    focusedField = focusedField,
                    onFocusedFieldChanged = { focusedField = it },
                    onEventSelected = onEventDisciplineSelected,
                    onEventPrTextChanged = { value ->
                        eventPrText = value
                        onEventPrChanged(parseTimeInput(value))
                    },
                    onUseDirect = {
                        focusManager.clearFocus()
                        onUseEventPrEstimateChanged(false)
                    }
                )
            } else {
                DirectFlyingPbForm(
                    timeText = timeText,
                    hasInvalidFlyingTime = hasInvalidFlyingTime,
                    speedText = speedText,
                    focusedField = focusedField,
                    onFocusedFieldChanged = { focusedField = it },
                    onTimeTextChanged = { value ->
                        timeText = value
                        onTimeChanged(parseTimeInput(value))
                    },
                    onUseEstimate = {
                        focusManager.clearFocus()
                        onUseEventPrEstimateChanged(true)
                    }
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        Button(
            onClick = {
                if (!canContinue) return@Button
                focusManager.clearFocus()
                if (!usesEventPrEstimate && selectedDistance == null) {
                    onDistanceSelected(distance)
                }
                onContinue()
            },
            enabled = canContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBlue,
                disabledContainerColor = AccentBlue.copy(alpha = 0.45f)
            )
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DistanceSelector(
    selectedDistance: FlyingDistance,
    onDistanceSelected: (FlyingDistance) -> Unit
) {
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

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            FlyingDistance.entries.forEachIndexed { index, distance ->
                SegmentedButton(
                    selected = selectedDistance == distance,
                    onClick = { onDistanceSelected(distance) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = FlyingDistance.entries.size
                    ),
                    label = {
                        Text(distance.rawValue, fontWeight = FontWeight.Medium)
                    }
                )
            }
        }
    }
}

@Composable
private fun DirectFlyingPbForm(
    timeText: String,
    hasInvalidFlyingTime: Boolean,
    speedText: String?,
    focusedField: FlyingTimeField?,
    onFocusedFieldChanged: (FlyingTimeField?) -> Unit,
    onTimeTextChanged: (String) -> Unit,
    onUseEstimate: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.onboarding_flying_personal_best),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )

        TimeInputBox(
            text = timeText,
            placeholder = "0.000",
            isFocused = focusedField == FlyingTimeField.FLYING_TIME,
            onFocusChanged = { focused -> onFocusedFieldChanged(if (focused) FlyingTimeField.FLYING_TIME else null) },
            onTextChanged = onTimeTextChanged
        )

        when {
            hasInvalidFlyingTime -> Text(
                stringResource(R.string.onboarding_flying_invalid_time),
                fontSize = 13.sp,
                color = TextMuted
            )
            speedText != null -> Text(
                stringResource(R.string.onboarding_flying_speed_simple, speedText),
                fontSize = 15.sp,
                color = TextSecondary
            )
        }

        Button(
            onClick = onUseEstimate,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue.copy(alpha = 0.12f))
        ) {
            Text(
                stringResource(R.string.onboarding_flying_no_pb),
                color = AccentBlue,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventPrEstimateForm(
    selectedEvent: SportDiscipline,
    eventPrText: String,
    eventPrValue: Double?,
    hasInvalidEventPr: Boolean,
    estimate: Double?,
    distance: FlyingDistance,
    speedText: String?,
    focusedField: FlyingTimeField?,
    onFocusedFieldChanged: (FlyingTimeField?) -> Unit,
    onEventSelected: (SportDiscipline) -> Unit,
    onEventPrTextChanged: (String) -> Unit,
    onUseDirect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.onboarding_flying_event_question),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                FlyingTimeEstimator.supportedEventDisciplines.forEachIndexed { index, discipline ->
                    SegmentedButton(
                        selected = selectedEvent == discipline,
                        onClick = { onEventSelected(discipline) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = FlyingTimeEstimator.supportedEventDisciplines.size
                        ),
                        label = {
                            Text(discipline.rawValue, fontWeight = FontWeight.Medium)
                        }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.onboarding_flying_event_pr),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
            TimeInputBox(
                text = eventPrText,
                placeholder = "10.50",
                isFocused = focusedField == FlyingTimeField.EVENT_PR,
                onFocusChanged = { focused -> onFocusedFieldChanged(if (focused) FlyingTimeField.EVENT_PR else null) },
                onTextChanged = onEventPrTextChanged
            )
            if (hasInvalidEventPr) {
                Text(
                    stringResource(R.string.onboarding_flying_invalid_event_time),
                    fontSize = 13.sp,
                    color = TextMuted
                )
            }
        }

        EstimateSummary(
            estimate = estimate,
            eventPrValue = eventPrValue,
            distance = distance,
            speedText = speedText
        )

        Button(
            onClick = onUseDirect,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue.copy(alpha = 0.12f))
        ) {
            Text(
                stringResource(R.string.onboarding_flying_has_pb),
                color = AccentBlue,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EstimateSummary(
    estimate: Double?,
    eventPrValue: Double?,
    distance: FlyingDistance,
    speedText: String?
) {
    if (estimate != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(12.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.onboarding_flying_estimated, distance.rawValue),
                fontSize = 13.sp,
                color = TextSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.common_seconds_value,
                        String.format(java.util.Locale.getDefault(), "%.3f", estimate)
                    ),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = AccentBlue
                )
                if (speedText != null) {
                    Text(
                        stringResource(R.string.onboarding_flying_speed_suffix, speedText),
                        fontSize = 15.sp,
                        color = TextSecondary
                    )
                }
            }
            Text(
                stringResource(R.string.onboarding_flying_replace_later),
                fontSize = 13.sp,
                color = TextMuted
            )
        }
    } else if (eventPrValue != null) {
        Text(
            stringResource(R.string.onboarding_flying_estimate_unavailable),
            fontSize = 13.sp,
            color = TextMuted
        )
    }
}

@Composable
private fun TimeInputBox(
    text: String,
    placeholder: String,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onTextChanged: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) AccentBlue else BorderSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            textStyle = TextStyle(
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = TextPrimary,
                textAlign = TextAlign.Center
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            cursorBrush = SolidColor(AccentBlue),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.Center) {
                    if (text.isEmpty()) {
                        Text(
                            placeholder,
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
        Text(stringResource(R.string.run_detail_seconds_label), fontSize = 16.sp, color = TextSecondary)
    }
}

private enum class FlyingTimeField {
    FLYING_TIME,
    EVENT_PR
}

private fun parseTimeInput(text: String): Double? {
    val trimmed = text
        .trim()
        .replace(",", ".")
        .replace("s", "", ignoreCase = true)
    if (trimmed.isBlank()) return null

    trimmed.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { return it }

    val parts = trimmed.split(":")
    if (parts.size == 2) {
        val minutes = parts[0].toDoubleOrNull()
        val seconds = parts[1].toDoubleOrNull()
        if (minutes != null && seconds != null && minutes >= 0.0 && seconds >= 0.0) {
            return minutes * 60.0 + seconds
        }
    }

    return null
}
