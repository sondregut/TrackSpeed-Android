package com.trackspeed.android.ui.screens.race

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

private const val UNKNOWN_UNCERTAINTY_MS = 1_000.0

internal fun contextStartReference(context: JsonObject?): Long? =
    (context?.get("startReferenceNanos") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

internal fun contextWatchUncertainty(context: JsonObject?): Double {
    if (context?.containsKey("watchStart") != true) return 0.0
    val watch = context["watchStart"] as? JsonObject ?: return UNKNOWN_UNCERTAINTY_MS
    return (watch["clockUncertaintyMilliseconds"] as? JsonPrimitive)?.doubleOrNull
        ?.takeIf { it.isFinite() && it >= 0 } ?: UNKNOWN_UNCERTAINTY_MS
}

internal fun contextClockUncertainty(context: JsonObject?): Double {
    if (context?.containsKey("clockQuality") != true) return 0.0
    val quality = context["clockQuality"] as? JsonObject ?: return UNKNOWN_UNCERTAINTY_MS
    if ((quality["isKnown"] as? JsonPrimitive)?.booleanOrNull != true) return UNKNOWN_UNCERTAINTY_MS
    return (quality["uncertaintyMs"] as? JsonPrimitive)?.doubleOrNull
        ?.takeIf { it.isFinite() && it >= 0 } ?: UNKNOWN_UNCERTAINTY_MS
}

internal fun sameWatchStart(previous: JsonObject?, candidate: JsonObject?): Boolean {
    fun runId(context: JsonObject?): String? {
        val watch = context?.get("watchStart") as? JsonObject
        val source = watch?.get("source") as? JsonObject
        return (source?.get("runID") as? JsonPrimitive)?.contentOrNull
            ?: (source?.get("runId") as? JsonPrimitive)?.contentOrNull
    }
    val previousId = runId(previous) ?: return false
    return previousId.equals(runId(candidate), ignoreCase = true)
}
