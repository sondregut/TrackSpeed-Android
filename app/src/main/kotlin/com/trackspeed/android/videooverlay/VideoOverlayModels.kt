package com.trackspeed.android.videooverlay

import android.net.Uri
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.model.StartType
import com.trackspeed.android.protocol.SegmentSplit
import com.trackspeed.android.ui.util.parseSegmentSplits
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

data class ImportedVideo(
    val uri: Uri,
    val file: File,
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val hasAudio: Boolean
) {
    val displayAspectRatio: Float
        get() {
            if (width <= 0 || height <= 0) return 1f
            val quarterTurn = ((rotationDegrees % 360) + 360) % 360 in setOf(90, 270)
            return if (quarterTurn) height.toFloat() / width else width.toFloat() / height
        }
}

data class SplitMark(
    val label: String,
    val raceTimeSeconds: Double,
    val segmentTimeSeconds: Double = raceTimeSeconds,
    val segmentLabel: String = label
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
    val showRunType: Boolean,
    val preferences: OverlayPreferences = OverlayPreferences(),
    val averageSpeedLabel: String = "AVG SPEED",
    val sourceAspectRatio: Float? = null
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
            visibleSplits = if (preferences.splitMode == OverlaySplitMode.OFF) emptyList() else
                splits.filter { it.raceTimeSeconds.isFinite() && it.raceTimeSeconds > 0 &&
                    currentTimeSeconds >= startMarkerTimeSeconds + it.raceTimeSeconds }.takeLast(3),
            runTypeLabel = if (showRunType) runTypeLabel else null,
            speedDisplay = if (showSpeed && phase == RaceOverlayPhase.FINISHED) speedDisplay else null
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
    showRunType: Boolean,
    preferences: OverlayPreferences = OverlayPreferences()
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
        showRunType = showRunType,
        preferences = preferences
    )
}

fun formatOverlayTime(seconds: Double): String {
    val clamped = if (seconds.isFinite()) seconds.coerceIn(0.0, 359999.99) else 0.0
    val totalHundredths = (clamped * 100.0).roundToLong()
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
        return if (run.timeSeconds.isFinite() && run.timeSeconds > 0.0) {
            listOf(SplitMark(label = "FINISH", raceTimeSeconds = run.timeSeconds))
        } else {
            emptyList()
        }
    }

    val valid = raw.filter { it.cumulativeSplitNanos > 0 && it.splitNanos > 0 &&
        it.splitNanos <= it.cumulativeSplitNanos && it.distanceMeters.isFinite() &&
        it.cumulativeDistanceMeters.isFinite() }.sortedBy { it.cumulativeSplitNanos }
    return valid.mapIndexed { index, split ->
        val isLast = index == valid.lastIndex
        SplitMark(
            label = if (isLast) "FINISH" else split.distanceLabel(),
            raceTimeSeconds = split.cumulativeSplitNanos / 1_000_000_000.0,
            segmentTimeSeconds = split.splitNanos / 1_000_000_000.0,
            segmentLabel = "${formatDistanceLabel((split.cumulativeDistanceMeters - split.distanceMeters).coerceAtLeast(0.0))}–${split.distanceLabel()}"
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

private fun buildSpeedDisplay(run: RunEntity, speedUnit: String): String? =
    measuredAverageSpeed(run.distance, run.timeSeconds)?.let { OverlaySpeedUnit.fromRaw(speedUnit).format(it) }

private fun formatDistanceLabel(distance: Double): String {
    return if (kotlin.math.abs(distance - distance.toInt()) < 0.05) {
        "${distance.toInt()}m"
    } else {
        String.format(Locale.US, "%.1fm", distance)
    }
}
