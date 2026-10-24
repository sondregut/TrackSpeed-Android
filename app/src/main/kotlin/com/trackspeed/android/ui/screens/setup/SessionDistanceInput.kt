package com.trackspeed.android.ui.screens.setup

/** Shipped iOS setup text contract: comma or period decimals, finite positive canonical meters. */
internal object SessionDistanceInput {
    fun isValidMeters(meters: Double): Boolean = meters.isFinite() && meters > 0.0

    fun parseMeters(text: String): Double? =
        text.replace(',', '.').toDoubleOrNull()?.takeIf(::isValidMeters)

    /** Blank custom input means the selected preset; nonblank text must itself be valid. */
    fun permitsSelection(customText: String, selectedMeters: Double): Boolean =
        isValidMeters(selectedMeters) && (customText.isBlank() || parseMeters(customText) != null)
}
