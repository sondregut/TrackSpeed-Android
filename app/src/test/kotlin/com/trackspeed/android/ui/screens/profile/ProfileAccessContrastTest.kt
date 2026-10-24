package com.trackspeed.android.ui.screens.profile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.trackspeed.android.ui.theme.darkGoldColors
import com.trackspeed.android.ui.theme.lightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ProfileAccessContrastTest {
    @Test fun includedAccessLabelRemainsReadableOnTheActualPageAndCardSurfaces() {
        for (theme in listOf(lightColors, darkGoldColors)) {
            val pair = profileAccessColors(theme.isDark)
            for (surface in listOf(theme.gradientTop, theme.gradientBottom, theme.surface)) {
                val container = pair.container.compositeOver(surface)
                assertTrue("Access status text needs 4.5:1 in both appearances", contrast(pair.content, container) >= 4.5)
            }
        }
    }

    @Test fun darkAccessStatusRetainsItsExistingSemanticGreenAndTint() {
        val pair = profileAccessColors(isDark = true)
        assertEquals(Color(0xFF00E676), pair.content)
        assertEquals(Color(0xFF00E676).copy(alpha = .12f), pair.container)
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
