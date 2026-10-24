package com.trackspeed.android.ui.screens.profile

import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportDiscipline
import java.math.BigDecimal

/** Explicit profile PRs, distinct from best times derived from recorded runs. */
data class ProfilePersonalBestValues(
    val primaryEvent: SportDiscipline = SportDiscipline.SPRINT_100M,
    val personalRecordSeconds: Double? = null,
    val flyingDistance: FlyingDistance? = null,
    val flyingPrSeconds: Double? = null
) {
    val isValid: Boolean
        get() = (personalRecordSeconds == null || personalRecordSeconds.isFinite() && personalRecordSeconds > 0) &&
            (flyingPrSeconds == null && flyingDistance == null ||
                flyingPrSeconds != null && flyingPrSeconds.isFinite() && flyingPrSeconds > 0 && flyingDistance != null)
}

data class ProfilePersonalBestDraft(
    val primaryEvent: SportDiscipline,
    val personalRecordText: String,
    val flyingDistance: FlyingDistance,
    val flyingPrText: String
) {
    val invalidPersonalRecord: Boolean
        get() = personalRecordText.isNotBlank() && parseProfileRecordTime(personalRecordText) == null
    val invalidFlyingPr: Boolean
        get() = flyingPrText.isNotBlank() && parseProfileRecordTime(flyingPrText, allowMinutes = false) == null
    val canSave: Boolean get() = !invalidPersonalRecord && !invalidFlyingPr

    /** Empty optional fields deliberately clear their previous values. */
    fun valuesOrNull(): ProfilePersonalBestValues? {
        if (!canSave) return null
        val flying = parseProfileRecordTime(flyingPrText, allowMinutes = false)
        return ProfilePersonalBestValues(primaryEvent, parseProfileRecordTime(personalRecordText),
            if (flying == null) null else flyingDistance, flying)
    }
}

/** Seconds or integral minutes:seconds, with optional decimal comma and trailing s.
 * A seconds component of 60+, NaN/Infinity, negative values and zero are invalid.
 */
internal fun parseProfileRecordTime(text: String, allowMinutes: Boolean = true): Double? {
    val normalized = text.trim().removeSuffix("s").removeSuffix("S").trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    val decimal = Regex("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")
    if (decimal.matches(normalized)) {
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    }
    if (!allowMinutes) return null
    val parts = normalized.split(':')
    if (parts.size != 2 || !Regex("[0-9]+").matches(parts[0]) || !decimal.matches(parts[1])) return null
    val minutes = parts[0].toDoubleOrNull() ?: return null
    val seconds = parts[1].toDoubleOrNull() ?: return null
    if (!minutes.isFinite() || !seconds.isFinite() || seconds >= 60) return null
    return (BigDecimal(parts[0]) * BigDecimal(60) + BigDecimal(parts[1]))
        .toDouble().takeIf { it.isFinite() && it > 0 }
}

/** No display rounding: opening and saving a PR must preserve its canonical Double. */
internal fun formatEditableProfileRecord(seconds: Double?): String =
    seconds?.takeIf { it.isFinite() && it > 0 }?.let {
        BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()
    }.orEmpty()
