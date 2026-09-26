package com.trackspeed.android.ui.screens.onephone

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.trackspeed.android.R
import com.trackspeed.android.onephone.SingleCameraInvalidReason
import com.trackspeed.android.onephone.SingleCameraSprintConfiguration
import com.trackspeed.android.onephone.SingleCameraSprintDrill
import com.trackspeed.android.onephone.SingleCameraSprintResultPresentation
import com.trackspeed.android.onephone.SingleCameraSprintStartStyle

@StringRes
fun singleCameraDrillTitleRes(drill: SingleCameraSprintDrill): Int = when (drill) {
    SingleCameraSprintDrill.SPRINT -> R.string.one_phone_drill_sprint
    SingleCameraSprintDrill.PRO_AGILITY -> R.string.one_phone_drill_pro_agility
    SingleCameraSprintDrill.SHUTTLE -> R.string.one_phone_drill_shuttle
}

@StringRes
fun singleCameraDrillInstructionsRes(drill: SingleCameraSprintDrill): Int = when (drill) {
    SingleCameraSprintDrill.SPRINT -> R.string.one_phone_drill_sprint_instructions
    SingleCameraSprintDrill.PRO_AGILITY -> R.string.one_phone_drill_pro_agility_instructions
    SingleCameraSprintDrill.SHUTTLE -> R.string.one_phone_drill_shuttle_instructions
}

@StringRes
fun singleCameraStartStyleTitleRes(style: SingleCameraSprintStartStyle): Int = when (style) {
    SingleCameraSprintStartStyle.STANDING -> R.string.one_phone_start_standing
    SingleCameraSprintStartStyle.FLYING -> R.string.one_phone_start_flying
}

@Composable
fun singleCameraDistanceLabel(configuration: SingleCameraSprintConfiguration, short: Boolean = false): String =
    if (configuration.drill == SingleCameraSprintDrill.PRO_AGILITY) {
        stringResource(if (short) R.string.one_phone_distance_pro_agility_short else R.string.one_phone_distance_pro_agility)
    } else {
        stringResource(R.string.one_phone_distance_meters, configuration.distanceMeters.toInt())
    }

@StringRes
fun singleCameraInvalidReasonRes(reason: SingleCameraInvalidReason): Int = when (reason) {
    SingleCameraInvalidReason.GATE_LAYOUT -> R.string.one_phone_invalid_gate_layout
    SingleCameraInvalidReason.FLYING_FRAMING -> R.string.one_phone_invalid_flying_framing
    SingleCameraInvalidReason.PRO_AGILITY_LAYOUT -> R.string.one_phone_invalid_pro_agility_layout
    SingleCameraInvalidReason.TRACKING_LOST -> R.string.one_phone_invalid_tracking_lost
    SingleCameraInvalidReason.REACHED_START_BEFORE_READY -> R.string.one_phone_invalid_reached_start
    SingleCameraInvalidReason.POSITION_JUMPED -> R.string.one_phone_invalid_position_jumped
    SingleCameraInvalidReason.START_NOT_BRACKETED -> R.string.one_phone_invalid_start_not_bracketed
    SingleCameraInvalidReason.CAMERA_INTERRUPTED -> R.string.one_phone_invalid_camera_interrupted
    SingleCameraInvalidReason.FLYING_LOCK_UNAVAILABLE,
    SingleCameraInvalidReason.STANDING_BASELINE_UNAVAILABLE,
    SingleCameraInvalidReason.START_TIMESTAMP_UNAVAILABLE -> R.string.one_phone_invalid_generic
}

@StringRes
fun singleCameraQualityNoteRes(reason: SingleCameraSprintResultPresentation.NoteReason): Int = when (reason) {
    SingleCameraSprintResultPresentation.NoteReason.REVIEW_LINE_TOUCHES -> R.string.one_phone_quality_review_touches
    SingleCameraSprintResultPresentation.NoteReason.USABLE_LOWER_TRACKING_RATE -> R.string.one_phone_quality_usable_tracking_rate
    SingleCameraSprintResultPresentation.NoteReason.USABLE_LOWER_POSE_CONFIDENCE -> R.string.one_phone_quality_usable_pose_confidence
    SingleCameraSprintResultPresentation.NoteReason.USABLE_LOOSE_CALIBRATION -> R.string.one_phone_quality_usable_calibration
    SingleCameraSprintResultPresentation.NoteReason.USABLE_CHECK_SETUP -> R.string.one_phone_quality_usable_setup
    SingleCameraSprintResultPresentation.NoteReason.REVIEW_PHONE_MOVED -> R.string.one_phone_quality_review_phone_moved
    SingleCameraSprintResultPresentation.NoteReason.REVIEW_LOW_TRACKING_RATE -> R.string.one_phone_quality_review_tracking_rate
    SingleCameraSprintResultPresentation.NoteReason.REVIEW_LOW_POSE_CONFIDENCE -> R.string.one_phone_quality_review_pose_confidence
    SingleCameraSprintResultPresentation.NoteReason.REVIEW_CHECK_CALIBRATION -> R.string.one_phone_quality_review_calibration
}
