package com.trackspeed.android.analytics

import android.util.Log
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.data.model.SportCategory
import com.trackspeed.android.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class AnalyticsEvent(val rawValue: String) {
    ONBOARDING_STEP_VIEWED("onboarding_step_viewed"),
    ONBOARDING_STEP_COMPLETED("onboarding_step_completed"),
    ONBOARDING_BACK_PRESSED("onboarding_back_pressed"),
    ONBOARDING_SKIPPED("onboarding_skipped"),
    ONBOARDING_COMPLETED("onboarding_completed"),
    AUTH_METHOD_SELECTED("auth_method_selected"),
    AUTH_COMPLETED("auth_completed"),
    AUTH_FAILED("auth_failed"),
    PAYWALL_VIEWED("paywall_viewed"),
    PAYWALL_PURCHASE_TAPPED("paywall_purchase_tapped"),
    PAYWALL_PURCHASE_COMPLETED("paywall_purchase_completed"),
    PAYWALL_PURCHASE_CANCELLED("paywall_purchase_cancelled"),
    PAYWALL_PURCHASE_FAILED("paywall_purchase_failed"),
    PAYWALL_JOIN_SESSION_TAPPED("paywall_join_session_tapped"),
    PAYWALL_SKIPPED("paywall_skipped"),
    PAYWALL_RESTORE_TAPPED("paywall_restore_tapped"),
    PAYWALL_PLAN_SELECTED("paywall_plan_selected"),
    PAYWALL_OFFERINGS_UNAVAILABLE("paywall_offerings_unavailable"),
    DISCOUNT_PAYWALL_SHOWN("discount_paywall_shown"),
    DISCOUNT_PAYWALL_DISMISSED("discount_paywall_dismissed"),
    DISCOUNT_PAYWALL_PURCHASED("discount_paywall_purchased"),
    NOTIFICATION_PERMISSION_RESULT("notification_permission_result"),
    TRACKING_AUTHORIZATION_RESULT("tracking_authorization_result"),
    SESSION_CREATED("session_created"),
    SESSION_COMPLETED("session_completed"),
    FEATURE_USED("feature_used"),
    FIRST_SESSION_TUTORIAL_SHOWN("first_session_tutorial_shown"),
    FIRST_SESSION_TUTORIAL_STARTED("first_session_tutorial_started"),
    FIRST_SESSION_TUTORIAL_DISMISSED("first_session_tutorial_dismissed"),
    SECONDARY_PHONE_JOIN_TIP_SHOWN("secondary_phone_join_tip_shown"),
    SECONDARY_PHONE_JOIN_TIP_DISMISSED("secondary_phone_join_tip_dismissed"),
    INVITE_CARD_VIEWED("invite_card_viewed"),
    INVITE_CARD_TAPPED("invite_card_tapped"),
    INVITE_CARD_DISMISSED("invite_card_dismissed"),
    REFERRAL_BONUS_EARNED("referral_bonus_earned"),
    REFERRAL_BONUS_CELEBRATION_SHOWN("referral_bonus_celebration_shown")
}

data class SubscriptionAnalyticsSnapshot(
    val isPro: Boolean,
    val hasRevenueCatSubscription: Boolean,
    val hasPromoAccess: Boolean,
    val hasSupabaseSubscription: Boolean,
    val isBillingGracePeriod: Boolean,
    val wasReferred: Boolean,
    val completedSessionCount: Int
)

@Singleton
class AnalyticsService @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var isConfigured = false

    fun configure(applicationContext: android.content.Context) {
        if (isConfigured) return
        if (BuildConfig.POSTHOG_API_KEY.isBlank()) {
            Log.w(TAG, "PostHog not configured: missing API key")
            return
        }

        runCatching {
            val config = PostHogAndroidConfig(
                apiKey = BuildConfig.POSTHOG_API_KEY,
                host = BuildConfig.POSTHOG_HOST
            ).apply {
                captureApplicationLifecycleEvents = false
                captureScreenViews = false
                captureDeepLinks = false
                sessionReplay = false
                errorTrackingConfig.autoCapture = true
                debug = false
            }

            PostHogAndroid.setup(applicationContext, config)
            if (PostHog.isOptOut()) {
                PostHog.optIn()
            }
            isConfigured = true
            Log.i(TAG, "PostHog configured (manual analytics: on, screen/lifecycle autocapture: off, replay: off, error tracking: on)")
            refreshPersonProperties()
        }.onFailure {
            Log.w(TAG, "PostHog configuration failed; analytics disabled", it)
        }
    }

    fun identify(userId: String, properties: Map<String, Any?> = emptyMap()) {
        if (userId.isBlank()) return
        runCatching {
            PostHog.identify(
                userId,
                userProperties = sanitizedProperties(properties)
            )
        }.onFailure { Log.w(TAG, "PostHog identify failed", it) }
    }

    fun reset() {
        runCatching { PostHog.reset() }
            .onFailure { Log.w(TAG, "PostHog reset failed", it) }
    }

    fun setPersonProperties(properties: Map<String, Any?>) {
        runCatching {
            PostHog.capture(
                "\$set",
                properties = emptyMap(),
                userProperties = sanitizedProperties(properties)
            )
        }.onFailure { Log.w(TAG, "PostHog person property update failed", it) }
    }

    fun refreshPersonProperties(subscription: SubscriptionAnalyticsSnapshot? = null) {
        scope.launch {
            runCatching {
                val props = mutableMapOf<String, Any?>(
                    "app_version" to BuildConfig.VERSION_NAME,
                    "app_build" to BuildConfig.VERSION_CODE.toString(),
                    "locale" to settingsRepository.appLanguage.first(),
                    "has_completed_onboarding" to settingsRepository.onboardingCompleted.first(),
                    "has_skipped_login" to settingsRepository.hasSkippedLogin.first()
                )
                settingsRepository.sportCategory.first()?.let {
                    props["sport_category"] = sportCategoryRawValue(it)
                }
                if (subscription != null) {
                    props["is_pro"] = subscription.isPro
                    props["has_rc_subscription"] = subscription.hasRevenueCatSubscription
                    props["has_promo_access"] = subscription.hasPromoAccess
                    props["has_supabase_subscription"] = subscription.hasSupabaseSubscription
                    props["is_billing_grace_period"] = subscription.isBillingGracePeriod
                    props["was_referred"] = subscription.wasReferred
                    props["completed_session_count"] = subscription.completedSessionCount
                }
                withContext(Dispatchers.Main.immediate) {
                    setPersonProperties(props)
                }
            }.onFailure {
                Log.w(TAG, "PostHog person property refresh skipped", it)
            }
        }
    }

    fun track(event: AnalyticsEvent, properties: Map<String, Any?> = emptyMap()) {
        trackRaw(event.rawValue, properties)
        compatibilityAliases(event).forEach { alias -> trackRaw(alias, properties) }
    }

    fun trackOnboardingStep(stepName: String, action: AnalyticsEvent) {
        track(action, mapOf("step_name" to stepName))
    }

    fun trackPaywall(action: AnalyticsEvent, properties: Map<String, Any?> = emptyMap()) {
        track(action, properties)
    }

    fun trackFeature(featureName: String, properties: Map<String, Any?> = emptyMap()) {
        track(
            AnalyticsEvent.FEATURE_USED,
            mapOf("feature_name" to featureName) + properties
        )
    }

    fun trackRaw(eventName: String, properties: Map<String, Any?> = emptyMap()) {
        val sanitized = sanitizedProperties(properties)
        if (sanitized.isEmpty()) {
            Log.i(TAG, "event=$eventName")
        } else {
            Log.i(TAG, "event=$eventName props=$sanitized")
        }
        runCatching {
            PostHog.capture(eventName, properties = sanitized)
        }.onFailure { Log.w(TAG, "PostHog capture failed for $eventName", it) }
    }

    fun captureException(error: Throwable, properties: Map<String, Any?> = emptyMap()) {
        runCatching {
            PostHog.captureException(
                error,
                properties = sanitizedProperties(properties)
            )
        }.onFailure { Log.w(TAG, "PostHog exception capture failed", it) }
    }

    fun distinctId(): String {
        return runCatching { PostHog.distinctId() }
            .getOrDefault("")
    }

    private fun compatibilityAliases(event: AnalyticsEvent): List<String> {
        return when (event) {
            AnalyticsEvent.SESSION_CREATED -> listOf("session_started")
            AnalyticsEvent.PAYWALL_SKIPPED -> listOf("paywall_dismissed")
            AnalyticsEvent.PAYWALL_RESTORE_TAPPED -> listOf("restore_purchases_tapped")
            else -> emptyList()
        }
    }

    companion object {
        private const val TAG = "AnalyticsService"
        private val uuidRegex =
            Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
        private val emailRegex =
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

        fun sanitizedProperties(properties: Map<String, Any?>): Map<String, Any> {
            return properties.mapNotNull { (key, value) ->
                val sanitizedValue = when (value) {
                    null -> return@mapNotNull null
                    is String -> redactSensitiveString(value)
                    else -> value
                }
                key to sanitizedValue
            }.toMap()
        }

        fun redactSensitiveString(input: String): String {
            return emailRegex.replace(
                uuidRegex.replace(input, "[redacted]"),
                "[redacted]"
            )
        }

        fun sportCategoryRawValue(category: SportCategory): String {
            return category.name.lowercase(Locale.US)
        }
    }
}
