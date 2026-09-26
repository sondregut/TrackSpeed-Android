package com.trackspeed.android.util

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLConnection
import java.util.Locale

/**
 * Defense-in-depth validation for remote image downloads.
 *
 * Mirrors the iOS ImageDownloadValidator: require HTTP 200, an image content
 * type prefix, and a bounded body size before handing bytes to image decoders.
 */
object ImageDownloadValidator {
    const val MAX_BYTES: Int = 10 * 1024 * 1024

    fun isValidImageData(data: ByteArray): Boolean {
        return data.size <= MAX_BYTES
    }

    fun isValidResponse(connection: URLConnection): Boolean {
        val http = connection as? HttpURLConnection ?: return false
        if (http.responseCode != HttpURLConnection.HTTP_OK) return false
        val contentType = http.contentType
            ?.lowercase(Locale.US)
            .orEmpty()
        return contentType.startsWith("image/")
    }

    fun readValidatedImageBytes(
        connection: URLConnection,
        maxBytes: Int = MAX_BYTES
    ): ByteArray? {
        if (!isValidResponse(connection)) return null

        val contentLength = connection.contentLengthLong
        if (contentLength > maxBytes) return null

        connection.getInputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
