package com.trackspeed.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackspeed.android.R
import com.trackspeed.android.camerastart.CameraStartArming
import com.trackspeed.android.ui.theme.AccentGreen
import com.trackspeed.android.ui.theme.CardBackground
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary

/** Presentation only. Timing lives in the sensor-stamped pose pipeline, never a Compose state observer. */
@Composable
fun CameraStartStatus(
    phase: CameraStartArming.Phase,
    progress: Float,
    cameraAvailable: Boolean,
    modifier: Modifier = Modifier
) {
    val label = when {
        !cameraAvailable -> stringResource(R.string.camera_start_wait_camera)
        else -> when (phase) {
            CameraStartArming.Phase.STEP_INTO_FRAME -> stringResource(R.string.camera_start_step_in)
            CameraStartArming.Phase.HOLD_STILL -> stringResource(R.string.camera_start_hold_still, (progress * 100).toInt())
            CameraStartArming.Phase.WAITING_FOR_SESSION -> stringResource(R.string.camera_start_wait_session)
            CameraStartArming.Phase.ARMED -> stringResource(R.string.camera_start_ready)
            CameraStartArming.Phase.CONFIRMING -> stringResource(R.string.camera_start_confirming)
            CameraStartArming.Phase.STARTED -> stringResource(R.string.camera_start_started)
        }
    }
    Column(modifier.fillMaxWidth().background(CardBackground, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(label, color = if (phase == CameraStartArming.Phase.ARMED) AccentGreen else TextPrimary,
            fontWeight = FontWeight.SemiBold)
        if (phase == CameraStartArming.Phase.HOLD_STILL && cameraAvailable) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Text(stringResource(R.string.camera_start_one_athlete), color = TextSecondary)
    }
}
