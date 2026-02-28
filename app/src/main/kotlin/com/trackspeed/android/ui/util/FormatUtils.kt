package com.trackspeed.android.ui.util

import androidx.compose.ui.graphics.Color

fun parseAthleteColor(colorStr: String): Color {
    return when (colorStr.lowercase()) {
        "red" -> Color(0xFFFF3B30)
        "blue" -> Color(0xFF0A84FF)
        "green" -> Color(0xFF30D158)
        "orange" -> Color(0xFFFF9500)
        "purple" -> Color(0xFFBF5AF2)
        "pink" -> Color(0xFFFF2D55)
        "yellow" -> Color(0xFFFFD60A)
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
        String.format("%d:%02d.%02d", mins, secs, hundredths)
    } else {
        String.format("%d.%02d", secs, hundredths)
    }
}

fun formatDistance(distance: Double): String {
    return when {
        distance == 36.576 -> "40yd"
        distance % 1.0 == 0.0 -> "${distance.toInt()}m"
        else -> "${distance}m"
    }
}

fun formatSpeed(distance: Double, timeSeconds: Double, speedUnit: String): String {
    if (distance <= 0 || timeSeconds <= 0) return "--"
    val speedMs = distance / timeSeconds
    return when (speedUnit) {
        "km/h" -> String.format("%.1f km/h", speedMs * 3.6)
        "mph" -> String.format("%.1f mph", speedMs * 2.23694)
        else -> String.format("%.1f m/s", speedMs)
    }
}
