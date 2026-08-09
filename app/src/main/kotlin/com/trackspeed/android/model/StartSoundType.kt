package com.trackspeed.android.model

import androidx.annotation.StringRes
import com.trackspeed.android.R

/**
 * Start signal sound options matching iOS StartSoundType.
 */
enum class StartSoundType(
    val rawValue: String,
    @StringRes val displayNameRes: Int,
    @StringRes val subtitleRes: Int,
    val isAvailable: Boolean
) {
    BEEP(
        rawValue = "beep",
        displayNameRes = R.string.start_sound_beep,
        subtitleRes = R.string.start_sound_beep_desc,
        isAvailable = true
    ),
    GUNSHOT(
        rawValue = "gunshot",
        displayNameRes = R.string.start_sound_gunshot,
        subtitleRes = R.string.start_sound_gunshot_desc,
        isAvailable = true
    ),
    WHISTLE(
        rawValue = "whistle",
        displayNameRes = R.string.start_sound_whistle,
        subtitleRes = R.string.start_sound_whistle_desc,
        isAvailable = false
    );

    companion object {
        val selectable: List<StartSoundType> = entries.filter { it.isAvailable }

        fun fromRawValue(value: String?): StartSoundType {
            val parsed = entries.firstOrNull { it.rawValue == value }
            return parsed?.takeIf { it.isAvailable } ?: BEEP
        }
    }
}
