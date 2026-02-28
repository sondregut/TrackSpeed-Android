package com.trackspeed.android.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.ui.theme.*

private val CardBackground = SurfaceDark
private val TextPrimary = com.trackspeed.android.ui.theme.TextPrimary
private val TextSecondary = com.trackspeed.android.ui.theme.TextSecondary
private val DividerColor = BorderSubtle
private val AccentBlue = AccentNavy
private val AccentGreen = com.trackspeed.android.ui.theme.AccentGreen
private val PresetBackground = SurfaceDark

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
                title = { Text(stringResource(R.string.converter_title), color = TextPrimary) },
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
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        DistanceConverterContent(
            state = state,
            onInputChanged = viewModel::setInputValue,
            onFromUnitSelected = viewModel::setFromUnit,
            onToUnitSelected = viewModel::setToUnit,
            onSwapUnits = viewModel::swapUnits,
            onPresetClick = viewModel::applyPreset,
            modifier = Modifier.padding(padding)
        )
    }
    } // close Box
}

@Composable
private fun DistanceConverterContent(
    state: DistanceConverterUiState,
    onInputChanged: (String) -> Unit,
    onFromUnitSelected: (DistanceUnit) -> Unit,
    onToUnitSelected: (DistanceUnit) -> Unit,
    onSwapUnits: () -> Unit,
    onPresetClick: (ConversionPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    var fromDropdownExpanded by remember { mutableStateOf(false) }
    var toDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Conversion Section
        SectionLabel(stringResource(R.string.converter_section_convert))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Input value
                OutlinedTextField(
                    value = state.inputValue,
                    onValueChange = onInputChanged,
                    label = { Text(stringResource(R.string.converter_value_label), color = TextSecondary) },
                    placeholder = { Text(stringResource(R.string.converter_value_placeholder), color = TextSecondary.copy(alpha = 0.5f)) },
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

                Spacer(modifier = Modifier.height(16.dp))

                // From unit selector
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.converter_from),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.width(50.dp)
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            color = PresetBackground,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { fromDropdownExpanded = true }
                        ) {
                            Text(
                                text = state.fromUnit.displayName,
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = fromDropdownExpanded,
                            onDismissRequest = { fromDropdownExpanded = false },
                            containerColor = CardBackground
                        ) {
                            DistanceUnit.entries.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit.displayName, color = TextPrimary) },
                                    onClick = {
                                        onFromUnitSelected(unit)
                                        fromDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Swap button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = onSwapUnits) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = stringResource(R.string.converter_swap_units),
                            tint = AccentBlue,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // To unit selector
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.converter_to),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.width(50.dp)
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            color = PresetBackground,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { toDropdownExpanded = true }
                        ) {
                            Text(
                                text = state.toUnit.displayName,
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = toDropdownExpanded,
                            onDismissRequest = { toDropdownExpanded = false },
                            containerColor = CardBackground
                        ) {
                            DistanceUnit.entries.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit.displayName, color = TextPrimary) },
                                    onClick = {
                                        onToUnitSelected(unit)
                                        toDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Result display
        state.result?.let { result ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.converter_result),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = formatResult(result),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = AccentBlue
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.toUnit.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Common presets
        SectionLabel(stringResource(R.string.converter_section_presets))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                PRESETS.forEachIndexed { index, preset ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPresetClick(preset) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.converter_apply),
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (index < PRESETS.lastIndex) {
                        HorizontalDivider(
                            color = DividerColor,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

private fun formatResult(value: Double): String {
    return when {
        value >= 1000 -> "%.2f".format(value)
        value >= 1 -> "%.4f".format(value)
        else -> "%.6f".format(value)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun DistanceConverterScreenPreview() {
    TrackSpeedTheme(darkTheme = true) {
        DistanceConverterContent(
            state = DistanceConverterUiState(
                inputValue = "100",
                fromUnit = DistanceUnit.METERS,
                toUnit = DistanceUnit.YARDS,
                result = 109.3613
            ),
            onInputChanged = {},
            onFromUnitSelected = {},
            onToUnitSelected = {},
            onSwapUnits = {},
            onPresetClick = {}
        )
    }
}
