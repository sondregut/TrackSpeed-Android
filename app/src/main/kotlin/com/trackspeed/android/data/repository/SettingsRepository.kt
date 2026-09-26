package com.trackspeed.android.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.data.model.SportCategory
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.model.StartType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    private object Keys {
        val DEFAULT_DISTANCE = doublePreferencesKey("default_distance")
        val START_TYPE = stringPreferencesKey("start_type")
        val DETECTION_SENSITIVITY = floatPreferencesKey("detection_sensitivity")
        val SPEED_UNIT = stringPreferencesKey("speed_unit")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val HAS_SKIPPED_LOGIN = booleanPreferencesKey("hasSkippedLogin")
        val PREFERRED_FPS = intPreferencesKey("preferred_fps")
        val USER_ROLE = stringPreferencesKey("user_role")
        val PRIMARY_EVENT = stringPreferencesKey("primary_event")
        val PERSONAL_RECORD = doublePreferencesKey("personal_record")
        val FLYING_DISTANCE = stringPreferencesKey("flying_distance")
        val FLYING_PR = doublePreferencesKey("flying_pr")
        val PENDING_PROFILE_SYNC = booleanPreferencesKey("pending_profile_sync")
        val PENDING_FLYING_PR_SYNC = stringPreferencesKey("pending_flying_pr_sync")
        val GOAL_TIME = doublePreferencesKey("goal_time")
        val HAS_SEEN_SPIN_WHEEL = booleanPreferencesKey("has_seen_spin_wheel")
        val SAVE_CROSSING_FRAMES = booleanPreferencesKey("save_crossing_frames")
        val ENABLE_FRAME_SCRUBBING = booleanPreferencesKey("enable_frame_scrubbing")
        val DETECTION_DIAGNOSTICS_ENABLED = booleanPreferencesKey("detection_diagnostics_enabled")
        val DETECTION_REVIEW_AUTO_UPLOAD_ENABLED = booleanPreferencesKey("detection_review_auto_upload_enabled")
        val CAMERA_PERFORMANCE_DIAGNOSTICS_ENABLED = booleanPreferencesKey("camera_performance_diagnostics_enabled")
        val SHOW_SPEED_IN_RESULTS = booleanPreferencesKey("show_speed_in_results")
        val PRE_START_DELAY_MIN = floatPreferencesKey("pre_start_delay_min")
        val PRE_START_DELAY_MAX = floatPreferencesKey("pre_start_delay_max")
        val MARKS_SET_DELAY_MIN = floatPreferencesKey("marks_set_delay_min")
        val MARKS_SET_DELAY_MAX = floatPreferencesKey("marks_set_delay_max")
        val SET_GO_HOLD_MIN = floatPreferencesKey("set_go_hold_min")
        val SET_GO_HOLD_MAX = floatPreferencesKey("set_go_hold_max")
        val INCLUDE_READY_COMMAND = booleanPreferencesKey("include_ready_command")
        val CROSSING_BEEP_ENABLED = booleanPreferencesKey("crossing_beep_enabled")
        val CROSSING_FLASH_ENABLED = booleanPreferencesKey("crossing_flash_enabled")
        val ANNOUNCE_TIMES_ENABLED = booleanPreferencesKey("announce_times_enabled")
        val CONNECTION_METHOD = stringPreferencesKey("connection_method")
        val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val TEAM_NAME = stringPreferencesKey("team_name")
        val PROMO_CODE = stringPreferencesKey("promo_code")
        val REFERRAL_CODE = stringPreferencesKey("referral_code")
        val DISCOUNT_PAYWALL_SHOW_COUNT = intPreferencesKey("discountPaywallShowCount")
        val DISCOUNT_PAYWALL_LAST_SHOWN_AT_MILLIS = longPreferencesKey("discountPaywallLastShownAt")
        val DISCOUNT_OFFER_EXPIRES_AT_MILLIS = longPreferencesKey("discountOfferExpiresAt")
        val HAS_SHOWN_ABANDONED_TRANSACTION_RECOVERY =
            booleanPreferencesKey("hasShownAbandonedTransactionRecovery")
        val LAST_DISCOUNT_MILESTONE_FIRED = intPreferencesKey("lastDiscountMilestoneFired")
        val PENDING_DISCOUNT_MILESTONE = intPreferencesKey("pendingDiscountMilestone")
        val HAS_DISMISSED_STANDARD_PAYWALL = booleanPreferencesKey("hasDismissedStandardPaywall")
        val SPORT_CATEGORY = stringPreferencesKey("sportCategory")
        val PRESET_LAUNCH_COUNTS = stringPreferencesKey("presetLaunchCounts")
        val HAS_DISMISSED_FIRST_SESSION_TUTORIAL =
            booleanPreferencesKey("hasDismissedFirstSessionTutorial")
        val FORCE_SHOW_FIRST_SESSION_TUTORIAL =
            booleanPreferencesKey("forceShowFirstSessionTutorial")
        val HAS_DISMISSED_SECONDARY_PHONE_JOIN_TIP =
            booleanPreferencesKey("hasDismissedSecondaryPhoneJoinTip")

        // Profile
        val USER_NAME = stringPreferencesKey("user_name")
        val AVATAR_PHOTO_PATH = stringPreferencesKey("avatar_photo_path")

        // Notification preferences
        val TRY_PRO_REMINDER_ENABLED = booleanPreferencesKey("try_pro_reminder_enabled")
        val TRAINING_REMINDER_ENABLED = booleanPreferencesKey("training_reminder_enabled")
        val BILLING_ISSUE_REMINDER_ENABLED = booleanPreferencesKey("billing_issue_reminder_enabled")
        val PROMO_OFFER_REMINDER_ENABLED = booleanPreferencesKey("promo_offer_reminder_enabled")
        val RATING_PROMPT_ENABLED = booleanPreferencesKey("rating_prompt_enabled")
        val HAS_BEEN_ASKED_FOR_REVIEW = booleanPreferencesKey("has_been_asked_for_review")

        // Voice/Start preferences
        val START_MODE = stringPreferencesKey("start_mode")
        val START_SOUND_TYPE = stringPreferencesKey("start_sound_type")
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
        const val START_TYPE = "flying"
        const val SENSITIVITY = 0.5f
        const val SPEED_UNIT = "m/s"
        const val DARK_MODE = true
        const val ONBOARDING_COMPLETED = false
        const val PREFERRED_FPS = 30
        const val SAVE_CROSSING_FRAMES = false
        const val ENABLE_FRAME_SCRUBBING = false
        const val DETECTION_DIAGNOSTICS_ENABLED = true
        const val DETECTION_REVIEW_AUTO_UPLOAD_ENABLED = true
        const val CAMERA_PERFORMANCE_DIAGNOSTICS_ENABLED = false
        const val SHOW_SPEED_IN_RESULTS = true
        const val PRE_START_DELAY_MIN = 3.0f
        const val PRE_START_DELAY_MAX = 5.0f
        const val MARKS_SET_DELAY_MIN = 8.0f
        const val MARKS_SET_DELAY_MAX = 12.0f
        const val SET_GO_HOLD_MIN = 1.5f
        const val SET_GO_HOLD_MAX = 2.3f
        const val INCLUDE_READY_COMMAND = false
        const val CROSSING_BEEP_ENABLED = true
        const val CROSSING_FLASH_ENABLED = false
        const val ANNOUNCE_TIMES_ENABLED = true
        const val CONNECTION_METHOD = "auto"
        const val APPEARANCE_MODE = "midnight"
        const val TRY_PRO_REMINDER_ENABLED = true
        const val TRAINING_REMINDER_ENABLED = true
        const val BILLING_ISSUE_REMINDER_ENABLED = true
        const val PROMO_OFFER_REMINDER_ENABLED = true
        const val RATING_PROMPT_ENABLED = true
        const val START_MODE = "flying"
        const val START_SOUND_TYPE = "beep"
        const val VOICE_GENDER = "male"
        const val COUNTDOWN_SECONDS = 3
        const val APP_LANGUAGE = "system"
        const val VOICE_PROVIDER = "eleven_labs"
        const val ELEVEN_LABS_VOICE = "arnold"
    }

    // --- Flows ---

    val defaultDistance: Flow<Double> = preferences.map { prefs ->
        prefs[Keys.DEFAULT_DISTANCE] ?: Defaults.DISTANCE
    }

    val startType: Flow<String> = preferences.map { prefs ->
        val raw = prefs[Keys.START_MODE] ?: prefs[Keys.START_TYPE] ?: Defaults.START_TYPE
        StartType.fromRawValue(raw).rawValue
    }

    val detectionSensitivity: Flow<Float> = preferences.map { prefs ->
        prefs[Keys.DETECTION_SENSITIVITY] ?: Defaults.SENSITIVITY
    }

    val speedUnit: Flow<String> = preferences.map { prefs ->
        prefs[Keys.SPEED_UNIT] ?: Defaults.SPEED_UNIT
    }

    val darkMode: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.DARK_MODE] ?: Defaults.DARK_MODE
    }

    val onboardingCompleted: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: Defaults.ONBOARDING_COMPLETED
    }

    val hasSkippedLogin: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.HAS_SKIPPED_LOGIN] ?: false
    }

    val preferredFps: Flow<Int> = preferences.map {
        Defaults.PREFERRED_FPS
    }

    val userRole: Flow<String?> = preferences.map { prefs ->
        prefs[Keys.USER_ROLE]
    }

    val primaryEvent: Flow<String?> = preferences.map { prefs ->
        prefs[Keys.PRIMARY_EVENT]
    }

    val personalRecord: Flow<Double?> = preferences.map { prefs ->
        prefs[Keys.PERSONAL_RECORD]
    }

    val flyingDistance: Flow<String?> = preferences.map { prefs ->
        prefs[Keys.FLYING_DISTANCE]
    }

    val flyingPR: Flow<Double?> = preferences.map { prefs ->
        prefs[Keys.FLYING_PR]
    }

    val pendingProfileSync: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.PENDING_PROFILE_SYNC] ?: false
    }

    val pendingFlyingPrSync: Flow<String> = preferences.map { prefs ->
        prefs[Keys.PENDING_FLYING_PR_SYNC] ?: ""
    }

    val goalTime: Flow<Double?> = preferences.map { prefs ->
        prefs[Keys.GOAL_TIME]
    }

    val hasSeenSpinWheel: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.HAS_SEEN_SPIN_WHEEL] ?: false
    }

    val saveCrossingFrames: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.SAVE_CROSSING_FRAMES] ?: Defaults.SAVE_CROSSING_FRAMES
    }

    val enableFrameScrubbing: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.ENABLE_FRAME_SCRUBBING] ?: Defaults.ENABLE_FRAME_SCRUBBING
    }

    val detectionDiagnosticsEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.DETECTION_DIAGNOSTICS_ENABLED] ?: Defaults.DETECTION_DIAGNOSTICS_ENABLED
    }

    val detectionReviewAutoUploadEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.DETECTION_REVIEW_AUTO_UPLOAD_ENABLED] ?: Defaults.DETECTION_REVIEW_AUTO_UPLOAD_ENABLED
    }

    val showSpeedInResults: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.SHOW_SPEED_IN_RESULTS] ?: Defaults.SHOW_SPEED_IN_RESULTS
    }

    val preStartDelayMin: Flow<Float> = preferences.map { prefs ->
        prefs[Keys.PRE_START_DELAY_MIN] ?: Defaults.PRE_START_DELAY_MIN
    }

    val preStartDelayMax: Flow<Float> = preferences.map { prefs ->
        prefs[Keys.PRE_START_DELAY_MAX] ?: Defaults.PRE_START_DELAY_MAX
    }

    val marksSetDelayMin: Flow<Float> = preferences.map { prefs ->
        prefs[Keys.MARKS_SET_DELAY_MIN] ?: Defaults.MARKS_SET_DELAY_MIN
    }

    val marksSetDelayMax: Flow<Float> = preferences.map { prefs ->
        prefs[Keys.MARKS_SET_DELAY_MAX] ?: Defaults.MARKS_SET_DELAY_MAX
    }

    val setGoHoldMin: Flow<Float> = preferences.map { prefs ->
        prefs[Keys.SET_GO_HOLD_MIN] ?: Defaults.SET_GO_HOLD_MIN
    }

    val setGoHoldMax: Flow<Float> = preferences.map { prefs ->
        prefs[Keys.SET_GO_HOLD_MAX] ?: Defaults.SET_GO_HOLD_MAX
    }

    val includeReadyCommand: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.INCLUDE_READY_COMMAND] ?: Defaults.INCLUDE_READY_COMMAND
    }

    val crossingBeepEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.CROSSING_BEEP_ENABLED] ?: Defaults.CROSSING_BEEP_ENABLED
    }

    val crossingFlashEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.CROSSING_FLASH_ENABLED] ?: Defaults.CROSSING_FLASH_ENABLED
    }

    val announceTimesEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.ANNOUNCE_TIMES_ENABLED] ?: Defaults.ANNOUNCE_TIMES_ENABLED
    }

    val connectionMethod: Flow<String> = preferences.map { prefs ->
        prefs[Keys.CONNECTION_METHOD] ?: Defaults.CONNECTION_METHOD
    }

    val appearanceMode: Flow<String> = preferences.map { prefs ->
        prefs[Keys.APPEARANCE_MODE] ?: Defaults.APPEARANCE_MODE
    }

    val displayName: Flow<String?> = preferences.map { prefs ->
        prefs[Keys.DISPLAY_NAME]
    }

    val teamName: Flow<String?> = preferences.map { prefs ->
        prefs[Keys.TEAM_NAME]
    }

    val promoCode: Flow<String?> = preferences.map { prefs ->
        prefs[Keys.PROMO_CODE]
    }

    val referralCode: Flow<String?> = preferences.map { prefs ->
        prefs[Keys.REFERRAL_CODE]
    }

    val sportCategory: Flow<SportCategory?> = preferences.map { prefs ->
        prefs[Keys.SPORT_CATEGORY]?.let(::parseSportCategory)
            ?: prefs[Keys.PRIMARY_EVENT]?.let { SportDiscipline.fromRawValue(it)?.category }
    }

    val presetLaunchCounts: Flow<Map<String, Int>> = preferences.map { prefs ->
        decodePresetLaunchCounts(prefs[Keys.PRESET_LAUNCH_COUNTS])
    }

    val discountPaywallShowCount: Flow<Int> = preferences.map { prefs ->
        prefs[Keys.DISCOUNT_PAYWALL_SHOW_COUNT] ?: 0
    }

    val discountPaywallLastShownAtMillis: Flow<Long> = preferences.map { prefs ->
        prefs[Keys.DISCOUNT_PAYWALL_LAST_SHOWN_AT_MILLIS] ?: 0L
    }

    val discountOfferExpiresAtMillis: Flow<Long> = preferences.map { prefs ->
        prefs[Keys.DISCOUNT_OFFER_EXPIRES_AT_MILLIS] ?: 0L
    }

    val hasShownAbandonedTransactionRecovery: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.HAS_SHOWN_ABANDONED_TRANSACTION_RECOVERY] ?: false
    }

    val lastDiscountMilestoneFired: Flow<Int> = preferences.map { prefs ->
        prefs[Keys.LAST_DISCOUNT_MILESTONE_FIRED] ?: 0
    }

    val pendingDiscountMilestone: Flow<Int> = preferences.map { prefs ->
        prefs[Keys.PENDING_DISCOUNT_MILESTONE] ?: 0
    }

    val hasDismissedStandardPaywall: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.HAS_DISMISSED_STANDARD_PAYWALL] ?: false
    }

    val hasDismissedFirstSessionTutorial: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.HAS_DISMISSED_FIRST_SESSION_TUTORIAL] ?: false
    }

    val forceShowFirstSessionTutorial: Flow<Boolean> = preferences.map { prefs ->
        BuildConfig.DEBUG && (prefs[Keys.FORCE_SHOW_FIRST_SESSION_TUTORIAL] ?: false)
    }

    val hasDismissedSecondaryPhoneJoinTip: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.HAS_DISMISSED_SECONDARY_PHONE_JOIN_TIP] ?: false
    }

    // Profile
    val userName: Flow<String> = preferences.map { prefs ->
        prefs[Keys.USER_NAME] ?: ""
    }

    val avatarPhotoPath: Flow<String?> = preferences.map { prefs ->
        prefs[Keys.AVATAR_PHOTO_PATH]
    }

    // Notification preferences
    val tryProReminderEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.TRY_PRO_REMINDER_ENABLED] ?: Defaults.TRY_PRO_REMINDER_ENABLED
    }

    val trainingReminderEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.TRAINING_REMINDER_ENABLED] ?: Defaults.TRAINING_REMINDER_ENABLED
    }

    val billingIssueReminderEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.BILLING_ISSUE_REMINDER_ENABLED] ?: Defaults.BILLING_ISSUE_REMINDER_ENABLED
    }

    val promoOfferReminderEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.PROMO_OFFER_REMINDER_ENABLED] ?: Defaults.PROMO_OFFER_REMINDER_ENABLED
    }

    val ratingPromptEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.RATING_PROMPT_ENABLED] ?: Defaults.RATING_PROMPT_ENABLED
    }

    val hasBeenAskedForReview: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.HAS_BEEN_ASKED_FOR_REVIEW] ?: false
    }

    // Voice/Start preferences — startMode is an alias for startType for backward compat
    val startMode: Flow<String> = startType

    val startSoundType: Flow<String> = preferences.map { prefs ->
        com.trackspeed.android.model.StartSoundType.fromRawValue(
            prefs[Keys.START_SOUND_TYPE] ?: Defaults.START_SOUND_TYPE
        ).rawValue
    }

    val cameraPerformanceDiagnosticsEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[Keys.CAMERA_PERFORMANCE_DIAGNOSTICS_ENABLED] ?: Defaults.CAMERA_PERFORMANCE_DIAGNOSTICS_ENABLED
    }

    val voiceGender: Flow<String> = preferences.map { prefs ->
        prefs[Keys.VOICE_GENDER] ?: Defaults.VOICE_GENDER
    }

    val countdownSeconds: Flow<Int> = preferences.map { prefs ->
        prefs[Keys.COUNTDOWN_SECONDS] ?: Defaults.COUNTDOWN_SECONDS
    }

    // Language
    val appLanguage: Flow<String> = preferences.map { prefs ->
        prefs[Keys.APP_LANGUAGE] ?: Defaults.APP_LANGUAGE
    }

    // ElevenLabs voice
    val voiceProvider: Flow<String> = preferences.map { prefs ->
        prefs[Keys.VOICE_PROVIDER] ?: Defaults.VOICE_PROVIDER
    }

    val elevenLabsVoice: Flow<String> = preferences.map { prefs ->
        prefs[Keys.ELEVEN_LABS_VOICE] ?: Defaults.ELEVEN_LABS_VOICE
    }

    // --- Update functions ---

    suspend fun setDefaultDistance(distance: Double) {
        dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_DISTANCE] = distance
        }
    }

    suspend fun setStartType(startType: String) {
        val normalized = StartType.fromRawValue(startType).rawValue
        dataStore.edit { prefs ->
            prefs[Keys.START_MODE] = normalized
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

    suspend fun setHasSkippedLogin(skipped: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_SKIPPED_LOGIN] = skipped
        }
    }

    suspend fun setPreferredFps(fps: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.PREFERRED_FPS] = Defaults.PREFERRED_FPS
        }
    }

    suspend fun getPreferredFpsOnce(): Int {
        return Defaults.PREFERRED_FPS
    }

    suspend fun setUserRole(role: String) {
        dataStore.edit { it[Keys.USER_ROLE] = role }
    }

    suspend fun setPrimaryEvent(event: String) {
        dataStore.edit {
            it[Keys.PRIMARY_EVENT] = event
            SportDiscipline.fromRawValue(event)?.category?.let { category ->
                it[Keys.SPORT_CATEGORY] = category.name
            }
        }
    }

    suspend fun setSportCategory(category: SportCategory?) {
        dataStore.edit {
            if (category == null) {
                it.remove(Keys.SPORT_CATEGORY)
            } else {
                it[Keys.SPORT_CATEGORY] = category.name
            }
        }
    }

    suspend fun recordPresetLaunch(presetId: String) {
        if (presetId.isBlank()) return
        dataStore.edit { prefs ->
            val counts = decodePresetLaunchCounts(prefs[Keys.PRESET_LAUNCH_COUNTS]).toMutableMap()
            counts[presetId] = (counts[presetId] ?: 0) + 1
            prefs[Keys.PRESET_LAUNCH_COUNTS] = encodePresetLaunchCounts(counts)
        }
    }

    suspend fun setDiscountPaywallShowCount(count: Int) {
        dataStore.edit { it[Keys.DISCOUNT_PAYWALL_SHOW_COUNT] = count.coerceAtLeast(0) }
    }

    suspend fun setDiscountPaywallLastShownAtMillis(timestampMillis: Long) {
        dataStore.edit { it[Keys.DISCOUNT_PAYWALL_LAST_SHOWN_AT_MILLIS] = timestampMillis.coerceAtLeast(0L) }
    }

    suspend fun setDiscountOfferExpiresAtMillis(timestampMillis: Long) {
        dataStore.edit { it[Keys.DISCOUNT_OFFER_EXPIRES_AT_MILLIS] = timestampMillis.coerceAtLeast(0L) }
    }

    suspend fun setHasShownAbandonedTransactionRecovery(shown: Boolean) {
        dataStore.edit { it[Keys.HAS_SHOWN_ABANDONED_TRANSACTION_RECOVERY] = shown }
    }

    suspend fun setLastDiscountMilestoneFired(milestone: Int) {
        dataStore.edit { it[Keys.LAST_DISCOUNT_MILESTONE_FIRED] = milestone.coerceAtLeast(0) }
    }

    suspend fun setPendingDiscountMilestone(milestone: Int) {
        dataStore.edit { it[Keys.PENDING_DISCOUNT_MILESTONE] = milestone.coerceAtLeast(0) }
    }

    suspend fun setHasDismissedStandardPaywall(dismissed: Boolean) {
        dataStore.edit { it[Keys.HAS_DISMISSED_STANDARD_PAYWALL] = dismissed }
    }

    suspend fun setHasDismissedFirstSessionTutorial(dismissed: Boolean) {
        dataStore.edit { it[Keys.HAS_DISMISSED_FIRST_SESSION_TUTORIAL] = dismissed }
    }

    suspend fun setForceShowFirstSessionTutorial(force: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.FORCE_SHOW_FIRST_SESSION_TUTORIAL] = BuildConfig.DEBUG && force
        }
    }

    suspend fun setHasDismissedSecondaryPhoneJoinTip(dismissed: Boolean) {
        dataStore.edit { it[Keys.HAS_DISMISSED_SECONDARY_PHONE_JOIN_TIP] = dismissed }
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

    suspend fun setPendingProfileSync(pending: Boolean) {
        dataStore.edit {
            if (pending) {
                it[Keys.PENDING_PROFILE_SYNC] = true
            } else {
                it.remove(Keys.PENDING_PROFILE_SYNC)
            }
        }
    }

    suspend fun setPendingFlyingPrSync(value: String) {
        dataStore.edit {
            if (value.isBlank()) {
                it.remove(Keys.PENDING_FLYING_PR_SYNC)
            } else {
                it[Keys.PENDING_FLYING_PR_SYNC] = value
            }
        }
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

    suspend fun setDetectionDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DETECTION_DIAGNOSTICS_ENABLED] = enabled }
    }

    suspend fun setDetectionReviewAutoUploadEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DETECTION_REVIEW_AUTO_UPLOAD_ENABLED] = enabled }
    }

    suspend fun setShowSpeedInResults(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_SPEED_IN_RESULTS] = enabled }
    }

    suspend fun setPreStartDelayMin(value: Float) {
        dataStore.edit { it[Keys.PRE_START_DELAY_MIN] = value }
    }

    suspend fun setPreStartDelayMax(value: Float) {
        dataStore.edit { it[Keys.PRE_START_DELAY_MAX] = value }
    }

    suspend fun setStartSoundType(rawValue: String) {
        dataStore.edit {
            it[Keys.START_SOUND_TYPE] =
                com.trackspeed.android.model.StartSoundType.fromRawValue(rawValue).rawValue
        }
    }

    suspend fun setIncludeReadyCommand(enabled: Boolean) {
        dataStore.edit { it[Keys.INCLUDE_READY_COMMAND] = enabled }
    }

    suspend fun setCameraPerformanceDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CAMERA_PERFORMANCE_DIAGNOSTICS_ENABLED] = enabled }
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

    suspend fun setBillingIssueReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BILLING_ISSUE_REMINDER_ENABLED] = enabled }
    }

    suspend fun setPromoOfferReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.PROMO_OFFER_REMINDER_ENABLED] = enabled }
    }

    suspend fun setRatingPromptEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.RATING_PROMPT_ENABLED] = enabled }
    }

    suspend fun setHasBeenAskedForReview(asked: Boolean) {
        dataStore.edit { it[Keys.HAS_BEEN_ASKED_FOR_REVIEW] = asked }
    }

    // Voice/Start setters
    suspend fun setVoiceGender(gender: String) {
        dataStore.edit { it[Keys.VOICE_GENDER] = gender }
    }

    suspend fun setCountdownSeconds(seconds: Int) {
        dataStore.edit { it[Keys.COUNTDOWN_SECONDS] = seconds.coerceIn(3, 5) }
    }

    suspend fun getStartModeOnce(): String {
        return startType.first()
    }

    suspend fun getVoiceGenderOnce(): String {
        return preferences.first()[Keys.VOICE_GENDER] ?: Defaults.VOICE_GENDER
    }

    suspend fun getCountdownSecondsOnce(): Int {
        return preferences.first()[Keys.COUNTDOWN_SECONDS] ?: Defaults.COUNTDOWN_SECONDS
    }

    // Language setter
    suspend fun setAppLanguage(language: String) {
        dataStore.edit { it[Keys.APP_LANGUAGE] = language }
    }

    suspend fun getAppLanguageOnce(): String {
        return preferences.first()[Keys.APP_LANGUAGE] ?: Defaults.APP_LANGUAGE
    }

    // ElevenLabs voice setters
    suspend fun setVoiceProvider(provider: String) {
        dataStore.edit { it[Keys.VOICE_PROVIDER] = provider }
    }

    suspend fun setElevenLabsVoice(voice: String) {
        dataStore.edit { it[Keys.ELEVEN_LABS_VOICE] = voice }
    }

    suspend fun getVoiceProviderOnce(): String {
        return preferences.first()[Keys.VOICE_PROVIDER] ?: Defaults.VOICE_PROVIDER
    }

    suspend fun getElevenLabsVoiceOnce(): String {
        return preferences.first()[Keys.ELEVEN_LABS_VOICE] ?: Defaults.ELEVEN_LABS_VOICE
    }

    /**
     * Reset every user-scoped setting so the next account that signs in
     * starts clean. Mirrors the teardown iOS `AuthService.signOut` performs
     * on `UserSettings`. Device-scoped fields (locale, FPS, theme, default
     * distance) are intentionally preserved.
     */
    suspend fun clearUserScopedFields() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.USER_NAME)
            prefs.remove(Keys.AVATAR_PHOTO_PATH)
            prefs.remove(Keys.DISPLAY_NAME)
            prefs.remove(Keys.TEAM_NAME)
            prefs.remove(Keys.PROMO_CODE)
            prefs.remove(Keys.REFERRAL_CODE)
            prefs.remove(Keys.DISCOUNT_PAYWALL_SHOW_COUNT)
            prefs.remove(Keys.DISCOUNT_PAYWALL_LAST_SHOWN_AT_MILLIS)
            prefs.remove(Keys.DISCOUNT_OFFER_EXPIRES_AT_MILLIS)
            prefs.remove(Keys.HAS_SHOWN_ABANDONED_TRANSACTION_RECOVERY)
            prefs.remove(Keys.LAST_DISCOUNT_MILESTONE_FIRED)
            prefs.remove(Keys.PENDING_DISCOUNT_MILESTONE)
            prefs.remove(Keys.HAS_DISMISSED_STANDARD_PAYWALL)
            prefs.remove(Keys.SPORT_CATEGORY)
            prefs.remove(Keys.PRESET_LAUNCH_COUNTS)
            prefs.remove(Keys.HAS_DISMISSED_FIRST_SESSION_TUTORIAL)
            prefs.remove(Keys.HAS_DISMISSED_SECONDARY_PHONE_JOIN_TIP)
            prefs.remove(Keys.PRIMARY_EVENT)
            prefs.remove(Keys.PERSONAL_RECORD)
            prefs.remove(Keys.FLYING_DISTANCE)
            prefs.remove(Keys.FLYING_PR)
            prefs.remove(Keys.PENDING_PROFILE_SYNC)
            prefs.remove(Keys.PENDING_FLYING_PR_SYNC)
            prefs.remove(Keys.GOAL_TIME)
            prefs.remove(Keys.USER_ROLE)
            prefs.remove(Keys.HAS_BEEN_ASKED_FOR_REVIEW)
            prefs.remove(Keys.HAS_SKIPPED_LOGIN)
            // Reset onboarding so the next user is taken back through the flow.
            prefs[Keys.ONBOARDING_COMPLETED] = false
            prefs.remove(Keys.HAS_SEEN_SPIN_WHEEL)
        }
    }

    private fun parseSportCategory(raw: String): SportCategory? {
        return SportCategory.entries.firstOrNull { category ->
            category.name == raw || category.displayName == raw
        }
    }

    private fun decodePresetLaunchCounts(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    put(key, json.optInt(key, 0))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodePresetLaunchCounts(counts: Map<String, Int>): String {
        val json = JSONObject()
        counts.forEach { (key, value) ->
            json.put(key, value)
        }
        return json.toString()
    }
}
