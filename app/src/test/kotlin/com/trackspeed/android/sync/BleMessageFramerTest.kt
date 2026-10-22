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
    fun mtu517CapsGattValuesAtAndroidAttributeLimitAndReassemblesSessionConfig() {
        val sessionConfigPayload = ByteArray(555) { index -> (index and 0xff).toByte() }

        val maximumPacketBytes = BleMessageFramer.maximumPacketBytesForMtu(mtu = 517)
        val packets = BleMessageFramer.packets(
            sessionConfigPayload,
            maximumPacketBytes = 514 // The nominal MTU minus ATT overhead.
        )

        assertEquals(512, maximumPacketBytes)
        assertEquals(listOf(512, 51), packets.map(ByteArray::size))
        assertTrue(
            packets.all { it.size <= BleMessageFramer.MAXIMUM_GATT_ATTRIBUTE_VALUE_BYTES }
        )

        val receiver = BleMessageFramer()
        val decoded = packets.flatMap(receiver::receive)
        assertArrayEquals(sessionConfigPayload, decoded.single())
        assertFalse(receiver.hasBufferedFrame)
    }

    @Test
    fun packetCapacityRespectsMtuBelowAndroidAttributeLimit() {
        assertEquals(20, BleMessageFramer.maximumPacketBytesForMtu(mtu = 23))
        assertEquals(509, BleMessageFramer.maximumPacketBytesForMtu(mtu = 512))
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

    @Test
    fun abandonedImageCannotConsumeNextControlMessage() {
        var now = 1L
        val receiver = BleMessageFramer { now }
        val firstPacket = BleMessageFramer.packets(ByteArray(100) { 7 }, 40).first()
        receiver.receive(firstPacket)
        now += 1_500_000_000L
        val control = "{\"ready\":true}".toByteArray()
        assertArrayEquals(control, receiver.receive(control).single())
        assertFalse(receiver.hasBufferedFrame)
    }

    @Test
    fun advancingLargeTransferCanExceedInactivityWindow() {
        var now = 1L
        val receiver = BleMessageFramer { now }
        val payload = ByteArray(1_000) { 9 }
        val results = BleMessageFramer.packets(payload, 40).flatMap { packet ->
            now += 1_000_000_000L
            receiver.receive(packet)
        }
        assertArrayEquals(payload, results.single())
    }

    @Test
    fun maximumFrameLifetimeStillBoundsSlowDrip() {
        var now = 1L
        val receiver = BleMessageFramer { now }
        val packets = BleMessageFramer.packets(ByteArray(5_000) { 9 }, 40)
        packets.take(60).forEach { packet ->
            receiver.receive(packet)
            now += 1_000_000_000L
        }
        val control = "{\"cancel\":true}".toByteArray()
        assertArrayEquals(control, receiver.receive(control).single())
    }

    @Test
    fun oversizedContinuationClearsBufferBeforeNextTransfer() {
        val receiver = BleMessageFramer()
        receiver.receive(BleMessageFramer.packets(ByteArray(100) { 7 }, 40).first())
        try {
            receiver.receive(ByteArray(BleMessageFramer.MAXIMUM_PAYLOAD_BYTES))
            throw AssertionError("Oversized continuation accepted")
        } catch (_: IllegalArgumentException) {
            assertFalse(receiver.hasBufferedFrame)
        }
        val control = "{}".toByteArray()
        assertArrayEquals(control, receiver.receive(control).single())
    }

    @Test
    fun iosAbortMarkerDiscardsPartialFrameBeforeRawControlArrives() {
        val receiver = BleMessageFramer { 1L }
        val firstPacket = BleMessageFramer.packets(ByteArray(300) { 7 }, 40).first()
        val abortMarker = byteArrayOf(0x53, 0x54, 0x42, 0x31, 0, 0, 0, 0)
        val control = "{\"ready\":true}".toByteArray()

        assertTrue(receiver.receive(firstPacket).isEmpty())
        assertTrue(receiver.receive(abortMarker).isEmpty())
        assertFalse(receiver.hasBufferedFrame)
        assertArrayEquals(control, receiver.receive(control).single())
    }

    @Test
    fun newCompleteFrameHeaderRecoversWithoutWaitingForInactivityTimeout() {
        val receiver = BleMessageFramer { 1L }
        receiver.receive(BleMessageFramer.packets(ByteArray(300) { 7 }, 40).first())
        val replacement = ByteArray(100) { 9 }

        val decoded = BleMessageFramer.packets(replacement, 40).flatMap(receiver::receive)

        assertArrayEquals(replacement, decoded.single())
        assertFalse(receiver.hasBufferedFrame)
    }

    @Test
    fun abortMarkerWhileIdleIsConsumedWithoutEmptyJsonDelivery() {
        val receiver = BleMessageFramer { 1L }
        val abortMarker = byteArrayOf(0x53, 0x54, 0x42, 0x31, 0, 0, 0, 0)

        assertTrue(receiver.receive(abortMarker).isEmpty())
        assertFalse(receiver.hasBufferedFrame)
    }

    @Test
    fun printableMagicInContinuationDoesNotDiscardAnIntactTransfer() {
        val receiver = BleMessageFramer { 1L }
        val payload = ByteArray(100) { 7 }
        // The first 40-byte ATT packet contains an 8-byte header and 32 body
        // bytes; this printable prefix starts the second ATT packet.
        "STB1QUJD".toByteArray().copyInto(payload, destinationOffset = 32)
        val packets = BleMessageFramer.packets(payload, 40)
        assertTrue(BleMessageFramer.isFramedPacketStart(packets[1]))

        val decoded = packets.flatMap(receiver::receive)

        assertArrayEquals(payload, decoded.single())
        assertFalse(receiver.hasBufferedFrame)
    }

    @Test
    fun repeatedFramedAndRawTransfersDoNotLeakState() {
        val receiver = BleMessageFramer()
        repeat(300) { index ->
            val payload = ByteArray(20 + index * 7) { index.toByte() }
            val decoded = BleMessageFramer.packets(payload, 128).flatMap(receiver::receive)
            assertArrayEquals(payload, decoded.single())
            assertFalse(receiver.hasBufferedFrame)
        }
    }
}
