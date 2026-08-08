package com.trackspeed.android.cloud

import android.util.Log
import com.trackspeed.android.cloud.dto.ProfileDto
import com.trackspeed.android.data.model.FlyingDistance
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileService @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val syncMutex = Mutex()

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

    suspend fun syncProfile(profile: ProfileDto): Boolean {
        if (isRlsBlocked) {
            Log.i(TAG, "Skipping profile sync: blocked by previous RLS failure")
            return false
        }
        if (!syncMutex.tryLock()) {
            Log.i(TAG, "Skipping profile sync: already in progress")
            return false
        }

        lastError = null
        return try {
            performProfileWrite(profile)
        } catch (e: Exception) {
            lastError = e.safeCloudErrorCode()
            if (!isRlsError(e)) {
                Log.w(TAG, "Profile sync failed: ${e.safeCloudErrorCode()}")
                false
            } else {
                retryProfileWriteAfterAuthRefresh(profile, e)
            }
        } finally {
            syncMutex.unlock()
        }
    }

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

    suspend fun updateFlyingPr(distance: FlyingDistance, timeSeconds: Double): Boolean {
        if (isRlsBlocked) {
            Log.i(TAG, "Skipping flying PR sync: blocked by previous profile RLS failure")
            return false
        }

        val userId = supabase.auth.currentUserOrNull()?.id ?: return false
        return try {
            supabase.postgrest["profiles"].update({
                set("flying_pr_distance", distance.rawValue)
                set("flying_pr", timeSeconds)
                set("updated_at", Instant.now().toString())
            }) {
                filter { eq("supabase_user_id", userId) }
            }
            true
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

    private suspend fun performProfileWrite(profile: ProfileDto): Boolean {
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

        supabase.postgrest["profiles"].upsert(profile) {
            onConflict = "supabase_user_id"
        }
        return true
    }

    private suspend fun retryProfileWriteAfterAuthRefresh(profile: ProfileDto, initialError: Exception): Boolean {
        Log.w(TAG, "Profile sync hit RLS; refreshing auth and retrying once: ${initialError.safeCloudErrorCode()}")
        return try {
            supabase.auth.refreshCurrentSession()
            val succeeded = performProfileWrite(profile)
            if (succeeded) {
                isRlsBlocked = false
                lastError = null
                Log.i(TAG, "Profile sync succeeded after auth refresh")
            }
            succeeded
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
