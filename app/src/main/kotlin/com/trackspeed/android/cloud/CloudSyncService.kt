package com.trackspeed.android.cloud

import android.content.Context
import com.trackspeed.android.cloud.dto.AthleteDto
import com.trackspeed.android.cloud.dto.RunDto
import com.trackspeed.android.cloud.dto.SessionDto
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabase: SupabaseClient
) {
    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("trackspeed", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: run {
            val newId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", newId).apply()
            newId
        }
    }

    suspend fun syncSession(entity: TrainingSessionEntity) {
        try {
            val dto = SessionDto(
                id = entity.id,
                deviceId = getDeviceId(),
                name = entity.name,
                location = entity.location,
                notes = entity.notes,
                distance = entity.distance,
                startType = entity.startType,
                createdAt = Instant.ofEpochMilli(entity.createdAt).toString(),
                updatedAt = Instant.ofEpochMilli(entity.updatedAt).toString()
            )
            supabase.postgrest["sessions"].upsert(dto)
        } catch (_: Exception) { }
    }

    suspend fun syncRun(entity: RunEntity, thumbnailUrl: String? = null) {
        try {
            val dto = RunDto(
                id = entity.id,
                sessionId = entity.sessionId,
                athleteId = entity.athleteId,
                athleteName = entity.athleteName,
                athleteColor = entity.athleteColor,
                runNumber = entity.runNumber,
                timeSeconds = entity.timeSeconds,
                distance = entity.distance,
                startType = entity.startType,
                reactionTime = entity.reactionTime,
                isPersonalBest = entity.isPersonalBest,
                thumbnailUrl = thumbnailUrl,
                createdAt = Instant.ofEpochMilli(entity.createdAt).toString()
            )
            supabase.postgrest["runs"].upsert(dto)
        } catch (_: Exception) { }
    }

    suspend fun syncAthlete(entity: AthleteEntity) {
        try {
            val dto = AthleteDto(
                id = entity.id,
                deviceId = getDeviceId(),
                name = entity.name,
                nickname = entity.nickname,
                color = entity.color,
                createdAt = Instant.ofEpochMilli(entity.createdAt).toString()
            )
            supabase.postgrest["athletes"].upsert(dto)
        } catch (_: Exception) { }
    }
}
