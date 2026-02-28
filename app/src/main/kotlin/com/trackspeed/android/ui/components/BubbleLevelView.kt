package com.trackspeed.android.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import kotlin.math.abs
import kotlin.math.sqrt

private val AccentGreen = Color(0xFF30D158)
private val AccentRed = Color(0xFFFF453A)
private val AccentOrange = Color(0xFFFF9F0A)
private val TextSecondary = Color(0xFF8E8E93)
private val TextMuted = Color(0xFF636366)

/**
 * Confidence level for the bubble level, matching iOS SetupConfidence enum.
 */
enum class LevelConfidence {
    PASS,        // Within 4 degrees
    ACCEPTABLE,  // Within 8 degrees
    NOT_READY    // Beyond 8 degrees
}

/**
 * Visual bubble level indicator showing if the phone is level.
 *
 * Uses accelerometer sensor data to display a "bubble" that moves based on device tilt.
 * The bubble moves opposite to the tilt direction (like a real spirit level).
 *
 * Ported from iOS BubbleLevelView.swift.
 *
 * @param size Diameter of the level circle in dp
 * @param passThreshold Degrees within which the level is considered "pass"
 * @param acceptableThreshold Degrees within which the level is "acceptable"
 */
@Composable
fun BubbleLevelView(
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    passThreshold: Double = 4.0,
    acceptableThreshold: Double = 8.0
) {
    val context = LocalContext.current

    // Sensor state
    var roll by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }

    // Register sensor listener
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]

                    // Calculate roll and pitch in degrees
                    // Roll = rotation around Y axis (left/right tilt)
                    // Pitch = rotation around X axis (forward/back tilt)
                    val g = sqrt(x * x + y * y + z * z)
                    if (g > 0.1f) {
                        roll = Math.toDegrees(Math.asin((x / g).toDouble().coerceIn(-1.0, 1.0))).toFloat()
                        pitch = Math.toDegrees(Math.asin((y / g).toDouble().coerceIn(-1.0, 1.0))).toFloat() - 90f
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accelerometer?.let {
            sensorManager.registerListener(
                listener,
                it,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    BubbleLevelContent(
        roll = roll.toDouble(),
        pitch = pitch.toDouble(),
        size = size,
        passThreshold = passThreshold,
        acceptableThreshold = acceptableThreshold,
        modifier = modifier
    )
}

/**
 * Stateless bubble level content for previews and direct state control.
 */
@Composable
fun BubbleLevelContent(
    roll: Double,
    pitch: Double,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    passThreshold: Double = 4.0,
    acceptableThreshold: Double = 8.0
) {
    val confidence = when {
        abs(roll) < passThreshold && abs(pitch) < passThreshold -> LevelConfidence.PASS
        abs(roll) < acceptableThreshold && abs(pitch) < acceptableThreshold -> LevelConfidence.ACCEPTABLE
        else -> LevelConfidence.NOT_READY
    }

    val isStable = confidence == LevelConfidence.PASS

    val borderColor = when (confidence) {
        LevelConfidence.PASS -> AccentGreen
        LevelConfidence.ACCEPTABLE -> AccentOrange
        LevelConfidence.NOT_READY -> TextMuted
    }

    val bubbleColor = when (confidence) {
        LevelConfidence.PASS -> AccentGreen
        LevelConfidence.ACCEPTABLE -> AccentOrange
        LevelConfidence.NOT_READY -> Color(0xFF0A84FF)
    }

    // Animated bubble position
    val maxOffset = size.value * 0.29f // ~70/240 ratio from iOS
    val clampedRoll = roll.coerceIn(-15.0, 15.0)
    val clampedPitch = pitch.coerceIn(-15.0, 15.0)

    // Bubble moves opposite to tilt (like a real bubble)
    val targetOffsetX = (-clampedRoll / 15.0 * maxOffset).toFloat()
    val targetOffsetY = (clampedPitch / 15.0 * maxOffset).toFloat()

    val animatedOffsetX by animateFloatAsState(
        targetValue = targetOffsetX,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bubbleX"
    )
    val animatedOffsetY by animateFloatAsState(
        targetValue = targetOffsetY,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bubbleY"
    )

    val passZoneFraction = (passThreshold / 15.0).toFloat()
    val acceptableZoneFraction = (acceptableThreshold / 15.0).toFloat()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Bubble level canvas
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size + 16.dp) // Extra space for stable indicator
        ) {
            Canvas(modifier = Modifier.size(size)) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val radius = this.size.width / 2f

                // Outer circle (level boundary)
                drawCircle(
                    color = borderColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )

                // Pass zone indicator (inner circle)
                drawCircle(
                    color = AccentGreen.copy(alpha = 0.3f),
                    radius = radius * passZoneFraction * 2f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Acceptable zone indicator
                drawCircle(
                    color = AccentOrange.copy(alpha = 0.2f),
                    radius = radius * acceptableZoneFraction * 2f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // Crosshairs
                val crosshairOffset = 10.dp.toPx()
                drawLine(
                    color = TextMuted.copy(alpha = 0.3f),
                    start = Offset(crosshairOffset, center.y),
                    end = Offset(this.size.width - crosshairOffset, center.y),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = TextMuted.copy(alpha = 0.3f),
                    start = Offset(center.x, crosshairOffset),
                    end = Offset(center.x, this.size.height - crosshairOffset),
                    strokeWidth = 1.dp.toPx()
                )

                // Center target dot
                drawCircle(
                    color = TextMuted.copy(alpha = 0.5f),
                    radius = 4.dp.toPx(),
                    center = center
                )

                // Bubble
                val bubbleRadius = 20.dp.toPx()
                val bubbleCenter = Offset(
                    center.x + animatedOffsetX.dp.toPx(),
                    center.y + animatedOffsetY.dp.toPx()
                )
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            bubbleColor.copy(alpha = 0.8f),
                            bubbleColor
                        ),
                        startY = bubbleCenter.y - bubbleRadius,
                        endY = bubbleCenter.y + bubbleRadius
                    ),
                    radius = bubbleRadius,
                    center = bubbleCenter
                )

                // Stable indicator ring
                if (isStable) {
                    drawCircle(
                        color = AccentGreen,
                        radius = radius + 5.dp.toPx(),
                        center = center,
                        style = Stroke(width = 4.dp.toPx())
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Angle readout
        AngleReadout(
            label = stringResource(R.string.bubble_level_tilt),
            value = roll,
            threshold = passThreshold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Status text
        val statusMessage = when {
            isStable -> stringResource(R.string.bubble_level_locked)
            confidence == LevelConfidence.PASS -> stringResource(R.string.bubble_level_hold_steady)
            confidence == LevelConfidence.ACCEPTABLE -> stringResource(R.string.bubble_level_almost)
            else -> stringResource(R.string.bubble_level_tilt_device)
        }

        val statusColor = when (confidence) {
            LevelConfidence.PASS -> AccentGreen
            LevelConfidence.ACCEPTABLE -> AccentOrange
            LevelConfidence.NOT_READY -> TextSecondary
        }

        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor
        )
    }
}

@Composable
private fun AngleReadout(
    label: String,
    value: Double,
    threshold: Double
) {
    val isPassing = abs(value) < threshold
    val checkColor = if (isPassing) AccentGreen else AccentRed

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "%.1f\u00B0".format(value),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum"
                ),
                color = Color.White
            )
            Canvas(modifier = Modifier.size(12.dp)) {
                drawCircle(
                    color = checkColor,
                    radius = this.size.width / 2f
                )
                if (isPassing) {
                    // Draw check mark
                    drawLine(
                        color = Color.White,
                        start = Offset(this.size.width * 0.25f, this.size.height * 0.5f),
                        end = Offset(this.size.width * 0.45f, this.size.height * 0.7f),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(this.size.width * 0.45f, this.size.height * 0.7f),
                        end = Offset(this.size.width * 0.75f, this.size.height * 0.3f),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                } else {
                    // Draw X mark
                    drawLine(
                        color = Color.White,
                        start = Offset(this.size.width * 0.3f, this.size.height * 0.3f),
                        end = Offset(this.size.width * 0.7f, this.size.height * 0.7f),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(this.size.width * 0.7f, this.size.height * 0.3f),
                        end = Offset(this.size.width * 0.3f, this.size.height * 0.7f),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BubbleLevelPassPreview() {
    TrackSpeedTheme(darkTheme = true) {
        BubbleLevelContent(
            roll = 0.3,
            pitch = 0.5,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BubbleLevelAcceptablePreview() {
    TrackSpeedTheme(darkTheme = true) {
        BubbleLevelContent(
            roll = 5.2,
            pitch = 1.5,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BubbleLevelNotReadyPreview() {
    TrackSpeedTheme(darkTheme = true) {
        BubbleLevelContent(
            roll = 12.0,
            pitch = -5.0,
            modifier = Modifier.padding(24.dp)
        )
    }
}
