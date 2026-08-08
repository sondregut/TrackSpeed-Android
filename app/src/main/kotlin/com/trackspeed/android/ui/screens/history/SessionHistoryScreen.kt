package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.ui.theme.*

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.data.export.shareCsv
import com.trackspeed.android.ui.util.formatDistance
import com.trackspeed.android.ui.util.formatSessionMode
import com.trackspeed.android.ui.util.formatTime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import com.trackspeed.android.model.StartType
import kotlinx.coroutines.launch
import java.util.Locale

private val BestGreen = Color(0xFF30D158)
private val WarningGold = Color(0xFFFFD600)
private val DeleteRed = Color(0xFFFF3B30)

@Composable
fun SessionHistoryScreen(
    onSessionClick: (String) -> Unit = {},
    viewModel: SessionHistoryViewModel = hiltViewModel()
) {
    val dateGroups by viewModel.dateGroups.collectAsState()
    val filterDistance by viewModel.filterDistance.collectAsState()
    val filterStartType by viewModel.filterStartType.collectAsState()
    val timeFilter by viewModel.timeFilter.collectAsState()
    val modeFilter by viewModel.modeFilter.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val distanceFilters by viewModel.distanceFilters.collectAsState()
    val startTypeFilters by viewModel.startTypeFilters.collectAsState()
    val hasAnySessions by viewModel.hasAnySessions.collectAsState()
    val hasActiveFilters by viewModel.hasActiveFilters.collectAsState()
    val historyStats by viewModel.historyStats.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showExportMenu by remember { mutableStateOf(false) }

    // Delete confirmation dialog
    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.session_history_delete_title), color = TextPrimary) },
            text = { Text(stringResource(R.string.session_history_delete_message), color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog?.let { viewModel.deleteSession(it) }
                        showDeleteDialog = null
                    }
                ) {
                    Text(stringResource(R.string.session_history_delete_confirm), color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.common_cancel), color = AccentBlue)
                }
            },
            containerColor = SurfaceDark
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.session_history_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )

            if (hasAnySessions) {
                Box {
                    IconButton(onClick = { showExportMenu = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.session_history_export_cd),
                            tint = TextPrimary
                        )
                    }
                    DropdownMenu(
                        expanded = showExportMenu,
                        onDismissRequest = { showExportMenu = false },
                        containerColor = SurfaceDark
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.session_history_export_all_csv), color = TextPrimary) },
                            onClick = {
                                showExportMenu = false
                                scope.launch {
                                    val uri = viewModel.exportAllSessionsCsv()
                                    if (uri != null) {
                                        shareCsv(context, uri)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.session_history_export_empty_toast),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.session_history_share_summary), color = TextPrimary) },
                            onClick = {
                                showExportMenu = false
                                scope.launch {
                                    val summary = viewModel.buildAllSessionsSummary()
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, summary)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(
                                            intent,
                                            context.getString(R.string.session_history_share_summary_chooser)
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Header stats row (Task 13)
        if (hasAnySessions) {
            HeaderStatsRow(stats = historyStats)
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Search bar (Task 3)
        SearchBar(
            query = searchQuery,
            onQueryChange = { viewModel.setSearchQuery(it) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        FilterChipRow(
            label = stringResource(R.string.session_history_filter_time),
            options = HistoryTimeFilter.entries.map { filter ->
                when (filter) {
                    HistoryTimeFilter.ALL -> stringResource(R.string.session_history_time_all)
                    HistoryTimeFilter.THIS_WEEK -> stringResource(R.string.session_history_time_this_week)
                    HistoryTimeFilter.THIS_MONTH -> stringResource(R.string.session_history_time_this_month)
                }
            },
            selectedIndex = HistoryTimeFilter.entries.indexOf(timeFilter),
            onSelected = { index ->
                viewModel.setTimeFilter(HistoryTimeFilter.entries[index])
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Distance filter chips (Task 4 - dynamic)
        FilterChipRow(
            label = stringResource(R.string.session_history_filter_distance),
            options = distanceFilters.map { it.label },
            selectedIndex = distanceFilters.indexOfFirst { it.distance == filterDistance },
            onSelected = { index ->
                viewModel.setFilterDistance(distanceFilters[index].distance)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Start type filter chips (Task 5)
        if (startTypeFilters.size > 1) {
            FilterChipRow(
                label = stringResource(R.string.session_history_filter_start_type),
                options = startTypeFilters,
                selectedIndex = if (filterStartType == null) 0 else
                    startTypeFilters.indexOfFirst { it.equals(filterStartType, ignoreCase = true) },
                onSelected = { index ->
                    viewModel.setFilterStartType(
                        if (index == 0) null else startTypeFilters[index].lowercase()
                    )
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        FilterChipRow(
            label = stringResource(R.string.session_history_filter_mode),
            options = HistoryModeFilter.entries.map { filter ->
                when (filter) {
                    HistoryModeFilter.ALL -> stringResource(R.string.session_history_mode_all)
                    HistoryModeFilter.ONE_PHONE -> stringResource(R.string.session_history_mode_one_phone)
                    HistoryModeFilter.TWO_PHONE -> stringResource(R.string.session_history_mode_two_phone)
                }
            },
            selectedIndex = HistoryModeFilter.entries.indexOf(modeFilter),
            onSelected = { index ->
                viewModel.setModeFilter(HistoryModeFilter.entries[index])
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Sort order chips
        FilterChipRow(
            label = stringResource(R.string.session_history_filter_sort),
            options = SortOrder.entries.map { order ->
                when (order) {
                    SortOrder.NEWEST -> stringResource(R.string.session_history_sort_newest)
                    SortOrder.OLDEST -> stringResource(R.string.session_history_sort_oldest)
                    SortOrder.FASTEST -> stringResource(R.string.session_history_sort_fastest)
                    SortOrder.MOST_RUNS -> stringResource(R.string.session_history_sort_most_runs)
                }
            },
            selectedIndex = SortOrder.entries.indexOf(sortOrder),
            onSelected = { index ->
                viewModel.setSortOrder(SortOrder.entries[index])
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (hasActiveFilters) {
            ActiveFiltersRow(onClear = { viewModel.clearFilters() })
            Spacer(modifier = Modifier.height(12.dp))
        }

        val isEmpty = dateGroups.isEmpty()

        if (isEmpty && hasActiveFilters) {
            // Filtered empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextSecondary.copy(alpha = 0.4f)
                    )
                    Text(
                        text = stringResource(R.string.session_history_no_match),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { viewModel.clearFilters() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(R.string.session_history_clear_filters))
                    }
                }
            }
        } else if (isEmpty) {
            // No sessions at all
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextSecondary.copy(alpha = 0.4f)
                    )
                    Text(
                        text = stringResource(R.string.session_history_no_sessions),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = stringResource(R.string.session_history_no_sessions_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            // Date-grouped session list (Task 2)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                dateGroups.forEach { group ->
                    item(key = "header_${group.key}") {
                        val groupLabel = when (group.key) {
                            DateGroupKey.TODAY -> stringResource(R.string.session_history_group_today)
                            DateGroupKey.YESTERDAY -> stringResource(R.string.session_history_group_yesterday)
                            DateGroupKey.THIS_WEEK -> stringResource(R.string.session_history_group_this_week)
                            DateGroupKey.EARLIER -> stringResource(R.string.session_history_group_earlier)
                        }
                        Text(
                            text = groupLabel,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = TextMuted,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }

                    // Session cards
                    items(
                        items = group.sessions,
                        key = { it.session.id }
                    ) { cardData ->
                        SessionCard(
                            cardData = cardData,
                            onClick = { onSessionClick(cardData.session.id) },
                            onDeleteClick = { showDeleteDialog = cardData.session.id }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveFiltersRow(
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AccentBlue.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = AccentBlue
        )
        Text(
            text = stringResource(R.string.session_history_filters_active),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.session_history_clear_filters_short),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = AccentBlue,
            modifier = Modifier.clickable(onClick = onClear)
        )
    }
}

@Composable
private fun HeaderStatsRow(stats: HistoryStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatBox(
            icon = Icons.Outlined.CalendarMonth,
            value = stats.totalSessions.toString(),
            label = stringResource(R.string.session_history_stat_sessions),
            modifier = Modifier.weight(1f)
        )
        StatBox(
            icon = Icons.Outlined.Timer,
            value = stats.totalRuns.toString(),
            label = stringResource(R.string.session_history_stat_runs),
            modifier = Modifier.weight(1f)
        )
        StatBox(
            icon = Icons.Outlined.Speed,
            value = stats.bestTime?.let { formatTime(it) } ?: "--",
            label = stats.bestTime?.let { stringResource(R.string.session_history_stat_best) } ?: stringResource(R.string.session_history_stat_best),
            valueColor = if (stats.bestTime != null) BestGreen else TextSecondary,
            modifier = Modifier.weight(1f)
        )
        StatBox(
            icon = Icons.Outlined.LocalFireDepartment,
            value = stats.weeklySessionCount.toString(),
            label = stringResource(R.string.session_history_stat_this_week),
            valueColor = if (stats.weeklySessionCount > 0) WarningGold else TextPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatBox(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary
) {
    Column(
        modifier = modifier
            .gunmetalCard()
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = IconMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        placeholder = {
            Text(
                stringResource(R.string.session_history_search_placeholder),
                color = TextSecondary.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(R.string.session_history_search_cd),
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.session_history_clear_cd),
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = CardBackground,
            unfocusedContainerColor = CardBackground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = AccentBlue
        ),
        textStyle = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun FilterChipRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                Surface(
                    modifier = Modifier.clickable { onSelected(index) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) AccentBlue else CardBackground
                ) {
                    Text(
                        text = option,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isSelected) Color.White else TextSecondary
                    )
                }
            }
        }
    }
}

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun SessionCard(
    cardData: SessionCardData,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val session = cardData.session
    val dateFormat = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(session.date))

    // Load thumbnail off UI thread
    val bitmap by produceState<Bitmap?>(null, session.thumbnailPath) {
        val loadedBitmap = withContext(Dispatchers.IO) {
            session.thumbnailPath?.let { path ->
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

    Card(
        modifier = modifier
            .fillMaxWidth()
            .gunmetalCard()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Thumbnail - iOS uses 72x72 square
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = 0.5.dp,
                        color = BorderSubtle,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val currentBitmap = bitmap
                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.session_history_session_thumbnail_cd),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        IconMuted.copy(alpha = 0.3f),
                                        IconMuted.copy(alpha = 0.1f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = IconMuted
                        )
                    }
                }
            }

            // Session info
            Column(modifier = Modifier.weight(1f)) {
                // Date as primary line (matching iOS)
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Distance + run count + start type (dot-separated)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (session.distance > 0) {
                        Text(
                            text = formatDistance(session.distance),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = AccentBlue
                        )
                        Text(
                            text = "\u2022",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted.copy(alpha = 0.6f)
                        )
                    }
                    if (cardData.runCount > 0) {
                        Text(
                            text = if (cardData.runCount == 1)
                                stringResource(R.string.session_history_run_count_singular, cardData.runCount)
                            else
                                stringResource(R.string.session_history_run_count, cardData.runCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = "\u2022",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted.copy(alpha = 0.6f)
                        )
                    }
                    Text(
                        text = StartType.fromRawValue(session.startType).displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = "\u2022",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatSessionMode(session.numberOfPhones, session.numberOfGates),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                // Best time
                if (cardData.bestTime != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${formatTime(cardData.bestTime)}s",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = BestGreen
                    )
                }
            }

            // Chevron
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = IconMuted.copy(alpha = 0.6f)
            )
        }
    }
}
