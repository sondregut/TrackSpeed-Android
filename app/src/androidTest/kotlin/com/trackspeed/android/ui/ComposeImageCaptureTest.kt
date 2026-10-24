package com.trackspeed.android.ui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.ui.components.ComposeCapture
import com.trackspeed.android.ui.components.ShareCardData
import com.trackspeed.android.ui.components.ShareCardTheme
import com.trackspeed.android.ui.components.ShareableResultCard
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Actual attached production capture into memory. Never calls gallery/cache/chooser APIs. */
@RunWith(AndroidJUnit4::class)
class ComposeImageCaptureTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var activity: ComponentActivity
    private lateinit var parentComposition: CompositionContext
    private lateinit var scope: CoroutineScope

    private fun hostTestScreen() {
        compose.setContent {
            activity = checkNotNull(LocalActivity.current) as ComponentActivity
            parentComposition = rememberCompositionContext()
            scope = rememberCoroutineScope()
            Box {}
        }
    }
    private fun temporaryHost(): View? = activity.findViewById<ViewGroup>(android.R.id.content)
        .findViewWithTag(ComposeCapture.CAPTURE_HOST_TAG)

    @Test fun actualResultCardExportsFullPixelsWithoutVisibleHostOrGalleryWrite() {
        hostTestScreen()
        lateinit var result: Deferred<Bitmap>
        compose.runOnIdle {
            result = scope.async {
                ComposeCapture.captureToBitmap(activity, 1080, 1920, parentComposition) {
                    TrackSpeedTheme {
                        ShareableResultCard(ShareCardData(4.123456, 30.0, "flying", "24 Oct 2026",
                            athleteName = "Synthetic athlete", speedValue = 7.275, speedUnit = "m/s"), ShareCardTheme.MIDNIGHT)
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { result.isCompleted }
        val bitmap = runBlocking { result.await() }
        try {
            assertEquals(1080, bitmap.width); assertEquals(1920, bitmap.height)
            // Real gradients and bright timing/branding text must both be present; a blank/transparent
            // fallback bitmap must fail. Samples cover the whole export, not just a preview crop.
            var opaque = 0; var bright = 0; val colors = mutableSetOf<Int>()
            for (y in 0 until bitmap.height step 6) for (x in 0 until bitmap.width step 6) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) > 240) opaque++
                if (android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) > 180 && android.graphics.Color.blue(pixel) > 180) bright++
                colors += pixel
            }
            assertTrue("Expected opaque rendered content", opaque > 50_000)
            assertTrue("Expected actual timing/branding text", bright > 80)
            assertTrue("Expected composable gradients and text", colors.size > 32)
        } finally { bitmap.recycle() }
        compose.runOnIdle { assertNull("Temporary render host must be removed", temporaryHost()) }
    }

    @Test fun cancellingBeforeFirstFrameRemovesAttachedHostAndProducesNoBitmap() {
        hostTestScreen()
        lateinit var job: Job; var produced = false
        compose.runOnIdle {
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val bitmap = ComposeCapture.captureToBitmap(activity, 1080, 1920, parentComposition) {
                    TrackSpeedTheme { ShareableResultCard(ShareCardData(4.0, 30.0, "flying", "24 Oct 2026"), ShareCardTheme.MIDNIGHT) }
                }
                produced = true; bitmap.recycle()
            }
            val host = temporaryHost()
            assertNotNull("Test must exercise the real attached host", host)
            assertEquals(0f, host!!.alpha, 0f)
            assertFalse(host.isFocusable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, host.importantForAccessibility)
            job.cancel()
        }
        compose.waitUntil { job.isCompleted }
        compose.runOnIdle { assertFalse(produced); assertNull(temporaryHost()) }
    }

    @Test fun invalidDimensionsAndMissingActivityFailWithoutLeavingAHost() {
        hostTestScreen()
        lateinit var result: Deferred<Pair<Throwable?, Throwable?>>
        compose.runOnIdle {
            result = scope.async {
                val dimensions = runCatching { ComposeCapture.captureToBitmap(activity, 0, 1920, parentComposition) {} }.exceptionOrNull()
                val noActivity = runCatching { ComposeCapture.captureToBitmap(
                    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext, 1080, 1920) {} }.exceptionOrNull()
                dimensions to noActivity
            }
        }
        compose.waitUntil { result.isCompleted }
        val errors = runBlocking { result.await() }
        assertTrue(errors.first is IllegalArgumentException)
        assertTrue(errors.second is IOException)
        compose.runOnIdle { assertNull(temporaryHost()) }
    }
}
