package com.trackspeed.android.ui.share

import com.trackspeed.android.videooverlay.OverlaySpeedUnit
import com.trackspeed.android.videooverlay.measuredAverageSpeed

/** Fixed export canvas, matching released iOS RunShareCardFormat. Not a device screenshot. */
enum class RunImageFormat(val designHeight: Int, val exportHeight: Int) {
    STORY(640, 1920), FEED(450, 1350);
    val exportWidth: Int get() = 1080
    val aspectRatio: Float get() = 360f / designHeight
}

data class RunImageOptions(
    val format: RunImageFormat = RunImageFormat.STORY,
    val showSplits: Boolean = true,
    val showPhotos: Boolean = true,
    val showStartType: Boolean = true,
    val showName: Boolean = true
) {
    fun feedHidesSplits(hasPhotos: Boolean): Boolean =
        format == RunImageFormat.FEED && showPhotos && hasPhotos
    fun displaysSplits(hasPhotos: Boolean): Boolean = showSplits && !feedHidesSplits(hasPhotos)
    internal fun fingerprintFields(): List<String> = listOf(format.name, showSplits.toString(),
        showPhotos.toString(), showStartType.toString(), showName.toString())
}

/** Session image output deliberately remains Story-only, as in released iOS. */
data class SessionImageOptions(
    val showSplits: Boolean = true,
    val showPhotos: Boolean = true,
    val showName: Boolean = true
) {
    internal fun fingerprintFields(): List<String> = listOf(showSplits.toString(), showPhotos.toString(), showName.toString())
}

/** Center-crop mapping; don't draw an off-crop or invalid gate at an invented boundary. */
internal fun croppedGateFraction(position: Double?, sourceWidth: Int, sourceHeight: Int,
    targetAspectRatio: Float = 3f / 4f): Float? {
    if (position == null || !position.isFinite() || position !in 0.0..1.0 || sourceWidth <= 0 || sourceHeight <= 0) return null
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val retainedWidth = minOf(1.0, targetAspectRatio / sourceAspect)
    return ((position - (1.0 - retainedWidth) / 2) / retainedWidth)
        .takeIf { it in 0.0..1.0 }?.toFloat()
}

/** Always derive from canonical meters/seconds, never a rounded or previously converted display. */
internal fun runImageSpeed(distanceMeters: Double, timeSeconds: Double, unit: OverlaySpeedUnit): Double? =
    measuredAverageSpeed(distanceMeters, timeSeconds)?.let { speed ->
        (speed * unit.factor).takeIf { it.isFinite() }
    }
