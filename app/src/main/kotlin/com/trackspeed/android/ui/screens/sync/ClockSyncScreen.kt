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
import com.trackspeed.android.sync.SyncQuality

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clock Sync") }
            )
        }
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
                        "Sync clocks between devices for accurate timing.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Role selection buttons
                    Text(
                        "Choose role:",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.startAsServer() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasBluetoothPermission
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Start as Reference Clock")
                            Text(
                                "(Other devices sync to this)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { viewModel.startAsClient() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasBluetoothPermission
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Sync to Another Device")
                            Text(
                                "(Find and sync to reference)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (!hasBluetoothPermission) {
                        Text(
                            "Bluetooth permissions required",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                SyncStatus.WAITING_FOR_PEER -> {
                    if (uiState.isServer) {
                        Text(
                            "Waiting for other device to connect...",
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Start the other device as 'Sync to Another Device'",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            "Searching for reference clock...",
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Make sure the other device is set as 'Reference Clock'",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    CircularProgressIndicator()

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = { viewModel.stop() }) {
                        Text("Cancel")
                    }
                }

                SyncStatus.CONNECTING -> {
                    Text("Connecting to device...")
                    CircularProgressIndicator()

                    TextButton(onClick = { viewModel.stop() }) {
                        Text("Cancel")
                    }
                }

                SyncStatus.SYNCING -> {
                    Text(
                        "Synchronizing clocks...",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LinearProgressIndicator(
                        progress = { uiState.progress },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        "${(uiState.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )

                    TextButton(onClick = { viewModel.stop() }) {
                        Text("Cancel")
                    }
                }

                SyncStatus.SYNCED -> {
                    SyncSuccessDetails(uiState)

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(onClick = onSyncComplete) {
                        Text("Continue to Timing")
                    }
                }

                SyncStatus.ERROR -> {
                    Text(
                        uiState.errorMessage ?: "Unknown error",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(onClick = { viewModel.stop() }) {
                        Text("Try Again")
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatusCard(uiState: ClockSyncUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (uiState.status) {
                SyncStatus.SYNCED -> MaterialTheme.colorScheme.primaryContainer
                SyncStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
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
                        contentDescription = "Synced",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
                SyncStatus.ERROR -> {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
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
                    SyncStatus.NOT_SYNCED -> "Not Synced"
                    SyncStatus.WAITING_FOR_PEER -> if (uiState.isServer) "Advertising..." else "Scanning..."
                    SyncStatus.CONNECTING -> "Connecting..."
                    SyncStatus.SYNCING -> "Syncing..."
                    SyncStatus.SYNCED -> "Synced"
                    SyncStatus.ERROR -> "Error"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (uiState.isServer && uiState.status != SyncStatus.NOT_SYNCED) {
                Text(
                    "(Reference Clock)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SyncSuccessDetails(uiState: ClockSyncUiState) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Sync Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            DetailRow("Clock Offset", "%.2f ms".format(uiState.offsetMs))
            DetailRow("Uncertainty", "%.2f ms".format(uiState.uncertaintyMs))
            DetailRow("Quality", uiState.quality.name, getQualityColor(uiState.quality))
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
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
