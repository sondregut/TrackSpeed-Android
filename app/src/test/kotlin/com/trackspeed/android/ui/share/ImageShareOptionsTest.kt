package com.trackspeed.android.ui.share

import com.trackspeed.android.videooverlay.OverlaySpeedUnit
import org.junit.Assert.*
import org.junit.Test

class ImageShareOptionsTest {
    @Test fun formatsHaveExactReleasedExportDimensionsAndUncroppedAspect() {
        assertEquals(1080, RunImageFormat.STORY.exportWidth)
        assertEquals(1920, RunImageFormat.STORY.exportHeight)
        assertEquals(1080, RunImageFormat.FEED.exportWidth)
        assertEquals(1350, RunImageFormat.FEED.exportHeight)
        RunImageFormat.entries.forEach { format ->
            assertEquals(format.exportWidth.toFloat() / format.exportHeight, format.aspectRatio, 0f)
            assertEquals(format.exportHeight, format.designHeight * 3)
        }
    }

    @Test fun feedSuppressesSplitsOnlyForRealVisiblePhotos() {
        val feed = RunImageOptions(format = RunImageFormat.FEED)
        assertTrue(feed.displaysSplits(hasPhotos = false))
        assertFalse(feed.displaysSplits(hasPhotos = true))
        assertTrue(feed.copy(showPhotos = false).displaysSplits(hasPhotos = true))
        assertTrue(feed.copy(format = RunImageFormat.STORY).displaysSplits(hasPhotos = true))
    }

    @Test fun temporaryFeedSuppressionDoesNotEraseEitherSavedSplitChoice() {
        listOf(true, false).forEach { original ->
            val story = RunImageOptions(showSplits = original)
            val feed = story.copy(format = RunImageFormat.FEED)
            assertFalse(feed.displaysSplits(true))
            assertEquals(original, feed.copy(showPhotos = false).displaysSplits(true))
            assertEquals(original, feed.copy(format = RunImageFormat.STORY).displaysSplits(true))
        }
    }

    @Test fun everyRunOutputOptionContributesToStableFingerprintAndRestores() {
        val default = RunImageOptions()
        val keys = mutableSetOf<String>()
        for (format in RunImageFormat.entries) for (mask in 0..15) {
            val options = RunImageOptions(format, mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0)
            keys += imageShareFingerprint(options.fingerprintFields())
        }
        assertEquals(32, keys.size)
        assertEquals(imageShareFingerprint(default.fingerprintFields()), imageShareFingerprint(default.copy(format = RunImageFormat.FEED).copy(format = RunImageFormat.STORY).fingerprintFields()))
    }

    @Test fun sessionOptionsAreIndependentAndDoNotAddFeedOrStartType() {
        val keys = (0..7).map { mask -> imageShareFingerprint(SessionImageOptions(mask and 1 != 0,
            mask and 2 != 0, mask and 4 != 0).fingerprintFields()) }.toSet()
        assertEquals(8, keys.size)
        assertEquals(3, SessionImageOptions().fingerprintFields().size)
    }

    @Test fun centerCropGateMappingUsesRealImageGeometry() {
        assertEquals(.5f, croppedGateFraction(.5, 1920, 1080)!!, 0f)
        assertEquals(.25f, croppedGateFraction(.25, 300, 400)!!, 0f)
        // Wide image outer quarters are cropped away in the3:4 thumbnail.
        assertNull(croppedGateFraction(.1, 1920, 1080))
        assertNull(croppedGateFraction(.9, 1920, 1080))
        assertEquals(0f, croppedGateFraction(0.0, 300, 400)!!, 0f)
    }

    @Test fun unavailableOrNonFiniteGatePositionNeverInventsACenterLine() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -.1, 1.1).forEach {
            assertNull(croppedGateFraction(it, 300, 400))
        }
        assertNull(croppedGateFraction(.5, 0, 400))
        assertNull(croppedGateFraction(.5, 300, -1))
    }

    @Test fun speedChoicesUseExactFactorsFromCanonicalMeasurementsAndNeverCompoundRounding() {
        val meters = 30.0; val seconds = 4.123456
        val canonical = meters / seconds
        assertEquals(canonical, runImageSpeed(meters, seconds, OverlaySpeedUnit.MPS)!!, 0.0)
        assertEquals(canonical * 3.6, runImageSpeed(meters, seconds, OverlaySpeedUnit.KMH)!!, 0.0)
        assertEquals(canonical / .44704, runImageSpeed(meters, seconds, OverlaySpeedUnit.MPH)!!, 1e-12)
        repeat(50) {
            runImageSpeed(meters, seconds, OverlaySpeedUnit.MPH)
            runImageSpeed(meters, seconds, OverlaySpeedUnit.KMH)
            assertEquals(canonical, runImageSpeed(meters, seconds, OverlaySpeedUnit.MPS)!!, 0.0)
        }
    }

    @Test fun invalidMeasurementAndConversionOverflowHaveNoDisplayedSpeed() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0).forEach {
            assertNull(runImageSpeed(it, 4.0, OverlaySpeedUnit.MPS))
            assertNull(runImageSpeed(30.0, it, OverlaySpeedUnit.MPS))
        }
        assertNull(runImageSpeed(Double.MAX_VALUE, 1.0, OverlaySpeedUnit.KMH))
    }

    @Test fun sessionFitUsesMeasuredHeightAndAccountsForEveryOmittedRow() {
        val options = SessionImageOptions(showPhotos = true, showSplits = true)
        val layouts = sessionImageLayouts(20, options)
        assertEquals(8, layouts.first().visibleCount)
        val fit = firstFittingSessionLayout(layouts, 409) { layout ->
            120 + layout.visibleCount * (layout.density.photoHeight + layout.density.rowPadding * 2) +
                (layout.visibleCount - 1) * layout.density.rowGap + 40
        }!!
        assertTrue(fit.visibleCount in 1..7)
        assertEquals(20, fit.visibleCount + fit.hiddenCount(20))
        assertEquals(24, fit.density.photoWidth)
        assertNull(firstFittingSessionLayout(layouts, 1) { 2 })
    }

    @Test fun compactSplitContextUsesBestVisibleSplitRunWithoutDisablingSelectedSplits() {
        assertEquals(2, compactSessionSplitIndex(listOf(4.5, 3.9, 4.0), listOf(true, false, true)))
        assertEquals(0, compactSessionSplitIndex(listOf(Double.NaN), listOf(true)))
        assertNull(compactSessionSplitIndex(listOf(4.0), listOf(false)))
    }

    @Test fun selectedLaterSplitRunIsIncludedWithoutMiscountingHiddenRuns() {
        assertEquals(listOf(0, 1, 11), sessionImageVisibleIndices(20, 3, listOf(11), true))
        assertEquals(listOf(0, 1, 2), sessionImageVisibleIndices(20, 3, listOf(11), false))
        assertEquals(listOf(0, 1, 2), sessionImageVisibleIndices(20, 3, listOf(1, 11), true))
        assertTrue(sessionImageVisibleIndices(20, 0, listOf(11), true).isEmpty())
    }

    @Test fun sessionCandidatesNeverInventRowsOrDropSelectedPhotosToFit() {
        for (total in listOf(0, 1, 5, 8, 20)) {
            val layouts = sessionImageLayouts(total, SessionImageOptions())
            assertTrue(layouts.isNotEmpty())
            layouts.forEach {
                assertTrue(it.visibleCount in 0..minOf(total, 8))
                assertTrue(it.density.photoWidth >= 24)
                assertTrue(it.density.photoHeight >= 32)
                assertEquals(total, it.visibleCount + it.hiddenCount(total))
            }
        }
    }

    @Test fun timingGateImagesKeepThousandthsAndOnePhoneImagesKeepHundredths() {
        assertEquals(ImageTimePresentation("4.123", "s"), imageTimePresentation(4.123456))
        assertEquals(ImageTimePresentation("4.12", "s"), imageTimePresentation(4.123456, digits = 2))
        assertEquals("61.235", imageTimePresentation(61.23456, useMinutes = false).value)
        assertEquals(ImageTimePresentation("1:01.235", "min:sec"), imageTimePresentation(61.23456))
    }

    @Test fun timeRoundingCarriesMinutesAndUsesPosixNotationRegardlessOfLocale() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(ImageTimePresentation("1:00.000", "min:sec"), imageTimePresentation(59.9996))
            assertEquals("4.123", imageTimePresentation(4.123).value)
            assertEquals("4.130", imageTimePresentation(4.1295).value)
        } finally { java.util.Locale.setDefault(original) }
    }

    @Test fun invalidTimingDoesNotInventZeroOrAlterCanonicalValue() {
        val canonical = 4.123456789
        imageTimePresentation(canonical)
        assertEquals(4.123456789, canonical, 0.0)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0).forEach {
            assertEquals(ImageTimePresentation("—", ""), imageTimePresentation(it))
        }
    }
}
