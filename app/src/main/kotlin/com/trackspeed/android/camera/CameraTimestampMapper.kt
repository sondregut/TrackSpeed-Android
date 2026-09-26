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
    init {
        require(calibrationFrameCount > 0)
    }

    private var framesObserved = 0
    private var minimumObservedOffsetNanos: Long? = null
    private var lastSensorTimestampNanos = 0L
    private var lastMappedTimestampNanos = 0L

    @Synchronized
    fun toElapsedRealtimeNanos(
        sensorTimestampNanos: Long,
        callbackElapsedRealtimeNanos: Long
    ): Long? {
        // A missing sensor timestamp is missing evidence. Using callback time
        // here would turn a stale/invalid image into a fresh timing anchor.
        if (sensorTimestampNanos <= lastSensorTimestampNanos ||
            callbackElapsedRealtimeNanos <= 0L
        ) return null
        lastSensorTimestampNanos = sensorTimestampNanos

        if (!sourceIsRealtime && framesObserved < calibrationFrameCount) {
            val observedOffset = callbackElapsedRealtimeNanos - sensorTimestampNanos
            minimumObservedOffsetNanos = minimumObservedOffsetNanos
                ?.let { minOf(it, observedOffset) }
                ?: observedOffset
            framesObserved++
            if (framesObserved < calibrationFrameCount) return null
        }

        val mapped = if (sourceIsRealtime) {
            sensorTimestampNanos
        } else {
            try {
                Math.addExact(sensorTimestampNanos, minimumObservedOffsetNanos ?: return null)
            } catch (_: ArithmeticException) {
                return null
            }
        }
        if (mapped <= lastMappedTimestampNanos) return null
        val age = callbackElapsedRealtimeNanos - mapped
        // UNKNOWN's frozen callback bridge can have a small positive bias.
        // This tolerance does not claim that its clock has millisecond accuracy.
        val futureTolerance = if (sourceIsRealtime) 0L else UNKNOWN_FUTURE_TOLERANCE_NANOS
        if (age < -futureTolerance || age > MAX_FRAME_AGE_NANOS) return null
        lastMappedTimestampNanos = mapped
        return mapped
    }

    @Synchronized
    fun reset() {
        framesObserved = 0
        minimumObservedOffsetNanos = null
        lastSensorTimestampNanos = 0L
        lastMappedTimestampNanos = 0L
    }

    private companion object {
        const val DEFAULT_CALIBRATION_FRAMES = 10
        const val MAX_FRAME_AGE_NANOS = 2_000_000_000L
        const val UNKNOWN_FUTURE_TOLERANCE_NANOS = 5_000_000L
    }
}
