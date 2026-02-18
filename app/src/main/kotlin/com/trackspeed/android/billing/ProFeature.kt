package com.trackspeed.android.billing

enum class ProFeature(val displayName: String, val description: String) {
    MILLISECOND_ACCURACY("Millisecond Accuracy", "Photo-finish precision timing"),
    MULTI_PHONE_SYNC("Multi-Phone Sync", "Use 2+ phones for start/finish gates"),
    UNLIMITED_HISTORY("Unlimited History", "Save unlimited training sessions"),
    ATHLETE_PROFILES("Athlete Profiles", "Track multiple athletes"),
    VIDEO_EXPORT("Video Export", "Export results with time overlay");
}
