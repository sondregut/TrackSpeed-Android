package com.trackspeed.android.ui.share

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal data class ImageTimePresentation(val value: String, val unit: String)

/** Shipped iOS uses POSIX timing notation; gate timings have3 digits, one-phone video timings2. */
internal fun imageTimePresentation(seconds: Double, digits: Int = 3, useMinutes: Boolean = true): ImageTimePresentation {
    require(digits == 2 || digits == 3)
    if (!seconds.isFinite() || seconds < 0) return ImageTimePresentation("—", "")
    val rounded = BigDecimal.valueOf(seconds).setScale(digits, RoundingMode.HALF_UP)
    if (useMinutes && rounded >= BigDecimal(60)) {
        val minutes = rounded.divideToIntegralValue(BigDecimal(60)).toBigInteger()
        val remainder = rounded.remainder(BigDecimal(60))
        return ImageTimePresentation(String.format(Locale.US, "%d:%0${digits + 3}.${digits}f", minutes, remainder), "min:sec")
    }
    return ImageTimePresentation(String.format(Locale.US, "%.${digits}f", rounded), "s")
}
