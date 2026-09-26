package com.trackspeed.android.ui.screens.onephone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackspeed.android.R
import com.trackspeed.android.onephone.SingleCameraBodyJointName
import com.trackspeed.android.onephone.SingleCameraBodyObservation
import com.trackspeed.android.onephone.SingleCameraPoint
import com.trackspeed.android.onephone.SingleCameraReadiness
import com.trackspeed.android.onephone.SingleCameraSprintConfiguration
import com.trackspeed.android.onephone.SingleCameraSprintDirection
import com.trackspeed.android.onephone.SingleCameraSprintDrill
import com.trackspeed.android.onephone.SingleCameraSprintFraming
import com.trackspeed.android.onephone.SingleCameraSprintFramingAssessment
import com.trackspeed.android.onephone.SingleCameraSprintGate
import com.trackspeed.android.onephone.SingleCameraSprintPhase
import com.trackspeed.android.onephone.SingleCameraSprintResult
import com.trackspeed.android.onephone.SingleCameraSprintResultPresentation
import com.trackspeed.android.onephone.SingleCameraSprintRunRecord
import com.trackspeed.android.onephone.SingleCameraSprintStartStyle
import com.trackspeed.android.onephone.SingleCameraSprintUiState
import com.trackspeed.android.onephone.SingleCameraSprintViewModel
import com.trackspeed.android.ui.components.CameraPreview
import com.trackspeed.android.detection.PhotoFinishDetector
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val TrackingColor = Color(0xFF2AD8B4)
private val PendingColor = Color(0xFFF3C83D)
private val WarningColor = Color(0xFFFF9F0A)

/**
 * Android port of iOS `SingleCameraSprintView`: one phone, landscape, the whole
 * course in view. The user drags a gate handle over each measured cone; body
 * pose crossings of those gates are timed.
 */
@Composable
fun SingleCameraSprintScreen(
    onClose: () -> Unit,
    viewModel: SingleCameraSprintViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    var showsEndConfirmation by remember { mutableStateOf(false) }
    var showsSessionReview by remember { mutableStateOf(false) }
    var isFinishingSession by remember { mutableStateOf(false) }

    LandscapeImmersiveEffect(context)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onPermissionResult(granted) }
    LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }

    val displayRotation = view.display?.rotation
    LaunchedEffect(displayRotation) {
        viewModel.setDisplayRotation(
            when (displayRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        )
    }

    LaunchedEffect(state.phase) {
        when (state.phase) {
            SingleCameraSprintPhase.Armed, SingleCameraSprintPhase.Completed,
            is SingleCameraSprintPhase.Invalid -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            SingleCameraSprintPhase.Running -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            else -> Unit
        }
    }

    fun requestClose() {
        when {
            state.phase.isRunInProgress -> showsEndConfirmation = true
            state.sessionRuns.isNotEmpty() -> {
                isFinishingSession = true
                showsSessionReview = true
            }
            else -> onClose()
        }
    }

    BackHandler { requestClose() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val mapper = VideoMapper(
            containerWidth = constraints.maxWidth.toFloat(),
            containerHeight = constraints.maxHeight.toFloat(),
            aspect = state.videoAspectRatio,
            density = density.density
        )

        if (state.readiness != SingleCameraReadiness.NEEDS_PERMISSION) {
            CameraPreview(
                gatePosition = 0.5f,
                onGatePositionChanged = {},
                gateLineDraggable = false,
                showGateLine = false,
                fps = 0,
                detectionState = PhotoFinishDetector.State.READY,
                sensorOrientation = state.sensorOrientation,
                isFrontCamera = state.isFrontCamera,
                previewWidth = state.previewWidth,
                previewHeight = state.previewHeight,
                showDiagnostics = false,
                onSurfaceReady = viewModel::onSurfaceReady,
                onSurfaceDestroyed = viewModel::onSurfaceDestroyed,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.34f),
                        0.35f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.14f)
                    )
                )
        )

        CourseOverlay(state = state, mapper = mapper)
        state.bodyObservation?.let { PoseOverlay(it, mapper) }

        if (state.phase == SingleCameraSprintPhase.Calibrating) {
            state.gates.forEach { gate ->
                GateHandle(
                    gate = gate,
                    mapper = mapper,
                    isActive = state.activeGateDistance == gate.distanceMeters,
                    label = gateLabel(state.configuration, gate),
                    onDrag = { normalizedX -> viewModel.moveGate(gate.distanceMeters, normalizedX) },
                    onDragEnd = {
                        viewModel.endGateDrag()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                )
            }
        }

        TopBar(
            state = state,
            onClose = ::requestClose,
            onSwitchCamera = viewModel::switchCamera,
            onArm = { viewModel.arm() },
            onReset = viewModel::resetRun,
            onSelectDrill = viewModel::selectDrill,
            onSelectStartStyle = viewModel::selectStartStyle,
            onSelectDistance = viewModel::selectDistance,
            onFlipDirection = viewModel::flipDirection,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        )

        StateBanner(
            state = state,
            onGrantPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onRetryCamera = viewModel::retryCamera,
            modifier = Modifier.align(Alignment.Center)
        )

        if (state.phoneMotionWarningVisible) {
            PhoneMotionBanner(
                completed = state.phase == SingleCameraSprintPhase.Completed,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 68.dp)
            )
        }

        if (state.phase == SingleCameraSprintPhase.Calibrating && state.readiness == SingleCameraReadiness.READY) {
            val handleY = with(density) { GateHandleGeometry.centerY(mapper).toDp() }
            CalibrationGuidance(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = max(92f, maxHeight.value - handleY.value + 54f).dp)
                    .padding(horizontal = 22.dp)
            )
        }

        if (state.result != null || state.sessionRuns.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                if (state.sessionRuns.isNotEmpty()) {
                    SessionRunShelf(
                        state = state,
                        onOpen = {
                            isFinishingSession = false
                            showsSessionReview = true
                        },
                        modifier = Modifier.widthIn(max = 460.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.result != null) {
                    MetricButton(
                        text = stringResource(R.string.one_phone_next_rep),
                        icon = Icons.Default.Refresh,
                        primary = true,
                        onClick = viewModel::resetRun
                    )
                }
            }
        }

        val errorReason = state.errorReason
        if (errorReason != null && state.result == null && state.phase !is SingleCameraSprintPhase.Invalid) {
            ErrorBanner(
                text = stringResource(singleCameraInvalidReasonRes(errorReason)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 74.dp)
                    .clickable { viewModel.dismissError() }
            )
        }
    }

    if (showsEndConfirmation) {
        val runCount = state.sessionRuns.size
        AlertDialog(
            onDismissRequest = { showsEndConfirmation = false },
            title = { Text(stringResource(R.string.one_phone_end_title)) },
            text = {
                Text(
                    if (runCount > 0) {
                        pluralStringResource(R.plurals.one_phone_end_message_runs, runCount, runCount)
                    } else {
                        stringResource(R.string.one_phone_end_message_no_runs)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showsEndConfirmation = false
                    viewModel.resetRun()
                    if (runCount == 0) {
                        onClose()
                    } else {
                        isFinishingSession = true
                        showsSessionReview = true
                    }
                }) {
                    Text(
                        stringResource(
                            if (runCount == 0) R.string.one_phone_end_session else R.string.one_phone_view_results
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showsEndConfirmation = false }) {
                    Text(stringResource(R.string.one_phone_keep_going))
                }
            }
        )
    }

    if (showsSessionReview) {
        SessionReviewDialog(
            state = state,
            isFinishing = isFinishingSession,
            onDismiss = { showsSessionReview = false },
            onFinish = {
                viewModel.finishSession {
                    showsSessionReview = false
                    onClose()
                }
            }
        )
    }
}

// region Orientation / immersive

@Composable
private fun LandscapeImmersiveEffect(context: Context) {
    DisposableEffect(context) {
        val activity = context.findActivity()
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            // A rotation relaunch keeps the requested orientation; only restore
            // when the screen is really leaving.
            if (activity != null && !activity.isChangingConfigurations) {
                activity.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                controller?.show(WindowInsetsCompat.Type.systemBars())
                activity.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

// endregion

// region Geometry

/** Maps normalized frame coordinates onto the aspect-filled preview. */
private class VideoMapper(
    val containerWidth: Float,
    val containerHeight: Float,
    aspect: Float,
    val density: Float
) {
    val left: Float
    val top: Float
    val width: Float
    val height: Float

    init {
        val videoWidth = max(aspect, 0.1f)
        val scale = max(containerWidth / videoWidth, containerHeight)
        width = videoWidth * scale
        height = scale
        left = (containerWidth - width) / 2
        top = (containerHeight - height) / 2
    }

    fun viewX(normalizedX: Double): Float = left + normalizedX.toFloat() * width
    fun point(p: SingleCameraPoint): Offset = Offset(left + p.x.toFloat() * width, top + p.y.toFloat() * height)
    fun normalizedX(viewX: Float): Double =
        if (width <= 0f) 0.5 else ((viewX - left) / width).toDouble().coerceIn(0.02, 0.98)

    fun dp(value: Float): Float = value * density

    val nearY: Float get() = min(containerHeight - dp(76f), top + height * 0.81f)
    val farY: Float get() = max(max(0f, top) + dp(88f), nearY - min(dp(58f), containerHeight * 0.13f))
}

private object GateHandleGeometry {
    fun centerY(mapper: VideoMapper): Float = min(mapper.containerHeight - mapper.dp(38f), mapper.nearY + mapper.dp(40f))
}

// endregion

// region Overlays

@Composable
private fun CourseOverlay(state: SingleCameraSprintUiState, mapper: VideoMapper) {
    val entersRight = stringResource(R.string.one_phone_runner_enters_right)
    val entersLeft = stringResource(R.string.one_phone_runner_enters_left)
    val showsFlyingZone = state.phase == SingleCameraSprintPhase.Calibrating &&
        state.configuration.startStyle == SingleCameraSprintStartStyle.FLYING
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (showsFlyingZone) drawFlyingZone(state, mapper)
            drawLane(state, mapper)
        }
        if (showsFlyingZone) {
            val startGate = state.gates.minByOrNull { it.distanceMeters }
            if (startGate != null) {
                val safeX = mapper.viewX(
                    if (state.direction == SingleCameraSprintDirection.LEFT_TO_RIGHT) {
                        SingleCameraSprintFraming.SAFE_DIRECTED_LOWER_BOUND
                    } else {
                        1 - SingleCameraSprintFraming.SAFE_DIRECTED_LOWER_BOUND
                    }
                )
                val startX = mapper.viewX(startGate.normalizedX)
                val centerX = (safeX + startX) / 2
                val centerY = min(mapper.containerHeight - mapper.dp(104f), max(mapper.dp(96f), mapper.containerHeight * 0.66f))
                val density = LocalDensity.current
                Text(
                    text = if (state.direction == SingleCameraSprintDirection.LEFT_TO_RIGHT) entersRight else entersLeft,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (centerX - with(density) { 70.dp.toPx() }).roundToInt(),
                                (centerY - with(density) { 15.dp.toPx() }).roundToInt()
                            )
                        }
                        .width(140.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(1.dp, TrackingColor.copy(alpha = 0.72f), RoundedCornerShape(15.dp))
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun DrawScope.drawFlyingZone(state: SingleCameraSprintUiState, mapper: VideoMapper) {
    val startGate = state.gates.minByOrNull { it.distanceMeters } ?: return
    val safeX = mapper.viewX(
        if (state.direction == SingleCameraSprintDirection.LEFT_TO_RIGHT) {
            SingleCameraSprintFraming.SAFE_DIRECTED_LOWER_BOUND
        } else {
            1 - SingleCameraSprintFraming.SAFE_DIRECTED_LOWER_BOUND
        }
    )
    val startX = mapper.viewX(startGate.normalizedX)
    val minX = min(safeX, startX)
    val maxX = max(safeX, startX)
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(TrackingColor.copy(alpha = 0.02f), TrackingColor.copy(alpha = 0.13f)),
            startX = safeX,
            endX = startX
        ),
        topLeft = Offset(minX, 0f),
        size = Size(maxX - minX, size.height)
    )
    drawLine(
        color = TrackingColor.copy(alpha = 0.42f),
        start = Offset(safeX, 0f),
        end = Offset(safeX, size.height),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 7.dp.toPx()))
    )
}

private fun DrawScope.drawLane(state: SingleCameraSprintUiState, mapper: VideoMapper) {
    val ordered = state.gates.sortedBy { it.distanceMeters }
    val start = ordered.firstOrNull() ?: return
    val finish = ordered.last()
    val startX = mapper.viewX(start.normalizedX)
    val finishX = mapper.viewX(finish.normalizedX)
    val visibleTop = max(0f, mapper.top)
    val nearY = mapper.nearY
    val farY = mapper.farY
    val centerX = (startX + finishX) / 2
    fun farX(nearX: Float) = centerX + (nearX - centerX) * 0.72f
    val isCompleted = state.phase == SingleCameraSprintPhase.Completed
    val didStart = state.phase == SingleCameraSprintPhase.Running || isCompleted
    val drill = state.configuration.drill
    val passed = state.splits.map { it.distanceMeters }.toSet()
    fun isPassed(gate: SingleCameraSprintGate): Boolean {
        if (drill.requiresTurnReview) {
            val startDistance = drill.startDistance(finish.distanceMeters)
            return abs(gate.distanceMeters - startDistance) < 0.01 && didStart
        }
        return if (gate.distanceMeters == 0.0) didStart else gate.distanceMeters in passed
    }

    if (!isCompleted) {
        val field = Path().apply {
            moveTo(startX, nearY)
            lineTo(finishX, nearY)
            lineTo(farX(finishX), farY)
            lineTo(farX(startX), farY)
            close()
        }
        drawPath(field, PendingColor.copy(alpha = 0.035f))
        drawPath(field, PendingColor.copy(alpha = 0.88f), style = Stroke(2.2.dp.toPx(), join = StrokeJoin.Round))
        if (drill == SingleCameraSprintDrill.SPRINT) {
            val sign = if (state.direction == SingleCameraSprintDirection.LEFT_TO_RIGHT) 1f else -1f
            for (index in 1..4) {
                val nearX = startX + (finishX - startX) * index / 5f
                val arrowY = nearY + (farY - nearY) * 0.46f
                val arrow = Path().apply {
                    moveTo(nearX - 10.dp.toPx() * sign, arrowY - 6.dp.toPx())
                    lineTo(nearX, arrowY)
                    lineTo(nearX - 10.dp.toPx() * sign, arrowY + 6.dp.toPx())
                }
                drawPath(
                    arrow,
                    PendingColor.copy(alpha = 0.84f),
                    style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }

    for (gate in ordered) {
        val nearX = mapper.viewX(gate.normalizedX)
        val gatePassed = isPassed(gate)
        val color = if (gatePassed) TrackingColor else PendingColor
        drawLine(
            color.copy(alpha = 0.93f),
            Offset(nearX, visibleTop),
            Offset(nearX, size.height),
            strokeWidth = 2.3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color,
            Offset(nearX, nearY),
            Offset(farX(nearX), farY),
            strokeWidth = (if (gatePassed) 4.5 else 3.0).dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(PendingColor, radius = 3.5.dp.toPx(), center = Offset(nearX, nearY))
    }
}

private val PoseConnections = listOf(
    SingleCameraBodyJointName.LEFT_WRIST to SingleCameraBodyJointName.LEFT_ELBOW,
    SingleCameraBodyJointName.LEFT_ELBOW to SingleCameraBodyJointName.LEFT_SHOULDER,
    SingleCameraBodyJointName.RIGHT_WRIST to SingleCameraBodyJointName.RIGHT_ELBOW,
    SingleCameraBodyJointName.RIGHT_ELBOW to SingleCameraBodyJointName.RIGHT_SHOULDER,
    SingleCameraBodyJointName.LEFT_SHOULDER to SingleCameraBodyJointName.NECK,
    SingleCameraBodyJointName.RIGHT_SHOULDER to SingleCameraBodyJointName.NECK,
    SingleCameraBodyJointName.NECK to SingleCameraBodyJointName.ROOT,
    SingleCameraBodyJointName.ROOT to SingleCameraBodyJointName.LEFT_HIP,
    SingleCameraBodyJointName.ROOT to SingleCameraBodyJointName.RIGHT_HIP,
    SingleCameraBodyJointName.LEFT_HIP to SingleCameraBodyJointName.LEFT_KNEE,
    SingleCameraBodyJointName.LEFT_KNEE to SingleCameraBodyJointName.LEFT_ANKLE,
    SingleCameraBodyJointName.RIGHT_HIP to SingleCameraBodyJointName.RIGHT_KNEE,
    SingleCameraBodyJointName.RIGHT_KNEE to SingleCameraBodyJointName.RIGHT_ANKLE
)

@Composable
private fun PoseOverlay(observation: SingleCameraBodyObservation, mapper: VideoMapper) {
    Canvas(Modifier.fillMaxSize()) {
        for ((from, to) in PoseConnections) {
            val a = observation.joints[from] ?: continue
            val b = observation.joints[to] ?: continue
            if (a.confidence < 0.18 || b.confidence < 0.18) continue
            val start = mapper.point(a.location)
            val end = mapper.point(b.location)
            drawLine(Color.Black.copy(alpha = 0.24f), start, end, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(TrackingColor, start, end, strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
        }
        for (joint in observation.joints.values) {
            if (joint.confidence < 0.18) continue
            val center = mapper.point(joint.location)
            if (joint.name == SingleCameraBodyJointName.ROOT) {
                drawCircle(TrackingColor.copy(alpha = 0.24f), radius = 15.dp.toPx(), center = center)
                drawCircle(Color.White.copy(alpha = 0.92f), radius = 15.dp.toPx(), center = center, style = Stroke(2.5.dp.toPx()))
                drawCircle(TrackingColor, radius = 6.dp.toPx(), center = center)
            } else {
                drawCircle(TrackingColor, radius = 3.5.dp.toPx(), center = center)
            }
        }
    }
}

@Composable
private fun GateHandle(
    gate: SingleCameraSprintGate,
    mapper: VideoMapper,
    isActive: Boolean,
    label: String,
    onDrag: (Double) -> Unit,
    onDragEnd: () -> Unit
) {
    val density = LocalDensity.current
    val currentMapper by rememberUpdatedState(mapper)
    val currentGate by rememberUpdatedState(gate)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val halfSizePx = with(density) { 38.dp.toPx() }
    val x = mapper.viewX(gate.normalizedX)
    val y = GateHandleGeometry.centerY(mapper)
    Box(
        modifier = Modifier
            .offset { IntOffset((x - halfSizePx).roundToInt(), (y - halfSizePx).roundToInt()) }
            .size(76.dp)
            .semantics { contentDescription = label }
            .pointerInput(gate.distanceMeters) {
                var dragX = 0f
                detectDragGestures(
                    onDragStart = { dragX = currentMapper.viewX(currentGate.normalizedX) },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    dragX += dragAmount.x
                    currentOnDrag(currentMapper.normalizedX(dragX))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .scale(if (isActive) 1.08f else 1f)
                .size(56.dp)
                .clip(CircleShape)
                .background(TrackingColor)
                .border(
                    if (isActive) 3.dp else 1.5.dp,
                    Color.White.copy(alpha = if (isActive) 0.96f else 0.62f),
                    CircleShape
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun gateLabel(configuration: SingleCameraSprintConfiguration, gate: SingleCameraSprintGate): String =
    when {
        configuration.drill == SingleCameraSprintDrill.PRO_AGILITY ->
            stringResource(
                if (abs(gate.distanceMeters - 4.572) < 0.01) R.string.one_phone_gate_label_start_end
                else R.string.one_phone_gate_label_five_yards
            )
        configuration.drill == SingleCameraSprintDrill.SHUTTLE && gate.distanceMeters == 0.0 ->
            stringResource(R.string.one_phone_gate_label_start_end)
        else -> stringResource(R.string.one_phone_gate_label_meters, gate.distanceMeters.toInt())
    }

// endregion

// region Chrome

@Composable
private fun TopBar(
    state: SingleCameraSprintUiState,
    onClose: () -> Unit,
    onSwitchCamera: () -> Unit,
    onArm: () -> Unit,
    onReset: () -> Unit,
    onSelectDrill: (SingleCameraSprintDrill) -> Unit,
    onSelectStartStyle: (SingleCameraSprintStartStyle) -> Unit,
    onSelectDistance: (Double) -> Unit,
    onFlipDirection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCalibrating = state.phase == SingleCameraSprintPhase.Calibrating
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChromeButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.one_phone_close), onClick = onClose)
        if (isCalibrating) {
            ChromeButton(
                Icons.Outlined.Cameraswitch,
                stringResource(R.string.one_phone_switch_camera),
                tint = if (state.isFrontCamera) TrackingColor else Color.White,
                enabled = state.readiness == SingleCameraReadiness.READY,
                onClick = onSwitchCamera
            )
        }
        Spacer(Modifier.weight(1f))
        when (state.phase) {
            SingleCameraSprintPhase.Calibrating -> MetricButton(
                text = stringResource(R.string.one_phone_start),
                icon = Icons.Default.Bolt,
                primary = true,
                enabled = state.canArm,
                onClick = onArm
            )
            SingleCameraSprintPhase.Completed -> Unit
            is SingleCameraSprintPhase.Invalid -> MetricButton(
                text = stringResource(R.string.one_phone_reset),
                icon = Icons.Default.Refresh,
                primary = false,
                onClick = onReset
            )
            else -> MetricButton(
                text = stringResource(R.string.one_phone_cancel),
                icon = Icons.Default.Close,
                primary = false,
                onClick = onReset
            )
        }
        Spacer(Modifier.weight(1f))
        ConfigurationPicker(
            configuration = state.configuration,
            enabled = state.isConfigurationEditable,
            onSelectDrill = onSelectDrill,
            onSelectStartStyle = onSelectStartStyle,
            onSelectDistance = onSelectDistance
        )
        ChromeButton(
            Icons.Default.SwapHoriz,
            stringResource(R.string.one_phone_flip_direction),
            tint = TrackingColor,
            enabled = isCalibrating,
            onClick = onFlipDirection
        )
    }
}

@Composable
private fun ConfigurationPicker(
    configuration: SingleCameraSprintConfiguration,
    enabled: Boolean,
    onSelectDrill: (SingleCameraSprintDrill) -> Unit,
    onSelectStartStyle: (SingleCameraSprintStartStyle) -> Unit,
    onSelectDistance: (Double) -> Unit
) {
    var drillMenu by remember { mutableStateOf(false) }
    var styleMenu by remember { mutableStateOf(false) }
    var distanceMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.54f))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            MenuLabel(stringResource(singleCameraDrillTitleRes(configuration.drill)), enabled) { drillMenu = true }
            DropdownMenu(expanded = drillMenu, onDismissRequest = { drillMenu = false }) {
                SingleCameraSprintDrill.entries.forEach { drill ->
                    DropdownMenuItem(
                        text = { Text(stringResource(singleCameraDrillTitleRes(drill))) },
                        onClick = {
                            drillMenu = false
                            onSelectDrill(drill)
                        }
                    )
                }
            }
        }
        Box {
            MenuLabel(stringResource(singleCameraStartStyleTitleRes(configuration.startStyle)), enabled) {
                styleMenu = true
            }
            DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                val styles = if (configuration.drill == SingleCameraSprintDrill.PRO_AGILITY) {
                    listOf(SingleCameraSprintStartStyle.STANDING)
                } else {
                    SingleCameraSprintStartStyle.entries
                }
                styles.forEach { style ->
                    DropdownMenuItem(
                        text = { Text(stringResource(singleCameraStartStyleTitleRes(style))) },
                        onClick = {
                            styleMenu = false
                            onSelectStartStyle(style)
                        }
                    )
                }
            }
        }
        Box(
            Modifier
                .width(1.dp)
                .height(22.dp)
                .background(Color.White.copy(alpha = 0.18f))
        )
        Box {
            MenuLabel(singleCameraDistanceLabel(configuration, short = true), enabled, icon = Icons.Outlined.Straighten) {
                distanceMenu = true
            }
            DropdownMenu(expanded = distanceMenu, onDismissRequest = { distanceMenu = false }) {
                SingleCameraSprintConfiguration.supportedDistances(configuration.startStyle, configuration.drill)
                    .forEach { distance ->
                        DropdownMenuItem(
                            text = { Text(singleCameraDistanceLabel(configuration.changingDistance(distance))) },
                            onClick = {
                                distanceMenu = false
                                onSelectDistance(distance)
                            }
                        )
                    }
            }
        }
    }
}

@Composable
private fun MenuLabel(title: String, enabled: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val tint = TrackingColor.copy(alpha = if (enabled) 1f else 0.58f)
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(title, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = tint.copy(alpha = 0.66f),
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun ChromeButton(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.54f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint.copy(alpha = if (enabled) 1f else 0.48f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MetricButton(
    text: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .background(if (primary) TrackingColor.copy(alpha = if (enabled) 1f else 0.55f) else Color.Black.copy(alpha = 0.54f))
            .then(if (primary) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// endregion

// region State surfaces

@Composable
private fun StateBanner(
    state: SingleCameraSprintUiState,
    onGrantPermission: () -> Unit,
    onRetryCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    val result = state.result
    when {
        result != null -> ResultBanner(state, result, modifier)
        state.phase == SingleCameraSprintPhase.Running -> Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            LiveTimer(state)
            Text(
                stringResource(R.string.one_phone_live),
                color = TrackingColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp
            )
        }
        state.readiness == SingleCameraReadiness.NEEDS_PERMISSION -> CenterMessage(
            title = stringResource(R.string.one_phone_camera_access),
            subtitle = stringResource(R.string.one_phone_camera_access_body),
            actionText = stringResource(R.string.one_phone_grant_access),
            onAction = onGrantPermission,
            modifier = modifier
        )
        state.readiness == SingleCameraReadiness.FAILED -> CenterMessage(
            title = stringResource(R.string.one_phone_camera_failed),
            subtitle = null,
            actionText = stringResource(R.string.one_phone_try_again),
            onAction = onRetryCamera,
            modifier = modifier
        )
        state.readiness != SingleCameraReadiness.READY -> CenterMessage(
            title = stringResource(R.string.one_phone_starting_camera),
            subtitle = null,
            modifier = modifier
        )
        state.phase == SingleCameraSprintPhase.Acquiring && state.isBodyVisibleOutsideTimingZone -> Row(
            modifier = modifier
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(PendingColor)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stateTitle(state), color = Color.Black.copy(alpha = 0.88f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        state.phase != SingleCameraSprintPhase.Calibrating -> {
            val phase = state.phase
            CenterMessage(
                title = stateTitle(state),
                subtitle = if (phase is SingleCameraSprintPhase.Invalid) {
                    stringResource(singleCameraInvalidReasonRes(phase.reason))
                } else {
                    stateSubtitle(state)
                },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun CenterMessage(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = if (actionText != null) 24.sp else 40.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        if (subtitle != null) {
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 430.dp)
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            MetricButton(text = actionText, icon = Icons.Default.Refresh, primary = true, onClick = onAction)
        }
    }
}

@Composable
private fun LiveTimer(state: SingleCameraSprintUiState) {
    val start = state.liveStartTimestamp
    var now by remember { mutableDoubleStateOf(SystemClock.elapsedRealtimeNanos() / 1e9) }
    LaunchedEffect(start) {
        if (start == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            now = SystemClock.elapsedRealtimeNanos() / 1e9
        }
    }
    // Pose updates can be slow; camera timestamps share elapsedRealtime, so the
    // display advances smoothly without changing any recorded time.
    val displayed = if (start != null) {
        max(state.liveElapsedSeconds, min(now - start, state.liveElapsedSeconds + 0.5))
    } else {
        state.liveElapsedSeconds
    }
    TimeText(displayed)
}

@Composable
private fun TimeText(seconds: Double) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            String.format(Locale.US, "%.2f", seconds),
            color = Color.White,
            fontSize = 88.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.one_phone_seconds_suffix),
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 14.dp)
        )
    }
}

@Composable
private fun ResultBanner(state: SingleCameraSprintUiState, result: SingleCameraSprintResult, modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TimeText(result.totalTimeSeconds)
        val intermediate = result.splits.dropLast(1)
        if (intermediate.isNotEmpty() || result.peakVelocityMetersPerSecond > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                intermediate.forEach { split ->
                    ResultChip(
                        label = if (result.drill == SingleCameraSprintDrill.PRO_AGILITY) {
                            stringResource(R.string.one_phone_split_chip_centre_return)
                        } else {
                            stringResource(R.string.one_phone_distance_meters, split.distanceMeters.toInt())
                        },
                        value = String.format(Locale.US, "%.2f", split.elapsedSeconds)
                    )
                }
                if (result.peakVelocityMetersPerSecond > 0) {
                    ResultChip(
                        label = stringResource(R.string.one_phone_split_chip_peak),
                        value = stringResource(R.string.one_phone_peak_value, result.peakVelocityMetersPerSecond),
                        valueColor = TrackingColor
                    )
                }
            }
        }
        val note = SingleCameraSprintResultPresentation.qualityNote(
            quality = result.quality,
            phoneMoved = state.phoneMotionWarningVisible || state.currentRun?.phoneMotionWarning == true,
            averagePoseConfidence = result.averagePoseConfidence,
            estimatedPoseFPS = result.estimatedPoseFPS,
            calibrationResidualMeters = result.calibrationResidualMeters,
            drill = result.drill
        )
        if (note != null) {
            val color = if (note.needsReview) WarningColor else TrackingColor
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.52f))
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    if (note.needsReview) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(13.dp)
                )
                Text(stringResource(singleCameraQualityNoteRes(note.reason)), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ResultChip(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = 0.66f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun stateTitle(state: SingleCameraSprintUiState): String {
    val flying = state.configuration.startStyle == SingleCameraSprintStartStyle.FLYING
    return when (val phase = state.phase) {
        SingleCameraSprintPhase.Calibrating -> ""
        SingleCameraSprintPhase.Acquiring -> stringResource(
            when {
                state.isBodyVisibleOutsideTimingZone && flying -> R.string.one_phone_state_runner_past_start
                state.isBodyVisibleOutsideTimingZone && state.configuration.drill == SingleCameraSprintDrill.PRO_AGILITY ->
                    R.string.one_phone_state_move_to_centre
                state.isBodyVisibleOutsideTimingZone -> R.string.one_phone_state_move_to_start
                flying -> R.string.one_phone_state_waiting_runner
                else -> R.string.one_phone_state_step_into_frame
            }
        )
        is SingleCameraSprintPhase.Locking -> stringResource(R.string.one_phone_state_runner_detected)
        is SingleCameraSprintPhase.Settling -> stringResource(
            if (phase.progress < 0.5) R.string.one_phone_state_athlete_detected else R.string.one_phone_state_hold_still
        )
        SingleCameraSprintPhase.Armed -> stringResource(
            if (flying) R.string.one_phone_state_tracking_runner else R.string.one_phone_state_ready
        )
        SingleCameraSprintPhase.Running -> stringResource(
            if (flying) R.string.one_phone_state_flying_started else R.string.one_phone_state_started
        )
        SingleCameraSprintPhase.Completed -> state.result?.let {
            String.format(Locale.US, "%.2f s", it.totalTimeSeconds)
        } ?: ""
        is SingleCameraSprintPhase.Invalid -> stringResource(R.string.one_phone_state_not_recorded)
    }
}

@Composable
private fun stateSubtitle(state: SingleCameraSprintUiState): String? {
    val drill = state.configuration.drill
    val leftToRight = state.direction == SingleCameraSprintDirection.LEFT_TO_RIGHT
    if (drill == SingleCameraSprintDrill.PRO_AGILITY) {
        return when (state.phase) {
            SingleCameraSprintPhase.Running -> stringResource(
                if (state.splits.isEmpty()) R.string.one_phone_subtitle_pro_agility_running_first
                else R.string.one_phone_subtitle_pro_agility_running_next
            )
            SingleCameraSprintPhase.Completed -> stringResource(R.string.one_phone_subtitle_review_touches)
            else -> stringResource(R.string.one_phone_subtitle_pro_agility_setup)
        }
    }
    if (drill == SingleCameraSprintDrill.SHUTTLE && state.phase == SingleCameraSprintPhase.Completed) {
        return stringResource(R.string.one_phone_subtitle_review_touches)
    }
    if (state.configuration.startStyle != SingleCameraSprintStartStyle.FLYING) return null
    return when (state.phase) {
        SingleCameraSprintPhase.Acquiring -> stringResource(
            if (state.isBodyVisibleOutsideTimingZone) {
                if (leftToRight) R.string.one_phone_subtitle_enter_again_left else R.string.one_phone_subtitle_enter_again_right
            } else {
                if (leftToRight) R.string.one_phone_subtitle_enter_left else R.string.one_phone_subtitle_enter_right
            }
        )
        is SingleCameraSprintPhase.Locking -> stringResource(R.string.one_phone_subtitle_confirming_direction)
        SingleCameraSprintPhase.Armed -> stringResource(R.string.one_phone_subtitle_timer_starts_at_zero)
        else -> null
    }
}

@Composable
private fun CalibrationGuidance(state: SingleCameraSprintUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val drill = state.configuration.drill
        if (drill.requiresTurnReview) {
            Capsule(
                text = stringResource(
                    if (drill == SingleCameraSprintDrill.PRO_AGILITY) R.string.one_phone_pro_agility_hint
                    else R.string.one_phone_shuttle_hint
                )
            )
        }
        if (!state.hasAdjustedGates && state.sessionRuns.isEmpty()) {
            Capsule(text = stringResource(R.string.one_phone_setup_hint), borderColor = TrackingColor.copy(alpha = 0.45f))
        }
        if (state.configuration.startStyle == SingleCameraSprintStartStyle.FLYING) {
            FlyingFramingBanner(
                assessment = SingleCameraSprintFraming.assessment(state.gates, state.direction),
                leftToRight = state.direction == SingleCameraSprintDirection.LEFT_TO_RIGHT
            )
        }
    }
}

@Composable
private fun Capsule(text: String, borderColor: Color = Color.Transparent) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.68f))
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .padding(horizontal = 15.dp, vertical = 12.dp)
    )
}

@Composable
private fun FlyingFramingBanner(assessment: SingleCameraSprintFramingAssessment?, leftToRight: Boolean) {
    val isReady = assessment?.isReady == true
    val (title, detail) = when {
        assessment == null -> stringResource(R.string.one_phone_framing_realign_title) to
            stringResource(R.string.one_phone_framing_realign_detail)
        else -> {
            val acquisitionNeeded = max(0.0, SingleCameraSprintFraming.REQUIRED_ACQUISITION_METERS - assessment.acquisitionMeters)
            val exitNeeded = max(0.0, SingleCameraSprintFraming.REQUIRED_EXIT_METERS - assessment.exitMeters)
            when {
                acquisitionNeeded > 0.05 && exitNeeded > 0.05 ->
                    stringResource(R.string.one_phone_framing_move_back_title) to
                        stringResource(R.string.one_phone_framing_move_back_detail, acquisitionNeeded, exitNeeded)
                acquisitionNeeded > 0.05 ->
                    stringResource(R.string.one_phone_framing_aim_start_title) to
                        stringResource(R.string.one_phone_framing_aim_start_detail, acquisitionNeeded)
                exitNeeded > 0.05 ->
                    stringResource(R.string.one_phone_framing_aim_finish_title) to
                        stringResource(R.string.one_phone_framing_aim_finish_detail, exitNeeded)
                else -> stringResource(R.string.one_phone_framing_ready_title) to stringResource(
                    if (leftToRight) R.string.one_phone_framing_ready_detail_left
                    else R.string.one_phone_framing_ready_detail_right
                )
            }
        }
    }
    val accent = if (isReady) TrackingColor else WarningColor
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.68f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Icon(if (isReady) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null, tint = accent)
        Column {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Color.White.copy(alpha = 0.76f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun PhoneMotionBanner(completed: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.72f))
            .border(1.dp, WarningColor.copy(alpha = 0.62f), RoundedCornerShape(50))
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = WarningColor)
        Column {
            Text(stringResource(R.string.one_phone_phone_moved), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(
                    if (completed) R.string.one_phone_phone_moved_completed else R.string.one_phone_phone_moved_running
                ),
                color = Color.White.copy(alpha = 0.76f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color.Black.copy(alpha = 0.76f))
            .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = WarningColor)
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

// endregion

// region Session runs

@Composable
private fun SessionRunShelf(state: SingleCameraSprintUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(Color.Black.copy(alpha = 0.46f))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onOpen),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.one_phone_session_label), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(
                pluralStringResource(R.plurals.one_phone_session_run_count, state.sessionRuns.size, state.sessionRuns.size),
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            state.sessionRuns.takeLast(4).forEach { run ->
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.58f))
                        .border(
                            1.dp,
                            if (run.id == state.currentRunId) TrackingColor.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.15f),
                            RoundedCornerShape(50)
                        )
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("#${run.runNumber}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(String.format(Locale.US, "%.2f", run.result.totalTimeSeconds), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    RunBadge(state, run)
                }
            }
        }
    }
}

@Composable
private fun RunBadge(state: SingleCameraSprintUiState, run: SingleCameraSprintRunRecord) {
    when {
        run.result.quality == com.trackspeed.android.onephone.SingleCameraSprintQuality.REVIEW -> Icon(
            Icons.Default.Error,
            contentDescription = stringResource(R.string.one_phone_review_needs_review),
            tint = WarningColor,
            modifier = Modifier.size(11.dp)
        )
        state.isBestSessionRun(run) -> Icon(
            Icons.Default.Star,
            contentDescription = stringResource(R.string.one_phone_review_best),
            tint = WarningColor,
            modifier = Modifier.size(11.dp)
        )
    }
}

@Composable
private fun SessionReviewDialog(
    state: SingleCameraSprintUiState,
    isFinishing: Boolean,
    onDismiss: () -> Unit,
    onFinish: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 28.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.one_phone_review_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = when {
                            state.persistenceFailed -> stringResource(R.string.one_phone_review_save_failed)
                            state.savedRunCount > 0 -> stringResource(R.string.one_phone_review_saved)
                            else -> pluralStringResource(R.plurals.one_phone_session_run_count, state.sessionRuns.size, state.sessionRuns.size)
                        },
                        color = if (state.persistenceFailed) WarningColor else Color.White.copy(alpha = 0.66f),
                        fontSize = 13.sp
                    )
                }
                if (isFinishing) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.one_phone_keep_going), color = Color.White.copy(alpha = 0.8f))
                    }
                    MetricButton(text = stringResource(R.string.one_phone_review_done), icon = Icons.Default.CheckCircle, primary = true, onClick = onFinish)
                } else {
                    MetricButton(text = stringResource(R.string.one_phone_review_close), icon = Icons.Default.Close, primary = false, onClick = onDismiss)
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sessionRuns.reversed(), key = { it.id }) { run -> ReviewRunRow(state, run) }
            }
        }
    }
}

@Composable
private fun ReviewRunRow(state: SingleCameraSprintUiState, run: SingleCameraSprintRunRecord) {
    val result = run.result
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.one_phone_review_run_number, run.runNumber), color = Color.White.copy(alpha = 0.66f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                RunBadge(state, run)
            }
            Text(
                listOf(
                    stringResource(singleCameraDrillTitleRes(result.drill)),
                    stringResource(singleCameraStartStyleTitleRes(result.startStyle)),
                    if (result.drill == SingleCameraSprintDrill.PRO_AGILITY) {
                        stringResource(R.string.one_phone_distance_pro_agility_short)
                    } else {
                        stringResource(R.string.one_phone_distance_meters, run.distanceMeters.roundToInt())
                    }
                ).joinToString(" · "),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
            if (result.splits.size > 1) {
                Text(
                    result.splits.dropLast(1).joinToString("   ") {
                        "${it.distanceMeters.roundToInt()} m ${String.format(Locale.US, "%.2f", it.elapsedSeconds)}"
                    },
                    color = Color.White.copy(alpha = 0.66f),
                    fontSize = 12.sp
                )
            }
        }
        if (result.peakVelocityMetersPerSecond > 0) {
            ResultChip(
                label = stringResource(R.string.one_phone_split_chip_peak),
                value = stringResource(R.string.one_phone_peak_value, result.peakVelocityMetersPerSecond),
                valueColor = TrackingColor
            )
        }
        Text(
            String.format(Locale.US, "%.2f s", result.totalTimeSeconds),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// endregion
