package com.trackspeed.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.ui.theme.TrackSpeedTheme

private val ScreenBackground = Color(0xFF000000)
private val CardBackground = Color(0xFF2C2C2E)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFF8E8E93)
private val TextTertiary = Color(0xFF636366)
private val DividerColor = Color(0xFF38383A)
private val AccentBlue = Color(0xFF0A84FF)
private val AccentGreen = Color(0xFF00E676)

/**
 * Map of display labels to distance values in meters.
 */
private val distanceOptions = linkedMapOf(
    "40yd" to 36.576,
    "60m" to 60.0,
    "100m" to 100.0,
    "200m" to 200.0
)

/**
 * Map of display labels to start type identifiers.
 */
private val startTypeOptions = linkedMapOf(
    "Flying" to "flying",
    "Standing" to "standing"
)

@Composable
fun SettingsScreen(
    onPaywallClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreenContent(
        state = state,
        onDistanceSelected = { viewModel.setDefaultDistance(it) },
        onStartTypeSelected = { viewModel.setStartType(it) },
        onSpeedUnitSelected = { viewModel.setSpeedUnit(it) },
        onDarkModeChanged = { viewModel.setDarkMode(it) },
        onSensitivityChanged = { viewModel.setDetectionSensitivity(it) },
        onFpsSelected = { viewModel.setPreferredFps(it) },
        onPaywallClick = onPaywallClick
    )
}

@Composable
private fun SettingsScreenContent(
    state: SettingsUiState,
    onDistanceSelected: (Double) -> Unit,
    onStartTypeSelected: (String) -> Unit,
    onSpeedUnitSelected: (String) -> Unit,
    onDarkModeChanged: (Boolean) -> Unit,
    onSensitivityChanged: (Float) -> Unit,
    onFpsSelected: (Int) -> Unit = {},
    onPaywallClick: () -> Unit = {}
) {
    var distanceDropdownExpanded by remember { mutableStateOf(false) }
    var startTypeDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Subscription section
        SectionHeader("Subscription")

        if (state.isProUser) {
            ListItem(
                headlineContent = { Text("TrackSpeed Pro", color = TextPrimary) },
                supportingContent = { Text("Active", color = AccentGreen) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = AccentGreen
                    )
                },
                trailingContent = {
                    Text(
                        text = "Manage",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentBlue,
                        modifier = Modifier.clickable {
                            // TODO: Open Google Play subscription management
                        }
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
        } else {
            ListItem(
                headlineContent = { Text("Upgrade to Pro", color = TextPrimary) },
                supportingContent = { Text("Unlock all features", color = TextSecondary) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = AccentGreen
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary
                    )
                },
                modifier = Modifier.clickable { onPaywallClick() },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = DividerColor
        )

        // Timing section
        SectionHeader("Timing")

        // Distance
        Box {
            ListItem(
                headlineContent = { Text("Distance", color = TextPrimary) },
                supportingContent = { Text(state.distanceLabel, color = TextSecondary) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Straighten,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                modifier = Modifier.clickable { distanceDropdownExpanded = true },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
            DropdownMenu(
                expanded = distanceDropdownExpanded,
                onDismissRequest = { distanceDropdownExpanded = false },
                containerColor = CardBackground
            ) {
                distanceOptions.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label, color = TextPrimary) },
                        onClick = {
                            onDistanceSelected(value)
                            distanceDropdownExpanded = false
                        }
                    )
                }
            }
        }

        // Start type
        Box {
            ListItem(
                headlineContent = { Text("Start type", color = TextPrimary) },
                supportingContent = { Text(state.startTypeLabel, color = TextSecondary) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                modifier = Modifier.clickable { startTypeDropdownExpanded = true },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
            DropdownMenu(
                expanded = startTypeDropdownExpanded,
                onDismissRequest = { startTypeDropdownExpanded = false },
                containerColor = CardBackground
            ) {
                startTypeOptions.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label, color = TextPrimary) },
                        onClick = {
                            onStartTypeSelected(value)
                            startTypeDropdownExpanded = false
                        }
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = DividerColor
        )

        // Display section
        SectionHeader("Display")

        // Speed unit
        ListItem(
            headlineContent = { Text("Speed unit", color = TextPrimary) },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = TextSecondary
                )
            },
            supportingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    listOf("m/s", "km/h", "mph").forEach { unit ->
                        FilterChip(
                            selected = state.speedUnit == unit,
                            onClick = { onSpeedUnitSelected(unit) },
                            label = { Text(unit, color = if (state.speedUnit == unit) Color.White else TextSecondary) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CardBackground,
                                selectedContainerColor = AccentBlue,
                                labelColor = TextSecondary,
                                selectedLabelColor = Color.White
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = TextTertiary,
                                selectedBorderColor = AccentBlue,
                                enabled = true,
                                selected = state.speedUnit == unit
                            )
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )

        // Dark mode
        ListItem(
            headlineContent = { Text("Dark mode", color = TextPrimary) },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.DarkMode,
                    contentDescription = null,
                    tint = TextSecondary
                )
            },
            trailingContent = {
                Switch(
                    checked = state.darkMode,
                    onCheckedChange = { onDarkModeChanged(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentBlue,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = TextTertiary,
                        uncheckedBorderColor = TextTertiary
                    )
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = DividerColor
        )

        // Detection section
        SectionHeader("Detection")

        ListItem(
            headlineContent = { Text("Sensitivity", color = TextPrimary) },
            supportingContent = {
                Column {
                    Text(
                        text = String.format("%.1f", state.detectionSensitivity),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Slider(
                        value = state.detectionSensitivity,
                        onValueChange = { onSensitivityChanged(it) },
                        valueRange = 0.1f..1.0f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentBlue,
                            activeTrackColor = AccentBlue,
                            inactiveTrackColor = TextTertiary,
                            activeTickColor = AccentBlue,
                            inactiveTickColor = TextTertiary
                        )
                    )
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )

        // Frame rate
        ListItem(
            headlineContent = { Text("Frame rate", color = TextPrimary) },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Videocam,
                    contentDescription = null,
                    tint = TextSecondary
                )
            },
            supportingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    listOf(30, 60, 120).forEach { fps ->
                        FilterChip(
                            selected = state.preferredFps == fps,
                            onClick = { onFpsSelected(fps) },
                            label = {
                                Text(
                                    "${fps}fps",
                                    color = if (state.preferredFps == fps) Color.White else TextSecondary
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CardBackground,
                                selectedContainerColor = AccentBlue,
                                labelColor = TextSecondary,
                                selectedLabelColor = Color.White
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = TextTertiary,
                                selectedBorderColor = AccentBlue,
                                enabled = true,
                                selected = state.preferredFps == fps
                            )
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = DividerColor
        )

        // About section
        SectionHeader("About")

        ListItem(
            headlineContent = { Text("Version", color = TextPrimary) },
            supportingContent = { Text("TrackSpeed v1.0.0", color = TextSecondary) },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = TextSecondary
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )

        Spacer(modifier = Modifier.height(24.dp))
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
private fun SettingsScreenPreview() {
    TrackSpeedTheme(darkTheme = true) {
        SettingsScreenContent(
            state = SettingsUiState(),
            onDistanceSelected = {},
            onStartTypeSelected = {},
            onSpeedUnitSelected = {},
            onDarkModeChanged = {},
            onSensitivityChanged = {},
            onFpsSelected = {},
            onPaywallClick = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScreenDarkPreview() {
    TrackSpeedTheme(darkTheme = true) {
        SettingsScreenContent(
            state = SettingsUiState(isProUser = true),
            onDistanceSelected = {},
            onStartTypeSelected = {},
            onSpeedUnitSelected = {},
            onDarkModeChanged = {},
            onSensitivityChanged = {},
            onFpsSelected = {},
            onPaywallClick = {}
        )
    }
}
