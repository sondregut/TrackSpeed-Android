package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.ui.theme.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.R
import com.trackspeed.android.ui.components.ExpandedThumbnail
import com.trackspeed.android.ui.components.ThumbnailViewerDialog
import com.trackspeed.android.ui.util.formatTime
import com.trackspeed.android.ui.util.parseAthleteColor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BestGreen = Color(0xFF4CAF50)
private val SeasonGold = Color(0xFFFFD600)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: RunDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var expandedThumbnail by remember { mutableStateOf<ExpandedThumbnail?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDistanceSheet by remember { mutableStateOf(false) }

    // Navigate back on delete
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onNavigateBack()
    }

    // Fullscreen thumbnail viewer
    ThumbnailViewerDialog(
        thumbnail = expandedThumbnail,
        onDismiss = { expandedThumbnail = null }
    )

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.run_detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.run_detail_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteRun()
                }) {
                    Text(
                        stringResource(R.string.run_detail_delete_confirm),
                        color = Color(0xFFFF3B30)
                    )
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

    // Edit distance bottom sheet
    if (showEditDistanceSheet) {
        EditDistanceSheet(
            currentDistance = uiState.run?.distance ?: 60.0,
            onDismiss = { showEditDistanceSheet = false },
            onSave = { newDist ->
                viewModel.updateRunDistance(newDist)
                showEditDistanceSheet = false
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.gradientBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.run?.let { stringResource(R.string.run_detail_title_format, it.runNumber) }
                            ?: stringResource(R.string.run_detail_title_default),
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.run_detail_back_cd),
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = TextPrimary
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = SurfaceDark
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.run_detail_edit_distance), color = TextPrimary) },
                                onClick = {
                                    showMenu = false
                                    showEditDistanceSheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.run_detail_delete_run), color = Color(0xFFFF3B30)) },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = TextPrimary)
            }
            return@Scaffold
        }

        val run = uiState.run
        if (run == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.run_detail_not_found),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp)
        ) {
            // Athlete header
            if (run.athleteName != null) {
                item {
                    AthleteHeader(
                        name = run.athleteName,
                        color = run.athleteColor
                    )
                }
            }

            // Main stats card (time + speed + distance + reaction)
            item {
                MainStatsCard(
                    timeSeconds = run.timeSeconds,
                    isPersonalBest = run.isPersonalBest,
                    isSeasonBest = run.isSeasonBest,
                    speedFormatted = uiState.speedFormatted,
                    speedUnit = uiState.speedUnit,
                    distance = run.distance,
                    reactionTime = run.reactionTime
                )
            }

            // Gate images gallery
            if (run.thumbnailPath != null) {
                item {
                    GateImagesGallery(
                        thumbnailPath = run.thumbnailPath,
                        runNumber = run.runNumber,
                        timeSeconds = run.timeSeconds,
                        onThumbnailClick = { expandedThumbnail = it }
                    )
                }
            }

            // Info card
            item {
                InfoCard(
                    session = uiState.session,
                    startType = run.startType
                )
            }
        }
    }
}

@Composable
private fun AthleteHeader(
    name: String,
    color: String?
) {
    val athleteColor = color?.let { parseAthleteColor(it) } ?: AccentBlue

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Colored circle with initial
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(athleteColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.first().uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = TextPrimary
            )
            Text(
                text = stringResource(R.string.run_detail_athlete_label),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun MainStatsCard(
    timeSeconds: Double,
    isPersonalBest: Boolean,
    isSeasonBest: Boolean,
    speedFormatted: String,
    speedUnit: String,
    distance: Double,
    reactionTime: Double?
) {
    val timeColor = when {
        isPersonalBest -> BestGreen
        isSeasonBest -> SeasonGold
        else -> TextPrimary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .gunmetalCard(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Large time
            Text(
                text = formatTime(timeSeconds),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 56.sp,
                    letterSpacing = (-1).sp
                ),
                color = timeColor,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.run_detail_seconds_label),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // PB / SB badges
            if (isPersonalBest || isSeasonBest) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPersonalBest) {
                        Badge(
                            text = stringResource(R.string.run_detail_badge_personal_best),
                            color = BestGreen
                        )
                    }
                    if (isPersonalBest && isSeasonBest) {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (isSeasonBest) {
                        Badge(
                            text = stringResource(R.string.run_detail_badge_season_best),
                            color = SeasonGold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(16.dp))

            // Stats row: Distance, Speed, Reaction
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = stringResource(R.string.run_detail_distance_label),
                    value = stringResource(R.string.run_detail_distance_value, distance.toInt())
                )

                StatItem(
                    label = stringResource(R.string.run_detail_speed_label),
                    value = if (speedFormatted != "--") "$speedFormatted $speedUnit" else "--"
                )

                if (reactionTime != null) {
                    StatItem(
                        label = stringResource(R.string.run_detail_reaction_time_label),
                        value = String.format(stringResource(R.string.run_detail_reaction_time_value), reactionTime)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold
        ),
        color = color,
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun GateImagesGallery(
    thumbnailPath: String,
    runNumber: Int,
    timeSeconds: Double,
    onThumbnailClick: (ExpandedThumbnail) -> Unit
) {
    val bitmap by produceState<Bitmap?>(null, thumbnailPath) {
        value = withContext(Dispatchers.IO) {
            try {
                val file = File(thumbnailPath)
                if (file.exists()) BitmapFactory.decodeFile(thumbnailPath) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .gunmetalCard(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header with camera icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.run_detail_gate_images),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = TextSecondary
                    )
                }

                // Horizontal scroll of thumbnails
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Single gate crossing thumbnail
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(140.dp)
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    onThumbnailClick(ExpandedThumbnail(bitmap = currentBitmap))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = currentBitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.run_detail_crossing_frame_cd, runNumber),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Text(
                            text = stringResource(R.string.run_detail_crossing_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )

                        Text(
                            text = formatTime(timeSeconds),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(
    session: com.trackspeed.android.data.local.entities.TrainingSessionEntity?,
    startType: String
) {
    val dateFormat = SimpleDateFormat("EEEE, MMM d, yyyy  HH:mm", Locale.getDefault())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .gunmetalCard(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.run_detail_details),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = TextSecondary
            )

            if (session != null) {
                InfoRow(
                    label = stringResource(R.string.run_detail_date_label),
                    value = dateFormat.format(Date(session.date))
                )
            }

            InfoRow(
                label = stringResource(R.string.run_detail_start_type_label),
                value = startType.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            )

            InfoRow(
                label = stringResource(R.string.run_detail_mode_label),
                value = stringResource(R.string.run_detail_mode_value)
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            color = TextPrimary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDistanceSheet(
    currentDistance: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val presets = listOf(10.0, 20.0, 30.0, 40.0, 60.0, 100.0)
    var customInput by remember { mutableStateOf("") }
    var selectedDistance by remember { mutableStateOf(currentDistance) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.run_detail_edit_distance),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Preset distance buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { dist ->
                    val isSelected = selectedDistance == dist && customInput.isEmpty()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .background(
                                if (isSelected) AccentBlue else Color.White.copy(alpha = 0.1f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                selectedDistance = dist
                                customInput = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${dist.toInt()}m",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isSelected) Color.White else TextSecondary
                        )
                    }
                }
            }

            // Custom input
            OutlinedTextField(
                value = customInput,
                onValueChange = { value ->
                    customInput = value
                    value.toDoubleOrNull()?.let { selectedDistance = it }
                },
                label = { Text(stringResource(R.string.run_detail_distance_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedLabelColor = AccentBlue,
                    unfocusedLabelColor = TextSecondary,
                    cursorColor = AccentBlue
                )
            )

            // Save button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(AccentBlue, RoundedCornerShape(12.dp))
                    .clickable {
                        if (selectedDistance > 0) onSave(selectedDistance)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.run_detail_save),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )
            }
        }
    }
}
