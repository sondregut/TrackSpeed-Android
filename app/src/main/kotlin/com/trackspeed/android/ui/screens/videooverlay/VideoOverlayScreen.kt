@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.trackspeed.android.ui.screens.videooverlay

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoOverlayScreen(
    onNavigateBack: () -> Unit,
    viewModel: VideoOverlayViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.importVideo(uri)
    }

    LaunchedEffect(uiState.savedMessage) {
        if (uiState.savedMessage != null) {
            delay(2_000)
            viewModel.clearSavedMessage()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.gradientBackground(),
        topBar = {
            TopAppBar(
                title = { Text("Video Overlay", color = TextPrimary) },
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
                            importError = uiState.importError,
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
                            snapshot = uiState.snapshot,
                            showSpeed = uiState.showSpeed,
                            showRunType = uiState.showRunType,
                            exportPhase = uiState.exportPhase,
                            onShowSpeedChange = viewModel::setShowSpeed,
                            onShowRunTypeChange = viewModel::setShowRunType,
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
                                context.startActivity(Intent.createChooser(intent, "Share Video"))
                            }
                        )
                    }
                }
            }

            uiState.savedMessage?.let { message ->
                Text(
                    text = message,
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
            text = "Run not found.",
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ImportStep(
    importError: String?,
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
            text = "Import your run video",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Choose an MP4, MOV, or HEVC clip from your photo library.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        if (importError != null) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = importError,
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
            Text("Choose Video", fontWeight = FontWeight.SemiBold)
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
                text = "Scrub to the frame where the race starts, then tap Set Start Here.",
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
                Text("Set Start Here", fontWeight = FontWeight.SemiBold)
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Choose a different video", color = TextSecondary)
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

    val player = rememberVideoPlayer(video = video, playWhenReady = true)
    var currentTime by remember(video.uri) { mutableDoubleStateOf(0.0) }

    LaunchedEffect(player) {
        while (true) {
            currentTime = player.currentPosition / 1000.0
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
            RaceOverlayView(
                snapshot = snapshot,
                currentTimeSeconds = currentTime,
                modifier = Modifier.fillMaxSize()
            )
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
                    text = "Overlay options",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            if (snapshot.speedDisplay != null) {
                ToggleRow(
                    title = "Show speed",
                    checked = showSpeed,
                    enabled = exportPhase !is VideoExportPhase.Exporting,
                    onCheckedChange = onShowSpeedChange
                )
            }
            ToggleRow(
                title = "Show run type",
                checked = showRunType,
                enabled = exportPhase !is VideoExportPhase.Exporting,
                onCheckedChange = onShowRunTypeChange
            )

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
                Text("Back to start marker", color = TextSecondary)
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
                Text("Create Video", fontWeight = FontWeight.SemiBold)
            }
        }

        is VideoExportPhase.Exporting -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Creating video...",
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
                    Text("Save to Photos", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { onShare(phase.file) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 13.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Share...", color = TextPrimary)
                }
                TextButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text("Re-create with different options", color = TextMuted)
                }
            }
        }

        is VideoExportPhase.Error -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = phase.message,
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
                    Text("Retry", fontWeight = FontWeight.SemiBold)
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
        Text("No video selected.", color = TextSecondary)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack) {
            Text("Choose Video")
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
private fun RaceOverlayView(
    snapshot: VideoOverlaySnapshot,
    currentTimeSeconds: Double,
    modifier: Modifier = Modifier
) {
    val state = snapshot.frameState(currentTimeSeconds)
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TimerPill(state)
            if (state.phase != RaceOverlayPhase.READY && !state.speedDisplay.isNullOrBlank()) {
                OverlayPill(
                    text = state.speedDisplay,
                    background = Color.Black.copy(alpha = 0.46f),
                    textColor = Color.White,
                    fontSize = 16
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.visibleSplits.forEach { split ->
                OverlayPill(
                    text = "${split.label}: ${formatOverlayTime(split.raceTimeSeconds)}",
                    background = AccentBlue.copy(alpha = 0.84f),
                    textColor = Color.White,
                    fontSize = 12
                )
            }
            state.runTypeLabel?.let { label ->
                OverlayPill(
                    text = label,
                    background = Color.Black.copy(alpha = 0.46f),
                    textColor = Color.White,
                    fontSize = 12
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(AccentBlue),
                contentAlignment = Alignment.Center
            ) {
                Text("T", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
private fun TimerPill(state: RaceOverlayFrameState) {
    val text = when (state.phase) {
        RaceOverlayPhase.READY -> "READY"
        RaceOverlayPhase.RUNNING,
        RaceOverlayPhase.FINISHED -> formatOverlayTime(state.displayedTimeSeconds)
    }
    val background = when (state.phase) {
        RaceOverlayPhase.READY -> Color.Black.copy(alpha = 0.46f)
        RaceOverlayPhase.RUNNING -> AccentBlue.copy(alpha = 0.84f)
        RaceOverlayPhase.FINISHED -> AccentGreen.copy(alpha = 0.84f)
    }
    OverlayPill(
        text = text,
        background = background,
        textColor = Color.White,
        fontSize = if (state.phase == RaceOverlayPhase.READY) 18 else 28,
        monospaced = state.phase != RaceOverlayPhase.READY
    )
}

@Composable
private fun OverlayPill(
    text: String,
    background: Color,
    textColor: Color,
    fontSize: Int,
    monospaced: Boolean = false
) {
    Text(
        text = text,
        color = textColor,
        maxLines = 1,
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default
        ),
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = if (fontSize >= 18) 18.dp else 11.dp, vertical = if (fontSize >= 18) 7.dp else 5.dp)
    )
}
