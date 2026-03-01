package com.trackspeed.android.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*

private val DestructiveRed = Color(0xFFFF3B30)

/**
 * Map of string resource IDs to distance values in meters.
 */
private val distanceOptions = linkedMapOf(
    R.string.settings_distance_40yd to 36.576,
    R.string.settings_distance_60m to 60.0,
    R.string.settings_distance_100m to 100.0,
    R.string.settings_distance_200m to 200.0
)

/**
 * Map of string resource IDs to start type identifiers.
 */
private val startTypeOptions = linkedMapOf(
    R.string.settings_start_type_flying to "flying",
    R.string.settings_start_type_standing to "standing"
)

/**
 * Map of string resource IDs to start mode identifiers (all 5 modes).
 */
private val startModeOptions = linkedMapOf(
    R.string.settings_start_mode_flying to "flying",
    R.string.settings_start_mode_touch to "touch",
    R.string.settings_start_mode_countdown to "countdown",
    R.string.settings_start_mode_voice to "voice",
    R.string.settings_start_mode_inframe to "inframe"
)

@Composable
fun SettingsScreen(
    onPaywallClick: () -> Unit = {},
    onShowOnboarding: () -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onDebugToolsClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val thumbnailStorageSize by viewModel.thumbnailStorageSize.collectAsStateWithLifecycle()

    SettingsScreenContent(
        state = state,
        thumbnailStorageSize = thumbnailStorageSize,
        onDistanceSelected = { viewModel.setDefaultDistance(it) },
        onStartTypeSelected = { viewModel.setStartType(it) },
        onSpeedUnitSelected = { viewModel.setSpeedUnit(it) },
        onThemeSelected = { viewModel.setAppTheme(it) },
        onSensitivityChanged = { viewModel.setDetectionSensitivity(it) },
        onFpsSelected = { viewModel.setPreferredFps(it) },
        onVoiceProviderSelected = { viewModel.setVoiceProvider(it) },
        onElevenLabsVoiceSelected = { viewModel.setElevenLabsVoice(it) },
        onAnnounceTimesChanged = { viewModel.setAnnounceTimesEnabled(it) },
        onPreviewVoice = { viewModel.previewVoice() },
        onStartModeSelected = { viewModel.setStartMode(it) },
        onPaywallClick = onPaywallClick,
        onClearData = { viewModel.clearAllData() },
        onShowOnboarding = {
            viewModel.resetOnboarding()
            onShowOnboarding()
        },
        onNotificationSettingsClick = onNotificationSettingsClick,
        onDebugToolsClick = onDebugToolsClick
    )
}

@Composable
private fun SettingsScreenContent(
    state: SettingsUiState,
    thumbnailStorageSize: String = "0 KB",
    onDistanceSelected: (Double) -> Unit,
    onStartTypeSelected: (String) -> Unit,
    onSpeedUnitSelected: (String) -> Unit,
    onThemeSelected: (AppTheme) -> Unit,
    onSensitivityChanged: (Float) -> Unit,
    onFpsSelected: (Int) -> Unit = {},
    onVoiceProviderSelected: (String) -> Unit = {},
    onElevenLabsVoiceSelected: (String) -> Unit = {},
    onAnnounceTimesChanged: (Boolean) -> Unit = {},
    onPreviewVoice: () -> Unit = {},
    onStartModeSelected: (String) -> Unit = {},
    onPaywallClick: () -> Unit = {},
    onClearData: () -> Unit = {},
    onShowOnboarding: () -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onDebugToolsClick: () -> Unit = {}
) {
    var distanceDropdownExpanded by remember { mutableStateOf(false) }
    var startTypeDropdownExpanded by remember { mutableStateOf(false) }
    var startModeDropdownExpanded by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── PREFERENCES SECTION ──
        SectionHeader(stringResource(R.string.settings_section_timing))

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Column {
                // Distance picker
                Box {
                    SettingsRow(
                        icon = Icons.Outlined.Straighten,
                        label = stringResource(R.string.settings_distance),
                        value = when (state.defaultDistance) {
                            36.576 -> stringResource(R.string.settings_distance_40yd)
                            60.0 -> stringResource(R.string.settings_distance_60m)
                            100.0 -> stringResource(R.string.settings_distance_100m)
                            200.0 -> stringResource(R.string.settings_distance_200m)
                            else -> "${state.defaultDistance.toInt()}m"
                        },
                        onClick = { distanceDropdownExpanded = true }
                    )
                    DropdownMenu(
                        expanded = distanceDropdownExpanded,
                        onDismissRequest = { distanceDropdownExpanded = false },
                        containerColor = CardBackground
                    ) {
                        distanceOptions.forEach { (labelRes, value) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes), color = TextPrimary) },
                                onClick = {
                                    onDistanceSelected(value)
                                    distanceDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                SettingsDivider()

                // Start type picker
                Box {
                    SettingsRow(
                        icon = Icons.Outlined.Timer,
                        label = stringResource(R.string.settings_start_type),
                        value = when (state.startType) {
                            "flying" -> stringResource(R.string.settings_start_type_flying)
                            "standing" -> stringResource(R.string.settings_start_type_standing)
                            else -> state.startType.replaceFirstChar { it.uppercase() }
                        },
                        onClick = { startTypeDropdownExpanded = true }
                    )
                    DropdownMenu(
                        expanded = startTypeDropdownExpanded,
                        onDismissRequest = { startTypeDropdownExpanded = false },
                        containerColor = CardBackground
                    ) {
                        startTypeOptions.forEach { (labelRes, value) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes), color = TextPrimary) },
                                onClick = {
                                    onStartTypeSelected(value)
                                    startTypeDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                SettingsDivider()

                // Start mode picker
                Box {
                    SettingsRow(
                        icon = Icons.Outlined.PlayArrow,
                        label = stringResource(R.string.settings_start_mode),
                        value = when (state.startMode) {
                            "flying" -> stringResource(R.string.settings_start_mode_flying)
                            "touch" -> stringResource(R.string.settings_start_mode_touch)
                            "countdown" -> stringResource(R.string.settings_start_mode_countdown)
                            "voice" -> stringResource(R.string.settings_start_mode_voice)
                            "inframe" -> stringResource(R.string.settings_start_mode_inframe)
                            else -> state.startMode.replaceFirstChar { it.uppercase() }
                        },
                        onClick = { startModeDropdownExpanded = true }
                    )
                    DropdownMenu(
                        expanded = startModeDropdownExpanded,
                        onDismissRequest = { startModeDropdownExpanded = false },
                        containerColor = CardBackground
                    ) {
                        startModeOptions.forEach { (labelRes, value) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes), color = TextPrimary) },
                                onClick = {
                                    onStartModeSelected(value)
                                    startModeDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                SettingsDivider()

                // Camera FPS
                SettingsChipRow(
                    icon = Icons.Outlined.Videocam,
                    label = stringResource(R.string.settings_frame_rate),
                    options = listOf(30, 60, 120),
                    selectedOption = state.preferredFps,
                    optionLabel = { stringResource(R.string.settings_fps_label, it) },
                    onSelected = onFpsSelected
                )

                SettingsDivider()

                // Speed unit
                SettingsChipRow(
                    icon = Icons.Outlined.Speed,
                    label = stringResource(R.string.settings_speed_unit),
                    options = listOf("m/s", "km/h", "mph"),
                    selectedOption = state.speedUnit,
                    optionLabel = { it },
                    onSelected = onSpeedUnitSelected
                )

                SettingsDivider()

                // Voice provider
                var voiceProviderExpanded by remember { mutableStateOf(false) }
                Box {
                    SettingsRow(
                        icon = Icons.Outlined.RecordVoiceOver,
                        label = "Start Voice",
                        value = if (state.voiceProvider == "eleven_labs") "AI Voice (Premium)" else "System Voice",
                        onClick = { voiceProviderExpanded = true }
                    )
                    DropdownMenu(
                        expanded = voiceProviderExpanded,
                        onDismissRequest = { voiceProviderExpanded = false },
                        containerColor = CardBackground
                    ) {
                        DropdownMenuItem(
                            text = { Text("AI Voice (Premium)", color = TextPrimary) },
                            onClick = {
                                onVoiceProviderSelected("eleven_labs")
                                voiceProviderExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("System Voice", color = TextPrimary) },
                            onClick = {
                                onVoiceProviderSelected("system")
                                voiceProviderExpanded = false
                            }
                        )
                    }
                }

                // ElevenLabs voice picker (only when AI Voice selected)
                if (state.voiceProvider == "eleven_labs") {
                    SettingsDivider()

                    var voicePickerExpanded by remember { mutableStateOf(false) }
                    val voiceOptions = listOf(
                        "adam" to "Adam (Male)",
                        "josh" to "Josh (Male)",
                        "arnold" to "Arnold (Male)",
                        "rachel" to "Rachel (Female)",
                        "bella" to "Bella (Female)",
                        "elli" to "Elli (Female)"
                    )
                    val currentVoiceLabel = voiceOptions.firstOrNull { it.first == state.elevenLabsVoice }?.second ?: "Arnold (Male)"

                    Box {
                        SettingsRow(
                            icon = null,
                            label = "AI Voice",
                            value = currentVoiceLabel,
                            onClick = { voicePickerExpanded = true }
                        )
                        DropdownMenu(
                            expanded = voicePickerExpanded,
                            onDismissRequest = { voicePickerExpanded = false },
                            containerColor = CardBackground
                        ) {
                            voiceOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = TextPrimary) },
                                    onClick = {
                                        onElevenLabsVoiceSelected(value)
                                        voicePickerExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                SettingsDivider()

                // Language
                var showLanguageDialog by remember { mutableStateOf(false) }
                val currentLanguageTag = getCurrentLanguageTag()

                SettingsRow(
                    icon = Icons.Outlined.Language,
                    label = stringResource(R.string.settings_language_title),
                    value = getLanguageDisplayName(currentLanguageTag),
                    onClick = { showLanguageDialog = true }
                )

                if (showLanguageDialog) {
                    LanguagePickerDialog(
                        currentLanguage = currentLanguageTag,
                        onLanguageSelected = { tag ->
                            applyLanguage(tag)
                        },
                        onDismiss = { showLanguageDialog = false }
                    )
                }

                SettingsDivider()

                // Preview voice button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPreviewVoice)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "Preview Voice",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AccentBlue
                    )
                }
            }
        }

        SectionFooter("Photo Finish uses camera-based detection for instant timing with no calibration needed.")

        // ── CROSSING FEEDBACK SECTION ──
        SectionHeader("Crossing Feedback")

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Column {
                // Announce times toggle
                SettingsToggleRow(
                    icon = Icons.Outlined.Mic,
                    label = "Announce Times",
                    checked = state.announceTimesEnabled,
                    onCheckedChange = onAnnounceTimesChanged
                )
            }
        }

        SectionFooter("Audio and visual feedback when a crossing is detected. Voice uses AI for instant time readout.")

        // ── NOTIFICATIONS SECTION ──
        SectionHeader(stringResource(R.string.settings_section_notifications))

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            SettingsRow(
                icon = Icons.Outlined.Notifications,
                label = stringResource(R.string.settings_notification_settings),
                showChevron = true,
                onClick = onNotificationSettingsClick
            )
        }

        SectionFooter(stringResource(R.string.settings_notification_subtitle))

        // ── DETECTION SECTION ──
        SectionHeader(stringResource(R.string.settings_section_detection))

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Column {
                // Sensitivity slider
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.settings_sensitivity),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = String.format("%.1f", state.detectionSensitivity),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
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
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── DATA SECTION ──
        SectionHeader(stringResource(R.string.settings_section_storage))

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Column {
                // Storage used
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_thumbnail_storage),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = thumbnailStorageSize,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                SettingsDivider()

                // Clear data button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showClearDataDialog = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = DestructiveRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.settings_clear_all_data),
                        style = MaterialTheme.typography.bodyLarge,
                        color = DestructiveRed
                    )
                }
            }
        }

        SectionFooter(stringResource(R.string.settings_clear_data_description))

        // ── ABOUT SECTION ──
        SectionHeader(stringResource(R.string.settings_section_about))

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_version),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.settings_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── APPEARANCE SECTION ──
        SectionHeader(stringResource(R.string.settings_section_display))

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    AppTheme.MIDNIGHT to stringResource(R.string.theme_midnight),
                    AppTheme.LIGHT to stringResource(R.string.theme_light),
                    AppTheme.DARKGOLD to stringResource(R.string.theme_gold)
                ).forEach { (theme, label) ->
                    val isSelected = state.appTheme == theme
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (isSelected) {
                                    Modifier.surfaceCard()
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onThemeSelected(theme) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.DarkMode,
                                contentDescription = null,
                                tint = if (isSelected) AccentBlue else TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (isSelected) TextPrimary else TextSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── DEVELOPER SECTION (debug only) ──
        if (BuildConfig.DEBUG) {
            SectionHeader(stringResource(R.string.settings_section_developer))

            Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
                Column {
                    SettingsRow(
                        icon = Icons.Outlined.Refresh,
                        label = stringResource(R.string.settings_show_onboarding),
                        showChevron = true,
                        onClick = onShowOnboarding
                    )

                    SettingsDivider()

                    SettingsRow(
                        icon = Icons.Outlined.Build,
                        label = stringResource(R.string.settings_debug_tools),
                        showChevron = true,
                        onClick = onDebugToolsClick
                    )
                }
            }

            SectionFooter("Debug options for testing. Only visible in development builds.")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // Clear data confirmation dialog
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = {
                Text(
                    stringResource(R.string.settings_clear_data_dialog_title),
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    stringResource(R.string.settings_clear_data_dialog_message),
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearData()
                        showClearDataDialog = false
                    }
                ) {
                    Text(stringResource(R.string.settings_delete_all), color = DestructiveRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = AccentBlue)
                }
            },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }
}

// ── Reusable row components ──

@Composable
private fun SettingsRow(
    icon: ImageVector?,
    label: String,
    value: String? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        if (showChevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector?,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = TextTertiary,
                uncheckedBorderColor = TextTertiary
            )
        )
    }
}

@Composable
private fun <T> SettingsChipRow(
    icon: ImageVector?,
    label: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = if (icon != null) Modifier.padding(start = 36.dp) else Modifier
        ) {
            options.forEach { option ->
                val isSelected = selectedOption == option
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelected(option) },
                    label = {
                        Text(
                            optionLabel(option),
                            color = if (isSelected) Color.White else TextSecondary
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
                        selected = isSelected
                    )
                )
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = BorderSubtle
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp, end = 16.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SettingsScreenPreview() {
    TrackSpeedTheme() {
        SettingsScreenContent(
            state = SettingsUiState(),
            onDistanceSelected = {},
            onStartTypeSelected = {},
            onSpeedUnitSelected = {},
            onThemeSelected = {},
            onSensitivityChanged = {},
            onFpsSelected = {},
            onPaywallClick = {},
            onNotificationSettingsClick = {},
            onDebugToolsClick = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScreenDarkPreview() {
    TrackSpeedTheme() {
        SettingsScreenContent(
            state = SettingsUiState(isProUser = true),
            onDistanceSelected = {},
            onStartTypeSelected = {},
            onSpeedUnitSelected = {},
            onThemeSelected = {},
            onSensitivityChanged = {},
            onFpsSelected = {},
            onPaywallClick = {},
            onNotificationSettingsClick = {},
            onDebugToolsClick = {}
        )
    }
}
