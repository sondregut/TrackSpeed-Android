package com.trackspeed.android.videooverlay

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.min

@Serializable
enum class OverlaySpeedUnit(val label: String, val factor: Double) {
    MPS("m/s", 1.0), MPH("mph", 1.0 / 0.44704), KMH("km/h", 3.6);
    fun format(metersPerSecond: Double): String? =
        (metersPerSecond * factor).takeIf { it.isFinite() && it >= 0 }?.let { String.format(Locale.US, "%.1f %s", it, label) }
    companion object {
        fun fromRaw(raw: String) = entries.firstOrNull { it.label == raw } ?: when (raw.lowercase()) {
            "mps" -> MPS; "kmh" -> KMH; else -> MPS
        }
    }
}

@Serializable enum class OverlayCanvas { SOCIAL_9_16, ORIGINAL }
@Serializable enum class OverlaySplitMode { OFF, CUMULATIVE, SEGMENTS }
@Serializable enum class OverlayPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

@Serializable
data class OverlayPreferences(
    val speedUnit: OverlaySpeedUnit = OverlaySpeedUnit.MPS,
    val showTime: Boolean = true,
    val showSpeed: Boolean = true,
    val showRunType: Boolean = true,
    val splitMode: OverlaySplitMode = OverlaySplitMode.CUMULATIVE,
    val position: OverlayPosition = OverlayPosition.TOP_LEFT,
    val socialSafe: Boolean = true,
    val canvas: OverlayCanvas = OverlayCanvas.SOCIAL_9_16
)

data class OverlayRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Conservative shared portrait safe area, not a promise about every platform UI.
 * Meta guidance: 14% top, 35% bottom, 6% sides. Extra top/right clearance
 * accommodates TikTok's variable caption/action UI. No automatic athlete tracking.
 */
fun overlaySafeRect(width: Float, height: Float, socialSafe: Boolean): OverlayRect {
    require(width > 0 && height > 0)
    return if (socialSafe && height > width)
        OverlayRect(width * .08f, height * .18f, width * .84f, height * .65f)
    else OverlayRect(width * .06f, height * .08f, width * .94f, height * .92f)
}

data class OverlayCardLayout(val safe: OverlayRect, val card: OverlayRect, val scale: Float, val compactHeader: Boolean = false)

fun overlayCardLayout(width: Float, height: Float, preferences: OverlayPreferences,
    showSpeedRow: Boolean, splitRows: Int, showRunType: Boolean, sourceAspectRatio: Float? = null): OverlayCardLayout {
    val socialSafe = overlaySafeRect(width, height, preferences.socialSafe)
    val videoTop = sourceAspectRatio?.takeIf { it.isFinite() && it > 1f }?.let { (height - width / it) / 2 }
    val headerBottom = videoTop?.minus(width * .015f)
    val compactHeader = preferences.canvas == OverlayCanvas.SOCIAL_9_16 && height > width &&
        headerBottom != null && headerBottom - socialSafe.top >= width * .24f
    val safe = if (compactHeader) socialSafe.copy(bottom = min(socialSafe.bottom, headerBottom!!)) else socialSafe
    val cardWidthUnits = if (compactHeader) 330f else 250f
    val cardHeight = if (compactHeader) 28f + (if (preferences.showTime || showSpeedRow) 45f else 0f) +
        (if (showRunType) 17f else 0f) + (if (splitRows > 0) 34f else 0f) + 12f else 34f + (if (preferences.showTime) 51f else 0f) +
        (if (showRunType) 20f else 0f) + (if (showSpeedRow) 30f else 0f) +
        splitRows.coerceIn(0, 3) * 21f + 16f
    val scale = min(min(width, height) / 390f, min(safe.width / cardWidthUnits, safe.height / cardHeight))
    val cardWidth = cardWidthUnits * scale
    val actualHeight = cardHeight * scale
    val left = when (preferences.position) {
        OverlayPosition.TOP_LEFT, OverlayPosition.BOTTOM_LEFT -> safe.left
        else -> safe.right - cardWidth
    }
    val top = when (preferences.position) {
        OverlayPosition.TOP_LEFT, OverlayPosition.TOP_RIGHT -> safe.top
        else -> safe.bottom - actualHeight
    }
    return OverlayCardLayout(safe, OverlayRect(left, top, left + cardWidth, top + actualHeight), scale, compactHeader)
}

fun measuredAverageSpeed(distanceMeters: Double, seconds: Double): Double? =
    if (distanceMeters.isFinite() && seconds.isFinite() && distanceMeters > 0 && seconds > 0)
        (distanceMeters / seconds).takeIf { it.isFinite() } else null
