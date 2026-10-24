package com.trackspeed.android.ui.screens.race

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Refreshes grants after Settings/background; never requests permissions or reconnects. */
@Composable
internal fun RefreshPairingPermissionOnResume(
    currentlyGranted: Boolean,
    readPermission: () -> Boolean,
    onPermissionChanged: (Boolean) -> Unit,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val latestGrant by rememberUpdatedState(currentlyGranted)
    val latestRead by rememberUpdatedState(readPermission)
    val latestChanged by rememberUpdatedState(onPermissionChanged)
    DisposableEffect(lifecycleOwner) {
        // Initial permission resolution belongs to the existing launch flow.
        var wasPaused = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> wasPaused = true
                Lifecycle.Event.ON_RESUME -> if (wasPaused) {
                    wasPaused = false
                    val granted = latestRead()
                    if (granted != latestGrant) latestChanged(granted)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
