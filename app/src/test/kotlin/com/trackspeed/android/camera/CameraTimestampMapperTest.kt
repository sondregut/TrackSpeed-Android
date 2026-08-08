package com.trackspeed.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTimestampMapperTest {
    @Test
    fun `realtime sensor timestamp is already in elapsed realtime domain`() {
        val mapper = CameraTimestampMapper(sourceIsRealtime = true)

        assertEquals(
            1_000_000_000L,
            mapper.toElapsedRealtimeNanos(
                sensorTimestampNanos = 1_000_000_000L,
                callbackElapsedRealtimeNanos = 1_020_000_000L
            )
        )
    }

    @Test
    fun `unknown source uses minimum callback delta and freezes after calibration`() {
        val mapper = CameraTimestampMapper(sourceIsRealtime = false, calibrationFrameCount = 3)

        assertEquals(null, mapper.toElapsedRealtimeNanos(1_000L, 1_010L))
        assertEquals(null, mapper.toElapsedRealtimeNanos(1_100L, 1_130L))
        assertEquals(1_205L, mapper.toElapsedRealtimeNanos(1_200L, 1_205L))

        // A lower delta after the three calibration samples must not move the
        // established timebase used for cross-device race timestamps.
        assertEquals(1_305L, mapper.toElapsedRealtimeNanos(1_300L, 1_301L))
    }

    @Test
    fun `unknown-source frames are withheld throughout calibration`() {
        val mapper = CameraTimestampMapper(sourceIsRealtime = false, calibrationFrameCount = 2)

        assertEquals(null, mapper.toElapsedRealtimeNanos(1_000L, 2_000L))
        assertTrue(mapper.toElapsedRealtimeNanos(1_100L, 1_150L)!! > 1_100L)
    }

    @Test
    fun `invalid sensor timestamp falls back to callback clock`() {
        val mapper = CameraTimestampMapper(sourceIsRealtime = false)

        assertEquals(42L, mapper.toElapsedRealtimeNanos(0L, 42L))
    }
}
