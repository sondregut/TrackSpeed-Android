package com.trackspeed.android.ui.screens.stats

import com.trackspeed.android.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.ChangeHistory
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SportsFootball
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.ui.util.parseAthleteColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ChartGreen = Color(0xFF30D158)
private val StatWarning = Color(0xFFFF9500)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onNavigateBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.gradientBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.stats_title),
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentBlue)
            }
        } else if (state.testTypes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.stats_no_data),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.stats_no_data_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "RANGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                TimeRangeSelector(
                    ranges = state.timeRanges,
                    selectedRange = state.selectedTimeRange,
                    onRangeSelected = viewModel::selectTimeRange
                )

                Text(
                    text = state.rangeContextText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "TEST TYPE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Test type selector
                TestTypeSelector(
                    testTypes = state.testTypes,
                    selectedTestType = state.selectedTestType,
                    onTestTypeSelected = { viewModel.selectTestType(it) }
                )

                if (state.athleteFilters.size > 1) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = stringResource(R.string.athlete_chip_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    AthleteSelector(
                        athletes = state.athleteFilters,
                        selectedAthleteId = state.selectedAthleteId,
                        onAthleteSelected = viewModel::selectAthlete
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                val rangeBestTime = state.rangeBestTime
                val rangeBestDateMillis = state.rangeBestDateMillis
                if (rangeBestTime != null && rangeBestDateMillis != null) {
                    RangeBestBanner(
                        timeSeconds = rangeBestTime,
                        dateMillis = rangeBestDateMillis
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                }

                // Summary stats card
                SummaryStatsCard(
                    bestTime = state.bestTime,
                    recentAverageTime = state.recentAverageTime,
                    averageTime = state.averageTime,
                    bestSpeed = state.bestSpeed,
                    speedUnit = state.speedUnit,
                    performanceDelta = state.performanceDelta,
                    consistency = state.consistency,
                    averageReactionTime = state.averageReactionTime,
                    totalRuns = state.totalRuns,
                    totalSessions = state.totalSessions
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Progress chart
                if (state.progressPoints.size >= 2) {
                    Text(
                        text = stringResource(R.string.stats_progress).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    ProgressChart(
                        points = state.progressPoints,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                } else if (state.progressPoints.size == 1) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .gunmetalCard(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.stats_more_sessions_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
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
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.emptyStateTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = state.emptyStateMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TimeRangeSelector(
    ranges: List<StatsTimeRange>,
    selectedRange: StatsTimeRange,
    onRangeSelected: (StatsTimeRange) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ranges.forEach { range ->
            val isSelected = range == selectedRange
            FilterChip(
                selected = isSelected,
                onClick = { onRangeSelected(range) },
                label = {
                    Text(
                        text = range.displayName,
                        color = if (isSelected) Color.White else TextSecondary
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = CardBackground,
                    selectedContainerColor = AccentBlue,
                    labelColor = TextSecondary,
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = BorderSubtle,
                    selectedBorderColor = AccentBlue,
                    enabled = true,
                    selected = isSelected
                )
            )
        }
    }
}

@Composable
private fun TestTypeSelector(
    testTypes: List<TestType>,
    selectedTestType: TestType?,
    onTestTypeSelected: (TestType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        testTypes.forEach { testType ->
            val isSelected = testType == selectedTestType
            FilterChip(
                selected = isSelected,
                onClick = { onTestTypeSelected(testType) },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = testType.icon(),
                            contentDescription = null,
                            tint = if (isSelected) Color.White else TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = testType.label,
                            color = if (isSelected) Color.White else TextSecondary
                        )
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = CardBackground,
                    selectedContainerColor = AccentBlue,
                    labelColor = TextSecondary,
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = BorderSubtle,
                    selectedBorderColor = AccentBlue,
                    enabled = true,
                    selected = isSelected
                )
            )
        }
    }
}

private fun TestType.icon(): ImageVector = when (this) {
    TestType.FLYING_10M,
    TestType.FLYING_20M,
    TestType.FLYING_30M -> Icons.Outlined.ElectricBolt
    TestType.SPRINT_10M,
    TestType.SPRINT_20M,
    TestType.SPRINT_30M,
    TestType.SPRINT_60M,
    TestType.SPRINT_100M -> Icons.AutoMirrored.Outlined.DirectionsRun
    TestType.FORTY_YARD_DASH -> Icons.Outlined.SportsFootball
    TestType.PRO_AGILITY -> Icons.Outlined.SwapHoriz
    TestType.L_DRILL -> Icons.Outlined.ChangeHistory
    TestType.TAKE_OFF_VELOCITY -> Icons.Outlined.NorthEast
    TestType.PRACTICE -> Icons.Outlined.Replay
    TestType.OTHER -> Icons.AutoMirrored.Outlined.HelpOutline
}

@Composable
private fun AthleteSelector(
    athletes: List<AthleteFilter>,
    selectedAthleteId: String?,
    onAthleteSelected: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AthleteFilterChip(
            name = "All",
            count = athletes.sumOf { it.runCount },
            color = null,
            isSelected = selectedAthleteId == null,
            onClick = { onAthleteSelected(null) }
        )
        athletes.forEach { athlete ->
            AthleteFilterChip(
                name = athlete.name,
                count = athlete.runCount,
                color = athlete.color,
                isSelected = selectedAthleteId == athlete.id,
                onClick = { onAthleteSelected(athlete.id) }
            )
        }
    }
}

@Composable
private fun AthleteFilterChip(
    name: String,
    count: Int,
    color: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                color?.let {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(parseAthleteColor(it), CircleShape)
                    )
                }
                Text(
                    text = "$name ($count)",
                    color = if (isSelected) Color.White else TextSecondary
                )
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = CardBackground,
            selectedContainerColor = AccentBlue,
            labelColor = TextSecondary,
            selectedLabelColor = Color.White
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = BorderSubtle,
            selectedBorderColor = AccentBlue,
            enabled = true,
            selected = isSelected
        )
    )
}

@Composable
private fun RangeBestBanner(
    timeSeconds: Double,
    dateMillis: Long
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .gunmetalCard(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Best in Range",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${formatTime(timeSeconds)}s",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextPrimary
                )
            }
            Text(
                text = formatShortDate(dateMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun SummaryStatsCard(
    bestTime: Double?,
    recentAverageTime: Double?,
    averageTime: Double?,
    bestSpeed: Double?,
    speedUnit: String,
    performanceDelta: Double?,
    consistency: Double?,
    averageReactionTime: Double?,
    totalRuns: Int,
    totalSessions: Int
) {
    val metrics = buildList {
        add(StatMetric("Best Time", bestTime?.let { "${formatTime(it)}s" } ?: "\u2014", TimerGreen))
        add(StatMetric("Last 5 Avg", recentAverageTime?.let { "${formatTime(it)}s" } ?: "\u2014", AccentBlue))
        add(StatMetric("Average", averageTime?.let { "${formatTime(it)}s" } ?: "\u2014", AccentBlue))
        add(StatMetric("Best Speed", bestSpeed?.let { formatSpeedValue(it, speedUnit) } ?: "\u2014", TextPrimary))
        add(StatMetric("Runs", totalRuns.toString(), TextPrimary))
        add(StatMetric("Sessions", totalSessions.toString(), TextPrimary))
        performanceDelta?.let {
            add(StatMetric("Since First", formatDeltaSeconds(it), if (it <= 0.0) TimerGreen else StatWarning))
        }
        consistency?.let {
            add(StatMetric("Consistency", "${formatSeconds3(it)}s", TextPrimary))
        }
        averageReactionTime?.let {
            add(StatMetric("Avg Reaction", "${formatSeconds3(it)}s", StatWarning))
        }
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
                .padding(20.dp)
        ) {
            // Header with icon + title (matches iOS statsCard header)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            metrics.chunked(2).forEachIndexed { index, row ->
                if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { metric ->
                        MetricTile(
                            label = metric.label,
                            value = metric.value,
                            valueColor = metric.valueColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class StatMetric(
    val label: String,
    val value: String,
    val valueColor: Color
)

@Composable
private fun MetricTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark.copy(alpha = 0.5f))
            .padding(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = valueColor
        )
    }
}

@Composable
private fun ProgressChart(
    points: List<ProgressPoint>,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) return

    val times = points.map { it.bestTime.toFloat() }
    val minTime = times.min()
    val maxTime = times.max()
    // Add some padding to the Y range so points are not at the very edge
    val timeRange = (maxTime - minTime).coerceAtLeast(0.1f)
    val yPadding = timeRange * 0.15f
    val yMin = minTime - yPadding
    val yMax = maxTime + yPadding

    // Capture composable colors for use inside Canvas DrawScope
    val textSecondaryColor = TextSecondary
    val borderSubtleColor = BorderSubtle
    val cardBottomColor = GunmetalCardBottom

    Card(
        modifier = modifier.gunmetalCard(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            val chartLeft = 56f
            val chartRight = size.width - 16f
            val chartTop = 16f
            val chartBottom = size.height - 40f
            val chartWidth = chartRight - chartLeft
            val chartHeight = chartBottom - chartTop

            val textPaintColor = android.graphics.Color.argb(
                (textSecondaryColor.alpha * 255).toInt(),
                (textSecondaryColor.red * 255).toInt(),
                (textSecondaryColor.green * 255).toInt(),
                (textSecondaryColor.blue * 255).toInt()
            )
            val textPaint = android.graphics.Paint().apply {
                color = textPaintColor
                textSize = 28f
                isAntiAlias = true
            }

            // Draw Y-axis labels and horizontal grid lines
            val ySteps = 4
            for (i in 0..ySteps) {
                val fraction = i.toFloat() / ySteps
                val y = chartTop + fraction * chartHeight
                // Y axis is inverted: top = max time (slower), bottom = min time (faster)
                // So lower on chart = faster time = lower value
                val timeValue = yMax - fraction * (yMax - yMin)

                // Grid line
                drawLine(
                    color = borderSubtleColor,
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 1f
                )

                // Y label
                val label = formatTime(timeValue.toDouble())
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    4f,
                    y + 10f,
                    textPaint
                )
            }

            // Draw X-axis labels
            val xLabelPaint = android.graphics.Paint().apply {
                color = textPaintColor
                textSize = 26f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }

            // Show a reasonable number of X labels
            val maxXLabels = 8
            val step = if (points.size <= maxXLabels) 1 else (points.size / maxXLabels).coerceAtLeast(1)

            for (i in points.indices step step) {
                val xFraction = if (points.size > 1) {
                    i.toFloat() / (points.size - 1)
                } else 0f
                val x = chartLeft + xFraction * chartWidth

                drawContext.canvas.nativeCanvas.drawText(
                    "${points[i].sessionIndex}",
                    x,
                    chartBottom + 30f,
                    xLabelPaint
                )
            }

            // Plot data points and connecting line
            if (points.size >= 2) {
                val path = Path()
                val dataPoints = mutableListOf<Offset>()

                points.forEachIndexed { index, point ->
                    val xFraction = index.toFloat() / (points.size - 1)
                    val yFraction = (yMax - point.bestTime.toFloat()) / (yMax - yMin)
                    val x = chartLeft + xFraction * chartWidth
                    val y = chartTop + (1f - yFraction) * chartHeight
                    dataPoints.add(Offset(x, y))

                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                // Draw line
                drawPath(
                    path = path,
                    color = ChartGreen,
                    style = Stroke(
                        width = 3f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Draw data point circles
                dataPoints.forEach { point ->
                    // Outer circle
                    drawCircle(
                        color = ChartGreen,
                        radius = 6f,
                        center = point
                    )
                    // Inner circle
                    drawCircle(
                        color = cardBottomColor,
                        radius = 3f,
                        center = point
                    )
                }
            }
        }
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds <= 0) return "0.00"
    val totalMs = (seconds * 1000).toLong()
    val mins = totalMs / 60000
    val secs = (totalMs % 60000) / 1000
    val hundredths = (totalMs % 1000) / 10
    return if (mins > 0) String.format(Locale.getDefault(), "%d:%02d.%02d", mins, secs, hundredths)
    else String.format(Locale.getDefault(), "%d.%02d", secs, hundredths)
}

private fun formatSeconds3(seconds: Double): String {
    return String.format(Locale.getDefault(), "%.3f", seconds)
}

private fun formatDeltaSeconds(seconds: Double): String {
    return String.format(Locale.getDefault(), "%+.3fs", seconds)
}

private fun formatSpeedValue(speedMs: Double, speedUnit: String): String {
    return when (speedUnit) {
        "km/h" -> String.format(Locale.getDefault(), "%.1f km/h", speedMs * 3.6)
        "mph" -> String.format(Locale.getDefault(), "%.1f mph", speedMs * 2.23694)
        else -> String.format(Locale.getDefault(), "%.1f m/s", speedMs)
    }
}

private fun formatShortDate(dateMillis: Long): String {
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(dateMillis))
}
