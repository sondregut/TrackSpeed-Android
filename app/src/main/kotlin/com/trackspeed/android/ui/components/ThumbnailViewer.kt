package com.trackspeed.android.ui.components

import androidx.annotation.StringRes
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.R
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Data class representing a thumbnail that can be expanded.
 */
data class ExpandedThumbnail(
    val bitmap: Bitmap,
    val gatePosition: Float? = null,
    val detectorYPosition: Float? = null,
    val reviewTarget: DetectionReviewTarget? = null,
    val onReviewSubmitted: ((DetectionReviewSubmission) -> Unit)? = null
)

data class DetectionReviewTarget(
    val sessionId: String?,
    val runId: String,
    val runNumber: Int,
    val numberOfPhones: Int = 1,
    val gateLabel: String,
    val target: String,
    val mode: String,
    val distanceMeters: Double? = null,
    val startType: String? = null,
    val displayedTimeSeconds: Double? = null,
    val originalGatePosition: Float? = null,
    val crossingDirection: String?,
    val detectorX: Float,
    val detectorY: Float? = null,
    val crossingVelocityPxPerSec: Double? = null,
    val workWidth: Int? = null,
    val interpolationAlpha: Double? = null,
    val framePick: String? = null,
    val s0: Double? = null,
    val s1: Double? = null,
    val isFrontCamera: Boolean? = null,
    val detectionDistance: String? = null,
    val exposureMs: Double? = null,
    val iso: Int? = null,
    val detectorTriggerFramePts: Long? = null,
    val chosenThumbnailFramePts: Long? = null,
    val savedThumbnailFramePts: Long? = null
)

data class DetectionReviewSubmission(
    val target: DetectionReviewTarget,
    val issue: String,
    val actualX: Float?,
    val actualY: Float?,
    val note: String,
    val rawMessage: String,
    val rawImageData: ByteArray? = null,
    val reviewImageData: ByteArray? = null
)

private data class ReviewIssue(
    val rawValue: String,
    @StringRes val labelRes: Int
)

private val reviewIssues = listOf(
    ReviewIssue("good", R.string.thumbnail_review_good),
    ReviewIssue("early", R.string.thumbnail_review_early),
    ReviewIssue("late", R.string.thumbnail_review_late),
    ReviewIssue("arm", R.string.thumbnail_review_arm),
    ReviewIssue("leg", R.string.thumbnail_review_leg),
    ReviewIssue("wrongFrame", R.string.thumbnail_review_wrong_frame),
    ReviewIssue("blur", R.string.thumbnail_review_blur),
    ReviewIssue("thumbnail", R.string.thumbnail_review_thumbnail),
    ReviewIssue("other", R.string.thumbnail_review_other)
)

/**
 * Fullscreen overlay dialog for viewing a thumbnail at full size with a gate line overlay.
 */
@Composable
fun ThumbnailViewerDialog(
    thumbnail: ExpandedThumbnail?,
    onDismiss: () -> Unit
) {
    if (thumbnail != null) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                StaticGateViewer(thumbnail = thumbnail)
            }
        }
    }
}

@Composable
private fun StaticGateViewer(thumbnail: ExpandedThumbnail) {
    var actualPoint by remember(thumbnail) { mutableStateOf<Offset?>(null) }
    var actualTapPointPx by remember(thumbnail) { mutableStateOf<Offset?>(null) }
    var actualViewSizePx by remember(thumbnail) { mutableStateOf<IntSize?>(null) }
    var selectedIssue by remember(thumbnail) { mutableStateOf("unlabeled") }
    var note by remember(thumbnail) { mutableStateOf("") }
    val reviewTarget = thumbnail.reviewTarget
    val reviewSubmit = thumbnail.onReviewSubmitted

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .onSizeChanged { actualViewSizePx = it }
                .then(
                    if (reviewTarget != null) {
                        Modifier.pointerInput(reviewTarget) {
                            detectTapGestures { offset ->
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val height = size.height.toFloat().coerceAtLeast(1f)
                                actualTapPointPx = offset
                                actualViewSizePx = size
                                actualPoint = Offset(
                                    x = (offset.x / width).coerceIn(0f, 1f),
                                    y = (offset.y / height).coerceIn(0f, 1f)
                                )
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    // Consume click
                }
        ) {
            Image(
                bitmap = thumbnail.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.thumbnail_viewer_cd),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            )

            Canvas(modifier = Modifier.matchParentSize()) {
                val gatePosition = thumbnail.gatePosition
                if (gatePosition != null) {
                    val x = size.width * gatePosition
                    drawLine(
                        color = Color.Red.copy(alpha = 0.85f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 3f
                    )

                    val detectorY = thumbnail.detectorYPosition
                    if (detectorY != null) {
                        drawCircle(
                            color = Color.Yellow,
                            radius = 8f,
                            center = Offset(x, size.height * detectorY)
                        )
                    }
                }

                actualPoint?.let { point ->
                    drawCircle(
                        color = Color.Green,
                        radius = 10f,
                        center = Offset(size.width * point.x, size.height * point.y)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.95f),
                        radius = 10f,
                        center = Offset(size.width * point.x, size.height * point.y),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                    )
                }
            }
        }

        if (reviewTarget != null && reviewSubmit != null) {
            ReviewControls(
                selectedIssue = selectedIssue,
                note = note,
                hasPoint = actualPoint != null,
                onIssueSelected = { issue ->
                    selectedIssue = if (selectedIssue == issue) "unlabeled" else issue
                },
                onNoteChanged = { note = it.take(160) },
                onSubmit = {
                    val actual = actualPoint
                    val detectorX = reviewTarget.detectorX
                    val detectorY = reviewTarget.detectorY
                    val deltaX = actual?.let { it.x - detectorX }
                    val deltaY = actual?.let { point ->
                        detectorY?.let { point.y - it }
                    }
                    val tapPoint = actualTapPointPx
                    val viewSize = actualViewSizePx
                    val imageWidth = thumbnail.bitmap.width
                    val imageHeight = thumbnail.bitmap.height
                    val actualPointLog = actual?.let {
                        "${logNumber(it.x, 4)},${logNumber(it.y, 4)}"
                    } ?: "nil"
                    val actualImagePx = actual?.let {
                        "${logNumber(it.x * imageWidth.toFloat(), 1)},${logNumber(it.y * imageHeight.toFloat(), 1)}"
                    } ?: "nil"
                    val tapPt = tapPoint?.let {
                        "${logNumber(it.x, 1)},${logNumber(it.y, 1)}"
                    } ?: "nil"
                    val touchPt = tapPt
                    val selectionOffsetPt = tapPoint?.let {
                        "${logNumber(0f, 1)},${logNumber(0f, 1)}"
                    } ?: "nil"
                    val viewPt = viewSize?.let {
                        "${logNumber(it.width.toFloat(), 1)}x${logNumber(it.height.toFloat(), 1)}"
                    } ?: "nil"
                    val imageRect = viewSize?.let {
                        "0.0,0.0,${logNumber(it.width.toFloat(), 1)}x${logNumber(it.height.toFloat(), 1)}"
                    } ?: "nil"
                    val logTag = if (actual != null) "DETECTION-MARK" else "DETECTION-NOTE"
                    val rawMessage =
                        "[$logTag] mode=${reviewTarget.mode} " +
                            "phones=${reviewTarget.numberOfPhones} " +
                            "run=${reviewTarget.runNumber} gate=${reviewTarget.gateLabel} " +
                            "actualX=${actual?.x ?: "nil"} actualY=${actual?.y ?: "nil"} " +
                            "detectorX=$detectorX detectorY=${detectorY ?: "nil"} " +
                            "deltaX=${deltaX ?: "nil"} deltaY=${deltaY ?: "nil"} " +
                            "alpha=${reviewTarget.interpolationAlpha ?: "nil"} " +
                            "pick=${reviewTarget.framePick ?: "nil"} " +
                            "s0=${reviewTarget.s0 ?: "nil"} s1=${reviewTarget.s1 ?: "nil"} " +
                            "direction=${reviewTarget.crossingDirection ?: "nil"} " +
                            "reviewSchema=4 method=Replica " +
                            "appVersion=${logQuoted(BuildConfig.VERSION_NAME)} " +
                            "appBuild=${logQuoted(BuildConfig.VERSION_CODE.toString())} " +
                            "appCommit=nil " +
                            "session=${reviewTarget.sessionId ?: "nil"} " +
                            "runId=${reviewTarget.runId} " +
                            "markerKey=${reviewTarget.runId}:${reviewTarget.gateLabel} " +
                            "target=${reviewTarget.target} " +
                            "gateLabel=${logQuoted(reviewTarget.gateLabel)} " +
                            "issue=$selectedIssue " +
                            "note=${logQuoted(note.trim())} " +
                            "actualPoint=$actualPointLog " +
                            "actualImagePx=$actualImagePx " +
                            "tapPt=$tapPt " +
                            "touchPt=$touchPt " +
                            "selectionOffsetPt=$selectionOffsetPt " +
                            "viewPt=$viewPt " +
                            "imageRect=$imageRect " +
                            "imagePx=${imageWidth}x${imageHeight} " +
                            "imageScale=1.00 " +
                            "imageOrientation=up " +
                            "loupeSource=androidLocalReticleScale " +
                            "loupeMag=1.00 " +
                            "loupeDiameterPt=0.0 " +
                            "distanceM=${reviewTarget.distanceMeters ?: "nil"} " +
                            "startType=${reviewTarget.startType ?: "nil"} " +
                            "originalDisplayedTimeSec=${reviewTarget.displayedTimeSeconds ?: "nil"} " +
                            "originalGateX=${reviewTarget.originalGatePosition ?: "nil"} " +
                            "currentLineX=$detectorX " +
                            "contextDetectorY=${detectorY ?: "nil"} " +
                            "contextVelocityPxPerSec=${reviewTarget.crossingVelocityPxPerSec ?: "nil"} " +
                            "contextDirection=${reviewTarget.crossingDirection ?: "nil"} " +
                            "contextWorkWidth=${reviewTarget.workWidth ?: "nil"} " +
                            "detectionDistance=${reviewTarget.detectionDistance ?: "nil"} " +
                            "exposureMs=${reviewTarget.exposureMs ?: "nil"} " +
                            "iso=${reviewTarget.iso ?: "nil"} " +
                            "detectorTriggerFramePts=${reviewTarget.detectorTriggerFramePts ?: "nil"} " +
                            "chosenThumbnailFramePts=${reviewTarget.chosenThumbnailFramePts ?: "nil"} " +
                            "savedThumbnailFramePts=${reviewTarget.savedThumbnailFramePts ?: "nil"} " +
                            "debugPresent=${reviewTarget.interpolationAlpha != null || reviewTarget.framePick != null}"

                    reviewSubmit(
                        DetectionReviewSubmission(
                            target = reviewTarget,
                            issue = selectedIssue,
                            actualX = actual?.x,
                            actualY = actual?.y,
                            note = note.trim(),
                            rawMessage = rawMessage,
                            rawImageData = jpegBytes(thumbnail.bitmap),
                            reviewImageData = reviewJpegBytes(
                                source = thumbnail.bitmap,
                                detectorX = detectorX,
                                detectorY = detectorY,
                                actual = actual
                            )
                        )
                    )
                }
            )
        }
    }
}

private fun logNumber(value: Float, digits: Int): String {
    return String.format(Locale.US, "%.${digits}f", value)
}

private fun logQuoted(value: String): String {
    if (value.isEmpty()) return "nil"
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")
    return "\"$escaped\""
}

private fun jpegBytes(bitmap: Bitmap, quality: Int = 86): ByteArray? {
    return try {
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }
}

private fun reviewJpegBytes(
    source: Bitmap,
    detectorX: Float,
    detectorY: Float?,
    actual: Offset?
): ByteArray? {
    return try {
        val rendered = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(rendered)
        canvas.drawBitmap(source, 0f, 0f, null)

        val linePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(235, 255, 0, 0)
            strokeWidth = maxOf(2f, minOf(source.width, source.height) * 0.004f)
        }
        val lineX = detectorX.coerceIn(0f, 1f) * source.width
        canvas.drawLine(lineX, 0f, lineX, source.height.toFloat(), linePaint)

        val detectorYValue = detectorY
        if (detectorYValue != null) {
            drawReviewDot(
                canvas = canvas,
                width = source.width,
                height = source.height,
                x = detectorX,
                y = detectorYValue,
                fill = AndroidColor.YELLOW,
                radius = maxOf(6f, minOf(source.width, source.height) * 0.017f)
            )
        }

        if (actual != null) {
            drawReviewDot(
                canvas = canvas,
                width = source.width,
                height = source.height,
                x = actual.x,
                y = actual.y,
                fill = AndroidColor.GREEN,
                radius = maxOf(7f, minOf(source.width, source.height) * 0.02f)
            )
        }

        jpegBytes(rendered).also {
            rendered.recycle()
        }
    } catch (_: Exception) {
        null
    }
}

private fun drawReviewDot(
    canvas: AndroidCanvas,
    width: Int,
    height: Int,
    x: Float,
    y: Float,
    fill: Int,
    radius: Float
) {
    val cx = x.coerceIn(0f, 1f) * width
    val cy = y.coerceIn(0f, 1f) * height
    val fillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = fill
        style = AndroidPaint.Style.FILL
    }
    val strokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(242, 255, 255, 255)
        style = AndroidPaint.Style.STROKE
        strokeWidth = maxOf(1.5f, radius * 0.18f)
    }
    canvas.drawCircle(cx, cy, radius, fillPaint)
    canvas.drawCircle(cx, cy, radius, strokePaint)
}

@Composable
private fun ReviewControls(
    selectedIssue: String,
    note: String,
    hasPoint: Boolean,
    onIssueSelected: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = stringResource(R.string.thumbnail_review_title),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.72f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp)
        ) {
            reviewIssues.forEach { issue ->
                FilterChip(
                    selected = selectedIssue == issue.rawValue,
                    onClick = { onIssueSelected(issue.rawValue) },
                    label = { Text(stringResource(issue.labelRes)) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = onNoteChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            label = { Text(stringResource(R.string.thumbnail_review_note)) },
            maxLines = 3
        )

        Button(
            onClick = onSubmit,
            enabled = hasPoint || selectedIssue != "unlabeled" || note.isNotBlank(),
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 8.dp)
        ) {
            Text(stringResource(R.string.common_submit))
        }
    }
}
