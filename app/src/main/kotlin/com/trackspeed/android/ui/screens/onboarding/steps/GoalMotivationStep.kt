package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.ui.theme.*

@Composable
fun GoalMotivationStep(
    flyingPR: Double?,
    goalTime: Double?,
    flyingDistance: FlyingDistance?,
    onContinue: () -> Unit
) {
    val hasGoalData = flyingPR != null && goalTime != null && goalTime < flyingPR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            text = if (hasGoalData) "Great goal!" else "Track your progress",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your personalized training path",
            fontSize = 15.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        if (hasGoalData) {
            GoalProgressChart(
                flyingPR = flyingPR!!,
                goalTime = goalTime!!,
                flyingDistance = flyingDistance
            )
            Spacer(Modifier.height(24.dp))
        }

        ResearchCalloutCard()

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNavy)
        ) {
            Text(
                stringResource(R.string.common_continue),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun GoalProgressChart(
    flyingPR: Double,
    goalTime: Double,
    flyingDistance: FlyingDistance?
) {
    // Capture @Composable colors before Canvas DrawScope
    val textPrimaryColor = TextPrimary
    val textSecondaryColor = TextSecondary
    val accentColor = AccentNavy // static val — no capture needed, but clarity
    val textMeasurer = rememberTextMeasurer()

    // 5 linearly interpolated data points from PR → goal
    val dataPoints = (0..4).map { i -> flyingPR + (goalTime - flyingPR) * (i / 4.0) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
    ) {
        val w = size.width
        val h = size.height

        val paddingH = 44f
        val paddingTop = 32f
        val paddingBottom = 22f

        val chartLeft = paddingH
        val chartRight = w - paddingH
        val chartTop = paddingTop
        val chartBottom = h - paddingBottom
        val chartW = chartRight - chartLeft
        val chartH = chartBottom - chartTop

        // Y mapping: lower time (goalTime) → chartTop, higher time (flyingPR) → chartBottom
        val range = flyingPR - goalTime
        fun timeToY(time: Double): Float {
            val normalized = ((time - goalTime) / range).toFloat()
            return chartTop + normalized * chartH
        }

        fun indexToX(i: Int): Float = chartLeft + (i / 4.0f) * chartW

        // Background track line
        drawLine(
            color = textSecondaryColor.copy(alpha = 0.12f),
            start = Offset(chartLeft, h / 2),
            end = Offset(chartRight, h / 2),
            strokeWidth = 1f
        )

        // Progress line connecting all 5 points
        val path = Path()
        path.moveTo(indexToX(0), timeToY(dataPoints[0]))
        for (i in 1..4) {
            path.lineTo(indexToX(i), timeToY(dataPoints[i]))
        }
        drawPath(
            path = path,
            color = accentColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        )

        // Dots with glow
        dataPoints.forEachIndexed { i, time ->
            val x = indexToX(i)
            val y = timeToY(time)
            val dotColor = when (i) {
                0 -> TimerRed
                4 -> AccentGreen
                else -> accentColor
            }
            drawCircle(dotColor.copy(alpha = 0.2f), 14f, Offset(x, y))
            drawCircle(dotColor, 6f, Offset(x, y))
        }

        // Time label above first point (Now / flyingPR)
        val prText = String.format("%.2f", flyingPR) + "s"
        val prLabelResult = textMeasurer.measure(
            text = prText,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TimerRed)
        )
        drawText(
            textLayoutResult = prLabelResult,
            topLeft = Offset(
                indexToX(0) - prLabelResult.size.width / 2f,
                timeToY(dataPoints[0]) - prLabelResult.size.height - 6f
            )
        )

        // Time label above last point (Goal / goalTime)
        val goalText = String.format("%.2f", goalTime) + "s"
        val goalLabelResult = textMeasurer.measure(
            text = goalText,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AccentGreen)
        )
        drawText(
            textLayoutResult = goalLabelResult,
            topLeft = Offset(
                indexToX(4) - goalLabelResult.size.width / 2f,
                timeToY(dataPoints[4]) - goalLabelResult.size.height - 6f
            )
        )

        // X-axis label "Now"
        val nowLabel = textMeasurer.measure(
            text = "Now",
            style = TextStyle(fontSize = 11.sp, color = textSecondaryColor)
        )
        drawText(
            textLayoutResult = nowLabel,
            topLeft = Offset(indexToX(0) - nowLabel.size.width / 2f, chartBottom + 4f)
        )

        // X-axis label "Goal"
        val goalXLabel = textMeasurer.measure(
            text = "Goal",
            style = TextStyle(fontSize = 11.sp, color = AccentGreen)
        )
        drawText(
            textLayoutResult = goalXLabel,
            topLeft = Offset(indexToX(4) - goalXLabel.size.width / 2f, chartBottom + 4f)
        )
    }

    // Distance label below chart
    if (flyingDistance != null) {
        Text(
            text = "${flyingDistance.rawValue} flying start",
            fontSize = 12.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ResearchCalloutCard() {
    val surfaceColor = SurfaceDark
    val borderColor = BorderSubtle

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(surfaceColor)
            .border(0.5.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(AccentNavy.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = AccentNavy,
                modifier = Modifier.size(18.dp)
            )
        }
        Column {
            Text(
                text = "RESEARCH-BACKED",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = AccentNavy,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Sprint athletes who track progress with structured goal-setting improve 2–5% more per training cycle.",
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 18.sp
            )
        }
    }
}
