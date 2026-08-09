package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.ui.theme.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.data.export.shareCsv
import com.trackspeed.android.data.local.entities.RunEntity
import kotlinx.coroutines.launch
import com.trackspeed.android.ui.components.DetectionReviewTarget
import com.trackspeed.android.ui.components.ExpandedThumbnail
import com.trackspeed.android.ui.components.ThumbnailViewerDialog
import com.trackspeed.android.ui.util.formatDistance
import com.trackspeed.android.ui.util.formatSessionMode
import com.trackspeed.android.ui.util.formatSplitDuration
import com.trackspeed.android.ui.util.formatTime
import com.trackspeed.android.ui.util.formatSpeed
import com.trackspeed.android.ui.util.parseSegmentSplits
import com.trackspeed.android.ui.util.parseAthleteColor
import java.io.File
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import java.text.SimpleDateFormat
import java.util.Date
import com.trackspeed.android.model.StartType
import com.trackspeed.android.ui.util.localizedDisplayName
import java.util.Locale

private val BestGreen = Color(0xFF4CAF50)
private val SeasonGold = Color(0xFFFFD600)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    onNavigateBack: () -> Unit,
    onRunClick: (String, String) -> Unit = { _, _ -> },
    onShareRunClick: (String, String) -> Unit = { _, _ -> },
    onShareSessionClick: (String) -> Unit = {},
    onVideoOverlayClick: (String, String) -> Unit = { _, _ -> },
    onFramesClick: (String, String) -> Unit = { _, _ -> },
    viewModel: SessionDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var expandedThumbnail by remember { mutableStateOf<ExpandedThumbnail?>(null) }
    var expandedRunId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMoreMenu by remember { mutableStateOf(false) }
    var showDeleteSessionDialog by remember { mutableStateOf(false) }
    var runToDelete by remember { mutableStateOf<RunEntity?>(null) }

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) {
            onNavigateBack()
        }
    }

    // Fullscreen thumbnail viewer
    ThumbnailViewerDialog(
        thumbnail = expandedThumbnail,
        onDismiss = { expandedThumbnail = null }
    )

    if (showDeleteSessionDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSessionDialog = false },
            title = { Text(stringResource(R.string.session_detail_delete_session_title)) },
            text = { Text(stringResource(R.string.session_detail_delete_session_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteSessionDialog = false
                        viewModel.deleteSession()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.session_history_delete_confirm),
                        color = Color(0xFFFF3B30)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSessionDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    runToDelete?.let { run ->
        AlertDialog(
            onDismissRequest = { runToDelete = null },
            title = { Text(stringResource(R.string.run_detail_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.session_detail_delete_run_message,
                        run.runNumber
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (expandedRunId == run.id) {
                            expandedRunId = null
                        }
                        viewModel.deleteRun(run.id)
                        runToDelete = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.run_detail_delete_confirm),
                        color = Color(0xFFFF3B30)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { runToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.session?.name ?: stringResource(R.string.session_detail_title_default),
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.session_detail_back_cd),
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val expandedRun = uiState.allRuns.firstOrNull { it.id == expandedRunId }
                        if (expandedRun != null) {
                            onShareRunClick(expandedRun.id, expandedRun.sessionId)
                        } else {
                            uiState.session?.let { session ->
                                onShareSessionClick(session.id)
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.session_detail_share_cd),
                            tint = TextPrimary
                        )
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.session_detail_more_cd),
                                tint = TextPrimary
                            )
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            containerColor = SurfaceDark
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.session_detail_export_csv),
                                        color = TextPrimary
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    scope.launch {
                                        val uri = viewModel.exportSessionCsv()
                                        if (uri != null) {
                                            shareCsv(context, uri)
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.session_detail_delete_session),
                                        color = Color(0xFFFF3B30)
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showDeleteSessionDialog = true
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
        },
        modifier = Modifier.gradientBackground()
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Compact info bar with pills
            uiState.session?.let { session ->
                item {
                    SessionInfoBar(session = session)
                }
            }

            // Athlete filter chips
            if (uiState.athletes.size > 1) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    AthleteFilterChips(
                        athletes = uiState.athletes,
                        selectedAthleteId = uiState.selectedAthleteId,
                        onAthleteSelected = { viewModel.setAthleteFilter(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            } else {
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }

            if (uiState.allRuns.size > 1) {
                item {
                    RunSortChips(
                        selectedSort = uiState.runSort,
                        onSortSelected = { viewModel.setRunSort(it) }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            // Table header
            if (uiState.runs.isNotEmpty()) {
                item {
                    TableHeader(
                        showAthlete = uiState.showAthleteColumn,
                        showSpeedInResults = uiState.showSpeedInResults
                    )
                }
            }

            // Run rows
            items(
                items = uiState.runs,
                key = { it.id }
            ) { run ->
                val isBest = uiState.bestTime != null && run.timeSeconds == uiState.bestTime
                val isExpanded = expandedRunId == run.id

                Column {
                    CompactRunRow(
                        run = run,
                        distance = uiState.session?.distance ?: run.distance,
                        speedUnit = uiState.speedUnit,
                        showSpeedInResults = uiState.showSpeedInResults,
                        isBest = isBest,
                        showAthlete = uiState.showAthleteColumn,
                        onClick = {
                            expandedRunId = if (isExpanded) null else run.id
                        },
                        onLongClick = { onRunClick(run.id, run.sessionId) }
                    )

                    // Expandable thumbnail row
                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        ExpandedThumbnailRow(
                            run = run,
                            detectionReviewEnabled = BuildConfig.DEBUG && uiState.detectionDiagnosticsEnabled,
                            onReviewSubmitted = { viewModel.submitCrossingReview(it) },
                            onThumbnailClick = { expandedThumbnail = it },
                            onDetailClick = { onRunClick(run.id, run.sessionId) },
                            onShareClick = { onShareRunClick(run.id, run.sessionId) },
                            onVideoOverlayClick = { onVideoOverlayClick(run.id, run.sessionId) },
                            onFramesClick = { onFramesClick(run.id, run.sessionId) },
                            onDeleteClick = { runToDelete = run }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunSortChips(
    selectedSort: SessionRunSort,
    onSortSelected: (SessionRunSort) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.session_detail_sort_label),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            fontSize = 12.sp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SessionRunSort.entries.forEach { sort ->
                val selected = selectedSort == sort
                Surface(
                    modifier = Modifier.clickable { onSortSelected(sort) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) AccentBlue else CardBackground
                ) {
                    Text(
                        text = when (sort) {
                            SessionRunSort.RUN_NUMBER -> stringResource(R.string.session_detail_sort_run_order)
                            SessionRunSort.FASTEST_FIRST -> stringResource(R.string.session_detail_sort_fastest)
                            SessionRunSort.SLOWEST_FIRST -> stringResource(R.string.session_detail_sort_slowest)
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (selected) Color.White else TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionInfoBar(
    session: com.trackspeed.android.data.local.entities.TrainingSessionEntity
) {
    val dateFormat = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(session.date))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Distance pill
        if (session.distance > 0) {
            Box(
                modifier = Modifier
                    .background(AccentBlue.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = formatDistance(session.distance),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = AccentBlue
                )
            }
        }

        // Start type pill
        Box(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = StartType.fromRawValue(session.startType).localizedDisplayName(),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }

        Box(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = formatSessionMode(session.numberOfPhones, session.numberOfGates),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Date right-aligned
        Text(
            text = dateStr,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun AthleteFilterChips(
    athletes: List<AthleteChip>,
    selectedAthleteId: String?,
    onAthleteSelected: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // "All" chip
        val allSelected = selectedAthleteId == null
        val totalRuns = athletes.sumOf { it.runCount }
        Surface(
            modifier = Modifier.clickable { onAthleteSelected(null) },
            shape = RoundedCornerShape(8.dp),
            color = if (allSelected) AccentBlue else CardBackground
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.session_detail_filter_all),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (allSelected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (allSelected) Color.White else TextSecondary
                )
                Text(
                    text = "$totalRuns",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (allSelected) Color.White.copy(alpha = 0.7f) else TextSecondary.copy(alpha = 0.5f)
                )
            }
        }

        // Athlete chips with color dot and count
        athletes.forEach { athlete ->
            val isSelected = selectedAthleteId == athlete.id
            val chipColor = athlete.color?.let { parseAthleteColor(it) } ?: AccentBlue

            Surface(
                modifier = Modifier.clickable { onAthleteSelected(athlete.id) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) chipColor else CardBackground
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Color dot
                    if (!isSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(chipColor, CircleShape)
                        )
                    }

                    Text(
                        text = athlete.name,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isSelected) Color.White else TextSecondary
                    )

                    Text(
                        text = "${athlete.runCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.White.copy(alpha = 0.7f) else TextSecondary.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TableHeader(
    showAthlete: Boolean,
    showSpeedInResults: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderCell(text = stringResource(R.string.session_detail_header_num), weight = 0.08f)
        HeaderCell(text = stringResource(R.string.session_detail_header_time), weight = 0.25f)
        if (showSpeedInResults) {
            HeaderCell(text = stringResource(R.string.session_detail_header_speed), weight = 0.25f)
        }
        HeaderCell(text = stringResource(R.string.session_detail_header_dist), weight = 0.17f)
        HeaderCell(text = stringResource(R.string.session_detail_header_type), weight = 0.17f)
        if (showAthlete) {
            HeaderCell(text = stringResource(R.string.session_detail_header_athlete), weight = 0.20f)
        }
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold
        ),
        color = TextSecondary,
        maxLines = 1
    )
}

@Composable
private fun CompactRunRow(
    run: RunEntity,
    distance: Double,
    speedUnit: String,
    showSpeedInResults: Boolean,
    isBest: Boolean,
    showAthlete: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bgColor = if (isBest) AccentBlue.copy(alpha = 0.08f) else Color.Transparent
    val timeColor = if (isBest) AccentBlue else TextPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // #
        Text(
            text = "${run.runNumber}",
            modifier = Modifier.weight(0.08f),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = TextMuted
        )

        // Time + badges
        Row(
            modifier = Modifier.weight(0.25f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = formatTime(run.timeSeconds),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                ),
                color = timeColor,
                maxLines = 1
            )
            if (run.isPersonalBest) {
                BadgeChip(text = stringResource(R.string.session_detail_badge_pb), color = BestGreen)
            }
            if (run.isSeasonBest) {
                BadgeChip(text = stringResource(R.string.session_detail_badge_sb), color = SeasonGold)
            }
        }

        // Speed
        if (showSpeedInResults) {
            Text(
                text = formatSpeed(distance, run.timeSeconds, speedUnit),
                modifier = Modifier.weight(0.25f),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = TextMuted,
                maxLines = 1
            )
        }

        // Dist - accent colored pill like iOS
        Box(
            modifier = Modifier.weight(0.17f)
        ) {
            Text(
                text = formatDistance(run.distance),
                style = MaterialTheme.typography.labelSmall,
                color = AccentBlue,
                modifier = Modifier
                    .background(
                        AccentBlue.copy(alpha = 0.15f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                maxLines = 1
            )
        }

        // Type
        Text(
            text = StartType.fromRawValue(run.startType).localizedDisplayName(),
            modifier = Modifier.weight(0.17f),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Athlete
        if (showAthlete) {
            Row(
                modifier = Modifier.weight(0.20f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (run.athleteColor != null) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(parseAthleteColor(run.athleteColor), CircleShape)
                    )
                }
                Text(
                    text = run.athleteName ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    // Thin divider between rows
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        color = DividerColor,
        thickness = 0.5.dp
    )
}

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun ExpandedThumbnailRow(
    run: RunEntity,
    detectionReviewEnabled: Boolean,
    onReviewSubmitted: (com.trackspeed.android.ui.components.DetectionReviewSubmission) -> Unit,
    onThumbnailClick: (ExpandedThumbnail) -> Unit,
    onDetailClick: () -> Unit,
    onShareClick: () -> Unit,
    onVideoOverlayClick: () -> Unit,
    onFramesClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val segments = remember(run.splitsJson) { parseSegmentSplits(run.splitsJson) }
    val context = LocalContext.current
    val detectionGateLabel = if (run.numberOfPhones <= 1) {
        stringResource(R.string.run_detail_crossing_label)
    } else {
        stringResource(R.string.device_role_finish)
    }
    val segmentSummary = segments.joinToString("  ·  ") {
        val gateLabel = context.getString(
            R.string.race_gate_number_range,
            it.fromGateIndex,
            it.toGateIndex
        )
        val splitTime = context.getString(
            R.string.common_seconds_value,
            formatSplitDuration(it.splitNanos)
        )
        "$gateLabel $splitTime"
    }
    val gatePosition = remember(run.finishGatePosition, run.gatePosition) {
        (run.finishGatePosition ?: run.gatePosition).toFloat().coerceIn(0f, 1f)
    }
    val bitmap by produceState<Bitmap?>(null, run.thumbnailPath) {
        val loadedBitmap = withContext(Dispatchers.IO) {
            run.thumbnailPath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) BitmapFactory.decodeFile(path) else null
                } catch (e: Exception) {
                    null
                }
            }
        }
        value = loadedBitmap
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark.copy(alpha = 0.7f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            // Thumbnail
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            onThumbnailClick(
                                ExpandedThumbnail(
                                    bitmap = currentBitmap,
                                    gatePosition = gatePosition,
                                    reviewTarget = if (detectionReviewEnabled) {
                                        run.toDetectionReviewTarget(
                                            gatePosition = gatePosition,
                                            gateLabel = detectionGateLabel
                                        )
                                    } else {
                                        null
                                    },
                                    onReviewSubmitted = if (detectionReviewEnabled) {
                                        onReviewSubmitted
                                    } else {
                                        null
                                    }
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.session_detail_run_thumbnail_cd, run.runNumber),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val x = size.width * gatePosition
                        drawLine(
                            color = Color.Red.copy(alpha = 0.8f),
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                            strokeWidth = 3f
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.run_detail_crossing_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        // Info + detail link
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.session_detail_run_number, run.runNumber),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary
            )
            Text(
                text = stringResource(R.string.common_seconds_value, formatTime(run.timeSeconds)),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = TextSecondary
            )
            if (segments.isNotEmpty()) {
                Text(
                    text = segmentSummary,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.run_detail_details),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = AccentBlue,
                modifier = Modifier.clickable { onDetailClick() }
            )
            Text(
                text = stringResource(R.string.run_detail_share_run),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = AccentBlue,
                modifier = Modifier.clickable { onShareClick() }
            )
            Text(
                text = stringResource(R.string.video_overlay_title),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = AccentBlue,
                modifier = Modifier.clickable { onVideoOverlayClick() }
            )
            if (run.hasFrameScrubberPayload()) {
                Text(
                    text = stringResource(R.string.frame_scrubber_title),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = AccentBlue,
                    modifier = Modifier.clickable { onFramesClick() }
                )
            }
            Text(
                text = stringResource(R.string.run_detail_delete_run),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = Color(0xFFFF3B30),
                modifier = Modifier.clickable { onDeleteClick() }
            )
        }
    }

    HorizontalDivider(
        color = Color.White.copy(alpha = 0.05f),
        thickness = 0.5.dp
    )
}

private fun RunEntity.toDetectionReviewTarget(
    gatePosition: Float,
    gateLabel: String
): DetectionReviewTarget {
    val isSolo = numberOfPhones <= 1
    return DetectionReviewTarget(
        sessionId = sessionId,
        runId = id,
        runNumber = runNumber,
        numberOfPhones = numberOfPhones,
        gateLabel = gateLabel,
        target = if (isSolo) "crossing" else "finish",
        mode = if (isSolo) "solo" else "multi",
        distanceMeters = distance,
        startType = startType,
        displayedTimeSeconds = timeSeconds,
        originalGatePosition = (finishGatePosition ?: this.gatePosition).toFloat(),
        crossingDirection = finishCrossingDirection ?: startCrossingDirection,
        detectorX = gatePosition,
        detectorY = finishDetectorY?.toFloat(),
        crossingVelocityPxPerSec = finishCrossingVelocity ?: crossingVelocity,
        workWidth = finishWorkResolutionWidth ?: workResolutionWidth,
        interpolationAlpha = finishInterpolationAlpha,
        framePick = finishFramePick,
        s0 = finishS0,
        s1 = finishS1,
        isFrontCamera = finishIsFrontCamera,
        detectorTriggerFramePts = finishDetectorTriggerFramePts,
        chosenThumbnailFramePts = finishChosenThumbnailFramePts,
        savedThumbnailFramePts = finishSavedThumbnailFramePts
    )
}

@Composable
private fun BadgeChip(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        ),
        color = color,
        modifier = Modifier
            .background(
                color.copy(alpha = 0.15f),
                shape = RoundedCornerShape(3.dp)
            )
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}
