package com.trackspeed.android.sync

/**
 * Length-prefixed framing shared with iOS `BLEMessageFramer`.
 *
 * Small payloads are deliberately sent as raw JSON for compatibility with
 * older app versions. Payloads larger than the negotiated ATT value size are
 * prefixed with `STB1` and a big-endian UInt32 payload length, then split into
 * packets no larger than [maximumPacketBytes]. Android's GATT APIs reject
 * characteristic values above 512 bytes even when a 517-byte MTU provides a
 * nominal 514-byte ATT payload, so packet sizing is capped here as the final
 * safety boundary for both client writes and server notifications.
 */
internal class BleMessageFramer(private val nowNanos: () -> Long = System::nanoTime) {
    private var buffer = ByteArray(0)
    private var frameStartedAtNanos = 0L
    private var lastProgressAtNanos = 0L

    val hasBufferedFrame: Boolean
        get() = buffer.isNotEmpty()

    @Synchronized
    fun reset() {
        buffer = ByteArray(0)
        frameStartedAtNanos = 0L
        lastProgressAtNanos = 0L
    }

    @Synchronized
    fun receive(packet: ByteArray): List<ByteArray> {
        val now = nowNanos()
        if (buffer.isNotEmpty() &&
            (isValidFrameHeader(packet) ||
                now < lastProgressAtNanos ||
                now - lastProgressAtNanos >= INACTIVITY_TIMEOUT_NANOS ||
                now - frameStartedAtNanos >= MAX_FRAME_LIFETIME_NANOS)
        ) {
            reset()
        }
        if (packet.isEmpty()) return emptyList()
        if (buffer.isEmpty() && !isFramedPacketStart(packet)) {
            require(packet.size <= MAXIMUM_PAYLOAD_BYTES) {
                "BLE payload too large: ${packet.size}"
            }
            return listOf(packet.copyOf())
        }

        // Bound allocation before concatenating untrusted characteristic data.
        if (packet.size > MAXIMUM_PAYLOAD_BYTES + HEADER_BYTE_COUNT - buffer.size) {
            reset()
            throw IllegalArgumentException("BLE frame buffer exceeded maximum payload size")
        }
        if (buffer.isEmpty()) frameStartedAtNanos = now
        lastProgressAtNanos = now
        buffer += packet
        val payloads = mutableListOf<ByteArray>()

        while (buffer.size >= HEADER_BYTE_COUNT) {
            if (!isFramedPacketStart(buffer)) {
                reset()
                throw IllegalArgumentException("Invalid BLE frame header")
            }

            val length =
                ((buffer[4].toLong() and 0xffL) shl 24) or
                    ((buffer[5].toLong() and 0xffL) shl 16) or
                    ((buffer[6].toLong() and 0xffL) shl 8) or
                    (buffer[7].toLong() and 0xffL)
            if (length > MAXIMUM_PAYLOAD_BYTES.toLong()) {
                reset()
                throw IllegalArgumentException("BLE payload too large: $length")
            }

            val frameLength = HEADER_BYTE_COUNT + length.toInt()
            if (buffer.size < frameLength) break

            // Current iPhone peers send a zero-length STB1 frame to abort a
            // partial message. Consume it without delivering empty JSON.
            if (length > 0) {
                payloads += buffer.copyOfRange(HEADER_BYTE_COUNT, frameLength)
            }
            buffer = buffer.copyOfRange(frameLength, buffer.size)
            frameStartedAtNanos = now
        }

        if (buffer.isEmpty()) reset()

        return payloads
    }

    companion object {
        const val MAXIMUM_PAYLOAD_BYTES = 1_000_000
        const val MAXIMUM_GATT_ATTRIBUTE_VALUE_BYTES = 512
        private const val DEFAULT_ATT_PAYLOAD_BYTES = 20
        private const val ATT_PROTOCOL_OVERHEAD_BYTES = 3
        private const val HEADER_BYTE_COUNT = 8
        private const val INACTIVITY_TIMEOUT_NANOS = 1_500_000_000L
        private const val MAX_FRAME_LIFETIME_NANOS = 60_000_000_000L
        private val MAGIC = byteArrayOf(0x53, 0x54, 0x42, 0x31) // "STB1"

        fun maximumPacketBytesForMtu(mtu: Int): Int =
            (mtu - ATT_PROTOCOL_OVERHEAD_BYTES).coerceIn(
                DEFAULT_ATT_PAYLOAD_BYTES,
                MAXIMUM_GATT_ATTRIBUTE_VALUE_BYTES
            )

        fun isFramedPacketStart(data: ByteArray): Boolean {
            if (data.size < MAGIC.size) return false
            return MAGIC.indices.all { data[it] == MAGIC[it] }
        }

        /**
         * Both apps send a complete 8-byte header in the first ATT packet.
         * Check its length too: a base64/JSON continuation can begin with the
         * four printable magic bytes without actually being a fresh frame.
         */
        private fun isValidFrameHeader(data: ByteArray): Boolean {
            if (data.size < HEADER_BYTE_COUNT || !isFramedPacketStart(data)) return false
            val length =
                ((data[4].toLong() and 0xffL) shl 24) or
                    ((data[5].toLong() and 0xffL) shl 16) or
                    ((data[6].toLong() and 0xffL) shl 8) or
                    (data[7].toLong() and 0xffL)
            return length <= MAXIMUM_PAYLOAD_BYTES.toLong()
        }

        fun packets(payload: ByteArray, maximumPacketBytes: Int): List<ByteArray> {
            require(maximumPacketBytes > 0) {
                "Invalid BLE packet size: $maximumPacketBytes"
            }
            require(payload.size <= MAXIMUM_PAYLOAD_BYTES) {
                "BLE payload too large: ${payload.size}"
            }

            val safeMaximumPacketBytes = maximumPacketBytes.coerceAtMost(
                MAXIMUM_GATT_ATTRIBUTE_VALUE_BYTES
            )

            if (payload.size <= safeMaximumPacketBytes) return listOf(payload.copyOf())
            require(safeMaximumPacketBytes >= HEADER_BYTE_COUNT) {
                "BLE packet size is smaller than the framing header: $safeMaximumPacketBytes"
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
                .chunked(safeMaximumPacketBytes)
                .map { chunk -> chunk.toByteArray() }
        }
    }
}
