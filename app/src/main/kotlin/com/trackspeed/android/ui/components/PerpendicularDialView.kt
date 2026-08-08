package com.trackspeed.android.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.ui.theme.AccentBlue
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary
import com.trackspeed.android.ui.theme.TextTertiary
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.trackspeed.android.R

private val DialGreen = Color(0xFF30D158)
private val DialRed = Color(0xFFFF453A)
private val DialOrange = Color(0xFFFF9F0A)
private const val STABLE_HOLD_MS = 750L

/**
 * Visual dial showing rotation angle relative to the track direction.
 *
 * Ported from iOS `PerpendicularDialView.swift`. The user first points the
 * camera down the track and taps "Set Direction", then rotates the device until
 * the dial reaches 90 degrees, meaning the camera gate is perpendicular.
 */
@Composable
fun PerpendicularDialView(
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    passThreshold: Double = 5.0,
    acceptableThreshold: Double = 10.0
) {
    val context = LocalContext.current
    var currentYaw by remember { mutableFloatStateOf(0f) }
    var referenceYaw by rememberSaveable { mutableStateOf<Float?>(null) }
    var hasRotationSensor by remember { mutableStateOf(true) }
    var stableSinceMs by remember { mutableLongStateOf(0L) }
    var isStable by remember { mutableStateOf(false) }

    DisposableEffect(context, referenceYaw) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        hasRotationSensor = rotationVector != null

        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)

                val yaw = Math.toDegrees(orientation[0].toDouble())
                    .toFloat()
                    .normalizedDegrees()
                currentYaw = yaw

                val reference = referenceYaw
                val confidence = perpendicularConfidence(
                    currentYaw = yaw.toDouble(),
                    referenceYaw = reference?.toDouble(),
                    passThreshold = passThreshold,
                    acceptableThreshold = acceptableThreshold
                )
                if (reference != null && confidence == LevelConfidence.PASS) {
                    val now = SystemClock.elapsedRealtime()
                    if (stableSinceMs == 0L) stableSinceMs = now
                    isStable = now - stableSinceMs >= STABLE_HOLD_MS
                } else {
                    stableSinceMs = 0L
                    isStable = false
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (rotationVector != null) {
            sensorManager.registerListener(listener, rotationVector, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    PerpendicularDialContent(
        currentYaw = currentYaw.toDouble(),
        referenceYaw = referenceYaw?.toDouble(),
        isStable = isStable,
        hasRotationSensor = hasRotationSensor,
        size = size,
        passThreshold = passThreshold,
        acceptableThreshold = acceptableThreshold,
        onSetDirection = {
            referenceYaw = currentYaw
            stableSinceMs = 0L
            isStable = false
        },
        onResetDirection = {
            referenceYaw = null
            stableSinceMs = 0L
            isStable = false
        },
        modifier = modifier
    )
}

@Composable
fun PerpendicularDialContent(
    currentYaw: Double,
    referenceYaw: Double?,
    isStable: Boolean,
    hasRotationSensor: Boolean,
    onSetDirection: () -> Unit,
    onResetDirection: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    passThreshold: Double = 5.0,
    acceptableThreshold: Double = 10.0
) {
    val currentAngle = perpendicularAngle(currentYaw, referenceYaw)
    val errorFromTarget = abs(currentAngle - 90.0)
    val confidence = perpendicularConfidence(currentYaw, referenceYaw, passThreshold, acceptableThreshold)
    val borderColor = when (confidence) {
        LevelConfidence.PASS -> DialGreen
        LevelConfidence.ACCEPTABLE -> DialOrange
        LevelConfidence.NOT_READY -> TextTertiary
    }
    val needleColor = when (confidence) {
        LevelConfidence.PASS -> DialGreen
        LevelConfidence.ACCEPTABLE -> DialOrange
        LevelConfidence.NOT_READY -> DialRed
    }
    val statusColor = when (confidence) {
        LevelConfidence.PASS -> DialGreen
        LevelConfidence.ACCEPTABLE -> DialOrange
        LevelConfidence.NOT_READY -> TextSecondary
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!hasRotationSensor) {
            Text(
                text = "Rotation sensor unavailable",
                style = MaterialTheme.typography.bodyMedium,
                color = DialOrange,
                textAlign = TextAlign.Center
            )
        }

        if (referenceYaw == null) {
            SetDirectionPrompt(size = size, onSetDirection = onSetDirection)
        } else {
            DialCanvas(
                currentAngle = currentAngle,
                isStable = isStable,
                borderColor = borderColor,
                needleColor = needleColor,
                size = size,
                passThreshold = passThreshold,
                acceptableThreshold = acceptableThreshold
            )

            Text(
                text = when {
                    isStable -> "Perpendicular - Locked!"
                    confidence == LevelConfidence.PASS -> "Perpendicular - Hold steady..."
                    confidence == LevelConfidence.ACCEPTABLE -> "Almost perpendicular (${formatAngle(errorFromTarget)} off)"
                    currentAngle < 90.0 -> "Rotate ${formatAngle(errorFromTarget)} more clockwise"
                    else -> "Rotate ${formatAngle(errorFromTarget)} more counter-clockwise"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                textAlign = TextAlign.Center
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatAngle(currentAngle)} from track",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                TextButton(onClick = onResetDirection) {
                    Text(stringResource(R.string.debug_reset))
                }
            }
        }
    }
}

@Composable
private fun SetDirectionPrompt(
    size: Dp,
    onSetDirection: () -> Unit
) {
    val tertiary = TextTertiary
    val accent = AccentBlue

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val radius = this.size.minDimension / 2f

            drawCircle(
                color = tertiary.copy(alpha = 0.35f),
                radius = radius,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )

            drawLine(
                color = accent,
                start = Offset(center.x, center.y + radius * 0.36f),
                end = Offset(center.x, center.y - radius * 0.45f),
                strokeWidth = 8.dp.toPx(),
                cap = StrokeCap.Round
            )
            val arrow = Path().apply {
                moveTo(center.x, center.y - radius * 0.58f)
                lineTo(center.x - radius * 0.16f, center.y - radius * 0.34f)
                lineTo(center.x + radius * 0.16f, center.y - radius * 0.34f)
                close()
            }
            drawPath(arrow, accent)
        }

        Text(
            text = "Point camera down the track",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Then set this as the track direction.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Button(onClick = onSetDirection) {
            Text("Set Direction")
        }
    }
}

@Composable
private fun DialCanvas(
    currentAngle: Double,
    isStable: Boolean,
    borderColor: Color,
    needleColor: Color,
    size: Dp,
    passThreshold: Double,
    acceptableThreshold: Double
) {
    val tertiary = TextTertiary
    val animatedAngle by animateFloatAsState(
        targetValue = currentAngle.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "perpendicularAngle"
    )

    Canvas(modifier = Modifier.size(size + 16.dp)) {
        val dialSize = size.toPx()
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = dialSize / 2f
        val arcRadius = radius - 15.dp.toPx()
        val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
        val arcSize = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f)

        drawCircle(
            color = borderColor,
            radius = radius,
            center = center,
            style = Stroke(width = 4.dp.toPx())
        )

        drawArc(
            color = DialGreen.copy(alpha = 0.30f),
            startAngle = (90.0 - passThreshold - 180.0).toFloat(),
            sweepAngle = (passThreshold * 2.0).toFloat(),
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 20.dp.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            color = DialOrange.copy(alpha = 0.22f),
            startAngle = (90.0 - acceptableThreshold - 180.0).toFloat(),
            sweepAngle = (acceptableThreshold - passThreshold).toFloat(),
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 20.dp.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            color = DialOrange.copy(alpha = 0.22f),
            startAngle = (90.0 + passThreshold - 180.0).toFloat(),
            sweepAngle = (acceptableThreshold - passThreshold).toFloat(),
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 20.dp.toPx(), cap = StrokeCap.Round)
        )

        listOf(0.0, 45.0, 90.0, 135.0, 180.0).forEach { angle ->
            val rad = Math.toRadians(angle - 180.0)
            val unit = Offset(cos(rad).toFloat(), sin(rad).toFloat())
            val isTarget = angle == 90.0
            val tickOuter = center + unit * (radius - 18.dp.toPx())
            val tickInner = center + unit * (radius - if (isTarget) 40.dp.toPx() else 32.dp.toPx())
            drawLine(
                color = if (isTarget) DialGreen else tertiary,
                start = tickInner,
                end = tickOuter,
                strokeWidth = if (isTarget) 3.dp.toPx() else 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        val needleRad = Math.toRadians(animatedAngle.toDouble() - 180.0)
        val unit = Offset(cos(needleRad).toFloat(), sin(needleRad).toFloat())
        val perp = Offset(-unit.y, unit.x)
        val tip = center + unit * (radius - 34.dp.toPx())
        val base = center - unit * 18.dp.toPx()
        val needlePath = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(base.x + perp.x * 7.dp.toPx(), base.y + perp.y * 7.dp.toPx())
            lineTo(base.x - perp.x * 7.dp.toPx(), base.y - perp.y * 7.dp.toPx())
            close()
        }
        drawPath(needlePath, needleColor)

        drawCircle(color = Color.Black.copy(alpha = 0.85f), radius = 21.dp.toPx(), center = center)
        drawCircle(color = borderColor, radius = 21.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))

        if (isStable) {
            drawCircle(
                color = DialGreen,
                radius = radius + 5.dp.toPx(),
                center = center,
                style = Stroke(width = 4.dp.toPx())
            )
        }
    }

    Text(
        text = formatAngle(currentAngle),
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum"
        ),
        color = TextPrimary
    )
}

private fun perpendicularConfidence(
    currentYaw: Double,
    referenceYaw: Double?,
    passThreshold: Double,
    acceptableThreshold: Double
): LevelConfidence {
    if (referenceYaw == null) return LevelConfidence.NOT_READY
    val error = abs(perpendicularAngle(currentYaw, referenceYaw) - 90.0)
    return when {
        error <= passThreshold -> LevelConfidence.PASS
        error <= acceptableThreshold -> LevelConfidence.ACCEPTABLE
        else -> LevelConfidence.NOT_READY
    }
}

private fun perpendicularAngle(currentYaw: Double, referenceYaw: Double?): Double {
    if (referenceYaw == null) return 0.0
    var delta = currentYaw - referenceYaw
    while (delta < 0.0) delta += 360.0
    while (delta >= 360.0) delta -= 360.0
    if (delta > 180.0) delta = 360.0 - delta
    return delta
}

private fun Float.normalizedDegrees(): Float {
    var value = this
    while (value < 0f) value += 360f
    while (value >= 360f) value -= 360f
    return value
}

private fun formatAngle(value: Double): String {
    return "%.0f\u00B0".format(value)
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PerpendicularDialPromptPreview() {
    TrackSpeedTheme {
        PerpendicularDialContent(
            currentYaw = 0.0,
            referenceYaw = null,
            isStable = false,
            hasRotationSensor = true,
            onSetDirection = {},
            onResetDirection = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PerpendicularDialReadyPreview() {
    TrackSpeedTheme {
        PerpendicularDialContent(
            currentYaw = 90.0,
            referenceYaw = 0.0,
            isStable = true,
            hasRotationSensor = true,
            onSetDirection = {},
            onResetDirection = {}
        )
    }
}
