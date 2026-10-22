@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.trackspeed.android.ui.screens.videooverlay

import android.content.Intent
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.trackspeed.android.ui.theme.AccentBlue
import com.trackspeed.android.ui.theme.AccentGreen
import com.trackspeed.android.ui.theme.CardBackground
import com.trackspeed.android.ui.theme.DividerColor
import com.trackspeed.android.ui.theme.StatusRed
import com.trackspeed.android.ui.theme.SurfaceDark
import com.trackspeed.android.ui.theme.TextMuted
import com.trackspeed.android.ui.theme.TextPrimary
import com.trackspeed.android.ui.theme.TextSecondary
import com.trackspeed.android.ui.theme.gradientBackground
import com.trackspeed.android.videooverlay.ImportedVideo
import com.trackspeed.android.videooverlay.RaceOverlayFrameState
import com.trackspeed.android.videooverlay.RaceOverlayPhase
import com.trackspeed.android.videooverlay.VideoOverlaySnapshot
import com.trackspeed.android.videooverlay.formatOverlayTime
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt
import com.trackspeed.android.R
import com.trackspeed.android.videooverlay.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoOverlayScreen(
    onNavigateBack: () -> Unit,
    viewModel: VideoOverlayViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.video_overlay_share_chooser)
    val localizedSnapshot = uiState.snapshot?.let(viewModel::localizeSnapshot)

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.importVideo(uri)
    }

    LaunchedEffect(uiState.savedMessageRes) {
        if (uiState.savedMessageRes != null) {
            delay(2_000)
            viewModel.clearSavedMessage()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.gradientBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.video_overlay_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> LoadingState()
                uiState.run == null -> MissingRunState()
                else -> {
                    when (uiState.step) {
                        VideoOverlayStep.IMPORT -> ImportStep(
                            importErrorRes = uiState.importErrorRes,
                            onChooseVideo = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            }
                        )

                        VideoOverlayStep.MARK_START -> MarkStartStep(
                            video = uiState.importedVideo,
                            onBack = { viewModel.setStep(VideoOverlayStep.IMPORT) },
                            onMarked = { viewModel.markStart(it) }
                        )

                        VideoOverlayStep.PREVIEW -> PreviewStep(
                            video = uiState.importedVideo,
                            snapshot = localizedSnapshot,
                            showSpeed = uiState.showSpeed,
                            showRunType = uiState.showRunType,
                            exportPhase = uiState.exportPhase,
                            onShowSpeedChange = viewModel::setShowSpeed,
                            onShowRunTypeChange = viewModel::setShowRunType,
                            onPreferencesChange = viewModel::setOverlayPreferences,
                            onBack = { viewModel.setStep(VideoOverlayStep.MARK_START) },
                            onExport = viewModel::exportVideo,
                            onRetry = viewModel::retryExport,
                            onSave = viewModel::saveExportedVideo,
                            onShare = { file ->
                                val shareUri = viewModel.shareUri(file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                            }
                        )
                    }
                }
            }

            uiState.savedMessageRes?.let { messageRes ->
                Text(
                    text = stringResource(messageRes),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(AccentGreen, CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = TextPrimary)
    }
}

@Composable
private fun MissingRunState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.video_overlay_run_not_found),
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ImportStep(
    @StringRes importErrorRes: Int?,
    onChooseVideo: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Videocam,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.video_overlay_import_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.video_overlay_import_body),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        if (importErrorRes != null) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(importErrorRes),
                color = StatusRed,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onChooseVideo,
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Text(stringResource(R.string.video_overlay_choose_video), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MarkStartStep(
    video: ImportedVideo?,
    onBack: () -> Unit,
    onMarked: (Double) -> Unit
) {
    if (video == null) {
        MissingVideoState(onBack = onBack)
        return
    }

    val player = rememberVideoPlayer(video = video, playWhenReady = false)
    var currentTime by remember(video.uri) { mutableDoubleStateOf(0.0) }
    var isSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            if (!isSeeking) {
                currentTime = player.currentPosition / 1000.0
            }
            delay(33)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            PlayerSurface(
                player = player,
                useController = true,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.video_overlay_mark_start_hint),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Slider(
                value = currentTime.toFloat().coerceIn(0f, video.durationSeconds.toFloat().coerceAtLeast(0.01f)),
                onValueChange = { value ->
                    isSeeking = true
                    currentTime = value.toDouble()
                    player.seekTo((value * 1000).toLong())
                },
                onValueChangeFinished = {
                    isSeeking = false
                    player.pause()
                },
                valueRange = 0f..video.durationSeconds.toFloat().coerceAtLeast(0.01f)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatOverlayTime(currentTime),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "/ ${formatOverlayTime(video.durationSeconds)}",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
            }

            Button(
                onClick = { onMarked(currentTime) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text(stringResource(R.string.video_overlay_set_start), fontWeight = FontWeight.SemiBold)
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.video_overlay_choose_different), color = TextSecondary)
            }
        }
    }
}

@Composable
private fun PreviewStep(
    video: ImportedVideo?,
    snapshot: VideoOverlaySnapshot?,
    showSpeed: Boolean,
    showRunType: Boolean,
    exportPhase: VideoExportPhase,
    onShowSpeedChange: (Boolean) -> Unit,
    onShowRunTypeChange: (Boolean) -> Unit,
    onPreferencesChange: (OverlayPreferences) -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onShare: (File) -> Unit
) {
    if (video == null || snapshot == null) {
        MissingVideoState(onBack = onBack)
        return
    }

    var showOptions by remember { mutableStateOf(false) }
    var showGuides by remember { mutableStateOf(false) }
    if (showOptions) OverlayOptionsDialog(snapshot.preferences,
        onChange = onPreferencesChange, onDismiss = { showOptions = false })
    val player = rememberVideoPlayer(video = video, playWhenReady = true)
    var currentTime by remember(video.uri) { mutableDoubleStateOf(0.0) }

    LaunchedEffect(player) {
        while (true) {
            currentTime = player.currentPosition / 1000.0
            delay(33)
        }
    }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            // The preview and exporter use the same output canvas. FIT preserves the
            // whole athlete when landscape footage is shared on a portrait canvas.
            val displayAspect = if (snapshot.preferences.canvas == OverlayCanvas.SOCIAL_9_16) 9f / 16f else video.displayAspectRatio
            val fittedWidth = minOf(maxWidth, maxHeight * displayAspect)
            val fittedHeight = fittedWidth / displayAspect
            Box(Modifier.align(Alignment.Center).size(fittedWidth, fittedHeight).background(Color.Black)) {
                PlayerSurface(player = player, useController = true, modifier = Modifier.fillMaxSize())
                RaceOverlayView(snapshot = snapshot, currentTimeSeconds = currentTime,
                    guides = showGuides, modifier = Modifier.fillMaxSize())
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = TextSecondary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.video_overlay_options),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showOptions = true }, enabled = exportPhase !is VideoExportPhase.Exporting,
                    modifier = Modifier.weight(1f)) { Text(stringResource(R.string.overlay_edit_metrics)) }
                OutlinedButton(onClick = { showGuides = !showGuides }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (showGuides) R.string.overlay_hide_guides else R.string.overlay_safe_guides))
                }
            }

            HorizontalDivider(color = DividerColor)

            ExportActions(
                phase = exportPhase,
                onExport = onExport,
                onRetry = onRetry,
                onSave = onSave,
                onShare = onShare
            )

            TextButton(
                onClick = onBack,
                enabled = exportPhase !is VideoExportPhase.Exporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.video_overlay_back_to_marker), color = TextSecondary)
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextSecondary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ExportActions(
    phase: VideoExportPhase,
    onExport: () -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onShare: (File) -> Unit
) {
    when (phase) {
        VideoExportPhase.Idle -> {
            Button(
                onClick = onExport,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text(stringResource(R.string.video_overlay_create), fontWeight = FontWeight.SemiBold)
            }
        }

        is VideoExportPhase.Exporting -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.video_overlay_creating),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${(phase.progress * 100).roundToInt()}%",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
                LinearProgressIndicator(
                    progress = { phase.progress.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentBlue,
                    trackColor = DividerColor
                )
            }
        }

        is VideoExportPhase.Ready -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 13.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.video_overlay_save_photos), fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { onShare(phase.file) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 13.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.video_overlay_share), color = TextPrimary)
                }
                TextButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.video_overlay_recreate), color = TextMuted)
                }
            }
        }

        is VideoExportPhase.Error -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(phase.messageRes),
                    color = StatusRed,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 13.dp)
                ) {
                    Text(stringResource(R.string.video_overlay_retry), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun MissingVideoState(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.video_overlay_no_video), color = TextSecondary)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack) {
            Text(stringResource(R.string.video_overlay_choose_video))
        }
    }
}

@Composable
private fun rememberVideoPlayer(video: ImportedVideo, playWhenReady: Boolean): ExoPlayer {
    val context = LocalContext.current
    val player = remember(video.uri) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(video.uri))
            prepare()
        }
    }

    LaunchedEffect(player, playWhenReady) {
        player.playWhenReady = playWhenReady
        if (playWhenReady) player.play() else player.pause()
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    return player
}

@Composable
private fun PlayerSurface(
    player: ExoPlayer,
    useController: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                this.useController = useController
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { view ->
            view.player = player
            view.useController = useController
        }
    )
}

@Composable
private fun RaceOverlayView(snapshot: VideoOverlaySnapshot, currentTimeSeconds: Double,
    modifier: Modifier = Modifier, guides: Boolean = false) {
    val renderer = remember { RaceOverlayCanvasRenderer() }
    Canvas(modifier) {
        drawIntoCanvas { renderer.draw(it.nativeCanvas, snapshot, currentTimeSeconds, guides, size.width, size.height) }
    }
}

@Composable
private fun OverlayOptionsDialog(options: OverlayPreferences, onChange: (OverlayPreferences) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.overlay_edit_metrics)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.overlay_canvas))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverlayCanvas.entries.forEach { canvas ->
                        FilterChip(selected = options.canvas == canvas, onClick = { onChange(options.copy(canvas = canvas)) },
                            label = { Text(stringResource(if (canvas == OverlayCanvas.SOCIAL_9_16) R.string.overlay_canvas_social else R.string.overlay_canvas_original)) })
                    }
                }
                Text(stringResource(R.string.overlay_speed_units))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverlaySpeedUnit.entries.forEach { unit ->
                        FilterChip(selected = options.speedUnit == unit, onClick = { onChange(options.copy(speedUnit = unit)) }, label = { Text(unit.label) })
                    }
                }
                ToggleRow(stringResource(R.string.overlay_show_time), options.showTime, true) { onChange(options.copy(showTime = it)) }
                ToggleRow(stringResource(R.string.overlay_average_speed), options.showSpeed, true) { onChange(options.copy(showSpeed = it)) }
                ToggleRow(stringResource(R.string.video_overlay_show_run_type), options.showRunType, true) { onChange(options.copy(showRunType = it)) }
                Text(stringResource(R.string.overlay_splits))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverlaySplitMode.entries.forEach { mode ->
                        val label = when (mode) { OverlaySplitMode.OFF -> R.string.overlay_splits_off
                            OverlaySplitMode.CUMULATIVE -> R.string.overlay_splits_cumulative
                            OverlaySplitMode.SEGMENTS -> R.string.overlay_splits_segments }
                        FilterChip(selected = options.splitMode == mode, onClick = { onChange(options.copy(splitMode = mode)) }, label = { Text(stringResource(label)) })
                    }
                }
                Text(stringResource(R.string.overlay_position))
                OverlayPosition.entries.chunked(2).forEach { positions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        positions.forEach { position ->
                            val label = when (position) {
                                OverlayPosition.TOP_LEFT -> R.string.overlay_top_left; OverlayPosition.TOP_RIGHT -> R.string.overlay_top_right
                                OverlayPosition.BOTTOM_LEFT -> R.string.overlay_bottom_left; OverlayPosition.BOTTOM_RIGHT -> R.string.overlay_bottom_right }
                            FilterChip(selected = options.position == position, onClick = { onChange(options.copy(position = position)) }, label = { Text(stringResource(label)) })
                        }
                    }
                }
                ToggleRow(stringResource(R.string.overlay_social_safe), options.socialSafe, true) { onChange(options.copy(socialSafe = it)) }
                Text(stringResource(R.string.overlay_safe_help), style = MaterialTheme.typography.bodySmall)
            }
        })
}
