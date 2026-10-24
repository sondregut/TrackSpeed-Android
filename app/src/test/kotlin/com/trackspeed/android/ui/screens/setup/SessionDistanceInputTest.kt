package com.trackspeed.android.ui.screens.setup

import org.junit.Assert.*
import org.junit.Test

class SessionDistanceInputTest {
    @Test fun commaAndPeriodAreDecimalSeparatorsWithoutConvertingMeters() {
        assertEquals(12.5, SessionDistanceInput.parseMeters("12,5")!!, 0.0)
        assertEquals(12.5, SessionDistanceInput.parseMeters("12.5")!!, 0.0)
        assertEquals(36.576, SessionDistanceInput.parseMeters("36,576")!!, 0.0)
        assertEquals(1.234, SessionDistanceInput.parseMeters("1,234")!!, 0.0) // decimal, never thousands
        assertEquals(30.0, SessionDistanceInput.parseMeters("30")!!, 0.0)
    }

    @Test fun nonFiniteAndOverflowCannotBecomeReadyOrReplaceSelectedMeters() {
        listOf("NaN", "Infinity", "+Infinity", "-Infinity", "1e309", "-1e309", "9".repeat(400)).forEach { input ->
            assertNull(input, SessionDistanceInput.parseMeters(input))
            assertFalse(input, SessionDistanceInput.permitsSelection(input, 30.0))
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { meters ->
            assertFalse(SessionDistanceInput.isValidMeters(meters))
            assertFalse(SessionDistanceInput.permitsSelection("", meters))
            assertFalse(SessionDistanceInput.permitsSelection("30", meters))
        }
    }

    @Test fun zeroNegativeAndUnderflowAreRejected() {
        listOf("0", "0,0", "-0.0", "-1", "-12,5", "1e-999").forEach { input ->
            assertNull(input, SessionDistanceInput.parseMeters(input))
            assertFalse(input, SessionDistanceInput.permitsSelection(input, 30.0))
        }
        assertFalse(SessionDistanceInput.isValidMeters(0.0))
        assertFalse(SessionDistanceInput.isValidMeters(-1.0))
    }

    @Test fun unicodeLookalikesAndGroupingAreNotGuessedIntoAnotherDistance() {
        listOf("12\u066B5", "12\uFF0E5", "12\uFF0C5", "１２.５", "12\u00A05", "1\u202F234", "−12.5",
            "12,5.0", "12.5,0", "12,,5", "12..5", "12m").forEach { input ->
            assertNull(input, SessionDistanceInput.parseMeters(input))
            assertFalse(input, SessionDistanceInput.permitsSelection(input, 30.0))
        }
    }

    @Test fun clearedCustomFieldUsesValidPresetButInvalidNonblankInputDoesNot() {
        assertNull(SessionDistanceInput.parseMeters(""))
        assertTrue(SessionDistanceInput.permitsSelection("", 30.0))
        assertTrue(SessionDistanceInput.permitsSelection("  ", 30.0))
        assertFalse(SessionDistanceInput.permitsSelection("12,", Double.POSITIVE_INFINITY))
        assertFalse(SessionDistanceInput.permitsSelection("invalid", 30.0))
        assertTrue(SessionDistanceInput.permitsSelection("12,5", 12.5))
    }

    @Test fun positiveSubMeterValuesAndLargeFiniteValuesKeepExistingAndroidPolicy() {
        assertEquals(0.5, SessionDistanceInput.parseMeters("0,5")!!, 0.0)
        assertEquals(Double.MAX_VALUE, SessionDistanceInput.parseMeters(Double.MAX_VALUE.toString())!!, 0.0)
        assertTrue(SessionDistanceInput.isValidMeters(Double.MIN_VALUE))
        assertTrue(SessionDistanceInput.permitsSelection("1e308", 1e308))
    }
}
