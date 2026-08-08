package com.trackspeed.android.model

/**
 * Start signal sound options matching iOS StartSoundType.
 */
enum class StartSoundType(
    val rawValue: String,
    val displayName: String,
    val subtitle: String,
    val isAvailable: Boolean
) {
    BEEP(
        rawValue = "beep",
        displayName = "Beep",
        subtitle = "Simple electronic beep",
        isAvailable = true
    ),
    GUNSHOT(
        rawValue = "gunshot",
        displayName = "Gunshot",
        subtitle = "Realistic starting pistol",
        isAvailable = true
    ),
    WHISTLE(
        rawValue = "whistle",
        displayName = "Whistle",
        subtitle = "Coach whistle",
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
