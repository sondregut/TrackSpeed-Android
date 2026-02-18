package com.trackspeed.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "athletes")
data class AthleteEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val nickname: String? = null,
    val color: String,
    val photoPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
