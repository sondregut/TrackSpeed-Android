package com.trackspeed.android.ui.components

data class LiveThumbnailSize(val width: Float, val height: Float)

fun liveThumbnailSize(imageWidth: Int?, imageHeight: Int?, compact: Boolean, expanded: Boolean = false): LiveThumbnailSize {
    val aspect = if (imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0)
        imageWidth.toFloat() / imageHeight else 9f / 16f
    val longEdge = if (expanded) (if (compact) 128f else 144f) else (if (compact) 66f else 80f)
    return if (aspect > 1f) LiveThumbnailSize(longEdge, longEdge / aspect)
        else LiveThumbnailSize(longEdge * aspect, longEdge)
}
