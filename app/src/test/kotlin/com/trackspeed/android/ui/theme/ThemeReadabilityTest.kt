package com.trackspeed.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Guard the actual shared body-text and neutral-action pairs, not a screenshot pixel snapshot. */
class ThemeReadabilityTest {
    @Test fun bodyTextAndActionsRemainReadableOnBothSupportedPalettes() {
        listOf(lightColors, darkGoldColors).forEach { colors ->
            listOf(colors.background, colors.surface, colors.cardBottom).forEach { background ->
                listOf(colors.textPrimary, colors.textSecondary, colors.textMuted).forEach { ink ->
                    assertTrue("Body text contrast must remain at least 4.5:1", contrast(ink, background) >= 4.5)
                }
            }
            assertTrue("Neutral action fill and ink must remain at least 7:1",
                contrast(colors.primaryAction, colors.onPrimaryAction) >= 7.0)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun linear(value: Float): Double = value.toDouble().let {
            if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
        }
        return linear(color.red) * 0.2126 + linear(color.green) * 0.7152 + linear(color.blue) * 0.0722
    }
}
