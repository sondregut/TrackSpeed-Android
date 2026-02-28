package com.trackspeed.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.audio.VoiceStartPhase
import com.trackspeed.android.audio.VoiceStartService
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

// Accent colors
private val AccentBlue = Color(0xFF0A84FF)
private val AccentGreen = Color(0xFF30D158)
private val OverlayBackground = Color(0xF2000000)
private val PhaseBlue = Color(0xD90A84FF)
private val PhaseAmber = Color(0xE5FF9500)
private val PhaseGo = Color(0xFF30D158)

/**
 * Full-screen overlay showing voice commands as they are spoken during the
 * "On your marks... Set... GO!" sequence.
 *
 * Displays animated text synchronized with the VoiceStartService phases, along
 * with a sound-wave visualization during speech. Matches the iOS VoiceStartOverlay.
 *
 * @param voiceStartService The injected VoiceStartService singleton.
 * @param onStart Called with the precise monotonic timestamp (nanos) when GO fires.
 * @param onCancel Called when the user cancels the sequence.
 */
@Composable
fun VoiceStartOverlay(
    voiceStartService: VoiceStartService,
    onStart: (Long) -> Unit,
    onCancel: () -> Unit
) {
    val phase by voiceStartService.phase.collectAsState()
    val isRunning by voiceStartService.isRunning.collectAsState()
    val scope = rememberCoroutineScope()

    // Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "voice_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice_pulse_scale"
    )

    // Set up callbacks
    DisposableEffect(voiceStartService) {
        voiceStartService.onStart = { timestamp ->
            onStart(timestamp)
        }
        voiceStartService.onCancel = {
            // Handled by cancel button
        }
        onDispose {
            voiceStartService.reset()
        }
    }

    // Background color based on phase
    val backgroundColor = when (phase) {
        VoiceStartPhase.IDLE, VoiceStartPhase.PRELOADING -> OverlayBackground
        VoiceStartPhase.PRE_START -> OverlayBackground
        VoiceStartPhase.ON_YOUR_MARKS, VoiceStartPhase.WAITING_FOR_SET -> PhaseBlue
        VoiceStartPhase.SET, VoiceStartPhase.WAITING_FOR_GO -> PhaseAmber
        VoiceStartPhase.GO, VoiceStartPhase.STARTED -> PhaseGo
        VoiceStartPhase.CANCELLED -> Color(0xCC666666)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // Top bar with back button
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 8.dp),
            contentAlignment = Alignment.TopStart
        ) {
            IconButton(onClick = {
                voiceStartService.cancel()
                onCancel()
            }) {
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

        // Main phase content
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                fadeIn(tween(250)) togetherWith fadeOut(tween(200))
            },
            label = "voice_phase_content"
        ) { currentPhase ->
            when (currentPhase) {
                VoiceStartPhase.IDLE -> VoiceIdleContent(
                    pulseScale = pulseScale,
                    onBegin = {
                        scope.launch {
                            voiceStartService.speakCountdown { timestamp ->
                                onStart(timestamp)
                            }
                        }
                    }
                )
                VoiceStartPhase.PRELOADING -> VoicePreStartContent()
                VoiceStartPhase.PRE_START -> VoicePreStartContent()
                VoiceStartPhase.ON_YOUR_MARKS, VoiceStartPhase.WAITING_FOR_SET ->
                    VoiceCommandContent(
                        text = stringResource(R.string.voice_overlay_on_your_marks),
                        subtext = stringResource(R.string.voice_overlay_get_into_position),
                        isSpeaking = currentPhase == VoiceStartPhase.ON_YOUR_MARKS
                    )
                VoiceStartPhase.SET, VoiceStartPhase.WAITING_FOR_GO ->
                    VoiceSetContent(
                        pulseScale = pulseScale,
                        isWaiting = currentPhase == VoiceStartPhase.WAITING_FOR_GO
                    )
                VoiceStartPhase.GO, VoiceStartPhase.STARTED -> VoiceGoContent()
                VoiceStartPhase.CANCELLED -> VoiceCancelledContent()
            }
        }
    }
}

@Composable
private fun VoiceIdleContent(pulseScale: Float, onBegin: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .size(80.dp)
                .scale(pulseScale)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.voice_overlay_title),
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.voice_overlay_description),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onBegin,
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBlue.copy(alpha = 0.3f),
                contentColor = Color.White
            ),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "  " + stringResource(R.string.voice_overlay_begin),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun VoicePreStartContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.voice_overlay_get_ready),
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.voice_overlay_set_phone_down),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun VoiceCommandContent(
    text: String,
    subtext: String?,
    isSpeaking: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            textAlign = TextAlign.Center,
            lineHeight = 56.sp
        )

        if (subtext != null) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = subtext,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 18.sp
            )
        }

        // Sound wave visualization during speech
        if (isSpeaking) {
            Spacer(modifier = Modifier.height(32.dp))
            SoundWaveVisualization()
        }
    }
}

@Composable
private fun VoiceSetContent(pulseScale: Float, isWaiting: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pulsing ring to build tension
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(pulseScale)
                .drawBehind {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.3f),
                        radius = size.minDimension / 2,
                        style = Stroke(width = 4.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.2f),
                        radius = size.minDimension / 2 * 0.8f
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(80.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.voice_overlay_set),
            color = Color.White,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 8.sp
        )

        if (isWaiting) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.voice_overlay_hold_position),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp
            )
        }
    }
}

@Composable
private fun VoiceGoContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
            contentDescription = stringResource(R.string.voice_overlay_go_cd),
            tint = Color.White,
            modifier = Modifier.size(140.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.voice_overlay_go),
            color = Color.White,
            fontSize = 96.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 8.sp
        )
    }
}

@Composable
private fun VoiceCancelledContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.voice_overlay_cancelled),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Animated sound-wave visualization displayed during TTS speech.
 * Shows oscillating bars that simulate a waveform.
 */
@Composable
private fun SoundWaveVisualization(
    barCount: Int = 20,
    modifier: Modifier = Modifier
        .fillMaxWidth(0.6f)
        .height(40.dp)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sound_wave")

    // Create animated phases for the wave bars
    val phases = (0 until barCount).map { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 300 + (index * 50 % 200),
                    delayMillis = index * 30 % 150
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }

    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2f)
        val maxHeight = size.height

        for (i in 0 until barCount) {
            val barHeight = maxHeight * phases[i].value
            val x = (i * 2f + 0.5f) * barWidth

            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = Offset(x, (maxHeight - barHeight) / 2),
                end = Offset(x, (maxHeight + barHeight) / 2),
                strokeWidth = barWidth * 0.7f,
                cap = StrokeCap.Round
            )
        }
    }
}
