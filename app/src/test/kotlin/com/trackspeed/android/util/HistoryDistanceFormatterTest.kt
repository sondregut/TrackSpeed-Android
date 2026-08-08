package com.trackspeed.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDistanceFormatterTest {

    @Test
    fun labelsFortyYardsLikeIosHistory() {
        val descriptor = HistoryDistanceFormatter.descriptor(36.576)

        assertEquals("40yd", descriptor.key)
        assertEquals("40yd", descriptor.label)
        assertEquals(36.576, descriptor.sortMeters, 0.0)
        assertEquals("36.576", HistoryDistanceFormatter.csvNumericMeters(36.576))
    }

    @Test
    fun normalizesMeterLabelsAndCsvValuesLikeIosHistory() {
        assertEquals("60m", HistoryDistanceFormatter.labelForMeters(60.01))
        assertEquals("60", HistoryDistanceFormatter.csvNumericMeters(60.0))
        assertEquals("60.125", HistoryDistanceFormatter.csvNumericMeters(60.125))

        val descriptor = HistoryDistanceFormatter.descriptor(60.06)
        assertEquals("60.1", descriptor.key)
        assertEquals("60.1m", descriptor.label)
        assertEquals(60.1, descriptor.sortMeters, 0.0)
    }
}
