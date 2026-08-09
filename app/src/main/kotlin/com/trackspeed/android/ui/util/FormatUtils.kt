package com.trackspeed.android.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.util.HistoryDistanceFormatter

fun parseAthleteColor(colorStr: String): Color {
    return when (colorStr.lowercase()) {
        "red" -> Color(0xFFFF3B30)
        "blue" -> Color(0xFF0A84FF)
        "green" -> Color(0xFF30D158)
        "orange" -> Color(0xFFFF9500)
        "purple" -> Color(0xFFBF5AF2)
        "pink" -> Color(0xFFFF2D55)
        "yellow" -> Color(0xFFFFD60A)
        "gray" -> Color(0xFF8E8E93)
        "cyan", "teal" -> Color(0xFF64D2FF)
        else -> Color(0xFF0A84FF)
    }
}

fun formatTime(seconds: Double): String {
    if (seconds <= 0) return "0.00"

    val totalMs = (seconds * 1000).toLong()
    val mins = totalMs / 60000
    val secs = (totalMs % 60000) / 1000
    val hundredths = (totalMs % 1000) / 10

    return if (mins > 0) {
        String.format(java.util.Locale.getDefault(), "%d:%02d.%02d", mins, secs, hundredths)
    } else {
        String.format(java.util.Locale.getDefault(), "%d.%02d", secs, hundredths)
    }
}

fun formatDistance(distance: Double): String {
    return HistoryDistanceFormatter.labelForMeters(distance)
}

@Composable
fun formatSessionMode(numberOfPhones: Int, numberOfGates: Int): String {
    val phones = pluralStringResource(
        R.plurals.session_mode_phone_count,
        numberOfPhones,
        numberOfPhones
    )
    return if (numberOfGates > 2) {
        val gates = pluralStringResource(
            R.plurals.session_mode_gate_count,
            numberOfGates,
            numberOfGates
        )
        stringResource(R.string.session_mode_with_gates, phones, gates)
    } else {
        phones
    }
}

fun formatSpeed(distance: Double, timeSeconds: Double, speedUnit: String): String {
    if (distance <= 0 || timeSeconds <= 0) return "--"
    val speedMs = distance / timeSeconds
    return when (speedUnit) {
        "km/h" -> String.format(java.util.Locale.getDefault(), "%.1f km/h", speedMs * 3.6)
        "mph" -> String.format(java.util.Locale.getDefault(), "%.1f mph", speedMs * 2.23694)
        else -> String.format(java.util.Locale.getDefault(), "%.1f m/s", speedMs)
    }
}
