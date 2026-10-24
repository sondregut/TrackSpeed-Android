package com.trackspeed.android.ui.components

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R
import com.trackspeed.android.ui.share.*
import com.trackspeed.android.ui.share.SessionShareNaming
import com.trackspeed.android.ui.share.sessionShareNaming
import com.trackspeed.android.model.StartType
import com.trackspeed.android.protocol.SegmentSplit
import com.trackspeed.android.ui.util.formatDistance
import com.trackspeed.android.ui.util.formatSplitDuration
import com.trackspeed.android.ui.util.localizedDisplayName

/**
 * Share card gradient themes matching iOS parity.
 * Each theme defines background gradient colors, accent glow, and speed text color.
 */
enum class ShareCardTheme(
    @StringRes val displayNameRes: Int,
    val gradientColors: List<Color>,
    val swatchColor: Color,
    val accentGlow: Color,
    val accentGlowAlpha: Float,
    val speedColor: Color
) {
    MIDNIGHT(
        displayNameRes = R.string.share_card_theme_midnight,
        gradientColors = listOf(Color(0xFF0F1219), Color(0xFF141824), Color(0xFF0F1219)),
        swatchColor = Color(0xFF141824),
        accentGlow = Color(0xFF4A90D9),
        accentGlowAlpha = 0.15f,
        speedColor = Color(0xFF00BCD4)
    ),
    EMBER(
        displayNameRes = R.string.share_card_theme_ember,
        gradientColors = listOf(Color(0xFF1A0A0A), Color(0xFF241210), Color(0xFF1A0A0A)),
        swatchColor = Color(0xFF241210),
        accentGlow = Color(0xFFE04040),
        accentGlowAlpha = 0.18f,
        speedColor = Color(0xFFFF8A65)
    ),
    OCEAN(
        displayNameRes = R.string.share_card_theme_ocean,
        gradientColors = listOf(Color(0xFF0A1419), Color(0xFF0E1F24), Color(0xFF0A1419)),
        swatchColor = Color(0xFF0E1F24),
        accentGlow = Color(0xFF4DB6AC),
        accentGlowAlpha = 0.15f,
        speedColor = Color(0xFF80CBC4)
    ),
    FOREST(
        displayNameRes = R.string.share_card_theme_forest,
        gradientColors = listOf(Color(0xFF0A140E), Color(0xFF0E2418), Color(0xFF0A140E)),
        swatchColor = Color(0xFF0E2418),
        accentGlow = Color(0xFF4CAF50),
        accentGlowAlpha = 0.15f,
        speedColor = Color(0xFF81C784)
    ),
    SLATE(
        displayNameRes = R.string.share_card_theme_slate,
        gradientColors = listOf(Color(0xFF121212), Color(0xFF1E1E1E), Color(0xFF121212)),
        swatchColor = Color(0xFF1E1E1E),
        accentGlow = Color(0xFFE0E0E0),
        accentGlowAlpha = 0.08f,
        speedColor = Color(0xFFE0E0E0).copy(alpha = 0.7f)
    );
}

/**
 * Data class holding all the information needed to render a shareable result card.
 */
data class ShareCardData(
    val timeSeconds: Double,
    val distance: Double,
    val startType: String,
    val dateFormatted: String,
    val athleteName: String? = null,
    val isPersonalBest: Boolean = false,
    val isSeasonBest: Boolean = false,
    val speedValue: Double? = null,
    val speedUnit: String = "km/h",
    val segments: List<SegmentSplit> = emptyList(),
    val startPhoto: Bitmap? = null,
    val finishPhoto: Bitmap? = null,
    val startGatePosition: Double? = null,
    val finishGatePosition: Double? = null,
    val timeDigits: Int = 3
) {
    val hasPhotos: Boolean get() = startPhoto != null || finishPhoto != null
    val hasStartType: Boolean get() = StartType.fromRawValue(startType) != StartType.FLYING
}

data class ShareSessionCardData(
    val distance: Double,
    val dateFormatted: String,
    val runs: List<ShareSessionRunData>
) {
    val runCount: Int = runs.size
    val bestTimeSeconds: Double? = runs
        .map { it.timeSeconds }
        .filter { it > 0.0 }
        .minOrNull()
}

data class ShareSessionRunData(
    val runNumber: Int,
    val timeSeconds: Double,
    val thumbnail: Bitmap? = null,
    val segments: List<SegmentSplit> = emptyList(),
    val athleteName: String? = null,
    val timeDigits: Int = 3
)

/** The same fixed logical canvas is used for the preview and full-resolution export. */
@Composable
fun ShareableResultCard(
    data: ShareCardData,
    theme: ShareCardTheme,
    modifier: Modifier = Modifier,
    options: RunImageOptions = RunImageOptions()
) {
    RunImageCard(data, theme, options, modifier)
}

@Composable
fun ShareableSessionCard(
    data: ShareSessionCardData,
    theme: ShareCardTheme,
    modifier: Modifier = Modifier,
    options: SessionImageOptions = SessionImageOptions()
) {
    SessionImageCard(data, theme, options, modifier)
}

@Composable
private fun SessionStatsRow(
    distance: Double,
    runCount: Int,
    bestTimeSeconds: Double?,
    textColor: Color,
    accentColor: Color
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = formatDistance(distance),
            color = textColor.copy(alpha = 0.9f),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = "  \u2022  ",
            color = textColor.copy(alpha = 0.28f),
            fontSize = 14.sp
        )
        Text(
            text = if (runCount == 1) {
                stringResource(R.string.share_card_run_count_singular, runCount)
            } else {
                stringResource(R.string.share_card_run_count, runCount)
            },
            color = textColor.copy(alpha = 0.9f),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )

        if (bestTimeSeconds != null) {
            Text(
                text = "  \u2022  ",
                color = textColor.copy(alpha = 0.28f),
                fontSize = 14.sp
            )
            Text(
                text = stringResource(R.string.share_card_best_time, formatShareTime(bestTimeSeconds)),
                color = accentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ShareSessionRunRow(
    run: ShareSessionRunData,
    isBest: Boolean,
    showSplits: Boolean,
    showThumbnail: Boolean,
    showName: Boolean,
    textColor: Color,
    accentColor: Color
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rowAccent = if (isBest) accentColor else textColor

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isBest) accentColor.copy(alpha = 0.11f) else textColor.copy(alpha = 0.07f),
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = if (isBest) 1.dp else 0.5.dp,
                color = if (isBest) accentColor.copy(alpha = 0.45f) else textColor.copy(alpha = 0.10f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showThumbnail) {
                ShareSessionThumbnail(bitmap = run.thumbnail)
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.session_detail_run_number, run.runNumber),
                    color = textColor.copy(alpha = 0.82f), fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (showName) run.athleteName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    Text(text = name, color = textColor.copy(alpha = 0.72f), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("session-share-row-name-${run.runNumber}"))
                }
            }

            if (isBest) {
                Text(
                    text = stringResource(R.string.session_detail_badge_best),
                    color = accentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier
                        .background(
                            color = accentColor.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(50)
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }

            Text(
                text = formatShareTime(run.timeSeconds),
                color = rowAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                maxLines = 1
            )
        }

        if (showSplits && run.segments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = run.segments.take(4).joinToString("  \u2022  ") {
                    val gateLabel = context.getString(
                        R.string.race_gate_number_range,
                        it.fromGateIndex,
                        it.toGateIndex
                    )
                    val splitTime = context.getString(
                        R.string.common_seconds_value,
                        formatSplitDuration(it.splitNanos)
                    )
                    "$gateLabel $splitTime"
                },
                color = textColor.copy(alpha = 0.48f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ShareSessionThumbnail(bitmap: Bitmap?) {
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "\u2014",
                color = Color.White.copy(alpha = 0.30f),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun SessionShareFooter(
    athleteName: String?,
    textColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        if (!athleteName.isNullOrBlank()) {
            Text(
                text = athleteName,
                color = textColor.copy(alpha = 0.9f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("session-share-footer-name")
            )
            Spacer(modifier = Modifier.height(9.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(0.5.dp)
                .background(textColor.copy(alpha = 0.10f))
        )

        Spacer(modifier = Modifier.height(9.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                alpha = 0.5f
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.share_card_watermark),
                color = textColor.copy(alpha = 0.28f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SplitSummary(
    segments: List<SegmentSplit>,
    textColor: Color,
    accentColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = textColor.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        segments.take(3).forEach { segment ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.race_gate_number_range,
                        segment.fromGateIndex,
                        segment.toGateIndex
                    ),
                    color = textColor.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(
                        R.string.common_seconds_value,
                        formatSplitDuration(segment.splitNanos)
                    ),
                    color = accentColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (segments.size > 3) {
            val remainingSplits = segments.size - 3
            Text(
                text = pluralStringResource(
                    R.plurals.share_more_splits,
                    remainingSplits,
                    remainingSplits
                ),
                color = textColor.copy(alpha = 0.45f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun BrandingHeader(textColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.share_card_branding),
            color = textColor.copy(alpha = 0.9f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun HeroTimeDisplay(
    timeSeconds: Double,
    textColor: Color
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = formatShareTime(timeSeconds),
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 64.sp,
            letterSpacing = (-1).sp
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.share_card_seconds_unit),
            color = textColor.copy(alpha = 0.5f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(bottom = 10.dp)
        )
    }
}

@Composable
private fun SpeedDisplay(
    speed: Double,
    unit: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = String.format(java.util.Locale.getDefault(), "%.2f", speed),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = unit,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}

@Composable
private fun BadgesRow(
    isPersonalBest: Boolean,
    isSeasonBest: Boolean
) {
    val bestGreen = Color(0xFF4CAF50)
    val seasonGold = Color(0xFFFFD600)

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isPersonalBest) {
            ShareBadge(text = stringResource(R.string.share_card_pb), color = bestGreen)
        }
        if (isPersonalBest && isSeasonBest) {
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (isSeasonBest) {
            ShareBadge(text = stringResource(R.string.share_card_sb), color = seasonGold)
        }
    }
}

@Composable
private fun ShareBadge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = color.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun BottomSection(
    athleteName: String?,
    distance: Double,
    startType: String,
    dateFormatted: String,
    textColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Athlete name
        if (!athleteName.isNullOrBlank()) {
            Text(
                text = athleteName,
                color = textColor.copy(alpha = 0.9f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Start type + distance badge
        val startLabel = StartType.fromRawValue(startType).localizedDisplayName()
        Text(
            text = stringResource(
                R.string.race_session_summary_format,
                startLabel,
                formatDistance(distance)
            ),
            color = textColor.copy(alpha = 0.85f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(
                    color = textColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(50)
                )
                .padding(horizontal = 20.dp, vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Date
        Text(
            text = dateFormatted,
            color = textColor.copy(alpha = 0.3f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Separator
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(0.5.dp)
                .background(textColor.copy(alpha = 0.08f))
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Footer watermark
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(12.dp),
                alpha = 0.5f
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.share_card_watermark),
                color = textColor.copy(alpha = 0.25f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp
            )
        }
    }
}

/**
 * Format time for the share card display.
 */
internal fun formatShareTime(seconds: Double): String = imageTimePresentation(seconds).value
