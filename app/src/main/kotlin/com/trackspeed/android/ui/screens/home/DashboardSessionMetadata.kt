package com.trackspeed.android.ui.screens.home

/** Display classification from the persisted session structure, never its name. */
internal object DashboardSessionMetadata {
    fun usesLapLabels(numberOfPhones: Int, numberOfGates: Int): Boolean =
        // saveSession stores solo laps as one phone/one gate. One-phone camera
        // sprints also use one phone, but persist at least two virtual gates.
        // Unknown gate counts cannot establish lap semantics; use generic runs.
        numberOfPhones == 1 && numberOfGates == 1
}
