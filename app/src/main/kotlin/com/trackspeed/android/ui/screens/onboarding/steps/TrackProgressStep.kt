package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R

private val AccentBlue = Color(0xFF0A84FF)
private val SurfaceColor = Color(0xFF1C1C1E)

private data class DataPoint(val month: String, val time: Double)

private val dataPoints = listOf(
    DataPoint("Mar", 1.07),
    DataPoint("Apr", 1.05),
    DataPoint("May", 1.03),
    DataPoint("Jun", 1.01),
    DataPoint("Jul", 0.99),
    DataPoint("Aug", 0.98)
)

private const val MIN_TIME = 0.90
private const val MAX_TIME = 1.10

@Composable
fun TrackProgressStep(onContinue: () -> Unit) {
    var appeared by remember { mutableStateOf(false) }
    val graphProgress = remember { Animatable(0f) }
    var showResearchCard by remember { mutableStateOf(false) }
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(Unit) {
        appeared = true
        graphProgress.animateTo(
            1f,
            animationSpec = tween(1200, delayMillis = 150, easing = LinearEasing)
        )
        showResearchCard = true
    }

    // Pre-resolve strings for Canvas
    val chartTitle = stringResource(R.string.onboarding_progress_chart_title)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // Headline
        Text(
            text = stringResource(R.string.onboarding_progress_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_progress_subtitle),
            fontSize = 15.sp,
            color = Color(0xFF8E8E93),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(0.5f))

        // Chart title
        Text(
            text = chartTitle,
            fontSize = 14.sp,
            color = Color(0xFFAEAEB2),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 50.dp, bottom = 8.dp)
        )

        // Performance graph
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
        ) {
            val chartLeft = 50f
            val chartBottom = size.height - 40f
            val chartWidth = size.width - chartLeft
            val chartHeight = chartBottom

            // Y-axis labels
            val yValues = listOf(1.10, 1.05, 1.00, 0.95, 0.90)
            yValues.forEach { value ->
                val normalized = (value - MIN_TIME) / (MAX_TIME - MIN_TIME)
                val y = chartBottom - (normalized.toFloat() * chartHeight)

                // Grid line
                drawLine(
                    color = Color(0xFF2C2C2E),
                    start = Offset(chartLeft, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )

                // Y label
                val label = textMeasurer.measure(
                    String.format("%.2fs", value),
                    style = TextStyle(
                        color = Color(0xFF636366),
                        fontSize = 10.sp
                    )
                )
                drawText(
                    label,
                    topLeft = Offset(0f, y - label.size.height / 2)
                )
            }

            // X-axis labels
            dataPoints.forEachIndexed { index, point ->
                val xStep = chartWidth / (dataPoints.size - 1)
                val x = chartLeft + index * xStep
                val label = textMeasurer.measure(
                    point.month,
                    style = TextStyle(
                        color = Color(0xFF636366),
                        fontSize = 10.sp
                    )
                )
                drawText(
                    label,
                    topLeft = Offset(x - label.size.width / 2, chartBottom + 8f)
                )
            }

            // Calculate all point positions
            val points = dataPoints.mapIndexed { index, point ->
                val xStep = chartWidth / (dataPoints.size - 1)
                val x = chartLeft + index * xStep
                val normalized = ((point.time - MIN_TIME) / (MAX_TIME - MIN_TIME)).toFloat()
                val y = chartBottom - (normalized * chartHeight)
                Offset(x, y)
            }

            // Gradient fill under line (masked by progress)
            val progress = graphProgress.value
            val revealWidth = chartWidth * progress

            clipRect(left = chartLeft, right = chartLeft + revealWidth) {
                val fillPath = Path().apply {
                    moveTo(points.first().x, chartBottom)
                    lineTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                    lineTo(points.last().x, chartBottom)
                    close()
                }
                drawPath(
                    fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            AccentBlue.copy(alpha = 0.25f),
                            AccentBlue.copy(alpha = 0.02f)
                        ),
                        startY = 0f,
                        endY = chartBottom
                    )
                )
            }

            // Line path (drawn with trim via clipRect)
            clipRect(left = chartLeft, right = chartLeft + revealWidth) {
                val linePath = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                }
                drawPath(
                    linePath,
                    color = AccentBlue,
                    style = Stroke(
                        width = 3f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Data point dots
            points.forEachIndexed { index, point ->
                val pointThreshold = index.toFloat() / (dataPoints.size - 1).toFloat()
                if (progress >= pointThreshold) {
                    // Outer dot
                    drawCircle(
                        color = AccentBlue,
                        radius = 5f,
                        center = point
                    )
                    // Inner dot
                    drawCircle(
                        color = Color(0xFF0A0A0A),
                        radius = 2f,
                        center = point
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Research callout card
        if (showResearchCard) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceColor),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = AccentBlue
                        )
                        Text(
                            text = stringResource(R.string.onboarding_progress_research_label),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF636366),
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.onboarding_progress_research_body),
                        fontSize = 14.sp,
                        color = Color.White,
                        lineHeight = 20.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.onboarding_progress_research_detail),
                        fontSize = 12.sp,
                        color = Color(0xFF636366),
                        lineHeight = 18.sp
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            Text(stringResource(R.string.common_continue), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(32.dp))
    }
}
