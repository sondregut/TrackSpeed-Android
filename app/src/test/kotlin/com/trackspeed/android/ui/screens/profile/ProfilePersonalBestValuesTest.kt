package com.trackspeed.android.ui.screens.profile

import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportDiscipline
import org.junit.Assert.*
import org.junit.Test

class ProfilePersonalBestValuesTest {
    @Test fun acceptsSecondsCommaAndMinutesWithoutRounding() {
        assertEquals(10.50123456789, parseProfileRecordTime(" 10,50123456789 s ")!!, 0.0)
        assertEquals(118.20123456789, parseProfileRecordTime("1:58,20123456789")!!, 0.0)
        assertEquals(.875, parseProfileRecordTime(".875S")!!, 0.0)
        assertEquals(.125, parseProfileRecordTime("0:00.125")!!, 0.0)
        assertEquals(600.25, parseProfileRecordTime("10:00.25")!!, 0.0)
    }

    @Test fun rejectsMalformedNonfiniteZeroAndOutOfRangeSeconds() {
        listOf("0", "-1", "NaN", "Infinity", "1e400", "0:00", "1:60", "1:99.9", "1.5:30",
            "-1:30", "1:-2", "1:2:3", "1:", ":30", "1::30", "1,2.3", "1ss", "12 sec", "1: 02")
            .forEach { assertNull(it, parseProfileRecordTime(it)) }
        assertNull(parseProfileRecordTime("1:00", allowMinutes = false))
        assertNull(parseProfileRecordTime(""))
        assertNull(parseProfileRecordTime("  \n"))
    }

    @Test fun unchangedEditorValuesRoundTripCanonicalPrecision() {
        listOf(.000000001, .92345678912345, 10.50123456789, 118.20123456789, 3600.123456789)
            .forEach { value ->
                assertEquals(value.toBits(), parseProfileRecordTime(formatEditableProfileRecord(value))!!.toBits())
            }
    }

    @Test fun emptyTimesClearOptionalRecordsAndFlyingDistanceTogether() {
        val draft = ProfilePersonalBestDraft(SportDiscipline.MIDDLE_800M, " \n ", FlyingDistance.METERS_30, "")
        assertTrue(draft.canSave)
        assertEquals(ProfilePersonalBestValues(primaryEvent = SportDiscipline.MIDDLE_800M), draft.valuesOrNull())
    }

    @Test fun eventAndFlyingValuesRemainDistinctAndInvalidDraftCannotSave() {
        val draft = ProfilePersonalBestDraft(SportDiscipline.MIDDLE_800M, "1:58.20", FlyingDistance.METERS_20, "2,123456789")
        assertEquals(ProfilePersonalBestValues(SportDiscipline.MIDDLE_800M, 118.2, FlyingDistance.METERS_20, 2.123456789), draft.valuesOrNull())
        assertFalse(draft.copy(personalRecordText = "1:60").canSave)
        assertNull(draft.copy(flyingPrText = "1:02").valuesOrNull())
        assertNull(draft.copy(flyingPrText = "NaN").valuesOrNull())
    }

    @Test fun valuesRequireFinitePositiveTimesAndMatchingFlyingDistance() {
        assertFalse(ProfilePersonalBestValues(personalRecordSeconds = Double.POSITIVE_INFINITY).isValid)
        assertFalse(ProfilePersonalBestValues(flyingPrSeconds = 1.0).isValid)
        assertFalse(ProfilePersonalBestValues(flyingDistance = FlyingDistance.METERS_10).isValid)
        assertFalse(ProfilePersonalBestValues(flyingDistance = FlyingDistance.METERS_10, flyingPrSeconds = 0.0).isValid)
        assertTrue(ProfilePersonalBestValues().isValid)
    }
}
