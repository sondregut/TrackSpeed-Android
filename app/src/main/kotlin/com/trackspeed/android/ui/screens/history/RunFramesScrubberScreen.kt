package com.trackspeed.android.ui.screens.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.repository.SessionRepository
import com.trackspeed.android.ui.theme.AccentBlue
import com.trackspeed.android.ui.theme.SurfaceDark
import com.trackspeed.android.ui.theme.TextMuted
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary
import com.trackspeed.android.ui.theme.gradientBackground
import com.trackspeed.android.ui.util.RetainedFlowLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import com.trackspeed.android.R

private val ScrubberGreen = Color(0xFF4CAF50)
private val ScrubberRed = Color(0xFFFF3B30)
private val ScrubberWarning = Color(0xFFFF9500)
private val OverlayCyan = Color(0xFF00D4FF)

data class RunFramesScrubberUiState(
    val run: RunEntity? = null,
    val frames: List<LocalGateFrameData> = emptyList(),
    val calibrationSnapshots: List<LocalGateCalibrationSnapshot> = emptyList(),
    val isLoading: Boolean = true,
    val hasLoadedData: Boolean = run != null,
    val loadFailed: Boolean = false
)

@Serializable
data class LocalGateFrameData(
    val imagePath: String,
    val frameNumber: Long = 0,
    val timestampInterval: Double = 0.0,
    val occupancy: Float = 0f,
    val longestRun: Int = 0,
    val isTracking: Boolean = false,
    val torsoTop: Int = 0,
    val torsoBottom: Int = 0,
    val frameHeight: Int = 0,
    val leftShoulderY: Float? = null,
    val rightShoulderY: Float? = null,
    val leftHipY: Float? = null,
    val rightHipY: Float? = null,
    val runStartY: Int = 0,
    val runEndY: Int = 0
)

@Serializable
data class LocalGateCalibrationSnapshot(
    val gateIndex: Int = 0,
    val role: String = "",
    val gatePosition: Double = 0.5,
    val velocityPxPerSec: Double = 0.0,
    val crossingDirection: String? = null,
    val workWidth: Int? = null,
    val thumbnailDebugJson: String? = null
)

@HiltViewModel
class RunFramesScrubberViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val runId: String = checkNotNull(savedStateHandle["runId"])

    private val _uiState = MutableStateFlow(RunFramesScrubberUiState())
    val uiState: StateFlow<RunFramesScrubberUiState> = _uiState.asStateFlow()

    private val detailLoader = RetainedFlowLoader(viewModelScope) {
        flow { emit(loadedFrameScrubberState(sessionRepository.getRunById(runId))) }
    }

    init {
        viewModelScope.launch {
            detailLoader.state.collect { loaded ->
                _uiState.update { previous -> mergeFrameScrubberLoadState(previous, loaded) }
            }
        }
    }

    fun retryLoading() = detailLoader.retry()

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunFramesScrubberScreen(
    onNavigateBack: () -> Unit,
    viewModel: RunFramesScrubberViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    RunFramesScrubberScreenContent(uiState, onNavigateBack, viewModel::retryLoading)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunFramesScrubberScreenContent(
    uiState: RunFramesScrubberUiState,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit
) {
    var showOverlays by rememberSaveable { mutableStateOf(true) }
    var currentIndex by remember { mutableIntStateOf(0) }

    val frames = uiState.frames
    // A refreshed list may shrink before the selection effect runs.
    val selectedIndex = currentIndex.coerceIn(0, (frames.size - 1).coerceAtLeast(0))
    val crossingFrameIndex = remember(frames) {
        frames.indices.maxByOrNull { frames[it].occupancy } ?: 0
    }

    LaunchedEffect(frames) {
        currentIndex = crossingFrameIndex.coerceIn(0, (frames.size - 1).coerceAtLeast(0))
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.gradientBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.frame_scrubber_title), color = TextPrimary)
                        uiState.run?.localGateRole?.takeIf { it.isNotBlank() }?.let { role ->
                            Text(
                                text = role.replaceFirstChar { it.titlecase(Locale.US) },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showOverlays = !showOverlays }) {
                        Icon(
                            imageVector = if (showOverlays) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = stringResource(
                                if (showOverlays) {
                                    R.string.frame_scrubber_hide_overlays_cd
                                } else {
                                    R.string.frame_scrubber_show_overlays_cd
                                }
                            ),
                            tint = if (showOverlays) TextPrimary else TextMuted
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            if (!uiState.hasLoadedData) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DetailLoadFeedback(uiState.isLoading, uiState.loadFailed, false, onRetry)
                }
            } else {
                if (uiState.isLoading || uiState.loadFailed) {
                    DetailLoadFeedback(uiState.isLoading, uiState.loadFailed, true, onRetry)
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val contentPadding = PaddingValues(0.dp)
                    val run = uiState.run
                    when {
                        run == null -> EmptyScrubberState(
                            paddingValues = contentPadding,
                            title = stringResource(R.string.frame_scrubber_run_missing_title),
                            message = stringResource(R.string.frame_scrubber_run_missing_body)
                        )
                        frames.isNotEmpty() -> FrameScrubberContent(
                            paddingValues = contentPadding,
                            run = run,
                            frames = frames,
                            currentIndex = selectedIndex,
                            crossingFrameIndex = crossingFrameIndex,
                            showOverlays = showOverlays,
                            onIndexChange = { currentIndex = it.coerceIn(0, frames.lastIndex) }
                        )
                        uiState.calibrationSnapshots.isNotEmpty() -> CalibrationSnapshotContent(
                            paddingValues = contentPadding,
                            snapshots = uiState.calibrationSnapshots
                        )
                        else -> EmptyScrubberState(
                            paddingValues = contentPadding,
                            title = stringResource(R.string.frame_scrubber_no_data_title),
                            message = stringResource(R.string.frame_scrubber_no_data_body)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyScrubberState(
    paddingValues: PaddingValues,
    title: String,
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(42.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary
            )
            Text(
                text = message,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun FrameScrubberContent(
    paddingValues: PaddingValues,
    run: RunEntity,
    frames: List<LocalGateFrameData>,
    currentIndex: Int,
    crossingFrameIndex: Int,
    showOverlays: Boolean,
    onIndexChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val frame = frames[currentIndex]
    val scope = rememberCoroutineScope()
    val imageLoader = remember(context, scope) {
        ScrubberImageLoader<Bitmap>(scope) { imagePath ->
            withContext(Dispatchers.IO) { loadFrameBitmap(context, imagePath) }
        }
    }
    val imageState by imageLoader.state.collectAsState()
    LaunchedEffect(frame.imagePath, imageLoader) { imageLoader.select(frame.imagePath) }
    val selectedImage = imageState.forSelection(frame.imagePath)
    val gatePosition = remember(run.localGateRole, run.startGatePosition, run.finishGatePosition, run.gatePosition) {
        gatePositionForRun(run)
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(12.dp)
    ) {
        val minimumImageHeight = 200.dp
        val sectionSpacing = 10.dp
        // A short multi-window viewport cannot show both panes usefully.
        // Scroll the entire stack there; never put a weighted image in it.
        val scrollWholeContent = maxHeight < minimumImageHeight + 160.dp
        val contentScrollState = rememberScrollState()
        val controlsScrollState = rememberScrollState()
        val controlsMaxHeight = maxHeight - minimumImageHeight - sectionSpacing

        Column(
            modifier = Modifier.fillMaxSize().then(
                if (scrollWholeContent) Modifier.verticalScroll(contentScrollState) else Modifier
            ),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing)
        ) {
            FrameImagePanel(
                bitmap = selectedImage.image,
                isLoading = selectedImage.isLoading,
                onRetry = imageLoader::retry,
                frame = frame,
                run = run,
                gatePosition = gatePosition,
                showOverlays = showOverlays,
                isCrossingFrame = currentIndex == crossingFrameIndex,
                modifier = Modifier.fillMaxWidth().then(
                    if (scrollWholeContent) Modifier.height(minimumImageHeight) else Modifier.weight(1f)
                )
            )
            Column(
                modifier = Modifier.fillMaxWidth().then(
                    if (scrollWholeContent) Modifier else Modifier
                        .heightIn(max = controlsMaxHeight)
                        .verticalScroll(controlsScrollState)
                ),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)
            ) {
                // Bound growing text/cards before measuring the weighted
                // image, so accessibility text cannot consume its viewport.
                FrameSummaryRow(frame = frame, currentIndex = currentIndex, frameCount = frames.size,
                    isCrossingFrame = currentIndex == crossingFrameIndex)
                FrameScrubberControls(currentIndex = currentIndex, frameCount = frames.size,
                    crossingFrameIndex = crossingFrameIndex, onIndexChange = onIndexChange)
            }
        }
    }
}

@Composable
private fun FrameImagePanel(
    bitmap: Bitmap?,
    isLoading: Boolean,
    onRetry: () -> Unit,
    frame: LocalGateFrameData,
    run: RunEntity,
    gatePosition: Float,
    showOverlays: Boolean,
    isCrossingFrame: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val currentBitmap = bitmap
            if (currentBitmap != null) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.frame_scrubber_gate_frame_cd),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                Canvas(modifier = Modifier.matchParentSize()) {
                    val imageRect = fittedImageRect(
                        containerWidth = size.width,
                        containerHeight = size.height,
                        imageWidth = currentBitmap.width,
                        imageHeight = currentBitmap.height
                    )
                    val gateX = imageRect.left + imageRect.width * gatePosition
                    drawLine(
                        color = ScrubberRed.copy(alpha = 0.9f),
                        start = Offset(gateX, imageRect.top),
                        end = Offset(gateX, imageRect.bottom),
                        strokeWidth = 4f
                    )
                    if (showOverlays && frame.frameHeight > 0) {
                        val runStart = frame.runStartY.coerceIn(0, frame.frameHeight)
                        val runEnd = frame.runEndY.coerceIn(runStart, frame.frameHeight)
                        if (frame.longestRun > 0 && runEnd > runStart) {
                            val top = imageRect.top + imageRect.height * (runStart.toFloat() / frame.frameHeight)
                            val bottom = imageRect.top + imageRect.height * (runEnd.toFloat() / frame.frameHeight)
                            drawRect(
                                color = ScrubberRed.copy(alpha = 0.38f),
                                topLeft = Offset(gateX - 10f, top),
                                size = androidx.compose.ui.geometry.Size(20f, (bottom - top).coerceAtLeast(5f))
                            )
                        }

                        val torsoTop = frame.torsoTop.coerceIn(0, frame.frameHeight)
                        val torsoBottom = frame.torsoBottom.coerceIn(torsoTop, frame.frameHeight)
                        if (torsoBottom > torsoTop) {
                            val top = imageRect.top + imageRect.height * (torsoTop.toFloat() / frame.frameHeight)
                            val bottom = imageRect.top + imageRect.height * (torsoBottom.toFloat() / frame.frameHeight)
                            drawRect(
                                color = OverlayCyan.copy(alpha = 0.64f),
                                topLeft = Offset(gateX - 30f, top),
                                size = androidx.compose.ui.geometry.Size(60f, bottom - top),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                            )
                        }
                    }
                }
            } else if (isLoading) {
                CircularProgressIndicator(color = TextPrimary)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(stringResource(R.string.frame_scrubber_image_unavailable), color = TextSecondary)
                    Button(onClick = onRetry) { Text(stringResource(R.string.paywall_retry)) }
                    Text(
                        text = stringResource(R.string.parity_replay_image_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }

            if (currentBitmap != null && showOverlays && isCrossingFrame) {
                CrossingMetricsStrip(
                    run = run,
                    frame = frame,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        }
    }
}

@Composable
private fun CrossingMetricsStrip(
    run: RunEntity,
    frame: LocalGateFrameData,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.74f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricLabel(stringResource(R.string.frame_scrubber_metric_occupancy_short), percent(frame.occupancy.toDouble()))
            MetricLabel(stringResource(R.string.frame_scrubber_metric_run_short), frame.longestRun.toString())
            MetricLabel(stringResource(R.string.frame_scrubber_metric_velocity_short), run.crossingVelocityLabel())
            run.finishInterpolationAlpha?.let { MetricLabel("alpha", decimal(it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            run.finishS0?.let { MetricLabel("s0", decimal(it)) }
            run.finishS1?.let { MetricLabel("s1", decimal(it)) }
            (run.finishCrossingDirection ?: run.startCrossingDirection)?.let {
                MetricLabel(stringResource(R.string.frame_scrubber_metric_direction_short), it)
            }
        }
    }
}

@Composable
private fun MetricLabel(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = TextPrimary
        )
    }
}

@Composable
private fun FrameSummaryRow(
    frame: LocalGateFrameData,
    currentIndex: Int,
    frameCount: Int,
    isCrossingFrame: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.9f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(
                        R.string.frame_scrubber_frame_position,
                        currentIndex + 1,
                        frameCount
                    ),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary
                )
                Text(
                    text = stringResource(
                        R.string.frame_scrubber_occupancy_value,
                        percent(frame.occupancy.toDouble())
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
            FrameStateBadge(frame = frame, isCrossingFrame = isCrossingFrame)
        }
    }
}

@Composable
private fun FrameStateBadge(frame: LocalGateFrameData, isCrossingFrame: Boolean) {
    val (label, color) = when {
        isCrossingFrame -> stringResource(R.string.frame_scrubber_status_crossing) to ScrubberWarning
        frame.occupancy >= 0.15f -> stringResource(R.string.frame_scrubber_status_in_frame) to ScrubberGreen
        frame.isTracking -> stringResource(R.string.frame_scrubber_status_tracking) to AccentBlue
        else -> stringResource(R.string.frame_scrubber_status_clear) to TextMuted
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun FrameScrubberControls(
    currentIndex: Int,
    frameCount: Int,
    crossingFrameIndex: Int,
    onIndexChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.9f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val framePosition = stringResource(R.string.parity_replay_frame_position, currentIndex + 1, frameCount)
            Slider(
                modifier = Modifier.semantics { contentDescription = framePosition },
                enabled = frameCount > 1,
                value = currentIndex.toFloat(),
                onValueChange = { onIndexChange(it.toInt()) },
                valueRange = 0f..((frameCount - 1).coerceAtLeast(1).toFloat()),
                steps = (frameCount - 2).coerceAtLeast(0)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onIndexChange(currentIndex - 1) },
                    enabled = currentIndex > 0
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.parity_replay_previous_frame))
                }
                Button(
                    onClick = { onIndexChange(crossingFrameIndex) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ScrubberWarning)
                ) {
                    Icon(Icons.Default.GpsFixed, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.run_detail_crossing_label))
                }
                OutlinedButton(
                    onClick = { onIndexChange(currentIndex + 1) },
                    enabled = currentIndex < frameCount - 1
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.parity_replay_next_frame))
                }
            }
        }
    }
}

@Composable
private fun CalibrationSnapshotContent(
    paddingValues: PaddingValues,
    snapshots: List<LocalGateCalibrationSnapshot>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.frame_scrubber_calibration_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary
            )
            Text(
                text = stringResource(R.string.frame_scrubber_calibration_body),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        items(snapshots) { snapshot ->
            CalibrationSnapshotCard(snapshot)
        }
    }
}

@Composable
private fun CalibrationSnapshotCard(snapshot: LocalGateCalibrationSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.9f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(AccentBlue.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = snapshot.gateIndex.toString(),
                        color = AccentBlue,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Column {
                    Text(
                        text = snapshot.role.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.frame_scrubber_gate_default),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(
                            R.string.frame_scrubber_gate_position,
                            percent(snapshot.gatePosition)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SnapshotMetric(
                    stringResource(R.string.frame_scrubber_velocity),
                    stringResource(
                        R.string.frame_scrubber_velocity_value,
                        decimal(snapshot.velocityPxPerSec, 0)
                    )
                )
                snapshot.workWidth?.let {
                    SnapshotMetric(stringResource(R.string.frame_scrubber_work_width), it.toString())
                }
                snapshot.crossingDirection?.let {
                    SnapshotMetric(stringResource(R.string.frame_scrubber_direction), it)
                }
            }
        }
    }
}

@Composable
private fun SnapshotMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary
        )
    }
}

fun RunEntity.hasFrameScrubberPayload(): Boolean = !localGateFramesDataJson.isNullOrBlank()

private fun loadFrameBitmap(context: Context, imagePath: String): Bitmap? {
    val candidates = buildList {
        if (imagePath.startsWith("file://")) {
            Uri.parse(imagePath).path?.let { add(File(it)) }
        }
        add(File(imagePath))
        add(File(context.filesDir, imagePath))
        add(File(context.filesDir.parentFile, imagePath))
    }

    return candidates
        .firstOrNull { it.exists() && it.isFile }
        ?.absolutePath
        ?.let { BitmapFactory.decodeFile(it) }
}

private fun gatePositionForRun(run: RunEntity): Float {
    val role = run.localGateRole?.lowercase(Locale.US).orEmpty()
    val position = when {
        "start" in role -> run.startGatePosition ?: run.gatePosition
        "finish" in role -> run.finishGatePosition ?: run.gatePosition
        else -> run.gatePosition
    }
    return position.toFloat().coerceIn(0f, 1f)
}

private fun fittedImageRect(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Int,
    imageHeight: Int
): Rect {
    if (imageWidth <= 0 || imageHeight <= 0 || containerWidth <= 0f || containerHeight <= 0f) {
        return Rect(0f, 0f, containerWidth, containerHeight)
    }
    val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
    val containerAspect = containerWidth / containerHeight
    val drawWidth: Float
    val drawHeight: Float
    if (imageAspect > containerAspect) {
        drawWidth = containerWidth
        drawHeight = containerWidth / imageAspect
    } else {
        drawHeight = containerHeight
        drawWidth = containerHeight * imageAspect
    }
    val left = (containerWidth - drawWidth) / 2f
    val top = (containerHeight - drawHeight) / 2f
    return Rect(left, top, left + drawWidth, top + drawHeight)
}

private fun RunEntity.crossingVelocityLabel(): String {
    val velocity = finishCrossingVelocity ?: crossingVelocity ?: startCrossingVelocity
    return velocity?.let { "${decimal(it, 0)} px/s" } ?: "--"
}

private fun percent(value: Double, digits: Int = 0): String = "${decimal(value * 100.0, digits)}%"

private fun decimal(value: Double, digits: Int = 2): String =
    String.format(Locale.US, "%.${digits}f", value)
