package com.trackspeed.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.util.Log
import androidx.annotation.RawRes
import com.trackspeed.android.R
import com.trackspeed.android.model.StartSoundType

/**
 * Low-latency player for countdown/voice start sounds.
 */
class StartSoundPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val loadedSoundIds = mutableSetOf<Int>()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val fallbackToneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to create fallback ToneGenerator", e)
        null
    }
    private val soundIds: Map<StartSoundType, Int>

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedSoundIds += sampleId
            } else {
                Log.w(TAG, "Failed to load start sound sampleId=$sampleId status=$status")
            }
        }
        soundIds = StartSoundType.selectable.mapNotNull { type ->
            rawResId(type)?.let { resId -> type to soundPool.load(appContext, resId, 1) }
        }.toMap()
    }

    fun play(soundType: StartSoundType) {
        val selectedType = soundType.takeIf { it.isAvailable } ?: StartSoundType.BEEP
        val soundId = soundIds[selectedType] ?: soundIds[StartSoundType.BEEP]
        if (soundId != null && soundId in loadedSoundIds) {
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        } else {
            fallbackToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, FALLBACK_BEEP_MS)
        }
    }

    fun release() {
        soundPool.release()
        fallbackToneGenerator?.release()
    }

    companion object {
        private const val TAG = "StartSoundPlayer"
        private const val FALLBACK_BEEP_MS = 200

        @RawRes
        fun rawResId(soundType: StartSoundType): Int? = when (soundType) {
            StartSoundType.BEEP -> R.raw.beep_401570
            StartSoundType.GUNSHOT -> R.raw.gunshot
            StartSoundType.WHISTLE -> null
        }
    }
}
