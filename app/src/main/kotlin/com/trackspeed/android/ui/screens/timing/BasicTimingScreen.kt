package com.trackspeed.android.ui.screens.timing

import android.Manifest
import android.view.SurfaceHolder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.camera.HighSpeedCameraManager
import com.trackspeed.android.ui.components.CameraPreview
import com.trackspeed.android.ui.components.CameraPreviewPlaceholder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasicTimingScreen(
    onNavigateBack: () -> Unit,
    viewModel: BasicTimingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Basic Timing") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Camera preview (takes most of the screen)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (val state = uiState.cameraState) {
                    is HighSpeedCameraManager.CameraState.Capturing -> {
                        CameraPreview(
                            gatePosition = uiState.gatePosition,
                            onGatePositionChanged = viewModel::setGatePosition,
                            fps = uiState.fps,
                            isCalibrating = uiState.isCalibrating,
                            isArmed = uiState.isArmed,
                            onSurfaceReady = { holder ->
                                viewModel.onSurfaceReady(holder.surface)
                            },
                            onSurfaceDestroyed = {
                                viewModel.onSurfaceDestroyed()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is HighSpeedCameraManager.CameraState.Error -> {
                        CameraPreviewPlaceholder(
                            message = state.message,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        CameraPreviewPlaceholder(
                            message = if (uiState.hasPermission) "Opening camera..." else "Camera permission required",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Timer display
            TimerDisplay(
                time = uiState.currentTime,
                splits = uiState.splits,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )

            // Control buttons
            ControlButtons(
                isRunning = uiState.isRunning,
                canStart = uiState.isArmed && !uiState.isRunning,
                onStart = viewModel::startTiming,
                onStop = viewModel::stopTiming,
                onReset = viewModel::resetTiming,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun TimerDisplay(
    time: Double,
    splits: List<Double>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main time display
        Text(
            text = formatTime(time),
            style = MaterialTheme.typography.displayLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 56.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Splits (if any)
        if (splits.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            splits.forEachIndexed { index, split ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Split ${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                        text = formatTime(split),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButtons(
    isRunning: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Reset button
        OutlinedButton(
            onClick = onReset,
            enabled = !isRunning,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reset")
        }

        // Start/Stop button
        Button(
            onClick = if (isRunning) onStop else onStart,
            enabled = if (isRunning) true else canStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            ),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isRunning) "Stop" else "Start")
        }
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds <= 0) return "0.000"

    val totalMs = (seconds * 1000).toLong()
    val mins = totalMs / 60000
    val secs = (totalMs % 60000) / 1000
    val ms = totalMs % 1000

    return if (mins > 0) {
        String.format("%d:%02d.%03d", mins, secs, ms)
    } else {
        String.format("%d.%03d", secs, ms)
    }
}
