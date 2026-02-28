package com.trackspeed.android.ui.screens.debug

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.ui.theme.TrackSpeedTheme

private val ScreenBackground = Color(0xFF000000)
private val CardBackground = Color(0xFF1C1C1E)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFF8E8E93)
private val DividerColor = Color(0xFF38383A)
private val AccentBlue = Color(0xFF0A84FF)
private val AccentRed = Color(0xFFFF453A)
private val AccentOrange = Color(0xFFFF9F0A)
private val AccentGreen = Color(0xFF30D158)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugToolsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DebugToolsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showClearThumbnailsConfirmation by remember { mutableStateOf(false) }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(stringResource(R.string.debug_reset_db_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.debug_reset_db_message),
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetDatabase()
                        showResetConfirmation = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                ) {
                    Text(stringResource(R.string.debug_reset))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmation = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentBlue)
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = CardBackground
        )
    }

    if (showClearThumbnailsConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearThumbnailsConfirmation = false },
            title = { Text(stringResource(R.string.debug_clear_thumbnails_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.debug_clear_thumbnails_message),
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearThumbnails()
                        showClearThumbnailsConfirmation = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                ) {
                    Text(stringResource(R.string.debug_clear))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearThumbnailsConfirmation = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentBlue)
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = CardBackground
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = AccentBlue
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ScreenBackground
                )
            )
        },
        containerColor = ScreenBackground
    ) { padding ->
        DebugToolsContent(
            state = state,
            context = context,
            onResetDatabase = { showResetConfirmation = true },
            onClearThumbnails = { showClearThumbnailsConfirmation = true },
            onToggleDetectionTest = viewModel::toggleDetectionTestMode,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun DebugToolsContent(
    state: DebugToolsUiState,
    context: Context,
    onResetDatabase: () -> Unit,
    onClearThumbnails: () -> Unit,
    onToggleDetectionTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Debug warning banner
        Card(
            colors = CardDefaults.cardColors(
                containerColor = AccentOrange.copy(alpha = 0.15f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.debug_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentOrange
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device Info
        DebugSectionHeader(stringResource(R.string.debug_section_device_info))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoRow("OS Version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow("Device", Build.DEVICE)
                InfoRow("Board", Build.BOARD)

                // Camera info
                val cameraInfo = remember { getCameraInfo(context) }
                HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))
                cameraInfo.forEach { (key, value) ->
                    InfoRow(key, value)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Database Stats
        DebugSectionHeader(stringResource(R.string.debug_section_database_stats))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow(stringResource(R.string.debug_label_sessions), state.sessionCount.toString())
                InfoRow(stringResource(R.string.debug_label_runs), state.runCount.toString())
                InfoRow(stringResource(R.string.debug_label_db_size), state.dbSizeFormatted)
                InfoRow(stringResource(R.string.debug_label_thumbnail_storage), state.thumbnailSizeFormatted)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Detection Config
        DebugSectionHeader(stringResource(R.string.debug_section_detection_config))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow("Work Resolution", "160 x 284")
                InfoRow("Diff Threshold Range", "8 - 40")
                InfoRow("Min Blob Height", "33%")
                InfoRow("Min Velocity", "60 px/s")
                InfoRow("Cooldown", "0.3s")
                InfoRow("Gyro Threshold", "0.35 rad/s")
                InfoRow("Trajectory Buffer", "6 points")
                InfoRow("Hysteresis Distance", "25%")
                InfoRow("Exit Zone", "35%")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clock Sync Status
        DebugSectionHeader(stringResource(R.string.debug_section_clock_sync))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow(stringResource(R.string.debug_label_status), state.clockSyncStatus)
                InfoRow(
                    stringResource(R.string.debug_label_quality),
                    state.clockSyncQuality,
                    valueColor = when (state.clockSyncQuality) {
                        "Excellent", "Good" -> AccentGreen
                        "Fair" -> AccentOrange
                        else -> TextSecondary
                    }
                )
                InfoRow(stringResource(R.string.debug_label_offset), state.clockSyncOffset)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Detection Test Mode Toggle
        DebugSectionHeader(stringResource(R.string.debug_section_test_controls))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_detection_test_mode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.debug_detection_test_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = state.detectionTestMode,
                    onCheckedChange = { onToggleDetectionTest() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentBlue,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF636366),
                        uncheckedBorderColor = Color(0xFF636366)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Danger Zone
        DebugSectionHeader(stringResource(R.string.debug_section_danger_zone))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Reset Database
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.debug_reset_database),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.debug_reset_database_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onResetDatabase,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentRed,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.debug_reset), style = MaterialTheme.typography.labelMedium)
                    }
                }

                HorizontalDivider(color = DividerColor, modifier = Modifier.padding(start = 16.dp))

                // Clear Thumbnails
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.debug_clear_thumbnails),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.debug_clear_thumbnails_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onClearThumbnails,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AccentRed
                        ),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(AccentRed)
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(stringResource(R.string.debug_clear), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFeatureSettings = "tnum"
            ),
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DebugSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/**
 * Get camera hardware information for debug display.
 */
private fun getCameraInfo(context: Context): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    try {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList
        results.add("Cameras" to "${cameraIds.size} available")

        for (id in cameraIds.take(2)) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "Back"
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                else -> "External"
            }

            val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val maxFps = fpsRanges?.maxOfOrNull { it.upper } ?: 0

            results.add("Camera $id" to "$facing, max ${maxFps}fps")
        }
    } catch (e: Exception) {
        results.add("Camera" to "Error: ${e.message}")
    }
    return results
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun DebugToolsScreenPreview() {
    TrackSpeedTheme(darkTheme = true) {
        DebugToolsContent(
            state = DebugToolsUiState(
                sessionCount = 15,
                runCount = 47,
                dbSizeFormatted = "2.3 MB",
                thumbnailSizeFormatted = "8.7 MB",
                clockSyncStatus = "Not connected",
                clockSyncQuality = "N/A",
                clockSyncOffset = "N/A"
            ),
            context = LocalContext.current,
            onResetDatabase = {},
            onClearThumbnails = {},
            onToggleDetectionTest = {}
        )
    }
}
