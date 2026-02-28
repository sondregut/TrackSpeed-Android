package com.trackspeed.android.ui.screens.race

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.detection.PhotoFinishDetector
import com.trackspeed.android.sync.SyncQuality
import com.trackspeed.android.ui.components.CameraPreview
import com.trackspeed.android.ui.components.CameraPreviewPlaceholder
import com.trackspeed.android.ui.theme.*

// Dark theme color constants using new LaserSpeed theme
private val CardBackground = SurfaceDark
private val DarkGray = BorderSubtle
private val TextPrimary = com.trackspeed.android.ui.theme.TextPrimary
private val TextSecondary = com.trackspeed.android.ui.theme.TextSecondary
private val TextTertiary = TextMuted
private val AccentBlue = AccentNavy
private val AccentGreen = com.trackspeed.android.ui.theme.AccentGreen
private val AccentRed = Color(0xFFFF453A)
private val AccentOrange = Color(0xFFFF9F0A)

@Composable
fun RaceModeScreen(
    onNavigateBack: () -> Unit,
    viewModel: RaceModeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // BLE permission launcher
    var hasBluetoothPermission by remember { mutableStateOf(false) }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasBluetoothPermission = permissions.values.all { it }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onCameraPermissionGranted()
        }
    }

    // Request BLE permissions on launch
    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        blePermissionLauncher.launch(permissions)
    }

    // Request camera permission when entering active race
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == RacePhase.ACTIVE_RACE || uiState.phase == RacePhase.RACE_READY) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .systemBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            RaceTopBar(
                phase = uiState.phase,
                connectedDeviceCount = uiState.connectedDeviceCount,
                onBack = {
                    if (uiState.phase == RacePhase.PAIRING) {
                        viewModel.resetToStart()
                        onNavigateBack()
                    } else {
                        viewModel.resetToStart()
                    }
                }
            )

            // Phase content
            when (uiState.phase) {
                RacePhase.PAIRING -> PairingContent(
                    pairingStatus = uiState.pairingStatus,
                    connectedDeviceCount = uiState.connectedDeviceCount,
                    onConfirm = { viewModel.confirmPairing() },
                    onCancel = {
                        viewModel.resetToStart()
                        onNavigateBack()
                    }
                )
                RacePhase.SYNCING -> SyncingContent(
                    progress = uiState.syncProgress,
                    quality = uiState.syncQuality,
                    onCancel = viewModel::resetToStart
                )
                RacePhase.RACE_READY -> RaceReadyContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onStartSession = viewModel::startSession
                )
                RacePhase.ACTIVE_RACE -> ActiveRaceContent(
                    uiState = uiState,
                    viewModel = viewModel
                )
                RacePhase.RESULT -> ResultContent(
                    uiState = uiState,
                    onNewRace = viewModel::startNewRace,
                    onExit = onNavigateBack
                )
            }
        }

        // Error snackbar
        if (uiState.errorMessage != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = viewModel::dismissError) {
                        Text(stringResource(R.string.race_dismiss), color = AccentBlue)
                    }
                },
                containerColor = CardBackground,
                contentColor = TextPrimary
            ) {
                Text(uiState.errorMessage ?: "")
            }
        }
    }
}

// =============================================================================
// Top Bar
// =============================================================================

@Composable
private fun RaceTopBar(
    phase: RacePhase,
    connectedDeviceCount: Int = 0,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(36.dp)
                .background(DarkGray, CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.race_back_cd),
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = when (phase) {
                RacePhase.PAIRING -> stringResource(R.string.race_phase_pairing)
                RacePhase.SYNCING -> stringResource(R.string.race_phase_clock_sync)
                RacePhase.RACE_READY -> stringResource(R.string.race_phase_race_ready)
                RacePhase.ACTIVE_RACE -> stringResource(R.string.race_phase_active_race)
                RacePhase.RESULT -> stringResource(R.string.race_phase_result)
            },
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        Spacer(modifier = Modifier.weight(1f))

        // Connected device count badge (during pairing)
        if (phase == RacePhase.PAIRING && connectedDeviceCount > 0) {
            Box(
                modifier = Modifier
                    .background(AccentGreen.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "$connectedDeviceCount connected",
                    color = AccentGreen,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Phase indicator pill
        val phaseLabel = when (phase) {
            RacePhase.PAIRING -> stringResource(R.string.race_pill_pairing)
            RacePhase.SYNCING -> stringResource(R.string.race_pill_syncing)
            RacePhase.RACE_READY -> stringResource(R.string.race_pill_ready)
            RacePhase.ACTIVE_RACE -> stringResource(R.string.race_pill_live)
            RacePhase.RESULT -> stringResource(R.string.race_pill_done)
        }
        val phaseColor = when (phase) {
            RacePhase.ACTIVE_RACE -> AccentRed
            RacePhase.RACE_READY -> AccentGreen
            RacePhase.RESULT -> AccentBlue
            else -> TextSecondary
        }

        Box(
            modifier = Modifier
                .background(phaseColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = phaseLabel,
                color = phaseColor,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

// =============================================================================
// Phase 1: Auto BLE Pairing
// =============================================================================

@Composable
private fun PairingContent(
    pairingStatus: String,
    connectedDeviceCount: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Scanning animation
        ScanningAnimation()

        Text(
            text = if (connectedDeviceCount >= 2) {
                "Ready to time"
            } else {
                stringResource(R.string.race_pairing_searching)
            },
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Text(
            text = if (connectedDeviceCount >= 2) {
                "$connectedDeviceCount phones connected — tap Start Timing when ready"
            } else if (connectedDeviceCount > 0) {
                "$connectedDeviceCount device connected, waiting for more..."
            } else {
                "Open race mode on all phones — they will auto-discover via Bluetooth"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (connectedDeviceCount > 0) AccentGreen else TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // Status pill
        if (pairingStatus.isNotEmpty() && connectedDeviceCount == 0) {
            Box(
                modifier = Modifier
                    .background(TextSecondary.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = pairingStatus,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Start timing button (appears when 2+ phones connected)
        if (connectedDeviceCount >= 2) {
            PillButton(
                text = "Start Timing",
                backgroundColor = AccentGreen,
                onClick = onConfirm
            )
        }

        // Cancel button
        PillButton(
            text = stringResource(R.string.race_cancel),
            backgroundColor = DarkGray,
            onClick = onCancel
        )
    }
}

@Composable
private fun ScanningAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1"
    )
    val scale1 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale1"
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseOut, delayMillis = 500),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2"
    )
    val scale2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseOut, delayMillis = 500),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale2"
    )

    Box(
        modifier = Modifier.size(120.dp),
        contentAlignment = Alignment.Center
    ) {
        // Animated rings
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = AccentBlue.copy(alpha = alpha1),
                radius = 30.dp.toPx() * scale1,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = AccentBlue.copy(alpha = alpha2),
                radius = 30.dp.toPx() * scale2,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
        }

        // Center icon
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(AccentBlue.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun RoleBadge(role: DeviceRole?) {
    val (label, color) = when (role) {
        DeviceRole.START -> stringResource(R.string.race_role_start) to AccentGreen
        DeviceRole.FINISH -> stringResource(R.string.race_role_finish) to AccentRed
        null -> stringResource(R.string.race_role_unknown) to TextSecondary
    }

    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

// =============================================================================
// Phase 3: Syncing
// =============================================================================

@Composable
private fun SyncingContent(
    progress: Float,
    quality: SyncQuality?,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Sync icon with progress ring
        Box(
            modifier = Modifier.size(100.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                color = AccentBlue,
                trackColor = DarkGray,
                strokeWidth = 6.dp
            )

            Icon(
                imageVector = Icons.Filled.Sync,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(36.dp)
            )
        }

        Text(
            text = stringResource(R.string.race_syncing_title),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        Text(
            text = stringResource(R.string.race_syncing_percent, (progress * 100).toInt()),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary
        )

        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = AccentBlue,
            trackColor = DarkGray
        )

        Text(
            text = stringResource(R.string.race_syncing_desc),
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // Quality indicator if available
        if (quality != null) {
            SyncQualityBadge(quality = quality)
        }

        Spacer(modifier = Modifier.weight(1f))

        PillButton(
            text = stringResource(R.string.race_cancel),
            backgroundColor = DarkGray,
            onClick = onCancel
        )
    }
}

// =============================================================================
// Phase 4: Race Ready
// =============================================================================

@Composable
private fun RaceReadyContent(
    uiState: RaceModeUiState,
    viewModel: RaceModeViewModel,
    onStartSession: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Sync quality card
        SyncStatusCard(
            quality = uiState.syncQuality,
            uncertaintyMs = uiState.syncUncertaintyMs,
            offsetMs = uiState.syncOffsetMs
        )

        // Role indicator
        RoleBadge(role = uiState.role)

        // Distance selector
        DistanceSelector(
            currentDistance = uiState.distanceMeters,
            onDistanceChanged = viewModel::setDistance
        )

        // Camera preview (small)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, DarkGray, RoundedCornerShape(20.dp))
        ) {
            if (uiState.hasPermission && uiState.cameraState !is CameraManager.CameraState.Error) {
                CameraPreview(
                    gatePosition = uiState.gatePosition,
                    onGatePositionChanged = viewModel::setGatePosition,
                    fps = uiState.fps,
                    detectionState = uiState.detectionState,
                    sensorOrientation = uiState.sensorOrientation,
                    isFrontCamera = uiState.isFrontCamera,
                    onSurfaceReady = { surface -> viewModel.onSurfaceReady(surface) },
                    onSurfaceDestroyed = { viewModel.onSurfaceDestroyed() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CameraPreviewPlaceholder(
                    message = stringResource(R.string.race_camera_permission_needed),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Text(
            text = stringResource(
                R.string.race_position_instruction,
                when (uiState.role) {
                    DeviceRole.START -> stringResource(R.string.race_role_start).lowercase()
                    DeviceRole.FINISH -> stringResource(R.string.race_role_finish).lowercase()
                    null -> stringResource(R.string.race_position_instruction_gate)
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        // Start session button
        PillButton(
            text = stringResource(R.string.race_start_session),
            backgroundColor = AccentGreen,
            onClick = onStartSession
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SyncStatusCard(
    quality: SyncQuality?,
    uncertaintyMs: Double,
    offsetMs: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.race_clock_synced),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )
                }

                if (quality != null) {
                    SyncQualityBadge(quality = quality)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DetailText(label = stringResource(R.string.race_offset_label), value = String.format(stringResource(R.string.race_ms_format), offsetMs))
                DetailText(label = stringResource(R.string.race_uncertainty_label), value = String.format(stringResource(R.string.race_ms_format), uncertaintyMs))
            }
        }
    }
}

@Composable
private fun DetailText(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium
            ),
            color = TextSecondary
        )
    }
}

@Composable
private fun DistanceSelector(
    currentDistance: Double,
    onDistanceChanged: (Double) -> Unit
) {
    val distances = listOf(10.0, 20.0, 30.0, 40.0, 60.0, 100.0, 200.0)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.race_distance_label),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = TextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            distances.forEach { distance ->
                val isSelected = distance == currentDistance
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(
                            if (isSelected) AccentBlue else DarkGray,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onDistanceChanged(distance) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.race_timer_distance, distance.toInt()),
                        color = if (isSelected) Color.White else TextSecondary,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncQualityBadge(quality: SyncQuality) {
    val (label, color) = when (quality) {
        SyncQuality.EXCELLENT -> stringResource(R.string.race_quality_excellent) to Color(0xFF30D158)
        SyncQuality.GOOD -> stringResource(R.string.race_quality_good) to Color(0xFF30D158)
        SyncQuality.FAIR -> stringResource(R.string.race_quality_fair) to AccentOrange
        SyncQuality.POOR -> stringResource(R.string.race_quality_poor) to AccentOrange
        SyncQuality.BAD -> stringResource(R.string.race_quality_bad) to AccentRed
    }

    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold
            )
        )
    }
}

// =============================================================================
// Phase 5: Active Race
// =============================================================================

@Composable
private fun ActiveRaceContent(
    uiState: RaceModeUiState,
    viewModel: RaceModeViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status banner
        ActiveRaceBanner(
            detectionState = uiState.detectionState,
            raceStatus = uiState.raceStatus
        )

        // Camera preview (large)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(
                    width = 3.dp,
                    color = getDetectionBorderColor(uiState.detectionState),
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            if (uiState.hasPermission && uiState.cameraState !is CameraManager.CameraState.Error) {
                CameraPreview(
                    gatePosition = uiState.gatePosition,
                    onGatePositionChanged = viewModel::setGatePosition,
                    fps = uiState.fps,
                    detectionState = uiState.detectionState,
                    sensorOrientation = uiState.sensorOrientation,
                    isFrontCamera = uiState.isFrontCamera,
                    onSurfaceReady = { surface -> viewModel.onSurfaceReady(surface) },
                    onSurfaceDestroyed = { viewModel.onSurfaceDestroyed() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CameraPreviewPlaceholder(
                    message = stringResource(R.string.race_camera_not_available),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Timer display
        TimerDisplay(
            role = uiState.role,
            elapsedTimeSeconds = uiState.elapsedTimeSeconds,
            isRunning = uiState.elapsedTimeSeconds > 0,
            distanceMeters = uiState.distanceMeters
        )

        // Role and sync info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            RoleBadge(role = uiState.role)

            if (uiState.syncQuality != null) {
                SyncQualityBadge(quality = uiState.syncQuality)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ActiveRaceBanner(
    detectionState: PhotoFinishDetector.State,
    raceStatus: String
) {
    val (text, icon, color) = when {
        detectionState == PhotoFinishDetector.State.UNSTABLE ->
            Triple(stringResource(R.string.race_banner_hold_steady), Icons.Filled.Vibration, AccentRed)
        detectionState == PhotoFinishDetector.State.ATHLETE_TOO_FAR ->
            Triple(stringResource(R.string.race_banner_too_far), Icons.AutoMirrored.Filled.DirectionsRun, AccentOrange)
        detectionState == PhotoFinishDetector.State.TRIGGERED ->
            Triple(stringResource(R.string.race_banner_triggered), Icons.Filled.FiberManualRecord, AccentRed)
        detectionState == PhotoFinishDetector.State.COOLDOWN ->
            Triple(stringResource(R.string.race_banner_cooldown), Icons.Filled.FiberManualRecord, AccentOrange)
        raceStatus.isNotEmpty() -> {
            val statusText = when (raceStatus) {
                "waiting" -> stringResource(R.string.race_status_waiting)
                "started" -> stringResource(R.string.race_status_started)
                "finished" -> stringResource(R.string.race_status_finished)
                else -> raceStatus
            }
            Triple(statusText.uppercase(), Icons.Filled.FiberManualRecord, AccentGreen)
        }
        else ->
            Triple(stringResource(R.string.race_banner_ready), Icons.Filled.FiberManualRecord, AccentGreen)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        )
    }
}

@Composable
private fun TimerDisplay(
    role: DeviceRole?,
    elapsedTimeSeconds: Double,
    isRunning: Boolean,
    distanceMeters: Double
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = formatRaceTime(elapsedTimeSeconds),
            color = if (isRunning) AccentBlue else TextTertiary,
            style = MaterialTheme.typography.displayLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 56.sp
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (isRunning) {
                stringResource(R.string.race_timer_distance, distanceMeters.toInt())
            } else {
                stringResource(R.string.race_waiting_for_crossing)
            },
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// =============================================================================
// Phase 6: Result
// =============================================================================

@Composable
private fun ResultContent(
    uiState: RaceModeUiState,
    onNewRace: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Checkmark icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(AccentGreen.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(48.dp)
            )
        }

        Text(
            text = stringResource(R.string.race_complete),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        // Time result
        val resultTime = uiState.resultTimeSeconds
        if (resultTime != null) {
            Text(
                text = formatRaceTime(resultTime),
                color = AccentBlue,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 64.sp
                )
            )

            // Details card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ResultDetailRow(
                        label = stringResource(R.string.race_result_distance),
                        value = stringResource(R.string.race_timer_distance, uiState.distanceMeters.toInt())
                    )

                    if (resultTime > 0) {
                        val speed = uiState.distanceMeters / resultTime
                        ResultDetailRow(
                            label = stringResource(R.string.race_result_avg_speed),
                            value = String.format(stringResource(R.string.race_result_speed_format), speed)
                        )
                    }

                    val uncertainty = uiState.resultUncertaintyMs
                    if (uncertainty != null) {
                        ResultDetailRow(
                            label = stringResource(R.string.race_result_uncertainty),
                            value = String.format(stringResource(R.string.race_result_uncertainty_format), uncertainty)
                        )
                    }

                    val syncQuality = uiState.syncQuality
                    if (syncQuality != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.race_result_sync_quality),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            SyncQualityBadge(quality = syncQuality)
                        }
                    }

                    ResultDetailRow(
                        label = stringResource(R.string.race_result_role),
                        value = when (uiState.role) {
                            DeviceRole.START -> stringResource(R.string.race_role_start)
                            DeviceRole.FINISH -> stringResource(R.string.race_role_finish)
                            null -> stringResource(R.string.race_role_unknown)
                        }
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.race_no_timing_data),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        PillButton(
            text = stringResource(R.string.race_new_race),
            backgroundColor = AccentGreen,
            onClick = onNewRace
        )

        PillButton(
            text = stringResource(R.string.race_exit),
            backgroundColor = DarkGray,
            onClick = onExit
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ResultDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )
    }
}

// =============================================================================
// Shared Components
// =============================================================================

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
            .background(backgroundColor, RoundedCornerShape(25.dp))
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

private fun getDetectionBorderColor(state: PhotoFinishDetector.State): Color {
    return when (state) {
        PhotoFinishDetector.State.UNSTABLE -> AccentRed
        PhotoFinishDetector.State.NO_ATHLETE -> AccentGreen
        PhotoFinishDetector.State.ATHLETE_TOO_FAR -> AccentOrange
        PhotoFinishDetector.State.READY -> AccentGreen
        PhotoFinishDetector.State.TRIGGERED -> AccentRed
        PhotoFinishDetector.State.COOLDOWN -> AccentOrange
    }
}

private fun formatRaceTime(seconds: Double): String {
    if (seconds <= 0) return "0.00"

    val totalMs = (seconds * 1000).toLong()
    val mins = totalMs / 60000
    val secs = (totalMs % 60000) / 1000
    val hundredths = (totalMs % 1000) / 10

    return if (mins > 0) {
        String.format("%d:%02d.%02d", mins, secs, hundredths)
    } else {
        String.format("%d.%02d", secs, hundredths)
    }
}
