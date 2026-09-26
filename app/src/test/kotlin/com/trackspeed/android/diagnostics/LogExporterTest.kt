package com.trackspeed.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExporterTest {

    @Test
    fun formatLogExportMatchesIosHeaderShape() {
        val text = LogExporter.formatLogExport(
            lines = listOf("06-14 12:00:00.000  1234  1234 I TrackSpeed: ready"),
            deviceId = "device-123",
            generatedAtMillis = 0L,
            subsystem = "com.trackspeed.android"
        )

        assertTrue(text.contains("# TrackSpeed log export\n"))
        assertTrue(text.contains("# subsystem : com.trackspeed.android\n"))
        assertTrue(text.contains("# device    : device-123\n"))
        assertTrue(text.contains("# generated : 1970-01-01T00:00:00.000Z\n"))
        assertTrue(text.contains("# entries   : 1\n"))
        assertTrue(text.endsWith("TrackSpeed: ready\n"))
    }

    @Test
    fun storagePathMatchesIosDeviceTimestampLabelSuffixShape() {
        assertEquals(
            "device-123/1718371200-30min-abcdef12.log",
            LogExporter.storagePath(
                deviceId = "device-123",
                label = "30min",
                timestampSeconds = 1_718_371_200L,
                randomSuffix = "abcdef12"
            )
        )
    }
}
