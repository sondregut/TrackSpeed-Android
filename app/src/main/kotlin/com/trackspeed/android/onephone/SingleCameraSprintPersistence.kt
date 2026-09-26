package com.trackspeed.android.onephone

import com.trackspeed.android.model.StartType
import com.trackspeed.android.protocol.SegmentSplit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * History persistence contract for one-phone landscape runs, mirroring iOS
 * `SingleCameraSprintHistoryPersistence` / `PersistedOnePhoneSprintDiagnostics`
 * (without pose samples or video, which Android does not retain yet).
 *
 * Runs are stored local-only: they are excluded from cloud sync and from
 * personal/season-best comparison with gate-timed runs.
 */
object SingleCameraSprintPersistence {
    const val CLOUD_SYNC_POLICY = "localOnlyOnePhoneSprint"
    const val SESSION_NAME_SPRINT = "One-phone Sprint"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Envelope(val onePhoneSprint: Diagnostics)

    @Serializable
    data class Diagnostics(
        val version: Int = 1,
        val peakVelocityMetersPerSecond: Double,
        val peakVelocityDistanceMeters: Double,
        val averagePoseConfidence: Double,
        val estimatedPoseFPS: Double,
        val calibrationResidualMeters: Double,
        val qualityRaw: String,
        val algorithmVersion: String,
        val startStyleRaw: String,
        val directionRaw: String,
        val drillRaw: String,
        val phoneMotionWarning: Boolean,
        val gates: List<Gate>,
        val splits: List<Split>,
        val trace: List<PositionSample>
    )

    @Serializable
    data class Gate(val distanceMeters: Double, val normalizedX: Double)

    @Serializable
    data class Split(val distanceMeters: Double, val crossingTimestamp: Double, val elapsedSeconds: Double)

    @Serializable
    data class PositionSample(val timestamp: Double, val distanceMeters: Double, val confidence: Double)

    fun persistedStartType(startStyle: SingleCameraSprintStartStyle): String =
        if (startStyle == SingleCameraSprintStartStyle.STANDING) StartType.IN_FRAME.rawValue else StartType.FLYING.rawValue

    fun sessionName(drill: SingleCameraSprintDrill): String = when (drill) {
        SingleCameraSprintDrill.SPRINT -> SESSION_NAME_SPRINT
        SingleCameraSprintDrill.PRO_AGILITY -> "5-10-5 (yards)"
        SingleCameraSprintDrill.SHUTTLE -> "Out and back"
    }

    fun diagnosticsJson(record: SingleCameraSprintRunRecord): String = json.encodeToString(
        Envelope(
            Diagnostics(
                peakVelocityMetersPerSecond = record.result.peakVelocityMetersPerSecond,
                peakVelocityDistanceMeters = record.result.peakVelocityDistanceMeters,
                averagePoseConfidence = record.result.averagePoseConfidence,
                estimatedPoseFPS = record.result.estimatedPoseFPS,
                calibrationResidualMeters = record.result.calibrationResidualMeters,
                qualityRaw = record.result.quality.rawValue,
                algorithmVersion = record.result.algorithmVersion,
                startStyleRaw = record.result.startStyle.rawValue,
                directionRaw = record.direction.rawValue,
                drillRaw = record.result.drill.rawValue,
                phoneMotionWarning = record.phoneMotionWarning,
                gates = record.gates.map { Gate(it.distanceMeters, it.normalizedX) },
                splits = record.result.splits.map { Split(it.distanceMeters, it.crossingTimestamp, it.elapsedSeconds) },
                trace = record.result.trace.map { PositionSample(it.timestamp, it.distanceMeters, it.confidence) }
            )
        )
    )

    fun decodeDiagnostics(timingDiagnosticsJson: String?): Diagnostics? {
        if (timingDiagnosticsJson.isNullOrBlank() || !timingDiagnosticsJson.contains("\"onePhoneSprint\"")) return null
        return runCatching { json.decodeFromString<Envelope>(timingDiagnosticsJson).onePhoneSprint }.getOrNull()
    }

    /** Cumulative gate splits in the shared `SegmentSplit` history format. */
    fun segmentSplits(splits: List<SingleCameraSprintSplit>): List<SegmentSplit> {
        var previousNanos = 0L
        var previousDistance = 0.0
        return splits.sortedBy { it.elapsedSeconds }.mapIndexed { index, split ->
            val cumulative = (max(0.0, split.elapsedSeconds) * 1_000_000_000).toLong()
            val segment = SegmentSplit(
                fromGateIndex = index,
                toGateIndex = index + 1,
                fromGateId = "gate-$index",
                toGateId = "gate-${index + 1}",
                splitNanos = if (cumulative >= previousNanos) cumulative - previousNanos else 0,
                distanceMeters = max(0.0, split.distanceMeters - previousDistance),
                cumulativeSplitNanos = cumulative,
                cumulativeDistanceMeters = split.distanceMeters
            )
            previousNanos = cumulative
            previousDistance = split.distanceMeters
            segment
        }
    }
}
