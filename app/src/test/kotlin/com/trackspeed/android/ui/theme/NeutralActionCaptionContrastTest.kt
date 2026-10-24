package com.trackspeed.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class NeutralActionCaptionContrastTest {
    @Test fun selectedSetupSubtitleRemainsReadableWithItsProductionOpacity() {
        for (palette in listOf(lightColors, darkGoldColors)) {
            val caption = palette.onPrimaryAction.copy(alpha = .8f).compositeOver(palette.primaryAction)
            assertTrue("Selected setup subtitle needs 4.5:1 contrast", contrast(caption, palette.primaryAction) >= 4.5)
        }
    }
    private fun contrast(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun linear(x: Float) = if (x <= .04045f) x / 12.92 else ((x + .055) / 1.055).pow(2.4)
            return .2126 * linear(c.red) + .7152 * linear(c.green) + .0722 * linear(c.blue)
        }
        val x = luminance(a); val y = luminance(b)
        return (max(x, y) + .05) / (min(x, y) + .05)
    }
}
