package com.trackspeed.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.ui.screens.help.cleanPrivateFeedbackImage
import com.trackspeed.android.ui.screens.help.PrivateFeedbackException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrivateFeedbackImageTest {
    @Test fun pickedImageIsOrientedResizedAndDoesNotRetainSourceMetadata() {
        val file = File.createTempFile("synthetic-feedback-", ".jpg", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(2_400, 1_200, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_MAKE, "SYNTHETIC_PRIVATE_METADATA")
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "2/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                saveAttributes()
            }
            val clean = Base64.decode(cleanPrivateFeedbackImage(file.readBytes()).base64, Base64.DEFAULT)
            assertTrue(clean.size <= 650_000)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(clean, 0, clean.size, bounds)
            assertEquals(800, bounds.outWidth); assertEquals(1_600, bounds.outHeight)
            val exif = ExifInterface(ByteArrayInputStream(clean))
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
            assertFalse(exif.getLatLong(FloatArray(2)))
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        } finally { file.delete() }
    }
    @Test fun unsupportedPickedBytesAreRejected() {
        try { cleanPrivateFeedbackImage("not an image".toByteArray()); fail("Accepted invalid image") }
        catch (_: PrivateFeedbackException) { }
    }
}
