package com.trackspeed.android.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrossingFeedback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val elevenLabsService: ElevenLabsService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to create ToneGenerator", e)
        null
    }

    // System TTS for time announcements when System Voice is selected
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    // Cached settings — populated from DataStore collectors, read lock-free on hot path
    @Volatile private var cachedBeepEnabled: Boolean = SettingsRepository.Defaults.CROSSING_BEEP_ENABLED
    @Volatile private var cachedAnnounceEnabled: Boolean = SettingsRepository.Defaults.ANNOUNCE_TIMES_ENABLED
    @Volatile private var cachedVoiceProvider: String = SettingsRepository.Defaults.VOICE_PROVIDER
    @Volatile private var cachedElevenLabsVoice: String = SettingsRepository.Defaults.ELEVEN_LABS_VOICE
    @Volatile private var cachedAppLanguage: String = SettingsRepository.Defaults.APP_LANGUAGE

    init {
        scope.launch { settingsRepository.crossingBeepEnabled.collect { cachedBeepEnabled = it } }
        scope.launch { settingsRepository.announceTimesEnabled.collect { cachedAnnounceEnabled = it } }
        scope.launch { settingsRepository.voiceProvider.collect { cachedVoiceProvider = it } }
        scope.launch { settingsRepository.elevenLabsVoice.collect { cachedElevenLabsVoice = it } }
        scope.launch { settingsRepository.appLanguage.collect { cachedAppLanguage = it } }

        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    fun playCrossingBeep() {
        if (!cachedBeepEnabled) return

        // Crossing feedback must not vibrate: a haptic can shake a mounted
        // camera at the exact timing boundary and is distracting on every lap.
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_DURATION_MS)
    }

    fun announceTime(seconds: Double) {
        if (!cachedAnnounceEnabled) return

        scope.launch {
            try {
                val languageCode = cachedAppLanguage
                val voiceProvider = VoiceProvider.fromString(cachedVoiceProvider)
                if (voiceProvider == VoiceProvider.ELEVEN_LABS) {
                    val voiceId = ElevenLabsVoiceId.fromString(cachedElevenLabsVoice)
                    elevenLabsService.speakTime(seconds, voiceId, languageCode)
                } else {
                    announceTimeWithSystemTts(seconds, languageCode)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to announce time", e)
            }
        }
    }

    private fun announceTimeWithSystemTts(seconds: Double, languageCode: String) {
        val engine = tts ?: return
        if (!ttsReady) return

        val commands = VoiceCommandPhrases.forLanguage(context, languageCode)
        engine.language = commands.ttsLocale

        val text = elevenLabsService.formatTimeForSpeech(seconds, languageCode)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "announce_${System.currentTimeMillis()}")
    }

    companion object {
        private const val TAG = "CrossingFeedback"
        private const val BEEP_DURATION_MS = 150
    }
}
