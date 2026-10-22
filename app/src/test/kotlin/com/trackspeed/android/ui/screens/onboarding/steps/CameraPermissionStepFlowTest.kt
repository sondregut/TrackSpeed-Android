package com.trackspeed.android.ui.screens.onboarding.steps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionStepFlowTest {
    @Test
    fun alreadyGrantedPageWaitsForAnExplicitActionAfterResume() {
        val flow = CameraPermissionStepFlow(initialGranted = true)
        repeat(3) { flow.onResume(granted = true) }
        assertEquals(CameraPermissionState.GRANTED, flow.permissionState)
        // Resume has not consumed navigation, so the first visible Continue still owns it.
        assertTrue(flow.claimContinue())
        assertFalse(flow.claimContinue())
    }

    @Test
    fun newGrantDoesNotConsumeContinuationAndRepeatedActionsNavigateOnce() {
        val flow = CameraPermissionStepFlow(initialGranted = false)
        assertFalse(flow.claimContinue())
        flow.onPermissionResult(granted = true)
        assertEquals(CameraPermissionState.GRANTED, flow.permissionState)
        assertTrue(flow.claimContinue())
        assertFalse(flow.claimContinue())
        assertFalse(flow.claimSkip())
    }

    @Test
    fun denialKeepsCameraUnavailableAndSkipNavigatesOnce() {
        val flow = CameraPermissionStepFlow(initialGranted = false)
        flow.onPermissionResult(granted = false)
        flow.onResume(granted = false)
        assertEquals(CameraPermissionState.DENIED, flow.permissionState)
        assertFalse(flow.claimContinue())
        assertTrue(flow.claimSkip())
        assertFalse(flow.claimSkip())
    }

    @Test
    fun grantInSettingsRefreshesToContinueWithoutNavigating() {
        val flow = CameraPermissionStepFlow(initialGranted = false)
        flow.onPermissionResult(granted = false)
        flow.onResume(granted = true)
        assertEquals(CameraPermissionState.GRANTED, flow.permissionState)
        assertTrue(flow.claimContinue())
    }

    @Test
    fun revocationInSettingsRemovesGrantedStateAndLeavesSkipAvailable() {
        val flow = CameraPermissionStepFlow(initialGranted = true)
        flow.onResume(granted = false)
        assertEquals(CameraPermissionState.DENIED, flow.permissionState)
        assertFalse(flow.claimContinue())
        assertTrue(flow.claimSkip())
    }

    @Test
    fun lifecycleAndPermissionCallbacksCannotReopenCompletedNavigation() {
        val flow = CameraPermissionStepFlow(initialGranted = false)
        assertTrue(flow.claimSkip())
        flow.onPermissionResult(granted = true)
        flow.onResume(granted = true)
        assertFalse(flow.claimContinue())
        assertFalse(flow.claimSkip())
        // A genuine new screen visit gets its own gate and remains explicitly actionable.
        assertTrue(CameraPermissionStepFlow(initialGranted = true).claimContinue())
    }
}
