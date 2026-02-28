package com.trackspeed.android.ui.screens.sync

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.sync.SyncQuality
import com.trackspeed.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockSyncScreen(
    onSyncComplete: () -> Unit = {},
    viewModel: ClockSyncViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var hasBluetoothPermission by remember { mutableStateOf(false) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasBluetoothPermission = permissions.values.all { it }
    }

    // Request permissions on launch
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
        permissionLauncher.launch(permissions)
    }

    // Navigate on sync complete
    LaunchedEffect(uiState.status) {
        if (uiState.status == SyncStatus.SYNCED) {
            // Delay to show success state
            kotlinx.coroutines.delay(1500)
            onSyncComplete()
        }
    }

    Box(modifier = Modifier.fillMaxSize().gradientBackground()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title), color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status indicator
            SyncStatusCard(uiState)

            Spacer(modifier = Modifier.height(16.dp))

            // Instructions
            when (uiState.status) {
                SyncStatus.NOT_SYNCED -> {
                    Text(
                        stringResource(R.string.sync_instruction_auto),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { viewModel.startSync() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasBluetoothPermission
                    ) {
                        Text(stringResource(R.string.sync_pair_button))
                    }

                    if (!hasBluetoothPermission) {
                        Text(
                            stringResource(R.string.sync_bluetooth_required),
                            color = Color(0xFFFF453A),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                SyncStatus.WAITING_FOR_PEER -> {
                    Text(
                        stringResource(R.string.sync_searching),
                        textAlign = TextAlign.Center,
                        color = TextPrimary
                    )
                    Text(
                        stringResource(R.string.sync_searching_hint),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    CircularProgressIndicator()

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = { viewModel.stop() }) {
                        Text(stringResource(R.string.sync_cancel))
                    }
                }

                SyncStatus.CONNECTING -> {
                    Text(stringResource(R.string.sync_connecting), color = TextPrimary)
                    CircularProgressIndicator()

                    TextButton(onClick = { viewModel.stop() }) {
                        Text(stringResource(R.string.sync_cancel))
                    }
                }

                SyncStatus.SYNCING -> {
                    Text(
                        stringResource(R.string.sync_syncing),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LinearProgressIndicator(
                        progress = { uiState.progress },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        stringResource(R.string.sync_progress_percent, (uiState.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    TextButton(onClick = { viewModel.stop() }) {
                        Text(stringResource(R.string.sync_cancel))
                    }
                }

                SyncStatus.SYNCED -> {
                    SyncSuccessDetails(uiState)

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(onClick = onSyncComplete) {
                        Text(stringResource(R.string.sync_continue))
                    }
                }

                SyncStatus.ERROR -> {
                    Text(
                        uiState.errorMessage ?: stringResource(R.string.sync_status_error),
                        color = Color(0xFFFF453A),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(onClick = { viewModel.stop() }) {
                        Text(stringResource(R.string.sync_try_again))
                    }
                }
            }
        }
    }
    } // close Box
}

@Composable
private fun SyncStatusCard(uiState: ClockSyncUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (uiState.status) {
                SyncStatus.SYNCED -> AccentGreen.copy(alpha = 0.15f)
                SyncStatus.ERROR -> Color(0xFFFF453A).copy(alpha = 0.15f)
                else -> SurfaceDark
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status icon
            when (uiState.status) {
                SyncStatus.SYNCED -> {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.sync_synced_cd),
                        tint = AccentGreen,
                        modifier = Modifier.size(48.dp)
                    )
                }
                SyncStatus.ERROR -> {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.sync_error_cd),
                        tint = Color(0xFFFF453A),
                        modifier = Modifier.size(48.dp)
                    )
                }
                else -> {
                    // No icon for other states
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (uiState.status) {
                    SyncStatus.NOT_SYNCED -> stringResource(R.string.sync_status_not_synced)
                    SyncStatus.WAITING_FOR_PEER -> stringResource(R.string.sync_status_searching)
                    SyncStatus.CONNECTING -> stringResource(R.string.sync_status_connecting)
                    SyncStatus.SYNCING -> stringResource(R.string.sync_status_syncing)
                    SyncStatus.SYNCED -> stringResource(R.string.sync_status_synced)
                    SyncStatus.ERROR -> stringResource(R.string.sync_status_error)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            if (uiState.isServer && uiState.status != SyncStatus.NOT_SYNCED) {
                Text(
                    stringResource(R.string.sync_reference_clock_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SyncSuccessDetails(uiState: ClockSyncUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.sync_details_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            DetailRow(stringResource(R.string.sync_details_offset), String.format(stringResource(R.string.sync_details_ms_format), uiState.offsetMs))
            DetailRow(stringResource(R.string.sync_details_uncertainty), String.format(stringResource(R.string.sync_details_ms_format), uiState.uncertaintyMs))
            DetailRow(stringResource(R.string.sync_details_quality), uiState.quality.name, getQualityColor(uiState.quality))
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
private fun getQualityColor(quality: SyncQuality): Color {
    return when (quality) {
        SyncQuality.EXCELLENT -> Color(0xFF4CAF50)  // Green
        SyncQuality.GOOD -> Color(0xFF8BC34A)       // Light green
        SyncQuality.FAIR -> Color(0xFFFFC107)       // Amber
        SyncQuality.POOR -> Color(0xFFFF9800)       // Orange
        SyncQuality.BAD -> Color(0xFFF44336)        // Red
    }
}
