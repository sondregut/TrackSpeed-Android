package com.trackspeed.android.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.cloud.safeCloudErrorCode
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Available ElevenLabs voice IDs.
 */
enum class ElevenLabsVoiceId(val id: String, val displayName: String) {
    ADAM("pNInz6obpgDQGcFmaJgB", "Adam"),
    JOSH("TxGEqnHWrfWFTfGW9XjX", "Josh"),
    ARNOLD("VR6AewLTigWG4xSOukaG", "Arnold"),
    RACHEL("21m00Tcm4TlvDq8ikWAM", "Rachel"),
    BELLA("EXAVITQu4vr4xnSDxMaL", "Bella"),
    ELLI("MF3mGyEYCl7XYWbV9V6O", "Elli");

    companion object {
        fun fromString(value: String): ElevenLabsVoiceId =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ARNOLD
    }
}

/**
 * Voice provider selection.
 */
enum class VoiceProvider {
    ELEVEN_LABS,
    SYSTEM;

    companion object {
        fun fromString(value: String): VoiceProvider =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ELEVEN_LABS
    }
}

/**
 * ElevenLabs TTS service that calls a Supabase Edge Function proxy.
 *
 * - Uses two models: `eleven_multilingual_v2` for commands, `eleven_flash_v2_5` for time announcements
 * - Two-tier cache: in-memory ConcurrentHashMap + disk cache in cacheDir/ElevenLabsAudio/
 * - Falls back gracefully on network errors (caller should use system TTS)
 */
@Singleton
class ElevenLabsService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ElevenLabsService"
        const val MODEL_MULTILINGUAL = "eleven_multilingual_v2"
        const val MODEL_FLASH = "eleven_flash_v2_5"
        private const val DEFAULT_STABILITY = 0.75
        private const val DEFAULT_SIMILARITY_BOOST = 0.75
    }

    // In-memory cache: cacheKey -> audio bytes
    private val memoryCache = ConcurrentHashMap<String, ByteArray>()

    // Disk cache directory
    private val diskCacheDir: File by lazy {
        File(context.cacheDir, "ElevenLabsAudio").also { it.mkdirs() }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(Android)

    @Serializable
    private data class TtsRequest(
        val text: String,
        val voice_id: String,
        val model_id: String,
        val voice_settings: VoiceSettings
    )

    @Serializable
    private data class VoiceSettings(
        val stability: Double,
        val similarity_boost: Double
    )

    /**
     * Generate speech audio bytes for the given text.
     * Returns cached data if available, otherwise calls the Edge Function.
     * Returns null on failure.
     */
    suspend fun generateSpeech(
        text: String,
        voiceId: ElevenLabsVoiceId,
        modelId: String = MODEL_MULTILINGUAL,
        stability: Double = DEFAULT_STABILITY,
        similarityBoost: Double = DEFAULT_SIMILARITY_BOOST
    ): ByteArray? = withContext(Dispatchers.IO) {
        val cacheKey = buildCacheKey(text, voiceId.id, modelId)

        // 1. Check memory cache
        memoryCache[cacheKey]?.let { return@withContext it }

        // 2. Check disk cache
        val diskFile = File(diskCacheDir, cacheKey)
        if (diskFile.exists() && diskFile.length() > 0) {
            val bytes = diskFile.readBytes()
            memoryCache[cacheKey] = bytes
            return@withContext bytes
        }

        // 3. Call Edge Function
        try {
            val requestBody = json.encodeToString(
                TtsRequest.serializer(),
                TtsRequest(
                    text = text,
                    voice_id = voiceId.id,
                    model_id = modelId,
                    voice_settings = VoiceSettings(
                        stability = stability,
                        similarity_boost = similarityBoost
                    )
                )
            )

            val url = "${BuildConfig.SUPABASE_URL}/functions/v1/elevenlabs-tts"
            val key = BuildConfig.SUPABASE_ANON_KEY
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $key")
                header("apikey", key)
                setBody(requestBody)
            }

            val bytes = response.bodyAsBytes()
            if (bytes.isEmpty()) {
                Log.w(TAG, "Empty response from ElevenLabs Edge Function")
                return@withContext null
            }

            // Cache on disk and memory
            diskFile.writeBytes(bytes)
            memoryCache[cacheKey] = bytes

            Log.d(TAG, "Generated speech for '$text' (${bytes.size} bytes, voice=${voiceId.name})")
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate speech for '$text': ${e.safeCloudErrorCode()}")
            null
        }
    }

    /**
     * Speak a time value using the flash model (low latency, no caching for dynamic values).
     */
    suspend fun speakTime(seconds: Double, voiceId: ElevenLabsVoiceId, languageCode: String = "en") {
        val text = formatTimeForSpeech(seconds, languageCode)
        val audioData = generateSpeech(
            text = text,
            voiceId = voiceId,
            modelId = MODEL_FLASH
        )
        if (audioData != null) {
            playAudio(audioData)
        }
    }

    /**
     * Play audio bytes via MediaPlayer using a temp file.
     * Suspends until playback completes so callers (e.g. countdown sequence) can
     * wait for the phrase to finish before proceeding.
     */
    suspend fun playAudio(data: ByteArray) {
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("elevenlabs_", ".mp3", context.cacheDir).also {
                it.writeBytes(data)
            }
        }

        try {
            // MediaPlayer callbacks require a Looper thread; use Main dispatcher.
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val player = MediaPlayer()

                    player.setOnCompletionListener {
                        it.release()
                        tempFile.delete()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    player.setOnErrorListener { mp, _, _ ->
                        mp.release()
                        tempFile.delete()
                        if (continuation.isActive) continuation.resume(Unit)
                        true
                    }

                    try {
                        player.setDataSource(tempFile.absolutePath)
                        player.prepare()
                        player.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start MediaPlayer", e)
                        player.release()
                        tempFile.delete()
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    continuation.invokeOnCancellation {
                        player.release()
                        tempFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            tempFile.delete()
        }
    }

    /**
     * Pre-cache voice start command phrases for the given voice and language.
     */
    suspend fun preloadVoiceStartPhrases(
        voiceId: ElevenLabsVoiceId,
        languageCode: String = "en",
        includeReadyCommand: Boolean = false
    ) {
        val commands = VoiceCommandPhrases.forLanguage(context, languageCode)
        val phrases = buildList {
            add(commands.onYourMarks)
            if (includeReadyCommand) add(commands.ready)
            add(commands.set)
            add(commands.go)
        }
        for (phrase in phrases) {
            generateSpeech(text = phrase, voiceId = voiceId, modelId = MODEL_MULTILINGUAL)
        }
    }

    /**
     * Format a time in seconds for speech, using locale-specific decimal words.
     * e.g. 10.23 -> "10 point 23 seconds" (English)
     *      10.23 -> "10 Komma 23 Sekunden" (German)
     */
    fun formatTimeForSpeech(seconds: Double, languageCode: String = "en"): String {
        val commands = VoiceCommandPhrases.forLanguage(context, languageCode)
        val wholePart = seconds.toInt()
        val fractionalPart = ((seconds - wholePart) * 100).toInt()

        return if (fractionalPart > 0) {
            val fractionalStr = String.format(java.util.Locale.US, "%02d", fractionalPart)
            "$wholePart ${commands.decimalWord} $fractionalStr"
        } else {
            "$wholePart"
        }
    }

    /**
     * Clear both memory and disk cache.
     */
    fun clearCache() {
        memoryCache.clear()
        diskCacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun buildCacheKey(text: String, voiceId: String, modelId: String): String {
        val input = "$text|$voiceId|$modelId"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(java.util.Locale.US, it) }
    }
}
