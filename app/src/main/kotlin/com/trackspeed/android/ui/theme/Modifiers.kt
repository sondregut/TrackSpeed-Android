package com.trackspeed.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Full-screen background gradient (iOS backgroundGradientDark).
 * Colors adapt to the active AppTheme via LocalAppColors.
 */
@Composable
fun Modifier.gradientBackground(): Modifier {
    val colors = LocalAppColors.current
    val brush = Brush.linearGradient(
        colors = listOf(colors.gradientTop, colors.gradientBottom),
        start = Offset(0f, 0f),
        end = Offset(0f, Float.POSITIVE_INFINITY)
    )
    return this.background(brush)
}

private val CardShape = RoundedCornerShape(20.dp)

/**
 * Feature/hero card — diagonal gradient with subtle border.
 * Use for mode selection cards and primary action cards.
 */
@Composable
fun Modifier.featureCard(): Modifier {
    val colors = LocalAppColors.current
    val brush = Brush.linearGradient(
        colors = listOf(colors.featureCardTop, colors.featureCardBottom),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )
    return this
        .clip(CardShape)
        .background(brush)
        .border(0.5.dp, colors.border, CardShape)
}

/**
 * Gunmetal card — subtle vertical gradient with border.
 * Use for standard content cards, list items, stat cards.
 */
@Composable
fun Modifier.gunmetalCard(): Modifier {
    val colors = LocalAppColors.current
    val brush = Brush.verticalGradient(
        colors = listOf(colors.cardTop, colors.cardBottom)
    )
    return this
        .clip(CardShape)
        .background(brush)
        .border(0.5.dp, colors.border, CardShape)
}

/**
 * Flat surface card — solid color with border.
 * Use for simple list items and secondary content.
 */
@Composable
fun Modifier.surfaceCard(): Modifier {
    val colors = LocalAppColors.current
    return this
        .clip(CardShape)
        .background(colors.surface)
        .border(0.5.dp, colors.border, CardShape)
}
