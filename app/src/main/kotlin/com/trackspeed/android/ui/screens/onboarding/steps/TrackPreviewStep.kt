package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import kotlin.math.cos
import kotlin.math.sin

private val AccentBlue = Color(0xFF0A84FF)
private val TrackBrown = Color(0xFFCC6633)
private val TrackLineWhite = Color(0xFFE5E5EA)
private val GreenMarker = Color(0xFF30D158)
private val RedMarker = Color(0xFFFF453A)

@Composable
fun TrackPreviewStep(onContinue: () -> Unit) {
    // Animate the runner dot along the track
    val infiniteTransition = rememberInfiniteTransition(label = "runner")
    val runnerProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "runner_progress"
    )

    val textMeasurer = rememberTextMeasurer()

    // Pre-resolve strings for Canvas usage
    val startLabel = stringResource(R.string.onboarding_preview_start_label)
    val finishLabel = stringResource(R.string.onboarding_preview_finish_label)
    val startText = stringResource(R.string.onboarding_preview_start_text)
    val finishText = stringResource(R.string.onboarding_preview_finish_text)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.onboarding_preview_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_preview_description),
            fontSize = 15.sp,
            color = Color(0xFF8E8E93),
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(32.dp))

        // Track illustration
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            val w = size.width
            val h = size.height
            val centerX = w / 2
            val centerY = h / 2

            // Track dimensions
            val trackWidth = w * 0.85f
            val trackHeight = h * 0.55f
            val halfW = trackWidth / 2
            val halfH = trackHeight / 2
            val laneWidth = trackHeight * 0.12f

            // Draw track oval (outer)
            drawOval(
                color = TrackBrown.copy(alpha = 0.3f),
                topLeft = Offset(centerX - halfW, centerY - halfH),
                size = Size(trackWidth, trackHeight)
            )

            // Draw track oval (inner)
            val innerW = halfW - laneWidth
            val innerH = halfH - laneWidth
            drawOval(
                color = Color(0xFF0A0A0A),
                topLeft = Offset(centerX - innerW, centerY - innerH),
                size = Size(innerW * 2, innerH * 2)
            )

            // Draw lane lines
            drawOval(
                color = TrackLineWhite.copy(alpha = 0.4f),
                topLeft = Offset(centerX - halfW, centerY - halfH),
                size = Size(trackWidth, trackHeight),
                style = Stroke(width = 2f)
            )
            drawOval(
                color = TrackLineWhite.copy(alpha = 0.4f),
                topLeft = Offset(centerX - innerW, centerY - innerH),
                size = Size(innerW * 2, innerH * 2),
                style = Stroke(width = 2f)
            )

            // Start/finish line (vertical dashed line at right side of oval)
            val lineX = centerX + halfW - laneWidth / 2
            drawLine(
                color = Color.White,
                start = Offset(lineX, centerY - halfH),
                end = Offset(lineX, centerY - innerH),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )

            // Start marker (green circle at bottom-right of track)
            val startAngle = -0.15f * Math.PI.toFloat()
            val startX = centerX + (halfW - laneWidth / 2) * cos(startAngle)
            val startY = centerY + (halfH - laneWidth / 2) * sin(startAngle)
            drawCircle(
                color = GreenMarker,
                radius = 14f,
                center = Offset(startX, startY)
            )
            // "S" label
            val startTextLayout = textMeasurer.measure(
                startLabel,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            drawText(
                startTextLayout,
                topLeft = Offset(
                    startX - startTextLayout.size.width / 2,
                    startY - startTextLayout.size.height / 2
                )
            )

            // Finish marker (red circle at top-right of track)
            val finishAngle = 0.15f * Math.PI.toFloat()
            val finishX = centerX + (halfW - laneWidth / 2) * cos(-finishAngle)
            val finishY = centerY + (halfH - laneWidth / 2) * sin(-finishAngle)
            drawCircle(
                color = RedMarker,
                radius = 14f,
                center = Offset(finishX, finishY)
            )
            // "F" label
            val finishTextLayout = textMeasurer.measure(
                finishLabel,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            drawText(
                finishTextLayout,
                topLeft = Offset(
                    finishX - finishTextLayout.size.width / 2,
                    finishY - finishTextLayout.size.height / 2
                )
            )

            // Phone icons (small rectangles) at start and finish gates
            val phoneW = 18f
            val phoneH = 28f
            // Phone at start
            drawRoundRect(
                color = Color(0xFFAEAEB2),
                topLeft = Offset(startX - phoneW / 2, startY + 22f),
                size = Size(phoneW, phoneH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
            drawCircle(
                color = AccentBlue,
                radius = 3f,
                center = Offset(startX, startY + 22f + phoneH / 2)
            )
            // Phone at finish
            drawRoundRect(
                color = Color(0xFFAEAEB2),
                topLeft = Offset(finishX - phoneW / 2, finishY - 22f - phoneH),
                size = Size(phoneW, phoneH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
            drawCircle(
                color = AccentBlue,
                radius = 3f,
                center = Offset(finishX, finishY - 22f - phoneH / 2)
            )

            // Animated runner dot along the track oval
            val midRadius = halfW - laneWidth / 2
            val midRadiusY = halfH - laneWidth / 2
            // Runner goes counter-clockwise starting from bottom-right
            val angle = (2 * Math.PI * runnerProgress - Math.PI / 2).toFloat()
            val runnerX = centerX + midRadius * cos(angle)
            val runnerY = centerY + midRadiusY * sin(angle)

            // Runner trail
            for (i in 1..6) {
                val trailProgress = (runnerProgress - i * 0.015f).let {
                    if (it < 0) it + 1f else it
                }
                val trailAngle = (2 * Math.PI * trailProgress - Math.PI / 2).toFloat()
                val trailX = centerX + midRadius * cos(trailAngle)
                val trailY = centerY + midRadiusY * sin(trailAngle)
                drawCircle(
                    color = AccentBlue.copy(alpha = 0.15f * (6 - i)),
                    radius = 5f,
                    center = Offset(trailX, trailY)
                )
            }

            // Runner dot
            drawCircle(
                color = AccentBlue,
                radius = 10f,
                center = Offset(runnerX, runnerY)
            )
            drawCircle(
                color = Color.White,
                radius = 4f,
                center = Offset(runnerX, runnerY)
            )

            // Labels below the track
            val startLabelLayout = textMeasurer.measure(
                startText,
                style = TextStyle(
                    color = GreenMarker,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            )
            drawText(
                startLabelLayout,
                topLeft = Offset(
                    startX - startLabelLayout.size.width / 2,
                    startY + 56f
                )
            )

            val finishLabelLayout = textMeasurer.measure(
                finishText,
                style = TextStyle(
                    color = RedMarker,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            )
            drawText(
                finishLabelLayout,
                topLeft = Offset(
                    finishX - finishLabelLayout.size.width / 2,
                    finishY - 56f - finishLabelLayout.size.height
                )
            )
        }

        Spacer(Modifier.height(16.dp))

        // Info cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.onboarding_preview_one_phone_title),
                subtitle = stringResource(R.string.onboarding_preview_one_phone_subtitle),
                color = AccentBlue
            )
            InfoCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.onboarding_preview_two_phones_title),
                subtitle = stringResource(R.string.onboarding_preview_two_phones_subtitle),
                color = GreenMarker
            )
        }

        Spacer(Modifier.height(24.dp))

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

@Composable
private fun InfoCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = Color(0xFF8E8E93)
            )
        }
    }
}
