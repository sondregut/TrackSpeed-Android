package com.trackspeed.android.data.model

data class OnboardingProfile(
    val role: UserRole? = null,
    val discipline: SportDiscipline? = null,
    val personalRecord: Double? = null,
    val flyingDistance: FlyingDistance? = null,
    val flyingPR: Double? = null,
    val goalTime: Double? = null,
    val attribution: String? = null,
    val promoCode: String? = null,
    val displayName: String? = null,
    val teamName: String? = null,
    val referralCode: String? = null
)
