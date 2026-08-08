package com.trackspeed.android.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Star
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.R
import com.trackspeed.android.diagnostics.LogExporter
import com.trackspeed.android.model.StartType
import com.trackspeed.android.ui.theme.*
import kotlinx.coroutines.launch

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
 * Map of string resource IDs to start type identifiers (matching iOS rawValues).
 */
private val startTypeOptions = linkedMapOf(
    R.string.settings_start_mode_flying to "flying",
    R.string.settings_start_mode_touch to "touchRelease",
    R.string.settings_start_mode_countdown to "countdown",
    R.string.settings_start_mode_voice to "voiceCommand",
    R.string.settings_start_mode_inframe to "inFrame"
)

@Composable
fun SettingsScreen(
    onPaywallClick: () -> Unit = {},
    onManageSubscriptionClick: () -> Unit = {},
    onRedeemPromoClick: () -> Unit = {},
    onShowOnboarding: () -> Unit = {},
    onShowFirstSessionTutorial: () -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onDebugToolsClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val thumbnailStorageSize by viewModel.thumbnailStorageSize.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var detectionLogBusy by remember { mutableStateOf(false) }
    var deviceLogBusy by remember { mutableStateOf(false) }

    fun showDetectionLogError(error: Throwable) {
        Toast.makeText(
            context,
            error.message ?: "Detection log action failed",
            Toast.LENGTH_LONG
        ).show()
    }

    fun showDeviceLogError(error: Throwable) {
        Toast.makeText(
            context,
            error.message ?: "Log export failed",
            Toast.LENGTH_LONG
        ).show()
    }

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
        onAppLanguageSelected = { viewModel.setAppLanguage(it) },
        onAnnounceTimesChanged = { viewModel.setAnnounceTimesEnabled(it) },
        onPreStartDelayChanged = { viewModel.setPreStartDelayMin(it) },
        onMarksSetDelayChanged = { viewModel.setMarksSetDelayMin(it) },
        onSetGoHoldChanged = { viewModel.setSetGoHoldMin(it) },
        onIncludeReadyCommandChanged = { viewModel.setIncludeReadyCommand(it) },
        onSaveCrossingFramesChanged = { viewModel.setSaveCrossingFrames(it) },
        onEnableFrameScrubbingChanged = { viewModel.setEnableFrameScrubbing(it) },
        onDetectionDiagnosticsChanged = { viewModel.setDetectionDiagnosticsEnabled(it) },
        onDetectionReviewAutoUploadChanged = { viewModel.setDetectionReviewAutoUploadEnabled(it) },
        onCameraPerformanceDiagnosticsChanged = { viewModel.setCameraPerformanceDiagnosticsEnabled(it) },
        detectionLogBusy = detectionLogBusy,
        onDetectionLogExport = {
            if (!detectionLogBusy) {
                coroutineScope.launch {
                    detectionLogBusy = true
                    runCatching { viewModel.exportDetectionReviewLog() }
                        .onSuccess { shareDetectionLogFile(context, it) }
                        .onFailure { showDetectionLogError(it) }
                    detectionLogBusy = false
                }
            }
        },
        onDetectionLogUpload = {
            if (!detectionLogBusy) {
                coroutineScope.launch {
                    detectionLogBusy = true
                    runCatching { viewModel.uploadDetectionReviewLog() }
                        .onSuccess { shareDetectionLogUrl(context, it) }
                        .onFailure { showDetectionLogError(it) }
                    detectionLogBusy = false
                }
            }
        },
        onDetectionLogsClear = {
            if (!detectionLogBusy) {
                coroutineScope.launch {
                    detectionLogBusy = true
                    runCatching { viewModel.clearDetectionReviewLogs() }
                        .onSuccess {
                            Toast.makeText(context, "Detection logs cleared", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { showDetectionLogError(it) }
                    detectionLogBusy = false
                }
            }
        },
        deviceLogBusy = deviceLogBusy,
        onDeviceLogExport = { window ->
            if (!deviceLogBusy) {
                coroutineScope.launch {
                    deviceLogBusy = true
                    runCatching { viewModel.exportRecentLogs(window) }
                        .onSuccess { shareLogExportUrl(context, it) }
                        .onFailure { showDeviceLogError(it) }
                    deviceLogBusy = false
                }
            }
        },
        onPreviewVoice = { viewModel.previewVoice() },
        onPaywallClick = onPaywallClick,
        onManageSubscriptionClick = onManageSubscriptionClick,
        onRedeemPromoClick = onRedeemPromoClick,
        onClearData = { viewModel.clearAllData() },
        onShowOnboarding = {
            viewModel.resetOnboarding()
            onShowOnboarding()
        },
        onShowFirstSessionTutorial = {
            coroutineScope.launch {
                viewModel.replayFirstSessionTutorial()
                onShowFirstSessionTutorial()
            }
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
    onAppLanguageSelected: (String) -> Unit = {},
    onAnnounceTimesChanged: (Boolean) -> Unit = {},
    onPreStartDelayChanged: (Float) -> Unit = {},
    onMarksSetDelayChanged: (Float) -> Unit = {},
    onSetGoHoldChanged: (Float) -> Unit = {},
    onIncludeReadyCommandChanged: (Boolean) -> Unit = {},
    onSaveCrossingFramesChanged: (Boolean) -> Unit = {},
    onEnableFrameScrubbingChanged: (Boolean) -> Unit = {},
    onDetectionDiagnosticsChanged: (Boolean) -> Unit = {},
    onDetectionReviewAutoUploadChanged: (Boolean) -> Unit = {},
    onCameraPerformanceDiagnosticsChanged: (Boolean) -> Unit = {},
    detectionLogBusy: Boolean = false,
    onDetectionLogExport: () -> Unit = {},
    onDetectionLogUpload: () -> Unit = {},
    onDetectionLogsClear: () -> Unit = {},
    deviceLogBusy: Boolean = false,
    onDeviceLogExport: (LogExporter.TimeWindow) -> Unit = {},
    onPreviewVoice: () -> Unit = {},
    onPaywallClick: () -> Unit = {},
    onManageSubscriptionClick: () -> Unit = {},
    onRedeemPromoClick: () -> Unit = {},
    onClearData: () -> Unit = {},
    onShowOnboarding: () -> Unit = {},
    onShowFirstSessionTutorial: () -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onDebugToolsClick: () -> Unit = {}
) {
    var distanceDropdownExpanded by remember { mutableStateOf(false) }
    var startTypeDropdownExpanded by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showClearDetectionLogDialog by remember { mutableStateOf(false) }
    var showDeviceLogMenu by remember { mutableStateOf(false) }

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

        SectionHeader(stringResource(R.string.settings_trackspeed_pro))

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Column {
                SettingsRow(
                    icon = Icons.Outlined.Star,
                    label = if (state.isProUser) {
                        stringResource(R.string.settings_manage_subscription)
                    } else {
                        stringResource(R.string.settings_upgrade_to_pro)
                    },
                    value = if (state.isProUser) {
                        stringResource(R.string.settings_pro_active)
                    } else {
                        null
                    },
                    showChevron = true,
                    onClick = if (state.isProUser) onManageSubscriptionClick else onPaywallClick
                )

                SettingsDivider()

                SettingsRow(
                    icon = Icons.Outlined.LocalOffer,
                    label = stringResource(R.string.settings_redeem_promo_code),
                    showChevron = true,
                    onClick = onRedeemPromoClick
                )
            }
        }

        SectionFooter(stringResource(R.string.settings_pro_access_footer))

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
                        value = state.startTypeLabel,
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

                // Camera FPS
                SettingsChipRow(
                    icon = Icons.Outlined.Videocam,
                    label = stringResource(R.string.settings_frame_rate),
                    options = listOf(30),
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
                        label = stringResource(R.string.settings_start_voice),
                        value = if (state.voiceProvider == "eleven_labs") "AI Voice (Premium)" else "System Voice",
                        onClick = { voiceProviderExpanded = true }
                    )
                    DropdownMenu(
                        expanded = voiceProviderExpanded,
                        onDismissRequest = { voiceProviderExpanded = false },
                        containerColor = CardBackground
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.settings_ai_voice_premium),
                                    color = TextPrimary
                                )
                            },
                            onClick = {
                                onVoiceProviderSelected("eleven_labs")
                                voiceProviderExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.settings_system_voice),
                                    color = TextPrimary
                                )
                            },
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
                            label = stringResource(R.string.settings_ai_voice),
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

                VoiceTimingSliderRow(
                    label = stringResource(R.string.settings_pre_start_delay),
                    value = state.preStartDelayMin,
                    valueText = "${state.preStartDelayMin.toInt()}-${(state.preStartDelayMin + 2f).toInt()}s",
                    helper = stringResource(R.string.settings_pre_start_delay_helper),
                    valueRange = 1f..8f,
                    steps = 6,
                    onValueChange = onPreStartDelayChanged
                )

                SettingsDivider()

                VoiceTimingSliderRow(
                    label = stringResource(R.string.settings_marks_delay),
                    value = state.marksSetDelayMin,
                    valueText = "${state.marksSetDelayMin.toInt()}-${(state.marksSetDelayMin + 4f).toInt()}s",
                    helper = stringResource(R.string.settings_marks_delay_helper),
                    valueRange = 3f..15f,
                    steps = 11,
                    onValueChange = onMarksSetDelayChanged
                )

                SettingsDivider()

                VoiceTimingSliderRow(
                    label = stringResource(R.string.settings_set_hold_time),
                    value = state.setGoHoldMin,
                    valueText = String.format(java.util.Locale.getDefault(), "%.1f-%.1fs", state.setGoHoldMin, state.setGoHoldMin + 0.8f),
                    helper = stringResource(R.string.settings_set_hold_time_helper),
                    valueRange = 1f..3f,
                    steps = 19,
                    onValueChange = onSetGoHoldChanged
                )

                SettingsDivider()

                SettingsToggleRow(
                    icon = Icons.Outlined.Mic,
                    label = stringResource(R.string.settings_include_ready_command),
                    checked = state.includeReadyCommand,
                    onCheckedChange = onIncludeReadyCommandChanged
                )

                SettingsDivider()

                // Language
                var showLanguageDialog by remember { mutableStateOf(false) }

                SettingsRow(
                    icon = Icons.Outlined.Language,
                    label = stringResource(R.string.settings_language_title),
                    value = getLanguageDisplayName(state.appLanguage),
                    onClick = { showLanguageDialog = true }
                )

                if (showLanguageDialog) {
                    LanguagePickerDialog(
                        currentLanguage = state.appLanguage,
                        onLanguageSelected = { tag ->
                            onAppLanguageSelected(tag)
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
                        text = stringResource(R.string.settings_preview_voice),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AccentBlue
                    )
                }
            }
        }

        SectionFooter(stringResource(R.string.settings_photo_finish_footer))

        // ── CROSSING FEEDBACK SECTION ──
        SectionHeader(stringResource(R.string.settings_crossing_feedback))

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Column {
                // Announce times toggle
                SettingsToggleRow(
                    icon = Icons.Outlined.Mic,
                    label = stringResource(R.string.settings_announce_times),
                    checked = state.announceTimesEnabled,
                    onCheckedChange = onAnnounceTimesChanged
                )
            }
        }

        SectionFooter(stringResource(R.string.settings_crossing_feedback_footer))

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
                            text = String.format(java.util.Locale.getDefault(), "%.1f", state.detectionSensitivity),
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

                SettingsDivider()

                SettingsToggleRow(
                    icon = Icons.Outlined.Videocam,
                    label = stringResource(R.string.settings_frame_scrubber),
                    checked = state.enableFrameScrubbing,
                    onCheckedChange = onEnableFrameScrubbingChanged
                )

                SettingsDivider()

                SettingsToggleRow(
                    icon = Icons.Outlined.Storage,
                    label = stringResource(R.string.settings_save_crossing_frames),
                    checked = state.saveCrossingFrames,
                    onCheckedChange = onSaveCrossingFramesChanged
                )
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
                        icon = Icons.Outlined.PlayArrow,
                        label = stringResource(R.string.settings_show_first_session_tutorial),
                        showChevron = true,
                        onClick = onShowFirstSessionTutorial
                    )

                    SettingsDivider()

                    SettingsRow(
                        icon = Icons.Outlined.Build,
                        label = stringResource(R.string.settings_debug_tools),
                        showChevron = true,
                        onClick = onDebugToolsClick
                    )

                    SettingsDivider()

                    Box {
                        SettingsRow(
                            icon = Icons.Outlined.Storage,
                            label = stringResource(R.string.settings_export_recent_logs),
                            value = if (deviceLogBusy) "Exporting..." else null,
                            showChevron = !deviceLogBusy,
                            onClick = if (deviceLogBusy) null else { { showDeviceLogMenu = true } }
                        )
                        DropdownMenu(
                            expanded = showDeviceLogMenu,
                            onDismissRequest = { showDeviceLogMenu = false },
                            containerColor = SurfaceDark
                        ) {
                            LogExporter.TimeWindow.entries.forEach { window ->
                                DropdownMenuItem(
                                    text = { Text(window.displayName, color = TextPrimary) },
                                    onClick = {
                                        showDeviceLogMenu = false
                                        onDeviceLogExport(window)
                                    }
                                )
                            }
                        }
                    }

                    SettingsDivider()

                    SettingsToggleRow(
                        icon = Icons.Outlined.Info,
                        label = stringResource(R.string.settings_detection_review_logging),
                        checked = state.detectionDiagnosticsEnabled,
                        onCheckedChange = onDetectionDiagnosticsChanged
                    )

                    SettingsDivider()

                    SettingsToggleRow(
                        icon = Icons.Outlined.Storage,
                        label = stringResource(R.string.settings_auto_upload_review_logs),
                        checked = state.detectionReviewAutoUploadEnabled,
                        onCheckedChange = onDetectionReviewAutoUploadChanged
                    )

                    SettingsDivider()

                    SettingsToggleRow(
                        icon = Icons.Outlined.Videocam,
                        label = stringResource(R.string.settings_camera_timing_summaries),
                        checked = state.cameraPerformanceDiagnosticsEnabled,
                        onCheckedChange = onCameraPerformanceDiagnosticsChanged
                    )

                    SettingsDivider()

                    SettingsRow(
                        icon = Icons.Outlined.Storage,
                        label = stringResource(R.string.settings_save_detection_log),
                        value = if (detectionLogBusy) "Working..." else null,
                        showChevron = !detectionLogBusy,
                        onClick = if (detectionLogBusy) null else onDetectionLogExport
                    )

                    SettingsDivider()

                    SettingsRow(
                        icon = Icons.Outlined.Storage,
                        label = stringResource(R.string.settings_upload_detection_log),
                        value = if (detectionLogBusy) "Working..." else null,
                        showChevron = !detectionLogBusy,
                        onClick = if (detectionLogBusy) null else onDetectionLogUpload
                    )

                    SettingsDivider()

                    SettingsRow(
                        icon = Icons.Outlined.Delete,
                        label = stringResource(R.string.settings_clear_detection_logs),
                        showChevron = true,
                        onClick = {
                            if (!detectionLogBusy) {
                                showClearDetectionLogDialog = true
                            }
                        }
                    )
                }
            }

            SectionFooter(stringResource(R.string.settings_detection_logging_footer))
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

    if (showClearDetectionLogDialog) {
        AlertDialog(
            onDismissRequest = { showClearDetectionLogDialog = false },
            title = {
                Text(
                    "Clear detection logs?",
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    "This removes local detection review log files from this phone.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDetectionLogsClear()
                        showClearDetectionLogDialog = false
                    }
                ) {
                    Text(stringResource(R.string.settings_clear_logs), color = DestructiveRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDetectionLogDialog = false }) {
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
private fun VoiceTimingSliderRow(
    label: String,
    value: Float,
    valueText: String,
    helper: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary
                )
                Text(
                    text = helper,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary.copy(alpha = 0.75f)
                )
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
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

private fun shareDetectionLogFile(context: Context, uri: Uri) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Save Detection Log"))
}

private fun shareDetectionLogUrl(context: Context, signedUrl: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, signedUrl)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Detection Log URL"))
}

private fun shareLogExportUrl(context: Context, signedUrl: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, signedUrl)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Recent Logs URL"))
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
