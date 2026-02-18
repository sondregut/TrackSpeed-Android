package com.trackspeed.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "training_sessions")
data class TrainingSessionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val date: Long,
    val name: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val distance: Double,
    val startType: String,
    val thumbnailPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
