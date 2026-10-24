package com.trackspeed.android.cloud

import android.util.Log
import com.trackspeed.android.cloud.dto.ProfileDto
import com.trackspeed.android.ui.screens.profile.ProfilePersonalBestValues
import com.trackspeed.android.ui.screens.profile.ProfilePersonalBestStore
import com.trackspeed.android.ui.screens.profile.ProfilePersonalBestSnapshot
import com.trackspeed.android.ui.screens.profile.FlyingPrSyncSnapshot
import io.github.jan.supabase.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileService @Inject constructor(
    private val supabase: SupabaseClient,
    private val personalBestStore: ProfilePersonalBestStore,
    private val workloadCoordinator: TimingWorkloadCoordinator
) {
    private val syncMutex = Mutex()

    // Local SDK session lookup only; callers capture this before queueing a PR.
    val currentProfileUserId: String?
        get() = supabase.auth.currentUserOrNull()?.id

    @Volatile
    var isRlsBlocked: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun clearRlsBlock() {
        isRlsBlocked = false
        lastError = null
        Log.i(TAG, "Profile RLS block cleared after confirmed auth")
    }

    suspend fun syncProfile(
        profile: ProfileDto,
        manualPersonalBest: ProfilePersonalBestValues? = null,
        expectedPersonalBest: ProfilePersonalBestSnapshot? = null
    ): Boolean = syncMutex.withProfileWriteLock(
        waitForTurn = manualPersonalBest != null,
        expectedUserId = profile.supabaseUserId,
        currentUserId = { currentProfileUserId },
        canWrite = { canWriteProfile(expectedPersonalBest) }
    ) {
        if (isRlsBlocked) {
            Log.i(TAG, "Skipping profile sync: blocked by previous RLS failure")
            return@withProfileWriteLock false
        }
        lastError = null
        try {
            performProfileWrite(profile, manualPersonalBest, expectedPersonalBest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastError = e.safeCloudErrorCode()
            if (!isRlsError(e)) {
                Log.w(TAG, "Profile sync failed: ${e.safeCloudErrorCode()}")
                false
            } else {
                retryProfileWriteAfterAuthRefresh(profile, e, manualPersonalBest, expectedPersonalBest)
            }
        }
    }

    private suspend fun canWriteProfile(snapshot: ProfilePersonalBestSnapshot?): Boolean =
        !workloadCoordinator.isLiveTimingActive &&
            (snapshot == null || personalBestStore.isCurrent(snapshot)) &&
            !workloadCoordinator.isLiveTimingActive

    suspend fun fetchProfile(supabaseUserId: String): ProfileDto? {
        return try {
            supabase.postgrest["profiles"]
                .select {
                    filter { eq("supabase_user_id", supabaseUserId) }
                }
                .decodeSingleOrNull<ProfileDto>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun updateFlyingPr(ticket: FlyingPrSyncSnapshot): Boolean {
        val userId = ticket.accountId ?: return false
        return syncMutex.withProfileWriteLock(
            waitForTurn = false,
            expectedUserId = userId,
            currentUserId = { currentProfileUserId },
            canWrite = {
                !workloadCoordinator.isLiveTimingActive && personalBestStore.isCurrentFlyingPr(ticket) &&
                    !workloadCoordinator.isLiveTimingActive
            }
        ) {
            if (isRlsBlocked) {
                Log.i(TAG, "Skipping flying PR sync: blocked by previous profile RLS failure")
                return@withProfileWriteLock false
            }
            try {
                supabase.postgrest["profiles"].update({
                    set("flying_pr_distance", ticket.distance.rawValue)
                    set("flying_pr", ticket.timeSeconds)
                    set("updated_at", Instant.now().toString())
                }) {
                    filter { eq("supabase_user_id", userId) }
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                lastError = e.safeCloudErrorCode()
                if (isRlsError(e)) {
                    isRlsBlocked = true
                    Log.w(TAG, "Flying PR sync hit RLS; blocking profile retries until next auth: ${e.safeCloudErrorCode()}")
                } else {
                    Log.w(TAG, "Flying PR sync failed: ${e.safeCloudErrorCode()}")
                }
                false
            }
        }
    }

    suspend fun uploadProfilePhoto(profileId: String, imageData: ByteArray): String? {
        return try {
            val path = "profiles/$profileId.jpg"
            supabase.storage["profile-photos"].upload(path, imageData) { upsert = true }
            supabase.postgrest["profiles"].update({
                set("photo_url", path)
            }) {
                filter { eq("id", profileId) }
            }
            path
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getProfilePhotoUrl(path: String): String {
        return supabase.storage["profile-photos"].publicUrl(path)
    }

    private suspend fun performProfileWrite(
        profile: ProfileDto,
        manualPersonalBest: ProfilePersonalBestValues?,
        expectedPersonalBest: ProfilePersonalBestSnapshot?
    ): Boolean {
        // Also runs after an auth refresh: a retry cannot resurrect an old PR.
        if (!canWriteProfile(expectedPersonalBest)) return false
        val authUserId = supabase.auth.currentUserOrNull()?.id
        if (authUserId.isNullOrBlank()) {
            Log.i(TAG, "Skipping profile sync: no current Supabase user")
            return false
        }
        if (authUserId != profile.supabaseUserId) {
            Log.w(
                TAG,
                "Skipping profile sync: profile user ${profile.supabaseUserId} does not match auth user $authUserId"
            )
            return false
        }

        if (manualPersonalBest == null) {
            supabase.postgrest["profiles"].upsert(profile) {
                onConflict = "supabase_user_id"
            }
        } else {
            val payload = profileWritePayload(supabase.defaultSerializer.encodeToJsonElement(profile).jsonObject, manualPersonalBest)
            supabase.postgrest["profiles"].upsert(payload) {
                onConflict = "supabase_user_id"
            }
        }
        return true
    }

    private suspend fun retryProfileWriteAfterAuthRefresh(
        profile: ProfileDto,
        initialError: Exception,
        manualPersonalBest: ProfilePersonalBestValues?,
        expectedPersonalBest: ProfilePersonalBestSnapshot?
    ): Boolean {
        Log.w(TAG, "Profile sync hit RLS; refreshing auth and retrying once: ${initialError.safeCloudErrorCode()}")
        return try {
            if (!canWriteProfile(expectedPersonalBest) || currentProfileUserId != profile.supabaseUserId) return false
            supabase.auth.refreshCurrentSession()
            val succeeded = performProfileWrite(profile, manualPersonalBest, expectedPersonalBest)
            if (succeeded) {
                isRlsBlocked = false
                lastError = null
                Log.i(TAG, "Profile sync succeeded after auth refresh")
            }
            succeeded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (retryError: Exception) {
            isRlsBlocked = true
            lastError = retryError.safeCloudErrorCode()
            Log.w(TAG, "Profile sync still blocked by RLS after auth refresh: ${retryError.safeCloudErrorCode()}")
            false
        }
    }

    private fun isRlsError(error: Exception): Boolean {
        val text = error.toString().lowercase()
        return "42501" in text || "row-level security" in text
    }

    companion object {
        private const val TAG = "ProfileService"
    }
}
