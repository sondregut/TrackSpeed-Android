package com.trackspeed.android.ui.screens.onboarding.steps

internal enum class CameraPermissionState { NOT_DETERMINED, GRANTED, DENIED }

/** Permission changes never navigate. A visible action can claim completion only once per visit. */
internal class CameraPermissionStepFlow(initialGranted: Boolean) {
    var permissionState = if (initialGranted) CameraPermissionState.GRANTED else CameraPermissionState.NOT_DETERMINED
        private set
    private var navigationClaimed = false

    fun onPermissionResult(granted: Boolean) {
        permissionState = if (granted) CameraPermissionState.GRANTED else CameraPermissionState.DENIED
    }

    fun onResume(granted: Boolean) {
        permissionState = when {
            granted -> CameraPermissionState.GRANTED
            permissionState == CameraPermissionState.GRANTED -> CameraPermissionState.DENIED
            else -> permissionState
        }
    }

    fun claimContinue(): Boolean = permissionState == CameraPermissionState.GRANTED && claimNavigation()

    fun claimSkip(): Boolean = claimNavigation()

    private fun claimNavigation(): Boolean {
        if (navigationClaimed) return false
        navigationClaimed = true
        return true
    }
}
