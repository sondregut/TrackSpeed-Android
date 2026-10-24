package com.trackspeed.android.ui

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.trackspeed.android.R
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackspeed.android.videooverlay.OverlaySpeedUnit
import com.trackspeed.android.protocol.SegmentSplit
import com.trackspeed.android.ui.components.*
import com.trackspeed.android.ui.screens.history.ThemeSelector
import com.trackspeed.android.ui.share.*
import com.trackspeed.android.ui.theme.TrackSpeedTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Real production controls/cards and in-memory capture. No gallery, cache, chooser or external sharing. */
@RunWith(AndroidJUnit4::class)
class ImageShareOptionsTest {
    @get:Rule val compose = createComposeRule()
    private val split = SegmentSplit(0, 1, "synthetic-start", "synthetic-finish", 4_123_456_000, 30.0, 4_123_456_000, 30.0)
    private fun photo() = Bitmap.createBitmap(90, 120, Bitmap.Config.ARGB_8888).apply {
        eraseColor(AndroidColor.rgb(40, 110, 170))
        for (x in 35..55) for (y in 20..100) setPixel(x, y, AndroidColor.rgb(220, 140, 60))
    }
    private fun run(photo: Bitmap? = null) = ShareCardData(4.123456, 30.0, "touchRelease", "24 Oct 2026",
        athleteName = "Synthetic Alex", speedValue = 7.275, speedUnit = "m/s", segments = listOf(split),
        startPhoto = photo, finishPhoto = photo, startGatePosition = .4, finishGatePosition = .6)
    private fun session(photo: Bitmap? = null) = ShareSessionCardData(30.0, "24 Oct 2026", listOf(
        ShareSessionRunData(1, 4.123456, photo, listOf(split), "Alex"),
        ShareSessionRunData(2, 4.5, photo, listOf(split), "Sam")))

    @Test fun feedPhotoConstraintRestoresExplicitSplitChoiceAndControlsWrapAtCompactWidth() {
        var options by mutableStateOf(RunImageOptions())
        compose.setContent { TrackSpeedTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                RunImageOptionControls(options, true, true, true, true, { options = it }, Modifier.width(240.dp))
            }
        } }
        compose.onNodeWithTag("image-format-FEED").performClick().assertIsSelected()
        compose.onNodeWithTag("image-option-splits").assertIsNotEnabled().assertIsNotSelected()
        compose.onNodeWithTag("image-feed-hint").assertIsDisplayed()
        compose.onNodeWithTag("image-option-photos").performClick()
        compose.onNodeWithTag("image-option-splits").assertIsEnabled().assertIsSelected().performClick()
        compose.onNodeWithTag("image-option-photos").performClick()
        compose.onNodeWithTag("image-format-STORY").performClick()
        compose.onNodeWithTag("image-option-splits").assertIsEnabled().assertIsNotSelected()
        compose.onNodeWithTag("image-option-name").assertIsDisplayed()
    }

    @Test fun absentOptionalDataDoesNotOfferNameStartOrSplitsAndMissingPhotosDoNotDisableSplits() {
        var hasSplits by mutableStateOf(false)
        compose.setContent { TrackSpeedTheme {
            RunImageOptionControls(RunImageOptions(format = RunImageFormat.FEED), hasSplits, false, false, false, {})
        } }
        compose.onNodeWithTag("image-option-name").assertDoesNotExist()
        compose.onNodeWithTag("image-option-start").assertDoesNotExist()
        compose.onNodeWithTag("image-option-splits").assertDoesNotExist()
        compose.onNodeWithTag("image-option-photos").assertIsDisplayed()
        compose.runOnIdle { hasSplits = true }
        compose.onNodeWithTag("image-option-splits").assertIsEnabled().assertIsSelected()
        compose.onNodeWithTag("image-feed-hint").assertDoesNotExist()
    }

    @Test fun sessionControlsKeepStoryOnlyAndExposeIndependentVisibility() {
        var options by mutableStateOf(SessionImageOptions())
        compose.setContent { TrackSpeedTheme { SessionImageOptionControls(options, true, true, { options = it }) } }
        compose.onNodeWithTag("image-format-FEED").assertDoesNotExist()
        compose.onNodeWithTag("image-option-start").assertDoesNotExist()
        compose.onNodeWithTag("image-option-name").performClick().assertIsNotSelected()
        compose.onNodeWithTag("image-option-photos").performClick().assertIsNotSelected()
        compose.onNodeWithTag("image-option-splits").assertIsSelected()
    }

    @Test fun everyThemeHasAccessibleNameRadioSelectionAndCanRestoreEarlierChoice() {
        var theme by mutableStateOf(ShareCardTheme.MIDNIGHT)
        val selected = mutableListOf<ShareCardTheme>()
        compose.setContent { TrackSpeedTheme {
            ThemeSelector(theme, { theme = it; selected += it }, Modifier.width(240.dp))
        } }
        val labels = listOf("Midnight", "Ember", "Ocean", "Forest", "Slate")
        ShareCardTheme.entries.zip(labels).forEach { (candidate, label) ->
            compose.onNodeWithContentDescription(label).assertHasClickAction().performClick().assertIsSelected()
            compose.onNodeWithTag("image-theme-${candidate.name}").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Midnight").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(ShareCardTheme.entries.toList() + ShareCardTheme.MIDNIGHT, selected) }
    }

    @Test fun actualRunCardHidesOnlySelectedFieldsAndKeepsTimeUnitsAcrossFormats() {
        val photo = photo()
        var options by mutableStateOf(RunImageOptions())
        compose.setContent { TrackSpeedTheme { ShareableResultCard(run(photo), ShareCardTheme.MIDNIGHT, Modifier.width(270.dp), options) } }
        compose.onNodeWithTag("run-image-start-photo").assertIsDisplayed()
        compose.onNodeWithTag("run-image-finish-photo").assertIsDisplayed()
        compose.onNodeWithTag("run-image-name").assertTextEquals("Synthetic Alex")
        compose.onNodeWithTag("run-image-time").assertTextEquals("4.123")
        compose.onNodeWithTag("run-image-speed").assertTextEquals("7.28 m/s")
        compose.runOnIdle { options = options.copy(format = RunImageFormat.FEED, showName = false, showStartType = false) }
        compose.onNodeWithTag("run-image-name").assertDoesNotExist()
        compose.onNodeWithTag("run-image-start-type").assertDoesNotExist()
        compose.onNodeWithTag("run-image-splits").assertDoesNotExist()
        compose.onNodeWithTag("run-image-time").assertTextEquals("4.123")
        compose.runOnIdle { options = options.copy(showPhotos = false) }
        compose.onNodeWithTag("run-image-start-photo").assertDoesNotExist()
        compose.onNodeWithTag("run-image-finish-photo").assertDoesNotExist()
        compose.onNodeWithTag("run-image-splits").assertIsDisplayed()
    }

    @Test fun longRunUsesMinuteUnitAndRecordedTimingPrecisionWithoutChangingStoredTime() {
        var data by mutableStateOf(run().copy(timeSeconds = 61.23456))
        compose.setContent { TrackSpeedTheme { ShareableResultCard(data, ShareCardTheme.MIDNIGHT, Modifier.width(270.dp)) } }
        compose.onNodeWithTag("run-image-time").assertTextEquals("1:01.235")
        compose.onNodeWithText("min:sec").assertIsDisplayed()
        compose.runOnIdle { data = data.copy(timeDigits = 2) }
        compose.onNodeWithTag("run-image-time").assertTextEquals("1:01.23")
        compose.runOnIdle { assertEquals(61.23456, data.timeSeconds, 0.0) }
    }

    @Test fun hiddenSessionNamesRemoveBothMixedRowsAndSingleNameFooter() {
        var data by mutableStateOf(session())
        var options by mutableStateOf(SessionImageOptions(showName = false))
        compose.setContent { TrackSpeedTheme { ShareableSessionCard(data, ShareCardTheme.MIDNIGHT, Modifier.width(270.dp), options) } }
        compose.onNodeWithTag("session-share-row-name-1").assertDoesNotExist()
        compose.onNodeWithTag("session-share-row-name-2").assertDoesNotExist()
        compose.onNodeWithTag("session-share-footer-name").assertDoesNotExist()
        compose.runOnIdle { data = data.copy(runs = data.runs.map { it.copy(athleteName = "Alex") }); options = options.copy(showName = true) }
        compose.onNodeWithTag("session-share-footer-name").assertTextEquals("Alex")
        compose.runOnIdle { options = options.copy(showName = false) }
        compose.onNodeWithTag("session-share-footer-name").assertDoesNotExist()
    }

    @Test fun unitControlUpdatesActualCardAndFingerprintAndRestoresExactCanonicalSpeed() {
        val original = run()
        var data by mutableStateOf(original.copy(speedValue = runImageSpeed(original.distance, original.timeSeconds, OverlaySpeedUnit.MPS)))
        val keys = mutableListOf<String>()
        compose.setContent { TrackSpeedTheme {
            RunImageSpeedUnitControls(OverlaySpeedUnit.fromRaw(data.speedUnit), { unit ->
                data = data.copy(speedUnit = unit.label, speedValue = runImageSpeed(data.distance, data.timeSeconds, unit))
                keys += data.outputKey(ShareCardTheme.MIDNIGHT, "en-US")
            })
        } }
        compose.onNodeWithTag("image-speed-unit-MPH").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals((30.0 / 4.123456) / .44704, data.speedValue!!, 1e-12) }
        compose.onNodeWithTag("image-speed-unit-KMH").performClick().assertIsSelected()
        compose.onNodeWithTag("image-speed-unit-MPS").performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals(30.0 / 4.123456, data.speedValue!!, 0.0)
            assertEquals(3, keys.distinct().size)
            assertEquals(original.timeSeconds, data.timeSeconds, 0.0)
        }
    }

    @Test fun actualOutputFingerprintIncludesOptionsAndPhotoContentWithoutAlteringMetrics() {
        val bitmap = photo(); val data = run(bitmap)
        val default = RunImageOptions()
        val key = data.outputKey(ShareCardTheme.MIDNIGHT, "en-US", default)
        assertNotEquals(key, data.copy(timeDigits = 2).outputKey(ShareCardTheme.MIDNIGHT, "en-US", default))
        val variants = listOf(default.copy(format = RunImageFormat.FEED), default.copy(showPhotos = false),
            default.copy(showSplits = false), default.copy(showStartType = false), default.copy(showName = false))
        variants.forEach { assertNotEquals(key, data.outputKey(ShareCardTheme.MIDNIGHT, "en-US", it)) }
        bitmap.setPixel(0, 0, AndroidColor.RED)
        assertNotEquals(key, data.outputKey(ShareCardTheme.MIDNIGHT, "en-US", default))
        assertEquals(4.123456, data.timeSeconds, 0.0); assertEquals(7.275, data.speedValue!!, 0.0)
        assertNotEquals(data.outputKey(ShareCardTheme.MIDNIGHT, "en-US"),
            data.copy(finishGatePosition = .7).outputKey(ShareCardTheme.MIDNIGHT, "en-US"))
        val session = session(bitmap)
        val sessionKey = session.outputKey(ShareCardTheme.MIDNIGHT, "en-US")
        listOf(SessionImageOptions(showPhotos = false), SessionImageOptions(showSplits = false), SessionImageOptions(showName = false))
            .forEach { assertNotEquals(sessionKey, session.outputKey(ShareCardTheme.MIDNIGHT, "en-US", it)) }
    }

    /** Exercise real Bundle/Parcel serialization, not reuse of the same ViewModel/map instance. */
    private fun recreatedHandle(handle: SavedStateHandle): SavedStateHandle {
        val bundle = Bundle()
        handle.keys().forEach { key ->
            when (val value = handle.get<Any>(key)) {
                is String -> bundle.putString(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                else -> fail("Only primitive share preferences may be retained: $key")
            }
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle); parcel.setDataPosition(0)
            val restored = parcel.readBundle(javaClass.classLoader)!!
            return SavedStateHandle(restored.keySet().associateWith { restored.get(it) })
        } finally { parcel.recycle() }
    }

    @Test fun allRunChoicesSurviveProcessSerializationAndHiddenFieldsStayHidden() {
        val handle = SavedStateHandle()
        val options = RunImageOptions(RunImageFormat.FEED, showSplits = false, showPhotos = false, showStartType = false, showName = false)
        ImageShareSavedState(handle).apply { save(options); save(ShareCardTheme.FOREST) }
        handle["imageShareSpeedUnit"] = "mph"
        val recreated = recreatedHandle(handle)
        val state = ImageShareSavedState(recreated)
        assertEquals(options, state.runOptions())
        assertEquals(ShareCardTheme.FOREST, state.theme())
        assertEquals("mph", recreated.get<String>("imageShareSpeedUnit"))
        compose.setContent { TrackSpeedTheme { ShareableResultCard(run(photo()), state.theme(), Modifier.width(270.dp), state.runOptions()) } }
        compose.onNodeWithTag("run-image-name").assertDoesNotExist()
        compose.onNodeWithTag("run-image-start-photo").assertDoesNotExist()
        compose.onNodeWithTag("run-image-finish-photo").assertDoesNotExist()
        compose.onNodeWithTag("run-image-start-type").assertDoesNotExist()
        compose.onNodeWithTag("run-image-splits").assertDoesNotExist()
        compose.onNodeWithTag("run-image-time").assertTextEquals("4.123")
    }

    @Test fun sessionChoicesAndThemeSurviveRecreationWithoutReenablingNamesPhotosOrSplits() {
        val handle = SavedStateHandle()
        val options = SessionImageOptions(false, false, false)
        ImageShareSavedState(handle).apply { save(options); save(ShareCardTheme.OCEAN) }
        val state = ImageShareSavedState(recreatedHandle(handle))
        assertEquals(options, state.sessionOptions()); assertEquals(ShareCardTheme.OCEAN, state.theme())
        compose.setContent { TrackSpeedTheme { ShareableSessionCard(session(photo()), state.theme(), Modifier.width(270.dp), state.sessionOptions()) } }
        compose.onNodeWithTag("session-share-row-name-1").assertDoesNotExist()
        compose.onNodeWithTag("session-image-photo-1").assertDoesNotExist()
        compose.onNodeWithTag("session-image-splits-1").assertDoesNotExist()
    }

    @Test fun unknownEnumRestorationDoesNotDiscardPersistedPrivacyChoices() {
        val handle = SavedStateHandle(mapOf("imageShareFormat" to "future-format", "imageShareTheme" to "future-theme",
            "imageSharePhotos" to false, "imageShareName" to false))
        val restored = ImageShareSavedState(recreatedHandle(handle))
        assertEquals(RunImageFormat.STORY, restored.runOptions().format)
        assertEquals(ShareCardTheme.MIDNIGHT, restored.theme())
        assertFalse(restored.runOptions().showName); assertFalse(restored.runOptions().showPhotos)
    }

    @Test fun fiveEightAndManyRunSessionsRenderCompleteRowsSplitsNamesAndAccurateHiddenCountInsideSafeBounds() {
        val thumbnail = photo()
        fun fixture(count: Int) = ShareSessionCardData(30.0, "24 Oct 2026", (1..count).map {
            ShareSessionRunData(it, 4.123456 + it, thumbnail, listOf(split), "Athlete $it")
        })
        var data by mutableStateOf(fixture(5))
        compose.setContent { TrackSpeedTheme { ShareableSessionCard(data, ShareCardTheme.MIDNIGHT, Modifier.width(360.dp)) } }
        listOf(5, 8, 20).forEach { total ->
            compose.runOnIdle { data = fixture(total) }
            val safe = compose.onNodeWithTag("session-image-safe-area").fetchSemanticsNode().boundsInRoot
            val rows = compose.onAllNodes(hasTestTagPrefix("session-image-row-")).fetchSemanticsNodes()
            assertTrue("At least one complete row must fit", rows.isNotEmpty())
            assertTrue(rows.size <= 8)
            rows.forEach { node ->
                assertTrue(node.boundsInRoot.top >= safe.top - 1f)
                assertTrue(node.boundsInRoot.bottom <= safe.bottom + 1f)
                assertTrue(node.boundsInRoot.left >= safe.left - 1f)
                assertTrue(node.boundsInRoot.right <= safe.right + 1f)
            }
            val times = compose.onAllNodes(hasTestTagPrefix("session-image-time-")).fetchSemanticsNodes()
            assertEquals(rows.size, times.size)
            val lastTime = times.maxBy { it.boundsInRoot.bottom }
            assertPaintedNode(lastTime.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag], 20)
            times.forEach { node ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                val clipping = timingGlyphClipping(results.single())
                assertTrue("Time must not be clipped: total=$total text=${results.single().layoutInput.text} problems=$clipping", clipping.isEmpty())
                assertTrue("Time font must stay readable", results.single().layoutInput.style.fontSize.value >= 18f)
            }
            assertEquals(rows.size, compose.onAllNodes(hasTestTagPrefix("session-image-photo-")).fetchSemanticsNodes().size)
            assertEquals(rows.size, compose.onAllNodes(hasTestTagPrefix("session-share-row-name-")).fetchSemanticsNodes().size)
            assertTrue(compose.onAllNodes(hasTestTagPrefix("session-image-splits-")).fetchSemanticsNodes().isNotEmpty())
            val omitted = total - rows.size
            if (omitted > 0) {
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                compose.onNodeWithTag("session-image-hidden-count").assertTextEquals(context.getString(R.string.share_card_more_runs, omitted)).assertIsDisplayed()
                assertPaintedNode("session-image-hidden-count", 20)
            } else compose.onNodeWithTag("session-image-hidden-count").assertDoesNotExist()
            val footer = compose.onNodeWithTag("session-image-footer").fetchSemanticsNode().boundsInRoot
            assertTrue(footer.bottom <= safe.bottom + 1f)
        }
    }

    /**
     * String Text's GetTextLayoutResult reconstructs a MultiParagraph with max constraints while
     * retaining its smaller painted size. didOverflowWidth can therefore be true for blank width.
     * Assert the actual line/character extents and complete unellipsized content instead.
     */
    private fun timingGlyphClipping(result: androidx.compose.ui.text.TextLayoutResult): List<String> {
        val problems = mutableListOf<String>()
        val textLength = result.layoutInput.text.length
        if (result.lineCount != 1) return listOf("Expected one timing line, got ${result.lineCount}")
        if (result.isLineEllipsized(0)) problems += "Timing is ellipsized"
        if (result.getLineStart(0) != 0 || result.getLineEnd(0, visibleEnd = true) != textLength)
            problems += "Not every timing character is laid out"
        if (result.getLineLeft(0) < 0f || result.getLineRight(0) > result.size.width.toFloat())
            problems += "Timing line exceeds painted width"
        if (result.getLineTop(0) < 0f || result.getLineBottom(0) > result.size.height.toFloat())
            problems += "Timing line exceeds painted height"
        for (offset in 0 until minOf(result.getLineEnd(0, visibleEnd = true), textLength)) {
            val box = result.getBoundingBox(offset)
            if (box.left < 0f || box.right > result.size.width.toFloat() ||
                box.top < 0f || box.bottom > result.size.height.toFloat()) {
                problems += "Timing character $offset exceeds painted bounds"
            }
        }
        return problems
    }

    @Test fun glyphClippingCheckAcceptsNaturalWidthAndRejectsRealClippingAndEllipsis() {
        compose.setContent { TrackSpeedTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Column {
                    Text("5.123", fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.widthIn(max = 225.dp).testTag("timing-natural"))
                    Text("1234.567", fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip,
                        modifier = Modifier.width(20.dp).testTag("timing-clipped"))
                    Text("1234.567", fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(40.dp).testTag("timing-ellipsized"))
                }
            }
        } }
        fun clipping(tag: String): List<String> {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return timingGlyphClipping(results.single())
        }
        assertTrue("Unconstrained timing glyphs should fit their natural box", clipping("timing-natural").isEmpty())
        assertTrue("A genuinely clipped timing must fail", clipping("timing-clipped").isNotEmpty())
        assertTrue("An ellipsized timing must fail", clipping("timing-ellipsized").isNotEmpty())
        assertPaintedNode("timing-natural", 20)
    }

    private fun assertPaintedNode(tag: String, minimumBrightPixels: Int) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        var bright = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (AndroidColor.alpha(pixel) > 200 && AndroidColor.red(pixel) > 110 &&
                AndroidColor.green(pixel) > 110 && AndroidColor.blue(pixel) > 110) bright++
        }
        assertTrue("Expected actual painted pixels for $tag, not only a semantics node", bright >= minimumBrightPixels)
    }

    private fun hasTestTagPrefix(prefix: String): SemanticsMatcher = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private lateinit var activity: ComponentActivity
    private lateinit var composition: CompositionContext
    private lateinit var scope: CoroutineScope
    private fun captureHost() {
        compose.setContent {
            activity = checkNotNull(LocalActivity.current) as ComponentActivity
            composition = rememberCompositionContext(); scope = rememberCoroutineScope()
            Box {}
        }
    }
    private fun capture(width: Int, height: Int, content: @Composable () -> Unit): Bitmap {
        lateinit var result: Deferred<Bitmap>
        compose.runOnIdle { result = scope.async { ComposeCapture.captureToBitmap(activity, width, height, composition) { TrackSpeedTheme { content() } } } }
        compose.waitUntil(10_000) { result.isCompleted }
        return runBlocking { result.await() }
    }
    private fun assertScaledEquivalent(preview: Bitmap, export: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(export, preview.width, preview.height, true)
        try {
            var totalError = 0L; var samples = 0; var bright = 0
            for (y in 2 until preview.height - 2 step 2) for (x in 2 until preview.width - 2 step 2) {
                val a = preview.getPixel(x,y); val b = scaled.getPixel(x,y)
                assertEquals(255, AndroidColor.alpha(a)); assertEquals(255, AndroidColor.alpha(b))
                totalError += abs(AndroidColor.red(a)-AndroidColor.red(b)) + abs(AndroidColor.green(a)-AndroidColor.green(b)) + abs(AndroidColor.blue(a)-AndroidColor.blue(b))
                if (AndroidColor.red(a) > 180 && AndroidColor.green(a) > 180 && AndroidColor.blue(a) > 180) bright++
                samples++
            }
            assertTrue("Must render actual timing/branding pixels", bright > 40)
            assertTrue("Preview and export must preserve proportions; mean RGB error=${totalError.toDouble()/(samples*3)}",
                totalError.toDouble()/(samples*3) < 12.0)
            for ((x,y) in listOf(0 to 0, export.width-1 to 0, 0 to export.height-1, export.width-1 to export.height-1))
                assertEquals("Exports have no transparent preview corners", 255, AndroidColor.alpha(export.getPixel(x,y)))
        } finally { scaled.recycle() }
    }

    @Test fun realRunPreviewAndExportHaveMatchingProportionsInBothFormats() {
        captureHost(); val data = run(photo())
        RunImageFormat.entries.forEach { format ->
            val options = RunImageOptions(format)
            val preview = capture(360, format.designHeight) { ShareableResultCard(data, ShareCardTheme.MIDNIGHT, options = options) }
            val export = capture(format.exportWidth, format.exportHeight) { ShareableResultCard(data, ShareCardTheme.MIDNIGHT, options = options) }
            try {
                assertEquals(format.exportWidth, export.width); assertEquals(format.exportHeight, export.height)
                assertScaledEquivalent(preview, export)
            } finally { preview.recycle(); export.recycle() }
        }
    }

    @Test fun realSessionPreviewAndExportUseOneLogicalCanvas() {
        captureHost(); val data = session(photo())
        val preview = capture(270, 480) { ShareableSessionCard(data, ShareCardTheme.MIDNIGHT) }
        val export = capture(1080, 1920) { ShareableSessionCard(data, ShareCardTheme.MIDNIGHT) }
        try { assertScaledEquivalent(preview, export) } finally { preview.recycle(); export.recycle() }
    }

    @Test fun denseSessionProductionExportsPaintLastIncludedRowsAndCountInsideSafeCanvas() {
        captureHost(); val thumbnail = photo()
        listOf(5, 8, 20).forEach { count ->
            val data = ShareSessionCardData(30.0, "24 Oct 2026", (1..count).map {
                ShareSessionRunData(it, 4.123456 + it, thumbnail, listOf(split), "Athlete $it")
            })
            val preview = capture(360, 640) { ShareableSessionCard(data, ShareCardTheme.MIDNIGHT) }
            val export = capture(1080, 1920) { ShareableSessionCard(data, ShareCardTheme.MIDNIGHT) }
            try {
                assertScaledEquivalent(preview, export)
                // Real high-contrast lower content must be painted in the safe area, not disappear
                // below a clipped weighted column. Safe area at export ends before y1500.
                var lowerBright = 0
                for (y in 1050 until 1490 step 2) for (x in 80 until 1000 step 2) {
                    val pixel = export.getPixel(x,y)
                    if (AndroidColor.red(pixel) > 180 && AndroidColor.green(pixel) > 180 && AndroidColor.blue(pixel) > 180) lowerBright++
                }
                assertTrue("Expected actual lower timing/name/count/footer pixels for $count runs", lowerBright > 25)
                var outsideBright = 0
                for (y in 1510 until 1900 step 3) for (x in 0 until 1080 step 3) {
                    val pixel = export.getPixel(x,y)
                    if (AndroidColor.red(pixel) > 180 && AndroidColor.green(pixel) > 180 && AndroidColor.blue(pixel) > 180) outsideBright++
                }
                assertEquals("No timing rows may spill below the safe area", 0, outsideBright)
            } finally { preview.recycle(); export.recycle() }
        }
    }
}
