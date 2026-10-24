package com.trackspeed.android.ui.share

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class ImageOutputWriteTest {
    @Test fun missingStreamCannotBeReportedSaved() {
        var encodes = 0
        assertThrows(IOException::class.java) { writeEncodedImage({ null }) { encodes++; true } }
        assertEquals(0, encodes)
    }
    @Test fun rejectedEncodingClosesStreamAndFails() {
        var closed = false
        val stream = object : ByteArrayOutputStream() { override fun close() { closed = true } }
        assertThrows(IOException::class.java) { writeEncodedImage({ stream }) { false } }
        assertTrue(closed)
    }
    @Test fun writeOrCloseFailuresAreNotSwallowed() {
        assertThrows(IOException::class.java) {
            writeEncodedImage({ ByteArrayOutputStream() }) { throw IOException("synthetic write failure") }
        }
        val stream = object : ByteArrayOutputStream() { override fun close() { throw IOException("synthetic close failure") } }
        assertThrows(IOException::class.java) { writeEncodedImage({ stream }) { true } }
    }
    @Test fun successfulOutputContainsEncodedBytesAndCloses() {
        var flushed = false; var closed = false
        val stream = object : ByteArrayOutputStream() {
            override fun flush() { flushed = true }
            override fun close() { closed = true }
        }
        writeEncodedImage({ stream }) { it.write(byteArrayOf(1, 2, 3)); true }
        assertArrayEquals(byteArrayOf(1, 2, 3), stream.toByteArray())
        assertTrue(flushed); assertTrue(closed)
    }
}
