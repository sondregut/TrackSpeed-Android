package com.trackspeed.android.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.model.StartType
import com.trackspeed.android.ui.share.*
import com.trackspeed.android.ui.util.formatDistance
import com.trackspeed.android.ui.util.formatSplitDuration
import com.trackspeed.android.ui.util.localizedDisplayName
import java.util.Locale

private data class RunImageDensity(val time: Int, val photo: Int, val gap: Int)

/** Released RunShareCard.swift shapes, margins, density order and photo/split visibility. */
@Composable
internal fun RunImageCard(data: ShareCardData, theme: ShareCardTheme, options: RunImageOptions, modifier: Modifier) {
    val photos = options.showPhotos && data.hasPhotos
    val densities = when {
        options.format == RunImageFormat.STORY && photos -> listOf(RunImageDensity(80,112,16), RunImageDensity(68,96,10), RunImageDensity(56,80,6))
        options.format == RunImageFormat.STORY -> listOf(RunImageDensity(96,0,18), RunImageDensity(80,0,12), RunImageDensity(64,0,6))
        photos -> listOf(RunImageDensity(64,92,8), RunImageDensity(54,78,6))
        else -> listOf(RunImageDensity(76,0,8), RunImageDensity(62,0,6))
    }
    ShareImageCanvas(options.format, modifier) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(theme.gradientColors))) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(
                listOf(theme.accentGlow.copy(alpha = theme.accentGlowAlpha), Color.Transparent),
                center = Offset(180f, 0f), radius = 350f)))
            // StorySafeArea.rect(in:360x640).insetBy(4,4); Feed uses24x22 margins.
            val story = options.format == RunImageFormat.STORY
            Box(Modifier.offset(24.dp, if (story) (250f / 3 + 4).dp else 22.dp)
                .size(312.dp, if (story) (1250f / 3 - 8).dp else 406.dp)) {
                SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
                    val width = constraints.maxWidth
                    val height = constraints.maxHeight
                    var chosen = densities.last()
                    var naturalHeight = 0
                    for ((index, density) in densities.withIndex()) {
                        val probe = subcompose("probe-$index") { Box(Modifier.clearAndSetSemantics { }) { RunImageContent(data, theme, options, density, false) } }
                            .single().measure(Constraints.fixedWidth(width))
                        chosen = density
                        naturalHeight = probe.height
                        if (naturalHeight <= height) break
                    }
                    val fits = naturalHeight <= height
                    val content = subcompose("content") { RunImageContent(data, theme, options, chosen, fits) }
                        .single().measure(if (fits) Constraints.fixed(width, height) else Constraints.fixedWidth(width))
                    val scale = if (fits) 1f else height.toFloat() / content.height
                    layout(width, height) {
                        content.placeWithLayer(((width - width * scale) / 2).toInt(), 0) {
                            scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunImageContent(data: ShareCardData, theme: ShareCardTheme, options: RunImageOptions,
    density: RunImageDensity, expand: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.app_logo), null, Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.app_name), fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(data.dateFormatted, color = Color.White.copy(alpha = .6f), fontSize = 11.sp, maxLines = 1)
        }
        Spacer(if (expand) Modifier.weight(1f).heightIn(min = density.gap.dp) else Modifier.height(density.gap.dp))
        if (options.showPhotos && data.hasPhotos) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                data.startPhoto?.let { RunImagePhoto(it, stringResource(R.string.onboarding_preview_start_text),
                    data.startGatePosition, density.photo, "run-image-start-photo") }
                data.finishPhoto?.let { RunImagePhoto(it, stringResource(R.string.onboarding_preview_finish_text),
                    data.finishGatePosition, density.photo, "run-image-finish-photo") }
            }
            Spacer(Modifier.height(density.gap.dp))
        }
        // Preserve Android's canonical measurement formatting and segment semantics.
        val title = if (StartType.fromRawValue(data.startType) == StartType.FLYING) {
            stringResource(R.string.race_session_summary_format, StartType.FLYING.localizedDisplayName(), formatDistance(data.distance))
        } else formatDistance(data.distance)
        Text(title, fontSize = (density.time * .17f).sp, color = theme.speedColor,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
        val measuredText = rememberTextMeasurer()
        val timing = imageTimePresentation(data.timeSeconds, data.timeDigits)
        val time = timing.value
        val unit = timing.unit
        val timeWidth = measuredText.measure(AnnotatedString(time), TextStyle(fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = density.time.sp), maxLines = 1).size.width
        val unitWidth = measuredText.measure(AnnotatedString(unit), TextStyle(fontSize = (density.time * .24f).sp), maxLines = 1).size.width
        val timeScale = minOf(1f, 308f / (timeWidth + unitWidth).coerceAtLeast(1))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(time, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                fontSize = (density.time * timeScale).sp, color = Color.White, maxLines = 1, modifier = Modifier.testTag("run-image-time"))
            Text(unit, color = Color.White.copy(alpha = .7f),
                fontSize = (density.time * .24f * timeScale).sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        data.speedValue?.let {
            Text(String.format(Locale.getDefault(), "%.2f %s", it, data.speedUnit),
                color = theme.speedColor, fontSize = (density.time * .22f).sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color.White.copy(alpha = .07f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 7.dp).testTag("run-image-speed"))
        }
        if (options.displaysSplits(data.hasPhotos) && data.segments.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("run-image-splits"),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                data.segments.chunked(3).forEach { segments ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        segments.forEach { split ->
                            Column(Modifier.weight(1f).background(Color.White.copy(alpha = .06f), RoundedCornerShape(10.dp))
                                .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.race_gate_number_range, split.fromGateIndex, split.toGateIndex),
                                    color = Color.White.copy(alpha = .6f), fontSize = 9.sp, maxLines = 1)
                                Text(stringResource(R.string.common_seconds_value, imageTimePresentation(split.splitNanos / 1_000_000_000.0, data.timeDigits, useMinutes = false).value),
                                    color = Color.White, fontSize = (density.time * .24f).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        if (options.showStartType && data.hasStartType) {
            Text(StartType.fromRawValue(data.startType).localizedDisplayName(), fontSize = 12.sp,
                color = Color.White.copy(alpha = .6f), maxLines = 2, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).testTag("run-image-start-type"))
        }
        if (data.isPersonalBest || data.isSeasonBest) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (data.isPersonalBest) Text(stringResource(R.string.share_card_pb), color = Color(0xFFFEAA21), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (data.isSeasonBest) Text(stringResource(R.string.share_card_sb), color = Color(0xFFFEAA21), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(if (expand) Modifier.weight(1f).heightIn(min = density.gap.dp) else Modifier.height(density.gap.dp))
        if (options.showName && !data.athleteName.isNullOrBlank()) {
            Text(data.athleteName.trim(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("run-image-name"))
        }
        Text(stringResource(R.string.share_card_watermark), color = Color.White.copy(alpha = .6f),
            fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun RunImagePhoto(photo: Bitmap, label: String, gatePosition: Double?, width: Int, tag: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width.dp, (width * 4f / 3).dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(14.dp)).testTag(tag)) {
            Image(photo.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            croppedGateFraction(gatePosition, photo.width, photo.height)?.let { position ->
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width * position
                    drawLine(Color(0xFFFEAA21), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
            }
        }
        Text(label, color = Color.White.copy(alpha = .7f), fontSize = 10.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    }
}
