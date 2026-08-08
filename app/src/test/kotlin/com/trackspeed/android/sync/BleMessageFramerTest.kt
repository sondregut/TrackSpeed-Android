package com.trackspeed.android.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleMessageFramerTest {

    @Test
    fun smallPayloadUsesLegacyRawPacket() {
        val payload = "{\"payload\":{}}".toByteArray()

        val packets = BleMessageFramer.packets(payload, maximumPacketBytes = 128)

        assertEquals(1, packets.size)
        assertArrayEquals(payload, packets.single())
        assertFalse(BleMessageFramer.isFramedPacketStart(packets.single()))
    }

    @Test
    fun largePayloadUsesIosCompatibleHeaderAndReassemblesAcrossPackets() {
        val payload = ByteArray(513) { index -> (index and 0xff).toByte() }
        val packets = BleMessageFramer.packets(payload, maximumPacketBytes = 64)

        assertTrue(packets.size > 1)
        assertArrayEquals(
            byteArrayOf(0x53, 0x54, 0x42, 0x31, 0x00, 0x00, 0x02, 0x01),
            packets.first().copyOfRange(0, 8)
        )

        val receiver = BleMessageFramer()
        val decoded = packets.flatMap(receiver::receive)

        assertEquals(1, decoded.size)
        assertArrayEquals(payload, decoded.single())
        assertFalse(receiver.hasBufferedFrame)
    }

    @Test
    fun receiverPreservesPartialFrameUntilRemainingPacketsArrive() {
        val payload = ByteArray(100) { 7 }
        val packets = BleMessageFramer.packets(payload, maximumPacketBytes = 40)
        val receiver = BleMessageFramer()

        assertTrue(receiver.receive(packets.first()).isEmpty())
        assertTrue(receiver.hasBufferedFrame)

        val decoded = packets.drop(1).flatMap(receiver::receive)
        assertArrayEquals(payload, decoded.single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun receiverRejectsInvalidContinuationHeader() {
        val receiver = BleMessageFramer()
        receiver.receive(
            byteArrayOf(
                0x53, 0x54, 0x42, 0x31,
                0x00, 0x00, 0x00, 0x00,
                0x01, 0x02, 0x03, 0x04
            )
        )
        receiver.receive(byteArrayOf(0x05, 0x06, 0x07, 0x08))
    }
}
