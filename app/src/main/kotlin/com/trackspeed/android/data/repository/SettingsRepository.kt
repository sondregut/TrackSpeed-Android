package com.trackspeed.android.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    private object Keys {
        val DEFAULT_DISTANCE = doublePreferencesKey("default_distance")
        val START_TYPE = stringPreferencesKey("start_type")
        val DETECTION_SENSITIVITY = floatPreferencesKey("detection_sensitivity")
        val SPEED_UNIT = stringPreferencesKey("speed_unit")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val PREFERRED_FPS = intPreferencesKey("preferred_fps")
        val USER_ROLE = stringPreferencesKey("user_role")
        val PRIMARY_EVENT = stringPreferencesKey("primary_event")
        val PERSONAL_RECORD = doublePreferencesKey("personal_record")
        val FLYING_DISTANCE = stringPreferencesKey("flying_distance")
        val FLYING_PR = doublePreferencesKey("flying_pr")
        val GOAL_TIME = doublePreferencesKey("goal_time")
        val HAS_SEEN_SPIN_WHEEL = booleanPreferencesKey("has_seen_spin_wheel")
        val SAVE_CROSSING_FRAMES = booleanPreferencesKey("save_crossing_frames")
        val ENABLE_FRAME_SCRUBBING = booleanPreferencesKey("enable_frame_scrubbing")
        val MARKS_SET_DELAY_MIN = floatPreferencesKey("marks_set_delay_min")
        val MARKS_SET_DELAY_MAX = floatPreferencesKey("marks_set_delay_max")
        val SET_GO_HOLD_MIN = floatPreferencesKey("set_go_hold_min")
        val SET_GO_HOLD_MAX = floatPreferencesKey("set_go_hold_max")
        val CROSSING_BEEP_ENABLED = booleanPreferencesKey("crossing_beep_enabled")
        val CROSSING_FLASH_ENABLED = booleanPreferencesKey("crossing_flash_enabled")
        val ANNOUNCE_TIMES_ENABLED = booleanPreferencesKey("announce_times_enabled")
        val CONNECTION_METHOD = stringPreferencesKey("connection_method")
        val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val TEAM_NAME = stringPreferencesKey("team_name")
        val PROMO_CODE = stringPreferencesKey("promo_code")
        val REFERRAL_CODE = stringPreferencesKey("referral_code")

        // Profile
        val USER_NAME = stringPreferencesKey("user_name")
        val AVATAR_PHOTO_PATH = stringPreferencesKey("avatar_photo_path")

        // Notification preferences
        val TRY_PRO_REMINDER_ENABLED = booleanPreferencesKey("try_pro_reminder_enabled")
        val TRAINING_REMINDER_ENABLED = booleanPreferencesKey("training_reminder_enabled")
        val RATING_PROMPT_ENABLED = booleanPreferencesKey("rating_prompt_enabled")

        // Voice/Start preferences
        val START_MODE = stringPreferencesKey("start_mode")
        val VOICE_GENDER = stringPreferencesKey("voice_gender")
        val COUNTDOWN_SECONDS = intPreferencesKey("countdown_seconds")

        // Language
        val APP_LANGUAGE = stringPreferencesKey("app_language")

        // ElevenLabs voice
        val VOICE_PROVIDER = stringPreferencesKey("voice_provider")
        val ELEVEN_LABS_VOICE = stringPreferencesKey("eleven_labs_voice")
    }

    object Defaults {
        const val DISTANCE = 60.0
        const val START_TYPE = "standing"
        const val SENSITIVITY = 0.5f
        const val SPEED_UNIT = "m/s"
        const val DARK_MODE = true
        const val ONBOARDING_COMPLETED = false
        const val PREFERRED_FPS = 120
        const val SAVE_CROSSING_FRAMES = false
        const val ENABLE_FRAME_SCRUBBING = false
        const val MARKS_SET_DELAY_MIN = 5.0f
        const val MARKS_SET_DELAY_MAX = 10.0f
        const val SET_GO_HOLD_MIN = 1.5f
        const val SET_GO_HOLD_MAX = 2.3f
        const val CROSSING_BEEP_ENABLED = true
        const val CROSSING_FLASH_ENABLED = true
        const val ANNOUNCE_TIMES_ENABLED = false
        const val CONNECTION_METHOD = "auto"
        const val APPEARANCE_MODE = "system"
        const val TRY_PRO_REMINDER_ENABLED = true
        const val TRAINING_REMINDER_ENABLED = true
        const val RATING_PROMPT_ENABLED = true
        const val START_MODE = "flying"
        const val VOICE_GENDER = "male"
        const val COUNTDOWN_SECONDS = 3
        const val APP_LANGUAGE = "system"
        const val VOICE_PROVIDER = "eleven_labs"
        const val ELEVEN_LABS_VOICE = "arnold"
    }

    // --- Flows ---

    val defaultDistance: Flow<Double> = dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_DISTANCE] ?: Defaults.DISTANCE
    }

    val startType: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.START_TYPE] ?: Defaults.START_TYPE
    }

    val detectionSensitivity: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.DETECTION_SENSITIVITY] ?: Defaults.SENSITIVITY
    }

    val speedUnit: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SPEED_UNIT] ?: Defaults.SPEED_UNIT
    }

    val darkMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DARK_MODE] ?: Defaults.DARK_MODE
    }

    val onboardingCompleted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: Defaults.ONBOARDING_COMPLETED
    }

    val preferredFps: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.PREFERRED_FPS] ?: Defaults.PREFERRED_FPS
    }

    val userRole: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.USER_ROLE]
    }

    val primaryEvent: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.PRIMARY_EVENT]
    }

    val personalRecord: Flow<Double?> = dataStore.data.map { prefs ->
        prefs[Keys.PERSONAL_RECORD]
    }

    val flyingDistance: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.FLYING_DISTANCE]
    }

    val flyingPR: Flow<Double?> = dataStore.data.map { prefs ->
        prefs[Keys.FLYING_PR]
    }

    val goalTime: Flow<Double?> = dataStore.data.map { prefs ->
        prefs[Keys.GOAL_TIME]
    }

    val hasSeenSpinWheel: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.HAS_SEEN_SPIN_WHEEL] ?: false
    }

    val saveCrossingFrames: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SAVE_CROSSING_FRAMES] ?: Defaults.SAVE_CROSSING_FRAMES
    }

    val enableFrameScrubbing: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ENABLE_FRAME_SCRUBBING] ?: Defaults.ENABLE_FRAME_SCRUBBING
    }

    val marksSetDelayMin: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.MARKS_SET_DELAY_MIN] ?: Defaults.MARKS_SET_DELAY_MIN
    }

    val marksSetDelayMax: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.MARKS_SET_DELAY_MAX] ?: Defaults.MARKS_SET_DELAY_MAX
    }

    val setGoHoldMin: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.SET_GO_HOLD_MIN] ?: Defaults.SET_GO_HOLD_MIN
    }

    val setGoHoldMax: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.SET_GO_HOLD_MAX] ?: Defaults.SET_GO_HOLD_MAX
    }

    val crossingBeepEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.CROSSING_BEEP_ENABLED] ?: Defaults.CROSSING_BEEP_ENABLED
    }

    val crossingFlashEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.CROSSING_FLASH_ENABLED] ?: Defaults.CROSSING_FLASH_ENABLED
    }

    val announceTimesEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ANNOUNCE_TIMES_ENABLED] ?: Defaults.ANNOUNCE_TIMES_ENABLED
    }

    val connectionMethod: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.CONNECTION_METHOD] ?: Defaults.CONNECTION_METHOD
    }

    val appearanceMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.APPEARANCE_MODE] ?: Defaults.APPEARANCE_MODE
    }

    val displayName: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.DISPLAY_NAME]
    }

    val teamName: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.TEAM_NAME]
    }

    val promoCode: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.PROMO_CODE]
    }

    val referralCode: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.REFERRAL_CODE]
    }

    // Profile
    val userName: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.USER_NAME] ?: ""
    }

    val avatarPhotoPath: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.AVATAR_PHOTO_PATH]
    }

    // Notification preferences
    val tryProReminderEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.TRY_PRO_REMINDER_ENABLED] ?: Defaults.TRY_PRO_REMINDER_ENABLED
    }

    val trainingReminderEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.TRAINING_REMINDER_ENABLED] ?: Defaults.TRAINING_REMINDER_ENABLED
    }

    val ratingPromptEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.RATING_PROMPT_ENABLED] ?: Defaults.RATING_PROMPT_ENABLED
    }

    // Voice/Start preferences
    val startMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.START_MODE] ?: Defaults.START_MODE
    }

    val voiceGender: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_GENDER] ?: Defaults.VOICE_GENDER
    }

    val countdownSeconds: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.COUNTDOWN_SECONDS] ?: Defaults.COUNTDOWN_SECONDS
    }

    // Language
    val appLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.APP_LANGUAGE] ?: Defaults.APP_LANGUAGE
    }

    // ElevenLabs voice
    val voiceProvider: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.VOICE_PROVIDER] ?: Defaults.VOICE_PROVIDER
    }

    val elevenLabsVoice: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.ELEVEN_LABS_VOICE] ?: Defaults.ELEVEN_LABS_VOICE
    }

    // --- Update functions ---

    suspend fun setDefaultDistance(distance: Double) {
        dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_DISTANCE] = distance
        }
    }

    suspend fun setStartType(startType: String) {
        dataStore.edit { prefs ->
            prefs[Keys.START_TYPE] = startType
        }
    }

    suspend fun setDetectionSensitivity(sensitivity: Float) {
        dataStore.edit { prefs ->
            prefs[Keys.DETECTION_SENSITIVITY] = sensitivity
        }
    }

    suspend fun setSpeedUnit(unit: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SPEED_UNIT] = unit
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DARK_MODE] = enabled
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setPreferredFps(fps: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.PREFERRED_FPS] = fps
        }
    }

    suspend fun getPreferredFpsOnce(): Int {
        return dataStore.data.first()[Keys.PREFERRED_FPS] ?: Defaults.PREFERRED_FPS
    }

    suspend fun setUserRole(role: String) {
        dataStore.edit { it[Keys.USER_ROLE] = role }
    }

    suspend fun setPrimaryEvent(event: String) {
        dataStore.edit { it[Keys.PRIMARY_EVENT] = event }
    }

    suspend fun setPersonalRecord(record: Double) {
        dataStore.edit { it[Keys.PERSONAL_RECORD] = record }
    }

    suspend fun setFlyingDistance(distance: String) {
        dataStore.edit { it[Keys.FLYING_DISTANCE] = distance }
    }

    suspend fun setFlyingPR(pr: Double) {
        dataStore.edit { it[Keys.FLYING_PR] = pr }
    }

    suspend fun setGoalTime(time: Double) {
        dataStore.edit { it[Keys.GOAL_TIME] = time }
    }

    suspend fun setHasSeenSpinWheel(seen: Boolean) {
        dataStore.edit { it[Keys.HAS_SEEN_SPIN_WHEEL] = seen }
    }

    suspend fun setSaveCrossingFrames(enabled: Boolean) {
        dataStore.edit { it[Keys.SAVE_CROSSING_FRAMES] = enabled }
    }

    suspend fun setEnableFrameScrubbing(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLE_FRAME_SCRUBBING] = enabled }
    }

    suspend fun setMarksSetDelayMin(value: Float) {
        dataStore.edit { it[Keys.MARKS_SET_DELAY_MIN] = value }
    }

    suspend fun setMarksSetDelayMax(value: Float) {
        dataStore.edit { it[Keys.MARKS_SET_DELAY_MAX] = value }
    }

    suspend fun setSetGoHoldMin(value: Float) {
        dataStore.edit { it[Keys.SET_GO_HOLD_MIN] = value }
    }

    suspend fun setSetGoHoldMax(value: Float) {
        dataStore.edit { it[Keys.SET_GO_HOLD_MAX] = value }
    }

    suspend fun setCrossingBeepEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSING_BEEP_ENABLED] = enabled }
    }

    suspend fun setCrossingFlashEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSING_FLASH_ENABLED] = enabled }
    }

    suspend fun setAnnounceTimesEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ANNOUNCE_TIMES_ENABLED] = enabled }
    }

    suspend fun setConnectionMethod(method: String) {
        dataStore.edit { it[Keys.CONNECTION_METHOD] = method }
    }

    suspend fun setAppearanceMode(mode: String) {
        dataStore.edit { it[Keys.APPEARANCE_MODE] = mode }
    }

    suspend fun setDisplayName(name: String) {
        dataStore.edit { it[Keys.DISPLAY_NAME] = name }
    }

    suspend fun setTeamName(team: String) {
        dataStore.edit { it[Keys.TEAM_NAME] = team }
    }

    suspend fun setPromoCode(code: String) {
        dataStore.edit { it[Keys.PROMO_CODE] = code }
    }

    suspend fun setReferralCode(code: String) {
        dataStore.edit { it[Keys.REFERRAL_CODE] = code }
    }

    // Profile setters
    suspend fun setUserName(name: String) {
        dataStore.edit { it[Keys.USER_NAME] = name }
    }

    suspend fun setAvatarPhotoPath(path: String?) {
        dataStore.edit { prefs ->
            if (path != null) {
                prefs[Keys.AVATAR_PHOTO_PATH] = path
            } else {
                prefs.remove(Keys.AVATAR_PHOTO_PATH)
            }
        }
    }

    // Notification setters
    suspend fun setTryProReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TRY_PRO_REMINDER_ENABLED] = enabled }
    }

    suspend fun setTrainingReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TRAINING_REMINDER_ENABLED] = enabled }
    }

    suspend fun setRatingPromptEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.RATING_PROMPT_ENABLED] = enabled }
    }

    // Voice/Start setters
    suspend fun setStartMode(mode: String) {
        dataStore.edit { it[Keys.START_MODE] = mode }
    }

    suspend fun setVoiceGender(gender: String) {
        dataStore.edit { it[Keys.VOICE_GENDER] = gender }
    }

    suspend fun setCountdownSeconds(seconds: Int) {
        dataStore.edit { it[Keys.COUNTDOWN_SECONDS] = seconds.coerceIn(3, 5) }
    }

    suspend fun getStartModeOnce(): String {
        return dataStore.data.first()[Keys.START_MODE] ?: Defaults.START_MODE
    }

    suspend fun getVoiceGenderOnce(): String {
        return dataStore.data.first()[Keys.VOICE_GENDER] ?: Defaults.VOICE_GENDER
    }

    suspend fun getCountdownSecondsOnce(): Int {
        return dataStore.data.first()[Keys.COUNTDOWN_SECONDS] ?: Defaults.COUNTDOWN_SECONDS
    }

    // Language setter
    suspend fun setAppLanguage(language: String) {
        dataStore.edit { it[Keys.APP_LANGUAGE] = language }
    }

    suspend fun getAppLanguageOnce(): String {
        return dataStore.data.first()[Keys.APP_LANGUAGE] ?: Defaults.APP_LANGUAGE
    }

    // ElevenLabs voice setters
    suspend fun setVoiceProvider(provider: String) {
        dataStore.edit { it[Keys.VOICE_PROVIDER] = provider }
    }

    suspend fun setElevenLabsVoice(voice: String) {
        dataStore.edit { it[Keys.ELEVEN_LABS_VOICE] = voice }
    }

    suspend fun getVoiceProviderOnce(): String {
        return dataStore.data.first()[Keys.VOICE_PROVIDER] ?: Defaults.VOICE_PROVIDER
    }

    suspend fun getElevenLabsVoiceOnce(): String {
        return dataStore.data.first()[Keys.ELEVEN_LABS_VOICE] ?: Defaults.ELEVEN_LABS_VOICE
    }
}
