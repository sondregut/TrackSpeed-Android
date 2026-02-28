package com.trackspeed.android.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageService @Inject constructor(
    private val supabase: SupabaseClient
) {
    suspend fun uploadThumbnail(bucket: String, path: String, imageData: ByteArray): String? {
        return try {
            supabase.storage[bucket].upload(path, imageData) { upsert = true }
            supabase.storage[bucket].publicUrl(path)
        } catch (e: Exception) {
            null
        }
    }

    fun getPublicUrl(bucket: String, path: String): String {
        return supabase.storage[bucket].publicUrl(path)
    }
}
