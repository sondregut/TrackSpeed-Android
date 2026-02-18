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
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.detection.PhotoFinishDetector
import com.trackspeed.android.sync.SyncQuality
import com.trackspeed.android.ui.components.CameraPreview
import com.trackspeed.android.ui.components.CameraPreviewPlaceholder

// Dark theme color constants matching the rest of the app
private val ScreenBackground = Color(0xFF000000)
private val CardBackground = Color(0xFF2C2C2E)
private val DarkGray = Color(0xFF3A3A3C)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFF8E8E93)
private val TextTertiary = Color(0xFF636366)
private val AccentBlue = Color(0xFF0A84FF)
private val AccentGreen = Color(0xFF30D158)
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
            .background(ScreenBackground)
            .systemBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            RaceTopBar(
                phase = uiState.phase,
                onBack = {
                    if (uiState.phase == RacePhase.ROLE_SELECTION) {
                        onNavigateBack()
                    } else {
                        viewModel.resetToRoleSelection()
                    }
                }
            )

            // Phase content
            when (uiState.phase) {
                RacePhase.ROLE_SELECTION -> RoleSelectionContent(
                    hasBluetoothPermission = hasBluetoothPermission,
                    onRoleSelected = viewModel::selectRole
                )
                RacePhase.PAIRING -> PairingContent(
                    role = uiState.role,
                    pairingStatus = uiState.pairingStatus,
                    onCancel = viewModel::resetToRoleSelection
                )
                RacePhase.SYNCING -> SyncingContent(
                    progress = uiState.syncProgress,
                    quality = uiState.syncQuality,
                    onCancel = viewModel::resetToRoleSelection
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
                        Text("Dismiss", color = AccentBlue)
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
                contentDescription = "Back",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = when (phase) {
                RacePhase.ROLE_SELECTION -> "Multi-Device Timing"
                RacePhase.PAIRING -> "Device Pairing"
                RacePhase.SYNCING -> "Clock Sync"
                RacePhase.RACE_READY -> "Race Ready"
                RacePhase.ACTIVE_RACE -> "Active Race"
                RacePhase.RESULT -> "Result"
            },
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        Spacer(modifier = Modifier.weight(1f))

        // Phase indicator pill
        val phaseLabel = when (phase) {
            RacePhase.ROLE_SELECTION -> "Setup"
            RacePhase.PAIRING -> "Pairing"
            RacePhase.SYNCING -> "Syncing"
            RacePhase.RACE_READY -> "Ready"
            RacePhase.ACTIVE_RACE -> "Live"
            RacePhase.RESULT -> "Done"
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
// Phase 1: Role Selection
// =============================================================================

@Composable
private fun RoleSelectionContent(
    hasBluetoothPermission: Boolean,
    onRoleSelected: (DeviceRole) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Icon(
            imageVector = Icons.Outlined.Devices,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(56.dp)
        )

        Text(
            text = "Choose Your Role",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        Text(
            text = "Each phone acts as a timing gate. Choose which position this phone will be at.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Start Phone card
        RoleCard(
            role = DeviceRole.START,
            icon = Icons.Filled.PlayArrow,
            iconColor = AccentGreen,
            subtitle = "Detects when the runner leaves the start line. Acts as the reference clock.",
            enabled = hasBluetoothPermission,
            onClick = { onRoleSelected(DeviceRole.START) }
        )

        // Finish Phone card
        RoleCard(
            role = DeviceRole.FINISH,
            icon = Icons.Filled.Flag,
            iconColor = AccentRed,
            subtitle = "Detects when the runner crosses the finish line. Syncs clock to start phone.",
            enabled = hasBluetoothPermission,
            onClick = { onRoleSelected(DeviceRole.FINISH) }
        )

        if (!hasBluetoothPermission) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Bluetooth permissions required for device pairing",
                    color = AccentOrange,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // How it works section
        HowItWorksSection()
    }
}

@Composable
private fun RoleCard(
    role: DeviceRole,
    icon: ImageVector,
    iconColor: Color,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = role.label,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (enabled) TextPrimary else TextTertiary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) TextSecondary else TextTertiary
                )
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HowItWorksSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "How It Works",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        HowItWorksStep(
            number = "1",
            text = "Choose roles and pair devices via Bluetooth"
        )
        HowItWorksStep(
            number = "2",
            text = "Clocks sync automatically (< 5ms accuracy)"
        )
        HowItWorksStep(
            number = "3",
            text = "Point cameras at the track and start the session"
        )
        HowItWorksStep(
            number = "4",
            text = "Crossing detection triggers timing automatically"
        )
    }
}

@Composable
private fun HowItWorksStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(AccentBlue.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = AccentBlue,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Text(
            text = text,
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// =============================================================================
// Phase 2: Pairing
// =============================================================================

@Composable
private fun PairingContent(
    role: DeviceRole?,
    pairingStatus: String,
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
            text = if (role == DeviceRole.START) "Waiting for Connection" else "Searching for Start Phone",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Text(
            text = pairingStatus.ifEmpty {
                if (role == DeviceRole.START) {
                    "This phone is advertising as the reference clock. Start the other phone as 'Finish Phone' to connect."
                } else {
                    "Scanning for a Start Phone nearby..."
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // Role badge
        RoleBadge(role = role)

        Spacer(modifier = Modifier.weight(1f))

        // Cancel button
        PillButton(
            text = "Cancel",
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
        DeviceRole.START -> "Start Phone" to AccentGreen
        DeviceRole.FINISH -> "Finish Phone" to AccentRed
        null -> "Unknown" to TextSecondary
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
            text = "Synchronizing Clocks",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        Text(
            text = "${(progress * 100).toInt()}% complete",
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
            text = "Exchanging timing samples to calculate clock offset between devices...",
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
            text = "Cancel",
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
                .clip(RoundedCornerShape(16.dp))
                .border(2.dp, DarkGray, RoundedCornerShape(16.dp))
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
                    message = "Camera permission needed",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Text(
            text = "Position this phone at the ${uiState.role?.label?.lowercase() ?: "gate"} and point the camera at the track.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        // Start session button
        PillButton(
            text = "START SESSION",
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
        shape = RoundedCornerShape(16.dp),
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
                        text = "Clock Synced",
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
                DetailText(label = "Offset", value = "${String.format("%.2f", offsetMs)} ms")
                DetailText(label = "Uncertainty", value = "${String.format("%.2f", uncertaintyMs)} ms")
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
            text = "Distance",
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
                        text = "${distance.toInt()}m",
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
        SyncQuality.EXCELLENT -> "Excellent" to Color(0xFF30D158)
        SyncQuality.GOOD -> "Good" to Color(0xFF30D158)
        SyncQuality.FAIR -> "Fair" to AccentOrange
        SyncQuality.POOR -> "Poor" to AccentOrange
        SyncQuality.BAD -> "Bad" to AccentRed
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
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = 3.dp,
                    color = getDetectionBorderColor(uiState.detectionState),
                    shape = RoundedCornerShape(16.dp)
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
                    message = "Camera not available",
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
            Triple("HOLD STEADY", Icons.Filled.Vibration, AccentRed)
        detectionState == PhotoFinishDetector.State.ATHLETE_TOO_FAR ->
            Triple("TOO FAR", Icons.AutoMirrored.Filled.DirectionsRun, AccentOrange)
        detectionState == PhotoFinishDetector.State.TRIGGERED ->
            Triple("TRIGGERED", Icons.Filled.FiberManualRecord, AccentRed)
        detectionState == PhotoFinishDetector.State.COOLDOWN ->
            Triple("COOLDOWN", Icons.Filled.FiberManualRecord, AccentOrange)
        raceStatus.isNotEmpty() ->
            Triple(raceStatus.uppercase(), Icons.Filled.FiberManualRecord, AccentGreen)
        else ->
            Triple("READY", Icons.Filled.FiberManualRecord, AccentGreen)
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
                "${distanceMeters.toInt()}m"
            } else {
                "Waiting for crossing..."
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
            text = "Race Complete",
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
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ResultDetailRow(
                        label = "Distance",
                        value = "${uiState.distanceMeters.toInt()}m"
                    )

                    if (resultTime > 0) {
                        val speed = uiState.distanceMeters / resultTime
                        ResultDetailRow(
                            label = "Average Speed",
                            value = "${String.format("%.2f", speed)} m/s"
                        )
                    }

                    val uncertainty = uiState.resultUncertaintyMs
                    if (uncertainty != null) {
                        ResultDetailRow(
                            label = "Timing Uncertainty",
                            value = "+/- ${String.format("%.1f", uncertainty)} ms"
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
                                text = "Sync Quality",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            SyncQualityBadge(quality = syncQuality)
                        }
                    }

                    ResultDetailRow(
                        label = "Role",
                        value = uiState.role?.label ?: "Unknown"
                    )
                }
            }
        } else {
            Text(
                text = "No timing data recorded",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        PillButton(
            text = "New Race",
            backgroundColor = AccentGreen,
            onClick = onNewRace
        )

        PillButton(
            text = "Exit",
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
