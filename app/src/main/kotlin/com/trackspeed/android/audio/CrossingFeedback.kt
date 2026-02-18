package com.trackspeed.android.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrossingFeedback @Inject constructor(
    @ApplicationContext private val context: Context
) {
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

    fun playCrossingBeep() {
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

    companion object {
        private const val TAG = "CrossingFeedback"
        private const val BEEP_DURATION_MS = 150
        private const val VIBRATION_DURATION_MS = 50L
    }
}
