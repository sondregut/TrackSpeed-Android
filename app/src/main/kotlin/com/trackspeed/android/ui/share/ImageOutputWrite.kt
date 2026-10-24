package com.trackspeed.android.ui.share

import java.io.IOException
import java.io.OutputStream

/** No successful image export without a stream, successful encoding and completed flush/close. */
internal fun writeEncodedImage(open: () -> OutputStream?, encode: (OutputStream) -> Boolean) {
    val stream = open() ?: throw IOException("Image output unavailable")
    stream.use {
        if (!encode(it)) throw IOException("Image encoding failed")
        it.flush()
    }
}
