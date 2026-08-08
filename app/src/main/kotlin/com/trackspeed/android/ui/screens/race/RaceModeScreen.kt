package com.trackspeed.android.ui.screens.race

import android.Manifest
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
import com.trackspeed.android.ui.components.PerpendicularDialView
import com.trackspeed.android.ui.components.StartMode
import com.trackspeed.android.ui.components.StartOverlaySelector
import com.trackspeed.android.ui.components.ThumbnailViewerDialog
import com.trackspeed.android.ui.components.TimingSessionEndOverlay
import com.trackspeed.android.ui.components.TouchStartOverlay
import com.trackspeed.android.ui.components.VoiceStartOverlay
import com.trackspeed.android.ui.components.VoiceStartOverlaySettings
import com.trackspeed.android.ui.components.VoiceStartOverlaySettingsActions
import com.trackspeed.android.ui.screens.settings.applyLanguage
import com.trackspeed.android.ui.theme.*
import com.trackspeed.android.ui.util.formatDistance
import com.trackspeed.android.ui.util.formatSegmentLabel
import com.trackspeed.android.ui.util.formatSpeed
import com.trackspeed.android.ui.util.formatSplitDuration
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
    ) { permissions ->
        hasBluetoothPermission = permissions.values.all { it }
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
        val permissions = requiredBlePermissions()
        hasBluetoothPermission = permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (hasBluetoothPermission) {
            viewModel.onBluetoothPermissionResult(granted = true)
        } else {
            blePermissionLauncher.launch(permissions)
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
            // Top bar
            RaceTopBar(
                phase = uiState.phase,
                connectedDeviceCount = uiState.connectedDeviceCount,
                onBack = {
                    if (uiState.phase == RacePhase.PAIRING) {
                        viewModel.resetToStart()
                        onNavigateBack()
                    } else {
                        viewModel.presentSessionEndConfirmation()
                    }
                }
            )

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
                "Ready to time"
            } else if (hasRequiredConnections) {
                "Synchronizing phones"
            } else if (isJoinMode) {
                "Join Session"
            } else if (isHostingSession) {
                "Create Session"
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
                "$displayedConnectedDeviceCount phones connected — tap Start Timing when ready"
            } else if (hasRequiredConnections) {
                "${syncedDeviceCount.coerceAtMost(requiredDeviceCount)} of $requiredDeviceCount phones synchronized"
            } else if (isJoinMode) {
                "Open Create Session on the host phone and keep it on the connect screen."
            } else if (isHostingSession) {
                val neededPhones = if (isControlOnlyHost) {
                    numberOfGates.coerceAtLeast(2)
                } else {
                    (numberOfGates - 1).coerceAtLeast(1)
                }
                if (neededPhones == 1) {
                    "On the other phone, tap Join Session. No account or subscription needed."
                } else if (isControlOnlyHost) {
                    "On each timing phone, open TrackSpeed and tap Join Session."
                } else {
                    "On each timing phone, open TrackSpeed and tap Join Session."
                }
            } else if (connectedDeviceCount > 0) {
                "$connectedDeviceCount of $requiredDeviceCount phones connected"
            } else {
                "Open race mode on all $requiredDeviceCount phones — they will auto-discover via Bluetooth"
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
                text = "Start Timing",
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
    val title = if (isSearching) "Scanning with Bluetooth" else "Ready to connect"
    val detail = if (isSearching) {
        "The host must stay on Create Session with Bluetooth enabled."
    } else {
        "Find a host phone over Bluetooth."
    }
    val hostDetail = if (isSearching) {
        "Create Session must stay open while this phone searches."
    } else {
        "Open Create Session and stay on the connect screen."
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
                        text = if (isSearching) "SEARCHING" else "JOIN SESSION",
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
                        text = if (isSearching) "Waiting" else "Bluetooth",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = if (isSearching) "STATUS" else "MODE",
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
                        title = "Host phone",
                        detail = hostDetail
                    ),
                    JoinSessionInfo(
                        icon = Icons.Filled.Bluetooth,
                        title = "This phone",
                        detail = "Bluetooth must be enabled on both phones."
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
        missingJoiners == 1 -> "Waiting for 1 more phone"
        missingJoiners > 1 -> "Waiting for $missingJoiners more phones"
        else -> "Phones connected"
    }
    val detail = if (isReady) {
        "Roles are assigned automatically. Continue when the lineup looks right."
    } else if (hostRole == TimingRole.CONTROL_ONLY) {
        "On each timing phone, open TrackSpeed and tap Join Session."
    } else {
        "On the other phone, open TrackSpeed and tap Join Session."
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
                            text = if (isReady) "Ready" else "Connect",
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
                        text = if (isReady) "Ready" else "$connectedJoiners/$requiredJoiners",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = accent
                    )
                    Text(
                        text = if (isReady) "STATUS" else "PHONES",
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
                    text = "$displayedConnectedDeviceCount/$requiredDeviceCount phones",
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
                    text = "GATE LINEUP",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    ),
                    color = TextTertiary
                )
                Text(
                    text = "$connectedDeviceCount/$requiredDeviceCount phones",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connectedDeviceCount >= requiredDeviceCount) AccentGreen else TextSecondary
                )
            }

            GateLineupRow(
                role = hostDeviceRole,
                deviceName = "This phone",
                status = "Ready",
                isWaiting = false,
                isHost = true
            )

            repeat(connectedJoiners) { index ->
                GateLineupRow(
                    role = roleForTimingPhone(index, requiredJoiners, hostRole),
                    deviceName = if (connectedJoiners == 1) "Timing phone" else "Timing phone ${index + 1}",
                    status = "Connected",
                    isWaiting = false,
                    isHost = false
                )
            }

            repeat(requiredJoiners - connectedJoiners) { offset ->
                val index = connectedJoiners + offset
                GateLineupRow(
                    role = roleForTimingPhone(index, requiredJoiners, hostRole),
                    deviceName = null,
                    status = "Waiting",
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
                text = deviceName ?: "Open TrackSpeed and tap Join Session",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1
            )
        }

        Text(
            text = if (isHost) "Host" else status,
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
    DeviceRole.LAP -> "Split"
    DeviceRole.FINISH -> stringResource(R.string.race_role_finish)
    DeviceRole.CONTROL -> "Control"
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
    val (label, color) = when (role) {
        DeviceRole.START -> stringResource(R.string.race_role_start) to AccentGreen
        DeviceRole.LAP -> "Split" to AccentOrange
        DeviceRole.FINISH -> stringResource(R.string.race_role_finish) to AccentRed
        DeviceRole.CONTROL -> "Control" to AccentBlue
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
                trackColor = BorderSubtle,
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
            trackColor = BorderSubtle
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
    var showStartTypeSelector by remember { mutableStateOf(false) }
    val selectedStartMode = StartMode.fromString(uiState.startType)

    if (showStartTypeSelector) {
        StartOverlaySelector(
            currentMode = selectedStartMode,
            onModeSelected = { mode -> viewModel.setStartType(mode.rawValue) },
            onDismiss = { showStartTypeSelector = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.isHostingSession) {
            SyncStatusCard(
                quality = uiState.syncQuality,
                uncertaintyMs = uiState.syncUncertaintyMs,
                offsetMs = uiState.syncOffsetMs
            )

            RoleBadge(role = uiState.role)

            ActiveAthleteChipBar(
                uiState = uiState,
                onActiveAthleteSelected = viewModel::setActiveAthlete
            )

            StartTypeConfigCard(
                startMode = selectedStartMode,
                enabled = true,
                onClick = { showStartTypeSelector = true }
            )

            DistanceSelector(
                currentDistance = uiState.distanceMeters,
                enabled = true,
                onDistanceChanged = viewModel::setDistance
            )

            GateDistanceConfigCard(
                uiState = uiState,
                onSegmentDistanceChanged = viewModel::setSegmentDistance
            )
        } else {
            JoinReadyStatusCard(uiState = uiState)
            JoinReadyRoleSummaryCard(uiState = uiState, startMode = selectedStartMode)
        }

        if (uiState.requiresLocalCamera) {
            // Camera preview (small)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(2.dp, BorderSubtle, RoundedCornerShape(20.dp))
            ) {
                if (uiState.hasPermission && uiState.cameraState !is CameraManager.CameraState.Error) {
                    CameraPreview(
                        gatePosition = uiState.gatePosition,
                        onGatePositionChanged = viewModel::setGatePosition,
                        gateLineDraggable = !uiState.isLocalGateCalibrating &&
                            !uiState.localGateStatus.isCalibrated,
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
        }

        if (uiState.requiresLocalCamera) {
            GateAlignmentCard()

            GateCalibrationCard(
                role = uiState.role,
                isCalibrating = uiState.isLocalGateCalibrating,
                isCalibrated = uiState.localGateStatus.isCalibrated,
                onCalibrate = viewModel::beginLocalGateCalibration
            )
        }

        if (uiState.isHostingSession && uiState.requiredPhysicalDeviceCount > 1) {
            GateReadinessCard(uiState = uiState)
        }

        Text(
            text = if (uiState.role == DeviceRole.CONTROL) {
                "Keep this phone with the coach. Other phones time the gates."
            } else {
                stringResource(
                    R.string.race_position_instruction,
                    when (uiState.role) {
                        DeviceRole.START -> stringResource(R.string.race_role_start).lowercase()
                        DeviceRole.LAP -> "split gate"
                        DeviceRole.FINISH -> stringResource(R.string.race_role_finish).lowercase()
                        DeviceRole.CONTROL -> "control"
                        null -> stringResource(R.string.race_position_instruction_gate)
                    }
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.isHostingSession) {
            val canStartSession = uiState.canStartSession
            PillButton(
                text = stringResource(R.string.race_start_session),
                backgroundColor = if (canStartSession) AccentGreen else BorderSubtle,
                contentColor = if (canStartSession) Color.White else TextSecondary,
                onClick = onStartSession,
                enabled = canStartSession
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
                    text = "Waiting for host to start",
                    color = TextSecondary,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
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
            text = "ACTIVE ATHLETE",
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
                        text = "GATE READINESS",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextTertiary
                    )
                    Text(
                        text = if (allReady) "All gates armed" else "Waiting for armed gates",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                Text(
                    text = "$readyRemoteGates/$requiredRemoteGates",
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
                    text = if (localReady) "This phone ready" else "Calibrate this phone",
                    color = if (localReady) AccentGreen else TextSecondary
                )
                PairingMetricChip(
                    icon = Icons.Outlined.PhoneAndroid,
                    text = "$readyRemoteGates joined ready",
                    color = if (readyRemoteGates >= requiredRemoteGates) AccentGreen else TextSecondary
                )
            }
        }
    }
}

@Composable
private fun GateAlignmentCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, AccentBlue.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "ALIGNMENT",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextTertiary
                )
                Text(
                    text = "Set the track direction, then rotate this phone perpendicular.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }

            PerpendicularDialView(
                modifier = Modifier.fillMaxWidth(),
                size = 180.dp
            )
        }
    }
}

@Composable
private fun GateCalibrationCard(
    role: DeviceRole?,
    isCalibrating: Boolean,
    isCalibrated: Boolean,
    onCalibrate: () -> Unit
) {
    val accent = when {
        isCalibrated -> AccentGreen
        isCalibrating -> AccentOrange
        else -> AccentRed
    }
    val roleLabel = when (role) {
        DeviceRole.START -> "start"
        DeviceRole.LAP -> "split"
        DeviceRole.FINISH -> "finish"
        else -> "gate"
    }
    val title = when {
        isCalibrated -> "Calibrated"
        isCalibrating -> "Calibrating..."
        else -> "Position Gate Line"
    }
    val detail = when {
        isCalibrated -> "Gate line is set. Keep the phone steady until timing starts."
        isCalibrating -> "Hold the phone steady while the camera settles."
        else -> "Drag the red line to the $roleLabel position, then calibrate."
    }

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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCalibrating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = accent,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isCalibrated) Icons.Outlined.CheckCircle else Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            if (!isCalibrated) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (isCalibrating) BorderSubtle else accent)
                        .clickable(enabled = !isCalibrating) { onCalibrate() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isCalibrating) "Calibrating..." else "Calibrate",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isCalibrating) TextSecondary else Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun JoinReadyStatusCard(uiState: RaceModeUiState) {
    val isSynced = uiState.syncQuality != null || uiState.syncProgress >= 1f
    val accent = if (isSynced) AccentGreen else AccentBlue
    val hostName = uiState.connectedDeviceName
        .takeUnless { it.isBlank() || it == "Other Device" }
        ?: "host phone"

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
                        text = "CONNECTED",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = accent
                    )
                    Text(
                        text = "Waiting for host",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "Connected to $hostName. Stay on this screen until timing starts.",
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
                        text = "SYNC",
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
    val sessionSummary = "${formatDistance(uiState.distanceMeters)} / ${startMode.displayName}"
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
                        text = "YOUR ROLE",
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

private fun joinReadyRoleName(role: DeviceRole?, startType: StartType): String {
    return when (role) {
        DeviceRole.START -> if (startType.usesStartTrigger) startType.startTriggerRoleName else "Start Gate"
        DeviceRole.LAP -> "Split Gate"
        DeviceRole.FINISH -> "Finish Gate"
        DeviceRole.CONTROL -> "Control Only"
        null -> "Assigned Gate"
    }
}

private fun joinReadyRoleDetail(role: DeviceRole?, startType: StartType): String {
    return when (role) {
        DeviceRole.START -> if (startType.usesStartTrigger) {
            startType.startTriggerHint
        } else {
            "Use this phone at the start line."
        }
        DeviceRole.LAP -> "Use this phone at the assigned split point."
        DeviceRole.FINISH -> "Use this phone at the finish line."
        DeviceRole.CONTROL -> "This phone controls the session without camera timing."
        null -> "The host will assign this phone to a timing gate."
    }
}

private fun joinReadyGateSummary(uiState: RaceModeUiState): String {
    val gateIndex = uiState.localGateIndex ?: uiState.gateAssignment?.gateIndex
    val gateDistance = uiState.localGateDistanceMeters ?: uiState.gateAssignment?.distanceFromStart
    return if (gateIndex != null && gateDistance != null && gateDistance > 0.0) {
        "Gate ${gateIndex + 1} at ${formatDistance(gateDistance)}"
    } else {
        "${uiState.numberOfGates} gates"
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
                    text = startMode.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Text(
                    text = startMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2
                )
            }

            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Change start type",
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
                    text = "$gateCount ${if (gateCount == 1) "gate" else "gates"} configured",
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
                text = "$fromLabel to $toLabel",
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
                contentDescription = "Decrease segment distance",
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
                contentDescription = "Increase segment distance",
                tint = if (enabled) TextPrimary else TextTertiary
            )
        }
    }
}

private fun gateLabelForUi(gateIndex: Int, gateCount: Int): String {
    return when (gateIndex) {
        0 -> "Start"
        gateCount - 1 -> "Finish"
        else -> "Gate $gateIndex"
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
    var selectedRun by remember(uiState.phase) { mutableStateOf<RunEntity?>(null) }
    var expandedThumbnail by remember(uiState.phase) { mutableStateOf<ExpandedThumbnail?>(null) }
    val startMode = StartMode.fromString(uiState.startType)
    val canTriggerExternalStart = uiState.role == DeviceRole.START &&
        startMode in listOf(StartMode.TOUCH, StartMode.COUNTDOWN, StartMode.VOICE) &&
        uiState.raceStatus != "started"
    val hasAudibleStartCue = startMode == StartMode.COUNTDOWN || startMode == StartMode.VOICE
    val isDetectionPaused = uiState.raceStatus == "paused"
    val canCancelRun = !isDetectionPaused && (
        uiState.raceStatus in setOf("started", "collecting_gates", "waiting_for_result") ||
        uiState.elapsedTimeSeconds > 0.0 ||
        uiState.receivedGateCount > 0
    )
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
            // Status banner
            ActiveRaceBanner(
                detectionState = uiState.detectionState,
                raceStatus = uiState.raceStatus
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

            if (uiState.requiresLocalCamera) {
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
                            gateLineDraggable = false,
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
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(CardBackground)
                        .border(2.dp, BorderSubtle, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Control phone",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "This phone starts runs and collects results while the timing phones watch the gates.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Timer display
            TimerDisplay(
                role = uiState.role,
                elapsedTimeSeconds = uiState.elapsedTimeSeconds,
                isRunning = uiState.elapsedTimeSeconds > 0,
                distanceMeters = uiState.distanceMeters,
                numberOfGates = uiState.numberOfGates
            )

            if (uiState.completedRuns.isNotEmpty()) {
                LiveResultsSummaryStrip(
                    runs = uiState.completedRuns,
                    onClick = { showLiveResults = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (canTriggerExternalStart) {
                StartTriggerButton(
                    startMode = startMode,
                    onClick = { activeStartOverlay = startMode }
                )
            }

            if (uiState.numberOfGates > 2) {
                GateCollectionStatus(
                    receivedGateCount = uiState.receivedGateCount,
                    numberOfGates = uiState.numberOfGates
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

            // Role and sync info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RoleBadge(role = uiState.role)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.isHostingSession) {
                        IconButton(
                            onClick = { showSessionSettings = true },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(BorderSubtle)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = "Session settings",
                                tint = TextSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    if (uiState.syncQuality != null) {
                        SyncQualityBadge(quality = uiState.syncQuality)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSettingsSheet(
    uiState: RaceModeUiState,
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
                    text = "Session Settings",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close session settings",
                        tint = TextSecondary
                    )
                }
            }

            SessionSettingsSection(title = "Test Type") {
                StartMode.entries.chunked(2).forEach { rowModes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowModes.forEach { mode ->
                            SessionSettingsChip(
                                text = mode.displayName,
                                icon = mode.icon,
                                selected = pendingStartType == mode.rawValue,
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
            }

            SessionSettingsSection(title = "Distance") {
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
                    label = { Text("Custom distance") },
                    suffix = { Text("m") },
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
                SessionSettingsSection(title = "Gate Segments") {
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
                title = "Athletes",
                trailing = if (pendingSelectedAthleteIds.isNotEmpty()) {
                    "${pendingSelectedAthleteIds.size} selected"
                } else {
                    null
                }
            ) {
                if (uiState.athletes.isEmpty()) {
                    EmptySettingsRow(
                        icon = Icons.Outlined.PersonAdd,
                        text = "No athletes added yet"
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
                SessionSettingsSection(title = "Next Result") {
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

            SessionSettingsSection(title = "Performance") {
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
                        text = "Start Sound",
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
                            text = soundType.displayName,
                            selected = pendingStartSoundType == soundType.rawValue,
                            modifier = Modifier.weight(1f)
                        ) {
                            pendingStartSoundType = soundType.rawValue
                        }
                    }
                }

                Text(
                    text = StartSoundType.fromRawValue(pendingStartSoundType).subtitle,
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
                            text = "Show Speed",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Display speed in run results.",
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
                    label = "Camera FPS",
                    value = if (uiState.fps > 0) "${uiState.fps} fps" else "Starting"
                )
                SettingsMetricRow(
                    icon = Icons.Outlined.Sync,
                    label = "Clock Sync",
                    value = uiState.syncQuality?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Syncing"
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
            text = "End Session",
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
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentBlue else BorderSubtle)
            .clickable(onClick = onClick)
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
    PillButton(
        text = stringResource(R.string.timing_btn_cancel_run),
        backgroundColor = AccentRed,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun PauseResumeButton(
    isPaused: Boolean,
    onClick: () -> Unit
) {
    PillButton(
        text = if (isPaused) "Resume" else "Pause",
        backgroundColor = if (isPaused) AccentBlue else BorderSubtle,
        contentColor = if (isPaused) Color.White else TextPrimary,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun StartTriggerButton(
    startMode: StartMode,
    onClick: () -> Unit
) {
    PillButton(
        text = when (startMode) {
            StartMode.TOUCH -> "Open Touch Start"
            StartMode.COUNTDOWN -> "Open Countdown"
            StartMode.VOICE -> "Open Voice Start"
            StartMode.FLYING -> "Await Gate Crossing"
            StartMode.INFRAME -> "Await In-Frame Start"
        },
        backgroundColor = AccentBlue,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
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
                "collecting_gates" -> "Collecting gate crossings"
                "waiting_for_result" -> "Waiting for host result"
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
private fun GateCollectionStatus(
    receivedGateCount: Int,
    numberOfGates: Int
) {
    Text(
        text = "$receivedGateCount/$numberOfGates gates reported",
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
                "${formatDistance(distanceMeters)} · $numberOfGates ${if (numberOfGates == 1) "gate" else "gates"}"
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
                        label = "Gates",
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
                                label = "Gate ${segment.fromGateIndex}-${segment.toGateIndex}",
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
                            DeviceRole.LAP -> "Split"
                            DeviceRole.FINISH -> stringResource(R.string.race_role_finish)
                            DeviceRole.CONTROL -> "Control"
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
                    text = "Waiting for host to arm next run",
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
                label = "Runs",
                value = runs.size.toString(),
                color = AccentBlue,
                modifier = Modifier.weight(1f)
            )
            LiveResultsStat(
                label = "Best",
                value = best?.let { "${formatRaceTime(it.timeSeconds)}s" } ?: "--",
                color = AccentGreen,
                modifier = Modifier.weight(1f)
            )
            LiveResultsStat(
                label = "Avg",
                value = average?.let { "${formatRaceTime(it)}s" } ?: "--",
                color = AccentOrange,
                modifier = Modifier.weight(1f)
            )
            LiveResultsStat(
                label = "Latest",
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
                        text = "${StartType.fromRawValue(uiState.startType).displayName} ${formatDistance(uiState.distanceMeters)}",
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
                            text = "No runs yet",
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
                        text = "Session Runs",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${runs.size} total",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                bestTime?.let {
                    Text(
                        text = "Best ${formatRaceTime(it)}s",
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
                    text = athleteName ?: "Run #${run.runNumber}",
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (run.isPersonalBest) {
                    ResultBadge(label = "PR", color = AccentOrange)
                } else if (run.isSeasonBest) {
                    ResultBadge(label = "SB", color = AccentGreen)
                }
            }

            Text(
                text = "${StartType.fromRawValue(run.startType).displayName} ${formatDistance(run.distance)}",
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
                text = "${formatRaceTime(run.timeSeconds)}s",
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
                    text = "Splits",
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
            text = spec.timeSeconds?.let { "${formatRaceTime(it)}s" } ?: "-",
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
                text = formatSegmentLabel(segment),
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
                text = "${formatSplitDuration(segment.splitNanos)}s",
                color = AccentBlue,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
            Text(
                text = buildString {
                    append("${formatSplitDuration(segment.cumulativeSplitNanos)}s cumulative")
                    if (showSpeedInResults) {
                        val seconds = segment.splitNanos / 1_000_000_000.0
                        append(" · ")
                        append(formatSpeed(segment.distanceMeters, seconds, speedUnit))
                    }
                },
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

    specs += RaceGateThumbnailSpec(
        label = "START",
        imagePath = run.startImagePath,
        gatePosition = startGatePosition,
        timeSeconds = null,
        placeholderColor = AccentGreen,
        speedText = null,
        reviewTarget = if (detectionReviewEnabled) {
            run.toRaceDetectionReviewTarget(
                gateLabel = "Start",
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
        specs += RaceGateThumbnailSpec(
            label = "LAP $gateIndex",
            imagePath = lapImages[gateIndex],
            gatePosition = lapGatePosition,
            timeSeconds = lapTimeSeconds,
            placeholderColor = AccentOrange,
            speedText = segment?.takeIf { showSpeedInResults }?.let {
                formatSpeed(it.distanceMeters, it.splitNanos / 1_000_000_000.0, speedUnit)
            },
            reviewTarget = if (detectionReviewEnabled) {
                run.toRaceDetectionReviewTarget(
                    gateLabel = "Lap $gateIndex",
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
        label = "FINISH",
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
                gateLabel = "Finish",
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
            text = { Text("This run will be permanently removed from the session.") },
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Run #${run.runNumber}",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.race_pill_done), color = AccentBlue)
                }
            }

            RaceRunPhoto(thumbnailPath = run.thumbnailPath)

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = formatRaceTime(run.timeSeconds),
                    color = AccentGreen,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Text(
                    text = stringResource(R.string.run_detail_seconds_label),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RaceRunStatCard(
                        title = "Speed",
                        value = if (showSpeedInResults) {
                            formatSpeed(run.distance, run.timeSeconds, speedUnit)
                        } else {
                            "--"
                        },
                        icon = Icons.Outlined.Speed,
                        modifier = Modifier.weight(1f)
                    )
                    RaceRunStatCard(
                        title = "Distance",
                        value = formatDistance(run.distance),
                        icon = Icons.Outlined.Straighten,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RaceRunStatCard(
                        title = "Start",
                        value = StartType.fromRawValue(run.startType).shortName,
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        modifier = Modifier.weight(1f)
                    )
                    RaceRunStatCard(
                        title = "Recorded",
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CardBackground)
            .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "Photo finish",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = TextSecondary.copy(alpha = 0.55f),
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = "No photo available",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
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
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
                label = { Text("Custom distance") },
                suffix = { Text("m") },
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
                text = "Save Distance",
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
