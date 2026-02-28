package com.trackspeed.android.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Voice gender preference for TTS voice selection.
 */
enum class VoiceGender(val displayName: String) {
    MALE("Male"),
    FEMALE("Female");

    companion object {
        fun fromString(value: String): VoiceGender =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MALE
    }
}

/**
 * Current phase of the voice start sequence.
 * Mirrors the iOS VoiceStartPhase for UI parity.
 */
enum class VoiceStartPhase(val displayText: String) {
    IDLE("Ready to start"),
    PRELOADING("Preparing voice..."),
    PRE_START("GET READY..."),
    ON_YOUR_MARKS("ON YOUR MARKS"),
    WAITING_FOR_SET("ON YOUR MARKS"),
    SET("SET"),
    WAITING_FOR_GO("SET"),
    GO("GO!"),
    STARTED("GO!"),
    CANCELLED("Cancelled");

    val isWaiting: Boolean
        get() = this == PRELOADING || this == PRE_START || this == WAITING_FOR_SET || this == WAITING_FOR_GO
}

/**
 * Service that handles voice commands for sprint start using Android TextToSpeech API.
 *
 * Provides the "On your marks... Set... GO!" sequence with configurable timing
 * that matches the iOS VoiceStartService behavior. Uses system TTS voices (offline,
 * no external API needed).
 *
 * Injected as a @Singleton via Hilt.
 */
@Singleton
class VoiceStartService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val elevenLabsService: ElevenLabsService
) {

    companion object {
        private const val TAG = "VoiceStartService"

        // Default timing ranges (seconds) - matching iOS defaults
        private const val PRE_START_DELAY_MIN = 3.0
        private const val PRE_START_DELAY_MAX = 5.0
        private const val ON_YOUR_MARKS_DELAY_MIN = 5.0
        private const val ON_YOUR_MARKS_DELAY_MAX = 10.0
        private const val SET_HOLD_TIME_MIN = 1.5
        private const val SET_HOLD_TIME_MAX = 2.3

        // Utterance IDs
        private const val UTTERANCE_ON_YOUR_MARKS = "on_your_marks"
        private const val UTTERANCE_SET = "set"
        private const val UTTERANCE_GO = "go"
        private const val UTTERANCE_PREVIEW = "preview"

        private const val BEEP_DURATION_MS = 200
        private const val VIBRATION_DURATION_MS = 80L
    }

    // --- Observable state ---

    private val _phase = MutableStateFlow(VoiceStartPhase.IDLE)
    val phase: StateFlow<VoiceStartPhase> = _phase.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    // --- Callbacks ---

    /** Called when the "GO!" moment happens - this is the precise monotonic start timestamp (nanos). */
    var onStart: ((Long) -> Unit)? = null

    /** Called when the sequence is cancelled. */
    var onCancel: (() -> Unit)? = null

    /** Called for each phase change (for UI updates). */
    var onPhaseChange: ((VoiceStartPhase) -> Unit)? = null

    // --- Private state ---

    private var tts: TextToSpeech? = null
    private var speechContinuation: CancellableContinuation<Unit>? = null
    private var sequenceJob: kotlinx.coroutines.Job? = null

    private var voiceGender: VoiceGender = VoiceGender.MALE
    private var currentCommands: VoiceCommands = VoiceCommandPhrases.forLanguage("en")
    var voiceProvider: VoiceProvider = VoiceProvider.ELEVEN_LABS
    var elevenLabsVoiceId: ElevenLabsVoiceId = ElevenLabsVoiceId.ARNOLD

    // Tone generator for the GO beep
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to create ToneGenerator", e)
        null
    }

    // Vibrator for haptic feedback
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

    init {
        initializeTts()
    }

    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyLanguageAndVoice()
                _isTtsReady.value = true
                Log.i(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                _isTtsReady.value = false
            }
        }
    }

    /**
     * Apply current language and voice preferences to TTS engine.
     */
    private fun applyLanguageAndVoice() {
        val engine = tts ?: return
        val locale = currentCommands.ttsLocale
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "TTS language ${locale.toLanguageTag()} not supported, falling back to English")
            engine.setLanguage(Locale.US)
        }
        applyVoicePreference()
    }

    /**
     * Apply the selected voice gender preference by choosing from available TTS voices.
     */
    private fun applyVoicePreference() {
        val engine = tts ?: return
        try {
            val voices = engine.voices ?: return
            val targetLang = currentCommands.ttsLocale.language
            val langVoices = voices.filter {
                it.locale.language == targetLang && !it.isNetworkConnectionRequired
            }

            // Try to pick a voice matching the desired gender.
            // Android TTS voice names often contain gender hints (male/female).
            val preferred = langVoices.filter { voice ->
                val name = voice.name.lowercase()
                when (voiceGender) {
                    VoiceGender.MALE -> name.contains("male") || name.contains("man") ||
                            (!name.contains("female") && !name.contains("woman"))
                    VoiceGender.FEMALE -> name.contains("female") || name.contains("woman")
                }
            }

            // Prefer higher quality voices
            val sorted = (preferred.ifEmpty { langVoices }).sortedByDescending { it.quality }
            sorted.firstOrNull()?.let { voice ->
                engine.voice = voice
                Log.i(TAG, "Selected TTS voice: ${voice.name} (quality=${voice.quality}, lang=$targetLang)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to select voice preference", e)
        }
    }

    /**
     * Update the voice gender preference.
     */
    fun setVoiceGender(gender: VoiceGender) {
        voiceGender = gender
        applyVoicePreference()
    }

    /**
     * Update the language for voice commands and TTS locale.
     */
    fun setLanguage(languageTag: String) {
        currentCommands = VoiceCommandPhrases.forLanguage(languageTag)
        applyLanguageAndVoice()
        Log.i(TAG, "Voice language set to: $languageTag (${currentCommands.onYourMarks})")
    }

    /**
     * Get the current voice commands (for UI display).
     */
    fun getCommands(): VoiceCommands = currentCommands

    /**
     * Speak the countdown sequence: "On your marks..." -> pause -> "Set..." -> pause -> "GO!" (beep).
     *
     * @param onComplete Called when the full sequence completes with the precise start timestamp (nanos).
     */
    suspend fun speakCountdown(onComplete: (Long) -> Unit) {
        if (voiceProvider == VoiceProvider.SYSTEM && !_isTtsReady.value) {
            Log.w(TAG, "System TTS not ready, cannot start countdown")
            return
        }
        if (_isRunning.value) {
            Log.w(TAG, "Voice sequence already running")
            return
        }

        _isRunning.value = true
        _phase.value = VoiceStartPhase.IDLE

        try {
            // Preload ElevenLabs audio if selected
            if (voiceProvider == VoiceProvider.ELEVEN_LABS) {
                setPhase(VoiceStartPhase.PRELOADING)
                val langTag = currentCommands.ttsLocale.language
                elevenLabsService.preloadVoiceStartPhrases(elevenLabsVoiceId, langTag)
            }
            runSequence(onComplete)
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "Voice sequence was cancelled")
            setPhase(VoiceStartPhase.CANCELLED)
        } catch (e: Exception) {
            Log.e(TAG, "Voice sequence error", e)
            setPhase(VoiceStartPhase.CANCELLED)
        } finally {
            _isRunning.value = false
        }
    }

    private suspend fun runSequence(onComplete: (Long) -> Unit) {
        // Phase 0: Pre-start delay - user sets phone down
        setPhase(VoiceStartPhase.PRE_START)
        val preDelay = randomInRange(PRE_START_DELAY_MIN, PRE_START_DELAY_MAX)
        Log.i(TAG, "Pre-start delay: ${String.format("%.1f", preDelay)}s")
        delay((preDelay * 1000).toLong())

        // Phase 1: "On your marks"
        setPhase(VoiceStartPhase.ON_YOUR_MARKS)
        speak(currentCommands.onYourMarks)

        // Phase 2: Wait after "On your marks"
        setPhase(VoiceStartPhase.WAITING_FOR_SET)
        val marksDelay = randomInRange(ON_YOUR_MARKS_DELAY_MIN, ON_YOUR_MARKS_DELAY_MAX)
        Log.i(TAG, "Waiting ${String.format("%.1f", marksDelay)}s after '${currentCommands.onYourMarks}'")
        delay((marksDelay * 1000).toLong())

        // Phase 3: "Set"
        setPhase(VoiceStartPhase.SET)
        speak(currentCommands.set)

        // Phase 4: Wait after "Set" (tension builds - random delay)
        setPhase(VoiceStartPhase.WAITING_FOR_GO)
        val setDelay = randomInRange(SET_HOLD_TIME_MIN, SET_HOLD_TIME_MAX)
        Log.i(TAG, "Waiting ${String.format("%.1f", setDelay)}s after 'Set' (tension)")
        delay((setDelay * 1000).toLong())

        // Phase 5: GO! - Capture precise monotonic timestamp BEFORE playing sound
        setPhase(VoiceStartPhase.GO)
        val startTimestamp = SystemClock.elapsedRealtimeNanos()

        // Haptic feedback on GO!
        triggerHaptic(heavy = true)

        // Play the beep sound for GO
        playGoBeep()

        // Notify that timer has started
        Log.i(TAG, "GO! Timer started at timestamp: $startTimestamp")
        setPhase(VoiceStartPhase.STARTED)
        onComplete(startTimestamp)
        onStart?.invoke(startTimestamp)
    }

    /**
     * Speak a single command text and wait for completion.
     */
    suspend fun speakCommand(text: String) {
        if (voiceProvider == VoiceProvider.SYSTEM && !_isTtsReady.value) {
            Log.w(TAG, "System TTS not ready, cannot speak command")
            return
        }
        speak(text)
    }

    /**
     * Preview the current voice by speaking a short phrase.
     */
    fun previewVoice() {
        val engine = tts ?: return
        if (!_isTtsReady.value) return

        val preview = "${currentCommands.onYourMarks}. ${currentCommands.set}. ${currentCommands.go}!"
        engine.speak(
            preview,
            TextToSpeech.QUEUE_FLUSH,
            null,
            UTTERANCE_PREVIEW
        )
    }

    /**
     * Cancel any running voice sequence.
     */
    fun cancel() {
        Log.i(TAG, "Cancelling voice command sequence")
        sequenceJob?.cancel()
        sequenceJob = null
        tts?.stop()

        // Resume any waiting continuation
        speechContinuation?.let {
            if (it.isActive) it.resume(Unit)
            speechContinuation = null
        }

        _phase.value = VoiceStartPhase.CANCELLED
        _isRunning.value = false
        onCancel?.invoke()
    }

    /**
     * Reset to idle state.
     */
    fun reset() {
        cancel()
        _phase.value = VoiceStartPhase.IDLE
    }

    /**
     * Release TTS resources. Call when the service is no longer needed.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        toneGenerator?.release()
        _isTtsReady.value = false
    }

    // --- Private helpers ---

    private fun setPhase(newPhase: VoiceStartPhase) {
        _phase.value = newPhase
        onPhaseChange?.invoke(newPhase)
        Log.d(TAG, "Phase: ${newPhase.displayText}")
    }

    private suspend fun speak(text: String) {
        if (voiceProvider == VoiceProvider.ELEVEN_LABS) {
            try {
                val audioData = elevenLabsService.generateSpeech(
                    text = text,
                    voiceId = elevenLabsVoiceId
                )
                if (audioData != null) {
                    elevenLabsService.playAudio(audioData)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "ElevenLabs failed for '$text', falling back to system TTS", e)
            }
        }
        speakWithSystemTts(text)
    }

    private suspend fun speakWithSystemTts(text: String) {
        val engine = tts ?: return

        suspendCancellableCoroutine { continuation ->
            speechContinuation = continuation

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS speaking: '$text'")
                }

                override fun onDone(utteranceId: String?) {
                    val cont = speechContinuation
                    speechContinuation = null
                    if (cont?.isActive == true) {
                        cont.resume(Unit)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS error for: '$text'")
                    val cont = speechContinuation
                    speechContinuation = null
                    if (cont?.isActive == true) {
                        cont.resume(Unit)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS error code $errorCode for: '$text'")
                    val cont = speechContinuation
                    speechContinuation = null
                    if (cont?.isActive == true) {
                        cont.resume(Unit)
                    }
                }
            })

            val params = android.os.Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)

            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "voice_start_${text.hashCode()}")

            continuation.invokeOnCancellation {
                engine.stop()
                speechContinuation = null
            }
        }
    }

    private fun playGoBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_DURATION_MS)
    }

    private fun triggerHaptic(heavy: Boolean = false) {
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = if (heavy) VibrationEffect.DEFAULT_AMPLITUDE else 80
                it.vibrate(VibrationEffect.createOneShot(VIBRATION_DURATION_MS, amplitude))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(VIBRATION_DURATION_MS)
            }
        }
    }

    private fun randomInRange(min: Double, max: Double): Double {
        return min + Random.nextDouble() * (max - min)
    }
}
