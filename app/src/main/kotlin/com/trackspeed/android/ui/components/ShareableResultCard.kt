package com.trackspeed.android.ui.components

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackspeed.android.R

/**
 * Share card gradient themes matching iOS parity.
 * Each theme defines background gradient colors, accent glow, and speed text color.
 */
enum class ShareCardTheme(
    val displayName: String,
    val gradientColors: List<Color>,
    val swatchColor: Color,
    val accentGlow: Color,
    val accentGlowAlpha: Float,
    val speedColor: Color
) {
    MIDNIGHT(
        displayName = "Midnight",
        gradientColors = listOf(Color(0xFF0F1219), Color(0xFF141824), Color(0xFF0F1219)),
        swatchColor = Color(0xFF141824),
        accentGlow = Color(0xFF4A90D9),
        accentGlowAlpha = 0.15f,
        speedColor = Color(0xFF00BCD4)
    ),
    EMBER(
        displayName = "Ember",
        gradientColors = listOf(Color(0xFF1A0A0A), Color(0xFF241210), Color(0xFF1A0A0A)),
        swatchColor = Color(0xFF241210),
        accentGlow = Color(0xFFE04040),
        accentGlowAlpha = 0.18f,
        speedColor = Color(0xFFFF8A65)
    ),
    OCEAN(
        displayName = "Ocean",
        gradientColors = listOf(Color(0xFF0A1419), Color(0xFF0E1F24), Color(0xFF0A1419)),
        swatchColor = Color(0xFF0E1F24),
        accentGlow = Color(0xFF4DB6AC),
        accentGlowAlpha = 0.15f,
        speedColor = Color(0xFF80CBC4)
    ),
    FOREST(
        displayName = "Forest",
        gradientColors = listOf(Color(0xFF0A140E), Color(0xFF0E2418), Color(0xFF0A140E)),
        swatchColor = Color(0xFF0E2418),
        accentGlow = Color(0xFF4CAF50),
        accentGlowAlpha = 0.15f,
        speedColor = Color(0xFF81C784)
    ),
    SLATE(
        displayName = "Slate",
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
    val speedUnit: String = "km/h"
)

/**
 * A shareable result card optimized for Instagram Stories (9:16 aspect ratio).
 * Renders a beautiful gradient card with the run result, matching iOS parity.
 */
@Composable
fun ShareableResultCard(
    data: ShareCardData,
    theme: ShareCardTheme,
    modifier: Modifier = Modifier
) {
    val textLight = Color.White

    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(20.dp))
    ) {
        // Background gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(colors = theme.gradientColors)
                )
        )

        // Accent glow at top
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            theme.accentGlow.copy(alpha = theme.accentGlowAlpha),
                            Color.Transparent
                        ),
                        center = Offset(0.5f, 0f),
                        radius = 800f
                    )
                )
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.08f))

            // Branding header
            BrandingHeader(textColor = textLight)

            Spacer(modifier = Modifier.weight(0.12f))

            // Hero time
            HeroTimeDisplay(
                timeSeconds = data.timeSeconds,
                textColor = textLight
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Speed
            if (data.speedValue != null) {
                SpeedDisplay(
                    speed = data.speedValue,
                    unit = data.speedUnit,
                    color = theme.speedColor
                )
            }

            // PB/SB badges
            if (data.isPersonalBest || data.isSeasonBest) {
                Spacer(modifier = Modifier.height(20.dp))
                BadgesRow(
                    isPersonalBest = data.isPersonalBest,
                    isSeasonBest = data.isSeasonBest
                )
            }

            Spacer(modifier = Modifier.weight(0.15f))

            // Bottom section
            BottomSection(
                athleteName = data.athleteName,
                distance = data.distance,
                startType = data.startType,
                dateFormatted = data.dateFormatted,
                textColor = textLight
            )

            Spacer(modifier = Modifier.weight(0.05f))
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
            text = String.format("%.2f", speed),
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
        val startLabel = startType.replaceFirstChar { it.uppercase() }
        Text(
            text = "$startLabel ${distance.toInt()}m",
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
private fun formatShareTime(seconds: Double): String {
    if (seconds <= 0) return "0.00"

    return if (seconds < 60) {
        String.format("%.2f", seconds)
    } else {
        val mins = (seconds / 60).toInt()
        val secs = seconds % 60
        String.format("%d:%05.2f", mins, secs)
    }
}
