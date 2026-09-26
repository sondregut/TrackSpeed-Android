package com.trackspeed.android.ui.util

import com.trackspeed.android.protocol.SegmentSplit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

fun parseSegmentSplits(splitsJson: String?): List<SegmentSplit> {
    if (splitsJson.isNullOrBlank()) return emptyList()
    return runCatching {
        Json.decodeFromString<List<SegmentSplit>>(splitsJson)
    }.getOrDefault(emptyList())
}

fun formatSplitDuration(splitNanos: Long): String {
    return formatTime(splitNanos / 1_000_000_000.0)
}

fun formatSegmentLabel(segment: SegmentSplit): String {
    return "Gate ${segment.fromGateIndex}-${segment.toGateIndex}"
}
