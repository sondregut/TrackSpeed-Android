package com.trackspeed.android.cloud

import com.trackspeed.android.cloud.dto.ProfileDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileService @Inject constructor(
    private val supabase: SupabaseClient
) {
    suspend fun syncProfile(profile: ProfileDto) {
        try {
            supabase.postgrest["profiles"].upsert(profile) {
                onConflict = "supabase_user_id"
            }
        } catch (e: Exception) {
            // Non-blocking -- profile sync is best-effort
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
}
