package com.trackspeed.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.ui.theme.AccentNavy
import com.trackspeed.android.ui.theme.BorderSubtle
import com.trackspeed.android.ui.theme.SurfaceDark
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary

private val StartGreen = Color(0xFF30D158)
private val FinishAmber = Color(0xFFFFAB00)

/**
 * Gate marker data for the track visualization.
 *
 * @param label Label for the gate (e.g. "START", "FINISH", "LAP 1").
 * @param distanceMeters Distance from start in meters.
 * @param color Color for this gate marker.
 */
data class GateMarker(
    val label: String,
    val distanceMeters: Double,
    val color: Color
)

/**
 * Canvas-drawn simplified track visualization with gate markers.
 * Shows a horizontal track bar with positioned gates, segment distances,
 * and a runner icon. Used for session setup visualization.
 * Matches iOS TrackVisualizationCard component.
 *
 * @param gates List of gate markers positioned along the track.
 * @param totalDistanceLabel Display label for total distance (e.g. "60m").
 * @param modifier Modifier.
 */
@Composable
fun TrackVisualizationCard(
    gates: List<GateMarker>,
    totalDistanceLabel: String = "",
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TRACK LAYOUT",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        fontSize = 10.sp
                    ),
                    color = TextSecondary
                )
                if (gates.isNotEmpty()) {
                    Text(
                        text = "${gates.size} phone${if (gates.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Track canvas
            val totalDistance = gates.maxOfOrNull { it.distanceMeters } ?: 100.0

            // Capture composable colors for use inside Canvas DrawScope
            val borderSubtleColor = BorderSubtle
            val textSecondaryColor = TextSecondary

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            ) {
                val trackY = size.height * 0.45f
                val trackPadding = 36f
                val trackWidth = size.width - trackPadding * 2

                // Draw track line
                drawRoundRect(
                    color = borderSubtleColor,
                    topLeft = Offset(trackPadding, trackY - 2f),
                    size = Size(trackWidth, 4f),
                    cornerRadius = CornerRadius(2f, 2f)
                )

                // Draw segments between gates with gradient-like coloring
                for (i in 0 until gates.size - 1) {
                    val fromFrac = if (totalDistance > 0) gates[i].distanceMeters / totalDistance else 0.0
                    val toFrac = if (totalDistance > 0) gates[i + 1].distanceMeters / totalDistance else 1.0
                    val fromX = trackPadding + (fromFrac * trackWidth).toFloat()
                    val toX = trackPadding + (toFrac * trackWidth).toFloat()

                    drawLine(
                        color = gates[i].color.copy(alpha = 0.7f),
                        start = Offset(fromX, trackY),
                        end = Offset(toX, trackY),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round
                    )

                    // Segment distance label
                    val segDist = (gates[i + 1].distanceMeters - gates[i].distanceMeters).toInt()
                    val labelX = (fromX + toX) / 2
                    val labelResult = textMeasurer.measure(
                        text = "${segDist}m",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    )
                    drawText(
                        textLayoutResult = labelResult,
                        topLeft = Offset(
                            labelX - labelResult.size.width / 2f,
                            trackY - 24f
                        )
                    )
                }

                // Draw gate markers
                for (gate in gates) {
                    val frac = if (totalDistance > 0) gate.distanceMeters / totalDistance else 0.0
                    val x = trackPadding + (frac * trackWidth).toFloat()

                    // Outer glow
                    drawCircle(
                        color = gate.color.copy(alpha = 0.15f),
                        radius = 18f,
                        center = Offset(x, trackY)
                    )

                    // Main circle
                    drawCircle(
                        color = gate.color,
                        radius = 12f,
                        center = Offset(x, trackY)
                    )

                    // Gate label below
                    val gateLabelResult = textMeasurer.measure(
                        text = gate.label,
                        style = TextStyle(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = gate.color
                        )
                    )
                    drawText(
                        textLayoutResult = gateLabelResult,
                        topLeft = Offset(
                            x - gateLabelResult.size.width / 2f,
                            trackY + 20f
                        )
                    )

                    // Distance below label
                    val distLabelResult = textMeasurer.measure(
                        text = "${gate.distanceMeters.toInt()}m",
                        style = TextStyle(
                            fontSize = 9.sp,
                            color = textSecondaryColor
                        )
                    )
                    drawText(
                        textLayoutResult = distLabelResult,
                        topLeft = Offset(
                            x - distLabelResult.size.width / 2f,
                            trackY + 34f
                        )
                    )
                }
            }

            // Total distance label
            if (totalDistanceLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = totalDistanceLabel,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun TrackVisualizationCard2GatesPreview() {
    TrackVisualizationCard(
        gates = listOf(
            GateMarker("START", 0.0, StartGreen),
            GateMarker("FINISH", 60.0, FinishAmber)
        ),
        totalDistanceLabel = "60m Sprint",
        modifier = Modifier.padding(16.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun TrackVisualizationCard3GatesPreview() {
    TrackVisualizationCard(
        gates = listOf(
            GateMarker("START", 0.0, StartGreen),
            GateMarker("LAP 1", 30.0, AccentNavy),
            GateMarker("FINISH", 60.0, FinishAmber)
        ),
        totalDistanceLabel = "60m with Split",
        modifier = Modifier.padding(16.dp)
    )
}
