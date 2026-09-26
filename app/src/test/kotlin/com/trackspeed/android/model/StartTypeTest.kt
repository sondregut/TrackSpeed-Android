package com.trackspeed.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartTypeTest {

    @Test
    fun `canonical start types preserve iOS wire values and order`() {
        assertEquals(
            listOf("flying", "touchRelease", "countdown", "voiceCommand", "inFrame"),
            StartType.entries.map { it.rawValue }
        )
    }

    @Test
    fun `legacy aliases normalize without changing the wire contract`() {
        assertEquals(StartType.FLYING, StartType.fromRawValue("standing"))
        assertEquals(StartType.TOUCH_RELEASE, StartType.fromRawValue("touch"))
        assertEquals(StartType.VOICE_COMMAND, StartType.fromRawValue("voice"))
        assertEquals(StartType.IN_FRAME, StartType.fromRawValue("inframe"))
    }

    @Test
    fun `only touch countdown and voice bypass a start gate crossing`() {
        assertFalse(StartType.FLYING.usesStartTrigger)
        assertTrue(StartType.TOUCH_RELEASE.usesStartTrigger)
        assertTrue(StartType.COUNTDOWN.usesStartTrigger)
        assertTrue(StartType.VOICE_COMMAND.usesStartTrigger)
        assertFalse(StartType.IN_FRAME.usesStartTrigger)
    }

    @Test
    fun `only in frame requests the front camera`() {
        StartType.entries.filterNot { it == StartType.IN_FRAME }.forEach {
            assertFalse(it.usesFrontCamera)
        }
        assertTrue(StartType.IN_FRAME.usesFrontCamera)
        assertTrue(StartType.IN_FRAME.shouldUseFrontCameraAtGate(isStartGate = true))
        assertFalse(StartType.IN_FRAME.shouldUseFrontCameraAtGate(isStartGate = false))
    }

    @Test
    fun `android premium policy only gates countdown and voice`() {
        assertEquals(
            listOf(StartType.COUNTDOWN, StartType.VOICE_COMMAND),
            StartType.entries.filter { it.isPro }
        )
    }

    @Test
    fun `trigger modes bypass camera only at the start gate`() {
        listOf(StartType.TOUCH_RELEASE, StartType.COUNTDOWN, StartType.VOICE_COMMAND).forEach {
            assertFalse(it.requiresCameraAtGate(isStartGate = true))
            assertTrue(it.requiresCameraAtGate(isStartGate = false))
        }
        assertTrue(StartType.FLYING.requiresCameraAtGate(isStartGate = true))
        assertTrue(StartType.IN_FRAME.requiresCameraAtGate(isStartGate = true))
    }
}
