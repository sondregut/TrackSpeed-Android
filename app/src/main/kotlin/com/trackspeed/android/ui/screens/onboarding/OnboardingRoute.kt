package com.trackspeed.android.ui.screens.onboarding

enum class OnboardingStep {
    WELCOME,               // 0
    REFERRAL_WELCOME,      // 1  - conditional for users who arrived from an invite link
    VALUE_PROPOSITION,     // 2
    FLYING_TIME,           // 3
    GOAL_TIME,             // 4
    GOAL_MOTIVATION,       // 5  - personalized chart + research
    PAIN_POINTS,           // 6
    PAIN_SWIPE,            // 7
    HOW_IT_WORKS,          // 8
    ATTRIBUTION,           // 9
    CAMERA_PERMISSION,     // 10
    SOLO_DEMO,             // 11
    RATING,                // 12
    TRIAL_INTRO,           // 13
    TRIAL_REMINDER,        // 14
    NOTIFICATION,          // 15
    COMPETITOR_COMPARISON, // 16
    PAYWALL,               // 17
    PAYWALL_RECOVERY,      // 18
    SPIN_WHEEL,            // 19
    COMPLETION,            // 20
    AUTH,                  // 21
    PROFILE_SETUP,         // 22
    START_TYPES,           // 23
    MULTI_DEVICE,          // 24
    CAMERA_ALIGNMENT,
    AUTO_TIMING,
    PROOF_PROGRESS,
    GETTING_STARTED, ROLE_SELECTION, SPORT_SELECTION;

    val progress: Float
        get() {
            if (this == WELCOME) return 0f
            val first = REFERRAL_WELCOME.ordinal
            val last = PAYWALL.ordinal
            val clamped = ordinal.coerceIn(first, last)
            return (clamped - first).toFloat() / (last - first).toFloat()
        }
    val showsProgressBar: Boolean get() = this != WELCOME && this != AUTH && this != PAYWALL_RECOVERY && this != COMPLETION && this != GETTING_STARTED
    val showsBackButton: Boolean get() = showsProgressBar && ordinal > 0

    val analyticsName: String
        get() = when (this) {
            WELCOME -> "getStarted"
            REFERRAL_WELCOME -> "referralWelcome"
            VALUE_PROPOSITION -> "valueProposition"
            FLYING_TIME -> "flyingPB"
            GOAL_TIME -> "goalTime"
            GOAL_MOTIVATION -> "goalValidation"
            PAIN_POINTS -> "painPoints"
            PAIN_SWIPE -> "painSwipe"
            HOW_IT_WORKS -> "howItWorks"
            ATTRIBUTION -> "referralCodeEntry"
            CAMERA_PERMISSION -> "cameraPermission"
            SOLO_DEMO -> "soloDemo"
            RATING -> "rating"
            TRIAL_INTRO -> "trialIntro"
            TRIAL_REMINDER -> "trialReminder"
            NOTIFICATION -> "notification"
            COMPETITOR_COMPARISON -> "competitorComparison"
            PAYWALL -> "paywall"
            PAYWALL_RECOVERY -> "paywallRecovery"
            SPIN_WHEEL -> "spinWheel"
            COMPLETION -> "completion"
            AUTH -> "auth"
            PROFILE_SETUP -> "profileSetup"
            START_TYPES -> "startTypes"
            MULTI_DEVICE -> "multiDevice"
            CAMERA_ALIGNMENT -> "cameraAlignment"
            AUTO_TIMING -> "autoTiming"
            PROOF_PROGRESS -> "proofAndProgress"
            GETTING_STARTED -> "gettingStartedGuide"
            ROLE_SELECTION -> "userRole"
            SPORT_SELECTION -> "primarySport"
        }
}

/** Explicit product route: enum ordinals are stable legacy identifiers, not navigation order. */
internal class OnboardingRoute(isReferred: Boolean, private val earlyRelease: Boolean) {
    val steps = buildList {
        add(OnboardingStep.WELCOME)
        if (isReferred) add(OnboardingStep.REFERRAL_WELCOME)
        addAll(listOf(OnboardingStep.VALUE_PROPOSITION, OnboardingStep.MULTI_DEVICE,
            OnboardingStep.CAMERA_ALIGNMENT, OnboardingStep.START_TYPES,
            OnboardingStep.AUTO_TIMING, OnboardingStep.PROOF_PROGRESS,
            OnboardingStep.ROLE_SELECTION, OnboardingStep.SPORT_SELECTION,
            OnboardingStep.HOW_IT_WORKS, OnboardingStep.ATTRIBUTION,
            OnboardingStep.CAMERA_PERMISSION, OnboardingStep.SOLO_DEMO))
        if (!earlyRelease) add(OnboardingStep.COMPETITOR_COMPARISON)
        addAll(listOf(OnboardingStep.PAYWALL, OnboardingStep.AUTH, OnboardingStep.GETTING_STARTED))
    }
    /** Upgrade saved process state without reopening retired questions or skipping access. */
    fun restore(step: OnboardingStep): OnboardingStep {
        if (step in steps) return step
        return when (step) {
            OnboardingStep.FLYING_TIME -> OnboardingStep.ROLE_SELECTION
            OnboardingStep.GOAL_TIME -> OnboardingStep.SPORT_SELECTION
            OnboardingStep.PAIN_POINTS, OnboardingStep.PAIN_SWIPE,
            OnboardingStep.GOAL_MOTIVATION -> OnboardingStep.HOW_IT_WORKS
            OnboardingStep.REFERRAL_WELCOME -> OnboardingStep.VALUE_PROPOSITION
            OnboardingStep.PROFILE_SETUP -> OnboardingStep.AUTH
            OnboardingStep.COMPLETION -> OnboardingStep.GETTING_STARTED
            OnboardingStep.PAYWALL_RECOVERY -> if (earlyRelease) OnboardingStep.PAYWALL else step
            OnboardingStep.RATING, OnboardingStep.TRIAL_INTRO, OnboardingStep.TRIAL_REMINDER,
            OnboardingStep.NOTIFICATION, OnboardingStep.COMPETITOR_COMPARISON,
            OnboardingStep.SPIN_WHEEL -> OnboardingStep.PAYWALL
            else -> OnboardingStep.WELCOME
        }
    }
    fun next(step: OnboardingStep): OnboardingStep {
        val current = restore(step)
        // Recovery is an off-route purchase choice, never an implicit authentication bypass.
        if (current == OnboardingStep.PAYWALL_RECOVERY) return OnboardingStep.PAYWALL
        return steps.getOrNull(steps.indexOf(current) + 1) ?: OnboardingStep.GETTING_STARTED
    }
    fun previous(step: OnboardingStep): OnboardingStep = when (step) {
        OnboardingStep.PAYWALL_RECOVERY -> OnboardingStep.PAYWALL
        OnboardingStep.AUTH -> OnboardingStep.PAYWALL
        else -> steps.getOrNull(steps.indexOf(restore(step)) - 1) ?: OnboardingStep.WELCOME
    }
    /** Outgoing animated pages retain callbacks; only their current source may navigate. */
    fun nextFrom(current: OnboardingStep, source: OnboardingStep): OnboardingStep? =
        destinationFrom(current, source, next(source))

    fun previousFrom(current: OnboardingStep, source: OnboardingStep): OnboardingStep? =
        destinationFrom(current, source, previous(source))

    fun destinationFrom(current: OnboardingStep, source: OnboardingStep, destination: OnboardingStep): OnboardingStep? =
        destination.takeIf { current == source }

    fun progress(step: OnboardingStep): Float {
        val end = steps.indexOf(OnboardingStep.PAYWALL).coerceAtLeast(1)
        return steps.indexOf(restore(step)).coerceIn(0, end).toFloat() / end
    }
}
