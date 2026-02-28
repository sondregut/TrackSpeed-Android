package com.trackspeed.android.ui.screens.race

import android.Manifest
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
                RacePhase.SESSION_CODE -> SessionCodeContent(
                    isHosting = uiState.isHostingSession,
                    sessionCode = uiState.sessionCode,
                    pairingError = uiState.pairingError,
                    onJoinSession = viewModel::joinSession,
                    onClearError = viewModel::clearPairingError,
                    onCancel = viewModel::resetToRoleSelection
                )
                RacePhase.PAIRING -> PairingContent(
                    role = uiState.role,
                    pairingStatus = uiState.pairingStatus,
                    remoteDeviceName = uiState.remoteDeviceName,
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
                RacePhase.ROLE_SELECTION -> stringResource(R.string.race_phase_multi_device)
                RacePhase.SESSION_CODE -> stringResource(R.string.race_phase_session_code)
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

        // Phase indicator pill
        val phaseLabel = when (phase) {
            RacePhase.ROLE_SELECTION -> stringResource(R.string.race_pill_setup)
            RacePhase.SESSION_CODE -> stringResource(R.string.race_pill_code)
            RacePhase.PAIRING -> stringResource(R.string.race_pill_pairing)
            RacePhase.SYNCING -> stringResource(R.string.race_pill_syncing)
            RacePhase.RACE_READY -> stringResource(R.string.race_pill_ready)
            RacePhase.ACTIVE_RACE -> stringResource(R.string.race_pill_live)
            RacePhase.RESULT -> stringResource(R.string.race_pill_done)
        }
        val phaseColor = when (phase) {
            RacePhase.ACTIVE_RACE -> AccentRed
            RacePhase.RACE_READY -> AccentGreen
            RacePhase.SESSION_CODE -> AccentOrange
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
            text = stringResource(R.string.race_choose_role),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        Text(
            text = stringResource(R.string.race_choose_role_desc),
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
            subtitle = stringResource(R.string.race_start_subtitle),
            enabled = hasBluetoothPermission,
            onClick = { onRoleSelected(DeviceRole.START) }
        )

        // Finish Phone card
        RoleCard(
            role = DeviceRole.FINISH,
            icon = Icons.Filled.Flag,
            iconColor = AccentRed,
            subtitle = stringResource(R.string.race_finish_subtitle),
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
                    text = stringResource(R.string.race_bluetooth_required),
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
                    text = when (role) {
                        DeviceRole.START -> stringResource(R.string.race_role_start)
                        DeviceRole.FINISH -> stringResource(R.string.race_role_finish)
                    },
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
            text = stringResource(R.string.race_how_it_works),
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        HowItWorksStep(
            number = "1",
            text = stringResource(R.string.race_step_1)
        )
        HowItWorksStep(
            number = "2",
            text = stringResource(R.string.race_step_2)
        )
        HowItWorksStep(
            number = "3",
            text = stringResource(R.string.race_step_3)
        )
        HowItWorksStep(
            number = "4",
            text = stringResource(R.string.race_step_4)
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
// Phase 1.5: Session Code
// =============================================================================

@Composable
private fun SessionCodeContent(
    isHosting: Boolean,
    sessionCode: String,
    pairingError: String?,
    onJoinSession: (String) -> Unit,
    onClearError: () -> Unit,
    onCancel: () -> Unit
) {
    if (isHosting) {
        HostSessionCodeContent(
            sessionCode = sessionCode,
            onCancel = onCancel
        )
    } else {
        JoinSessionCodeContent(
            pairingError = pairingError,
            onJoinSession = onJoinSession,
            onClearError = onClearError,
            onCancel = onCancel
        )
    }
}

@Composable
private fun HostSessionCodeContent(
    sessionCode: String,
    onCancel: () -> Unit
) {
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Pulsing animation for waiting indicator
    val infiniteTransition = rememberInfiniteTransition(label = "waiting")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dots"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Icon
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(AccentBlue.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(32.dp)
            )
        }

        Text(
            text = stringResource(R.string.race_your_session_code),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        Text(
            text = stringResource(R.string.race_share_this_code),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        // Large code display
        if (sessionCode.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(16.dp))
                    .padding(vertical = 24.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sessionCode.forEach { digit ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(DarkGray, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = digit.toString(),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        } else {
            // Loading state
            CircularProgressIndicator(
                color = AccentBlue,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = stringResource(R.string.race_generating_code),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        // Waiting indicator
        Text(
            text = stringResource(R.string.race_waiting_for_partner),
            style = MaterialTheme.typography.bodyMedium,
            color = AccentBlue.copy(alpha = dotAlpha)
        )

        Spacer(modifier = Modifier.weight(1f))

        // Share Code button
        if (sessionCode.isNotEmpty()) {
            PillButton(
                text = stringResource(R.string.race_share_code),
                backgroundColor = AccentBlue,
                onClick = {
                    clipboardManager.setText(AnnotatedString(sessionCode))
                    Toast.makeText(
                        context,
                        context.getString(R.string.race_code_copied),
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
private fun JoinSessionCodeContent(
    pairingError: String?,
    onJoinSession: (String) -> Unit,
    onClearError: () -> Unit,
    onCancel: () -> Unit
) {
    var codeDigits by remember { mutableStateOf(List(6) { "" }) }
    val focusRequesters = remember { List(6) { FocusRequester() } }

    // Auto-focus first field
    LaunchedEffect(Unit) {
        focusRequesters[0].requestFocus()
    }

    val fullCode = codeDigits.joinToString("")
    val isCodeComplete = fullCode.length == 6

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Icon
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(AccentOrange.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Dialpad,
                contentDescription = null,
                tint = AccentOrange,
                modifier = Modifier.size(32.dp)
            )
        }

        Text(
            text = stringResource(R.string.race_enter_session_code),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        Text(
            text = stringResource(R.string.race_enter_code_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        // OTP-style 6-digit input
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            for (i in 0 until 6) {
                OutlinedTextField(
                    value = codeDigits[i],
                    onValueChange = { value ->
                        // Clear error on input
                        if (pairingError != null) onClearError()

                        // Only accept single digit
                        val filtered = value.filter { it.isDigit() }.take(1)
                        val newDigits = codeDigits.toMutableList()
                        newDigits[i] = filtered
                        codeDigits = newDigits

                        // Auto-advance to next field
                        if (filtered.isNotEmpty() && i < 5) {
                            focusRequesters[i + 1].requestFocus()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequesters[i])
                        .onKeyEvent { event ->
                            // Handle backspace to go to previous field
                            if (event.key == Key.Backspace && codeDigits[i].isEmpty() && i > 0) {
                                val newDigits = codeDigits.toMutableList()
                                newDigits[i - 1] = ""
                                codeDigits = newDigits
                                focusRequesters[i - 1].requestFocus()
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        fontFamily = FontFamily.Monospace
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = DarkGray,
                        focusedContainerColor = CardBackground,
                        unfocusedContainerColor = CardBackground,
                        errorBorderColor = AccentRed
                    ),
                    isError = pairingError != null,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        // Error display
        if (pairingError != null) {
            Text(
                text = pairingError,
                color = AccentRed,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Join Session button
        PillButton(
            text = stringResource(R.string.race_join_session),
            backgroundColor = if (isCodeComplete) AccentGreen else DarkGray,
            onClick = {
                if (isCodeComplete) {
                    onJoinSession(fullCode)
                }
            }
        )

        // Cancel button
        PillButton(
            text = stringResource(R.string.race_cancel),
            backgroundColor = DarkGray,
            onClick = onCancel
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
    remoteDeviceName: String? = null,
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
            text = if (role == DeviceRole.START) stringResource(R.string.race_pairing_waiting) else stringResource(R.string.race_pairing_searching),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Text(
            text = if (pairingStatus.isEmpty()) {
                if (role == DeviceRole.START) {
                    stringResource(R.string.race_pairing_start_desc)
                } else {
                    stringResource(R.string.race_pairing_finish_desc)
                }
            } else {
                pairingStatus
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

        // Cloud-only warning
        if (uiState.isCloudOnlyMode) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AccentOrange.copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = AccentOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.race_cloud_only_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentOrange
                    )
                }
            }
        }

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
