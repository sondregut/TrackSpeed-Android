package com.trackspeed.android.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.trackspeed.android.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to get Vibrator", e)
        null
    }

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
    }

    fun playCrossingBeep() {
        if (!cachedBeepEnabled) return

        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_DURATION_MS)

        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(VIBRATION_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(VIBRATION_DURATION_MS)
            }
        }
    }

    fun announceTime(seconds: Double) {
        if (!cachedAnnounceEnabled) return

        scope.launch {
            try {
                val voiceProvider = VoiceProvider.fromString(cachedVoiceProvider)
                if (voiceProvider == VoiceProvider.ELEVEN_LABS) {
                    val voiceId = ElevenLabsVoiceId.fromString(cachedElevenLabsVoice)
                    val languageCode = cachedAppLanguage.let {
                        if (it == "system") "en" else it
                    }
                    elevenLabsService.speakTime(seconds, voiceId, languageCode)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to announce time", e)
            }
        }
    }

    companion object {
        private const val TAG = "CrossingFeedback"
        private const val BEEP_DURATION_MS = 150
        private const val VIBRATION_DURATION_MS = 50L
    }
}
