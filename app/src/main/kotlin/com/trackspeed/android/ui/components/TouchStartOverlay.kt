package com.trackspeed.android.ui.components

import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R

// App accent colors
private val AccentGreen = Color(0xFF30D158)
private val OverlayBackground = Color(0xF2000000)
private val HoldingAmber = Color(0xD9FF9500)

/**
 * Touch state for the overlay.
 */
private enum class TouchState {
    WAITING,   // Dark screen - "Touch anywhere"
    HOLDING,   // Amber/orange - finger is down
    RELEASED   // Green flash - timer started
}

/**
 * Full-screen overlay implementing a touch-to-start timing mode.
 *
 * The athlete touches the screen, holds their finger down (amber state with
 * pulsing ring and "LIFT TO START"), then lifts their finger to trigger the
 * timer start with a precise monotonic timestamp.
 *
 * Matches the iOS TouchStartOverlay behavior: waiting -> holding -> released
 * with haptic feedback at each transition.
 *
 * @param onStart Called with the precise monotonic timestamp (nanos) when the finger is lifted.
 * @param onCancel Called when the user cancels (back button in waiting state).
 */
@Composable
fun TouchStartOverlay(
    onStart: (Long) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(TouchState.WAITING) }

    // Vibrator for haptic feedback
    val vibrator = remember {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }

    // Pulsing animation for waiting and holding states
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Background color based on state
    val backgroundColor = when (state) {
        TouchState.WAITING -> OverlayBackground
        TouchState.HOLDING -> HoldingAmber
        TouchState.RELEASED -> AccentGreen
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .pointerInput(state) {
                if (state == TouchState.RELEASED) return@pointerInput

                detectTapGestures(
                    onPress = {
                        if (state == TouchState.WAITING) {
                            // Touch down -> holding
                            state = TouchState.HOLDING
                            Log.i("TouchStartOverlay", "Touch down - transitioning to HOLDING")

                            // Heavy haptic on touch down
                            vibrator?.let {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    it.vibrate(
                                        VibrationEffect.createOneShot(
                                            60,
                                            VibrationEffect.DEFAULT_AMPLITUDE
                                        )
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    it.vibrate(60)
                                }
                            }

                            // Wait for finger lift
                            val released = tryAwaitRelease()

                            if (released && state == TouchState.HOLDING) {
                                // Finger lifted -> capture precise timestamp
                                val timestamp = SystemClock.elapsedRealtimeNanos()
                                state = TouchState.RELEASED
                                Log.i("TouchStartOverlay", "Touch released - STARTING TIMER at $timestamp")

                                // Strong haptic on release
                                vibrator?.let {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        it.vibrate(
                                            VibrationEffect.createOneShot(
                                                80,
                                                VibrationEffect.DEFAULT_AMPLITUDE
                                            )
                                        )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        it.vibrate(80)
                                    }
                                }

                                onStart(timestamp)
                            } else if (state == TouchState.HOLDING) {
                                // Touch cancelled (system gesture, etc.)
                                state = TouchState.WAITING
                                Log.i("TouchStartOverlay", "Touch cancelled - resetting to WAITING")
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Cancel button (top-left) - only in waiting state
        if (state == TouchState.WAITING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp, start = 8.dp),
                contentAlignment = Alignment.TopStart
            ) {
                IconButton(onClick = onCancel) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = Color.White.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.common_back),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Main visual content
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(150))
            },
            label = "touch_state_content"
        ) { currentState ->
            when (currentState) {
                TouchState.WAITING -> WaitingContent(pulseScale = pulseScale)
                TouchState.HOLDING -> HoldingContent(pulseScale = pulseScale)
                TouchState.RELEASED -> ReleasedContent()
            }
        }
    }
}

@Composable
private fun WaitingContent(pulseScale: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.TouchApp,
            contentDescription = stringResource(R.string.touch_start_touch_cd),
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .size(100.dp)
                .scale(pulseScale)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.touch_start_touch_anywhere),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.touch_start_hold_then_lift),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp
        )
    }
}

@Composable
private fun HoldingContent(pulseScale: Float) {
    val ringColor = Color.White.copy(alpha = 0.3f)
    val innerColor = Color.White.copy(alpha = 0.2f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pulsing ring
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(pulseScale)
                .drawBehind {
                    // Outer ring
                    drawCircle(
                        color = ringColor,
                        radius = size.minDimension / 2,
                        style = Stroke(width = 4.dp.toPx())
                    )
                    // Inner filled circle
                    drawCircle(
                        color = innerColor,
                        radius = size.minDimension / 2 * 0.78f
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Up arrow indicating "lift"
            Text(
                text = "\u2191",  // up arrow
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.touch_start_holding),
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.touch_start_lift_to_start),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(
                    Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 32.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun ReleasedContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
            contentDescription = stringResource(R.string.touch_start_go_cd),
            tint = Color.White,
            modifier = Modifier.size(120.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.touch_start_go),
            color = Color.White,
            fontSize = 96.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 8.sp
        )
    }
}
