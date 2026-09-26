package com.trackspeed.android.ui.screens.race

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.R
import com.trackspeed.android.audio.AudioStartTiming
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.detection.PhotoFinishDetector
import com.trackspeed.android.model.StartSoundType
import com.trackspeed.android.model.StartType
import com.trackspeed.android.protocol.SegmentSplit
import com.trackspeed.android.protocol.TimingRole
import com.trackspeed.android.sync.SyncQuality
import com.trackspeed.android.ui.components.CameraPreview
import com.trackspeed.android.ui.components.CameraPreviewPlaceholder
import com.trackspeed.android.ui.components.CountdownOverlay
import com.trackspeed.android.ui.components.DetectionReviewSubmission
import com.trackspeed.android.ui.components.DetectionReviewTarget
import com.trackspeed.android.ui.components.ExpandedThumbnail
import com.trackspeed.android.ui.components.StartMode
import com.trackspeed.android.ui.components.ThumbnailViewerDialog
import com.trackspeed.android.ui.components.TimingSessionEndOverlay
import com.trackspeed.android.ui.components.TouchStartOverlay
import com.trackspeed.android.ui.components.VoiceStartOverlay
import com.trackspeed.android.ui.components.VoiceStartOverlaySettings
import com.trackspeed.android.ui.components.VoiceStartOverlaySettingsActions
import com.trackspeed.android.ui.screens.settings.applyLanguage
import com.trackspeed.android.ui.theme.*
import com.trackspeed.android.ui.util.formatDistance
import com.trackspeed.android.ui.util.formatSpeed
import com.trackspeed.android.ui.util.formatSplitDuration
import com.trackspeed.android.ui.util.localizedDisplayName
import com.trackspeed.android.ui.util.localizedShortName
import com.trackspeed.android.ui.util.parseSegmentSplits
import com.trackspeed.android.ui.util.parseAthleteColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Dark theme color constants using new LaserSpeed theme
private val AccentRed = Color(0xFFFF453A)
private val AccentOrange = Color(0xFFFF9F0A)

@Composable
fun RaceModeScreen(
    onNavigateBack: () -> Unit,
    onViewSession: (String) -> Unit,
    viewModel: RaceModeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_STOP -> viewModel.saveActiveSessionSnapshot()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // BLE permission launcher
    var hasBluetoothPermission by remember { mutableStateOf(false) }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasBluetoothPermission = requiredBlePermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
        viewModel.onBluetoothPermissionResult(hasBluetoothPermission)
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
        val blePermissions = requiredBlePermissions()
        val permissions = blePermissions + optionalWifiAwarePermissions(context)
        hasBluetoothPermission = blePermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        val missingPermissions = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            viewModel.onBluetoothPermissionResult(granted = true)
        } else {
            blePermissionLauncher.launch(missingPermissions.toTypedArray())
        }
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
            // Active timing has its own compact iOS-parity session header.
            // Setup phases keep the navigational title bar.
            if (uiState.phase != RacePhase.ACTIVE_RACE) {
                RaceTopBar(
                    phase = uiState.phase,
                    connectedDeviceCount = uiState.connectedDeviceCount,
                    isDeviceConnected = uiState.isDeviceConnected,
                    onBack = {
                        if (uiState.phase == RacePhase.PAIRING) {
                            viewModel.resetToStart()
                            onNavigateBack()
                        } else {
                            viewModel.presentSessionEndConfirmation()
                        }
                    }
                )
            }

            // Phase content
            when (uiState.phase) {
                RacePhase.PAIRING -> PairingContent(
                    pairingStatus = uiState.pairingStatus,
                    connectedDeviceCount = uiState.connectedDeviceCount,
                    syncedDeviceCount = uiState.syncedDeviceCount,
                    numberOfGates = uiState.numberOfGates,
                    requiredDeviceCount = uiState.requiredPhysicalDeviceCount,
                    isJoinMode = uiState.isJoinMode,
                    isHostingSession = uiState.isHostingSession,
                    hostRole = uiState.hostRole,
                    onConfirm = { viewModel.confirmPairing() },
                    onCancel = {
                        viewModel.resetToStart()
                        onNavigateBack()
                    }
                )
                RacePhase.SYNCING -> SyncingContent(
                    progress = uiState.syncProgress,
                    quality = uiState.syncQuality,
                    isDeviceConnected = uiState.isDeviceConnected,
                    onCancel = viewModel::endSessionAndReset
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
                    onUpdateRunDistance = viewModel::updateRunDistance,
                    onDeleteRun = viewModel::deleteRun,
                    onReviewSubmitted = viewModel::submitCrossingReview,
                    onExit = viewModel::presentSessionEndConfirmation
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

        uiState.sessionEndPresentation?.let { presentation ->
            TimingSessionEndOverlay(
                presentation = presentation,
                onCancel = viewModel::cancelSessionEndConfirmation,
                onConfirm = viewModel::confirmSessionEnd,
                onViewSession = { savedSessionId ->
                    viewModel.dismissSessionEndSummary()
                    onViewSession(savedSessionId)
                },
                onDone = {
                    viewModel.dismissSessionEndSummary()
                    onNavigateBack()
                }
            )
        }
    }
}

private fun requiredBlePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun optionalWifiAwarePermissions(context: Context): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    ) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        emptyArray()
    }
}

// =============================================================================
// Top Bar
// =============================================================================

@Composable
private fun RaceTopBar(
    phase: RacePhase,
    connectedDeviceCount: Int = 0,
    isDeviceConnected: Boolean = false,
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
                .background(BorderSubtle, CircleShape)
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
                    text = pluralStringResource(
                        R.plurals.race_connected_count,
                        connectedDeviceCount,
                        connectedDeviceCount
                    ),
                    color = AccentGreen,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Phase indicator pill
        val phaseLabel = when {
            phase == RacePhase.SYNCING && isDeviceConnected -> {
                stringResource(R.string.race_pill_connected)
            }
            phase == RacePhase.PAIRING -> stringResource(R.string.race_pill_pairing)
            phase == RacePhase.SYNCING -> stringResource(R.string.race_pill_syncing)
            phase == RacePhase.RACE_READY -> stringResource(R.string.race_pill_ready)
            phase == RacePhase.ACTIVE_RACE -> stringResource(R.string.race_pill_live)
            else -> stringResource(R.string.race_pill_done)
        }
        val phaseColor = when {
            phase == RacePhase.SYNCING && isDeviceConnected -> AccentGreen
            phase == RacePhase.ACTIVE_RACE -> AccentRed
            phase == RacePhase.RACE_READY -> AccentGreen
            phase == RacePhase.RESULT -> AccentBlue
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
private fun ConnectionHelpDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBackground,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = stringResource(R.string.race_connection_help_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionHelpTip(stringResource(R.string.race_connection_help_tip1))
                ConnectionHelpTip(stringResource(R.string.race_connection_help_tip2))
                ConnectionHelpTip(stringResource(R.string.race_connection_help_tip3))
                ConnectionHelpTip(stringResource(R.string.race_connection_help_tip4))
                ConnectionHelpTip(stringResource(R.string.race_connection_help_tip5))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.race_connection_help_close),
                    color = AccentBlue,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
private fun ConnectionHelpTip(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Filled.FiberManualRecord,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier
                .size(8.dp)
                .offset(y = 6.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun PairingContent(
    pairingStatus: String,
    connectedDeviceCount: Int,
    syncedDeviceCount: Int,
    numberOfGates: Int,
    requiredDeviceCount: Int,
    isJoinMode: Boolean,
    isHostingSession: Boolean,
    hostRole: TimingRole,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var showHelpDialog by remember { mutableStateOf(false) }
    val hasRequiredConnections = connectedDeviceCount >= requiredDeviceCount
    val hasRequiredDevices = pairingHasRequiredReadyDevices(
        connectedDeviceCount = connectedDeviceCount,
        syncedDeviceCount = syncedDeviceCount,
        requiredDeviceCount = requiredDeviceCount
    )
    val isControlOnlyHost = hostRole == TimingRole.CONTROL_ONLY
    val displayedConnectedDeviceCount = if (isHostingSession) {
        connectedDeviceCount.coerceAtLeast(1)
    } else {
        connectedDeviceCount
    }

    if (showHelpDialog) {
        ConnectionHelpDialog(onDismiss = { showHelpDialog = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Scanning animation with help button
        Box(contentAlignment = Alignment.TopEnd) {
            ScanningAnimation()

            IconButton(
                onClick = { showHelpDialog = true },
                modifier = Modifier
                    .size(32.dp)
                    .offset(x = 24.dp, y = (-8).dp)
                    .background(BorderSubtle, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.race_connection_help_cd),
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Text(
            text = if (hasRequiredDevices) {
                stringResource(R.string.race_pairing_ready_to_time)
            } else if (hasRequiredConnections) {
                stringResource(R.string.race_pairing_synchronizing_phones)
            } else if (isJoinMode) {
                stringResource(R.string.race_join_session)
            } else if (isHostingSession) {
                stringResource(R.string.race_pairing_create_session)
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
            text = if (hasRequiredDevices) {
                pluralStringResource(
                    R.plurals.race_pairing_connected_ready,
                    displayedConnectedDeviceCount,
                    displayedConnectedDeviceCount
                )
            } else if (hasRequiredConnections) {
                stringResource(
                    R.string.race_pairing_synchronized_fraction,
                    syncedDeviceCount.coerceAtMost(requiredDeviceCount),
                    requiredDeviceCount
                )
            } else if (isJoinMode) {
                stringResource(R.string.race_pairing_open_host)
            } else if (isHostingSession) {
                val neededPhones = if (isControlOnlyHost) {
                    numberOfGates.coerceAtLeast(2)
                } else {
                    (numberOfGates - 1).coerceAtLeast(1)
                }
                if (neededPhones == 1) {
                    stringResource(R.string.race_pairing_join_other_no_account)
                } else if (isControlOnlyHost) {
                    stringResource(R.string.race_pairing_join_each_timing)
                } else {
                    stringResource(R.string.race_pairing_join_each_timing)
                }
            } else if (connectedDeviceCount > 0) {
                stringResource(
                    R.string.race_pairing_connected_fraction,
                    connectedDeviceCount,
                    requiredDeviceCount
                )
            } else {
                stringResource(R.string.race_pairing_auto_discover, requiredDeviceCount)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (displayedConnectedDeviceCount > 0) AccentGreen else TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (isJoinMode) {
            JoinSessionStatusCard(pairingStatus = pairingStatus)
        }

        if (isHostingSession) {
            PairingStatusCard(
                displayedConnectedDeviceCount = displayedConnectedDeviceCount,
                requiredDeviceCount = requiredDeviceCount,
                hostRole = hostRole
            )

            GateLineupCard(
                connectedDeviceCount = displayedConnectedDeviceCount,
                requiredDeviceCount = requiredDeviceCount,
                hostRole = hostRole
            )
        }

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

        // Start timing button appears when the configured gate count is connected.
        if (hasRequiredDevices) {
            PillButton(
                text = stringResource(R.string.race_start_timing),
                backgroundColor = AccentGreen,
                onClick = onConfirm
            )
        }

        // Cancel button
        PillButton(
            text = stringResource(R.string.race_cancel),
            backgroundColor = BorderSubtle,
            onClick = onCancel
        )
    }
}

@Composable
private fun JoinSessionStatusCard(pairingStatus: String) {
    val isSearching = pairingStatus.isNotBlank()
    val title = stringResource(
        if (isSearching) R.string.race_join_scanning_title else R.string.race_join_ready_title
    )
    val detail = if (isSearching) {
        stringResource(R.string.race_join_scanning_detail)
    } else {
        stringResource(R.string.race_join_ready_detail)
    }
    val hostDetail = if (isSearching) {
        stringResource(R.string.race_join_host_scanning_detail)
    } else {
        stringResource(R.string.race_join_host_ready_detail)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, AccentOrange.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AccentOrange.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = AccentOrange,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (isSearching) R.string.race_join_searching else R.string.race_join_session_upper
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = AccentOrange
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(
                            if (isSearching) R.string.race_waiting else R.string.race_bluetooth
                        ),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = stringResource(
                            if (isSearching) R.string.race_status_upper else R.string.race_mode_upper
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TextTertiary
                    )
                }
            }

            if (isSearching) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = AccentOrange,
                    trackColor = BorderSubtle
                )
            }

            JoinSessionInfoList(
                rows = listOf(
                    JoinSessionInfo(
                        icon = Icons.Outlined.PhoneAndroid,
                        title = stringResource(R.string.race_host_phone),
                        detail = hostDetail
                    ),
                    JoinSessionInfo(
                        icon = Icons.Filled.Bluetooth,
                        title = stringResource(R.string.race_this_phone),
                        detail = stringResource(R.string.race_bluetooth_both_phones)
                    )
                )
            )
        }
    }
}

private data class JoinSessionInfo(
    val icon: ImageVector,
    val title: String,
    val detail: String
)

@Composable
private fun JoinSessionInfoList(rows: List<JoinSessionInfo>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark.copy(alpha = 0.58f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = row.icon,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(20.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )
                    Text(
                        text = row.detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        lineHeight = 17.sp
                    )
                }
            }

            if (index < rows.lastIndex) {
                HorizontalDivider(
                    color = BorderSubtle,
                    modifier = Modifier.padding(start = 30.dp)
                )
            }
        }
    }
}

@Composable
private fun PairingStatusCard(
    displayedConnectedDeviceCount: Int,
    requiredDeviceCount: Int,
    hostRole: TimingRole
) {
    val requiredJoiners = (requiredDeviceCount - 1).coerceAtLeast(1)
    val connectedJoiners = (displayedConnectedDeviceCount - 1).coerceIn(0, requiredJoiners)
    val missingJoiners = (requiredJoiners - connectedJoiners).coerceAtLeast(0)
    val isReady = missingJoiners == 0
    val accent = if (isReady) AccentGreen else AccentBlue
    val title = when {
        missingJoiners > 0 -> pluralStringResource(
            R.plurals.race_pairing_waiting_more,
            missingJoiners,
            missingJoiners
        )
        else -> stringResource(R.string.race_pairing_phones_connected)
    }
    val detail = if (isReady) {
        stringResource(R.string.race_pairing_roles_assigned)
    } else if (hostRole == TimingRole.CONTROL_ONLY) {
        stringResource(R.string.race_pairing_join_each_timing)
    } else {
        stringResource(R.string.race_pairing_join_other)
    }
    val progress = connectedJoiners.toFloat() / requiredJoiners.toFloat()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isReady) Icons.Outlined.CheckCircle else Icons.Outlined.PhoneAndroid,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(
                                if (isReady) R.string.race_ready else R.string.race_connect
                            ),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = accent
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isReady) {
                            stringResource(R.string.race_ready)
                        } else {
                            stringResource(R.string.race_ready_fraction, connectedJoiners, requiredJoiners)
                        },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = accent
                    )
                    Text(
                        text = stringResource(
                            if (isReady) R.string.race_status_upper else R.string.race_phones_upper
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TextTertiary
                    )
                }
            }

            LinearProgressIndicator(
                progress = { if (isReady) 1f else progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = accent,
                trackColor = BorderSubtle
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PairingMetricChip(
                    icon = Icons.Outlined.PhoneAndroid,
                    text = stringResource(
                        R.string.race_phone_fraction,
                        displayedConnectedDeviceCount,
                        requiredDeviceCount
                    ),
                    color = if (displayedConnectedDeviceCount >= requiredDeviceCount) AccentGreen else TextSecondary
                )
                PairingMetricChip(
                    icon = Icons.Filled.Bluetooth,
                    text = stringResource(R.string.onboarding_multidevice_bluetooth),
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun PairingMetricChip(
    icon: ImageVector,
    text: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.11f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun GateLineupCard(
    connectedDeviceCount: Int,
    requiredDeviceCount: Int,
    hostRole: TimingRole
) {
    val requiredJoiners = (requiredDeviceCount - 1).coerceAtLeast(1)
    val connectedJoiners = (connectedDeviceCount - 1).coerceIn(0, requiredJoiners)
    val hostDeviceRole = if (hostRole == TimingRole.CONTROL_ONLY) DeviceRole.CONTROL else DeviceRole.FINISH

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.race_gate_lineup),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    ),
                    color = TextTertiary
                )
                Text(
                    text = stringResource(
                        R.string.race_phone_fraction,
                        connectedDeviceCount,
                        requiredDeviceCount
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connectedDeviceCount >= requiredDeviceCount) AccentGreen else TextSecondary
                )
            }

            GateLineupRow(
                role = hostDeviceRole,
                deviceName = stringResource(R.string.race_this_phone),
                status = stringResource(R.string.race_ready),
                isWaiting = false,
                isHost = true
            )

            repeat(connectedJoiners) { index ->
                GateLineupRow(
                    role = roleForTimingPhone(index, requiredJoiners, hostRole),
                    deviceName = if (connectedJoiners == 1) {
                        stringResource(R.string.race_timing_phone)
                    } else {
                        stringResource(R.string.race_timing_phone_number, index + 1)
                    },
                    status = stringResource(R.string.race_connected),
                    isWaiting = false,
                    isHost = false
                )
            }

            repeat(requiredJoiners - connectedJoiners) { offset ->
                val index = connectedJoiners + offset
                GateLineupRow(
                    role = roleForTimingPhone(index, requiredJoiners, hostRole),
                    deviceName = null,
                    status = stringResource(R.string.race_waiting),
                    isWaiting = true,
                    isHost = false
                )
            }
        }
    }
}

@Composable
private fun GateLineupRow(
    role: DeviceRole,
    deviceName: String?,
    status: String,
    isWaiting: Boolean,
    isHost: Boolean
) {
    val color = colorForLineupRole(role)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isWaiting) SurfaceDark.copy(alpha = 0.45f) else SurfaceDark)
            .border(
                width = 1.dp,
                color = if (isWaiting) BorderSubtle else color.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (isWaiting) 0.10f else 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (role == DeviceRole.CONTROL) Icons.Outlined.Settings else Icons.Outlined.PhoneAndroid,
                contentDescription = null,
                tint = if (isWaiting) color.copy(alpha = 0.70f) else color,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = roleLabel(role),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (isWaiting) TextSecondary else TextPrimary
            )
            Text(
                text = deviceName ?: stringResource(R.string.race_open_join_instruction),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1
            )
        }

        Text(
            text = if (isHost) stringResource(R.string.race_host) else status,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = when {
                isWaiting -> TextTertiary
                isHost -> AccentBlue
                else -> AccentGreen
            }
        )
    }
}

private fun roleForTimingPhone(
    joinerIndex: Int,
    requiredJoiners: Int,
    hostRole: TimingRole
): DeviceRole {
    return when {
        joinerIndex == 0 -> DeviceRole.START
        hostRole == TimingRole.CONTROL_ONLY && joinerIndex == requiredJoiners - 1 -> DeviceRole.FINISH
        else -> DeviceRole.LAP
    }
}

@Composable
private fun colorForLineupRole(role: DeviceRole): Color = when (role) {
    DeviceRole.START -> AccentGreen
    DeviceRole.LAP -> AccentOrange
    DeviceRole.FINISH -> AccentRed
    DeviceRole.CONTROL -> AccentBlue
}

@Composable
private fun roleLabel(role: DeviceRole): String = when (role) {
    DeviceRole.START -> stringResource(R.string.race_role_start)
    DeviceRole.LAP -> stringResource(R.string.device_role_split)
    DeviceRole.FINISH -> stringResource(R.string.race_role_finish)
    DeviceRole.CONTROL -> stringResource(R.string.setup_control)
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

    val accentBlueColor = AccentBlue
    Box(
        modifier = Modifier.size(120.dp),
        contentAlignment = Alignment.Center
    ) {
        // Animated rings
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = accentBlueColor.copy(alpha = alpha1),
                radius = 30.dp.toPx() * scale1,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = accentBlueColor.copy(alpha = alpha2),
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
    val label: String
    val color: Color
    val icon: ImageVector
    when (role) {
        DeviceRole.START -> {
            label = stringResource(R.string.race_role_start)
            color = AccentGreen
            icon = Icons.Filled.Flag
        }
        DeviceRole.LAP -> {
            label = stringResource(R.string.device_role_split)
            color = AccentOrange
            icon = Icons.Filled.Timer
        }
        DeviceRole.FINISH -> {
            label = stringResource(R.string.race_role_finish)
            color = AccentRed
            icon = Icons.Filled.SportsScore
        }
        DeviceRole.CONTROL -> {
            label = stringResource(R.string.setup_control)
            color = AccentBlue
            icon = Icons.Filled.Tune
        }
        null -> {
            label = stringResource(R.string.race_role_unknown)
            color = TextSecondary
            icon = Icons.Filled.PhoneAndroid
        }
    }

    Row(
        modifier = Modifier
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold
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
    isDeviceConnected: Boolean,
    onCancel: () -> Unit
) {
    val isClockSynced = quality != null
    val progressColor = if (isClockSynced) AccentGreen else AccentBlue
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(
                        color = if (isDeviceConnected) AccentGreen else AccentOrange,
                        shape = CircleShape
                    )
            )
            Text(
                text = stringResource(
                    if (isDeviceConnected) {
                        R.string.race_bluetooth_connected
                    } else {
                        R.string.race_bluetooth_reconnecting
                    }
                ),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (isDeviceConnected) AccentGreen else AccentOrange
            )
        }

        Text(
            text = stringResource(
                if (isDeviceConnected) {
                    R.string.race_measuring_clock_accuracy
                } else {
                    R.string.race_bluetooth_reconnecting_detail
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )

        // Sync icon with progress ring
        Box(
            modifier = Modifier.size(100.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { if (isClockSynced) 1f else progress },
                modifier = Modifier.fillMaxSize(),
                color = progressColor,
                trackColor = BorderSubtle,
                strokeWidth = 6.dp
            )

            Icon(
                imageVector = if (isClockSynced) Icons.Filled.CheckCircle else Icons.Filled.Sync,
                contentDescription = null,
                tint = progressColor,
                modifier = Modifier.size(36.dp)
            )
        }

        Text(
            text = stringResource(
                if (isClockSynced) R.string.race_clock_synced else R.string.race_syncing_title
            ),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )

        Text(
            text = stringResource(
                R.string.race_syncing_percent,
                if (isClockSynced) 100 else (progress * 100).toInt()
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary
        )

        Text(
            text = stringResource(
                if (isClockSynced) {
                    R.string.race_waiting_host_start
                } else if (progress > 0f) {
                    R.string.race_syncing_receiving_sample
                } else {
                    R.string.race_syncing_waiting_sample
                }
            ),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (isClockSynced || progress > 0f) AccentGreen else TextTertiary,
            textAlign = TextAlign.Center
        )

        // Progress bar
        LinearProgressIndicator(
            progress = { if (isClockSynced) 1f else progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = progressColor,
            trackColor = BorderSubtle
        )

        Text(
            text = stringResource(
                if (isClockSynced) {
                    R.string.race_clock_synced_waiting_detail
                } else {
                    R.string.race_syncing_desc
                }
            ),
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
            backgroundColor = BorderSubtle,
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
    val selectedStartMode = StartMode.fromString(uiState.startType)
    if (uiState.requiresLocalCamera && !uiState.isLocalGateReady) {
        RaceReadyCameraAlignment(
            uiState = uiState,
            onSurfaceReady = viewModel::onSurfaceReady,
            onSurfaceDestroyed = viewModel::onSurfaceDestroyed,
            onConfirmAlignment = viewModel::beginLocalGateCalibration
        )
    } else {
        RaceReadyWaitingContent(
            uiState = uiState,
            startMode = selectedStartMode,
            onStartSession = onStartSession
        )
    }
}

@Composable
private fun RaceReadyCameraAlignment(
    uiState: RaceModeUiState,
    onSurfaceReady: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onConfirmAlignment: () -> Unit
) {
    val cameraCapturing = uiState.cameraState is CameraManager.CameraState.Capturing
    val cameraAvailable = uiState.hasPermission &&
        uiState.cameraState !is CameraManager.CameraState.Error
    val isSteady = uiState.detectionState != PhotoFinishDetector.State.UNSTABLE
    val instructionColor = when {
        uiState.isLocalGateCalibrating -> AccentOrange
        isSteady -> AccentGreen
        else -> AccentRed
    }
    val instruction = when {
        uiState.isLocalGateCalibrating -> stringResource(R.string.race_alignment_checking)
        isSteady -> stringResource(R.string.race_align_camera_confirm)
        else -> stringResource(R.string.race_align_camera_hold)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (cameraAvailable) {
            CameraPreview(
                gatePosition = uiState.gatePosition,
                onGatePositionChanged = {},
                gateLineDraggable = false,
                showDiagnostics = false,
                fps = uiState.fps,
                detectionState = uiState.detectionState,
                sensorOrientation = uiState.sensorOrientation,
                isFrontCamera = uiState.isFrontCamera,
                previewWidth = uiState.previewWidth,
                previewHeight = uiState.previewHeight,
                onSurfaceReady = onSurfaceReady,
                onSurfaceDestroyed = onSurfaceDestroyed,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CameraPreviewPlaceholder(
                message = stringResource(R.string.race_camera_permission_needed),
                modifier = Modifier.fillMaxSize()
            )
        }

        Text(
            text = instruction,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(instructionColor.copy(alpha = 0.94f))
                .padding(horizontal = 20.dp, vertical = 14.dp)
        )

        RaceReadyRolePill(
            role = uiState.role,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 64.dp)
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (cameraCapturing && !uiState.isLocalGateCalibrating) AccentBlue
                        else BorderSubtle
                    )
                    .clickable(
                        enabled = cameraCapturing && !uiState.isLocalGateCalibrating,
                        onClick = onConfirmAlignment
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.isLocalGateCalibrating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = TextSecondary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (cameraCapturing) Color.White else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = when {
                            uiState.isLocalGateCalibrating -> stringResource(R.string.race_alignment_checking)
                            cameraCapturing -> stringResource(R.string.race_alignment_confirm_action)
                            else -> stringResource(R.string.race_alignment_preparing)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (cameraCapturing && !uiState.isLocalGateCalibrating) {
                            Color.White
                        } else {
                            TextSecondary
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RaceReadyWaitingContent(
    uiState: RaceModeUiState,
    startMode: StartMode,
    onStartSession: () -> Unit
) {
    val requiredRemoteGates = uiState.requiredRemoteReadyGateCount.coerceAtLeast(0)
    val readyRemoteGates = uiState.remoteArmedGateIds.size.coerceAtMost(requiredRemoteGates)
    val canStartSession = uiState.canStartSession
    val title = when {
        uiState.isHostingSession && canStartSession -> stringResource(R.string.race_ready_to_start)
        uiState.isHostingSession -> stringResource(R.string.race_waiting_for_phones)
        else -> stringResource(R.string.race_waiting_for_host)
    }
    val detail = when {
        uiState.isHostingSession && canStartSession -> stringResource(R.string.race_all_gates_ready_detail)
        uiState.isHostingSession -> stringResource(R.string.race_waiting_remote_aligned_detail)
        else -> stringResource(R.string.race_waiting_host_aligned_detail)
    }
    val accent = if (canStartSession || !uiState.isHostingSession) AccentGreen else AccentOrange

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (canStartSession || !uiState.isHostingSession) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.Outlined.PhoneAndroid
                },
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 23.sp,
            modifier = Modifier.widthIn(max = 380.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.isHostingSession && requiredRemoteGates > 0) {
            LinearProgressIndicator(
                progress = {
                    if (requiredRemoteGates == 0) 1f
                    else readyRemoteGates.toFloat() / requiredRemoteGates.toFloat()
                },
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = accent,
                trackColor = BorderSubtle
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.race_gates_ready_count,
                    readyRemoteGates,
                    requiredRemoteGates
                ),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        } else if (!uiState.isHostingSession) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = AccentOrange,
                strokeWidth = 2.5.dp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        RaceReadySessionSummary(uiState = uiState, startMode = startMode)

        if (uiState.isHostingSession) {
            Spacer(modifier = Modifier.height(32.dp))
            PillButton(
                text = stringResource(R.string.race_start_session),
                backgroundColor = if (canStartSession) AccentGreen else BorderSubtle,
                contentColor = if (canStartSession) Color.White else TextSecondary,
                onClick = onStartSession,
                enabled = canStartSession
            )
        }
    }
}

@Composable
private fun RaceReadyRolePill(
    role: DeviceRole?,
    modifier: Modifier = Modifier
) {
    val roleText = when (role) {
        DeviceRole.START -> stringResource(R.string.race_start_gate)
        DeviceRole.LAP -> stringResource(R.string.race_split_gate_title)
        DeviceRole.FINISH -> stringResource(R.string.race_finish_gate)
        DeviceRole.CONTROL -> stringResource(R.string.race_control_only)
        null -> stringResource(R.string.race_assigned_gate)
    }
    Text(
        text = roleText,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun RaceReadySessionSummary(
    uiState: RaceModeUiState,
    startMode: StartMode
) {
    val roleText = when (uiState.role) {
        DeviceRole.START -> stringResource(R.string.race_start_gate)
        DeviceRole.LAP -> stringResource(R.string.race_split_gate_title)
        DeviceRole.FINISH -> stringResource(R.string.race_finish_gate)
        DeviceRole.CONTROL -> stringResource(R.string.race_control_only)
        null -> stringResource(R.string.race_assigned_gate)
    }
    Text(
        text = listOf(
            roleText,
            formatDistance(uiState.distanceMeters),
            stringResource(startMode.displayNameRes)
        ).joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = TextTertiary,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ActiveAthleteChipBar(
    uiState: RaceModeUiState,
    onActiveAthleteSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!uiState.isHostingSession) return
    val athletes = uiState.sessionAthletesForUi()
    if (athletes.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.race_active_athlete),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            athletes.forEach { athlete ->
                ActiveAthleteChip(
                    athlete = athlete,
                    selected = uiState.activeAthleteId == athlete.id,
                    onClick = { onActiveAthleteSelected(athlete.id) }
                )
            }
        }
    }
}

@Composable
private fun ActiveAthleteChip(
    athlete: AthleteEntity,
    selected: Boolean,
    onClick: () -> Unit
) {
    val athleteColor = parseAthleteColor(athlete.color)
    Row(
        modifier = Modifier
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) athleteColor.copy(alpha = 0.18f) else BorderSubtle)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) athleteColor else BorderSubtle,
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(athleteColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = athlete.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
        Text(
            text = athlete.displayName,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
            ),
            color = if (selected) TextPrimary else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 132.dp)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = athleteColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun RaceModeUiState.sessionAthletesForUi(): List<AthleteEntity> {
    return athletes.filter { it.id in selectedAthleteIds }
}

@Composable
private fun GateReadinessCard(uiState: RaceModeUiState) {
    val requiredRemoteGates = uiState.requiredRemoteReadyGateCount.coerceAtLeast(1)
    val readyRemoteGates = uiState.remoteArmedGateIds.size.coerceAtMost(requiredRemoteGates)
    val localReady = uiState.isLocalGateReady
    val allReady = uiState.canStartSession
    val accent = if (allReady) AccentGreen else AccentBlue

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accent.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.race_gate_readiness),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextTertiary
                    )
                    Text(
                        text = stringResource(
                            if (allReady) R.string.race_all_gates_armed else R.string.race_waiting_armed_gates
                        ),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                Text(
                    text = stringResource(
                        R.string.race_ready_fraction,
                        readyRemoteGates,
                        requiredRemoteGates
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = accent
                )
            }

            LinearProgressIndicator(
                progress = { readyRemoteGates.toFloat() / requiredRemoteGates.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = accent,
                trackColor = BorderSubtle
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PairingMetricChip(
                    icon = if (localReady) Icons.Outlined.CheckCircle else Icons.Outlined.Tune,
                    text = stringResource(
                        if (localReady) R.string.race_this_phone_ready else R.string.race_calibrate_this_phone
                    ),
                    color = if (localReady) AccentGreen else TextSecondary
                )
                PairingMetricChip(
                    icon = Icons.Outlined.PhoneAndroid,
                    text = pluralStringResource(
                        R.plurals.race_joined_ready,
                        readyRemoteGates,
                        readyRemoteGates
                    ),
                    color = if (readyRemoteGates >= requiredRemoteGates) AccentGreen else TextSecondary
                )
            }
        }
    }
}

@Composable
private fun JoinReadyStatusCard(uiState: RaceModeUiState) {
    val isSynced = uiState.syncQuality != null || uiState.syncProgress >= 1f
    val accent = if (isSynced) AccentGreen else AccentBlue
    val hostName = uiState.connectedDeviceName
        .takeUnless { it.isBlank() }
        ?: stringResource(R.string.race_default_host_phone)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = stringResource(R.string.race_connected_upper),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = accent
                    )
                    Text(
                        text = stringResource(R.string.race_waiting_for_host),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.race_connected_to_host, hostName),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isSynced) "100%" else "${(uiState.syncProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = accent
                    )
                    Text(
                        text = stringResource(R.string.race_sync_upper),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TextTertiary
                    )
                }
            }

            LinearProgressIndicator(
                progress = { if (isSynced) 1f else uiState.syncProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = accent,
                trackColor = BorderSubtle
            )
        }
    }
}

@Composable
private fun JoinReadyRoleSummaryCard(
    uiState: RaceModeUiState,
    startMode: StartMode
) {
    val startType = StartType.fromRawValue(uiState.startType)
    val roleName = joinReadyRoleName(uiState.role, startType)
    val roleDetail = joinReadyRoleDetail(uiState.role, startType)
    val roleColor = joinReadyRoleColor(uiState.role)
    val sessionSummary = stringResource(
        R.string.race_session_summary_format,
        formatDistance(uiState.distanceMeters),
        stringResource(startMode.displayNameRes)
    )
    val gateSummary = joinReadyGateSummary(uiState)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, roleColor.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(roleColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (uiState.role == DeviceRole.CONTROL) {
                            Icons.Outlined.Settings
                        } else {
                            Icons.Outlined.PhoneAndroid
                        },
                        contentDescription = null,
                        tint = roleColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.race_your_role),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextTertiary
                    )
                    Text(
                        text = roleName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }
            }

            Text(
                text = roleDetail,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 18.sp
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PairingMetricChip(
                    icon = Icons.Filled.Timer,
                    text = sessionSummary,
                    color = TextSecondary
                )
                PairingMetricChip(
                    icon = Icons.Outlined.PhoneAndroid,
                    text = gateSummary,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun joinReadyRoleName(role: DeviceRole?, startType: StartType): String {
    return when (role) {
        DeviceRole.START -> if (startType.usesStartTrigger) {
            stringResource(
                when (startType) {
                    StartType.TOUCH_RELEASE -> R.string.race_touch_start_role
                    StartType.COUNTDOWN -> R.string.race_countdown_start_role
                    StartType.VOICE_COMMAND -> R.string.race_voice_start_role
                    StartType.FLYING, StartType.IN_FRAME -> R.string.race_start_gate
                }
            )
        } else stringResource(R.string.race_start_gate)
        DeviceRole.LAP -> stringResource(R.string.race_split_gate_title)
        DeviceRole.FINISH -> stringResource(R.string.race_finish_gate)
        DeviceRole.CONTROL -> stringResource(R.string.race_control_only)
        null -> stringResource(R.string.race_assigned_gate)
    }
}

@Composable
private fun joinReadyRoleDetail(role: DeviceRole?, startType: StartType): String {
    return when (role) {
        DeviceRole.START -> if (startType.usesStartTrigger) {
            stringResource(
                when (startType) {
                    StartType.TOUCH_RELEASE -> R.string.race_touch_start_hint
                    StartType.COUNTDOWN -> R.string.race_countdown_start_hint
                    StartType.VOICE_COMMAND -> R.string.race_voice_start_hint
                    StartType.FLYING, StartType.IN_FRAME -> R.string.race_role_start_line_detail
                }
            )
        } else {
            stringResource(R.string.race_role_start_line_detail)
        }
        DeviceRole.LAP -> stringResource(R.string.race_role_split_detail)
        DeviceRole.FINISH -> stringResource(R.string.race_role_finish_detail)
        DeviceRole.CONTROL -> stringResource(R.string.race_role_control_detail)
        null -> stringResource(R.string.race_role_unassigned_detail)
    }
}

@Composable
private fun joinReadyGateSummary(uiState: RaceModeUiState): String {
    val gateIndex = uiState.localGateIndex ?: uiState.gateAssignment?.gateIndex
    val gateDistance = uiState.localGateDistanceMeters ?: uiState.gateAssignment?.distanceFromStart
    return if (gateIndex != null && gateDistance != null && gateDistance > 0.0) {
        stringResource(R.string.race_gate_at_distance, gateIndex + 1, formatDistance(gateDistance))
    } else {
        pluralStringResource(
            R.plurals.race_gate_count,
            uiState.numberOfGates,
            uiState.numberOfGates
        )
    }
}

@Composable
private fun joinReadyRoleColor(role: DeviceRole?): Color {
    return when (role) {
        DeviceRole.START -> AccentGreen
        DeviceRole.LAP -> AccentOrange
        DeviceRole.FINISH -> AccentRed
        DeviceRole.CONTROL -> AccentBlue
        null -> TextSecondary
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
private fun StartTypeConfigCard(
    startMode: StartMode,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentBlue.copy(alpha = if (enabled) 0.16f else 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = startMode.icon,
                    contentDescription = null,
                    tint = if (enabled) AccentBlue else TextTertiary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.session_history_filter_start_type),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
                Text(
                    text = stringResource(startMode.displayNameRes),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Text(
                    text = stringResource(startMode.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2
                )
            }

            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.race_change_start_type_cd),
                tint = if (enabled) TextSecondary else TextTertiary
            )
        }
    }
}

@Composable
private fun DistanceSelector(
    currentDistance: Double,
    enabled: Boolean,
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
                            when {
                                isSelected -> AccentBlue
                                enabled -> BorderSubtle
                                else -> BorderSubtle.copy(alpha = 0.45f)
                            },
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = enabled) { onDistanceChanged(distance) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.race_timer_distance, distance.toInt()),
                        color = when {
                            isSelected -> Color.White
                            enabled -> TextSecondary
                            else -> TextTertiary
                        },
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
private fun GateDistanceConfigCard(
    uiState: RaceModeUiState,
    onSegmentDistanceChanged: (fromGateIndex: Int, toGateIndex: Int, distanceMeters: Double) -> Unit
) {
    val gateCount = uiState.numberOfGates.coerceAtLeast(2)
    val finishIndex = gateCount - 1
    val gateDistances = uiState.gateDistances
    val canEdit = uiState.isHostingSession

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.race_gates_configured,
                        gateCount,
                        gateCount
                    ),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary
                )
                Text(
                    text = formatDistance(gateDistances[finishIndex] ?: uiState.distanceMeters),
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentBlue
                )
            }

            for (fromGate in 0 until finishIndex) {
                val toGate = fromGate + 1
                val fromDistance = gateDistances[fromGate]
                    ?: defaultGateDistanceForUi(fromGate, gateCount, uiState.distanceMeters)
                val toDistance = gateDistances[toGate]
                    ?: defaultGateDistanceForUi(toGate, gateCount, uiState.distanceMeters)
                val segmentDistance = (toDistance - fromDistance).coerceAtLeast(1.0)

                SegmentDistanceRow(
                    fromLabel = gateLabelForUi(fromGate, gateCount),
                    toLabel = gateLabelForUi(toGate, gateCount),
                    distanceMeters = segmentDistance,
                    enabled = canEdit,
                    onDecrease = {
                        onSegmentDistanceChanged(fromGate, toGate, (segmentDistance - 1.0).coerceAtLeast(1.0))
                    },
                    onIncrease = {
                        onSegmentDistanceChanged(fromGate, toGate, segmentDistance + 1.0)
                    }
                )
            }
        }
    }
}

@Composable
private fun SegmentDistanceRow(
    fromLabel: String,
    toLabel: String,
    distanceMeters: Double,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.race_segment_range, fromLabel, toLabel),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = formatDistance(distanceMeters),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
        }

        IconButton(
            onClick = onDecrease,
            enabled = enabled && distanceMeters > 1.0,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.race_decrease_segment_cd),
                tint = if (enabled && distanceMeters > 1.0) TextPrimary else TextTertiary
            )
        }

        IconButton(
            onClick = onIncrease,
            enabled = enabled,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.race_increase_segment_cd),
                tint = if (enabled) TextPrimary else TextTertiary
            )
        }
    }
}

@Composable
private fun gateLabelForUi(gateIndex: Int, gateCount: Int): String {
    return when (gateIndex) {
        0 -> stringResource(R.string.device_role_start)
        gateCount - 1 -> stringResource(R.string.device_role_finish)
        else -> stringResource(R.string.race_gate_number, gateIndex)
    }
}

private fun defaultGateDistanceForUi(
    gateIndex: Int,
    gateCount: Int,
    distanceMeters: Double
): Double {
    val finishIndex = (gateCount - 1).coerceAtLeast(1)
    return distanceMeters * gateIndex.toDouble() / finishIndex.toDouble()
}

private fun sanitizeDistanceInput(rawText: String): String {
    val builder = StringBuilder()
    var hasDecimal = false
    rawText.forEach { char ->
        when {
            char.isDigit() -> builder.append(char)
            char == '.' && !hasDecimal -> {
                builder.append(char)
                hasDecimal = true
            }
        }
    }
    return builder.toString().take(6)
}

private fun Double.toCleanDistanceText(): String {
    val rounded = kotlin.math.round(this * 10.0) / 10.0
    val text = rounded.toString()
    return if (text.endsWith(".0")) text.dropLast(2) else text
}

private fun normalizeGateDistancesForUi(
    gateDistances: Map<Int, Double>,
    gateCount: Int,
    fallbackTotalDistanceMeters: Double
): Map<Int, Double> {
    val finishIndex = (gateCount - 1).coerceAtLeast(1)
    val fallbackTotal = fallbackTotalDistanceMeters.coerceAtLeast(1.0)
    val normalized = linkedMapOf<Int, Double>()
    var previousDistance = 0.0

    normalized[0] = 0.0
    for (gateIndex in 1..finishIndex) {
        val fallbackDistance = fallbackTotal * gateIndex.toDouble() / finishIndex.toDouble()
        val rawDistance = gateDistances[gateIndex] ?: fallbackDistance
        val distance = rawDistance.coerceAtLeast(previousDistance + 1.0)
        normalized[gateIndex] = distance
        previousDistance = distance
    }

    return normalized
}

private fun scaledGateDistancesForUi(
    gateCount: Int,
    totalDistanceMeters: Double,
    currentGateDistances: Map<Int, Double>
): Map<Int, Double> {
    val finishIndex = (gateCount - 1).coerceAtLeast(1)
    if (finishIndex == 1) {
        return mapOf(0 to 0.0, 1 to totalDistanceMeters.coerceAtLeast(1.0))
    }

    val current = normalizeGateDistancesForUi(
        gateDistances = currentGateDistances,
        gateCount = gateCount,
        fallbackTotalDistanceMeters = totalDistanceMeters
    )
    val oldTotal = current[finishIndex]?.takeIf { it > 0.0 } ?: totalDistanceMeters.coerceAtLeast(1.0)
    val scale = totalDistanceMeters.coerceAtLeast(1.0) / oldTotal

    return current.mapValues { (gateIndex, distance) ->
        if (gateIndex == 0) 0.0 else distance * scale
    }
}

private fun updateSegmentDistanceForUi(
    gateDistances: Map<Int, Double>,
    gateCount: Int,
    fromGateIndex: Int,
    toGateIndex: Int,
    segmentDistanceMeters: Double
): Map<Int, Double> {
    val finishIndex = (gateCount - 1).coerceAtLeast(1)
    val fromIndex = fromGateIndex.coerceIn(0, finishIndex - 1)
    val toIndex = toGateIndex.coerceIn(1, finishIndex)
    if (toIndex != fromIndex + 1) return gateDistances

    val currentDistances = normalizeGateDistancesForUi(
        gateDistances = gateDistances,
        gateCount = gateCount,
        fallbackTotalDistanceMeters = gateDistances[finishIndex] ?: 60.0
    ).toMutableMap()
    val oldSegmentDistance = (currentDistances[toIndex] ?: 0.0) - (currentDistances[fromIndex] ?: 0.0)
    val delta = segmentDistanceMeters.coerceAtLeast(1.0) - oldSegmentDistance

    for (index in toIndex..finishIndex) {
        currentDistances[index] = (currentDistances[index] ?: 0.0) + delta
    }

    return currentDistances
}

private fun sameGateDistancesForUi(
    first: Map<Int, Double>,
    second: Map<Int, Double>,
    gateCount: Int
): Boolean {
    for (gateIndex in 0 until gateCount.coerceAtLeast(2)) {
        if (kotlin.math.abs((first[gateIndex] ?: 0.0) - (second[gateIndex] ?: 0.0)) > 0.001) {
            return false
        }
    }
    return true
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

@Composable
private fun BluetoothAudioWarningBanner(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AccentRed.copy(alpha = 0.12f))
            .border(1.dp, AccentRed.copy(alpha = 0.28f))
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = AccentRed,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(R.string.race_bluetooth_audio_warning),
            color = AccentRed,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.race_dismiss_bluetooth_audio_warning),
                tint = AccentRed.copy(alpha = 0.75f),
                modifier = Modifier.size(16.dp)
            )
        }
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
    val context = LocalContext.current
    var activeStartOverlay by remember(uiState.startType, uiState.phase) { mutableStateOf<StartMode?>(null) }
    var showBluetoothAudioWarning by remember(uiState.startType, uiState.phase) { mutableStateOf(false) }
    var showSessionSettings by remember(uiState.phase) { mutableStateOf(false) }
    var showLiveResults by remember(uiState.phase) { mutableStateOf(false) }
    var showFullscreenCamera by remember(uiState.phase) { mutableStateOf(false) }
    var selectedRun by remember(uiState.phase) { mutableStateOf<RunEntity?>(null) }
    var expandedThumbnail by remember(uiState.phase) { mutableStateOf<ExpandedThumbnail?>(null) }
    val startMode = StartMode.fromString(uiState.startType)
    val canTriggerExternalStart = uiState.role == DeviceRole.START &&
        startMode in listOf(StartMode.TOUCH, StartMode.COUNTDOWN, StartMode.VOICE) &&
        uiState.raceStatus != "started"
    val hasAudibleStartCue = startMode == StartMode.COUNTDOWN || startMode == StartMode.VOICE
    val isDetectionPaused = uiState.raceStatus == "paused"
    val isRunInProgress =
        uiState.raceStatus in setOf("started", "collecting_gates", "waiting_for_result") ||
        isDetectionPaused ||
        uiState.elapsedTimeSeconds > 0.0 ||
        uiState.receivedGateCount > 0
    val canCancelRun = !isDetectionPaused && isRunInProgress
    val canToggleDetectionPause = uiState.requiresLocalCamera && (!canCancelRun || isDetectionPaused) &&
        !canTriggerExternalStart

    LaunchedEffect(context, uiState.role, hasAudibleStartCue) {
        showBluetoothAudioWarning = uiState.role == DeviceRole.START &&
            hasAudibleStartCue &&
            AudioStartTiming.hasBluetoothAudioOutput(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ThumbnailViewerDialog(
            thumbnail = expandedThumbnail,
            onDismiss = { expandedThumbnail = null }
        )

        if (showSessionSettings) {
            SessionSettingsSheet(
                uiState = uiState,
                startTypeLocked = isRunInProgress,
                onApply = { startTypeRaw, distanceMeters, gateDistances, selectedAthleteIds, activeAthleteId, startSoundType, showSpeedInResults ->
                    viewModel.applySessionSettings(
                        startTypeRaw = startTypeRaw,
                        distanceMeters = distanceMeters,
                        gateDistances = gateDistances,
                        selectedAthleteIds = selectedAthleteIds,
                        activeAthleteId = activeAthleteId
                    )
                    viewModel.setStartSoundType(startSoundType)
                    viewModel.setShowSpeedInResults(showSpeedInResults)
                    showSessionSettings = false
                },
                onEndSession = {
                    showSessionSettings = false
                    viewModel.presentSessionEndConfirmation()
                },
                onDismiss = { showSessionSettings = false }
            )
        }

        if (showLiveResults) {
            ActiveSessionResultsSheet(
                uiState = uiState,
                onRunClick = { run ->
                    showLiveResults = false
                    selectedRun = run
                },
                onThumbnailClick = { expandedThumbnail = it },
                onReviewSubmitted = viewModel::submitCrossingReview,
                onDismiss = { showLiveResults = false }
            )
        }

        selectedRun?.let { run ->
            RaceRunDetailSheet(
                run = run,
                showSpeedInResults = uiState.showSpeedInResults,
                speedUnit = uiState.speedUnit,
                onDistanceChanged = { newDistance ->
                    viewModel.updateRunDistance(run.id, newDistance)
                    selectedRun = run.copy(distance = newDistance)
                },
                onDelete = {
                    viewModel.deleteRun(run.id)
                    selectedRun = null
                },
                onDismiss = { selectedRun = null }
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ActiveRaceSessionHeader(
                uiState = uiState,
                onSettings = { showSessionSettings = true },
                onEndSession = viewModel::presentSessionEndConfirmation
            )

            if (showBluetoothAudioWarning) {
                BluetoothAudioWarningBanner(
                    onDismiss = { showBluetoothAudioWarning = false }
                )
            }

            ActiveAthleteChipBar(
                uiState = uiState,
                onActiveAthleteSelected = viewModel::setActiveAthlete,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            ActiveRaceTabBar(
                onRecordClick = {},
                onResultsClick = { showLiveResults = true }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(228.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                ActiveRaceTimingSummary(
                    uiState = uiState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                if (uiState.requiresLocalCamera) {
                    if (showFullscreenCamera) {
                        Spacer(modifier = Modifier.size(width = 160.dp, height = 228.dp))
                    } else {
                        ActiveRaceCameraCard(
                            uiState = uiState,
                            onSurfaceReady = viewModel::onSurfaceReady,
                            onSurfaceDestroyed = viewModel::onSurfaceDestroyed,
                            onExpand = { showFullscreenCamera = true },
                            modifier = Modifier.size(width = 160.dp, height = 228.dp)
                        )
                    }
                } else {
                    ActiveRaceControlPhoneCard(
                        modifier = Modifier.size(width = 160.dp, height = 228.dp)
                    )
                }
            }

            if (uiState.numberOfGates > 2) {
                GateCollectionStatus(
                    receivedGateCount = uiState.receivedGateCount,
                    numberOfGates = uiState.numberOfGates
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter
            ) {
                if (uiState.completedRuns.isNotEmpty()) {
                    SessionResultsList(
                        runs = uiState.completedRuns,
                        numberOfGates = uiState.numberOfGates,
                        showSpeedInResults = uiState.showSpeedInResults,
                        speedUnit = uiState.speedUnit,
                        detectionReviewEnabled = false,
                        onRunClick = { selectedRun = it },
                        onThumbnailClick = { expandedThumbnail = it },
                        onReviewSubmitted = viewModel::submitCrossingReview,
                        maxRuns = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    ActiveRaceEmptyResults()
                }
            }

            if (canTriggerExternalStart) {
                StartTriggerButton(
                    startMode = startMode,
                    onClick = { activeStartOverlay = startMode }
                )
            }

            if (canCancelRun) {
                CancelRunButton(onClick = viewModel::cancelCurrentRun)
            } else if (canToggleDetectionPause) {
                PauseResumeButton(
                    isPaused = isDetectionPaused,
                    onClick = viewModel::toggleDetectionPause
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        if (showFullscreenCamera && uiState.requiresLocalCamera) {
            ActiveRaceFullscreenCamera(
                uiState = uiState,
                onSurfaceReady = viewModel::onSurfaceReady,
                onSurfaceDestroyed = viewModel::onSurfaceDestroyed,
                onMinimize = { showFullscreenCamera = false }
            )
        }

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
                    preStartDelayMin = uiState.preStartDelayMin.toDouble(),
                    preStartDelayMax = uiState.preStartDelayMax.toDouble(),
                    startSoundType = StartSoundType.fromRawValue(uiState.startSoundType),
                    onCountdownComplete = { timestamp ->
                        viewModel.handleExternalStart(timestamp)
                        activeStartOverlay = null
                    },
                    onCancel = { activeStartOverlay = null }
                )
                StartMode.VOICE -> VoiceStartOverlay(
                    voiceStartService = viewModel.voiceStartServiceForOverlay,
                    settings = VoiceStartOverlaySettings(
                        voiceProvider = uiState.voiceProvider,
                        elevenLabsVoice = uiState.elevenLabsVoice,
                        voiceGender = uiState.voiceGender,
                        appLanguage = uiState.appLanguage,
                        startSoundType = uiState.startSoundType,
                        preStartDelayMin = uiState.preStartDelayMin,
                        marksSetDelayMin = uiState.marksSetDelayMin,
                        setGoHoldMin = uiState.setGoHoldMin,
                        includeReadyCommand = uiState.includeReadyCommand
                    ),
                    settingsActions = VoiceStartOverlaySettingsActions(
                        onVoiceProviderChanged = viewModel::setVoiceProvider,
                        onElevenLabsVoiceChanged = viewModel::setElevenLabsVoice,
                        onVoiceGenderChanged = viewModel::setVoiceGender,
                        onAppLanguageChanged = { tag ->
                            viewModel.setAppLanguage(tag)
                            applyLanguage(tag)
                        },
                        onStartSoundTypeChanged = viewModel::setStartSoundType,
                        onPreStartDelayChanged = viewModel::setPreStartDelayMin,
                        onMarksSetDelayChanged = viewModel::setMarksSetDelayMin,
                        onSetGoHoldChanged = viewModel::setSetGoHoldMin,
                        onIncludeReadyCommandChanged = viewModel::setIncludeReadyCommand
                    ),
                    onStart = { timestamp ->
                        viewModel.handleExternalStart(timestamp)
                        activeStartOverlay = null
                    },
                    onCancel = {
                        viewModel.cancelVoiceCountdown()
                        activeStartOverlay = null
                    }
                )
                StartMode.FLYING, StartMode.INFRAME -> Unit
            }
        }
    }
}

@Composable
private fun ActiveRaceSessionHeader(
    uiState: RaceModeUiState,
    onSettings: () -> Unit,
    onEndSession: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoleBadge(role = uiState.role)

        if (uiState.isDeviceConnected) {
            Row(
                modifier = Modifier
                    .background(AccentGreen.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(AccentGreen, CircleShape)
                )
                Text(
                    text = stringResource(R.string.race_pill_connected),
                    color = AccentGreen,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        uiState.syncQuality?.let { SyncQualityBadge(quality = it) }

        if (uiState.isHostingSession) {
            IconButton(
                onClick = onSettings,
                modifier = Modifier
                    .size(36.dp)
                    .background(BorderSubtle, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = stringResource(R.string.race_session_settings_cd),
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        TextButton(
            onClick = onEndSession,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            colors = ButtonDefaults.textButtonColors(
                containerColor = AccentRed.copy(alpha = 0.16f),
                contentColor = AccentRed
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = stringResource(R.string.race_end),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
private fun ActiveRaceTabBar(
    onRecordClick: () -> Unit,
    onResultsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(CardBackground.copy(alpha = 0.5f))
    ) {
        ActiveRaceTab(
            text = stringResource(R.string.timing_tab_record),
            selected = true,
            onClick = onRecordClick,
            modifier = Modifier.weight(1f)
        )
        ActiveRaceTab(
            text = stringResource(R.string.timing_tab_results),
            selected = false,
            onClick = onResultsClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActiveRaceTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) TextPrimary else TextSecondary,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(AccentBlue)
            )
        }
    }
}

@Composable
private fun ActiveRaceTimingSummary(
    uiState: RaceModeUiState,
    modifier: Modifier = Modifier
) {
    val latestRun = uiState.completedRuns.maxByOrNull { it.runNumber }
    val isRunning = uiState.raceStatus in setOf(
        "started",
        "collecting_gates",
        "waiting_for_result"
    )
    val displayTime = when {
        uiState.elapsedTimeSeconds > 0.0 -> uiState.elapsedTimeSeconds
        latestRun != null -> latestRun.timeSeconds
        else -> 0.0
    }
    val runNumber = (latestRun?.runNumber ?: 0) + 1
    val timerColor = when {
        isRunning -> AccentBlue
        latestRun != null -> AccentGreen
        else -> TextTertiary
    }

    Column(
        modifier = modifier.padding(top = 12.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = stringResource(R.string.timing_run_number, runNumber),
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
        )
        Text(
            text = formatRaceTime(displayTime),
            color = timerColor,
            style = MaterialTheme.typography.displayMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 43.sp
            ),
            maxLines = 1
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Top
        ) {
            ActiveRaceMetric(
                label = stringResource(R.string.timing_label_speed),
                value = if (displayTime > 0.0) {
                    formatSpeed(uiState.distanceMeters, displayTime, uiState.speedUnit)
                } else {
                    stringResource(R.string.timing_no_speed_ms)
                }
            )
            ActiveRaceMetric(
                label = stringResource(R.string.timing_label_distance),
                value = formatDistance(uiState.distanceMeters)
            )
        }
        if (uiState.raceStatus == "waiting_for_result") {
            Text(
                text = stringResource(R.string.race_waiting_host_result),
                color = AccentOrange,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2
            )
        }
    }
}

@Composable
private fun ActiveRaceMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = TextTertiary,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = value,
            color = TextPrimary,
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun ActiveRaceCameraCard(
    uiState: RaceModeUiState,
    onSurfaceReady: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    ActiveRaceCameraSurface(
        uiState = uiState,
        onSurfaceReady = onSurfaceReady,
        onSurfaceDestroyed = onSurfaceDestroyed,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.5.dp,
                color = getDetectionBorderColor(uiState.detectionState),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onExpand),
        topEndControl = {
            IconButton(
                onClick = onExpand,
                modifier = Modifier
                    .size(30.dp)
                    .background(Color.Black.copy(alpha = 0.62f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInFull,
                    contentDescription = stringResource(R.string.race_expand_camera_cd),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    )
}

@Composable
private fun ActiveRaceFullscreenCamera(
    uiState: RaceModeUiState,
    onSurfaceReady: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onMinimize: () -> Unit
) {
    ActiveRaceCameraSurface(
        uiState = uiState,
        onSurfaceReady = onSurfaceReady,
        onSurfaceDestroyed = onSurfaceDestroyed,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        topEndControl = {
            IconButton(
                onClick = onMinimize,
                modifier = Modifier
                    .size(42.dp)
                    .background(Color.Black.copy(alpha = 0.7f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.CloseFullscreen,
                    contentDescription = stringResource(R.string.race_minimize_camera_cd),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        bottomContent = {
            Text(
                text = stringResource(R.string.race_fullscreen_camera_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    )
}

@Composable
private fun ActiveRaceCameraSurface(
    uiState: RaceModeUiState,
    onSurfaceReady: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
    topEndControl: @Composable BoxScope.() -> Unit = {},
    bottomContent: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier = modifier) {
        if (uiState.hasPermission && uiState.cameraState !is CameraManager.CameraState.Error) {
            CameraPreview(
                gatePosition = uiState.gatePosition,
                onGatePositionChanged = {},
                gateLineDraggable = false,
                fps = uiState.fps,
                detectionState = uiState.detectionState,
                sensorOrientation = uiState.sensorOrientation,
                isFrontCamera = uiState.isFrontCamera,
                previewWidth = uiState.previewWidth,
                previewHeight = uiState.previewHeight,
                showDiagnostics = false,
                onSurfaceReady = onSurfaceReady,
                onSurfaceDestroyed = onSurfaceDestroyed,
                modifier = Modifier.matchParentSize()
            )
        } else {
            CameraPreviewPlaceholder(
                message = stringResource(R.string.race_camera_not_available),
                modifier = Modifier.matchParentSize()
            )
        }

        ActiveRaceCameraStatusPill(
            detectionState = uiState.detectionState,
            raceStatus = uiState.raceStatus,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        )

        if (uiState.fps > 0) {
            Text(
                text = stringResource(R.string.settings_fps_label, uiState.fps),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            content = topEndControl
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            content = bottomContent
        )
    }
}

@Composable
private fun ActiveRaceControlPhoneCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(34.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.race_control_phone),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = stringResource(R.string.race_control_phone_desc),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActiveRaceEmptyResults() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .background(CardBackground.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Timer,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.timing_no_results_yet),
            color = TextTertiary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSettingsSheet(
    uiState: RaceModeUiState,
    startTypeLocked: Boolean,
    onApply: (
        startTypeRaw: String,
        distanceMeters: Double,
        gateDistances: Map<Int, Double>,
        selectedAthleteIds: Set<String>,
        activeAthleteId: String?,
        startSoundType: String,
        showSpeedInResults: Boolean
    ) -> Unit,
    onEndSession: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingStartType by remember(uiState.phase) { mutableStateOf(uiState.startType) }
    var pendingDistanceText by remember(uiState.phase) {
        mutableStateOf(uiState.distanceMeters.toCleanDistanceText())
    }
    var pendingGateDistances by remember(uiState.phase) { mutableStateOf(uiState.gateDistances) }
    var pendingSelectedAthleteIds by remember(uiState.phase) { mutableStateOf(uiState.selectedAthleteIds) }
    var pendingActiveAthleteId by remember(uiState.phase) { mutableStateOf(uiState.activeAthleteId) }
    var pendingStartSoundType by remember(uiState.phase) {
        mutableStateOf(StartSoundType.fromRawValue(uiState.startSoundType).rawValue)
    }
    var pendingShowSpeedInResults by remember(uiState.phase) {
        mutableStateOf(uiState.showSpeedInResults)
    }
    val pendingDistance = pendingDistanceText.toDoubleOrNull()?.coerceAtLeast(1.0) ?: uiState.distanceMeters
    val normalizedGateDistances = normalizeGateDistancesForUi(
        gateDistances = pendingGateDistances,
        gateCount = uiState.numberOfGates,
        fallbackTotalDistanceMeters = pendingDistance
    )
    val canSave = pendingDistanceText.toDoubleOrNull() != null
    val hasChanges = pendingStartType != uiState.startType ||
        kotlin.math.abs(pendingDistance - uiState.distanceMeters) > 0.001 ||
        !sameGateDistancesForUi(uiState.gateDistances, normalizedGateDistances, uiState.numberOfGates) ||
        pendingSelectedAthleteIds != uiState.selectedAthleteIds ||
        pendingActiveAthleteId != uiState.activeAthleteId ||
        pendingStartSoundType != StartSoundType.fromRawValue(uiState.startSoundType).rawValue ||
        pendingShowSpeedInResults != uiState.showSpeedInResults

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.session_settings_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.race_close_session_settings_cd),
                        tint = TextSecondary
                    )
                }
            }

            SessionSettingsSection(title = stringResource(R.string.session_settings_start_type)) {
                StartMode.entries.chunked(2).forEach { rowModes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowModes.forEach { mode ->
                            SessionSettingsChip(
                                text = stringResource(mode.displayNameRes),
                                icon = mode.icon,
                                selected = pendingStartType == mode.rawValue,
                                enabled = !startTypeLocked,
                                modifier = Modifier.weight(1f)
                            ) {
                                pendingStartType = mode.rawValue
                            }
                        }
                        if (rowModes.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                Text(
                    text = if (startTypeLocked) {
                        stringResource(R.string.session_settings_start_type_locked)
                    } else {
                        stringResource(StartMode.fromString(pendingStartType).descriptionRes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (startTypeLocked) AccentOrange else TextTertiary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SessionSettingsSection(title = stringResource(R.string.setup_distance)) {
                val presets = listOf(5.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 100.0)
                presets.chunked(4).forEach { rowDistances ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowDistances.forEach { distance ->
                            SessionSettingsChip(
                                text = "${distance.toInt()}m",
                                selected = kotlin.math.abs(pendingDistance - distance) < 0.5,
                                modifier = Modifier.weight(1f)
                            ) {
                                pendingDistanceText = distance.toCleanDistanceText()
                                pendingGateDistances = scaledGateDistancesForUi(
                                    gateCount = uiState.numberOfGates,
                                    totalDistanceMeters = distance,
                                    currentGateDistances = pendingGateDistances
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = pendingDistanceText,
                    onValueChange = { rawText ->
                        val sanitized = sanitizeDistanceInput(rawText)
                        val previousDistance = pendingDistanceText.toDoubleOrNull() ?: pendingDistance
                        pendingDistanceText = sanitized
                        val nextDistance = sanitized.toDoubleOrNull()
                        if (nextDistance != null && kotlin.math.abs(nextDistance - previousDistance) > 0.001) {
                            pendingGateDistances = scaledGateDistancesForUi(
                                gateCount = uiState.numberOfGates,
                                totalDistanceMeters = nextDistance,
                                currentGateDistances = pendingGateDistances
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.setup_custom_distance)) },
                    suffix = { Text(stringResource(R.string.common_meters_abbreviation)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentBlue,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = TextSecondary
                    )
                )
            }

            if (uiState.numberOfGates > 2) {
                SessionSettingsSection(title = stringResource(R.string.race_gate_segments)) {
                    val gateCount = uiState.numberOfGates.coerceAtLeast(2)
                    val finishIndex = gateCount - 1
                    for (fromGate in 0 until finishIndex) {
                        val toGate = fromGate + 1
                        val fromDistance = normalizedGateDistances[fromGate]
                            ?: defaultGateDistanceForUi(fromGate, gateCount, pendingDistance)
                        val toDistance = normalizedGateDistances[toGate]
                            ?: defaultGateDistanceForUi(toGate, gateCount, pendingDistance)
                        val segmentDistance = (toDistance - fromDistance).coerceAtLeast(1.0)

                        SegmentDistanceRow(
                            fromLabel = gateLabelForUi(fromGate, gateCount),
                            toLabel = gateLabelForUi(toGate, gateCount),
                            distanceMeters = segmentDistance,
                            enabled = true,
                            onDecrease = {
                                pendingGateDistances = updateSegmentDistanceForUi(
                                    gateDistances = normalizedGateDistances,
                                    gateCount = gateCount,
                                    fromGateIndex = fromGate,
                                    toGateIndex = toGate,
                                    segmentDistanceMeters = (segmentDistance - 1.0).coerceAtLeast(1.0)
                                )
                                pendingDistanceText = (pendingGateDistances[finishIndex] ?: pendingDistance)
                                    .toCleanDistanceText()
                            },
                            onIncrease = {
                                pendingGateDistances = updateSegmentDistanceForUi(
                                    gateDistances = normalizedGateDistances,
                                    gateCount = gateCount,
                                    fromGateIndex = fromGate,
                                    toGateIndex = toGate,
                                    segmentDistanceMeters = segmentDistance + 1.0
                                )
                                pendingDistanceText = (pendingGateDistances[finishIndex] ?: pendingDistance)
                                    .toCleanDistanceText()
                            }
                        )
                    }
                }
            }

            SessionSettingsSection(
                title = stringResource(R.string.setup_athletes),
                trailing = if (pendingSelectedAthleteIds.isNotEmpty()) {
                    stringResource(R.string.common_selected_count, pendingSelectedAthleteIds.size)
                } else {
                    null
                }
            ) {
                if (uiState.athletes.isEmpty()) {
                    EmptySettingsRow(
                        icon = Icons.Outlined.PersonAdd,
                        text = stringResource(R.string.session_settings_no_athletes)
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        uiState.athletes.forEach { athlete ->
                            val isSelected = athlete.id in pendingSelectedAthleteIds
                            SessionAthleteAvatar(
                                athlete = athlete,
                                selected = isSelected,
                                active = pendingActiveAthleteId == athlete.id,
                                onClick = {
                                    if (isSelected) {
                                        val updated = pendingSelectedAthleteIds - athlete.id
                                        pendingSelectedAthleteIds = updated
                                        if (pendingActiveAthleteId == athlete.id) {
                                            pendingActiveAthleteId = updated.firstOrNull()
                                        }
                                    } else {
                                        pendingSelectedAthleteIds = pendingSelectedAthleteIds + athlete.id
                                        pendingActiveAthleteId = athlete.id
                                    }
                                }
                            )
                        }
                    }
                }
            }

            val selectedAthletes = uiState.athletes.filter { it.id in pendingSelectedAthleteIds }
            if (selectedAthletes.size > 1) {
                SessionSettingsSection(title = stringResource(R.string.race_next_result)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedAthletes.forEach { athlete ->
                            ActiveAthleteChip(
                                athlete = athlete,
                                selected = pendingActiveAthleteId == athlete.id,
                                onClick = { pendingActiveAthleteId = athlete.id }
                            )
                        }
                    }
                }
            }

            SessionSettingsSection(title = stringResource(R.string.session_settings_performance)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.session_settings_start_sound),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StartSoundType.selectable.forEach { soundType ->
                        SessionSettingsChip(
                            text = stringResource(soundType.displayNameRes),
                            selected = pendingStartSoundType == soundType.rawValue,
                            modifier = Modifier.weight(1f)
                        ) {
                            pendingStartSoundType = soundType.rawValue
                        }
                    }
                }

                Text(
                    text = stringResource(
                        StartSoundType.fromRawValue(pendingStartSoundType).subtitleRes
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )

                HorizontalDivider(color = BorderSubtle)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Speed,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.session_settings_show_speed),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.session_settings_show_speed_results_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                    Switch(
                        checked = pendingShowSpeedInResults,
                        onCheckedChange = { pendingShowSpeedInResults = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentBlue,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = TextTertiary
                        )
                    )
                }

                HorizontalDivider(color = BorderSubtle)

                SettingsMetricRow(
                    icon = Icons.Outlined.Speed,
                    label = stringResource(R.string.race_camera_fps),
                    value = if (uiState.fps > 0) {
                        stringResource(R.string.settings_fps_label, uiState.fps)
                    } else stringResource(R.string.race_starting)
                )
                SettingsMetricRow(
                    icon = Icons.Outlined.Sync,
                    label = stringResource(R.string.race_clock_sync),
                    value = uiState.syncQuality?.let { quality ->
                        when (quality) {
                            SyncQuality.EXCELLENT -> stringResource(R.string.race_quality_excellent)
                            SyncQuality.GOOD -> stringResource(R.string.race_quality_good)
                            SyncQuality.FAIR -> stringResource(R.string.race_quality_fair)
                            SyncQuality.POOR -> stringResource(R.string.race_quality_poor)
                            SyncQuality.BAD -> stringResource(R.string.race_quality_bad)
                        }
                    } ?: stringResource(R.string.race_syncing)
                )
            }

            EndSessionSettingsButton(
                onClick = onEndSession
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(
                    onClick = {
                        onApply(
                            pendingStartType,
                            pendingDistance,
                            normalizedGateDistances,
                            pendingSelectedAthleteIds,
                            pendingActiveAthleteId?.takeIf { it in pendingSelectedAthleteIds },
                            pendingStartSoundType,
                            pendingShowSpeedInResults
                        )
                    },
                    enabled = canSave && hasChanges,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = Color.White,
                        disabledContainerColor = BorderSubtle,
                        disabledContentColor = TextTertiary
                    )
                ) {
                    Text(stringResource(R.string.run_detail_save))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EndSessionSettingsButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AccentRed.copy(alpha = 0.12f))
            .border(
                width = 1.dp,
                color = AccentRed.copy(alpha = 0.32f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = null,
            tint = AccentRed,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.common_end_session),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = AccentRed
        )
    }
}

@Composable
private fun SessionSettingsSection(
    title: String,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                trailing?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SessionSettingsChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentBlue else BorderSubtle)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = if (selected) Color.White else TextSecondary,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SessionAthleteAvatar(
    athlete: AthleteEntity,
    selected: Boolean,
    active: Boolean,
    onClick: () -> Unit
) {
    val athleteColor = parseAthleteColor(athlete.color)
    Column(
        modifier = Modifier
            .width(74.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(if (selected) athleteColor.copy(alpha = 0.22f) else BorderSubtle)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) athleteColor else BorderSubtle,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(athleteColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = athlete.displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }

            if (active) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = athleteColor,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .background(SurfaceDark, CircleShape)
                )
            }
        }

        Text(
            text = athlete.displayName,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (selected) TextPrimary else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EmptySettingsRow(
    icon: ImageVector,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
    }
}

@Composable
private fun SettingsMetricRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary
        )
    }
}

@Composable
private fun CancelRunButton(onClick: () -> Unit) {
    ActiveRaceControlButton(
        text = stringResource(R.string.timing_btn_cancel_run),
        icon = Icons.Filled.Cancel,
        backgroundColor = AccentRed,
        onClick = onClick
    )
}

@Composable
private fun PauseResumeButton(
    isPaused: Boolean,
    onClick: () -> Unit
) {
    ActiveRaceControlButton(
        text = if (isPaused) {
            stringResource(R.string.race_resume)
        } else {
            stringResource(R.string.timing_btn_pause)
        },
        icon = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
        backgroundColor = if (isPaused) AccentBlue else BorderSubtle,
        contentColor = if (isPaused) Color.White else TextPrimary,
        onClick = onClick
    )
}

@Composable
private fun StartTriggerButton(
    startMode: StartMode,
    onClick: () -> Unit
) {
    ActiveRaceControlButton(
        text = when (startMode) {
            StartMode.TOUCH -> stringResource(R.string.race_open_touch_start)
            StartMode.COUNTDOWN -> stringResource(R.string.race_open_countdown)
            StartMode.VOICE -> stringResource(R.string.race_open_voice_start)
            StartMode.FLYING -> stringResource(R.string.race_await_gate_crossing)
            StartMode.INFRAME -> stringResource(R.string.race_await_inframe_start)
        },
        icon = Icons.Filled.PlayArrow,
        backgroundColor = AccentBlue,
        onClick = onClick
    )
}

@Composable
private fun ActiveRaceControlButton(
    text: String,
    icon: ImageVector,
    backgroundColor: Color,
    contentColor: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActiveRaceCameraStatusPill(
    detectionState: PhotoFinishDetector.State,
    raceStatus: String,
    modifier: Modifier = Modifier
) {
    val (text, icon, color) = when {
        raceStatus == "paused" ->
            Triple(stringResource(R.string.race_status_paused), Icons.Filled.Pause, AccentOrange)
        detectionState == PhotoFinishDetector.State.UNSTABLE ->
            Triple(stringResource(R.string.race_camera_status_hold_still), Icons.Filled.PhoneAndroid, AccentRed)
        detectionState == PhotoFinishDetector.State.ATHLETE_TOO_FAR ->
            Triple(stringResource(R.string.race_banner_too_far), Icons.AutoMirrored.Filled.DirectionsRun, AccentOrange)
        detectionState == PhotoFinishDetector.State.TRIGGERED ||
            detectionState == PhotoFinishDetector.State.COOLDOWN ||
            raceStatus == "waiting_for_result" ->
            Triple(stringResource(R.string.race_camera_status_calculating), Icons.Filled.HourglassTop, AccentOrange)
        raceStatus == "started" || raceStatus == "collecting_gates" ->
            Triple(stringResource(R.string.race_camera_status_running), Icons.Filled.Timer, AccentGreen)
        detectionState == PhotoFinishDetector.State.READY ->
            Triple(stringResource(R.string.race_camera_status_go), Icons.AutoMirrored.Filled.DirectionsRun, AccentGreen)
        else ->
            Triple(stringResource(R.string.race_camera_status_position), Icons.Filled.CenterFocusStrong, AccentBlue)
    }

    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.88f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GateCollectionStatus(
    receivedGateCount: Int,
    numberOfGates: Int
) {
    Text(
        text = stringResource(
            R.string.race_gates_reported,
            receivedGateCount,
            numberOfGates
        ),
        color = TextSecondary,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    )
}

@Composable
private fun TimerDisplay(
    role: DeviceRole?,
    elapsedTimeSeconds: Double,
    isRunning: Boolean,
    distanceMeters: Double,
    numberOfGates: Int
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
                stringResource(
                    R.string.race_running_summary,
                    formatDistance(distanceMeters),
                    pluralStringResource(R.plurals.race_gate_count, numberOfGates, numberOfGates)
                )
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
    onUpdateRunDistance: (String, Double) -> Unit,
    onDeleteRun: (String) -> Unit,
    onReviewSubmitted: (DetectionReviewSubmission) -> Unit,
    onExit: () -> Unit
) {
    var selectedRun by remember(uiState.phase) { mutableStateOf<RunEntity?>(null) }
    var expandedThumbnail by remember(uiState.phase) { mutableStateOf<ExpandedThumbnail?>(null) }

    ThumbnailViewerDialog(
        thumbnail = expandedThumbnail,
        onDismiss = { expandedThumbnail = null }
    )

    selectedRun?.let { run ->
        RaceRunDetailSheet(
            run = run,
            showSpeedInResults = uiState.showSpeedInResults,
            speedUnit = uiState.speedUnit,
            onDistanceChanged = { newDistance ->
                onUpdateRunDistance(run.id, newDistance)
                selectedRun = run.copy(distance = newDistance)
            },
            onDelete = {
                onDeleteRun(run.id)
                selectedRun = null
            },
            onDismiss = { selectedRun = null }
        )
    }

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
                        value = formatDistance(uiState.distanceMeters)
                    )

                    ResultDetailRow(
                        label = stringResource(R.string.setup_gates),
                        value = uiState.numberOfGates.toString()
                    )

                    if (uiState.showSpeedInResults && resultTime > 0) {
                        ResultDetailRow(
                            label = stringResource(R.string.race_result_avg_speed),
                            value = formatSpeed(uiState.distanceMeters, resultTime, uiState.speedUnit)
                        )
                    }

                    val uncertainty = uiState.resultUncertaintyMs
                    if (uncertainty != null) {
                        ResultDetailRow(
                            label = stringResource(R.string.race_result_uncertainty),
                            value = String.format(stringResource(R.string.race_result_uncertainty_format), uncertainty)
                        )
                    }

                    if (uiState.resultSegments.isNotEmpty()) {
                        HorizontalDivider(color = BorderSubtle)
                        uiState.resultSegments.forEach { segment ->
                            ResultDetailRow(
                                label = stringResource(
                                    R.string.race_gate_number_range,
                                    segment.fromGateIndex,
                                    segment.toGateIndex
                                ),
                                value = formatRaceTime(segment.splitNanos / 1_000_000_000.0)
                            )
                        }
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
                            DeviceRole.LAP -> stringResource(R.string.device_role_split)
                            DeviceRole.FINISH -> stringResource(R.string.race_role_finish)
                            DeviceRole.CONTROL -> stringResource(R.string.setup_control)
                            null -> stringResource(R.string.race_role_unknown)
                        }
                    )
                }
            }

            if (uiState.completedRuns.isNotEmpty()) {
                SessionResultsList(
                    runs = uiState.completedRuns,
                    numberOfGates = uiState.numberOfGates,
                    showSpeedInResults = uiState.showSpeedInResults,
                    speedUnit = uiState.speedUnit,
                    detectionReviewEnabled = BuildConfig.DEBUG && uiState.detectionDiagnosticsEnabled,
                    onRunClick = { selectedRun = it },
                    onThumbnailClick = { expandedThumbnail = it },
                    onReviewSubmitted = onReviewSubmitted,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Text(
                text = stringResource(R.string.race_no_timing_data),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (uiState.isHostingSession) {
            PillButton(
                text = stringResource(R.string.race_new_race),
                backgroundColor = AccentGreen,
                onClick = onNewRace
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(BorderSubtle, RoundedCornerShape(25.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.race_waiting_host_arm),
                    color = TextSecondary,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }

        PillButton(
            text = stringResource(R.string.race_exit),
            backgroundColor = BorderSubtle,
            onClick = onExit
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun LiveResultsSummaryStrip(
    runs: List<RunEntity>,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val best = runs.minByOrNull { it.timeSeconds }
    val average = runs.takeIf { it.isNotEmpty() }
        ?.map { it.timeSeconds }
        ?.average()
    val latest = runs.maxByOrNull { it.runNumber }

    Card(
        modifier = if (onClick != null) {
            modifier.clickable(onClick = onClick)
        } else {
            modifier
        },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveResultsStat(
                label = stringResource(R.string.session_detail_stat_runs),
                value = runs.size.toString(),
                color = AccentBlue,
                modifier = Modifier.weight(1f)
            )
            LiveResultsStat(
                label = stringResource(R.string.session_detail_stat_best),
                value = best?.let {
                    stringResource(R.string.common_seconds_value, formatRaceTime(it.timeSeconds))
                } ?: "--",
                color = AccentGreen,
                modifier = Modifier.weight(1f)
            )
            LiveResultsStat(
                label = stringResource(R.string.race_metric_average),
                value = average?.let {
                    stringResource(R.string.common_seconds_value, formatRaceTime(it))
                } ?: "--",
                color = AccentOrange,
                modifier = Modifier.weight(1f)
            )
            LiveResultsStat(
                label = stringResource(R.string.race_metric_latest),
                value = latest?.let { "#${it.runNumber}" } ?: "--",
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LiveResultsStat(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            color = color,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            maxLines = 1
        )
        Text(
            text = label,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveSessionResultsSheet(
    uiState: RaceModeUiState,
    onRunClick: (RunEntity) -> Unit,
    onThumbnailClick: (ExpandedThumbnail) -> Unit,
    onReviewSubmitted: (DetectionReviewSubmission) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.timing_tab_results),
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = stringResource(
                            R.string.race_session_summary_format,
                            StartType.fromRawValue(uiState.startType).localizedDisplayName(),
                            formatDistance(uiState.distanceMeters)
                        ),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.timing_tab_record), color = AccentBlue)
                }
            }

            if (uiState.completedRuns.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(CardBackground, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(38.dp)
                        )
                        Text(
                            text = stringResource(R.string.race_no_runs_yet),
                            color = TextSecondary,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            } else {
                LiveResultsSummaryStrip(
                    runs = uiState.completedRuns,
                    modifier = Modifier.fillMaxWidth()
                )
                SessionResultsList(
                    runs = uiState.completedRuns,
                    numberOfGates = uiState.numberOfGates,
                    showSpeedInResults = uiState.showSpeedInResults,
                    speedUnit = uiState.speedUnit,
                    detectionReviewEnabled = BuildConfig.DEBUG && uiState.detectionDiagnosticsEnabled,
                    onRunClick = onRunClick,
                    onThumbnailClick = onThumbnailClick,
                    onReviewSubmitted = onReviewSubmitted,
                    maxRuns = null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SessionResultsList(
    runs: List<RunEntity>,
    numberOfGates: Int,
    showSpeedInResults: Boolean,
    speedUnit: String,
    detectionReviewEnabled: Boolean,
    onRunClick: (RunEntity) -> Unit,
    onThumbnailClick: (ExpandedThumbnail) -> Unit,
    onReviewSubmitted: (DetectionReviewSubmission) -> Unit,
    maxRuns: Int? = 6,
    modifier: Modifier = Modifier
) {
    val sortedRuns = remember(runs, maxRuns) {
        val sorted = runs.sortedByDescending { it.runNumber }
        maxRuns?.let { sorted.take(it) } ?: sorted
    }
    val bestTime = runs.minOfOrNull { it.timeSeconds }
    var expandedRunId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sortedRuns) {
        val visibleIds = sortedRuns.map { it.id }.toSet()
        val currentExpandedRunId = expandedRunId
        if (currentExpandedRunId != null && currentExpandedRunId !in visibleIds) {
            expandedRunId = null
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.race_session_runs),
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = pluralStringResource(R.plurals.race_total_runs, runs.size, runs.size),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                bestTime?.let {
                    Text(
                        text = stringResource(
                            R.string.race_best_time,
                            stringResource(R.string.common_seconds_value, formatRaceTime(it))
                        ),
                        color = AccentGreen,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            sortedRuns.forEachIndexed { index, run ->
                if (index > 0) {
                    HorizontalDivider(color = BorderSubtle)
                }
                SessionRunResultRow(
                    run = run,
                    isBest = bestTime != null && run.timeSeconds == bestTime,
                    showSpeedInResults = showSpeedInResults,
                    speedUnit = speedUnit,
                    isExpanded = expandedRunId == run.id,
                    onToggleExpanded = {
                        expandedRunId = if (expandedRunId == run.id) null else run.id
                    }
                )
                if (expandedRunId == run.id) {
                    ExpandedRunResultDetail(
                        run = run,
                        numberOfGates = numberOfGates,
                        showSpeedInResults = showSpeedInResults,
                        speedUnit = speedUnit,
                        detectionReviewEnabled = detectionReviewEnabled,
                        onThumbnailClick = onThumbnailClick,
                        onReviewSubmitted = onReviewSubmitted,
                        onDetailsClick = { onRunClick(run) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRunResultRow(
    run: RunEntity,
    isBest: Boolean,
    showSpeedInResults: Boolean,
    speedUnit: String,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val athleteName = run.athleteName?.takeIf { it.isNotBlank() }
    val athleteColor = run.athleteColor?.let { parseAthleteColor(it) } ?: AccentBlue
    val speedText = if (showSpeedInResults) {
        formatSpeed(run.distance, run.timeSeconds, speedUnit)
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggleExpanded)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(athleteColor.copy(alpha = 0.2f), CircleShape)
                .border(1.dp, athleteColor.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = athleteName?.take(1)?.uppercase() ?: run.runNumber.toString(),
                color = athleteColor,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = athleteName ?: stringResource(R.string.race_run_number_label, run.runNumber),
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (run.isPersonalBest) {
                    ResultBadge(label = stringResource(R.string.race_badge_personal_best_short), color = AccentOrange)
                } else if (run.isSeasonBest) {
                    ResultBadge(label = stringResource(R.string.race_badge_season_best_short), color = AccentGreen)
                }
            }

            Text(
                text = stringResource(
                    R.string.race_session_summary_format,
                    StartType.fromRawValue(run.startType).localizedDisplayName(),
                    formatDistance(run.distance)
                ),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.common_seconds_value, formatRaceTime(run.timeSeconds)),
                color = if (isBest) AccentGreen else TextPrimary,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = 1
            )
            speedText?.let {
                Text(
                    text = it,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        }

        Icon(
            imageVector = if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ExpandedRunResultDetail(
    run: RunEntity,
    numberOfGates: Int,
    showSpeedInResults: Boolean,
    speedUnit: String,
    detectionReviewEnabled: Boolean,
    onThumbnailClick: (ExpandedThumbnail) -> Unit,
    onReviewSubmitted: (DetectionReviewSubmission) -> Unit,
    onDetailsClick: () -> Unit
) {
    val segments = remember(run.splitsJson) { parseSegmentSplits(run.splitsJson) }
    val gateThumbnails = buildGateThumbnailSpecs(
        run = run,
        numberOfGates = numberOfGates,
        segments = segments,
        showSpeedInResults = showSpeedInResults,
        speedUnit = speedUnit,
        detectionReviewEnabled = detectionReviewEnabled
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            gateThumbnails.forEach { spec ->
                GateThumbnailCard(
                    spec = spec,
                    onThumbnailClick = onThumbnailClick,
                    onReviewSubmitted = onReviewSubmitted
                )
            }
        }

        if (segments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.race_splits),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                segments.forEach { segment ->
                    ExpandedSplitRow(
                        segment = segment,
                        showSpeedInResults = showSpeedInResults,
                        speedUnit = speedUnit
                    )
                }
            }
        }

        TextButton(
            onClick = onDetailsClick,
            modifier = Modifier
                .align(Alignment.End)
                .padding(horizontal = 8.dp)
        ) {
            Text(stringResource(R.string.run_detail_details), color = AccentBlue)
        }
    }
}

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun GateThumbnailCard(
    spec: RaceGateThumbnailSpec,
    onThumbnailClick: (ExpandedThumbnail) -> Unit,
    onReviewSubmitted: (DetectionReviewSubmission) -> Unit
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, spec.imagePath) {
        val loadedBitmap = withContext(Dispatchers.IO) {
            spec.imagePath
                ?.takeIf { it.isNotBlank() }
                ?.let { path ->
                    val file = File(path)
                    if (file.exists()) BitmapFactory.decodeFile(path) else null
                }
        }
        value = loadedBitmap
    }

    Column(
        modifier = Modifier.width(112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val currentBitmap = bitmap
        Box(
            modifier = Modifier
                .width(112.dp)
                .height(128.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(spec.placeholderColor.copy(alpha = 0.14f))
                .border(1.dp, spec.placeholderColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .then(
                    if (currentBitmap != null) {
                        Modifier.clickable {
                            onThumbnailClick(
                                ExpandedThumbnail(
                                    bitmap = currentBitmap,
                                    gatePosition = spec.gatePosition,
                                    detectorYPosition = spec.reviewTarget?.detectorY,
                                    reviewTarget = spec.reviewTarget,
                                    onReviewSubmitted = spec.reviewTarget?.let { onReviewSubmitted }
                                )
                            )
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (currentBitmap != null) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = spec.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Photo,
                        contentDescription = null,
                        tint = spec.placeholderColor.copy(alpha = 0.65f),
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = spec.label,
                        color = spec.placeholderColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }
            }
        }

        Text(
            text = spec.label,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1
        )
        Text(
            text = spec.timeSeconds?.let {
                stringResource(R.string.common_seconds_value, formatRaceTime(it))
            } ?: "-",
            color = TextPrimary,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1
        )
        Text(
            text = spec.speedText ?: " ",
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1
        )
    }
}

@Composable
private fun ExpandedSplitRow(
    segment: SegmentSplit,
    showSpeedInResults: Boolean,
    speedUnit: String
) {
    val cumulativeText = stringResource(
        R.string.race_cumulative_time,
        stringResource(
            R.string.common_seconds_value,
            formatSplitDuration(segment.cumulativeSplitNanos)
        )
    )
    val secondaryText = buildString {
        append(cumulativeText)
        if (showSpeedInResults) {
            val seconds = segment.splitNanos / 1_000_000_000.0
            append(" · ")
            append(formatSpeed(segment.distanceMeters, seconds, speedUnit))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    R.string.race_gate_number_range,
                    segment.fromGateIndex,
                    segment.toGateIndex
                ),
                color = TextPrimary,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = formatDistance(segment.distanceMeters),
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(
                    R.string.common_seconds_value,
                    formatSplitDuration(segment.splitNanos)
                ),
                color = AccentBlue,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
            Text(
                text = secondaryText,
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

private data class RaceGateThumbnailSpec(
    val label: String,
    val imagePath: String?,
    val gatePosition: Float?,
    val timeSeconds: Double?,
    val placeholderColor: Color,
    val speedText: String?,
    val reviewTarget: DetectionReviewTarget?
)

@Composable
private fun buildGateThumbnailSpecs(
    run: RunEntity,
    numberOfGates: Int,
    segments: List<SegmentSplit>,
    showSpeedInResults: Boolean,
    speedUnit: String,
    detectionReviewEnabled: Boolean
): List<RaceGateThumbnailSpec> {
    val finishGateIndex = numberOfGates.coerceAtLeast(2) - 1
    val lapImages = parseLapImagePaths(run.lapImagePathsJson)
    val lapReviewInfo = parseLapGateReviewInfo(run.localGateFramesDataJson)
    val segmentsByToGate = segments.associateBy { it.toGateIndex }
    val specs = mutableListOf<RaceGateThumbnailSpec>()
    val startDebug = parseThumbnailDebugReviewInfo(run.startThumbnailDebugJson)
    val finishDebug = parseThumbnailDebugReviewInfo(run.finishThumbnailDebugJson)
    val startGatePosition = startDebug?.linePosition
        ?: (run.startGatePosition ?: run.gatePosition).toFloat().coerceIn(0f, 1f)
    val finishGatePosition = finishDebug?.linePosition
        ?: (run.finishGatePosition ?: run.gatePosition).toFloat().coerceIn(0f, 1f)

    val startLabel = stringResource(R.string.device_role_start)
    val finishLabel = stringResource(R.string.device_role_finish)

    specs += RaceGateThumbnailSpec(
        label = stringResource(R.string.onboarding_preview_start_text),
        imagePath = run.startImagePath,
        gatePosition = startGatePosition,
        timeSeconds = null,
        placeholderColor = AccentGreen,
        speedText = null,
        reviewTarget = if (detectionReviewEnabled) {
            run.toRaceDetectionReviewTarget(
                gateLabel = startLabel,
                target = "start",
                gatePosition = startGatePosition,
                displayedTimeSeconds = null,
                crossingDirection = run.startCrossingDirection,
                velocityPxPerSec = run.startCrossingVelocity ?: startDebug?.velocityPxPerSec ?: run.crossingVelocity,
                workWidth = run.startWorkResolutionWidth ?: startDebug?.workWidth ?: run.workResolutionWidth,
                detectorY = startDebug?.detectorY,
                interpolationAlpha = startDebug?.interpolationAlpha,
                framePick = startDebug?.framePick,
                s0 = startDebug?.s0,
                s1 = startDebug?.s1,
                isFrontCamera = startDebug?.isFrontCamera,
                exposureMs = startDebug?.exposureMs,
                iso = startDebug?.iso,
                detectorTriggerFramePts = startDebug?.detectorTriggerFramePts,
                chosenThumbnailFramePts = startDebug?.chosenThumbnailFramePts,
                savedThumbnailFramePts = startDebug?.savedThumbnailFramePts
            )
        } else {
            null
        }
    )

    for (gateIndex in 1 until finishGateIndex) {
        val segment = segmentsByToGate[gateIndex]
        val lapInfo = lapReviewInfo[gateIndex]
        val lapGatePosition = lapInfo?.gatePosition
            ?: run.gatePosition.toFloat().coerceIn(0f, 1f)
        val lapTimeSeconds = segment?.cumulativeSplitNanos?.let { it / 1_000_000_000.0 }
        val lapLabel = stringResource(R.string.race_lap_label, gateIndex)
        specs += RaceGateThumbnailSpec(
            label = lapLabel,
            imagePath = lapImages[gateIndex],
            gatePosition = lapGatePosition,
            timeSeconds = lapTimeSeconds,
            placeholderColor = AccentOrange,
            speedText = segment?.takeIf { showSpeedInResults }?.let {
                formatSpeed(it.distanceMeters, it.splitNanos / 1_000_000_000.0, speedUnit)
            },
            reviewTarget = if (detectionReviewEnabled) {
                run.toRaceDetectionReviewTarget(
                    gateLabel = lapLabel,
                    target = "lap",
                    gatePosition = lapGatePosition,
                    displayedTimeSeconds = lapTimeSeconds,
                    crossingDirection = lapInfo?.crossingDirection,
                    velocityPxPerSec = lapInfo?.velocityPxPerSec,
                    workWidth = lapInfo?.workWidth,
                    detectorY = lapInfo?.detectorY,
                    interpolationAlpha = lapInfo?.interpolationAlpha,
                    framePick = lapInfo?.framePick,
                    s0 = lapInfo?.s0,
                    s1 = lapInfo?.s1,
                    isFrontCamera = lapInfo?.isFrontCamera,
                    exposureMs = lapInfo?.exposureMs,
                    iso = lapInfo?.iso,
                    detectorTriggerFramePts = lapInfo?.detectorTriggerFramePts,
                    chosenThumbnailFramePts = lapInfo?.chosenThumbnailFramePts,
                    savedThumbnailFramePts = lapInfo?.savedThumbnailFramePts
                )
            } else {
                null
            }
        )
    }

    specs += RaceGateThumbnailSpec(
        label = stringResource(R.string.onboarding_preview_finish_text),
        imagePath = run.finishImagePath ?: run.thumbnailPath,
        gatePosition = finishGatePosition,
        timeSeconds = run.timeSeconds,
        placeholderColor = AccentBlue,
        speedText = if (showSpeedInResults) {
            formatSpeed(run.distance, run.timeSeconds, speedUnit)
        } else {
            null
        },
        reviewTarget = if (detectionReviewEnabled) {
            run.toRaceDetectionReviewTarget(
                gateLabel = finishLabel,
                target = "finish",
                gatePosition = finishGatePosition,
                displayedTimeSeconds = run.timeSeconds,
                crossingDirection = run.finishCrossingDirection ?: run.startCrossingDirection,
                velocityPxPerSec = run.finishCrossingVelocity ?: finishDebug?.velocityPxPerSec ?: run.crossingVelocity,
                workWidth = run.finishWorkResolutionWidth ?: finishDebug?.workWidth ?: run.workResolutionWidth,
                detectorY = run.finishDetectorY?.toFloat() ?: finishDebug?.detectorY,
                interpolationAlpha = run.finishInterpolationAlpha ?: finishDebug?.interpolationAlpha,
                framePick = run.finishFramePick ?: finishDebug?.framePick,
                s0 = run.finishS0 ?: finishDebug?.s0,
                s1 = run.finishS1 ?: finishDebug?.s1,
                isFrontCamera = run.finishIsFrontCamera ?: finishDebug?.isFrontCamera,
                exposureMs = finishDebug?.exposureMs,
                iso = finishDebug?.iso,
                detectorTriggerFramePts = run.finishDetectorTriggerFramePts ?: finishDebug?.detectorTriggerFramePts,
                chosenThumbnailFramePts = run.finishChosenThumbnailFramePts ?: finishDebug?.chosenThumbnailFramePts,
                savedThumbnailFramePts = run.finishSavedThumbnailFramePts ?: finishDebug?.savedThumbnailFramePts
            )
        } else {
            null
        }
    )

    return specs
}

private fun RunEntity.toRaceDetectionReviewTarget(
    gateLabel: String,
    target: String,
    gatePosition: Float,
    displayedTimeSeconds: Double?,
    crossingDirection: String?,
    velocityPxPerSec: Double?,
    workWidth: Int?,
    detectorY: Float? = null,
    interpolationAlpha: Double? = null,
    framePick: String? = null,
    s0: Double? = null,
    s1: Double? = null,
    isFrontCamera: Boolean? = null,
    exposureMs: Double? = null,
    iso: Int? = null,
    detectorTriggerFramePts: Long? = null,
    chosenThumbnailFramePts: Long? = null,
    savedThumbnailFramePts: Long? = null
): DetectionReviewTarget {
    return DetectionReviewTarget(
        sessionId = sessionId,
        runId = id,
        runNumber = runNumber,
        numberOfPhones = numberOfPhones,
        gateLabel = gateLabel,
        target = target,
        mode = if (numberOfPhones <= 1) "solo" else "multi",
        distanceMeters = distance,
        startType = startType,
        displayedTimeSeconds = displayedTimeSeconds,
        originalGatePosition = gatePosition,
        crossingDirection = crossingDirection,
        detectorX = gatePosition,
        detectorY = detectorY,
        crossingVelocityPxPerSec = velocityPxPerSec,
        workWidth = workWidth,
        interpolationAlpha = interpolationAlpha,
        framePick = framePick,
        s0 = s0,
        s1 = s1,
        isFrontCamera = isFrontCamera,
        exposureMs = exposureMs,
        iso = iso,
        detectorTriggerFramePts = detectorTriggerFramePts,
        chosenThumbnailFramePts = chosenThumbnailFramePts,
        savedThumbnailFramePts = savedThumbnailFramePts
    )
}

private fun parseLapImagePaths(rawValue: String?): Map<Int, String> {
    if (rawValue.isNullOrBlank()) return emptyMap()
    return runCatching {
        Json.decodeFromString<Map<String, String>>(rawValue)
            .mapNotNull { (key, value) ->
                key.toIntOrNull()?.let { it to value }
            }
            .toMap()
    }.getOrDefault(emptyMap())
}

private fun parseLapGateReviewInfo(rawValue: String?): Map<Int, LapGateReviewInfo> {
    if (rawValue.isNullOrBlank()) return emptyMap()
    return runCatching {
        Json.parseToJsonElement(rawValue)
            .jsonArray
            .mapNotNull { element ->
                val obj = element.jsonObject
                val gateIndex = obj["gateIndex"]?.jsonPrimitive?.intOrNull
                val role = obj["role"]?.jsonPrimitive?.contentOrNull
                if (gateIndex != null && role == TimingRole.LAP_GATE.value) {
                    val debug = parseThumbnailDebugReviewInfo(
                        obj["thumbnailDebugJson"]?.jsonPrimitive?.contentOrNull
                    )
                    gateIndex to LapGateReviewInfo(
                        gatePosition = debug?.linePosition ?: obj["gatePosition"]
                            ?.jsonPrimitive
                            ?.doubleOrNull
                            ?.toFloat()
                            ?.coerceIn(0f, 1f),
                        velocityPxPerSec = obj["velocityPxPerSec"]?.jsonPrimitive?.doubleOrNull
                            ?: debug?.velocityPxPerSec,
                        crossingDirection = obj["crossingDirection"]?.jsonPrimitive?.contentOrNull,
                        workWidth = obj["workWidth"]?.jsonPrimitive?.intOrNull ?: debug?.workWidth,
                        detectorY = debug?.detectorY,
                        interpolationAlpha = debug?.interpolationAlpha,
                        framePick = debug?.framePick,
                        s0 = debug?.s0,
                        s1 = debug?.s1,
                        isFrontCamera = debug?.isFrontCamera,
                        exposureMs = debug?.exposureMs,
                        iso = debug?.iso,
                        detectorTriggerFramePts = debug?.detectorTriggerFramePts,
                        chosenThumbnailFramePts = debug?.chosenThumbnailFramePts,
                        savedThumbnailFramePts = debug?.savedThumbnailFramePts
                    )
                } else {
                    null
                }
            }
            .toMap()
    }.getOrDefault(emptyMap())
}

private data class LapGateReviewInfo(
    val gatePosition: Float?,
    val velocityPxPerSec: Double?,
    val crossingDirection: String?,
    val workWidth: Int?,
    val detectorY: Float?,
    val interpolationAlpha: Double?,
    val framePick: String?,
    val s0: Double?,
    val s1: Double?,
    val isFrontCamera: Boolean?,
    val exposureMs: Double?,
    val iso: Int?,
    val detectorTriggerFramePts: Long?,
    val chosenThumbnailFramePts: Long?,
    val savedThumbnailFramePts: Long?
)

private data class ThumbnailDebugReviewInfo(
    val linePosition: Float?,
    val detectorY: Float?,
    val velocityPxPerSec: Double?,
    val workWidth: Int?,
    val interpolationAlpha: Double?,
    val framePick: String?,
    val s0: Double?,
    val s1: Double?,
    val isFrontCamera: Boolean?,
    val exposureMs: Double?,
    val iso: Int?,
    val detectorTriggerFramePts: Long?,
    val chosenThumbnailFramePts: Long?,
    val savedThumbnailFramePts: Long?
)

private fun parseThumbnailDebugReviewInfo(rawValue: String?): ThumbnailDebugReviewInfo? {
    if (rawValue.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(rawValue).jsonObject
        ThumbnailDebugReviewInfo(
            linePosition = obj.floatFor(
                "interpolatedDisplayPosition",
                "projectedDisplayPosition",
                "detectorPosition",
                "configuredGatePosition"
            )?.coerceIn(0f, 1f),
            detectorY = obj.floatFor("detectorYPosition", "detectorY")?.coerceIn(0f, 1f),
            velocityPxPerSec = obj.doubleFor("velocityPxPerSec", "algo_velocity_px_per_sec"),
            workWidth = obj.intFor("workBufferW", "workWidth", "algo_work_width"),
            interpolationAlpha = obj.doubleFor("interpolationAlpha", "algo_interpolation_alpha"),
            framePick = obj.stringFor("chosenFramePick", "framePick"),
            s0 = obj.doubleFor("s0", "algo_s0"),
            s1 = obj.doubleFor("s1", "algo_s1"),
            isFrontCamera = obj.booleanFor("isFrontCamera"),
            exposureMs = obj.doubleFor("savedImageExposureDurationMs", "exposureMs"),
            iso = obj.intFor("savedImageISO", "iso"),
            detectorTriggerFramePts = obj.longFor("detectorTriggerFramePtsNanos", "detectorTriggerFramePts"),
            chosenThumbnailFramePts = obj.longFor("chosenThumbnailFramePtsNanos", "chosenThumbnailFramePts"),
            savedThumbnailFramePts = obj.longFor("savedThumbnailFramePtsNanos", "savedThumbnailFramePts")
        )
    }.getOrNull()
}

private fun JsonObject.doubleFor(vararg keys: String): Double? {
    return keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.doubleOrNull
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    }
}

private fun JsonObject.floatFor(vararg keys: String): Float? {
    return doubleFor(*keys)?.toFloat()
}

private fun JsonObject.intFor(vararg keys: String): Int? {
    return keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.intOrNull
            ?: this[key]?.jsonPrimitive?.doubleOrNull?.toInt()
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    }
}

private fun JsonObject.longFor(vararg keys: String): Long? {
    return keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.longOrNull
            ?: this[key]?.jsonPrimitive?.doubleOrNull?.toLong()
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }
}

private fun JsonObject.stringFor(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.contentOrNull
    }
}

private fun JsonObject.booleanFor(vararg keys: String): Boolean? {
    return keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.booleanOrNull
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    }
}

@Composable
private fun ResultBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaceRunDetailSheet(
    run: RunEntity,
    showSpeedInResults: Boolean,
    speedUnit: String,
    onDistanceChanged: (Double) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var showEditDistanceSheet by remember(run.id) { mutableStateOf(false) }
    var showDeleteDialog by remember(run.id) { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.run_detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.race_delete_run_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.run_detail_delete_confirm), color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    if (showEditDistanceSheet) {
        EditRaceRunDistanceSheet(
            currentDistance = run.distance,
            onDismiss = { showEditDistanceSheet = false },
            onSave = { newDistance ->
                showEditDistanceSheet = false
                onDistanceChanged(newDistance)
            }
        )
    }

    // iOS c57925da: open at full height so the result reads as one focused
    // surface instead of leaving the live timer visible behind a half sheet.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.race_run_number_label, run.runNumber),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.race_pill_done), color = AccentBlue)
                }
            }

            RaceRunTimeSummary(timeSeconds = run.timeSeconds)

            RaceRunPhoto(thumbnailPath = run.thumbnailPath)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RaceRunStatCard(
                        title = stringResource(R.string.run_detail_speed_label),
                        value = if (showSpeedInResults) {
                            formatSpeed(run.distance, run.timeSeconds, speedUnit)
                        } else {
                            "--"
                        },
                        icon = Icons.Outlined.Speed,
                        modifier = Modifier.weight(1f)
                    )
                    RaceRunStatCard(
                        title = stringResource(R.string.run_detail_distance_label),
                        value = formatDistance(run.distance),
                        icon = Icons.Outlined.Straighten,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RaceRunStatCard(
                        title = stringResource(R.string.device_role_start),
                        value = StartType.fromRawValue(run.startType).localizedShortName(),
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        modifier = Modifier.weight(1f)
                    )
                    RaceRunStatCard(
                        title = stringResource(R.string.race_recorded_label),
                        value = remember(run.createdAt) {
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(run.createdAt))
                        },
                        icon = Icons.Outlined.Schedule,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            run.athleteName?.takeIf { it.isNotBlank() }?.let { athleteName ->
                val athleteColor = run.athleteColor?.let { parseAthleteColor(it) } ?: AccentBlue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBackground, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(athleteColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = athleteName.take(1).uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Text(
                        text = athleteName,
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { showEditDistanceSheet = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Straighten,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.run_detail_edit_distance))
                }
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.run_detail_delete_confirm))
                }
            }
        }
        }
    }
}

/**
 * Leading, left-aligned time card (iOS `RunDetailSheet.timeSummary`). Long
 * efforts switch to minutes and seconds like the live timing screen.
 */
@Composable
private fun RaceRunTimeSummary(timeSeconds: Double) {
    val displayTime = formatRaceTime(timeSeconds)
    val accessibilityLabel = stringResource(R.string.race_run_time_accessibility, displayTime)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBackground)
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(R.string.race_run_time_label),
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp
            )
        )
        Text(
            text = displayTime,
            color = AccentGreen,
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun RaceRunPhoto(thumbnailPath: String?) {
    val bitmap by produceState<Bitmap?>(initialValue = null, thumbnailPath) {
        val loadedBitmap = withContext(Dispatchers.IO) {
            thumbnailPath
                ?.takeIf { it.isNotBlank() }
                ?.let { path ->
                    val file = File(path)
                    if (file.exists()) BitmapFactory.decodeFile(path) else null
                }
        }
        value = loadedBitmap
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.race_photo_finish_cd),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(2.dp, AccentGreen.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Fit
        )
    } else {
        // A missing photo is a compact note, not a large empty placeholder.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBackground.copy(alpha = 0.6f))
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.NoPhotography,
                    contentDescription = null,
                    tint = TextSecondary
                )
            }
            Text(
                text = stringResource(R.string.race_no_photo),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun RaceRunStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = value,
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditRaceRunDistanceSheet(
    currentDistance: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    val presets = listOf(10.0, 20.0, 30.0, 40.0, 60.0, 100.0)
    var selectedDistance by remember(currentDistance) { mutableStateOf(currentDistance) }
    var customInput by remember(currentDistance) { mutableStateOf("") }
    val customDistance = customInput.toDoubleOrNull()
    val distanceToSave = customDistance ?: selectedDistance

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.run_detail_edit_distance),
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { distance ->
                    val selected = customInput.isBlank() && selectedDistance == distance
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedDistance = distance
                            customInput = ""
                        },
                        label = { Text(formatDistance(distance)) }
                    )
                }
            }

            OutlinedTextField(
                value = customInput,
                onValueChange = { value ->
                    customInput = value.filter { it.isDigit() || it == '.' }
                },
                label = { Text(stringResource(R.string.run_detail_custom_distance)) },
                suffix = { Text(stringResource(R.string.common_meters_abbreviation)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = BorderSubtle,
                    focusedLabelColor = AccentBlue,
                    unfocusedLabelColor = TextSecondary,
                    cursorColor = AccentBlue
                )
            )

            PillButton(
                text = stringResource(R.string.race_save_distance),
                backgroundColor = AccentBlue,
                enabled = distanceToSave > 0.0,
                onClick = { onSave(distanceToSave) }
            )
        }
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
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(if (enabled) backgroundColor else BorderSubtle, RoundedCornerShape(25.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else TextSecondary,
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
        String.format(Locale.getDefault(), "%d:%02d.%02d", mins, secs, hundredths)
    } else {
        String.format(Locale.getDefault(), "%d.%02d", secs, hundredths)
    }
}
