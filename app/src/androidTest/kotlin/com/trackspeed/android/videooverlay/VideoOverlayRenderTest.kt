package com.trackspeed.android.videooverlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@UnstableApi
class VideoOverlayRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val output = File(context.getExternalFilesDir(null), "overlay-review").apply { mkdirs() }
    private fun snapshot(uri: Uri = Uri.EMPTY, options: OverlayPreferences = OverlayPreferences()) = VideoOverlaySnapshot(
        uri, 2.93, listOf(SplitMark("10m", .85, .85, "0–10m"), SplitMark("20m", 1.90, 1.05, "10–20m"),
            SplitMark("FINISH", 2.93, 1.03, "20–30m")), "Flying 30m", "READY",
        options.speedUnit.format(30.0 / 2.93), .25, options.showSpeed, options.showRunType, options)

    @Test fun phaseAndMetricsUseMeasuredValues() {
        val s=snapshot()
        assertEquals(RaceOverlayPhase.READY,s.frameState(.1).phase)
        assertTrue(s.frameState(.1).visibleSplits.isEmpty())
        assertNull(s.frameState(1.0).speedDisplay)
        assertEquals(1,s.frameState(1.1).visibleSplits.size)
        assertEquals(2.93,s.frameState(20.0).displayedTimeSeconds,0.0)
        assertEquals("10.2 m/s",s.frameState(20.0).speedDisplay)
        assertTrue(s.copy(preferences=s.preferences.copy(splitMode=OverlaySplitMode.OFF)).frameState(20.0).visibleSplits.isEmpty())
    }

    @Test fun explicitPreviewViewportMatchesExportCanvas() {
        val renderer=RaceOverlayCanvasRenderer()
        val expected=Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(expected),snapshot(),4.0)
        val backing=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(backing),snapshot(),4.0,width=360f,height=640f)
        assertTrue(expected.sameAs(Bitmap.createBitmap(backing,0,0,360,640)))
        val safe=overlaySafeRect(360f,640f,true)
        for (y in 0 until 640) for (x in 0 until 360) {
            if (x < safe.left - 1 || x > safe.right + 1 || y < safe.top - 1 || y > safe.bottom + 1)
                assertEquals("Pixel outside safe area at $x,$y",0,Color.alpha(expected.getPixel(x,y)))
        }
    }

    @Test fun reusedExportLayerMatchesFreshFrameAcrossPhasesAndSeeking() {
        for (aspect in listOf(9f/16,16f/9)) {
            val s=snapshot().copy(sourceAspectRatio=aspect)
            val overlay=RaceOverlayCanvasOverlay(s)
            overlay.setCanvasSize(360,640)
            for(timeUs in listOf(0L,900_000L,2_200_000L,3_500_000L,500_000L,0L)) {
                val actual=overlay.getBitmap(timeUs)
                val fresh=Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888)
                RaceOverlayCanvasRenderer().draw(Canvas(fresh),s,timeUs/1_000_000.0)
                assertTrue("Stale overlay pixels at $timeUs, aspect $aspect",fresh.sameAs(actual))
                fresh.recycle()
            }
            overlay.release()
        }
    }

    @Test fun renderAndExportPortraitAndLandscape() = runBlocking {
        val renderer=RaceOverlayCanvasRenderer()
        for (case in listOf("portrait","landscape","social-landscape")) {
            val social = case == "social-landscape"
            val orientation = if (social) "landscape" else case
            val source=File(context.cacheDir,"synthetic-$orientation.mp4")
            instrumentation.context.assets.open("synthetic-$orientation.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val retriever=MediaMetadataRetriever()
            retriever.setDataSource(source.path)
            val frame=requireNotNull(retriever.getFrameAtTime(3_500_000,MediaMetadataRetriever.OPTION_CLOSEST))
            retriever.release()
            val s=snapshot(Uri.fromFile(source),OverlayPreferences(
                canvas=if(social) OverlayCanvas.SOCIAL_9_16 else OverlayCanvas.ORIGINAL,
                speedUnit=when(case) { "landscape" -> OverlaySpeedUnit.MPH; "social-landscape" -> OverlaySpeedUnit.KMH; else -> OverlaySpeedUnit.MPS },
                splitMode=if(case=="portrait") OverlaySplitMode.CUMULATIVE else OverlaySplitMode.SEGMENTS
            )).copy(sourceAspectRatio=frame.width.toFloat()/frame.height)
            val canvasFrame = if (social) Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas=Canvas(bitmap)
                canvas.drawColor(Color.BLACK)
                val fittedHeight=1080f*frame.height/frame.width
                canvas.drawBitmap(frame,null,android.graphics.RectF(0f,(1920-fittedHeight)/2,1080f,(1920+fittedHeight)/2),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            } else frame
            fun save(name:String,block:(Canvas)->Unit) {
                val bitmap=canvasFrame.copy(Bitmap.Config.ARGB_8888,true)
                block(Canvas(bitmap))
                File(output,"$case-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            save("before") { LegacyRaceOverlayCanvasRenderer().draw(it,s,3.5) }
            save("after") { renderer.draw(it,s,3.5) }
            save("safe-guide") { renderer.draw(it,s,3.5,true) }
            val segments=snapshot(Uri.fromFile(source),OverlayPreferences(speedUnit=OverlaySpeedUnit.MPH,splitMode=OverlaySplitMode.SEGMENTS,position=OverlayPosition.BOTTOM_RIGHT)).copy(sourceAspectRatio=s.sourceAspectRatio)
            save("mph-segments") { renderer.draw(it,segments,3.5,true) }
            val exported=VideoOverlayExportService(context).export(s) { }
            assertTrue(exported.length()>1000)
            val result=MediaMetadataRetriever()
            result.setDataSource(exported.path)
            val encodedWidth=result.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val encodedHeight=result.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rotation=result.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            assertEquals(canvasFrame.width,if(rotation%180==0) encodedWidth else encodedHeight)
            assertEquals(canvasFrame.height,if(rotation%180==0) encodedHeight else encodedWidth)
            val encoded=requireNotNull(result.getFrameAtTime(3_500_000,MediaMetadataRetriever.OPTION_CLOSEST))
            assertEquals(canvasFrame.width,encoded.width)
            assertEquals(canvasFrame.height,encoded.height)
            File(output,"$case-export-frame.png").outputStream().use { encoded.compress(Bitmap.CompressFormat.PNG,100,it) }
            // Compare the real exported result to the shared preview renderer, allowing H.264 loss.
            val preview=canvasFrame.copy(Bitmap.Config.ARGB_8888,true)
            renderer.draw(Canvas(preview),s,3.5)
            val card=overlaySafeRect(canvasFrame.width.toFloat(),canvasFrame.height.toFloat(),s.preferences.socialSafe)
            var difference=0L; var samples=0
            for (y in card.top.toInt()+3 until card.bottom.toInt()-3 step 2) for (x in card.left.toInt()+3 until card.right.toInt()-3 step 2) {
                val a=preview.getPixel(x,y);val b=encoded.getPixel(x,y)
                difference+=kotlin.math.abs(Color.red(a)-Color.red(b))+kotlin.math.abs(Color.green(a)-Color.green(b))+kotlin.math.abs(Color.blue(a)-Color.blue(b));samples+=3
            }
            assertTrue("Preview/export mean channel error ${difference.toDouble()/samples}",difference.toDouble()/samples<18)
            exported.copyTo(File(output,"$case-export.mp4"),overwrite=true)
            result.release(); if(social) canvasFrame.recycle();frame.recycle();preview.recycle();encoded.recycle()
        }
    }
}
