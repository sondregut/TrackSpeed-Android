package com.trackspeed.android.detection

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionEngineIntegrationTest {
    private val width = DetectionEngine.PROCESS_WIDTH
    private val height = DetectionEngine.PROCESS_HEIGHT

    @Test
    fun `body crossing center gate produces monotonic subframe result`() {
        val engine = DetectionEngine().apply {
            setCooldown(0.0)
            start(timestampNanos = 0L)
        }
        var timestamp = 0L
        repeat(11) {
            assertNull(engine.processFrame(blankFrame(), width, height, width, timestamp))
            timestamp += FRAME_NANOS
        }

        assertNull(
            engine.processFrame(
                bodyFrame(left = 40, right = 80),
                width,
                height,
                width,
                timestamp
            )
        )
        timestamp += FRAME_NANOS
        val result = engine.processFrame(
            bodyFrame(left = 70, right = 110),
            width,
            height,
            width,
            timestamp
        )

        assertNotNull(result)
        result!!
        assertTrue(result.crossingTimestampNanos in (timestamp - FRAME_NANOS)..timestamp)
        assertTrue(result.interpolationFraction in 0.0..1.0)
        assertTrue(result.componentBoundsNorm.height >= DetectionEngine.HEIGHT_FRACTION)
    }

    @Test
    fun `full frame exposure jump is rejected as scene flash`() {
        val engine = DetectionEngine().apply { start(timestampNanos = 0L) }
        var timestamp = 0L
        repeat(11) {
            engine.processFrame(blankFrame(), width, height, width, timestamp)
            timestamp += FRAME_NANOS
        }

        val flash = ByteArray(width * height) { 220.toByte() }
        assertNull(engine.processFrame(flash, width, height, width, timestamp))
    }

    @Test
    fun `remote legacy profile uses local support instead of leading edge floor`() {
        val leadingEngine = DetectionEngine(
            ReplicaDetectionConfiguration.bundled.copy(
                parameters = ReplicaDetectionConfiguration.Parameters(useLeadingEdgeTrigger = true)
            )
        ).apply { start(timestampNanos = 0L) }
        val legacyEngine = DetectionEngine(
            ReplicaDetectionConfiguration.bundled.copy(
                parameters = ReplicaDetectionConfiguration.Parameters(useLeadingEdgeTrigger = false)
            )
        ).apply { start(timestampNanos = 0L) }
        var timestamp = 0L
        repeat(11) {
            val frame = blankFrame()
            leadingEngine.processFrame(frame, width, height, width, timestamp)
            legacyEngine.processFrame(frame, width, height, width, timestamp)
            timestamp += FRAME_NANOS
        }

        val frame = legacySupportOnlyFrame()
        assertNull(leadingEngine.processFrame(frame, width, height, width, timestamp))
        assertNotNull(legacyEngine.processFrame(frame, width, height, width, timestamp))
    }

    private fun blankFrame(): ByteArray = ByteArray(width * height) { BACKGROUND.toByte() }

    private fun bodyFrame(left: Int, right: Int): ByteArray {
        return blankFrame().also { frame ->
            for (y in 45..275) {
                for (x in left..right) {
                    frame[y * width + x] = BODY.toByte()
                }
            }
        }
    }

    /** Tall body connected to only a 26px gate slice: iOS's legacy 8%
     * local-support gate accepts it, while the shipped leading-edge 30px
     * torso floor intentionally does not. */
    private fun legacySupportOnlyFrame(): ByteArray {
        return blankFrame().also { frame ->
            for (y in 45..275) {
                for (x in 40..85) frame[y * width + x] = BODY.toByte()
            }
            for (y in 90..115) {
                for (x in 86..94) frame[y * width + x] = BODY.toByte()
            }
        }
    }

    private companion object {
        const val BACKGROUND = 60
        const val BODY = 180
        const val FRAME_NANOS = 33_333_333L
    }
}
