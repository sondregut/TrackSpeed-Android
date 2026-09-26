package com.trackspeed.android.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Shared distance formatting for history and export surfaces.
 *
 * Mirrors iOS HistoryDistanceFormatter so labels, filters, summaries, and CSVs
 * agree across platforms.
 */
object HistoryDistanceFormatter {
    private const val FORTY_YARD_METERS = 36.576
    private const val FORTY_YARD_TOLERANCE = 0.5

    data class Descriptor(
        val key: String,
        val label: String,
        val sortMeters: Double
    )

    fun isFortyYard(meters: Double): Boolean {
        return abs(meters - FORTY_YARD_METERS) <= FORTY_YARD_TOLERANCE
    }

    fun descriptor(meters: Double): Descriptor {
        if (isFortyYard(meters)) {
            return Descriptor(key = "40yd", label = "40yd", sortMeters = FORTY_YARD_METERS)
        }

        val normalized = normalizedMeters(meters)
        return Descriptor(
            key = String.format(Locale.US, "%.1f", normalized),
            label = labelForMeters(normalized),
            sortMeters = normalized
        )
    }

    fun labelForMeters(meters: Double): String {
        if (isFortyYard(meters)) {
            return "40yd"
        }

        val normalized = normalizedMeters(meters)
        return if (abs(normalized.roundToInt() - normalized) < 0.05) {
            "${normalized.roundToInt()}m"
        } else {
            String.format(Locale.US, "%.1fm", normalized)
        }
    }

    fun csvNumericMeters(meters: Double): String {
        return if (abs(round(meters) - meters) < 0.001) {
            String.format(Locale.US, "%.0f", meters)
        } else {
            String.format(Locale.US, "%.3f", meters)
        }
    }

    private fun normalizedMeters(meters: Double): Double {
        val roundedInt = round(meters)
        return if (abs(meters - roundedInt) < 0.05) {
            roundedInt
        } else {
            round(meters * 10.0) / 10.0
        }
    }
}
