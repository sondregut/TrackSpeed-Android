package com.trackspeed.android.videooverlay

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OverlayPresentationTest {
    @Test fun landscapeOnSocialCanvasKeepsHeaderOutsideTheFootage() {
        val layout=overlayCardLayout(1080f,1920f,OverlayPreferences(),true,3,true,16f/9f)
        assertTrue(layout.compactHeader)
        assertTrue(layout.card.bottom < (1920f-1080f/(16f/9f))/2)
        assertTrue(layout.scale >= 2f)
    }

    @Test fun correctConversionsFromCanonicalMetersPerSecond() {
        assertEquals("10.0 m/s", OverlaySpeedUnit.MPS.format(10.0))
        assertEquals("22.4 mph", OverlaySpeedUnit.MPH.format(10.0))
        assertEquals("36.0 km/h", OverlaySpeedUnit.KMH.format(10.0))
        assertEquals("1.0 mph", OverlaySpeedUnit.MPH.format(.44704))
        assertNull(OverlaySpeedUnit.MPH.format(Double.NaN))
        assertNull(OverlaySpeedUnit.KMH.format(Double.MAX_VALUE))
        assertNull(measuredAverageSpeed(30.0, 0.0))
        assertNull(measuredAverageSpeed(-1.0, 1.0))
        assertEquals(10.0, measuredAverageSpeed(30.0, 3.0)!!, 0.0)
    }
    @Test fun everyMetricCombinationFitsEveryPositionAndAspect() {
        for ((width,height) in listOf(360f to 640f, 1080f to 1920f, 1920f to 1080f, 1080f to 1080f, 320f to 900f)) {
            for (position in OverlayPosition.entries) for (mask in 0..7) for (rows in 0..5) {
                val options = OverlayPreferences(position=position, showTime=mask and 1 != 0)
                val layout = overlayCardLayout(width,height,options,mask and 2 != 0,rows,mask and 4 != 0)
                assertTrue(layout.card.left >= layout.safe.left - .01f)
                assertTrue(layout.card.top >= layout.safe.top - .01f)
                assertTrue(layout.card.right <= layout.safe.right + .01f)
                assertTrue(layout.card.bottom <= layout.safe.bottom + .01f)
                assertTrue(layout.scale > 0)
            }
        }
    }
    @Test fun hiddenFieldsReflowWithoutAnEmptyTimeSlot() {
        val full=overlayCardLayout(390f,844f,OverlayPreferences(),true,3,true)
        val minimal=overlayCardLayout(390f,844f,OverlayPreferences(showTime=false),false,0,false)
        assertTrue(minimal.card.height < full.card.height)
        assertEquals(full.card.top,minimal.card.top,0f)
    }
    @Test fun savedOptionsRoundTrip() {
        val options=OverlayPreferences(OverlaySpeedUnit.MPH,false,false,false,OverlaySplitMode.SEGMENTS,OverlayPosition.BOTTOM_RIGHT)
        assertEquals(options, Json.decodeFromString<OverlayPreferences>(Json.encodeToString(options)))
    }
    @Test fun hundredthsAreRoundedWithoutFloatingPointTruncation() {
        assertEquals("3.33",formatOverlayTime(3.329999999))
        assertEquals("1:00.00",formatOverlayTime(59.999))
        assertEquals("0.00",formatOverlayTime(Double.NaN))
    }
}
