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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.detection.PhotoFinishDetector
import kotlinx.coroutines.flow.StateFlow

private val AccentGreen = Color(0xFF30D158)
private val OverlayBackground = Color(0xF2000000)
private val DetectedAmber = Color(0xD9FF9500)

/** Consecutive frames without a valid blob needed to trigger start after detection. */
private const val FRAMES_WITHOUT_BLOB_TO_START = 3

/**
 * Internal state machine for the in-frame start mode.
 */
private enum class InFramePhase {
    WAITING_FOR_ATHLETE,  // "Stand in front of the camera"
    ATHLETE_DETECTED,     // "Athlete detected! Step away to start..."
    STARTED               // Green flash - timer started
}

/**
 * Full-screen overlay implementing in-frame start timing mode.
 *
 * The athlete stands in front of the camera until detected (valid blob with
 * READY state), then steps away. When the detection state shows no valid blob
 * for 3+ consecutive frames after detection, the timer starts.
 *
 * @param onStart Called with the precise monotonic timestamp (nanos) when the start triggers.
 * @param onCancel Called when the user cancels.
 * @param detectionState StateFlow of the PhotoFinishDetector state, observed from GateEngine.
 */
@Composable
fun InFrameStartOverlay(
    onStart: (Long) -> Unit,
    onCancel: () -> Unit,
    detectionState: StateFlow<PhotoFinishDetector.State>
) {
    val context = LocalContext.current
    val currentDetectionState by detectionState.collectAsStateWithLifecycle()
    var phase by remember { mutableStateOf(InFramePhase.WAITING_FOR_ATHLETE) }
    var framesWithoutBlob by remember { mutableIntStateOf(0) }

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

    // React to detection state changes
    LaunchedEffect(currentDetectionState, phase) {
        when (phase) {
            InFramePhase.WAITING_FOR_ATHLETE -> {
                // Transition to DETECTED when we see a valid blob (READY or TRIGGERED)
                if (currentDetectionState == PhotoFinishDetector.State.READY ||
                    currentDetectionState == PhotoFinishDetector.State.TRIGGERED
                ) {
                    phase = InFramePhase.ATHLETE_DETECTED
                    framesWithoutBlob = 0
                    Log.i("InFrameStartOverlay", "Athlete detected - waiting for step-away")

                    // Haptic for detection
                    vibrator?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            it.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            it.vibrate(50)
                        }
                    }
                }
            }
            InFramePhase.ATHLETE_DETECTED -> {
                // If detection state drops to NO_ATHLETE or UNSTABLE, count frames
                val hasBlob = currentDetectionState == PhotoFinishDetector.State.READY ||
                        currentDetectionState == PhotoFinishDetector.State.TRIGGERED ||
                        currentDetectionState == PhotoFinishDetector.State.ATHLETE_TOO_FAR
                if (!hasBlob) {
                    framesWithoutBlob++
                    if (framesWithoutBlob >= FRAMES_WITHOUT_BLOB_TO_START) {
                        val timestamp = SystemClock.elapsedRealtimeNanos()
                        phase = InFramePhase.STARTED
                        Log.i("InFrameStartOverlay", "Athlete stepped away - STARTING TIMER at $timestamp")

                        // Strong haptic on start
                        vibrator?.let {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                it.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                it.vibrate(80)
                            }
                        }

                        onStart(timestamp)
                    }
                } else {
                    // Reset counter if blob reappears
                    framesWithoutBlob = 0
                }
            }
            InFramePhase.STARTED -> {
                // No further processing
            }
        }
    }

    // Pulsing animation
    val infiniteTransition = rememberInfiniteTransition(label = "inframe_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "inframe_pulse_scale"
    )

    // Background color based on phase
    val backgroundColor = when (phase) {
        InFramePhase.WAITING_FOR_ATHLETE -> OverlayBackground
        InFramePhase.ATHLETE_DETECTED -> DetectedAmber
        InFramePhase.STARTED -> AccentGreen
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // Cancel button (top-left) - only before start
        if (phase != InFramePhase.STARTED) {
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
            targetState = phase,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(150))
            },
            label = "inframe_phase_content"
        ) { currentPhase ->
            when (currentPhase) {
                InFramePhase.WAITING_FOR_ATHLETE -> WaitingForAthleteContent(pulseScale = pulseScale)
                InFramePhase.ATHLETE_DETECTED -> AthleteDetectedContent(pulseScale = pulseScale)
                InFramePhase.STARTED -> StartedContent()
            }
        }
    }
}

@Composable
private fun WaitingForAthleteContent(pulseScale: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PersonOff,
            contentDescription = stringResource(R.string.inframe_waiting_cd),
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .size(100.dp)
                .scale(pulseScale)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.inframe_stand_in_front),
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.inframe_waiting_desc),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

@Composable
private fun AthleteDetectedContent(pulseScale: Float) {
    val ringColor = Color.White.copy(alpha = 0.3f)
    val innerColor = Color.White.copy(alpha = 0.2f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pulsing ring with visibility icon
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(pulseScale)
                .drawBehind {
                    drawCircle(
                        color = ringColor,
                        radius = size.minDimension / 2,
                        style = Stroke(width = 4.dp.toPx())
                    )
                    drawCircle(
                        color = innerColor,
                        radius = size.minDimension / 2 * 0.78f
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = stringResource(R.string.inframe_detected_cd),
                tint = Color.White,
                modifier = Modifier.size(64.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.inframe_detected),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.inframe_step_away),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
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
private fun StartedContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
            contentDescription = stringResource(R.string.inframe_go_cd),
            tint = Color.White,
            modifier = Modifier.size(120.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.inframe_go),
            color = Color.White,
            fontSize = 96.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 8.sp
        )
    }
}
