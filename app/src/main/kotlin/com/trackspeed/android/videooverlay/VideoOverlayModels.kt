package com.trackspeed.android.videooverlay

import android.net.Uri
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.model.StartType
import com.trackspeed.android.protocol.SegmentSplit
import com.trackspeed.android.ui.util.parseSegmentSplits
import java.io.File
import java.util.Locale

data class ImportedVideo(
    val uri: Uri,
    val file: File,
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val hasAudio: Boolean
)

data class SplitMark(
    val label: String,
    val raceTimeSeconds: Double
)

data class VideoOverlaySnapshot(
    val sourceUri: Uri,
    val finalTimeSeconds: Double,
    val splits: List<SplitMark>,
    val runTypeLabel: String?,
    val readyLabel: String,
    val speedDisplay: String?,
    val startMarkerTimeSeconds: Double,
    val showSpeed: Boolean,
    val showRunType: Boolean
) {
    fun frameState(currentTimeSeconds: Double): RaceOverlayFrameState {
        val raceTime = (currentTimeSeconds - startMarkerTimeSeconds).coerceAtLeast(0.0)
        val phase = when {
            currentTimeSeconds < startMarkerTimeSeconds -> RaceOverlayPhase.READY
            finalTimeSeconds > 0.0 && raceTime >= finalTimeSeconds -> RaceOverlayPhase.FINISHED
            else -> RaceOverlayPhase.RUNNING
        }
        val displayTime = when (phase) {
            RaceOverlayPhase.READY -> 0.0
            RaceOverlayPhase.RUNNING -> raceTime
            RaceOverlayPhase.FINISHED -> finalTimeSeconds
        }
        return RaceOverlayFrameState(
            phase = phase,
            displayedTimeSeconds = displayTime,
            visibleSplits = splits.filter { currentTimeSeconds >= startMarkerTimeSeconds + it.raceTimeSeconds },
            runTypeLabel = if (showRunType) runTypeLabel else null,
            speedDisplay = if (showSpeed) speedDisplay else null
        )
    }
}

data class RaceOverlayFrameState(
    val phase: RaceOverlayPhase,
    val displayedTimeSeconds: Double,
    val visibleSplits: List<SplitMark>,
    val runTypeLabel: String?,
    val speedDisplay: String?
)

enum class RaceOverlayPhase {
    READY,
    RUNNING,
    FINISHED
}

fun RunEntity.toVideoOverlaySnapshot(
    sourceUri: Uri,
    speedUnit: String,
    startMarkerTimeSeconds: Double,
    showSpeed: Boolean,
    showRunType: Boolean
): VideoOverlaySnapshot {
    return VideoOverlaySnapshot(
        sourceUri = sourceUri,
        finalTimeSeconds = timeSeconds,
        splits = buildSplitMarks(this),
        runTypeLabel = buildRunTypeLabel(this),
        readyLabel = "READY",
        speedDisplay = buildSpeedDisplay(this, speedUnit),
        startMarkerTimeSeconds = startMarkerTimeSeconds,
        showSpeed = showSpeed,
        showRunType = showRunType
    )
}

fun formatOverlayTime(seconds: Double): String {
    val clamped = seconds.coerceAtLeast(0.0)
    val totalHundredths = (clamped * 100.0).toLong()
    val minutes = totalHundredths / 6000
    val wholeSeconds = (totalHundredths % 6000) / 100
    val hundredths = totalHundredths % 100
    return if (minutes > 0) {
        String.format(Locale.US, "%d:%02d.%02d", minutes, wholeSeconds, hundredths)
    } else {
        String.format(Locale.US, "%d.%02d", wholeSeconds, hundredths)
    }
}

private fun buildSplitMarks(run: RunEntity): List<SplitMark> {
    val raw = parseSegmentSplits(run.splitsJson)
    if (raw.isEmpty()) {
        return if (run.timeSeconds > 0.0) {
            listOf(SplitMark(label = "FINISH", raceTimeSeconds = run.timeSeconds))
        } else {
            emptyList()
        }
    }

    return raw.mapIndexed { index, split ->
        val isLast = index == raw.lastIndex
        SplitMark(
            label = if (isLast) "FINISH" else split.distanceLabel(),
            raceTimeSeconds = split.cumulativeSplitNanos / 1_000_000_000.0
        )
    }
}

private fun SegmentSplit.distanceLabel(): String {
    val meters = cumulativeDistanceMeters.takeIf { it > 0.0 } ?: distanceMeters
    return if (kotlin.math.abs(meters - meters.toInt()) < 0.05) {
        "${meters.toInt()}m"
    } else {
        String.format(Locale.US, "%.1fm", meters)
    }
}

private fun buildRunTypeLabel(run: RunEntity): String {
    val startType = StartType.fromRawValue(run.startType).shortName
    return if (run.distance > 0.0) {
        "$startType ${formatDistanceLabel(run.distance)}"
    } else {
        startType
    }
}

private fun buildSpeedDisplay(run: RunEntity, speedUnit: String): String? {
    if (run.distance <= 0.0 || run.timeSeconds <= 0.0) return null
    val metersPerSecond = run.distance / run.timeSeconds
    return when (speedUnit) {
        "km/h" -> String.format(Locale.US, "%.1f km/h", metersPerSecond * 3.6)
        "mph" -> String.format(Locale.US, "%.1f mph", metersPerSecond * 2.23694)
        else -> String.format(Locale.US, "%.1f m/s", metersPerSecond)
    }
}

private fun formatDistanceLabel(distance: Double): String {
    return if (kotlin.math.abs(distance - distance.toInt()) < 0.05) {
        "${distance.toInt()}m"
    } else {
        String.format(Locale.US, "%.1fm", distance)
    }
}
