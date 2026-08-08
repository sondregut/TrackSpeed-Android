package com.trackspeed.android.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.AccentBlue
import com.trackspeed.android.ui.theme.BorderSubtle
import com.trackspeed.android.ui.theme.CardBackground
import com.trackspeed.android.ui.theme.DividerColor
import com.trackspeed.android.ui.theme.SurfaceDark
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary
import com.trackspeed.android.ui.theme.TextTertiary
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import com.trackspeed.android.ui.theme.gradientBackground
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistanceConverterScreen(
    onNavigateBack: () -> Unit,
    viewModel: DistanceConverterViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().gradientBackground()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.profile_distance_converter), color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                                tint = AccentBlue
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            DistanceConverterContent(
                state = state,
                onModeSelected = viewModel::setMode,
                onGenderSelected = viewModel::setGender,
                onSelectedDistanceChanged = viewModel::setSelectedDistance,
                onTimeInputChanged = viewModel::setTimeInput,
                onFlyingDistanceChanged = viewModel::setFlyingDistance,
                onFlyingTimeChanged = viewModel::setFlyingTimeInput,
                onBlock30Changed = viewModel::setBlock30Input,
                onFly10Changed = viewModel::setFly10Input,
                onPredictorWindChanged = viewModel::setPredictorWindInput,
                onReactionTimeChanged = viewModel::setReactionTimeInput,
                onLaneTimeChanged = viewModel::setLaneTimeInput,
                onSelectedLaneChanged = viewModel::setSelectedLane,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun DistanceConverterContent(
    state: DistanceConverterUiState,
    onModeSelected: (ConverterMode) -> Unit,
    onGenderSelected: (SprintGender) -> Unit,
    onSelectedDistanceChanged: (SprintDistance) -> Unit,
    onTimeInputChanged: (String) -> Unit,
    onFlyingDistanceChanged: (FlyingSprintConverter.FlyingDistance) -> Unit,
    onFlyingTimeChanged: (String) -> Unit,
    onBlock30Changed: (String) -> Unit,
    onFly10Changed: (String) -> Unit,
    onPredictorWindChanged: (String) -> Unit,
    onReactionTimeChanged: (String) -> Unit,
    onLaneTimeChanged: (String) -> Unit,
    onSelectedLaneChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(2.dp))
        ModePicker(selected = state.mode, onSelected = onModeSelected)
        if (state.mode != ConverterMode.LANES) {
            GenderPicker(selected = state.gender, onSelected = onGenderSelected)
        }

        when (state.mode) {
            ConverterMode.DISTANCE -> DistanceMode(
                state = state,
                onSelectedDistanceChanged = onSelectedDistanceChanged,
                onTimeInputChanged = onTimeInputChanged
            )
            ConverterMode.FLYING -> FlyingMode(
                state = state,
                onFlyingDistanceChanged = onFlyingDistanceChanged,
                onFlyingTimeChanged = onFlyingTimeChanged
            )
            ConverterMode.PREDICTOR -> PredictorMode(
                state = state,
                onBlock30Changed = onBlock30Changed,
                onFly10Changed = onFly10Changed,
                onPredictorWindChanged = onPredictorWindChanged,
                onReactionTimeChanged = onReactionTimeChanged
            )
            ConverterMode.LANES -> LaneMode(
                state = state,
                onLaneTimeChanged = onLaneTimeChanged,
                onSelectedLaneChanged = onSelectedLaneChanged
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
    }
}

@Composable
private fun ModePicker(
    selected: ConverterMode,
    onSelected: (ConverterMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ConverterMode.entries.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                label = {
                    Text(
                        text = mode.label,
                        maxLines = 1
                    )
                },
                modifier = Modifier.weight(1f),
                colors = chipColors()
            )
        }
    }
}

@Composable
private fun GenderPicker(
    selected: SprintGender,
    onSelected: (SprintGender) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SprintGender.entries.forEach { gender ->
            FilterChip(
                selected = selected == gender,
                onClick = { onSelected(gender) },
                label = { Text(gender.displayName) },
                modifier = Modifier.weight(1f),
                colors = chipColors()
            )
        }
    }
}

@Composable
private fun DistanceMode(
    state: DistanceConverterUiState,
    onSelectedDistanceChanged: (SprintDistance) -> Unit,
    onTimeInputChanged: (String) -> Unit
) {
    InputCard(
        title = "Input",
        footer = "Enter your time for the selected sprint distance."
    ) {
        DropdownSelector(
            label = "Distance",
            selected = state.selectedDistance,
            options = SprintDistance.entries,
            optionLabel = { it.displayName },
            onSelected = onSelectedDistanceChanged
        )
        TimeInputRow(
            label = "Time",
            value = state.timeInput,
            placeholder = "10.25",
            suffix = "s",
            onValueChange = onTimeInputChanged
        )
    }

    ResultsCard(title = "Estimated Times") {
        SprintDistance.entries.filterNot { it == state.selectedDistance }.forEachIndexed { index, distance ->
            ResultRow(label = distance.displayName, time = state.distanceResults[distance])
            if (index < SprintDistance.entries.size - 2) Divider()
        }
    }

    NotesCard(
        title = "Important Notes",
        notes = buildList {
            add("Based on 2025 World Athletics Scoring Tables.")
            add("Uses quadratic-fit coefficients; audited sprint events are within about 0.02s of table lookup.")
            add("Individual speed endurance profiles cause variation.")
            if (state.selectedDistance == SprintDistance.M60 || state.timeInput.isNotBlank()) {
                add("60m to 100m is cross-context indoor-to-outdoor and less reliable.")
            }
            add("Wind conditions are not factored here; use Wind Adjustment separately.")
        }
    )
}

@Composable
private fun FlyingMode(
    state: DistanceConverterUiState,
    onFlyingDistanceChanged: (FlyingSprintConverter.FlyingDistance) -> Unit,
    onFlyingTimeChanged: (String) -> Unit
) {
    InputCard(
        title = "Input",
        footer = state.flyingDistance.caveat
    ) {
        DropdownSelector(
            label = "Distance",
            selected = state.flyingDistance,
            options = FlyingSprintConverter.FlyingDistance.entries,
            optionLabel = { it.displayName },
            onSelected = onFlyingDistanceChanged
        )
        TimeInputRow(
            label = "Time",
            value = state.flyingTimeInput,
            placeholder = "2.70",
            suffix = "s",
            onValueChange = onFlyingTimeChanged
        )
    }

    VelocityCard(
        velocityMs = state.flyingResult?.velocityMs,
        velocityKmh = state.flyingResult?.velocityKmh
    )

    ResultsCard(title = "Predicted Times") {
        SprintDistance.entries.forEachIndexed { index, distance ->
            ResultRow(label = distance.displayName, time = state.flyingResult?.conversions?.get(distance))
            if (index < SprintDistance.entries.lastIndex) Divider()
        }
    }

    NotesCard(
        title = "Important Notes",
        notes = listOf(
            "Assumes about 1.0s acceleration penalty, which varies by athlete.",
            "Flying 10m and 20m are extrapolated to fly30 using constant velocity.",
            "Flying 30m is the most reliable direct 100m potential estimate.",
            "Predicted distances use 2025 World Athletics Scoring Tables."
        )
    )
}

@Composable
private fun PredictorMode(
    state: DistanceConverterUiState,
    onBlock30Changed: (String) -> Unit,
    onFly10Changed: (String) -> Unit,
    onPredictorWindChanged: (String) -> Unit,
    onReactionTimeChanged: (String) -> Unit
) {
    InputCard(
        title = "Input",
        footer = "30m from standing start excluding reaction time. Flying 10m at max velocity. Wind uses + tailwind / - headwind."
    ) {
        TimeInputRow(
            label = "30m Time",
            value = state.block30Input,
            placeholder = "4.00",
            suffix = "s",
            onValueChange = onBlock30Changed
        )
        TimeInputRow(
            label = "Flying 10m",
            value = state.fly10Input,
            placeholder = "1.00",
            suffix = "s",
            onValueChange = onFly10Changed
        )
        TimeInputRow(
            label = "Reaction",
            value = state.reactionTimeInput,
            placeholder = "0.149",
            suffix = "s",
            onValueChange = onReactionTimeChanged
        )
        TimeInputRow(
            label = "Wind",
            value = state.predictorWindInput,
            placeholder = "0.0",
            suffix = "m/s",
            signed = true,
            onValueChange = onPredictorWindChanged
        )
    }

    VelocityCard(
        velocityMs = state.predictorResult?.velocityMs,
        velocityKmh = state.predictorResult?.velocityKmh
    )

    ResultsCard(title = "Predicted Times") {
        SprintDistance.entries.forEachIndexed { index, distance ->
            ResultRow(label = distance.displayName, time = state.predictorResult?.conversions?.get(distance))
            if (index < SprintDistance.entries.lastIndex) Divider()
        }
    }

    NotesCard(
        title = "Important Notes",
        notes = listOf(
            "Model trained on men's 9.58-11.66s 100m range; less accurate outside that range.",
            "Less reliable for women because the regression model was trained on men's data.",
            "Uses published coefficients with wind-aware 100m correction.",
            "Derived distances use WA quadratic-fit coefficients."
        )
    )
}

@Composable
private fun LaneMode(
    state: DistanceConverterUiState,
    onLaneTimeChanged: (String) -> Unit,
    onSelectedLaneChanged: (Int) -> Unit
) {
    InputCard(
        title = "Input",
        footer = "Enter your 200m time and the lane you ran in."
    ) {
        TimeInputRow(
            label = "200m Time",
            value = state.laneTimeInput,
            placeholder = "20.50",
            suffix = "s",
            onValueChange = onLaneTimeChanged
        )
        DropdownSelector(
            label = "Lane",
            selected = state.selectedLane,
            options = LaneDrawConverter.laneRange.toList(),
            optionLabel = { "Lane $it" },
            onSelected = onSelectedLaneChanged
        )
    }

    ResultsCard(title = "Equivalent Times by Lane") {
        val originalTime = state.laneTimeInput.toDoubleOrNull()
        LaneDrawConverter.laneRange.forEachIndexed { index, lane ->
            LaneResultRow(
                lane = lane,
                selectedLane = state.selectedLane,
                originalTime = originalTime,
                adjustedTime = state.laneResults[lane]
            )
            if (index < LaneDrawConverter.laneRange.last - LaneDrawConverter.laneRange.first) Divider()
        }
    }

    NotesCard(
        title = "Important Notes",
        notes = listOf(
            "Effect: 0.018s per lane; lane 9 is about 0.14s faster than lane 1.",
            "Based on Diamond League data from 2015-2021 across 425 results.",
            "Corrected for athlete ability using season-best times.",
            "Trained on elite men's data, so the effect may differ for other levels."
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownSelector(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label, color = TextSecondary) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
            colors = fieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = CardBackground
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), color = TextPrimary) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TimeInputRow(
    label: String,
    value: String,
    placeholder: String,
    suffix: String,
    signed: Boolean = false,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        placeholder = { Text(placeholder, color = TextSecondary.copy(alpha = 0.55f)) },
        suffix = { Text(suffix, color = TextSecondary) },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors()
    )
}

@Composable
private fun InputCard(
    title: String,
    footer: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SectionCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun ResultsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SectionCard(title = title) {
        Column {
            content()
        }
    }
}

@Composable
private fun VelocityCard(
    velocityMs: Double?,
    velocityKmh: Double?
) {
    ResultsCard(title = "Top Speed") {
        MetricRow(
            label = "Max Velocity",
            value = velocityMs?.let { "${formatNumber(it, 3)} m/s" } ?: "- m/s"
        )
        Spacer(modifier = Modifier.height(3.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = velocityKmh?.let { "${formatNumber(it, 1)} km/h" } ?: "- km/h",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun NotesCard(title: String, notes: List<String>) {
    SectionCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            notes.forEach { note ->
                Text(
                    text = "- $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

@Composable
private fun ResultRow(label: String, time: Double?) {
    MetricRow(
        label = label,
        value = time?.let { "${formatRaceTime(it)} s" } ?: "- s"
    )
}

@Composable
private fun LaneResultRow(
    lane: Int,
    selectedLane: Int,
    originalTime: Double?,
    adjustedTime: Double?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Lane $lane",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = if (lane == selectedLane) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (adjustedTime != null && originalTime != null) {
            val delta = adjustedTime - originalTime
            Text(
                text = if (lane == selectedLane) "ran" else formatDelta(delta),
                style = MaterialTheme.typography.bodySmall,
                color = if (delta < 0.0) AccentBlue else TextSecondary,
                modifier = Modifier.width(56.dp)
            )
            Text(
                text = "${formatRaceTime(adjustedTime)} s",
                style = MaterialTheme.typography.titleMedium,
                color = if (lane == selectedLane) AccentBlue else TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Text(
                text = "- s",
                style = MaterialTheme.typography.titleMedium,
                color = TextTertiary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = AccentBlue,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = DividerColor.copy(alpha = 0.7f))
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = AccentBlue.copy(alpha = 0.22f),
    selectedLabelColor = TextPrimary,
    containerColor = SurfaceDark,
    labelColor = TextSecondary
)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = AccentBlue,
    unfocusedBorderColor = BorderSubtle,
    focusedContainerColor = SurfaceDark,
    unfocusedContainerColor = SurfaceDark,
    cursorColor = AccentBlue
)

private fun formatRaceTime(value: Double): String = formatNumber(value, 3)

private fun formatNumber(value: Double, digits: Int): String {
    return String.format(Locale.US, "%.${digits}f", value)
}

private fun formatDelta(value: Double): String {
    return String.format(Locale.US, "%+.3f", value)
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun DistanceConverterScreenPreview() {
    TrackSpeedTheme {
        DistanceConverterContent(
            state = DistanceConverterUiState(
                timeInput = "10.25",
                distanceResults = SprintDistanceConverter.convertToAll(
                    timeSeconds = 10.25,
                    from = SprintDistance.M100,
                    gender = SprintGender.MEN
                )
            ),
            onModeSelected = {},
            onGenderSelected = {},
            onSelectedDistanceChanged = {},
            onTimeInputChanged = {},
            onFlyingDistanceChanged = {},
            onFlyingTimeChanged = {},
            onBlock30Changed = {},
            onFly10Changed = {},
            onPredictorWindChanged = {},
            onReactionTimeChanged = {},
            onLaneTimeChanged = {},
            onSelectedLaneChanged = {}
        )
    }
}
