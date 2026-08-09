package com.trackspeed.android.ui.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportCategory
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.data.model.UserRole
import com.trackspeed.android.model.StartType
import com.trackspeed.android.model.TestPreset
import com.trackspeed.android.model.TestPresetCategory

@StringRes
fun StartType.displayNameResource(): Int = when (this) {
    StartType.FLYING -> R.string.start_type_flying_start
    StartType.TOUCH_RELEASE -> R.string.start_type_touch_release
    StartType.COUNTDOWN -> R.string.start_selector_countdown
    StartType.VOICE_COMMAND -> R.string.start_selector_voice
    StartType.IN_FRAME -> R.string.start_type_inframe_start
}

@StringRes
fun StartType.shortNameResource(): Int = when (this) {
    StartType.FLYING -> R.string.start_type_flying
    StartType.TOUCH_RELEASE -> R.string.start_type_touch_short
    StartType.COUNTDOWN -> R.string.start_selector_countdown
    StartType.VOICE_COMMAND -> R.string.start_type_voice_short
    StartType.IN_FRAME -> R.string.start_selector_inframe
}

@StringRes
fun StartType.descriptionResource(): Int = when (this) {
    StartType.FLYING -> R.string.start_type_flying_full_desc
    StartType.TOUCH_RELEASE -> R.string.start_type_touch_release_desc
    StartType.COUNTDOWN -> R.string.start_type_countdown_full_desc
    StartType.VOICE_COMMAND -> R.string.start_type_voice_command_desc
    StartType.IN_FRAME -> R.string.start_type_inframe_desc
}

@Composable
fun StartType.localizedDisplayName(): String = stringResource(displayNameResource())

@Composable
fun StartType.localizedShortName(): String = stringResource(shortNameResource())

@Composable
fun StartType.localizedDescription(): String = stringResource(descriptionResource())

@StringRes
fun TestPresetCategory.displayNameResource(): Int = when (this) {
    TestPresetCategory.ACCELERATION -> R.string.preset_category_acceleration
    TestPresetCategory.MAX_SPEED -> R.string.preset_category_max_speed
    TestPresetCategory.AGILITY -> R.string.preset_category_agility
    TestPresetCategory.COMBINE -> R.string.preset_category_combine
}

@Composable
fun TestPresetCategory.localizedDisplayName(): String = stringResource(displayNameResource())

@StringRes
fun TestPreset.nameResource(): Int = when (id) {
    "40yd" -> R.string.preset_40yd_name
    "60m" -> R.string.preset_60m_name
    "flying" -> R.string.preset_flying_name
    "takeoff-velocity" -> R.string.preset_takeoff_velocity_name
    "flying-10m" -> R.string.preset_flying_10m_name
    "flying-30m" -> R.string.preset_flying_30m_name
    "30m" -> R.string.preset_30m_name
    "practice" -> R.string.preset_practice_name
    "5-10-5" -> R.string.preset_pro_agility_name
    "100m" -> R.string.preset_100m_name
    "10m" -> R.string.preset_10m_name
    "20m" -> R.string.preset_20m_name
    "l-drill" -> R.string.preset_l_drill_name
    else -> R.string.preset_custom_name
}

@Composable
fun TestPreset.localizedName(): String = stringResource(nameResource())

fun TestPreset.tipResources(): List<Int> = when (id) {
    "40yd" -> listOf(R.string.preset_40yd_tip_1, R.string.preset_40yd_tip_2)
    "60m" -> listOf(R.string.preset_60m_tip_1, R.string.preset_60m_tip_2)
    "flying" -> listOf(
        R.string.preset_flying_tip_1,
        R.string.preset_flying_tip_2,
        R.string.preset_flying_tip_3
    )
    "takeoff-velocity" -> listOf(
        R.string.preset_takeoff_tip_1,
        R.string.preset_takeoff_tip_2,
        R.string.preset_takeoff_tip_3
    )
    "flying-10m" -> listOf(
        R.string.preset_flying_10m_tip_1,
        R.string.preset_flying_10m_tip_2,
        R.string.preset_flying_10m_tip_3
    )
    "flying-30m" -> listOf(
        R.string.preset_flying_30m_tip_1,
        R.string.preset_flying_30m_tip_2,
        R.string.preset_flying_30m_tip_3
    )
    "30m" -> listOf(R.string.preset_30m_tip_1, R.string.preset_30m_tip_2)
    "practice" -> listOf(
        R.string.preset_practice_tip_1,
        R.string.preset_practice_tip_2,
        R.string.preset_practice_tip_3
    )
    "5-10-5" -> listOf(
        R.string.preset_pro_agility_tip_1,
        R.string.preset_pro_agility_tip_2,
        R.string.preset_pro_agility_tip_3,
        R.string.preset_pro_agility_tip_4
    )
    "100m" -> listOf(
        R.string.preset_100m_tip_1,
        R.string.preset_100m_tip_2,
        R.string.preset_100m_tip_3
    )
    "10m" -> listOf(
        R.string.preset_10m_tip_1,
        R.string.preset_10m_tip_2,
        R.string.preset_10m_tip_3
    )
    "20m" -> listOf(
        R.string.preset_20m_tip_1,
        R.string.preset_20m_tip_2,
        R.string.preset_20m_tip_3
    )
    "l-drill" -> listOf(
        R.string.preset_l_drill_tip_1,
        R.string.preset_l_drill_tip_2,
        R.string.preset_l_drill_tip_3,
        R.string.preset_l_drill_tip_4
    )
    else -> emptyList()
}

@Composable
fun TestPreset.localizedTips(): List<String> {
    val context = LocalContext.current
    return tipResources().map(context::getString)
}

@StringRes
fun UserRole.displayNameResource(): Int = when (this) {
    UserRole.ATHLETE -> R.string.sport_role_athlete
    UserRole.COACH -> R.string.sport_role_coach
}

@Composable
fun UserRole.localizedDisplayName(): String = stringResource(displayNameResource())

@StringRes
fun SportCategory.displayNameResource(): Int = when (this) {
    SportCategory.SPRINTS -> R.string.sport_category_sprints
    SportCategory.HURDLES -> R.string.sport_category_hurdles
    SportCategory.MIDDLE_DISTANCE -> R.string.sport_category_middle_distance
    SportCategory.LONG_DISTANCE -> R.string.sport_category_long_distance
    SportCategory.FIELD_EVENTS -> R.string.sport_category_field_events
    SportCategory.TEAM_SPORTS -> R.string.sport_category_team_sports
    SportCategory.STRENGTH -> R.string.sport_category_strength
}

@Composable
fun SportCategory.localizedDisplayName(): String = stringResource(displayNameResource())

@StringRes
fun SportDiscipline.displayNameResource(): Int = when (this) {
    SportDiscipline.SPRINT_60M -> R.string.sport_discipline_60m_sprint
    SportDiscipline.SPRINT_100M -> R.string.sport_discipline_100m_sprint
    SportDiscipline.SPRINT_200M -> R.string.sport_discipline_200m_sprint
    SportDiscipline.SPRINT_400M -> R.string.sport_discipline_400m_sprint
    SportDiscipline.HURDLES_60M -> R.string.sport_discipline_60m_hurdles
    SportDiscipline.HURDLES_100M -> R.string.sport_discipline_100m_hurdles
    SportDiscipline.HURDLES_110M -> R.string.sport_discipline_110m_hurdles
    SportDiscipline.HURDLES_400M -> R.string.sport_discipline_400m_hurdles
    SportDiscipline.MIDDLE_800M -> R.string.sport_discipline_800m
    SportDiscipline.MIDDLE_1500M -> R.string.sport_discipline_1500m
    SportDiscipline.LONG_3000M -> R.string.sport_discipline_3000m
    SportDiscipline.LONG_5000M -> R.string.sport_discipline_5000m
    SportDiscipline.LONG_10000M -> R.string.sport_discipline_10000m
    SportDiscipline.LONG_JUMP -> R.string.sport_discipline_long_jump
    SportDiscipline.TRIPLE_JUMP -> R.string.sport_discipline_triple_jump
    SportDiscipline.HIGH_JUMP -> R.string.sport_discipline_high_jump
    SportDiscipline.POLE_VAULT -> R.string.sport_discipline_pole_vault
    SportDiscipline.SHOT_PUT -> R.string.sport_discipline_shot_put
    SportDiscipline.DISCUS -> R.string.sport_discipline_discus
    SportDiscipline.JAVELIN -> R.string.sport_discipline_javelin
    SportDiscipline.HAMMER -> R.string.sport_discipline_hammer
    SportDiscipline.FOOTBALL -> R.string.sport_discipline_football
    SportDiscipline.SOCCER -> R.string.sport_discipline_soccer
    SportDiscipline.RUGBY -> R.string.sport_discipline_rugby
    SportDiscipline.BASKETBALL -> R.string.sport_discipline_basketball
    SportDiscipline.HOCKEY -> R.string.sport_discipline_hockey
    SportDiscipline.POWERLIFTING -> R.string.sport_discipline_powerlifting
    SportDiscipline.CROSSFIT -> R.string.sport_discipline_crossfit
    SportDiscipline.OTHER -> R.string.sport_discipline_other
}

@Composable
fun SportDiscipline.localizedDisplayName(): String = stringResource(displayNameResource())

@StringRes
fun FlyingDistance.displayNameResource(): Int = when (this) {
    FlyingDistance.METERS_10 -> R.string.sport_flying_distance_10m
    FlyingDistance.METERS_20 -> R.string.sport_flying_distance_20m
    FlyingDistance.METERS_30 -> R.string.sport_flying_distance_30m
}

@Composable
fun FlyingDistance.localizedDisplayName(): String = stringResource(displayNameResource())
