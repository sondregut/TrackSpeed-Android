package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.data.local.entities.RunEntity

/** Presentation only. Never changes stored results, event timestamps or sync bounds. */
internal data class ActiveRunDisplay(val timeSeconds: Double, val runNumber: Int, val isRunning: Boolean)

internal fun activeRunDisplay(status: String, elapsedTimeSeconds: Double, latestRun: RunEntity?): ActiveRunDisplay {
    val running = status in setOf("started", "collecting_gates", "waiting_for_result")
    // A newly started run must show its own zero before the first timer tick.
    val time = when {
        running -> elapsedTimeSeconds
        elapsedTimeSeconds > 0.0 -> elapsedTimeSeconds
        latestRun != null -> latestRun.timeSeconds
        else -> 0.0
    }
    val number = if (!running && latestRun != null && time > 0.0) {
        latestRun.runNumber
    } else {
        (latestRun?.runNumber ?: 0) + 1
    }
    return ActiveRunDisplay(time, number, running)
}
