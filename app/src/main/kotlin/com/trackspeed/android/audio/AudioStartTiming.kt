package com.trackspeed.android.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Timing helpers for audible start cues.
 *
 * iOS records countdown/voice starts as the moment the athlete hears the cue,
 * not the moment the app asks the audio stack to play. Android does not expose
 * the same AVAudioSession latency values, so this uses the platform output
 * buffer properties plus a conservative fixed pipeline estimate.
 */
object AudioStartTiming {
    private const val TAG = "AudioStartTiming"
    private const val DEFAULT_FRAMES_PER_BUFFER = 256
    private const val DEFAULT_SAMPLE_RATE = 48_000
    private const val PIPELINE_OVERHEAD_NANOS = 15_000_000L

    fun monotonicNanosAudioCompensated(context: Context): Long {
        return SystemClock.elapsedRealtimeNanos() + estimatedOutputLatencyNanos(context)
    }

    fun estimatedOutputLatencyNanos(context: Context): Long {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return 0L

        val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?: DEFAULT_FRAMES_PER_BUFFER
        val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?: DEFAULT_SAMPLE_RATE

        val bufferDurationNanos = (framesPerBuffer.toLong() * 1_000_000_000L) / sampleRate
        val totalLatencyNanos = bufferDurationNanos * 2 + PIPELINE_OVERHEAD_NANOS

        Log.d(
            TAG,
            "Audio latency estimate: ${totalLatencyNanos / 1_000_000.0}ms " +
                "(bufferFrames=$framesPerBuffer, sampleRate=$sampleRate, " +
                "bufferDuration=${bufferDurationNanos / 1_000_000.0}ms)"
        )

        return totalLatencyNanos
    }

    fun hasBluetoothAudioOutput(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false

        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.isSink && isBluetoothOutputType(device.type)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to inspect audio output route", e)
            false
        }
    }

    private fun isBluetoothOutputType(type: Int): Boolean {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
            AudioDeviceInfo.TYPE_HEARING_AID -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            else -> {
                val isBleUnicast = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                    )
                val isBleBroadcast = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                isBleUnicast || isBleBroadcast
            }
        }
    }
}
