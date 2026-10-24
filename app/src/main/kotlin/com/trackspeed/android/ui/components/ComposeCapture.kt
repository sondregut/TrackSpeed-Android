package com.trackspeed.android.ui.components

import android.content.ContentValues
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import kotlin.coroutines.resume
import com.trackspeed.android.ui.share.writeEncodedImage

/**
 * Utility for capturing a Compose view hierarchy as a Bitmap.
 * Uses a temporary noninteractive render host owned by the visible Activity lifecycle.
 */
object ComposeCapture {

    internal const val CAPTURE_HOST_TAG = "trackspeed-image-capture-host"

    /** Full-sized in-memory export; the temporary host never requests focus or consumes touch. */
    suspend fun captureToBitmap(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        parentCompositionContext: CompositionContext? = null,
        content: @Composable () -> Unit
    ): Bitmap = withContext(Dispatchers.Main.immediate) {
        require(widthPx > 0 && heightPx > 0) { "Image dimensions must be positive" }
        val activity = context.captureActivity() ?: throw IOException("Image capture requires a visible Activity")
        if (activity.isFinishing || activity.isDestroyed) throw IOException("Image capture Activity unavailable")
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: throw IOException("Image capture root unavailable")
        val lifecycleOwner = root.findViewTreeLifecycleOwner()
            ?: throw IOException("Image capture lifecycle unavailable")
        if (!root.isAttachedToWindow || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            throw IOException("Image capture Activity is not visible")
        }
        if (root.findViewTreeSavedStateRegistryOwner() == null) throw IOException("Image capture state owner unavailable")
        currentCoroutineContext().ensureActive()
        val host = object : FrameLayout(activity) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
        }.apply {
            tag = CAPTURE_HOST_TAG
            alpha = 0f // Only this invisible parent; child.draw(Canvas) renders at full opacity.
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val composeView = ComposeView(activity).apply {
            isFocusable = false
            parentCompositionContext?.let(::setParentCompositionContext)
        }
        var bitmap: Bitmap? = null
        try {
            host.addView(composeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(host, ViewGroup.LayoutParams(widthPx, heightPx))
            composeView.setContent(content)
            withTimeout(5_000) { composeView.awaitCaptureLayout(lifecycleOwner) }
            currentCoroutineContext().ensureActive()
            // Exact requested pixel dimensions, independent of the visible phone screen bounds.
            composeView.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
            composeView.layout(0, 0, widthPx, heightPx)
            val rendered = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bitmap = rendered
            composeView.draw(Canvas(rendered))
            rendered
        } catch (timeout: TimeoutCancellationException) {
            bitmap?.recycle()
            throw IOException("Image capture did not become ready", timeout)
        } catch (failure: Exception) {
            bitmap?.recycle()
            throw failure
        } finally {
            try {
                composeView.disposeComposition()
            } finally {
                root.removeView(host)
                host.removeAllViews()
            }
        }
    }

    private suspend fun View.awaitCaptureLayout(owner: LifecycleOwner) = suspendCancellableCoroutine<Unit> { continuation ->
        lateinit var beforeDraw: ViewTreeObserver.OnPreDrawListener
        lateinit var lifecycle: LifecycleEventObserver
        lateinit var attachment: View.OnAttachStateChangeListener
        fun removeListeners() {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(beforeDraw)
            owner.lifecycle.removeObserver(lifecycle)
            removeOnAttachStateChangeListener(attachment)
        }
        beforeDraw = ViewTreeObserver.OnPreDrawListener {
            if (continuation.isActive) {
                removeListeners()
                if (isAttachedToWindow && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) continuation.resume(Unit)
                else continuation.cancel(CancellationException("Image capture stopped"))
            }
            true
        }
        lifecycle = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                continuation.cancel(CancellationException("Image capture stopped"))
            }
        }
        attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { continuation.cancel(CancellationException("Image capture detached")) }
        }
        viewTreeObserver.addOnPreDrawListener(beforeDraw)
        owner.lifecycle.addObserver(lifecycle)
        addOnAttachStateChangeListener(attachment)
        continuation.invokeOnCancellation {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) removeListeners()
            else android.os.Handler(android.os.Looper.getMainLooper()).post { removeListeners() }
        }
        requestLayout()
        invalidate()
    }

    private fun Context.captureActivity(): Activity? {
        var current: Context = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
        return current as? Activity
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
        if (!exportsDir.isDirectory && !exportsDir.mkdirs()) throw IOException("Image cache unavailable")
        val file = File(exportsDir, fileName)
        try {
            writeEncodedImage({ FileOutputStream(file) }) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (failure: Exception) {
            file.delete() // Only this newly-created export; never source photos or unrelated cache files.
            throw failure
        }
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
        var createdUri: Uri? = null
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
            createdUri = uri
            writeEncodedImage({ resolver.openOutputStream(uri) }) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                if (resolver.update(uri, contentValues, null, null) <= 0) throw IOException("Image publish not confirmed")
            }

            true
        } catch (cancelled: CancellationException) {
            createdUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            throw cancelled
        } catch (_: Exception) {
            // Remove only the item this call just inserted; a failed write is not a saved image.
            createdUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
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
