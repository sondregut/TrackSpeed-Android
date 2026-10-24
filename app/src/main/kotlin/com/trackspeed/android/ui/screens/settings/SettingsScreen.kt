package com.trackspeed.android.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.R
import com.trackspeed.android.billing.EarlyReleaseAccess
import com.trackspeed.android.diagnostics.LogExporter
import com.trackspeed.android.model.StartType
import com.trackspeed.android.ui.theme.*
import com.trackspeed.android.ui.util.displayNameResource
import com.trackspeed.android.ui.util.localizedDescription
import com.trackspeed.android.ui.util.localizedDisplayName
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

@Composable
fun SettingsScreen(
    onPaywallClick: () -> Unit = {},
    onManageSubscriptionClick: () -> Unit = {},
    onRedeemPromoClick: () -> Unit = {},
    onShowOnboarding: () -> Unit = {},
    onShowFirstSessionTutorial: () -> Unit = {},
    onNotificationSettingsClick: () -> Unit = {},
    onDebugToolsClick: () -> Unit = {},
    onWebDashboardClick: () -> Unit = {},
    onHelpFeedbackClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val thumbnailStorageSize by viewModel.thumbnailStorageSize.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val detectionLogFailedMessage = stringResource(R.string.settings_detection_log_failed)
    val logExportFailedMessage = stringResource(R.string.settings_log_export_failed)
    val detectionLogsClearedMessage = stringResource(R.string.settings_detection_logs_cleared)
    val coroutineScope = rememberCoroutineScope()
    var detectionLogBusy by remember { mutableStateOf(false) }
    var deviceLogBusy by remember { mutableStateOf(false) }

    fun showDetectionLogError(error: Throwable) {
        Toast.makeText(
            context,
            detectionLogFailedMessage,
            Toast.LENGTH_LONG
        ).show()
    }

    fun showDeviceLogError(error: Throwable) {
        Toast.makeText(
            context,
            logExportFailedMessage,
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
                            Toast.makeText(context, detectionLogsClearedMessage, Toast.LENGTH_SHORT).show()
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
        onDebugToolsClick = onDebugToolsClick,
        onWebDashboardClick = onWebDashboardClick,
        onHelpFeedbackClick = onHelpFeedbackClick
    )
}

@Composable
internal fun SettingsScreenContent(
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
    onDebugToolsClick: () -> Unit = {},
    onWebDashboardClick: () -> Unit = {},
    onHelpFeedbackClick: () -> Unit = {}
) {
    val uriHandler = LocalUriHandler.current
    var distanceDropdownExpanded by remember { mutableStateOf(false) }
    var startTypeDropdownExpanded by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showClearDetectionLogDialog by remember { mutableStateOf(false) }
    var showDeviceLogMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
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
        SectionHeader(stringResource(R.string.parity_settings_preferences))

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
                        value = stringResource(StartType.fromRawValue(state.startType).displayNameResource()),
                        onClick = { startTypeDropdownExpanded = true }
                    )
                    DropdownMenu(
                        expanded = startTypeDropdownExpanded,
                        onDismissRequest = { startTypeDropdownExpanded = false },
                        containerColor = CardBackground
                    ) {
                        StartType.localStartTypes.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(type.localizedDisplayName(), color = TextPrimary)
                                            if (type.isPro && !state.isProUser) {
                                                Text(
                                                    text = stringResource(R.string.common_pro_badge),
                                                    color = AccentBlue,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Text(
                                            type.localizedDescription(),
                                            color = TextSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                },
                                onClick = {
                                    startTypeDropdownExpanded = false
                                    if (type.isPro && !state.isProUser) {
                                        onPaywallClick()
                                    } else {
                                        onStartTypeSelected(type.rawValue)
                                    }
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
                        value = if (state.voiceProvider == "eleven_labs") {
                            stringResource(R.string.settings_ai_voice_premium)
                        } else {
                            stringResource(R.string.settings_system_voice)
                        },
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
                    val maleLabel = stringResource(R.string.settings_voice_gender_male)
                    val femaleLabel = stringResource(R.string.settings_voice_gender_female)
                    val voiceOptions = listOf(
                        "adam" to stringResource(R.string.settings_voice_name_gender, "Adam", maleLabel),
                        "josh" to stringResource(R.string.settings_voice_name_gender, "Josh", maleLabel),
                        "arnold" to stringResource(R.string.settings_voice_name_gender, "Arnold", maleLabel),
                        "rachel" to stringResource(R.string.settings_voice_name_gender, "Rachel", femaleLabel),
                        "bella" to stringResource(R.string.settings_voice_name_gender, "Bella", femaleLabel),
                        "elli" to stringResource(R.string.settings_voice_name_gender, "Elli", femaleLabel)
                    )
                    val currentVoiceLabel = voiceOptions.firstOrNull { it.first == state.elevenLabsVoice }?.second
                        ?: stringResource(R.string.settings_voice_name_gender, "Arnold", maleLabel)

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
                    ParitySettingsIconBadge(Icons.Outlined.PlayArrow)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_preview_voice),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AccentBlue
                    )
                }
            }
        }

        SectionFooter(stringResource(R.string.settings_photo_finish_footer))

        // ── APPEARANCE SECTION ──
        SectionHeader(stringResource(R.string.parity_settings_appearance))

        SettingsAppearanceChoices(state.appTheme, onThemeSelected)

        SectionHeader(stringResource(R.string.parity_settings_voice_timing))
        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Column {
                VoiceTimingSliderRow(
                    label = stringResource(R.string.settings_pre_start_delay),
                    value = state.preStartDelayMin,
                    valueText = stringResource(
                        R.string.settings_seconds_range_int,
                        state.preStartDelayMin.toInt(),
                        (state.preStartDelayMin + 2f).toInt()
                    ),
                    helper = stringResource(R.string.settings_pre_start_delay_helper),
                    valueRange = 1f..8f,
                    steps = 6,
                    onValueChange = onPreStartDelayChanged
                )

                SettingsDivider()

                VoiceTimingSliderRow(
                    label = stringResource(R.string.settings_marks_delay),
                    value = state.marksSetDelayMin,
                    valueText = stringResource(
                        R.string.settings_seconds_range_int,
                        state.marksSetDelayMin.toInt(),
                        (state.marksSetDelayMin + 4f).toInt()
                    ),
                    helper = stringResource(R.string.settings_marks_delay_helper),
                    valueRange = 3f..15f,
                    steps = 11,
                    onValueChange = onMarksSetDelayChanged
                )

                SettingsDivider()

                VoiceTimingSliderRow(
                    label = stringResource(R.string.settings_set_hold_time),
                    value = state.setGoHoldMin,
                    valueText = stringResource(
                        R.string.settings_seconds_range_decimal,
                        state.setGoHoldMin,
                        state.setGoHoldMin + 0.8f
                    ),
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
            }
        }
        SectionFooter(stringResource(R.string.parity_settings_random_starter))

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
        SectionHeader(stringResource(R.string.parity_settings_data))

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

        SectionHeader(stringResource(R.string.dashboard_title))
        Box(Modifier.fillMaxWidth().surfaceCard()) {
            SettingsDashboardEntry(state.dashboardAvailable, onWebDashboardClick)
        }
        if (!state.dashboardAvailable) SectionFooter(stringResource(R.string.dashboard_sign_in_required))

        SectionHeader(stringResource(R.string.parity_settings_subscription))

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Column {
                SettingsRow(
                    icon = Icons.Outlined.Star,
                    label = if (EarlyReleaseAccess.isEnabled) {
                        stringResource(R.string.early_release_access_active)
                    } else if (state.isProUser) {
                        stringResource(R.string.settings_manage_subscription)
                    } else {
                        stringResource(R.string.settings_upgrade_to_pro)
                    },
                    value = if (EarlyReleaseAccess.isEnabled) {
                        stringResource(R.string.early_release_all_features)
                    } else if (state.isProUser) {
                        stringResource(R.string.settings_pro_active)
                    } else {
                        null
                    },
                    showChevron = !EarlyReleaseAccess.isEnabled,
                    onClick = when {
                        EarlyReleaseAccess.isEnabled -> ({})
                        state.isProUser -> onManageSubscriptionClick
                        else -> onPaywallClick
                    }
                )

                if (EarlyReleaseAccess.isEnabled) {
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Star,
                        label = stringResource(R.string.settings_manage_subscription),
                        showChevron = true,
                        onClick = onManageSubscriptionClick
                    )
                }

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

        // ── ABOUT SECTION ──
        SectionHeader(stringResource(R.string.settings_section_about))

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            SettingsRow(icon = Icons.Outlined.Info,
                label = stringResource(R.string.help_title), showChevron = true,
                onClick = onHelpFeedbackClick)
        }

        Box(modifier = Modifier.fillMaxWidth().surfaceCard()) {
            Column {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.settings_version),
                    value = stringResource(R.string.settings_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.PrivacyTip,
                    label = stringResource(R.string.parity_settings_privacy),
                    showChevron = true,
                    onClick = { uriHandler.openUri("https://mytrackspeed.com/privacy") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.Description,
                    label = stringResource(R.string.parity_settings_terms),
                    showChevron = true,
                    onClick = { uriHandler.openUri("https://mytrackspeed.com/terms") }
                )
            }
        }

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
                            value = if (deviceLogBusy) stringResource(R.string.settings_exporting) else null,
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
                                    text = { Text(window.localizedDisplayName(), color = TextPrimary) },
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
                        value = if (detectionLogBusy) stringResource(R.string.common_working) else null,
                        showChevron = !detectionLogBusy,
                        onClick = if (detectionLogBusy) null else onDetectionLogExport
                    )

                    SettingsDivider()

                    SettingsRow(
                        icon = Icons.Outlined.Storage,
                        label = stringResource(R.string.settings_upload_detection_log),
                        value = if (detectionLogBusy) stringResource(R.string.common_working) else null,
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
                    stringResource(R.string.settings_clear_detection_logs_title),
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    stringResource(R.string.settings_clear_detection_logs_message),
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
internal fun SettingsAppearanceChoices(selectedTheme: AppTheme, onSelected: (AppTheme) -> Unit) {
    val options = AppTheme.visibleThemes.map { theme ->
        theme to stringResource(if (theme == AppTheme.LIGHT) R.string.theme_light else R.string.theme_dark)
    }
    val supportedSelection = selectedTheme.supportedTheme
    val largeText = LocalDensity.current.fontScale >= 1.3f
    if (largeText) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (theme, label) ->
                SettingsAppearanceChoice(theme, label, supportedSelection == theme, true, { onSelected(theme) }, Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { (theme, label) ->
                SettingsAppearanceChoice(theme, label, supportedSelection == theme, false, { onSelected(theme) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SettingsAppearanceChoice(
    theme: AppTheme,
    label: String,
    selected: Boolean,
    horizontal: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val icon = when (theme) {
        AppTheme.LIGHT -> Icons.Outlined.LightMode
        else -> Icons.Outlined.DarkMode
    }
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimary else TextSecondary
    val card = modifier.clip(RoundedCornerShape(12.dp))
        .background(if (selected) MaterialTheme.colorScheme.primary else CardBackground)
        .selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 16.dp)
    if (horizontal) {
        Row(card, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = foreground, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = foreground)
        }
    } else {
        Column(card, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = foreground, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = foreground)
        }
    }
}

@Composable
internal fun ParitySettingsIconBadge(icon: ImageVector, tint: Color = settingsBadgeTint(icon)) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(tint),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

private fun settingsBadgeTint(icon: ImageVector): Color = when (icon) {
    Icons.Outlined.RecordVoiceOver, Icons.Outlined.VolumeUp, Icons.Outlined.Mic, Icons.Outlined.PlayArrow -> Color(0xFF45B981)
    Icons.Outlined.Notifications, Icons.Outlined.FlashOn -> Color(0xFFF4C008)
    Icons.Outlined.Straighten, Icons.Outlined.Timer -> Color(0xFFF9D14B)
    Icons.Outlined.Storage, Icons.Outlined.Info -> Color(0xFF8A94A6)
    Icons.Outlined.PrivacyTip, Icons.Outlined.Description -> Color(0xFF9B92E6)
    Icons.Outlined.Build -> Color(0xFF3C3C43)
    Icons.Outlined.Delete -> Color(0xFFD95959)
    else -> Color(0xFF5DC0BA)
}

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
            ParitySettingsIconBadge(icon)
            Spacer(modifier = Modifier.width(12.dp))
        }
        if (LocalDensity.current.fontScale >= 1.3f && value != null) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        } else {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, modifier = Modifier.weight(1f))
            if (value != null) {
                Spacer(Modifier.width(8.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.widthIn(max = 160.dp))
            }
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
            ParitySettingsIconBadge(icon)
            Spacer(modifier = Modifier.width(12.dp))
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
                ParitySettingsIconBadge(icon)
                Spacer(modifier = Modifier.width(12.dp))
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
            modifier = if (icon != null) Modifier.padding(start = 40.dp) else Modifier
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
        modifier = Modifier.padding(start = 56.dp, end = 16.dp),
        color = BorderSubtle
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = TextPrimary,
        fontWeight = FontWeight.SemiBold,
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
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.settings_save_detection_log_chooser)))
}

private fun shareDetectionLogUrl(context: Context, signedUrl: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, signedUrl)
    }
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.settings_share_detection_log_chooser)))
}

private fun shareLogExportUrl(context: Context, signedUrl: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, signedUrl)
    }
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.settings_share_recent_logs_chooser)))
}

@Composable
private fun LogExporter.TimeWindow.localizedDisplayName(): String = stringResource(
    when (this) {
        LogExporter.TimeWindow.LAST_5_MINUTES -> R.string.settings_log_window_5_minutes
        LogExporter.TimeWindow.LAST_30_MINUTES -> R.string.settings_log_window_30_minutes
        LogExporter.TimeWindow.LAST_6_HOURS -> R.string.settings_log_window_6_hours
    }
)

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

/** Named-account gate mirrors the released iPhone Settings entry. */
@Composable
internal fun SettingsDashboardEntry(available: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = available, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        ParitySettingsIconBadge(Icons.Outlined.Computer, Color(0xFF9B92E6))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.dashboard_title), style = MaterialTheme.typography.bodyLarge,
                color = if (available) TextPrimary else TextMuted)
            Text("mytrackspeed.com", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted)
    }
}
