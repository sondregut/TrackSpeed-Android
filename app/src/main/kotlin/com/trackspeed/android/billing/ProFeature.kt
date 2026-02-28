package com.trackspeed.android.billing

enum class ProFeature(val displayName: String, val description: String) {
    MILLISECOND_ACCURACY("Millisecond Accuracy", "Photo-finish accuracy down to 4 thousandths of a second"),
    MULTI_PHONE_SYNC("Multi-Phone Sync", "Use 2+ phones for start and finish lines"),
    UNLIMITED_HISTORY("Unlimited History", "Save every session and track improvement over months"),
    ATHLETE_PROFILES("Athlete Profiles", "Create profiles for each athlete with their own history"),
    VIDEO_EXPORT("Video Export", "Export videos with time overlay");
}
