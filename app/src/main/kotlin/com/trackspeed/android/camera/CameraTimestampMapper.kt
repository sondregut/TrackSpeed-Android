package com.trackspeed.android.camera

/**
 * Maps Camera2 sensor timestamps into the elapsed-realtime clock domain used by
 * BLE clock sync and race events.
 *
 * Cameras declaring SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME already use the
 * elapsed-realtime timebase. For UNKNOWN cameras, the only portable bridge is
 * the image callback time. The minimum observed callback delta is used so
 * transient queue/processing delay is not mistaken for a clock offset. Frames
 * are withheld until the estimate is frozen, so calibration cannot distort
 * detector velocity or produce a regressing race timestamp.
 */
internal class CameraTimestampMapper(
    private val sourceIsRealtime: Boolean,
    private val calibrationFrameCount: Int = DEFAULT_CALIBRATION_FRAMES
) {
    private var framesObserved = 0
    private var minimumObservedOffsetNanos: Long? = null

    @Synchronized
    fun toElapsedRealtimeNanos(
        sensorTimestampNanos: Long,
        callbackElapsedRealtimeNanos: Long
    ): Long? {
        if (sensorTimestampNanos <= 0L) {
            return callbackElapsedRealtimeNanos
        }

        if (sourceIsRealtime) {
            return sensorTimestampNanos
        }

        if (framesObserved < calibrationFrameCount) {
            val observedOffset = callbackElapsedRealtimeNanos - sensorTimestampNanos
            minimumObservedOffsetNanos = minimumObservedOffsetNanos
                ?.let { minOf(it, observedOffset) }
                ?: observedOffset
            framesObserved++
            if (framesObserved < calibrationFrameCount) return null
        }

        val offset = minimumObservedOffsetNanos
            ?: (callbackElapsedRealtimeNanos - sensorTimestampNanos)
        return sensorTimestampNanos + offset
    }

    @Synchronized
    fun reset() {
        framesObserved = 0
        minimumObservedOffsetNanos = null
    }

    private companion object {
        const val DEFAULT_CALIBRATION_FRAMES = 10
    }
}
