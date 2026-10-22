package com.trackspeed.android.camerastart

import com.trackspeed.android.ui.screens.race.DeviceRole
import com.trackspeed.android.ui.screens.race.RaceModeUiState
import org.junit.Assert.*
import org.junit.Test

class CameraStartRolePolicyTest {
    @Test fun `motion and in frame use local pose only on start phone`() {
        for (mode in listOf("motionStart", "inFrame")) {
            val start = RaceModeUiState(role = DeviceRole.START, startType = mode)
            assertTrue(start.usesLocalPoseStart)
            assertTrue(start.requiresLocalCamera)
            assertFalse(start.hasUnsupportedLocalStart)
            assertFalse(start.isLocalGateReady)
            val finish = start.copy(role = DeviceRole.FINISH)
            assertFalse(finish.usesLocalPoseStart)
            assertTrue(finish.requiresLocalCamera)
        }
    }
    @Test fun `reaction start remains guarded locally while remote finish works`() {
        val start = RaceModeUiState(role = DeviceRole.START, startType = "reactionStart")
        assertTrue(start.hasUnsupportedLocalStart)
        assertFalse(start.isLocalGateReady)
        assertFalse(start.copy(role = DeviceRole.FINISH).hasUnsupportedLocalStart)
    }
}
