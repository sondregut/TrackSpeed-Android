package com.trackspeed.android.ui.screens.timing

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.R
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.detection.PhotoFinishDetector
import com.trackspeed.android.ui.components.CameraPreview
import com.trackspeed.android.ui.components.CameraPreviewPlaceholder
import com.trackspeed.android.ui.components.CountdownOverlay
import com.trackspeed.android.ui.components.ExpandedThumbnail
import com.trackspeed.android.ui.components.InFrameStartOverlay
import com.trackspeed.android.ui.components.ProFeatureGateDialog
import com.trackspeed.android.ui.components.StartMode
import com.trackspeed.android.ui.components.StartOverlaySelector
import com.trackspeed.android.ui.components.ThumbnailViewerDialog
import com.trackspeed.android.ui.components.TouchStartOverlay
import com.trackspeed.android.ui.components.VoiceStartOverlay
import com.trackspeed.android.ui.util.formatDistance
import com.trackspeed.android.ui.util.formatTime
import com.trackspeed.android.ui.theme.*

// Color constants - keep camera area dark for contrast, update accent/text colors
private val ScreenBackground = Color(0xFF000000) // Keep dark for camera contrast

@Composable
fun BasicTimingScreen(
    onNavigateBack: () -> Unit,
    onPaywallClick: () -> Unit = {},
    distance: Double = 60.0,
    startType: String = "standing",
    viewModel: BasicTimingViewModel = hiltViewModel()
) {
    // Pass distance and startType to ViewModel on first composition
    LaunchedEffect(distance, startType) {
        viewModel.setSessionConfig(distance, startType)
    }

    val uiState by viewModel.uiState.collectAsState()
    val isProUser by viewModel.isProUser.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var expandedThumbnail by remember { mutableStateOf<ExpandedThumbnail?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showFullscreenCamera by remember { mutableStateOf(false) }

    // Start mode state
    var currentStartMode by remember { mutableStateOf(StartMode.FLYING) }
    var activeStartOverlay by remember { mutableStateOf<StartMode?>(null) }
    var showStartModeSelector by remember { mutableStateOf(false) }

    // Pro feature gate dialog state
    var proGateDialogMode by remember { mutableStateOf<StartMode?>(null) }

    // Fullscreen thumbnail viewer
    ThumbnailViewerDialog(
        thumbnail = expandedThumbnail,
        onDismiss = { expandedThumbnail = null }
    )

    // Pro feature gate dialog for start modes
    proGateDialogMode?.let { mode ->
        ProFeatureGateDialog(
            featureName = "${mode.displayName} Start",
            featureDescription = mode.description,
            onUpgrade = {
                proGateDialogMode = null
                onPaywallClick()
            },
            onDismiss = {
                proGateDialogMode = null
            }
        )
    }

    // Paywall prompt when free session limit reached
    if (uiState.showPaywallPrompt) {
        ProFeatureGateDialog(
            featureName = "Unlimited Sessions",
            featureDescription = "You've reached the free session limit. Upgrade to Pro to save unlimited training sessions.",
            onUpgrade = {
                viewModel.onPaywallPromptConsumed()
                onPaywallClick()
            },
            onDismiss = {
                viewModel.onPaywallPromptConsumed()
            }
        )
    }

    // Start mode selector bottom sheet
    if (showStartModeSelector) {
        StartOverlaySelector(
            currentMode = currentStartMode,
            onModeSelected = { mode ->
                if (viewModel.canUseStartMode(mode.name)) {
                    currentStartMode = mode
                } else {
                    proGateDialogMode = mode
                }
            },
            onDismiss = { showStartModeSelector = false }
        )
    }

    // Note: showFullscreenCamera is used below to expand the camera preview area

    // Show snackbar when session saved
    val sessionSavedMessage = stringResource(R.string.timing_session_saved)
    LaunchedEffect(uiState.sessionSaved) {
        if (uiState.sessionSaved) {
            snackbarHostState.showSnackbar(sessionSavedMessage)
            viewModel.onSessionSavedConsumed()
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onCameraPermissionGranted()
        }
    }

    // Request camera permission on launch
    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (showFullscreenCamera) {
        // Fullscreen camera mode - replaces entire layout so camera surface transfers
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
        ) {
            if (uiState.hasPermission) {
                CameraPreview(
                    gatePosition = uiState.gatePosition,
                    onGatePositionChanged = viewModel::setGatePosition,
                    fps = uiState.fps,
                    detectionState = uiState.detectionState,
                    sensorOrientation = uiState.sensorOrientation,
                    isFrontCamera = uiState.isFrontCamera,
                    onSurfaceReady = { surface ->
                        viewModel.onSurfaceReady(surface)
                    },
                    onSurfaceDestroyed = {
                        viewModel.onSurfaceDestroyed()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Close button at top
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .clickable { showFullscreenCamera = false }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.timing_tap_to_close),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            // Camera flip button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp)
                    .size(44.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(22.dp)
                    )
                    .clickable { viewModel.switchCamera() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = stringResource(R.string.timing_flip_camera),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Timer overlay at bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = formatTime(uiState.currentTime),
                    color = AccentBlue,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    } else {
        // Normal timing layout
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenBackground)
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 1. Custom Top Bar
                TopBar(
                    distance = uiState.distance,
                    startType = uiState.startType,
                    onSettingsClick = { /* TODO: settings */ },
                    onEndClick = onNavigateBack
                )

                // 2. Tab Row
                TimingTabRow(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )

                if (selectedTab == 0) {
                    // Record tab content
                    RecordTabContent(
                        uiState = uiState,
                        viewModel = viewModel,
                        onThumbnailClick = { expandedThumbnail = it },
                        onCameraClick = { showFullscreenCamera = true },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    // Results tab
                    ResultsTabContent(
                        uiState = uiState,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Bottom button area
                BottomButtonBar(
                    uiState = uiState,
                    startMode = currentStartMode,
                    onStart = {
                        when (currentStartMode) {
                            StartMode.FLYING -> viewModel.startTiming()
                            else -> activeStartOverlay = currentStartMode
                        }
                    },
                    onStop = viewModel::stopTiming,
                    onReset = viewModel::resetTiming,
                    onSave = viewModel::saveSession,
                    onNavigateBack = onNavigateBack,
                    onStartModeClick = { showStartModeSelector = true }
                )
            }

            // Snackbar host
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // Active start overlay (renders on top of everything, including camera)
    activeStartOverlay?.let { mode ->
        when (mode) {
            StartMode.TOUCH -> TouchStartOverlay(
                onStart = { timestamp ->
                    viewModel.handleExternalStart(timestamp)
                    activeStartOverlay = null
                },
                onCancel = { activeStartOverlay = null }
            )
            StartMode.COUNTDOWN -> CountdownOverlay(
                onCountdownComplete = { timestamp ->
                    viewModel.handleExternalStart(timestamp)
                    activeStartOverlay = null
                },
                onCancel = { activeStartOverlay = null }
            )
            StartMode.VOICE -> VoiceStartOverlay(
                voiceStartService = viewModel.voiceStartService,
                onStart = { timestamp ->
                    viewModel.handleExternalStart(timestamp)
                    activeStartOverlay = null
                },
                onCancel = { activeStartOverlay = null }
            )
            StartMode.INFRAME -> InFrameStartOverlay(
                detectionState = viewModel.detectionStateFlow,
                onStart = { timestamp ->
                    viewModel.handleExternalStart(timestamp)
                    activeStartOverlay = null
                },
                onCancel = { activeStartOverlay = null }
            )
            StartMode.FLYING -> {
                // Flying mode doesn't use an overlay
                activeStartOverlay = null
            }
        }
    }
}

// -- 1. Custom Top Bar: Practice pill + Settings + End button --

@Composable
private fun TopBar(
    distance: Double,
    startType: String,
    onSettingsClick: () -> Unit,
    onEndClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillLabel = stringResource(R.string.timing_practice_label, formatDistance(distance))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Practice pill badge with distance
        Row(
            modifier = Modifier
                .background(
                    color = AccentGreen.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = pillLabel,
                color = AccentGreen,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Settings gear icon
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = BorderSubtle,
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.common_settings),
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // End button pill
        Box(
            modifier = Modifier
                .background(
                    color = StatusRed,
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable { onEndClick() }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.timing_end),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

// -- 2. Tab Row --

@Composable
private fun TimingTabRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Record tab
        TabPill(
            label = stringResource(R.string.timing_tab_record),
            icon = Icons.Default.PlayArrow,
            isSelected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            modifier = Modifier.weight(1f)
        )

        // Results tab
        TabPill(
            label = stringResource(R.string.timing_tab_results),
            icon = Icons.AutoMirrored.Filled.List,
            isSelected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TabPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .background(
                color = if (isSelected) BorderSubtle else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) TextPrimary else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = if (isSelected) TextPrimary else TextSecondary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            )
        }
    }
}

// -- Record Tab Content --

@Composable
private fun RecordTabContent(
    uiState: BasicTimingUiState,
    viewModel: BasicTimingViewModel,
    onThumbnailClick: (ExpandedThumbnail) -> Unit,
    onCameraClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // 3. Status Banner (full width)
        StatusBanner(uiState = uiState)

        // 4. Timer + Camera Row
        TimerCameraRow(
            uiState = uiState,
            viewModel = viewModel,
            onCameraClick = onCameraClick
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 5. Practice Section Header
        PracticeSectionHeader(
            distance = uiState.distance,
            lapCount = uiState.laps.count { it.lapNumber > 0 }
        )

        // 6. Laps List (takes remaining space)
        LapsList(
            laps = uiState.laps,
            speedUnit = uiState.speedUnit,
            onThumbnailClick = onThumbnailClick,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    }
}

// -- 3. Status Banner --

@Composable
private fun StatusBanner(
    uiState: BasicTimingUiState,
    modifier: Modifier = Modifier
) {
    val bannerInfo = getStatusBannerInfo(uiState)

    if (bannerInfo != null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(bannerInfo.backgroundColor)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = bannerInfo.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = bannerInfo.text,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            )
        }
    }
}

private data class BannerInfo(
    val text: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val backgroundColor: Color
)

@Composable
private fun getStatusBannerInfo(uiState: BasicTimingUiState): BannerInfo? {
    return when {
        uiState.detectionState == PhotoFinishDetector.State.UNSTABLE ->
            BannerInfo(stringResource(R.string.timing_banner_hold_still), Icons.Default.Vibration, StatusRed)

        uiState.detectionState == PhotoFinishDetector.State.ATHLETE_TOO_FAR ->
            BannerInfo(stringResource(R.string.timing_banner_too_far), Icons.AutoMirrored.Filled.DirectionsRun, Color(0xFFFF9500))

        uiState.isRunning && !uiState.waitingForStart ->
            BannerInfo(stringResource(R.string.timing_banner_running), Icons.Default.FiberManualRecord, StatusGreen)

        uiState.isRunning && uiState.waitingForStart ->
            BannerInfo(stringResource(R.string.timing_banner_ready), Icons.Default.FiberManualRecord, StatusGreen)

        !uiState.isRunning && uiState.isArmed && uiState.laps.isEmpty() ->
            BannerInfo(stringResource(R.string.timing_banner_ready), Icons.Default.FiberManualRecord, StatusGreen)

        !uiState.isRunning && uiState.laps.isNotEmpty() ->
            BannerInfo(stringResource(R.string.timing_banner_stopped), Icons.Default.Pause, TextMuted)

        else -> null
    }
}

// -- 4. Timer + Camera Row --

@Composable
private fun TimerCameraRow(
    uiState: BasicTimingUiState,
    viewModel: BasicTimingViewModel,
    onCameraClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lapCount = uiState.laps.count { it.lapNumber > 0 }
    val runNumber = lapCount + 1

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left side: Timer info (~55%)
        Column(
            modifier = Modifier
                .weight(0.55f)
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // Run number label
            Text(
                text = stringResource(R.string.timing_run_number, runNumber),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Large time display
            Text(
                text = formatTime(uiState.currentTime),
                color = AccentBlue,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 48.sp
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Speed row
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.timing_label_speed),
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatSpeed(uiState.currentSpeedMs, uiState.speedUnit),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Distance row
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.timing_label_distance),
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatDistance(uiState.distance),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }

        // Right side: Camera preview (~45%)
        val cameraBorderColor = when {
            uiState.detectionState == PhotoFinishDetector.State.UNSTABLE -> StatusRed
            uiState.isRunning && !uiState.waitingForStart -> StatusGreen
            uiState.detectionState == PhotoFinishDetector.State.READY -> StatusGreen
            uiState.isArmed -> StatusGreen
            else -> StatusRed
        }

        Box(
            modifier = Modifier
                .weight(0.45f)
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = 3.dp,
                    color = cameraBorderColor,
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable { onCameraClick() }
        ) {
            when {
                uiState.cameraState is CameraManager.CameraState.Error -> {
                    CameraPreviewPlaceholder(
                        message = (uiState.cameraState as CameraManager.CameraState.Error).message,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                uiState.hasPermission -> {
                    CameraPreview(
                        gatePosition = uiState.gatePosition,
                        onGatePositionChanged = viewModel::setGatePosition,
                        fps = uiState.fps,
                        detectionState = uiState.detectionState,
                        sensorOrientation = uiState.sensorOrientation,
                        isFrontCamera = uiState.isFrontCamera,
                        onSurfaceReady = { surface ->
                            viewModel.onSurfaceReady(surface)
                        },
                        onSurfaceDestroyed = {
                            viewModel.onSurfaceDestroyed()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    CameraPreviewPlaceholder(
                        message = stringResource(R.string.timing_camera_permission_required),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Camera icon overlay in top-left
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(28.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
            }

            // Camera flip button in top-right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { viewModel.switchCamera() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = stringResource(R.string.timing_flip_camera),
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// -- 5. Practice Section Header --

@Composable
private fun PracticeSectionHeader(
    distance: Double,
    lapCount: Int,
    modifier: Modifier = Modifier
) {
    val sectionLabel = stringResource(R.string.timing_practice_label, formatDistance(distance))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = sectionLabel,
            color = TextPrimary,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold
            )
        )
        Text(
            text = " \u00B7 ${pluralStringResource(R.plurals.timing_lap_count, lapCount, lapCount)}",
            color = TextSecondary,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

// -- 6. Laps List --

@Composable
private fun LapsList(
    laps: List<SoloLapResult>,
    speedUnit: String,
    onThumbnailClick: (ExpandedThumbnail) -> Unit,
    modifier: Modifier = Modifier
) {
    val actualLaps = laps.filter { it.lapNumber > 0 }

    if (actualLaps.isEmpty() && laps.none { it.lapNumber == 0 }) {
        // Empty state
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.timing_no_laps_yet),
                    color = TextSecondary,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.timing_run_through_gate),
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        val listState = rememberLazyListState()
        val reversedLaps = remember(laps) { laps.reversed() }

        // Auto-scroll to top when new lap arrives
        LaunchedEffect(laps.size) {
            if (laps.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
        }

        LazyColumn(
            state = listState,
            modifier = modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(
                items = reversedLaps,
                key = { it.lapNumber }
            ) { lap ->
                LapCard(
                    lap = lap,
                    speedUnit = speedUnit,
                    onThumbnailClick = onThumbnailClick
                )
            }
        }
    }
}

// -- Lap Card --

@Composable
private fun LapCard(
    lap: SoloLapResult,
    speedUnit: String,
    onThumbnailClick: (ExpandedThumbnail) -> Unit,
    modifier: Modifier = Modifier
) {
    val isStart = lap.lapNumber == 0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = CardBackground,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .width(50.dp)
                .height(66.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = TextTertiary.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp)
                )
                .then(
                    if (lap.thumbnail != null) {
                        Modifier.clickable {
                            onThumbnailClick(
                                ExpandedThumbnail(
                                    bitmap = lap.thumbnail,
                                    gatePosition = lap.gatePosition
                                )
                            )
                        }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (lap.thumbnail != null) {
                Image(
                    bitmap = lap.thumbnail.asImageBitmap(),
                    contentDescription = stringResource(R.string.timing_crossing_frame),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Gate line overlay on thumbnail (live session — two-layer style matching iOS)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val x = size.width * lap.gatePosition
                    // Shadow layer
                    drawLine(
                        color = Color.Black.copy(alpha = 0.5f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 4f
                    )
                    // Core layer
                    drawLine(
                        color = Color.Red.copy(alpha = 0.9f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2.5f
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.05f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isStart) stringResource(R.string.timing_go) else stringResource(R.string.timing_no_thumbnail),
                        color = TextTertiary,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }

        // Middle: title + subtitle + speed
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (isStart) stringResource(R.string.timing_start) else stringResource(R.string.timing_lap_number, lap.lapNumber),
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isStart) stringResource(R.string.timing_timer_started) else stringResource(R.string.timing_total_time, formatTime(lap.totalTimeSeconds)),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            if (!isStart && lap.speedMs > 0.0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatSpeed(lap.speedMs, speedUnit),
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // Right: time
        Text(
            text = if (isStart) "0.00" else formatTime(lap.lapTimeSeconds),
            color = if (isStart) TextTertiary else TextPrimary,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

// -- Results Tab Content --

@Composable
private fun ResultsTabContent(
    uiState: BasicTimingUiState,
    modifier: Modifier = Modifier
) {
    val actualLaps = remember(uiState.laps) {
        uiState.laps.filter { it.lapNumber > 0 }
    }

    if (actualLaps.isEmpty()) {
        // Empty state
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.timing_no_results_yet),
                    color = TextSecondary,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.timing_complete_laps_hint),
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        val bestLapTime = actualLaps.minOf { it.lapTimeSeconds }
        val averageLapTime = actualLaps.map { it.lapTimeSeconds }.average()
        val totalTime = actualLaps.last().totalTimeSeconds
        val lapCount = actualLaps.size
        val distance = uiState.distance
        val bestSpeed = if (distance > 0 && bestLapTime > 0) distance / bestLapTime else null
        val maxLapTime = actualLaps.maxOf { it.lapTimeSeconds }

        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Summary stats card
            item(key = "summary") {
                ResultsSummaryCard(
                    bestLapTime = bestLapTime,
                    averageLapTime = averageLapTime,
                    totalTime = totalTime,
                    lapCount = lapCount,
                    bestSpeed = bestSpeed,
                    distance = distance
                )
            }

            // Section header
            item(key = "header") {
                Text(
                    text = stringResource(R.string.timing_lap_comparison),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Lap comparison rows
            items(
                items = actualLaps,
                key = { it.lapNumber }
            ) { lap ->
                LapComparisonRow(
                    lap = lap,
                    bestLapTime = bestLapTime,
                    maxLapTime = maxLapTime,
                    distance = distance
                )
            }
        }
    }
}

@Composable
private fun ResultsSummaryCard(
    bestLapTime: Double,
    averageLapTime: Double,
    totalTime: Double,
    lapCount: Int,
    bestSpeed: Double?,
    distance: Double,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = CardBackground,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title row
        Text(
            text = stringResource(R.string.timing_session_summary),
            color = TextPrimary,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold
            )
        )

        // Stats grid: 2 columns
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatItem(
                    label = stringResource(R.string.timing_stat_best),
                    value = formatTime(bestLapTime),
                    valueColor = StatusGreen
                )
                StatItem(
                    label = stringResource(R.string.timing_stat_total_time),
                    value = formatTime(totalTime),
                    valueColor = TextPrimary
                )
            }

            // Right column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatItem(
                    label = stringResource(R.string.timing_stat_average),
                    value = formatTime(averageLapTime),
                    valueColor = AccentBlue
                )
                StatItem(
                    label = stringResource(R.string.timing_stat_laps),
                    value = lapCount.toString(),
                    valueColor = TextPrimary
                )
            }
        }

        // Best speed row (if distance is known)
        if (bestSpeed != null && distance > 0) {
            HorizontalDivider(
                color = TextTertiary.copy(alpha = 0.3f),
                thickness = 0.5.dp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.timing_best_speed),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(R.string.timing_speed_ms_format, bestSpeed),
                    color = StatusGreen,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun LapComparisonRow(
    lap: SoloLapResult,
    bestLapTime: Double,
    maxLapTime: Double,
    distance: Double,
    modifier: Modifier = Modifier
) {
    val isBest = lap.lapTimeSeconds == bestLapTime
    val delta = lap.lapTimeSeconds - bestLapTime
    val speed = if (distance > 0 && lap.lapTimeSeconds > 0) distance / lap.lapTimeSeconds else null

    // Bar width fraction: best lap = full bar, slowest = minimum bar
    // Faster time (lower seconds) = longer bar
    val timeRange = maxLapTime - bestLapTime
    val barFraction = if (timeRange > 0.001) {
        (1.0 - (lap.lapTimeSeconds - bestLapTime) / timeRange)
            .toFloat()
            .coerceIn(0.15f, 1f)
    } else {
        1f // All laps are the same time
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = CardBackground,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Lap number badge
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (isBest) StatusGreen.copy(alpha = 0.2f) else BorderSubtle,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${lap.lapNumber}",
                color = if (isBest) StatusGreen else TextSecondary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // Middle: bar graph + speed
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Speed bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(
                        color = BorderSubtle,
                        shape = RoundedCornerShape(3.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = barFraction)
                        .background(
                            color = if (isBest) StatusGreen else AccentBlue.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(3.dp)
                        )
                )
            }

            // Speed + delta row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (speed != null) {
                        stringResource(R.string.timing_speed_ms_format, speed)
                    } else {
                        stringResource(R.string.timing_no_speed_ms)
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                if (!isBest) {
                    Text(
                        text = stringResource(R.string.timing_delta_format, delta),
                        color = StatusRed.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                } else {
                    Text(
                        text = stringResource(R.string.timing_best_label),
                        color = StatusGreen,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }

        // Right: lap time
        Text(
            text = formatTime(lap.lapTimeSeconds),
            color = if (isBest) StatusGreen else TextPrimary,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

// -- 7. Bottom Button Bar --

@Composable
private fun BottomButtonBar(
    uiState: BasicTimingUiState,
    startMode: StartMode,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onNavigateBack: () -> Unit,
    onStartModeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasActualLaps = uiState.laps.count { it.lapNumber > 0 } >= 1

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp, top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when {
            // Running with laps: show Cancel Run
            uiState.isRunning && hasActualLaps -> {
                PillButton(
                    text = stringResource(R.string.timing_btn_cancel_run),
                    backgroundColor = StatusRed,
                    onClick = onStop
                )
            }

            // Running, waiting for start or just started: show Pause
            uiState.isRunning -> {
                PillButton(
                    text = stringResource(R.string.timing_btn_pause),
                    backgroundColor = BorderSubtle,
                    onClick = onStop
                )
            }

            // Stopped with laps: save + restart options
            !uiState.isRunning && uiState.laps.isNotEmpty() -> {
                if (hasActualLaps) {
                    PillButton(
                        text = stringResource(R.string.timing_btn_save_session),
                        backgroundColor = AccentBlue,
                        onClick = onSave
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PillButton(
                        text = stringResource(R.string.timing_btn_restart),
                        backgroundColor = StatusGreen,
                        onClick = onReset,
                        modifier = Modifier.weight(1f)
                    )
                    PillButton(
                        text = stringResource(R.string.timing_btn_exit),
                        backgroundColor = BorderSubtle,
                        onClick = onNavigateBack,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Initial state: show Start mode chip + Start button
            else -> {
                // Start mode chip
                Row(
                    modifier = Modifier
                        .background(
                            color = BorderSubtle,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onStartModeClick() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = startMode.icon,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = startMode.displayName,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                }

                PillButton(
                    text = stringResource(R.string.timing_btn_start),
                    backgroundColor = StatusGreen,
                    onClick = onStart
                )
            }
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(25.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold
            )
        )
    }
}

// -- Utilities --


private fun formatSpeed(speedMs: Double, speedUnit: String): String {
    if (speedMs <= 0.0) return "-- $speedUnit"

    val convertedSpeed = when (speedUnit) {
        "km/h" -> speedMs * 3.6
        "mph" -> speedMs * 2.23694
        else -> speedMs  // m/s is the base unit
    }

    return String.format(java.util.Locale.US, "%.2f %s", convertedSpeed, speedUnit)
}
