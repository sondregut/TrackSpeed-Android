package com.trackspeed.android.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val ScreenBackground = Color(0xFF000000)
private val CardBackground = Color(0xFF2C2C2E)
private val ChipUnselected = Color(0xFF1C1C1E)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFF8E8E93)
private val AccentBlue = Color(0xFF0A84FF)
private val ChartGreen = Color(0xFF30D158)
private val TopBarBackground = Color(0xFF1C1C1E)
private val ChipBorder = Color(0xFF38383A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onNavigateBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = ScreenBackground,
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
                    containerColor = TopBarBackground,
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

                // Test type selector
                TestTypeSelector(
                    testTypes = state.testTypes,
                    selectedTestType = state.selectedTestType,
                    onTestTypeSelected = { viewModel.selectTestType(it) }
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Summary stats card
                SummaryStatsCard(
                    bestTime = state.bestTime,
                    averageTime = state.averageTime,
                    totalRuns = state.totalRuns,
                    totalSessions = state.totalSessions
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Progress chart
                if (state.progressPoints.size >= 2) {
                    Text(
                        text = stringResource(R.string.stats_progress),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
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
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
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
                    Text(
                        text = testType.label,
                        color = if (isSelected) Color.White else TextSecondary
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = ChipUnselected,
                    selectedContainerColor = AccentBlue,
                    labelColor = TextSecondary,
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = ChipBorder,
                    selectedBorderColor = AccentBlue,
                    enabled = true,
                    selected = isSelected
                )
            )
        }
    }
}

@Composable
private fun SummaryStatsCard(
    bestTime: Double?,
    averageTime: Double?,
    totalRuns: Int,
    totalSessions: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatCell(
                value = bestTime?.let { formatTime(it) } ?: "--",
                label = stringResource(R.string.stats_best),
                modifier = Modifier.weight(1f)
            )
            StatCell(
                value = averageTime?.let { formatTime(it) } ?: "--",
                label = stringResource(R.string.stats_average),
                modifier = Modifier.weight(1f)
            )
            StatCell(
                value = totalRuns.toString(),
                label = stringResource(R.string.stats_runs),
                modifier = Modifier.weight(1f)
            )
            StatCell(
                value = totalSessions.toString(),
                label = stringResource(R.string.stats_sessions),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
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

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
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

            val textPaint = android.graphics.Paint().apply {
                color = 0xFF8E8E93.toInt()
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
                    color = Color(0xFF38383A),
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
                color = 0xFF8E8E93.toInt()
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
                        color = CardBackground,
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
    return if (mins > 0) String.format("%d:%02d.%02d", mins, secs, hundredths)
    else String.format("%d.%02d", secs, hundredths)
}
