package com.trackspeed.android.ui.screens.history

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
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ScreenBackground = Color(0xFF000000)
private val CardBackground = Color(0xFF2C2C2E)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFF8E8E93)
private val AccentBlue = Color(0xFF0A84FF)
private val ChipUnselectedBackground = Color(0xFF1C1C1E)

@Composable
fun SessionHistoryScreen(
    onSessionClick: (String) -> Unit = {},
    viewModel: SessionHistoryViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsState()
    val filterDistance by viewModel.filterDistance.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val hasUnfilteredSessions by viewModel.hasUnfilteredSessions.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "History",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Distance filter chips
        FilterChipRow(
            label = "Distance",
            options = DISTANCE_FILTERS.map { it.label },
            selectedIndex = DISTANCE_FILTERS.indexOfFirst { it.distance == filterDistance },
            onSelected = { index ->
                viewModel.setFilterDistance(DISTANCE_FILTERS[index].distance)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Sort order chips
        FilterChipRow(
            label = "Sort by",
            options = SortOrder.entries.map { it.label },
            selectedIndex = SortOrder.entries.indexOf(sortOrder),
            onSelected = { index ->
                viewModel.setSortOrder(SortOrder.entries[index])
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (sessions.isEmpty() && hasUnfilteredSessions) {
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
                        text = "No sessions match your filters",
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
                        Text("Clear Filters")
                    }
                }
            }
        } else if (sessions.isEmpty()) {
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
                        text = "No sessions yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = "Your timing sessions will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    items = sessions,
                    key = { it.id }
                ) { session ->
                    SessionCard(
                        session = session,
                        onClick = { onSessionClick(session.id) }
                    )
                }
            }
        }
    }
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
                    color = if (isSelected) AccentBlue else ChipUnselectedBackground
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
    session: TrainingSessionEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(session.date))

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardBackground
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.name ?: "Practice Session",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = dateStr,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            if (session.distance > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${session.distance.toInt()}m",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = TextSecondary
                )
            }
        }
    }
}
