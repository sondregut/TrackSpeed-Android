package com.trackspeed.android.sync

/**
 * Length-prefixed framing shared with iOS `BLEMessageFramer`.
 *
 * Small payloads are deliberately sent as raw JSON for compatibility with
 * older app versions. Payloads larger than the negotiated ATT value size are
 * prefixed with `STB1` and a big-endian UInt32 payload length, then split into
 * packets no larger than [maximumPacketBytes].
 */
internal class BleMessageFramer {
    private var buffer = ByteArray(0)

    val hasBufferedFrame: Boolean
        get() = buffer.isNotEmpty()

    @Synchronized
    fun reset() {
        buffer = ByteArray(0)
    }

    @Synchronized
    fun receive(packet: ByteArray): List<ByteArray> {
        if (buffer.isEmpty() && !isFramedPacketStart(packet)) {
            require(packet.size <= MAXIMUM_PAYLOAD_BYTES) {
                "BLE payload too large: ${packet.size}"
            }
            return listOf(packet.copyOf())
        }

        require(buffer.size <= MAXIMUM_PAYLOAD_BYTES + HEADER_BYTE_COUNT) {
            "BLE frame buffer exceeded maximum payload size"
        }
        buffer += packet
        val payloads = mutableListOf<ByteArray>()

        while (buffer.size >= HEADER_BYTE_COUNT) {
            if (!isFramedPacketStart(buffer)) {
                buffer = ByteArray(0)
                throw IllegalArgumentException("Invalid BLE frame header")
            }

            val length =
                ((buffer[4].toLong() and 0xffL) shl 24) or
                    ((buffer[5].toLong() and 0xffL) shl 16) or
                    ((buffer[6].toLong() and 0xffL) shl 8) or
                    (buffer[7].toLong() and 0xffL)
            if (length > MAXIMUM_PAYLOAD_BYTES.toLong()) {
                buffer = ByteArray(0)
                throw IllegalArgumentException("BLE payload too large: $length")
            }

            val frameLength = HEADER_BYTE_COUNT + length.toInt()
            if (buffer.size < frameLength) break

            payloads += buffer.copyOfRange(HEADER_BYTE_COUNT, frameLength)
            buffer = buffer.copyOfRange(frameLength, buffer.size)
        }

        return payloads
    }

    companion object {
        const val MAXIMUM_PAYLOAD_BYTES = 1_000_000
        private const val HEADER_BYTE_COUNT = 8
        private val MAGIC = byteArrayOf(0x53, 0x54, 0x42, 0x31) // "STB1"

        fun isFramedPacketStart(data: ByteArray): Boolean {
            if (data.size < MAGIC.size) return false
            return MAGIC.indices.all { data[it] == MAGIC[it] }
        }

        fun packets(payload: ByteArray, maximumPacketBytes: Int): List<ByteArray> {
            require(maximumPacketBytes > 0) {
                "Invalid BLE packet size: $maximumPacketBytes"
            }
            require(payload.size <= MAXIMUM_PAYLOAD_BYTES) {
                "BLE payload too large: ${payload.size}"
            }

            if (payload.size <= maximumPacketBytes) return listOf(payload.copyOf())
            require(maximumPacketBytes >= HEADER_BYTE_COUNT) {
                "BLE packet size is smaller than the framing header: $maximumPacketBytes"
            }

            val length = payload.size
            val framed = ByteArray(HEADER_BYTE_COUNT + length)
            MAGIC.copyInto(framed)
            framed[4] = ((length ushr 24) and 0xff).toByte()
            framed[5] = ((length ushr 16) and 0xff).toByte()
            framed[6] = ((length ushr 8) and 0xff).toByte()
            framed[7] = (length and 0xff).toByte()
            payload.copyInto(framed, destinationOffset = HEADER_BYTE_COUNT)

            return framed.asList()
                .chunked(maximumPacketBytes)
                .map { chunk -> chunk.toByteArray() }
        }
    }
}
