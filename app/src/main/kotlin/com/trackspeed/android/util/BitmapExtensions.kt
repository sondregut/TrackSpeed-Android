package com.trackspeed.android.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

fun Bitmap.toJpeg(quality: Int = 30): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return stream.toByteArray()
}
