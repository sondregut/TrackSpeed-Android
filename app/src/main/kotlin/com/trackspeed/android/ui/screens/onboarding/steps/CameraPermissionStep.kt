package com.trackspeed.android.ui.screens.onboarding.steps

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.trackspeed.android.R
import com.trackspeed.android.ui.theme.*
import kotlinx.coroutines.delay

private val SuccessGreen = AccentGreen
private val WarningOrange = AccentGold

private enum class CameraPermissionState {
    NOT_DETERMINED,
    GRANTED,
    DENIED
}

@Composable
fun CameraPermissionStep(
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    var permissionState by remember {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) CameraPermissionState.GRANTED
            else CameraPermissionState.NOT_DETERMINED
        )
    }

    var appeared by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.8f,
        animationSpec = tween(300),
        label = "icon_scale"
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionState = if (granted) CameraPermissionState.GRANTED else CameraPermissionState.DENIED
        if (granted) {
            // Continue after a brief delay to show success state
        }
    }

    // Auto-continue after permission granted
    LaunchedEffect(permissionState) {
        if (permissionState == CameraPermissionState.GRANTED) {
            delay(600)
            onContinue()
        }
    }

    LaunchedEffect(Unit) { appeared = true }

    val iconTint = when (permissionState) {
        CameraPermissionState.GRANTED -> SuccessGreen
        CameraPermissionState.DENIED -> WarningOrange
        CameraPermissionState.NOT_DETERMINED -> AccentBlue
    }

    val headline = when (permissionState) {
        CameraPermissionState.GRANTED -> stringResource(R.string.onboarding_camera_headline_granted)
        CameraPermissionState.DENIED -> stringResource(R.string.onboarding_camera_headline_denied)
        CameraPermissionState.NOT_DETERMINED -> stringResource(R.string.onboarding_camera_headline_undetermined)
    }

    val description = when (permissionState) {
        CameraPermissionState.GRANTED -> stringResource(R.string.onboarding_camera_desc_granted)
        CameraPermissionState.DENIED -> stringResource(R.string.onboarding_camera_desc_denied)
        CameraPermissionState.NOT_DETERMINED -> stringResource(R.string.onboarding_camera_desc_undetermined)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.weight(1f))

        // Camera icon in tinted circle
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .background(
                    color = iconTint.copy(alpha = 0.15f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (permissionState == CameraPermissionState.GRANTED)
                    Icons.Default.CheckCircle else Icons.Default.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = iconTint
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = headline,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = description,
            fontSize = 17.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        Spacer(Modifier.weight(1f))

        when (permissionState) {
            CameraPermissionState.NOT_DETERMINED -> {
                Button(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(
                        stringResource(R.string.onboarding_camera_allow),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            CameraPermissionState.DENIED -> {
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(
                        stringResource(R.string.onboarding_camera_open_settings),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            CameraPermissionState.GRANTED -> {
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(
                        stringResource(R.string.common_continue),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (permissionState != CameraPermissionState.GRANTED) {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.common_skip_for_now), color = TextSecondary)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
