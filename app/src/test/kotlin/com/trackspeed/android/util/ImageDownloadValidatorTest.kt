package com.trackspeed.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ImageDownloadValidatorTest {

    @Test
    fun validHttpImageResponseIsAccepted() {
        val connection = FakeHttpURLConnection(
            responseCode = HttpURLConnection.HTTP_OK,
            contentType = "image/jpeg",
            body = byteArrayOf(1, 2, 3)
        )

        assertTrue(ImageDownloadValidator.isValidResponse(connection))
        assertTrue(
            ImageDownloadValidator.readValidatedImageBytes(connection)!!.contentEquals(byteArrayOf(1, 2, 3))
        )
    }

    @Test
    fun nonOkStatusIsRejected() {
        val connection = FakeHttpURLConnection(
            responseCode = HttpURLConnection.HTTP_NOT_FOUND,
            contentType = "image/jpeg",
            body = byteArrayOf(1, 2, 3)
        )

        assertFalse(ImageDownloadValidator.isValidResponse(connection))
        assertTrue(ImageDownloadValidator.readValidatedImageBytes(connection) == null)
    }

    @Test
    fun nonImageContentTypeIsRejected() {
        val connection = FakeHttpURLConnection(
            responseCode = HttpURLConnection.HTTP_OK,
            contentType = "text/html",
            body = byteArrayOf(1, 2, 3)
        )

        assertFalse(ImageDownloadValidator.isValidResponse(connection))
        assertTrue(ImageDownloadValidator.readValidatedImageBytes(connection) == null)
    }

    @Test
    fun oversizedImageDataIsRejected() {
        assertFalse(ImageDownloadValidator.isValidImageData(ByteArray(ImageDownloadValidator.MAX_BYTES + 1)))
        assertTrue(ImageDownloadValidator.isValidImageData(ByteArray(ImageDownloadValidator.MAX_BYTES)))
    }

    private class FakeHttpURLConnection(
        private val responseCode: Int,
        private val contentType: String?,
        private val body: ByteArray
    ) : HttpURLConnection(URL("https://example.com/image.jpg")) {
        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit

        override fun getResponseCode(): Int = responseCode

        override fun getContentType(): String? = contentType

        override fun getContentLengthLong(): Long = body.size.toLong()

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }
}
