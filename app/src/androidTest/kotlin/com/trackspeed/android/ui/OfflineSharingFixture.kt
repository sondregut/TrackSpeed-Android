package com.trackspeed.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.data.local.TrackSpeedDatabase
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import com.trackspeed.android.protocol.SegmentSplit
import com.trackspeed.android.ui.screens.history.LocalGateFrameData
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in synthetic records for actual History -> sharing / Frame Scrubber navigation.
 * Never measured timing, never a physical-device fixture, and skipped by default.
 * The task-owned emulator MUST stay offline for its entire remaining lifetime:
 * these ordinary local records must never enter a real account's synchronization.
 */
class OfflineSharingFixture {
    @Test fun seedSyntheticSharingSessionsForActualNavigation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("offlineSharingFixture") == "true")
        assertEquals("ranchu", Build.HARDWARE)
        // The existing UI-only runner avoids analytics, billing and upload startup.
        assertTrue("Build the androidTest APK with -Ptrackspeed.uiParity=true",
            InstrumentationRegistry.getInstrumentation() is UiParityTestRunner)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertOffline(context)
        val filesRoot = context.filesDir.canonicalFile
        val imageDir = File(filesRoot, IMAGE_DIRECTORY)
        assertEquals(File(filesRoot, IMAGE_DIRECTORY).absolutePath, imageDir.canonicalPath)
        assertTrue(imageDir.isDirectory || imageDir.mkdirs())

        val start = createImageIfAbsent(context, imageDir, "synthetic-start.png", "START", 0)
        val finish = createImageIfAbsent(context, imageDir, "synthetic-finish.png", "FINISH", 2)
        val frameFiles = (1..3).map { index ->
            createImageIfAbsent(context, imageDir, "synthetic-frame-$index.png", "FRAME $index", index - 1)
        }
        val frames = frameFiles.mapIndexed { index, file ->
            LocalGateFrameData(
                imagePath = file.absolutePath,
                frameNumber = index + 1L,
                timestampInterval = (120_000_000_000L + index * 16_666_667L).toDouble() / NANOS_PER_SECOND,
                occupancy = 0.20f + index * 0.15f,
                longestRun = 28 + index * 8,
                isTracking = true,
                torsoTop = 235, torsoBottom = 390, frameHeight = IMAGE_HEIGHT,
                leftShoulderY = 245f, rightShoulderY = 248f,
                leftHipY = 382f, rightHipY = 386f,
                runStartY = 225, runEndY = 405
            )
        }
        val frameJson = Json.encodeToString(frames)
        assertEquals(frames, Json.decodeFromString<List<LocalGateFrameData>>(frameJson))
        assertTrue(frames.all { File(it.imagePath).parentFile == imageDir })

        val sessions = listOf(
            FixtureSession("$PREFIX-compact", "SYNTHETIC sharing — 3 runs", BASE_TIME,
                listOf(2_933_456_789L, 3_052_345_678L, 3_184_567_890L),
                listOf("Synthetic Alex", "", null)),
            FixtureSession("$PREFIX-dense", "SYNTHETIC sharing — 9 runs", BASE_TIME + 3_600_000L,
                listOf(2_987_654_321L, 3_104_567_890L, 3_087_654_321L,
                    2_954_321_987L, 3_163_456_789L, 3_001_234_567L,
                    2_916_789_123L, 3_214_567_890L, 3_078_901_234L),
                listOf("Synthetic Alex", null, "Synthetic Jordan", "", "Synthetic Casey",
                    "Synthetic athlete with a longer review name", "Synthetic Alex", "", "Synthetic Riley"))
        )
        assertOffline(context)
        // No migrations or destructive fallback: an incompatible database fails safely.
        val db = Room.databaseBuilder(context, TrackSpeedDatabase::class.java, "trackspeed.db").build()
        try {
            // Both DAOs use REPLACE; the transaction protects each absent check + insert.
            // Existing records are only compared, never updated, replaced or deleted.
            db.withTransaction {
                for (fixture in sessions) {
                    assertEquals(fixture.timesNanos.size, fixture.athleteNames.size)
                    val session = TrainingSessionEntity(
                        id = fixture.id, date = fixture.date, name = fixture.name,
                        notes = "SYNTHETIC offline sharing/navigation fixture. Not measured timing. " +
                            "Task 20261024; keep this emulator offline. No user media.",
                        distance = 30.0, startType = "flying", numberOfPhones = 3, numberOfGates = 3,
                        gateConfigJson = Json.encodeToString(mapOf("0" to 0.0, "1" to 15.0, "2" to 30.0)),
                        thumbnailPath = finish.absolutePath, createdAt = fixture.date, updatedAt = fixture.date
                    )
                    val existingSession = db.trainingSessionDao().getSession(session.id)
                    if (existingSession == null) {
                        assertOffline(context)
                        db.trainingSessionDao().insert(session)
                    } else {
                        assertEquals("Existing fixture differs; preserve it and stop", session, existingSession)
                    }
                    fixture.timesNanos.forEachIndexed { index, totalNanos ->
                        val splits = syntheticSplits(totalNanos)
                        val splitsJson = Json.encodeToString(splits)
                        assertEquals(splits, Json.decodeFromString<List<SegmentSplit>>(splitsJson))
                        assertEquals(totalNanos, splits.sumOf { it.splitNanos })
                        assertEquals(totalNanos, splits.last().cumulativeSplitNanos)
                        val run = RunEntity(
                            id = "${fixture.id}-run-${index + 1}", sessionId = fixture.id,
                            athleteName = fixture.athleteNames[index], runNumber = index + 1,
                            timeSeconds = totalNanos.toDouble() / NANOS_PER_SECOND,
                            distance = 30.0, startType = "flying", numberOfPhones = 3,
                            thumbnailPath = finish.absolutePath,
                            startImagePath = start.absolutePath, finishImagePath = finish.absolutePath,
                            splitsJson = splitsJson, gatePosition = 0.5,
                            startGatePosition = 0.5, finishGatePosition = 0.5,
                            startWorkResolutionWidth = IMAGE_WIDTH, finishWorkResolutionWidth = IMAGE_WIDTH,
                            workResolutionWidth = IMAGE_WIDTH,
                            localGateFramesDataJson = if (fixture.id == "$PREFIX-compact" && index == 0) frameJson else null,
                            localGateRole = "finish", createdAt = fixture.date + index * 60_000L
                        )
                        val existingRun = db.runDao().getRunById(run.id)
                        if (existingRun == null) {
                            assertOffline(context)
                            db.runDao().insert(run)
                        } else {
                            assertEquals("Existing fixture differs; preserve it and stop", run, existingRun)
                        }
                    }
                    assertEquals(fixture.timesNanos.size, db.trainingSessionDao().getRunCount(fixture.id))
                }
                assertOffline(context)
            }
        } finally {
            db.close()
        }
        assertOffline(context)
    }

    private fun assertOffline(context: Context) {
        assertEquals("ranchu", Build.HARDWARE)
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
        assertNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
    }

    private fun syntheticSplits(totalNanos: Long): List<SegmentSplit> {
        require(totalNanos > 0)
        // Integer nanoseconds are authoritative; no rounded/display times are re-parsed.
        val firstNanos = totalNanos * 53L / 100L
        return listOf(
            SegmentSplit(0, 1, "$PREFIX-gate-start", "$PREFIX-gate-split",
                firstNanos, 15.0, firstNanos, 15.0),
            SegmentSplit(1, 2, "$PREFIX-gate-split", "$PREFIX-gate-finish",
                totalNanos - firstNanos, 15.0, totalNanos, 30.0)
        )
    }

    private fun createImageIfAbsent(context: Context, directory: File, name: String, label: String, step: Int): File {
        val file = File(directory, name)
        assertEquals(directory.canonicalFile, file.canonicalFile.parentFile)
        if (!file.exists()) {
            assertOffline(context)
            val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(20, 29, 43))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.rgb(43, 60, 79)
                canvas.drawRect(0f, 150f, IMAGE_WIDTH.toFloat(), 510f, paint)
                paint.color = Color.rgb(140, 155, 170)
                paint.strokeWidth = 3f
                for (lane in 0..3) canvas.drawLine(0f, 190f + lane * 90f, IMAGE_WIDTH.toFloat(), 190f + lane * 90f, paint)
                paint.color = Color.rgb(56, 189, 248)
                paint.strokeWidth = 4f
                canvas.drawLine(256f, 150f, 256f, 510f, paint)
                // Deliberately schematic figure: generated pixels, not a photo/person.
                val figureX = 195f + step * 55f
                paint.color = Color.rgb(251, 191, 36)
                canvas.drawCircle(figureX, 210f, 24f, paint)
                paint.strokeWidth = 16f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(figureX, 245f, figureX, 380f, paint)
                canvas.drawLine(figureX, 265f, figureX - 42f, 320f, paint)
                canvas.drawLine(figureX, 265f, figureX + 42f, 310f, paint)
                canvas.drawLine(figureX, 380f, figureX - 44f, 468f, paint)
                canvas.drawLine(figureX, 380f, figureX + 48f, 455f, paint)
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                paint.textSize = 25f
                canvas.drawText("SYNTHETIC UI FIXTURE", 256f, 53f, paint)
                paint.textSize = 35f
                canvas.drawText(label, 256f, 106f, paint)
                paint.textSize = 22f
                canvas.drawText("NOT MEASURED TIMING", 256f, 562f, paint)
                paint.textSize = 18f
                canvas.drawText("Generated review pixels · no user media", 256f, 600f, paint)
                assertOffline(context)
                try {
                    Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                } catch (_: FileAlreadyExistsException) {
                    // Another invocation won; validate, never truncate or replace.
                }
            } finally {
                bitmap.recycle()
            }
        }
        assertTrue("Existing fixture image must be a nonempty file", file.isFile && file.length() in 1L..1_048_576L)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        assertEquals(IMAGE_WIDTH, bounds.outWidth)
        assertEquals(IMAGE_HEIGHT, bounds.outHeight)
        return file
    }

    private data class FixtureSession(
        val id: String, val name: String, val date: Long,
        val timesNanos: List<Long>, val athleteNames: List<String?>
    )

    private companion object {
        const val PREFIX = "offline-sharing-review-20261024-v1"
        const val IMAGE_DIRECTORY = "offline-review/sharing-navigation-20261024-v1"
        const val BASE_TIME = 1792857600000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val IMAGE_WIDTH = 512
        const val IMAGE_HEIGHT = 640
    }
}
