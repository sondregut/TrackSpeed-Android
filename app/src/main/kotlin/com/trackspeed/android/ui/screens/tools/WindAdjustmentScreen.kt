package com.trackspeed.android.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.ui.theme.TrackSpeedTheme

private val ScreenBackground = Color(0xFF000000)
private val CardBackground = Color(0xFF1C1C1E)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFF8E8E93)
private val DividerColor = Color(0xFF38383A)
private val AccentBlue = Color(0xFF0A84FF)
private val AccentGreen = Color(0xFF30D158)
private val AccentRed = Color(0xFFFF453A)
private val AccentOrange = Color(0xFFFF9F0A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindAdjustmentScreen(
    onNavigateBack: () -> Unit,
    viewModel: WindAdjustmentViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wind_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = AccentBlue
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ScreenBackground
                )
            )
        },
        containerColor = ScreenBackground
    ) { padding ->
        WindAdjustmentContent(
            state = state,
            onEventSelected = viewModel::setEvent,
            onTimeInputChanged = viewModel::setTimeInput,
            onWindInputChanged = viewModel::setWindInput,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun WindAdjustmentContent(
    state: WindAdjustmentUiState,
    onEventSelected: (WindAdjustmentCalculator.Event) -> Unit,
    onTimeInputChanged: (String) -> Unit,
    onWindInputChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var eventDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Input Section
        SectionHeader(stringResource(R.string.wind_section_input))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Event picker
                Box {
                    OutlinedTextField(
                        value = state.selectedEvent.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.wind_event_label), color = TextSecondary) },
                        modifier = Modifier
                            .fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = DividerColor,
                            cursorColor = AccentBlue
                        )
                    )
                    // Invisible click target over the text field
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Transparent)
                            .let { mod ->
                                mod
                            }
                    ) {
                        DropdownMenu(
                            expanded = eventDropdownExpanded,
                            onDismissRequest = { eventDropdownExpanded = false },
                            containerColor = CardBackground
                        ) {
                            WindAdjustmentCalculator.Event.entries.forEach { event ->
                                DropdownMenuItem(
                                    text = { Text(event.displayName, color = TextPrimary) },
                                    onClick = {
                                        onEventSelected(event)
                                        eventDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    // Make the whole row clickable
                    Surface(
                        modifier = Modifier.matchParentSize(),
                        color = Color.Transparent,
                        onClick = { eventDropdownExpanded = true }
                    ) {}
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Time input
                OutlinedTextField(
                    value = state.timeInput,
                    onValueChange = onTimeInputChanged,
                    label = { Text(stringResource(R.string.wind_time_label), color = TextSecondary) },
                    placeholder = { Text(stringResource(R.string.wind_time_placeholder), color = TextSecondary.copy(alpha = 0.5f)) },
                    suffix = { Text(stringResource(R.string.wind_time_suffix), color = TextSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = DividerColor,
                        cursorColor = AccentBlue
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Wind input
                OutlinedTextField(
                    value = state.windInput,
                    onValueChange = onWindInputChanged,
                    label = { Text(stringResource(R.string.wind_wind_label), color = TextSecondary) },
                    placeholder = { Text(stringResource(R.string.wind_wind_placeholder), color = TextSecondary.copy(alpha = 0.5f)) },
                    suffix = { Text(stringResource(R.string.wind_wind_suffix), color = TextSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = DividerColor,
                        cursorColor = AccentBlue
                    )
                )
            }
        }

        Text(
            text = stringResource(R.string.wind_help_text),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Results Section
        if (state.hasValidInput) {
            SectionHeader(stringResource(R.string.wind_section_adjusted_times))

            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    state.zeroWindTime?.let { zeroWind ->
                        ResultRow(
                            label = stringResource(R.string.wind_zero_wind_time),
                            subtitle = stringResource(R.string.wind_zero_wind_subtitle),
                            time = zeroWind,
                            isHighlighted = true
                        )
                    }

                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))

                    state.tailwindTime?.let { tailwind ->
                        ResultRow(
                            label = stringResource(R.string.wind_tailwind),
                            subtitle = stringResource(R.string.wind_tailwind_subtitle),
                            time = tailwind,
                            isHighlighted = false
                        )
                    }

                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))

                    state.headwindTime?.let { headwind ->
                        ResultRow(
                            label = stringResource(R.string.wind_headwind),
                            subtitle = stringResource(R.string.wind_headwind_subtitle),
                            time = headwind,
                            isHighlighted = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Wind Legal Status
            state.parsedWind?.let { wind ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isLegalWind) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (state.isLegalWind) AccentGreen else AccentRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (state.isLegalWind) {
                                stringResource(R.string.wind_legal_format, wind)
                            } else {
                                stringResource(R.string.wind_illegal_format, wind)
                            },
                            color = if (state.isLegalWind) TextPrimary else AccentRed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Info Section
        SectionHeader(stringResource(R.string.wind_section_about))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.wind_how_it_works),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.wind_formula_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.wind_asymmetry_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ResultRow(
    label: String,
    subtitle: String,
    time: Double,
    isHighlighted: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Text(
            text = "%.3f".format(time),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            color = if (isHighlighted) AccentBlue else TextPrimary
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun WindAdjustmentScreenPreview() {
    TrackSpeedTheme(darkTheme = true) {
        WindAdjustmentContent(
            state = WindAdjustmentUiState(
                timeInput = "10.25",
                windInput = "1.5",
                hasValidInput = true,
                zeroWindTime = 10.312,
                tailwindTime = 10.189,
                headwindTime = 10.439,
                isLegalWind = true,
                parsedWind = 1.5
            ),
            onEventSelected = {},
            onTimeInputChanged = {},
            onWindInputChanged = {}
        )
    }
}
