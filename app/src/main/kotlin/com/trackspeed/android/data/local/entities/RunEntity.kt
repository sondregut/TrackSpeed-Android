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
    val reactionTime: Double? = null,
    val isPersonalBest: Boolean = false,
    val isSeasonBest: Boolean = false,
    val thumbnailPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
