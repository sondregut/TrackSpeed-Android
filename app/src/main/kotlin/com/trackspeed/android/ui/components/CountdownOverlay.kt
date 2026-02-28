package com.trackspeed.android.ui.components

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import kotlinx.coroutines.delay
import kotlin.random.Random

// App accent colors as specified
private val AccentBlue = Color(0xFF0A84FF)
private val AccentGreen = Color(0xFF30D158)
private val OverlayBackground = Color(0xF2000000)  // ~95% opacity black
private val CountdownBlue = Color(0xB30A84FF)       // Blue at 70%
private val CountdownAmber = Color(0xD9FF9500)       // Amber at 85%
private val CountdownGo = Color(0xFF30D158)          // Solid green

/**
 * Full-screen semi-transparent overlay that shows an animated countdown
 * (3, 2, 1, GO!) with scale + fade animations and a beep on GO.
 *
 * Matches the iOS CountdownStartOverlay behavior: pre-start delay with
 * "GET READY...", then number countdown, then green "GO!" with pulse animation.
 *
 * @param countdownFrom Number of seconds to count from (3 or 5).
 * @param preStartDelayMin Minimum pre-start delay in seconds.
 * @param preStartDelayMax Maximum pre-start delay in seconds.
 * @param onCountdownComplete Called with the precise monotonic timestamp (nanos) when GO fires.
 * @param onCancel Called when the user dismisses the overlay.
 */
@Composable
fun CountdownOverlay(
    countdownFrom: Int = 3,
    preStartDelayMin: Double = 3.0,
    preStartDelayMax: Double = 5.0,
    onCountdownComplete: (Long) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    // 0 = idle, 99 = getting ready, 3/2/1 = counting, -1 = GO!
    var countdownValue by remember { mutableIntStateOf(0) }
    var isRunning by remember { mutableStateOf(false) }

    // Tone generator for beep on GO
    val toneGenerator = remember {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
        } catch (e: Exception) {
            null
        }
    }

    // Vibrator for haptic
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

    // Scale animation for numbers
    val scale = remember { Animatable(1f) }

    // Cleanup tone generator
    DisposableEffect(Unit) {
        onDispose {
            toneGenerator?.release()
        }
    }

    // Animate scale on each countdown change
    LaunchedEffect(countdownValue) {
        if (countdownValue in 1..countdownFrom) {
            // Pop in from 1.3x and settle to 1.0x
            scale.snapTo(1.3f)
            scale.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    // Countdown logic
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect

        // Show "GET READY" with random pre-start delay
        countdownValue = 99
        val preDelay = preStartDelayMin + Random.nextDouble() * (preStartDelayMax - preStartDelayMin)
        delay((preDelay * 1000).toLong())

        // Count down from countdownFrom to 1
        for (i in countdownFrom downTo 1) {
            countdownValue = i

            // Light haptic tick for each number
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(30, 80))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(30)
                }
            }

            delay(1000L)
        }

        // GO! - capture precise timestamp BEFORE playing sound
        val startTimestamp = SystemClock.elapsedRealtimeNanos()

        countdownValue = -1

        // Strong haptic
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(80)
            }
        }

        // Play beep
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)

        Log.i("CountdownOverlay", "Countdown complete! Timer started at $startTimestamp")
        onCountdownComplete(startTimestamp)
    }

    // Background color based on state
    val backgroundColor = when (countdownValue) {
        0 -> OverlayBackground
        99 -> OverlayBackground
        3 -> CountdownBlue
        2 -> CountdownAmber
        1 -> CountdownAmber.copy(alpha = 0.9f)
        -1 -> CountdownGo
        else -> if (countdownValue > 3) CountdownBlue else OverlayBackground
    }

    val animatedBg by animateFloatAsState(
        targetValue = if (countdownValue == -1) 1f else 0f,
        animationSpec = tween(150),
        label = "bg_anim"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // Cancel button (top-left) - only before starting
        if (!isRunning) {
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
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            text = stringResource(R.string.common_back),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Main content
        AnimatedContent(
            targetState = countdownValue,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(
                    initialScale = 0.8f,
                    animationSpec = tween(200)
                )) togetherWith (fadeOut(tween(150)) + scaleOut(
                    targetScale = 1.2f,
                    animationSpec = tween(150)
                ))
            },
            label = "countdown_content"
        ) { value ->
            when (value) {
                0 -> {
                    // Idle state - show description and start button
                    IdleContent(
                        onStart = { isRunning = true }
                    )
                }
                99 -> {
                    // Getting ready
                    GetReadyContent()
                }
                -1 -> {
                    // GO!
                    GoContent()
                }
                else -> {
                    // Number countdown
                    NumberContent(
                        number = value,
                        scale = scale.value
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleContent(onStart: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Timer,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.countdown_title),
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.countdown_description),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBlue.copy(alpha = 0.3f),
                contentColor = Color.White
            ),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "  " + stringResource(R.string.countdown_start_button),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun GetReadyContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pulsing icon placeholder
        Text(
            text = stringResource(R.string.countdown_get_ready),
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.countdown_set_phone_down),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NumberContent(number: Int, scale: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "$number",
            color = Color.White,
            fontSize = 200.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.scale(scale)
        )
    }
}

@Composable
private fun GoContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
            contentDescription = stringResource(R.string.countdown_go_cd),
            tint = Color.White,
            modifier = Modifier.size(140.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.countdown_go),
            color = Color.White,
            fontSize = 96.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Default,
            letterSpacing = 8.sp
        )
    }
}
