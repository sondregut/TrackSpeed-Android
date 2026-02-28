package com.trackspeed.android.ui.screens.onboarding.steps

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val AccentBlue = AccentNavy
private val SpinGreen = AccentGreen

private data class WheelSegment(val labelRes: Int?, val emoji: String?, val color: Color, val isTarget: Boolean = false)

private enum class SpinState { READY, SPINNING, LANDED }

@Composable
fun SpinWheelStep(onContinue: () -> Unit) {
    var spinState by remember { mutableStateOf(SpinState.READY) }
    var canSkip by remember { mutableStateOf(false) }

    // Resolve segment labels
    val segment20Off = stringResource(R.string.onboarding_spin_segment_20_off)
    val segmentNoLuck = stringResource(R.string.onboarding_spin_segment_no_luck)

    val segments = remember(segment20Off, segmentNoLuck) {
        listOf(
            WheelSegment(null, segment20Off, AccentBlue, isTarget = true),
            WheelSegment(null, "\uD83C\uDF81", SurfaceDark),
            WheelSegment(null, segmentNoLuck, SurfaceDark.copy(alpha = 0.8f)),
            WheelSegment(null, "\uD83C\uDF81", SurfaceDark),
            WheelSegment(null, segmentNoLuck, SurfaceDark.copy(alpha = 0.8f)),
            WheelSegment(null, "\uD83C\uDF81", SurfaceDark),
            WheelSegment(null, segmentNoLuck, SurfaceDark.copy(alpha = 0.8f)),
            WheelSegment(null, "\uD83C\uDF81", SurfaceDark),
        )
    }

    // Target rotation: 5-7 full rotations + offset to land on "20%" (segment 0)
    // Segment 0 spans 0-45, center at 22.5. Pointer is at top (270).
    // Final angle = N*360 + (360 - 22.5) = N*360 + 337.5
    val targetRotation = 360f * 6 + 337.5f

    val rotation = remember { Animatable(0f) }

    LaunchedEffect(spinState) {
        if (spinState == SpinState.SPINNING) {
            rotation.animateTo(
                targetValue = targetRotation,
                animationSpec = tween(
                    durationMillis = 4500,
                    easing = CubicBezierEasing(0.12f, 0.8f, 0.2f, 1.0f)
                )
            )
            spinState = SpinState.LANDED
        }
    }

    // Delayed skip button
    LaunchedEffect(Unit) { delay(5000); canSkip = true }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            stringResource(R.string.onboarding_spin_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_spin_subtitle),
            fontSize = 17.sp,
            color = TextSecondary
        )

        Spacer(Modifier.height(24.dp))

        // Wheel with pointer
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.size(260.dp)
        ) {
            val textMeasurer = rememberTextMeasurer()

            Canvas(modifier = Modifier.size(240.dp).align(Alignment.Center)) {
                val sliceAngle = 360f / segments.size
                val cx = size.width / 2
                val cy = size.height / 2
                val radius = size.width / 2

                rotate(rotation.value, pivot = Offset(cx, cy)) {
                    segments.forEachIndexed { index, segment ->
                        drawArc(
                            color = segment.color,
                            startAngle = index * sliceAngle - 90f,
                            sweepAngle = sliceAngle,
                            useCenter = true,
                            size = Size(size.width, size.height)
                        )
                    }
                }
            }

            // Pointer triangle at top
            Canvas(modifier = Modifier.size(24.dp).offset(y = (-4).dp)) {
                val path = Path().apply {
                    moveTo(size.width / 2, size.height)
                    lineTo(0f, 0f)
                    lineTo(size.width, 0f)
                    close()
                }
                drawPath(path, TextPrimary)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Result area
        when (spinState) {
            SpinState.LANDED -> {
                Text(
                    stringResource(R.string.onboarding_spin_won_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen
                )
                Spacer(Modifier.height(12.dp))

                // Pricing with strikethrough
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.onboarding_spin_original_price),
                                fontSize = 16.sp,
                                color = TextSecondary,
                                style = LocalTextStyle.current.copy(
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                )
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.onboarding_spin_discount_price),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentGreen
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.onboarding_spin_monthly_price),
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
            else -> {
                Spacer(Modifier.height(60.dp))
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                when (spinState) {
                    SpinState.READY -> spinState = SpinState.SPINNING
                    SpinState.SPINNING -> {} // no-op
                    SpinState.LANDED -> onContinue()
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (spinState == SpinState.LANDED) AccentGreen else AccentBlue
            ),
            enabled = spinState != SpinState.SPINNING
        ) {
            Text(
                when (spinState) {
                    SpinState.READY -> stringResource(R.string.onboarding_spin_button_ready)
                    SpinState.SPINNING -> stringResource(R.string.onboarding_spin_button_spinning)
                    SpinState.LANDED -> stringResource(R.string.onboarding_spin_button_landed)
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (spinState == SpinState.LANDED) Color.Black else TextPrimary
            )
        }

        if (canSkip && spinState != SpinState.LANDED) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.common_skip), color = TextSecondary, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
