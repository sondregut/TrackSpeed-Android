package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.ui.theme.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import com.trackspeed.android.data.export.shareCsv
import com.trackspeed.android.data.local.entities.RunEntity
import kotlinx.coroutines.launch
import com.trackspeed.android.ui.components.ExpandedThumbnail
import com.trackspeed.android.ui.components.ThumbnailViewerDialog
import com.trackspeed.android.ui.util.formatTime
import com.trackspeed.android.ui.util.formatSpeed
import com.trackspeed.android.ui.util.parseAthleteColor
import java.io.File
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BestGreen = Color(0xFF4CAF50)
private val SeasonGold = Color(0xFFFFD600)
private val BestRowBg = Color(0xFF1A3A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    onNavigateBack: () -> Unit,
    onRunClick: (String, String) -> Unit = { _, _ -> },
    viewModel: SessionDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var expandedThumbnail by remember { mutableStateOf<ExpandedThumbnail?>(null) }
    var expandedRunId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Fullscreen thumbnail viewer
    ThumbnailViewerDialog(
        thumbnail = expandedThumbnail,
        onDismiss = { expandedThumbnail = null }
    )

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
                        scope.launch {
                            val uri = viewModel.exportSessionCsv()
                            if (uri != null) {
                                shareCsv(context, uri)
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.session_detail_export_cd),
                            tint = TextPrimary
                        )
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

            // Table header
            if (uiState.runs.isNotEmpty()) {
                item {
                    TableHeader(
                        showAthlete = uiState.showAthleteColumn
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
                            onThumbnailClick = { expandedThumbnail = it },
                            onDetailClick = { onRunClick(run.id, run.sessionId) }
                        )
                    }
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
                    text = "${session.distance.toInt()}m",
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
                text = session.startType.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                },
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
private fun TableHeader(showAthlete: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderCell(text = stringResource(R.string.session_detail_header_num), weight = 0.08f)
        HeaderCell(text = stringResource(R.string.session_detail_header_time), weight = 0.25f)
        HeaderCell(text = stringResource(R.string.session_detail_header_speed), weight = 0.25f)
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
    isBest: Boolean,
    showAthlete: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bgColor = if (isBest) BestRowBg else SurfaceDark
    val timeColor = if (isBest) BestGreen else TextPrimary

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
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
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
        Text(
            text = formatSpeed(distance, run.timeSeconds, speedUnit),
            modifier = Modifier.weight(0.25f),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = TextSecondary,
            maxLines = 1
        )

        // Dist
        Text(
            text = "${run.distance.toInt()}m",
            modifier = Modifier.weight(0.17f),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1
        )

        // Type
        Text(
            text = run.startType.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            },
            modifier = Modifier.weight(0.17f),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
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
                            .size(8.dp)
                            .background(parseAthleteColor(run.athleteColor), CircleShape)
                    )
                }
                Text(
                    text = run.athleteName ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    // Thin divider between rows
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.05f),
        thickness = 0.5.dp
    )
}

@Composable
private fun ExpandedThumbnailRow(
    run: RunEntity,
    onThumbnailClick: (ExpandedThumbnail) -> Unit,
    onDetailClick: () -> Unit
) {
    val bitmap by produceState<Bitmap?>(null, run.thumbnailPath) {
        value = withContext(Dispatchers.IO) {
            run.thumbnailPath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) BitmapFactory.decodeFile(path) else null
                } catch (e: Exception) {
                    null
                }
            }
        }
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
                            onThumbnailClick(ExpandedThumbnail(bitmap = currentBitmap))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.session_detail_run_thumbnail_cd, run.runNumber),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
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
                text = "${formatTime(run.timeSeconds)}s",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = TextSecondary
            )
        }

        // View detail link
        Text(
            text = stringResource(R.string.run_detail_details),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = AccentBlue,
            modifier = Modifier.clickable { onDetailClick() }
        )
    }

    HorizontalDivider(
        color = Color.White.copy(alpha = 0.05f),
        thickness = 0.5.dp
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
