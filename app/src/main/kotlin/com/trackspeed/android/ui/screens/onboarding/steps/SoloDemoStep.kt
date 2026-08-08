package com.trackspeed.android.ui.screens.onboarding.steps

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.detection.PhotoFinishDetector
import com.trackspeed.android.ui.components.CameraPreview
import com.trackspeed.android.ui.components.CameraPreviewPlaceholder
import com.trackspeed.android.ui.theme.*

@Composable
fun SoloDemoStep(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    viewModel: SoloDemoViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { viewModel.stopCamera() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.onboarding_solo_demo_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_solo_demo_body),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 23.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        SetupHint()

        Spacer(modifier = Modifier.height(14.dp))

        CameraDemoCard(
            state = state,
            onSurfaceReady = viewModel::onSurfaceReady,
            onSurfaceDestroyed = viewModel::onSurfaceDestroyed,
            onSwitchCamera = viewModel::switchCamera,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 260.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        ThumbnailStrip(
            thumbnails = state.thumbnails,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = {
                if (state.isCapturing) {
                    viewModel.stopCamera()
                    onContinue()
                } else {
                    viewModel.beginCapture()
                }
            },
            enabled = !state.cameraUnavailable,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
        ) {
            if (!state.isCapturing) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (state.isCapturing) {
                    stringResource(R.string.onboarding_solo_demo_done)
                } else {
                    stringResource(R.string.onboarding_solo_demo_start)
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        TextButton(
            onClick = {
                viewModel.stopCamera()
                onSkip()
            }
        ) {
            Text(
                text = stringResource(R.string.common_skip),
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun SetupHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AccentBlue.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .border(0.5.dp, AccentBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.onboarding_solo_demo_setup),
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun CameraDemoCard(
    state: SoloDemoUiState,
    onSurfaceReady: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previewDetectionState = if (state.isCapturing) {
        state.detectionState
    } else {
        PhotoFinishDetector.State.NO_ATHLETE
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
    ) {
        if (state.cameraUnavailable) {
            CameraPreviewPlaceholder(
                modifier = Modifier.matchParentSize(),
                message = stringResource(R.string.onboarding_solo_demo_camera_unavailable)
            )
        } else {
            CameraPreview(
                modifier = Modifier.matchParentSize(),
                gatePosition = state.gatePosition,
                onGatePositionChanged = {},
                gateLineDraggable = false,
                fps = state.fps,
                detectionState = previewDetectionState,
                sensorOrientation = state.sensorOrientation,
                isFrontCamera = state.isFrontCamera,
                onSurfaceReady = onSurfaceReady,
                onSurfaceDestroyed = onSurfaceDestroyed
            )
        }

        if (state.showFlash) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = 0.22f))
            )
        }

        DemoStatusPill(
            state = state,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )

        IconButton(
            onClick = onSwitchCamera,
            enabled = !state.cameraUnavailable,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .size(44.dp)
                .background(Color.Black.copy(alpha = 0.65f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Cameraswitch,
                contentDescription = stringResource(R.string.onboarding_solo_demo_flip_camera),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun DemoStatusPill(
    state: SoloDemoUiState,
    modifier: Modifier = Modifier
) {
    val (text, color) = when {
        state.cameraUnavailable -> stringResource(R.string.onboarding_solo_demo_camera_unavailable) to TimerRed
        state.cameraState is CameraManager.CameraState.Opening -> stringResource(R.string.onboarding_solo_demo_starting_camera) to AccentGold
        !state.isCapturing -> stringResource(R.string.onboarding_solo_demo_position) to Color.White
        state.detectionState == PhotoFinishDetector.State.UNSTABLE -> stringResource(R.string.onboarding_solo_demo_hold_still) to AccentGold
        state.detectionState == PhotoFinishDetector.State.TRIGGERED ||
            state.detectionState == PhotoFinishDetector.State.COOLDOWN -> stringResource(R.string.onboarding_solo_demo_crossing) to TimerRed
        else -> stringResource(R.string.onboarding_solo_demo_go) to AccentGreen
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(50))
            .border(0.5.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = color)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.detectionCount > 0) "$text ${state.detectionCount}" else text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
private fun ThumbnailStrip(
    thumbnails: List<Bitmap>,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (thumbnails.isEmpty()) {
            items(4) {
                ThumbnailPlaceholder()
            }
        } else {
            items(thumbnails.size) { index ->
                CrossingThumbnail(bitmap = thumbnails[index])
            }
        }
    }
}

@Composable
private fun ThumbnailPlaceholder() {
    Box(
        modifier = Modifier
            .size(width = 58.dp, height = 58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardBackground)
            .border(0.5.dp, BorderSubtle, RoundedCornerShape(10.dp))
    ) {
        GateLine(modifier = Modifier.matchParentSize(), color = TextMuted.copy(alpha = 0.35f))
    }
}

@Composable
private fun CrossingThumbnail(bitmap: Bitmap) {
    Box(
        modifier = Modifier
            .size(width = 58.dp, height = 58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .border(0.5.dp, AccentBlue.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.onboarding_solo_demo_thumbnail),
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop
        )
        GateLine(modifier = Modifier.matchParentSize(), color = TimerRed)
    }
}

@Composable
private fun GateLine(
    modifier: Modifier = Modifier,
    color: Color
) {
    Canvas(modifier = modifier) {
        val x = size.width * 0.5f
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 2f
        )
    }
}
