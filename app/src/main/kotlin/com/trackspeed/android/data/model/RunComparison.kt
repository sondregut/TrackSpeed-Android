package com.trackspeed.android.data.model

import com.trackspeed.android.data.local.entities.RunEntity

/** Persisted method and course must agree before displaying a shared best. */
data class RunComparisonKey(val distance: Double, val startType: String, val multiPhone: Boolean)

fun RunEntity.comparisonKey(): RunComparisonKey? {
    if (!timeSeconds.isFinite() || timeSeconds <= 0 || !distance.isFinite() || distance <= 0 ||
        numberOfPhones < 1 || startType.isBlank() || cloudSyncPolicy == "localOnlyWatchBeta" ||
        cloudSyncPolicy == "localOnlyOnePhoneSprint"
    ) return null
    return RunComparisonKey(distance, startType, numberOfPhones > 1)
}

fun comparableBestTime(runs: List<RunEntity>): Double? {
    val keys = runs.map { it.comparisonKey() }
    if (keys.isEmpty() || keys.any { it == null } || keys.distinct().size != 1) return null
    return runs.minOf { it.timeSeconds }
}
