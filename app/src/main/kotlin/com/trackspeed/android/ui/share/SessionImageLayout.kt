package com.trackspeed.android.ui.share

/** Density sizes follow released iOS SessionCard; selected photos/split context are never silently removed. */
internal data class SessionImageDensity(
    val photoWidth: Int, val photoHeight: Int, val rowPadding: Int, val rowGap: Int,
    val sectionGap: Int, val splitsOnEveryRow: Boolean
)
internal data class SessionImageLayout(val visibleCount: Int, val density: SessionImageDensity) {
    fun hiddenCount(total: Int): Int = (total - visibleCount).coerceAtLeast(0)
}

/**
 * Largest visible prefix first, then progressively denser fixed-size typography. If even the
 * compact rows do not fit, show fewer rows and count every omitted run. No crop/downscale fallback.
 */
internal fun sessionImageLayouts(total: Int, options: SessionImageOptions): List<SessionImageLayout> {
    require(total >= 0)
    val densities = listOf(
        SessionImageDensity(38, 50, 8, 6, 16, true),
        SessionImageDensity(32, 42, 6, 3, 12, false),
        SessionImageDensity(24, 32, 4, 3, 8, false)
    )
    if (total == 0) return listOf(SessionImageLayout(0, densities.last()))
    return buildList {
        for (count in minOf(total, 8) downTo 1) {
            val compact = count > 5 || (options.showSplits && count > 3)
            (if (compact) densities.drop(1) else densities).forEach { add(SessionImageLayout(count, it)) }
        }
    }
}

internal fun firstFittingSessionLayout(layouts: List<SessionImageLayout>, availableHeight: Int,
    measuredHeight: (SessionImageLayout) -> Int): SessionImageLayout? =
    layouts.firstOrNull { measuredHeight(it) <= availableHeight }

/** Best visible run with splits gets compact detail, rather than displaying an enabled no-op switch. */
internal fun compactSessionSplitIndex(times: List<Double>, hasSplits: List<Boolean>): Int? {
    require(times.size == hasSplits.size)
    val indices = times.indices.filter { hasSplits[it] }
    return indices.filter { times[it].isFinite() && times[it] > 0 }.minByOrNull { times[it] } ?: indices.firstOrNull()
}

/** Keep an explicit split selection meaningful even when only a later run has splits. */
internal fun sessionImageVisibleIndices(total: Int, visibleCount: Int, splitIndices: List<Int>, showSplits: Boolean): List<Int> {
    require(visibleCount in 0..minOf(total, 8))
    val prefix = (0 until visibleCount).toList()
    if (!showSplits || prefix.isEmpty() || prefix.any { it in splitIndices }) return prefix
    val splitRun = splitIndices.firstOrNull { it in 0 until total } ?: return prefix
    return (prefix.dropLast(1) + splitRun).sorted()
}
