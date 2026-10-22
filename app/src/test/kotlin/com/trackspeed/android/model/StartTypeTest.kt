package com.trackspeed.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartTypeTest {
    @Test fun `remote movement starts preserve identity and never become flying`() {
        assertEquals(StartType.MOTION_START, StartType.fromRawValue("motionStart"))
        assertEquals(StartType.REACTION_START, StartType.fromRawValue("reactionStart"))
        for (type in listOf(StartType.MOTION_START,StartType.REACTION_START)) {
            assertEquals(type != StartType.REACTION_START, type.supportsLocalStart)
            assertEquals(type != StartType.REACTION_START, type in StartType.localStartTypes)
            assertTrue(type.requiresCameraAtGate(false))
            assertTrue(type.usesStartTrigger)
        }
    }


    @Test
    fun `canonical start types preserve iOS wire values and order`() {
        assertEquals(
            listOf("flying", "touchRelease", "countdown", "voiceCommand", "inFrame", "motionStart", "reactionStart"),
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
    fun `pose starts and deliberate triggers bypass line crossing`() {
        assertFalse(StartType.FLYING.usesStartTrigger)
        assertTrue(StartType.TOUCH_RELEASE.usesStartTrigger)
        assertTrue(StartType.COUNTDOWN.usesStartTrigger)
        assertTrue(StartType.VOICE_COMMAND.usesStartTrigger)
        assertTrue(StartType.IN_FRAME.usesStartTrigger)
        assertTrue(StartType.MOTION_START.usesStartTrigger)
    }

    @Test
    fun `pose start modes request front camera only on start phone`() {
        StartType.entries.filterNot { it in listOf(StartType.IN_FRAME, StartType.MOTION_START, StartType.REACTION_START) }.forEach {
            assertFalse(it.usesFrontCamera)
        }
        assertTrue(StartType.IN_FRAME.usesFrontCamera)
        assertTrue(StartType.MOTION_START.usesFrontCamera)
        assertTrue(StartType.MOTION_START.requiresCameraAtGate(true))
        assertFalse(StartType.REACTION_START.supportsLocalStart)
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
