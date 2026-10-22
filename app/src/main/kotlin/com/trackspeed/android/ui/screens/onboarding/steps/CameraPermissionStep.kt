package com.trackspeed.android.ui.screens.onboarding.steps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.trackspeed.android.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun CameraPermissionStep(onContinue: () -> Unit, onSkip: () -> Unit, onPermissionResult: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val flow = remember {
        CameraPermissionStepFlow(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionState by remember { mutableStateOf(flow.permissionState) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        flow.onPermissionResult(granted)
        permissionState = flow.permissionState
        onPermissionResult(granted)
    }
    DisposableEffect(lifecycleOwner, context, flow) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                flow.onResume(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                permissionState = flow.permissionState
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Explicit continuation also applies after a native grant and when returning here with Back.
    // The flow gate protects against repeated taps while a navigation transition is in progress.
    val continueOnce = { if (flow.claimContinue()) onContinue() }
    val skipOnce = { if (flow.claimSkip()) onSkip() }
    val headline = stringResource(when (permissionState) {
        CameraPermissionState.NOT_DETERMINED -> R.string.parity_onboarding_camera_needed
        CameraPermissionState.GRANTED -> R.string.parity_onboarding_camera_granted
        CameraPermissionState.DENIED -> R.string.parity_onboarding_camera_off
    })
    val description = stringResource(when (permissionState) {
        CameraPermissionState.NOT_DETERMINED -> R.string.parity_onboarding_camera_explanation
        CameraPermissionState.GRANTED -> R.string.parity_onboarding_camera_ready
        CameraPermissionState.DENIED -> R.string.parity_onboarding_camera_unavailable
    })

    Box(Modifier.fillMaxSize()) {
        OnboardingPhotoBackdrop(R.drawable.onboarding_camera_alignment_photo)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(headline, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, modifier = Modifier.semantics { heading() })
                Text(description, fontSize = 17.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium, color = Color.White)
            }
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (permissionState) {
                    CameraPermissionState.NOT_DETERMINED -> OnboardingPhotoContinueButton(
                        title = stringResource(R.string.common_continue),
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()
                    )
                    CameraPermissionState.DENIED -> OnboardingPhotoContinueButton(
                        title = stringResource(R.string.onboarding_camera_open_settings),
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }, modifier = Modifier.fillMaxWidth()
                    )
                    CameraPermissionState.GRANTED -> OnboardingPhotoContinueButton(
                        title = stringResource(R.string.common_continue), onClick = continueOnce, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (permissionState != CameraPermissionState.GRANTED) {
                    TextButton(onClick = skipOnce, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.common_skip_for_now), color = Color.White)
                    }
                }
            }
        }
    }
}
