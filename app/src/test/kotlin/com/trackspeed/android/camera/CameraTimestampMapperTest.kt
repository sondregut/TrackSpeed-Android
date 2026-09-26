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
    fun `invalid sensor timestamp cannot become a callback clock crossing`() {
        val mapper = CameraTimestampMapper(sourceIsRealtime = false)

        assertEquals(null, mapper.toElapsedRealtimeNanos(0L, 42L))
        assertEquals(null, mapper.toElapsedRealtimeNanos(-1L, 42L))
    }

    @Test
    fun `duplicate regressing stale and future realtime frames are withheld`() {
        val mapper = CameraTimestampMapper(sourceIsRealtime = true)
        assertEquals(100L, mapper.toElapsedRealtimeNanos(100L, 120L))
        assertEquals(null, mapper.toElapsedRealtimeNanos(100L, 150L))
        assertEquals(null, mapper.toElapsedRealtimeNanos(99L, 150L))
        assertEquals(null, mapper.toElapsedRealtimeNanos(200L, 2_000_000_201L))
        assertEquals(null, mapper.toElapsedRealtimeNanos(300L, 299L))
        assertEquals(400L, mapper.toElapsedRealtimeNanos(400L, 410L))
    }

    @Test
    fun `unknown source rejects delay spikes without rebasing its clock`() {
        val mapper = CameraTimestampMapper(false, 1)
        assertEquals(1_010L, mapper.toElapsedRealtimeNanos(1_000L, 1_010L))
        assertEquals(null, mapper.toElapsedRealtimeNanos(2_000L, 3_000_000_000L))
        assertEquals(3_010L, mapper.toElapsedRealtimeNanos(3_000L, 3_040L))
    }

    @Test
    fun `clock reset requires explicit camera generation reset`() {
        val mapper = CameraTimestampMapper(false, 1)
        assertEquals(1_010L, mapper.toElapsedRealtimeNanos(1_000L, 1_010L))
        assertEquals(null, mapper.toElapsedRealtimeNanos(100L, 110L))
        mapper.reset()
        assertEquals(110L, mapper.toElapsedRealtimeNanos(100L, 110L))
    }

    @Test
    fun `overflow cannot wrap an unknown-source timestamp`() {
        val mapper = CameraTimestampMapper(false, 1)
        assertEquals(110L, mapper.toElapsedRealtimeNanos(100L, 110L))
        assertEquals(null, mapper.toElapsedRealtimeNanos(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty calibration is rejected`() {
        CameraTimestampMapper(false, 0)
    }
}
