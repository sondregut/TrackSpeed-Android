package com.trackspeed.android.ui.screens.help

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal suspend fun importPrivateFeedbackImage(resolver: ContentResolver, uri: Uri): PrivateFeedbackAttachment = withContext(Dispatchers.IO) {
    // Only the explicitly picked stream is read. Never retain its URI, filename, EXIF or library identifier.
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8_192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            if (out.size() + n > 25_000_000) throw PrivateFeedbackException(PrivateFeedbackFailure.IMAGE)
            out.write(buffer, 0, n)
        }; out.toByteArray()
    } ?: throw PrivateFeedbackException(PrivateFeedbackFailure.IMAGE)
    cleanPrivateFeedbackImage(bytes)
}

internal fun cleanPrivateFeedbackImage(bytes: ByteArray): PrivateFeedbackAttachment {
    if (bytes.size > 25_000_000) throw PrivateFeedbackException(PrivateFeedbackFailure.IMAGE)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PrivateFeedbackException(PrivateFeedbackFailure.IMAGE)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3_200) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: throw PrivateFeedbackException(PrivateFeedbackFailure.IMAGE)
    val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    val ratio = minOf(1f, 1_600f / maxOf(oriented.width, oriented.height))
    val clean = Bitmap.createBitmap((oriented.width * ratio).roundToInt().coerceAtLeast(1),
        (oriented.height * ratio).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    try {
        Canvas(clean).apply {
            drawColor(Color.WHITE)
            drawBitmap(oriented, null, android.graphics.Rect(0, 0, clean.width, clean.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        val output = ByteArrayOutputStream()
        if (!clean.compress(Bitmap.CompressFormat.JPEG, 65, output) || output.size() > 650_000)
            throw PrivateFeedbackException(PrivateFeedbackFailure.IMAGE)
        return PrivateFeedbackAttachment(base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
    } finally {
        clean.recycle(); if (oriented !== decoded) oriented.recycle(); decoded.recycle()
    }
}
