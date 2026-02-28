package com.trackspeed.android.ui.screens.history

import com.trackspeed.android.ui.theme.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.ui.util.formatTime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BestGreen = Color(0xFF30D158)
private val DeleteRed = Color(0xFFFF3B30)

@Composable
fun SessionHistoryScreen(
    onSessionClick: (String) -> Unit = {},
    viewModel: SessionHistoryViewModel = hiltViewModel()
) {
    val dateGroups by viewModel.dateGroups.collectAsState()
    val filterDistance by viewModel.filterDistance.collectAsState()
    val filterStartType by viewModel.filterStartType.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val distanceFilters by viewModel.distanceFilters.collectAsState()
    val startTypeFilters by viewModel.startTypeFilters.collectAsState()
    val hasAnySessions by viewModel.hasAnySessions.collectAsState()
    val hasActiveFilters by viewModel.hasActiveFilters.collectAsState()
    val historyStats by viewModel.historyStats.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

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

        Text(
            text = stringResource(R.string.session_history_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

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

        // Sort order chips
        FilterChipRow(
            label = stringResource(R.string.session_history_filter_sort),
            options = SortOrder.entries.map { order ->
                when (order) {
                    SortOrder.NEWEST -> stringResource(R.string.session_history_sort_newest)
                    SortOrder.OLDEST -> stringResource(R.string.session_history_sort_oldest)
                    SortOrder.FASTEST -> stringResource(R.string.session_history_sort_fastest)
                    SortOrder.SLOWEST -> stringResource(R.string.session_history_sort_slowest)
                }
            },
            selectedIndex = SortOrder.entries.indexOf(sortOrder),
            onSelected = { index ->
                viewModel.setSortOrder(SortOrder.entries[index])
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

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
                            text = groupLabel.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.5.sp
                            ),
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp)
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
private fun HeaderStatsRow(stats: HistoryStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MiniStatChip(
            icon = Icons.Outlined.CalendarMonth,
            value = stats.totalSessions.toString(),
            modifier = Modifier.weight(1f)
        )
        MiniStatChip(
            icon = Icons.Outlined.Timer,
            value = stats.totalRuns.toString(),
            modifier = Modifier.weight(1f)
        )
        MiniStatChip(
            icon = Icons.Outlined.Speed,
            value = stats.bestTime?.let { formatTime(it) } ?: "--",
            valueColor = if (stats.bestTime != null) BestGreen else TextSecondary,
            modifier = Modifier.weight(1f)
        )
        MiniStatChip(
            icon = Icons.Outlined.LocalFireDepartment,
            value = stats.weeklySessionCount.toString(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MiniStatChip(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = SurfaceDark
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = TextSecondary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = valueColor
            )
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
        value = withContext(Dispatchers.IO) {
            session.thumbnailPath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) BitmapFactory.decodeFile(path) else null
                } catch (e: Exception) {
                    null
                }
            }
        }
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
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thumbnail (Task 1)
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(62.dp)
                    .clip(RoundedCornerShape(8.dp)),
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
                            .background(Color.White.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = TextSecondary.copy(alpha = 0.3f)
                        )
                    }
                }
            }

            // Session info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name ?: stringResource(R.string.session_history_session_name_default),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (session.distance > 0) {
                        Text(
                            text = "${session.distance.toInt()}m",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = TextSecondary
                        )
                    }
                    if (cardData.runCount > 0) {
                        Text(
                            text = if (cardData.runCount == 1)
                            stringResource(R.string.session_history_run_count_singular, cardData.runCount)
                        else
                            stringResource(R.string.session_history_run_count, cardData.runCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Best time + delete
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Best time (Task 1)
                if (cardData.bestTime != null) {
                    Text(
                        text = formatTime(cardData.bestTime),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = BestGreen
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Delete button (Task 12)
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.session_history_delete_session_cd),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onDeleteClick() },
                    tint = TextSecondary.copy(alpha = 0.4f)
                )
            }
        }
    }
}

