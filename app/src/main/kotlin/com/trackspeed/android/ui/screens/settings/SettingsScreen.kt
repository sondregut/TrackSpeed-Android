package com.trackspeed.android.ui.screens.settings

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Subscription section
        SectionHeader(stringResource(R.string.settings_section_subscription))

        if (state.isProUser) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_trackspeed_pro), color = TextPrimary) },
                supportingContent = { Text(stringResource(R.string.settings_active), color = AccentGreen) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = AccentGreen
                    )
                },
                trailingContent = {
                    Text(
                        text = stringResource(R.string.settings_manage),
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
                headlineContent = { Text(stringResource(R.string.settings_upgrade_to_pro), color = TextPrimary) },
                supportingContent = { Text(stringResource(R.string.settings_unlock_all_features), color = TextSecondary) },
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
        SectionHeader(stringResource(R.string.settings_section_timing))

        // Distance
        Box {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_distance), color = TextPrimary) },
                supportingContent = {
                    val distanceDisplayLabel = when (state.defaultDistance) {
                        36.576 -> stringResource(R.string.settings_distance_40yd)
                        60.0 -> stringResource(R.string.settings_distance_60m)
                        100.0 -> stringResource(R.string.settings_distance_100m)
                        200.0 -> stringResource(R.string.settings_distance_200m)
                        else -> "${state.defaultDistance.toInt()}m"
                    }
                    Text(distanceDisplayLabel, color = TextSecondary)
                },
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

        // Start type
        Box {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_start_type), color = TextPrimary) },
                supportingContent = {
                    val startTypeDisplayLabel = when (state.startType) {
                        "flying" -> stringResource(R.string.settings_start_type_flying)
                        "standing" -> stringResource(R.string.settings_start_type_standing)
                        else -> state.startType.replaceFirstChar { it.uppercase() }
                    }
                    Text(startTypeDisplayLabel, color = TextSecondary)
                },
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

        // Start mode
        Box {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_start_mode), color = TextPrimary) },
                supportingContent = {
                    val startModeDisplayLabel = when (state.startMode) {
                        "flying" -> stringResource(R.string.settings_start_mode_flying)
                        "touch" -> stringResource(R.string.settings_start_mode_touch)
                        "countdown" -> stringResource(R.string.settings_start_mode_countdown)
                        "voice" -> stringResource(R.string.settings_start_mode_voice)
                        "inframe" -> stringResource(R.string.settings_start_mode_inframe)
                        else -> state.startMode.replaceFirstChar { it.uppercase() }
                    }
                    Text(startModeDisplayLabel, color = TextSecondary)
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                modifier = Modifier.clickable { startModeDropdownExpanded = true },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
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

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = DividerColor
        )

        // Display section
        SectionHeader(stringResource(R.string.settings_section_display))

        // Speed unit
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_speed_unit), color = TextPrimary) },
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

        // Theme picker
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_theme), color = TextPrimary) },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.DarkMode,
                    contentDescription = null,
                    tint = TextSecondary
                )
            },
            supportingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    listOf(
                        AppTheme.MIDNIGHT to stringResource(R.string.theme_midnight),
                        AppTheme.LIGHT to stringResource(R.string.theme_light),
                        AppTheme.DARKGOLD to stringResource(R.string.theme_gold)
                    ).forEach { (theme, label) ->
                        FilterChip(
                            selected = state.appTheme == theme,
                            onClick = { onThemeSelected(theme) },
                            label = {
                                Text(
                                    label,
                                    color = if (state.appTheme == theme) Color.White else TextSecondary,
                                    style = MaterialTheme.typography.labelMedium
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
                                selected = state.appTheme == theme
                            )
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )

        // Language picker
        var showLanguageDialog by remember { mutableStateOf(false) }
        val currentLanguageTag = getCurrentLanguageTag()

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_language_title), color = TextPrimary) },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = null,
                    tint = TextSecondary
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = getLanguageDisplayName(currentLanguageTag),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            ),
            modifier = Modifier.clickable { showLanguageDialog = true }
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

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = DividerColor
        )

        // Voice section
        SectionHeader("VOICE")

        // Voice provider picker
        var voiceProviderExpanded by remember { mutableStateOf(false) }
        Box {
            ListItem(
                headlineContent = { Text("Voice Provider", color = TextPrimary) },
                supportingContent = {
                    val label = if (state.voiceProvider == "eleven_labs") "AI Voice (Premium)" else "System Voice"
                    Text(label, color = TextSecondary)
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.RecordVoiceOver,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                modifier = Modifier.clickable { voiceProviderExpanded = true },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
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

        // ElevenLabs voice picker (only shown when AI Voice selected)
        if (state.voiceProvider == "eleven_labs") {
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
                ListItem(
                    headlineContent = { Text("AI Voice", color = TextPrimary) },
                    supportingContent = { Text(currentVoiceLabel, color = TextSecondary) },
                    modifier = Modifier.clickable { voicePickerExpanded = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
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

            // Preview button
            ListItem(
                headlineContent = {
                    Text(
                        "Preview Voice",
                        color = AccentBlue,
                        modifier = Modifier.clickable { onPreviewVoice() }
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = AccentBlue
                    )
                },
                modifier = Modifier.clickable { onPreviewVoice() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }

        // Announce times toggle
        ListItem(
            headlineContent = { Text("Announce Times", color = TextPrimary) },
            supportingContent = { Text("Read lap times aloud after each crossing", color = TextSecondary) },
            trailingContent = {
                Switch(
                    checked = state.announceTimesEnabled,
                    onCheckedChange = { onAnnounceTimesChanged(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentBlue,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = TextTertiary,
                        uncheckedBorderColor = TextTertiary
                    )
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = DividerColor
        )

        // Detection section
        SectionHeader(stringResource(R.string.settings_section_detection))

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_sensitivity), color = TextPrimary) },
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
            headlineContent = { Text(stringResource(R.string.settings_frame_rate), color = TextPrimary) },
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
                                    stringResource(R.string.settings_fps_label, fps),
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

        // Notifications section
        SectionHeader(stringResource(R.string.settings_section_notifications))

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_notification_settings), color = TextPrimary) },
            supportingContent = { Text(stringResource(R.string.settings_notification_subtitle), color = TextSecondary) },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = TextSecondary
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = TextTertiary
                )
            },
            modifier = Modifier.clickable { onNotificationSettingsClick() },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = DividerColor
        )

        // Storage section
        SectionHeader(stringResource(R.string.settings_section_storage))

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_thumbnail_storage), color = TextPrimary) },
            supportingContent = {
                Text(
                    text = stringResource(R.string.settings_storage_used, thumbnailStorageSize),
                    color = TextSecondary
                )
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Storage,
                    contentDescription = null,
                    tint = TextSecondary
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

        // About section
        SectionHeader(stringResource(R.string.settings_section_about))

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_version), color = TextPrimary) },
            supportingContent = {
                Text(
                    text = stringResource(R.string.settings_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    color = TextSecondary
                )
            },
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

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = DividerColor
        )

        // Developer section (only in debug builds)
        if (BuildConfig.DEBUG) {
            SectionHeader(stringResource(R.string.settings_section_developer))

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_show_onboarding), color = TextPrimary) },
                supportingContent = { Text(stringResource(R.string.settings_show_onboarding_subtitle), color = TextSecondary) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary
                    )
                },
                modifier = Modifier.clickable { onShowOnboarding() },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_debug_tools), color = TextPrimary) },
                supportingContent = { Text(stringResource(R.string.settings_debug_tools_subtitle), color = TextSecondary) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Build,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary
                    )
                },
                modifier = Modifier.clickable { onDebugToolsClick() },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = DividerColor
            )
        }

        // Data Management section
        SectionHeader(stringResource(R.string.settings_section_data))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .gunmetalCard()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_clear_data),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = DestructiveRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_clear_data_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showClearDataDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = DestructiveRed
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).let {
                        androidx.compose.foundation.BorderStroke(1.dp, DestructiveRed.copy(alpha = 0.5f))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_clear_all_data))
                }
            }
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
