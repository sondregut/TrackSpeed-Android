package com.trackspeed.android.ui.screens.history

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.ui.components.ExpandedThumbnail
import com.trackspeed.android.ui.components.ThumbnailViewerDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ScreenBackground = Color(0xFF000000)
private val CardBackground = Color(0xFF2C2C2E)
private val TopBarBackground = Color(0xFF1C1C1E)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFF8E8E93)
private val BestGreen = Color(0xFF4CAF50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var expandedThumbnail by remember { mutableStateOf<ExpandedThumbnail?>(null) }

    // Fullscreen thumbnail viewer
    ThumbnailViewerDialog(
        thumbnail = expandedThumbnail,
        onDismiss = { expandedThumbnail = null }
    )

    Scaffold(
        containerColor = ScreenBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.session?.name ?: "Session Details",
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TopBarBackground,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenBackground)
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Session info header
            uiState.session?.let { session ->
                item {
                    SessionInfoHeader(session = session)
                }
            }

            // Stats card
            if (uiState.runs.isNotEmpty()) {
                item {
                    StatsCard(
                        bestTime = uiState.bestTime,
                        averageTime = uiState.averageTime,
                        totalRuns = uiState.runs.size
                    )
                }
            }

            // Run list
            items(
                items = uiState.runs,
                key = { it.id }
            ) { run ->
                RunCard(
                    run = run,
                    isBest = uiState.bestTime != null && run.timeSeconds == uiState.bestTime,
                    onThumbnailClick = { expandedThumbnail = it }
                )
            }
        }
    }
}

@Composable
private fun SessionInfoHeader(
    session: com.trackspeed.android.data.local.entities.TrainingSessionEntity
) {
    val dateFormat = SimpleDateFormat("EEEE, MMM d, yyyy  HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(session.date))

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = dateStr,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        if (session.distance > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${session.distance.toInt()}m  |  ${session.startType}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun StatsCard(
    bestTime: Double?,
    averageTime: Double?,
    totalRuns: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardBackground
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(label = "Best", value = bestTime?.let { formatTime(it) } ?: "--")
            StatItem(label = "Average", value = averageTime?.let { formatTime(it) } ?: "--")
            StatItem(label = "Runs", value = totalRuns.toString())
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
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
private fun RunCard(
    run: RunEntity,
    isBest: Boolean,
    onThumbnailClick: (ExpandedThumbnail) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isBest) {
        BestGreen.copy(alpha = 0.6f)
    } else {
        Color.White.copy(alpha = 0.1f)
    }
    val borderWidth = if (isBest) 2.dp else 1.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                CardBackground,
                shape = RoundedCornerShape(12.dp)
            )
            .border(borderWidth, borderColor, shape = RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Thumbnail
        val bitmap = remember(run.thumbnailPath) {
            run.thumbnailPath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) BitmapFactory.decodeFile(path) else null
                } catch (e: Exception) {
                    null
                }
            }
        }

        Box(
            modifier = Modifier
                .width(60.dp)
                .height(75.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                )
                .then(
                    if (bitmap != null) {
                        Modifier.clickable {
                            onThumbnailClick(
                                ExpandedThumbnail(
                                    bitmap = bitmap,
                                    gatePosition = 0.5f
                                )
                            )
                        }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Run ${run.runNumber}",
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
                    Text(
                        text = "#${run.runNumber}",
                        color = TextSecondary.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Run info
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Run ${run.runNumber}",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary
                )

                if (isBest) {
                    Text(
                        text = "BEST",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = BestGreen,
                        modifier = Modifier
                            .background(
                                BestGreen.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (run.athleteName != null) {
                Text(
                    text = run.athleteName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary.copy(alpha = 0.7f)
                )
            }
        }

        // Time
        Text(
            text = formatTime(run.timeSeconds),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = if (isBest) BestGreen else TextPrimary
        )
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds <= 0) return "0.00"

    val totalMs = (seconds * 1000).toLong()
    val mins = totalMs / 60000
    val secs = (totalMs % 60000) / 1000
    val hundredths = (totalMs % 1000) / 10

    return if (mins > 0) {
        String.format("%d:%02d.%02d", mins, secs, hundredths)
    } else {
        String.format("%d.%02d", secs, hundredths)
    }
}
