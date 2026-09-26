package com.trackspeed.android.data.model

enum class UserRole(val rawValue: String, val displayName: String) {
    ATHLETE("athlete", "Athlete"),
    COACH("coach", "Coach");

    companion object {
        fun fromRawValue(raw: String): UserRole? = entries.find { it.rawValue == raw }
    }
}

enum class SportCategory(val displayName: String) {
    SPRINTS("Sprints"),
    HURDLES("Hurdles"),
    MIDDLE_DISTANCE("Middle Distance"),
    LONG_DISTANCE("Long Distance"),
    FIELD_EVENTS("Field Events"),
    TEAM_SPORTS("Team Sports"),
    STRENGTH("Strength & Power");
}

enum class SportDiscipline(
    val rawValue: String,
    val displayName: String,
    val category: SportCategory
) {
    SPRINT_60M("60m", "60m Sprint", SportCategory.SPRINTS),
    SPRINT_100M("100m", "100m Sprint", SportCategory.SPRINTS),
    SPRINT_200M("200m", "200m Sprint", SportCategory.SPRINTS),
    SPRINT_400M("400m", "400m Sprint", SportCategory.SPRINTS),
    HURDLES_60M("60mH", "60m Hurdles", SportCategory.HURDLES),
    HURDLES_100M("100mH", "100m Hurdles", SportCategory.HURDLES),
    HURDLES_110M("110mH", "110m Hurdles", SportCategory.HURDLES),
    HURDLES_400M("400mH", "400m Hurdles", SportCategory.HURDLES),
    MIDDLE_800M("800m", "800m", SportCategory.MIDDLE_DISTANCE),
    MIDDLE_1500M("1500m", "1500m", SportCategory.MIDDLE_DISTANCE),
    LONG_3000M("3000m", "3000m", SportCategory.LONG_DISTANCE),
    LONG_5000M("5000m", "5000m", SportCategory.LONG_DISTANCE),
    LONG_10000M("10000m", "10000m", SportCategory.LONG_DISTANCE),
    LONG_JUMP("longJump", "Long Jump", SportCategory.FIELD_EVENTS),
    TRIPLE_JUMP("tripleJump", "Triple Jump", SportCategory.FIELD_EVENTS),
    HIGH_JUMP("highJump", "High Jump", SportCategory.FIELD_EVENTS),
    POLE_VAULT("poleVault", "Pole Vault", SportCategory.FIELD_EVENTS),
    SHOT_PUT("shotPut", "Shot Put", SportCategory.FIELD_EVENTS),
    DISCUS("discus", "Discus Throw", SportCategory.FIELD_EVENTS),
    JAVELIN("javelin", "Javelin Throw", SportCategory.FIELD_EVENTS),
    HAMMER("hammer", "Hammer Throw", SportCategory.FIELD_EVENTS),
    FOOTBALL("football", "Football", SportCategory.TEAM_SPORTS),
    SOCCER("soccer", "Soccer", SportCategory.TEAM_SPORTS),
    RUGBY("rugby", "Rugby", SportCategory.TEAM_SPORTS),
    BASKETBALL("basketball", "Basketball", SportCategory.TEAM_SPORTS),
    HOCKEY("hockey", "Hockey", SportCategory.TEAM_SPORTS),
    POWERLIFTING("powerlifting", "Powerlifting", SportCategory.STRENGTH),
    CROSSFIT("crossfit", "CrossFit", SportCategory.STRENGTH),
    OTHER("other", "Other", SportCategory.SPRINTS);

    companion object {
        fun fromRawValue(raw: String): SportDiscipline? = entries.find { it.rawValue == raw }
        fun byCategory(): Map<SportCategory, List<SportDiscipline>> = entries.groupBy { it.category }
    }
}

enum class FlyingDistance(val rawValue: String, val displayName: String, val meters: Int) {
    METERS_10("10m", "10 meters", 10),
    METERS_20("20m", "20 meters", 20),
    METERS_30("30m", "30 meters", 30);

    companion object {
        fun fromRawValue(raw: String): FlyingDistance? = entries.find { it.rawValue == raw }
    }
}
