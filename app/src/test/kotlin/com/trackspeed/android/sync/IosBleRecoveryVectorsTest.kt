package com.trackspeed.android.sync

import org.junit.Assert.*
import org.junit.Test

/** Actual bytes produced by the unchanged iPhone framer, not Android-generated expectations. */
class IosBleRecoveryVectorsTest {
    private val vectors = checkNotNull(javaClass.getResourceAsStream("/ble/ios-recovery-vectors.txt"))
        .bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val (key, hex) = line.split("=", limit = 2)
                key to hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }.toList().groupBy({ it.first }, { it.second })
        }

    @Test fun abortedIosTransferAllowsImmediateControlWithoutTimeout() {
        val receiver = BleMessageFramer { 1L }
        val control = vectors.getValue("control").single()
        assertTrue(receiver.receive(vectors.getValue("originalPackets").first()).isEmpty())
        assertTrue(receiver.receive(vectors.getValue("abort").single()).isEmpty())
        assertFalse(receiver.hasBufferedFrame)
        assertArrayEquals(control, receiver.receive(control).single())
    }

    @Test fun replacementIosFrameRestoresMessageBoundary() {
        val receiver = BleMessageFramer { 1L }
        receiver.receive(vectors.getValue("originalPackets").first())
        val decoded = vectors.getValue("replacementPackets").flatMap(receiver::receive)
        assertArrayEquals(ByteArray(100) { 9 }, decoded.single())
        assertFalse(receiver.hasBufferedFrame)
        val complete = vectors.getValue("originalPackets").flatMap(receiver::receive)
        assertArrayEquals(ByteArray(300) { 7 }, complete.single())
        assertFalse(receiver.hasBufferedFrame)
    }
}
