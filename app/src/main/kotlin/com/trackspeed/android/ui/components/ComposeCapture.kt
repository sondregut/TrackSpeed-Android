package com.trackspeed.android.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Utility for capturing a Compose view hierarchy as a Bitmap.
 * Uses a detached ComposeView rendered to a Canvas.
 */
object ComposeCapture {

    /**
     * Renders a composable into a Bitmap at the specified pixel dimensions.
     * Must be called from the main thread.
     *
     * @param context Application or Activity context
     * @param widthPx Target width in pixels
     * @param heightPx Target height in pixels
     * @param content The composable content to render
     * @return The rendered Bitmap
     */
    fun captureToBitmap(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        content: @Composable () -> Unit
    ): Bitmap {
        val composeView = ComposeView(context).apply {
            setContent { content() }
        }

        // Measure and layout the view at the desired pixel dimensions
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        composeView.layout(0, 0, widthPx, heightPx)

        // Force a draw pass -- ComposeView needs to be attached for a proper draw,
        // but we can still capture the hardware layer via the fallback software draw.
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        composeView.draw(canvas)

        return bitmap
    }

    /**
     * Saves a bitmap to the app-internal cache directory for sharing via FileProvider.
     *
     * @return URI pointing to the saved file (content:// scheme via FileProvider)
     */
    fun saveToCacheForSharing(
        context: Context,
        bitmap: Bitmap,
        fileName: String = "trackspeed_result.png"
    ): Uri {
        val exportsDir = File(context.cacheDir, "exports")
        exportsDir.mkdirs()
        val file = File(exportsDir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Saves a bitmap to the device gallery (MediaStore).
     *
     * @return true if save was successful
     */
    fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        displayName: String = "TrackSpeed_${System.currentTimeMillis()}"
    ): Boolean {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TrackSpeed")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return false

            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates a share intent for an image URI.
     */
    fun createShareIntent(imageUri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
