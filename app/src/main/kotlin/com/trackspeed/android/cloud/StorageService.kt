package com.trackspeed.android.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
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

    suspend fun uploadAthletePhoto(athleteId: String, imageData: ByteArray): String? {
        val path = "athletes/$athleteId.jpg"
        return uploadThumbnail(
            bucket = "athlete-photos",
            path = path,
            imageData = imageData
        )
    }

    suspend fun uploadObject(bucket: String, path: String, data: ByteArray): Boolean {
        return try {
            supabase.storage[bucket].upload(path, data) { upsert = true }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getPublicUrl(bucket: String, path: String): String {
        return supabase.storage[bucket].publicUrl(path)
    }

    suspend fun resolveDownloadUrl(
        bucket: String,
        pathOrUrl: String,
        expiresIn: Duration = 1.hours
    ): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl
        }

        return supabase.storage[bucket].createSignedUrl(pathOrUrl, expiresIn)
    }

    suspend fun uploadDeviceLog(path: String, data: ByteArray): Boolean {
        return try {
            supabase.storage["device-logs"].upload(path, data) {
                upsert = true
                contentType = ContentType.Text.Plain
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun signedDeviceLogUrl(path: String, expiresIn: Duration = 24.hours): String {
        return supabase.storage["device-logs"].createSignedUrl(path, expiresIn)
    }
}
