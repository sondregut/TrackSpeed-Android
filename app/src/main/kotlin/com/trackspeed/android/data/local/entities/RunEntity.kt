package com.trackspeed.android.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "runs",
    foreignKeys = [
        ForeignKey(
            entity = TrainingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class RunEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val athleteId: String? = null,
    val athleteName: String? = null,
    val athleteColor: String? = null,
    val runNumber: Int,
    val timeSeconds: Double,
    val distance: Double,
    val startType: String,
    val numberOfPhones: Int = 1,
    val reactionTime: Double? = null,
    val isPersonalBest: Boolean = false,
    val isSeasonBest: Boolean = false,
    val thumbnailPath: String? = null,
    val startImagePath: String? = null,
    val finishImagePath: String? = null,
    val lapImagePathsJson: String? = null,
    val splitsJson: String? = null,
    val gatePosition: Double = 0.5,
    val crossingVelocity: Double? = null,
    val startGatePosition: Double? = null,
    val finishGatePosition: Double? = null,
    val startCrossingVelocity: Double? = null,
    val finishCrossingVelocity: Double? = null,
    val startCrossingDirection: String? = null,
    val finishCrossingDirection: String? = null,
    val startWorkResolutionWidth: Int? = null,
    val finishWorkResolutionWidth: Int? = null,
    val workResolutionWidth: Int? = null,
    val startThumbnailDebugJson: String? = null,
    val finishThumbnailDebugJson: String? = null,
    val timingDiagnosticsJson: String? = null,
    val localGateFramesDataJson: String? = null,
    val finishDetectorY: Double? = null,
    val finishInterpolationAlpha: Double? = null,
    val finishFramePick: String? = null,
    val finishS0: Double? = null,
    val finishS1: Double? = null,
    val finishIsFrontCamera: Boolean? = null,
    val finishDetectorTriggerFramePts: Long? = null,
    val finishChosenThumbnailFramePts: Long? = null,
    val finishSavedThumbnailFramePts: Long? = null,
    val localGateRole: String? = null,
    val cloudRunId: String? = null,
    val cloudSyncPolicy: String? = null,
    val crossingTimestampNanos: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
