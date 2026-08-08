package com.trackspeed.android.model

/**
 * Canonical start type enum matching the iOS StartType exactly.
 *
 * Each value has a [rawValue] used for persistence (Room, DataStore, navigation args)
 * and must match the iOS app's string identifiers for cross-platform compatibility.
 */
enum class StartType(
    val rawValue: String,
    val displayName: String,
    val shortName: String,
    val description: String,
    val isPro: Boolean = false
) {
    FLYING(
        rawValue = "flying",
        displayName = "Flying Start",
        shortName = "Flying",
        description = "Timer starts when you run past the first phone"
    ),
    TOUCH_RELEASE(
        rawValue = "touchRelease",
        displayName = "Touch Release",
        shortName = "Touch",
        description = "Hold the screen, then lift your finger to start"
    ),
    COUNTDOWN(
        rawValue = "countdown",
        displayName = "Countdown",
        shortName = "Countdown",
        description = "3, 2, 1, BEEP! Timer starts on beep",
        isPro = true
    ),
    VOICE_COMMAND(
        rawValue = "voiceCommand",
        displayName = "Voice Command",
        shortName = "Voice",
        description = "AI voice calls out commands, timer starts on GO!",
        isPro = true
    ),
    IN_FRAME(
        rawValue = "inFrame",
        displayName = "In-Frame Start",
        shortName = "In-Frame",
        description = "Timer starts when you run past the front camera"
    );

    val usesStartTrigger: Boolean
        get() = this == TOUCH_RELEASE || this == COUNTDOWN || this == VOICE_COMMAND

    val startTriggerRoleName: String
        get() = when (this) {
            TOUCH_RELEASE -> "Touch Start"
            COUNTDOWN -> "Countdown Start"
            VOICE_COMMAND -> "Voice Start"
            FLYING, IN_FRAME -> displayName
        }

    val startTriggerStatusText: String
        get() = when (this) {
            TOUCH_RELEASE -> "Touch start"
            COUNTDOWN -> "Countdown start"
            VOICE_COMMAND -> "Voice start"
            FLYING, IN_FRAME -> displayName
        }

    val startTriggerHint: String
        get() = when (this) {
            TOUCH_RELEASE -> "You'll trigger the start by releasing your finger"
            COUNTDOWN -> "You'll trigger the start with the countdown"
            VOICE_COMMAND -> "You'll trigger the start with voice commands"
            FLYING, IN_FRAME -> description
        }

    val requiresStartGate: Boolean
        get() = this == FLYING

    val usesFrontCamera: Boolean
        get() = this == IN_FRAME

    companion object {
        /**
         * Parse any legacy or current raw value into the canonical StartType.
         * Handles all historical values that may exist in Room DB, DataStore, or nav args.
         */
        fun fromRawValue(value: String): StartType = when (value.lowercase()) {
            "flying" -> FLYING
            "standing" -> FLYING
            "block" -> FLYING
            "touchrelease" -> TOUCH_RELEASE
            "touch" -> TOUCH_RELEASE
            "countdown" -> COUNTDOWN
            "voicecommand" -> VOICE_COMMAND
            "voice" -> VOICE_COMMAND
            "inframe" -> IN_FRAME
            else -> FLYING
        }
    }
}
