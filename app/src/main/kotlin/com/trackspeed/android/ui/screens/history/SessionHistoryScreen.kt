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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.data.export.shareCsv
import com.trackspeed.android.ui.util.formatDistance
import com.trackspeed.android.ui.util.formatTime
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import com.trackspeed.android.model.StartType
import com.trackspeed.android.ui.util.localizedDisplayName
import com.trackspeed.android.ui.util.localizedShortName
import kotlinx.coroutines.launch
import java.util.Locale

private val BestGreen = Color(0xFF30D158)
private val DeleteRed = Color(0xFFFF3B30)

@Composable
fun SessionHistoryScreen(
    onSessionClick: (String) -> Unit = {},
    onStatsClick: () -> Unit = {},
    onStartSessionClick: (() -> Unit)? = null,
    viewModel: SessionHistoryViewModel = hiltViewModel()
) {
    val historyState by viewModel.historyState.collectAsState()
    val dateGroups = historyState.dateGroups
    val hasAnySessions = historyState.hasAnySessions
    val filterDistance by viewModel.filterDistance.collectAsState()
    val filterStartType by viewModel.filterStartType.collectAsState()
    val timeFilter by viewModel.timeFilter.collectAsState()
    val modeFilter by viewModel.modeFilter.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val distanceFilters by viewModel.distanceFilters.collectAsState()
    val startTypeFilters by viewModel.startTypeFilters.collectAsState()
    val hasActiveFilters by viewModel.hasActiveFilters.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    val overviewStats = remember(dateGroups) { dateGroups.toOverviewStats() }
    val filteredSessionCount = remember(dateGroups) {
        dateGroups.sumOf { it.sessions.size }
    }
    val advancedFilterCount = listOf(
        filterDistance != null,
        filterStartType != null,
        modeFilter != HistoryModeFilter.ALL
    ).count { it }
    val hasAdvancedFilters = advancedFilterCount > 0
    val hasModifiedFilterSheet = hasAdvancedFilters || sortOrder != SortOrder.NEWEST

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
                    Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = SurfaceDark
        )
    }

    if (showFilterSheet) {
        HistoryFilterSheet(
            distanceFilters = distanceFilters,
            selectedDistance = filterDistance,
            onDistanceSelected = viewModel::setFilterDistance,
            startTypeFilters = startTypeFilters,
            selectedStartType = filterStartType,
            onStartTypeSelected = viewModel::setFilterStartType,
            selectedMode = modeFilter,
            onModeSelected = viewModel::setModeFilter,
            selectedSortOrder = sortOrder,
            onSortOrderSelected = viewModel::setSortOrder,
            hasModifiedFilters = hasModifiedFilterSheet,
            onClear = viewModel::clearAdvancedFilters,
            onDismiss = { showFilterSheet = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

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

        if (hasAnySessions) {
            HistoryStatsCard(
                stats = overviewStats,
                isFiltered = hasActiveFilters,
                onStatsClick = onStatsClick
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        SearchBar(
            query = searchQuery,
            onQueryChange = { viewModel.setSearchQuery(it) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        HistoryTimeAndFilterControls(
            selectedTimeFilter = timeFilter,
            onTimeFilterSelected = viewModel::setTimeFilter,
            hasModifiedFilterSheet = hasModifiedFilterSheet,
            onFilterClick = { showFilterSheet = true }
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (hasAdvancedFilters) {
            ActiveFiltersRow(
                filterCount = advancedFilterCount,
                onClear = viewModel::clearFilters
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        val isEmpty = dateGroups.isEmpty()

        if (historyState.hasLoadedData && (historyState.loadFailed || historyState.isLoading)) {
            HistoryLoadFeedback(
                isLoading = historyState.isLoading,
                failed = historyState.loadFailed,
                hasLoadedData = true,
                onRetry = viewModel::retryLoading
            )
            Spacer(Modifier.height(12.dp))
        }

        if (!historyState.hasLoadedData) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                HistoryLoadFeedback(
                    isLoading = historyState.isLoading,
                    failed = historyState.loadFailed,
                    hasLoadedData = false,
                    onRetry = viewModel::retryLoading
                )
            }
        } else if (isEmpty && hasActiveFilters) {
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
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
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
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextSecondary.copy(alpha = 0.4f)
                    )
                    Text(
                        text = stringResource(R.string.session_history_no_sessions),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.parity_history_empty_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    onStartSessionClick?.let { startSession ->
                        Button(onClick = startSession, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.home_start_session))
                        }
                    }
                }
            }
        } else {
            Text(
                text = pluralStringResource(
                    R.plurals.session_history_results_count,
                    filteredSessionCount,
                    filteredSessionCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

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
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = TextPrimary,
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

private data class HistoryOverviewStats(
    val totalSessions: Int,
    val totalRuns: Int,
    val bestTime: Double?,
    val bestDistance: Double?,
    val weeklySessionCount: Int
)

private fun List<DateGroup>.toOverviewStats(): HistoryOverviewStats {
    val cards = flatMap { it.sessions }
    val bestCard = cards
        .filter { (it.bestTime ?: 0.0) > 0.0 }
        .minByOrNull { it.bestTime ?: Double.MAX_VALUE }
    val weekStart = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    return HistoryOverviewStats(
        totalSessions = cards.size,
        totalRuns = cards.sumOf { it.runCount },
        bestTime = bestCard?.bestTime,
        bestDistance = bestCard?.session?.distance,
        weeklySessionCount = cards.count { it.session.date >= weekStart }
    )
}

@Composable
private fun ActiveFiltersRow(
    filterCount: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = pluralStringResource(
                R.plurals.session_history_active_filter_count,
                filterCount,
                filterCount
            ),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.session_history_clear_filters_short),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onClear)
        )
    }
}

@Composable
private fun HistoryStatsCard(
    stats: HistoryOverviewStats,
    isFiltered: Boolean,
    onStatsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .gunmetalCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (isFiltered) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.session_history_overview_filtered),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = stringResource(R.string.session_history_best_time_label),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    ),
                    color = TextMuted
                )
                if (stats.bestTime != null) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.common_seconds_value,
                                formatTime(stats.bestTime)
                            ),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 30.sp
                            ),
                            color = BestGreen,
                            maxLines = 1
                        )
                        stats.bestDistance?.let { distance ->
                            Text(
                                text = formatDistance(distance),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                modifier = Modifier.padding(bottom = 4.dp),
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.session_history_no_timed_runs),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextMuted
                    )
                }
            }

            if (stats.totalRuns > 0) {
                Surface(
                    modifier = Modifier.clickable(onClick = onStatsClick),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BarChart,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.run_detail_details),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = DividerColor)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HistoryOverviewMetric(
                value = stats.totalSessions.toString(),
                label = stringResource(R.string.session_history_stat_sessions),
                modifier = Modifier.weight(1f)
            )
            HistoryMetricDivider()
            HistoryOverviewMetric(
                value = stats.totalRuns.toString(),
                label = stringResource(R.string.session_history_stat_runs),
                modifier = Modifier.weight(1f)
            )
            HistoryMetricDivider()
            HistoryOverviewMetric(
                value = stats.weeklySessionCount.toString(),
                label = stringResource(R.string.session_history_stat_this_week),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HistoryOverviewMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            ),
            color = TextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun HistoryMetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(34.dp)
            .background(DividerColor)
    )
}

@Composable
internal fun HistoryTimeAndFilterControls(
    selectedTimeFilter: HistoryTimeFilter,
    onTimeFilterSelected: (HistoryTimeFilter) -> Unit,
    hasModifiedFilterSheet: Boolean,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val labels = listOf(
        stringResource(R.string.session_history_time_all),
        stringResource(R.string.session_history_time_this_week),
        stringResource(R.string.session_history_time_this_month)
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CardBackground)
                .border(0.5.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HistoryTimeFilter.entries.forEachIndexed { index, filter ->
                val isSelected = filter == selectedTimeFilter
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onTimeFilterSelected(filter) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labels[index],
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CardBackground)
                .border(0.5.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .clickable(onClick = onFilterClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = stringResource(R.string.session_history_open_filters),
                modifier = Modifier.size(20.dp),
                tint = TextPrimary
            )
            if (hasModifiedFilterSheet) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 1.dp, y = (-1).dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
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
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        textStyle = MaterialTheme.typography.bodyMedium
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterSheet(
    distanceFilters: List<DistanceFilter>,
    selectedDistance: Double?,
    onDistanceSelected: (Double?) -> Unit,
    startTypeFilters: List<StartType>,
    selectedStartType: String?,
    onStartTypeSelected: (String?) -> Unit,
    selectedMode: HistoryModeFilter,
    onModeSelected: (HistoryModeFilter) -> Unit,
    selectedSortOrder: SortOrder,
    onSortOrderSelected: (SortOrder) -> Unit,
    hasModifiedFilters: Boolean,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val distanceLabels = distanceFilters.map { filter ->
        filter.distance?.let { filter.label }
            ?: stringResource(R.string.session_history_filter_all_distances)
    }
    val selectedDistanceIndex = distanceFilters
        .indexOfFirst { it.distance == selectedDistance }
        .takeIf { it >= 0 }
        ?: 0
    val startTypeLabels = listOf(stringResource(R.string.session_history_filter_all_types)) +
        startTypeFilters.map { it.localizedDisplayName() }
    val selectedStartTypeIndex = selectedStartType?.let { selected ->
        startTypeFilters.indexOfFirst { it.rawValue.equals(selected, ignoreCase = true) }
            .takeIf { it >= 0 }
            ?.plus(1)
    } ?: 0
    val modeLabels = HistoryModeFilter.entries.map { filter ->
        when (filter) {
            HistoryModeFilter.ALL -> stringResource(R.string.session_history_mode_all)
            HistoryModeFilter.ONE_PHONE -> stringResource(R.string.session_history_mode_one_phone)
            HistoryModeFilter.TWO_PHONE -> stringResource(R.string.session_history_mode_two_phone)
        }
    }
    val sortLabels = SortOrder.entries.map { order ->
        when (order) {
            SortOrder.NEWEST -> stringResource(R.string.session_history_sort_newest)
            SortOrder.OLDEST -> stringResource(R.string.session_history_sort_oldest)
            SortOrder.FASTEST -> stringResource(R.string.session_history_sort_fastest)
            SortOrder.MOST_RUNS -> stringResource(R.string.session_history_sort_most_runs)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.session_history_filters_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.race_pill_done),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HistoryFilterSection(stringResource(R.string.session_history_filter_distance)) {
                HistoryFilterGrid(
                    options = distanceLabels,
                    selectedIndex = selectedDistanceIndex,
                    columns = 3,
                    onSelected = { index ->
                        onDistanceSelected(distanceFilters[index].distance)
                    }
                )
            }

            HistoryFilterSection(stringResource(R.string.session_history_filter_start_type)) {
                HistoryFilterGrid(
                    options = startTypeLabels,
                    selectedIndex = selectedStartTypeIndex,
                    columns = 2,
                    onSelected = { index ->
                        onStartTypeSelected(
                            if (index == 0) null else startTypeFilters[index - 1].rawValue
                        )
                    }
                )
            }

            HistoryFilterSection(stringResource(R.string.session_history_filter_timing_mode)) {
                HistoryFilterGrid(
                    options = modeLabels,
                    selectedIndex = HistoryModeFilter.entries.indexOf(selectedMode),
                    columns = 3,
                    onSelected = { index -> onModeSelected(HistoryModeFilter.entries[index]) }
                )
            }

            HistoryFilterSection(stringResource(R.string.session_history_filter_sort)) {
                HistoryFilterGrid(
                    options = sortLabels,
                    selectedIndex = SortOrder.entries.indexOf(selectedSortOrder),
                    columns = 2,
                    onSelected = { index -> onSortOrderSelected(SortOrder.entries[index]) }
                )
            }

            if (hasModifiedFilters) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClear),
                    shape = RoundedCornerShape(12.dp),
                    color = DeleteRed.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = stringResource(R.string.session_history_clear_all_filters),
                        modifier = Modifier.padding(vertical = 13.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = DeleteRed,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryFilterSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = TextMuted
        )
        content()
    }
}

@Composable
private fun HistoryFilterGrid(
    options: List<String>,
    selectedIndex: Int,
    columns: Int,
    onSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEachIndexed { rowIndex, rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowOptions.forEachIndexed { columnIndex, option ->
                    val index = rowIndex * columns + columnIndex
                    HistoryFilterChip(
                        text = option,
                        isSelected = index == selectedIndex,
                        onClick = { onSelected(index) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - rowOptions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun HistoryFilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else CardBackground,
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(
            0.5.dp,
            BorderSubtle
        )
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else TextPrimary,
                maxLines = 1
            )
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
    val dateFormat = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault()
    )
    val dateStr = dateFormat.format(Date(session.date))
    var showActionsMenu by remember(session.id) { mutableStateOf(false) }

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

                // Match iOS metadata density: distance, laps/runs, then start type for multi-phone.
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
                    Text(
                        text = if (session.numberOfPhones == 1) {
                            pluralStringResource(
                                R.plurals.timing_lap_count,
                                cardData.runCount,
                                cardData.runCount
                            )
                        } else if (cardData.runCount == 1) {
                            stringResource(
                                R.string.session_history_run_count_singular,
                                cardData.runCount
                            )
                        } else {
                            stringResource(R.string.session_history_run_count, cardData.runCount)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1
                    )
                    if (session.numberOfPhones != 1) {
                        Text(
                            text = "\u2022",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted.copy(alpha = 0.6f)
                        )
                        Text(
                            text = StartType.fromRawValue(session.startType).localizedShortName(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1
                        )
                    }
                }

                // Best time
                if (cardData.bestTime != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.common_seconds_value,
                            formatTime(cardData.bestTime)
                        ),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = BestGreen
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(72.dp)
            ) {
                Box(modifier = Modifier.align(Alignment.TopCenter)) {
                    IconButton(
                        onClick = { showActionsMenu = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreHoriz,
                            contentDescription = stringResource(
                                R.string.session_history_session_menu_cd
                            ),
                            tint = TextSecondary
                        )
                    }
                    DropdownMenu(
                        expanded = showActionsMenu,
                        onDismissRequest = { showActionsMenu = false },
                        containerColor = SurfaceDark
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.session_history_delete_title),
                                    color = DeleteRed
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = DeleteRed
                                )
                            },
                            onClick = {
                                showActionsMenu = false
                                onDeleteClick()
                            }
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .size(16.dp),
                    tint = IconMuted.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** Shared by initial-load and retained-results states; never exposes raw database errors. */
@Composable
internal fun HistoryLoadFeedback(
    isLoading: Boolean,
    failed: Boolean,
    hasLoadedData: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = AccentBlue)
            Text(stringResource(R.string.history_loading), color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium)
        } else if (failed) {
            Text(stringResource(if (hasLoadedData) R.string.history_load_error_saved else R.string.history_load_error),
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.paywall_retry))
            }
        }
    }
}
