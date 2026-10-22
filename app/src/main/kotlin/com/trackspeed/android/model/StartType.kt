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
        description = "Run through the start phone's camera gate to start"
    ),
    TOUCH_RELEASE(
        rawValue = "touchRelease",
        displayName = "Touch Release",
        shortName = "Touch",
        description = "Hold the start phone's screen, then lift your finger to start"
    ),
    COUNTDOWN(
        rawValue = "countdown",
        displayName = "Countdown",
        shortName = "Countdown",
        description = "Starts automatically on the beep after 3, 2, 1",
        isPro = true
    ),
    VOICE_COMMAND(
        rawValue = "voiceCommand",
        displayName = "Voice Command",
        shortName = "Voice",
        description = "Spoken start commands; the timer starts on GO",
        isPro = true
    ),
    IN_FRAME(
        rawValue = "inFrame",
        displayName = "In-Frame Start",
        shortName = "In-Frame",
        description = "Hold still in front of the camera, then run to start from your first movement"
    ),
    MOTION_START("motionStart", "Motion Start", "Motion", "First movement from the start phone"),
    REACTION_START("reactionStart", "Reaction Start", "Reaction", "Reaction to the start phone’s cue");

    /** Reaction needs a measured audible-cue onset contract; remote reaction starts remain supported. */
    val supportsLocalStart: Boolean get() = this != REACTION_START

    val usesPoseStart: Boolean get() = this == MOTION_START || this == IN_FRAME

    val usesStartTrigger: Boolean
        get() = this == TOUCH_RELEASE || this == COUNTDOWN || this == VOICE_COMMAND || usesPoseStart || !supportsLocalStart

    val startTriggerRoleName: String
        get() = when (this) {
            TOUCH_RELEASE -> "Touch Start"
            COUNTDOWN -> "Countdown Start"
            VOICE_COMMAND -> "Voice Start"
            FLYING, IN_FRAME, MOTION_START, REACTION_START -> displayName
        }

    val startTriggerStatusText: String
        get() = when (this) {
            TOUCH_RELEASE -> "Touch start"
            COUNTDOWN -> "Countdown start"
            VOICE_COMMAND -> "Voice start"
            FLYING, IN_FRAME, MOTION_START, REACTION_START -> displayName
        }

    val startTriggerHint: String
        get() = when (this) {
            TOUCH_RELEASE -> "You'll trigger the start by releasing your finger"
            COUNTDOWN -> "You'll trigger the start with the countdown"
            VOICE_COMMAND -> "You'll trigger the start with voice commands"
            FLYING, IN_FRAME, MOTION_START, REACTION_START -> description
        }

    val requiresStartGate: Boolean
        get() = this == FLYING

    val usesFrontCamera: Boolean
        get() = usesPoseStart || this == REACTION_START

    /** Trigger-based modes bypass the camera only on the assigned start gate. */
    fun requiresCameraAtGate(isStartGate: Boolean): Boolean {
        return !isStartGate || usesPoseStart || !usesStartTrigger
    }

    fun shouldUseFrontCameraAtGate(isStartGate: Boolean): Boolean {
        return usesFrontCamera && isStartGate
    }

    companion object {
        val localStartTypes: List<StartType> get() = entries.filter { it.supportsLocalStart }
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
            "motionstart" -> MOTION_START
            "reactionstart" -> REACTION_START
            else -> FLYING
        }
    }
}
