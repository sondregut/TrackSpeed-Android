package com.trackspeed.android.ui.screens.onboarding

import androidx.annotation.StringRes
import com.trackspeed.android.R

enum class OnboardingPainPoint(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val painRes: Int,
    @StringRes val fixRes: Int
) {
    PROGRESS_BLIND(
        id = "progressBlind",
        labelRes = R.string.onboarding_pain_progress_blind,
        painRes = R.string.onboarding_pain_fix_progress_blind_pain,
        fixRes = R.string.onboarding_pain_fix_progress_blind_fix
    ),
    STOPWATCH_INACCURATE(
        id = "stopwatchInaccurate",
        labelRes = R.string.onboarding_pain_stopwatch_inaccurate,
        painRes = R.string.onboarding_pain_fix_stopwatch_pain,
        fixRes = R.string.onboarding_pain_fix_stopwatch_fix
    ),
    HARDWARE_EXPENSIVE(
        id = "hardwareExpensive",
        labelRes = R.string.onboarding_pain_hardware_expensive,
        painRes = R.string.onboarding_pain_fix_hardware_pain,
        fixRes = R.string.onboarding_pain_fix_hardware_fix
    ),
    NO_COACH(
        id = "noCoach",
        labelRes = R.string.onboarding_pain_no_coach,
        painRes = R.string.onboarding_pain_fix_no_coach_pain,
        fixRes = R.string.onboarding_pain_fix_no_coach_fix
    ),
    WASTED_SESSIONS(
        id = "wastedSessions",
        labelRes = R.string.onboarding_pain_wasted_sessions,
        painRes = R.string.onboarding_pain_fix_wasted_sessions_pain,
        fixRes = R.string.onboarding_pain_fix_wasted_sessions_fix
    ),
    SPLITS_BLACK_BOX(
        id = "splitsBlackBox",
        labelRes = R.string.onboarding_pain_splits_black_box,
        painRes = R.string.onboarding_pain_fix_splits_pain,
        fixRes = R.string.onboarding_pain_fix_splits_fix
    );

    companion object {
        val defaultOrder = listOf(
            STOPWATCH_INACCURATE,
            PROGRESS_BLIND,
            HARDWARE_EXPENSIVE,
            SPLITS_BLACK_BOX,
            NO_COACH,
            WASTED_SESSIONS
        )
    }
}
