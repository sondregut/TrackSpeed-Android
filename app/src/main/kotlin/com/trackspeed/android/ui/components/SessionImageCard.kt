package com.trackspeed.android.ui.components

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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.share.*
import com.trackspeed.android.ui.util.formatDistance

/** Measured rows within the shipped Story safe area. No weighted remainder, clipping or reduced type size. */
@Composable
internal fun SessionImageCard(data: ShareSessionCardData, theme: ShareCardTheme, options: SessionImageOptions,
    modifier: Modifier) {
    val naming = if (options.showName) sessionShareNaming(data.runs.map { it.athleteName }) else SessionShareNaming.None
    val layouts = sessionImageLayouts(data.runs.size, options)
    ShareImageCanvas(RunImageFormat.STORY, modifier.testTag("session-image-card")) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(theme.gradientColors))) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(
                listOf(theme.accentGlow.copy(alpha = theme.accentGlowAlpha), Color.Transparent),
                center = Offset(180f, 0f), radius = 350f)))
            Box(Modifier.offset(24.dp, (250f / 3 + 4).dp).size(312.dp, (1250f / 3 - 8).dp)
                .testTag("session-image-safe-area")) {
                SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
                    val chosen = firstFittingSessionLayout(layouts, constraints.maxHeight) { candidate ->
                        subcompose("probe-${candidate.visibleCount}-${candidate.density.photoWidth}") {
                            Box(Modifier.clearAndSetSemantics { }) { SessionImageContent(data, theme, options, naming, candidate) }
                        }.single().measure(Constraints.fixedWidth(constraints.maxWidth)).height
                    } ?: SessionImageLayout(0, layouts.last().density)
                    // If extreme content cannot fit even one complete row, count all rows as omitted.
                    // Never paint a partial timing row or pretend it was included.
                    val content = subcompose("content") { SessionImageContent(data, theme, options, naming, chosen) }
                        .single().measure(Constraints.fixedWidth(constraints.maxWidth))
                    check(content.height <= constraints.maxHeight) { "Session image content exceeds its fixed safe canvas" }
                    layout(constraints.maxWidth, constraints.maxHeight) { content.place(0, 0) }
                }
            }
        }
    }
}

@Composable
private fun SessionImageContent(data: ShareSessionCardData, theme: ShareCardTheme, options: SessionImageOptions,
    naming: SessionShareNaming, layout: SessionImageLayout) {
    val density = layout.density
    val indices = sessionImageVisibleIndices(data.runs.size, layout.visibleCount,
        data.runs.indices.filter { data.runs[it].segments.isNotEmpty() }, options.showSplits)
    val visible = indices.map { data.runs[it] }
    val splitIndex = compactSessionSplitIndex(visible.map { it.timeSeconds }, visible.map { it.segments.isNotEmpty() })
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painterResource(R.drawable.app_logo), null, Modifier.size(20.dp))
            Text(stringResource(R.string.app_name), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(data.dateFormatted, color = Color.White.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(density.sectionGap.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDistance(data.distance), color = Color.White, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
            Text(stringResource(if (data.runCount == 1) R.string.share_card_run_count_singular else R.string.share_card_run_count, data.runCount),
                color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            val best = data.runs.filter { it.timeSeconds.isFinite() && it.timeSeconds > 0 }.minByOrNull { it.timeSeconds }
            if (best != null) Text(stringResource(R.string.share_card_best_time,
                imageTimePresentation(best.timeSeconds, best.timeDigits, useMinutes = false).value), color = theme.speedColor,
                fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.weight(1.5f))
        }
        Spacer(Modifier.height(density.sectionGap.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(density.rowGap.dp)) {
            visible.forEachIndexed { index, run ->
                SessionImageRow(run, theme, density, options.showPhotos, naming == SessionShareNaming.PerRow,
                    options.showSplits && (density.splitsOnEveryRow || index == splitIndex),
                    data.bestTimeSeconds == run.timeSeconds)
            }
        }
        val omitted = layout.hiddenCount(data.runs.size)
        if (omitted > 0) Text(stringResource(R.string.share_card_more_runs, omitted), color = Color.White.copy(alpha = .65f),
            fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).testTag("session-image-hidden-count"))
        Spacer(Modifier.height(density.sectionGap.dp))
        if (naming is SessionShareNaming.Footer) Text(naming.name, color = Color.White, fontSize = 15.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("session-share-footer-name"))
        Text(stringResource(R.string.share_card_watermark), color = Color.White.copy(alpha = .6f), fontSize = 12.sp,
            maxLines = 1, modifier = Modifier.padding(top = 3.dp).testTag("session-image-footer"))
    }
}

@Composable
private fun SessionImageRow(run: ShareSessionRunData, theme: ShareCardTheme, density: SessionImageDensity,
    showPhoto: Boolean, showName: Boolean, showSplits: Boolean, isBest: Boolean) {
    val accent = if (isBest) theme.speedColor else Color.White
    Column(Modifier.fillMaxWidth().background(accent.copy(alpha = .07f), RoundedCornerShape(10.dp))
        .border(if (isBest) 1.dp else .5.dp, accent.copy(alpha = if (isBest) .45f else .1f), RoundedCornerShape(10.dp))
        .padding(horizontal = 8.dp, vertical = density.rowPadding.dp).testTag("session-image-row-${run.runNumber}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showPhoto && run.thumbnail != null) {
                Image(run.thumbnail.asImageBitmap(), null, Modifier.size(density.photoWidth.dp, density.photoHeight.dp)
                    .clip(RoundedCornerShape(6.dp)).testTag("session-image-photo-${run.runNumber}"), contentScale = ContentScale.Crop)
            }
            Text(stringResource(R.string.session_detail_run_number, run.runNumber), color = Color.White.copy(alpha = .85f),
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 62.dp))
            if (showName && !run.athleteName.isNullOrBlank()) {
                Text(run.athleteName.trim(), color = Color.White.copy(alpha = .65f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("session-share-row-name-${run.runNumber}"))
            } else Spacer(Modifier.weight(1f))
            Text(imageTimePresentation(run.timeSeconds, run.timeDigits, useMinutes = false).value,
                color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                maxLines = 1, modifier = Modifier.testTag("session-image-time-${run.runNumber}"))
        }
        if (showSplits && run.segments.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("session-image-splits-${run.runNumber}"),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                run.segments.chunked(3).forEach { segments ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        segments.forEach { split ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.race_gate_number_range, split.fromGateIndex, split.toGateIndex),
                                    color = Color.White.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1)
                                Text(imageTimePresentation(split.splitNanos / 1_000_000_000.0, run.timeDigits, useMinutes = false).value,
                                    color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
