package com.trackspeed.android.cloud

import android.content.Context
import android.util.Log
import com.trackspeed.android.cloud.dto.AthleteDto
import com.trackspeed.android.cloud.dto.RunDto
import com.trackspeed.android.cloud.dto.SessionDto
import com.trackspeed.android.data.local.entities.AthleteEntity
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabase: SupabaseClient,
    private val deviceIdProvider: DeviceIdProvider,
    private val authService: AuthService,
    private val storageService: StorageService
) {
    private val deletionPrefs by lazy {
        context.getSharedPreferences("pending_deletions", Context.MODE_PRIVATE)
    }

    private fun getDeviceId(): String = deviceIdProvider.deviceId

    suspend fun syncSession(entity: TrainingSessionEntity): Boolean {
        return try {
            val dto = SessionDto(
                id = entity.id,
                deviceId = getDeviceId(),
                userId = authService.currentUserId,
                name = entity.name,
                location = entity.location,
                notes = entity.notes,
                distance = entity.distance,
                startType = entity.startType,
                createdAt = Instant.ofEpochMilli(entity.createdAt).toString(),
                updatedAt = Instant.ofEpochMilli(entity.updatedAt).toString()
            )
            supabase.postgrest["sessions"].upsert(dto)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Session sync failed: ${entity.id}: ${e.safeCloudErrorCode()}")
            false
        }
    }

    suspend fun syncRun(entity: RunEntity, thumbnailUrl: String? = null): Boolean {
        return try {
            val dto = RunDto(
                id = entity.id,
                sessionId = entity.sessionId,
                userId = authService.currentUserId,
                athleteId = entity.athleteId,
                athleteName = entity.athleteName,
                athleteColor = entity.athleteColor,
                runNumber = entity.runNumber,
                timeSeconds = entity.timeSeconds,
                distance = entity.distance,
                startType = entity.startType,
                reactionTime = entity.reactionTime,
                isPersonalBest = entity.isPersonalBest,
                isSeasonBest = entity.isSeasonBest,
                thumbnailUrl = thumbnailUrl,
                splitsJson = entity.splitsJson,
                createdAt = Instant.ofEpochMilli(entity.createdAt).toString()
            )
            supabase.postgrest["runs"].upsert(dto)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Run sync failed: ${entity.id}: ${e.safeCloudErrorCode()}")
            false
        }
    }

    suspend fun fetchSessions(limit: Int = 50): List<SessionDto> {
        return try {
            val byDevice = supabase.postgrest["sessions"]
                .select {
                    filter { eq("device_id", getDeviceId()) }
                }
                .decodeList<SessionDto>()

            val userId = authService.currentUserId
            val byUser = if (!userId.isNullOrBlank()) {
                supabase.postgrest["sessions"]
                    .select {
                        filter { eq("user_id", userId) }
                    }
                    .decodeList<SessionDto>()
            } else {
                emptyList()
            }

            (byDevice + byUser)
                .distinctBy { it.id ?: "${it.deviceId}:${it.createdAt}:${it.name}" }
                .sortedByDescending { it.createdAt ?: it.updatedAt ?: "" }
                .take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchRuns(sessionId: String): List<RunDto> {
        return try {
            supabase.postgrest["runs"]
                .select {
                    filter { eq("session_id", sessionId) }
                }
                .decodeList<RunDto>()
                .sortedBy { it.runNumber }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun syncAthlete(entity: AthleteEntity): Boolean {
        return try {
            val dto = AthleteDto(
                id = entity.id,
                deviceId = getDeviceId(),
                // RLS lockdown: athletes.user_id must equal auth.uid().
                // Anonymous Supabase users (created by ensureAnonymousSession at
                // app launch) have a valid currentUserId, so guests still pass.
                userId = authService.currentUserId,
                name = entity.name,
                nickname = entity.nickname,
                color = entity.color,
                photoUrl = resolveAthletePhotoUrl(entity),
                birthdate = entity.birthdate?.let { epochMillis ->
                    Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                },
                gender = entity.gender,
                personalBests = entity.personalBests(),
                seasonBests = entity.seasonBests(),
                createdAt = Instant.ofEpochMilli(entity.createdAt).toString(),
                updatedAt = Instant.ofEpochMilli(entity.updatedAt).toString()
            )
            supabase.postgrest["athletes"].upsert(dto)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Athlete sync failed: ${entity.id}: ${e.safeCloudErrorCode()}")
            false
        }
    }

    suspend fun fetchAthletes(): List<AthleteDto> {
        return try {
            supabase.postgrest["athletes"]
                .select {
                    filter { eq("device_id", getDeviceId()) }
                }
                .decodeList<AthleteDto>()
                .sortedBy { it.name.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveAthletePhotoUrl(entity: AthleteEntity): String? {
        val path = entity.photoPath ?: return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val file = File(path)
        if (!file.exists()) return null
        return storageService.uploadAthletePhoto(entity.id, file.readBytes())
    }

    suspend fun deleteAthlete(id: String): Boolean {
        val deleted = deleteAthleteRemote(id)
        if (!deleted) {
            enqueuePendingAthleteDeletion(id)
        }
        return deleted
    }

    suspend fun deleteSession(id: String): Boolean {
        val deleted = deleteSessionRemote(id)
        if (!deleted) {
            enqueuePendingSessionDeletion(id)
        }
        return deleted
    }

    suspend fun deleteRun(id: String): Boolean {
        val deleted = deleteRunRemote(id)
        if (!deleted) {
            enqueuePendingRunDeletion(id)
        }
        return deleted
    }

    suspend fun processPendingAthleteDeletions(): Int {
        val ids = pendingAthleteDeletionIds()
        var deletedCount = 0
        for (id in ids) {
            if (deleteAthleteRemote(id)) {
                removePendingAthleteDeletion(id)
                deletedCount++
            }
        }
        return deletedCount
    }

    suspend fun processPendingSessionDeletions(): Int {
        val ids = pendingSessionDeletionIds()
        var deletedCount = 0
        for (id in ids) {
            if (deleteSessionRemote(id)) {
                removePendingSessionDeletion(id)
                deletedCount++
            }
        }
        return deletedCount
    }

    suspend fun processPendingRunDeletions(): Int {
        val ids = pendingRunDeletionIds()
        var deletedCount = 0
        for (id in ids) {
            if (deleteRunRemote(id)) {
                removePendingRunDeletion(id)
                deletedCount++
            }
        }
        return deletedCount
    }

    suspend fun processPendingCloudDeletions(): Int {
        return processPendingAthleteDeletions() +
            processPendingSessionDeletions() +
            processPendingRunDeletions()
    }

    fun clearPendingCloudDeletions() {
        deletionPrefs.edit()
            .remove(PENDING_SESSION_DELETIONS)
            .remove(PENDING_RUN_DELETIONS)
            .remove(PENDING_ATHLETE_DELETIONS)
            .apply()
    }

    private suspend fun deleteAthleteRemote(id: String): Boolean {
        return try {
            supabase.postgrest["athletes"].delete {
                filter { eq("id", id) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Athlete delete failed, will retry: $id: ${e.safeCloudErrorCode()}")
            false
        }
    }

    private suspend fun deleteSessionRemote(id: String): Boolean {
        return try {
            supabase.postgrest["runs"].delete {
                filter { eq("session_id", id) }
            }
            supabase.postgrest["sessions"].delete {
                filter { eq("id", id) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Session delete failed, will retry: $id: ${e.safeCloudErrorCode()}")
            false
        }
    }

    private suspend fun deleteRunRemote(id: String): Boolean {
        return try {
            supabase.postgrest["runs"].delete {
                filter { eq("id", id) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Run delete failed, will retry: $id: ${e.safeCloudErrorCode()}")
            false
        }
    }

    private fun enqueuePendingAthleteDeletion(id: String) {
        val normalized = normalizedUuidString(id) ?: return
        storePendingDeletionIds(PENDING_ATHLETE_DELETIONS, pendingAthleteDeletionIds() + normalized)
    }

    private fun enqueuePendingSessionDeletion(id: String) {
        val normalized = normalizedUuidString(id) ?: return
        storePendingDeletionIds(PENDING_SESSION_DELETIONS, pendingSessionDeletionIds() + normalized)
    }

    private fun enqueuePendingRunDeletion(id: String) {
        val normalized = normalizedUuidString(id) ?: return
        storePendingDeletionIds(PENDING_RUN_DELETIONS, pendingRunDeletionIds() + normalized)
    }

    private fun removePendingAthleteDeletion(id: String) {
        val normalized = normalizedUuidString(id) ?: id
        storePendingDeletionIds(PENDING_ATHLETE_DELETIONS, pendingAthleteDeletionIds() - normalized)
    }

    private fun removePendingSessionDeletion(id: String) {
        val normalized = normalizedUuidString(id) ?: id
        storePendingDeletionIds(PENDING_SESSION_DELETIONS, pendingSessionDeletionIds() - normalized)
    }

    private fun removePendingRunDeletion(id: String) {
        val normalized = normalizedUuidString(id) ?: id
        storePendingDeletionIds(PENDING_RUN_DELETIONS, pendingRunDeletionIds() - normalized)
    }

    private fun pendingAthleteDeletionIds(): Set<String> {
        return pendingDeletionIds(PENDING_ATHLETE_DELETIONS)
    }

    private fun pendingSessionDeletionIds(): Set<String> {
        return pendingDeletionIds(PENDING_SESSION_DELETIONS)
    }

    private fun pendingRunDeletionIds(): Set<String> {
        return pendingDeletionIds(PENDING_RUN_DELETIONS)
    }

    private fun pendingDeletionIds(key: String): Set<String> {
        val stored = deletionPrefs.getStringSet(key, emptySet()) ?: emptySet()
        val sanitized = stored.mapNotNull(::normalizedUuidString).toSet()
        if (sanitized != stored) {
            storePendingDeletionIds(key, sanitized)
            Log.i(TAG, "Cleaned invalid or duplicate pending deletion IDs")
        }
        return sanitized
    }

    private fun storePendingDeletionIds(key: String, ids: Set<String>) {
        if (ids.isEmpty()) {
            deletionPrefs.edit().remove(key).apply()
        } else {
            deletionPrefs.edit().putStringSet(key, ids).apply()
        }
    }

    private fun normalizedUuidString(id: String): String? {
        return runCatching { UUID.fromString(id).toString() }.getOrNull()
    }

    companion object {
        private const val TAG = "CloudSyncService"
        private const val PENDING_ATHLETE_DELETIONS = "pending_athlete_deletions"
        private const val PENDING_SESSION_DELETIONS = "pending_session_deletions"
        private const val PENDING_RUN_DELETIONS = "pending_run_deletions"
    }
}
