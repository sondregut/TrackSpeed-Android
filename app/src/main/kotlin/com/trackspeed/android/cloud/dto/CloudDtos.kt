package com.trackspeed.android.cloud.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for the `race_events` Supabase table.
 * Real-time cross-device timing events for multi-phone sessions.
 * Field names use snake_case to match the Postgres column names exactly.
 */
@Serializable
data class RaceEventDto(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("event_type") val eventType: String, // "start" or "finish"
    @SerialName("crossing_time_nanos") val crossingTimeNanos: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("clock_offset_nanos") val clockOffsetNanos: Long? = null,
    @SerialName("uncertainty_ms") val uncertaintyMs: Double? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class RaceEventWithoutRunIdDto(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("crossing_time_nanos") val crossingTimeNanos: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("clock_offset_nanos") val clockOffsetNanos: Long? = null,
    @SerialName("uncertainty_ms") val uncertaintyMs: Double? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    constructor(event: RaceEventDto) : this(
        id = event.id,
        sessionId = event.sessionId,
        eventType = event.eventType,
        crossingTimeNanos = event.crossingTimeNanos,
        deviceId = event.deviceId,
        deviceName = event.deviceName,
        imagePath = event.imagePath,
        clockOffsetNanos = event.clockOffsetNanos,
        uncertaintyMs = event.uncertaintyMs,
        createdAt = event.createdAt
    )
}

/**
 * DTO for the `sessions` Supabase table.
 * Training session metadata.
 */
@Serializable
data class SessionDto(
    val id: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String? = null,
    val name: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val distance: Double,
    @SerialName("start_type") val startType: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * DTO for the `runs` Supabase table.
 * Individual timing runs within sessions.
 */
@Serializable
data class RunDto(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("athlete_id") val athleteId: String? = null,
    @SerialName("athlete_name") val athleteName: String? = null,
    @SerialName("athlete_color") val athleteColor: String? = null,
    @SerialName("run_number") val runNumber: Int,
    @SerialName("time_seconds") val timeSeconds: Double,
    val distance: Double,
    @SerialName("start_type") val startType: String,
    @SerialName("reaction_time") val reactionTime: Double? = null,
    @SerialName("is_personal_best") val isPersonalBest: Boolean = false,
    @SerialName("is_season_best") val isSeasonBest: Boolean = false,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("splits_json") val splitsJson: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

/**
 * DTO for the `crossings` Supabase table.
 * Detailed crossing data for each gate.
 */
@Serializable
data class CrossingDto(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("run_id") val runId: String,
    @SerialName("gate_role") val gateRole: String, // start, split_1, split_2, split_3, finish, lap
    @SerialName("device_id") val deviceId: String,
    @SerialName("crossing_time_nanos") val crossingTimeNanos: Long,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("full_res_url") val fullResUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

/**
 * DTO for `crossing_review_marks`.
 * Stores tester/user spatial review annotations for detection quality analysis.
 */
@Serializable
data class CrossingReviewMarkDto(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("run_number") val runNumber: Int,
    @SerialName("gate_label") val gateLabel: String? = null,
    val target: String? = null,
    val mode: String? = null,
    @SerialName("crossing_direction") val crossingDirection: String? = null,
    val issue: String? = null,
    @SerialName("actual_x") val actualX: Double? = null,
    @SerialName("actual_y") val actualY: Double? = null,
    @SerialName("detector_x") val detectorX: Double,
    @SerialName("detector_y") val detectorY: Double? = null,
    @SerialName("delta_x") val deltaX: Double? = null,
    @SerialName("delta_y") val deltaY: Double? = null,
    @SerialName("interpolation_alpha") val interpolationAlpha: Double? = null,
    @SerialName("frame_pick") val framePick: String? = null,
    val s0: Double? = null,
    val s1: Double? = null,
    @SerialName("is_front_camera") val isFrontCamera: Boolean? = null,
    @SerialName("detection_distance") val detectionDistance: String? = null,
    @SerialName("work_width") val workWidth: Int? = null,
    @SerialName("exposure_ms") val exposureMs: Double? = null,
    val iso: Int? = null,
    @SerialName("detector_trigger_frame_pts") val detectorTriggerFramePts: Long? = null,
    @SerialName("chosen_thumbnail_frame_pts") val chosenThumbnailFramePts: Long? = null,
    @SerialName("saved_thumbnail_frame_pts") val savedThumbnailFramePts: Long? = null,
    @SerialName("thumbnail_storage_path") val thumbnailStoragePath: String? = null,
    val note: String? = null,
    @SerialName("raw_message") val rawMessage: String? = null,
    @SerialName("review_schema") val reviewSchema: Int
)

@Serializable
data class CrossingDebugFrameMetadataDto(
    val ptsNanos: Long,
    val chestX: Float,
    val blobHeightFraction: Float,
    val velocityPxPerSec: Float,
    val dtFromPrevMs: Float? = null,
    val anchorMode: String? = null,
    val torsoLeadingEdgeX: Float? = null,
    val legacyEdgeX: Float? = null,
    val centroidX: Float? = null,
    val torsoSegmentWidthPx: Int? = null,
    val timingModel: String? = null,
    val exposureCompensationFactor: Float? = null,
    val exposureDurationMs: Float? = null,
    val contourRowsUsed: Int? = null,
    val storagePath: String? = null
)

@Serializable
data class CrossingDebugCaptureDto(
    val id: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("run_id") val runId: String,
    @SerialName("run_number") val runNumber: Int,
    @SerialName("gate_label") val gateLabel: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("crossing_time_nanos") val crossingTimeNanos: Long,
    @SerialName("detector_trigger_frame_pts_nanos") val detectorTriggerFramePtsNanos: Long? = null,
    @SerialName("detector_chosen_frame_pts_nanos") val detectorChosenFramePtsNanos: Long? = null,
    @SerialName("saved_thumbnail_frame_pts_nanos") val savedThumbnailFramePtsNanos: Long? = null,
    @SerialName("detector_to_saved_frame_delta_ms") val detectorToSavedFrameDeltaMs: Float? = null,
    @SerialName("chosen_to_saved_frame_delta_ms") val chosenToSavedFrameDeltaMs: Float? = null,
    @SerialName("configured_gate_position") val configuredGatePosition: Float,
    @SerialName("detector_position") val detectorPosition: Float,
    @SerialName("interpolated_display_position") val interpolatedDisplayPosition: Float? = null,
    @SerialName("projected_display_position") val projectedDisplayPosition: Float? = null,
    @SerialName("algo_interpolation_alpha") val algoInterpolationAlpha: Float,
    @SerialName("algo_velocity_px_per_sec") val algoVelocityPxPerSec: Float,
    @SerialName("algo_blob_height_fraction") val algoBlobHeightFraction: Float,
    @SerialName("algo_blob_width_fraction") val algoBlobWidthFraction: Float? = null,
    @SerialName("algo_gate_position") val algoGatePosition: Float,
    @SerialName("algo_fps") val algoFps: Double,
    @SerialName("algo_gate_run_fraction") val algoGateRunFraction: Float? = null,
    @SerialName("algo_motion_amount") val algoMotionAmount: Float? = null,
    @SerialName("algo_exposure_compensation_ms") val algoExposureCompensationMs: Float? = null,
    @SerialName("algo_adaptive_threshold") val algoAdaptiveThreshold: Int? = null,
    @SerialName("algo_crossing_direction") val algoCrossingDirection: String? = null,
    @SerialName("algo_work_width") val algoWorkWidth: Int? = null,
    @SerialName("algo_s0") val algoS0: Float? = null,
    @SerialName("algo_s1") val algoS1: Float? = null,
    @SerialName("algo_detector_frame_duration_ms") val algoDetectorFrameDurationMs: Float? = null,
    @SerialName("reference_torso_width_px") val referenceTorsoWidthPx: Int? = null,
    @SerialName("accepted_row_count") val acceptedRowCount: Int? = null,
    @SerialName("cluster_height_px") val clusterHeightPx: Int? = null,
    @SerialName("cluster_median_width_px") val clusterMedianWidthPx: Int? = null,
    @SerialName("edge_near_gate_rows") val edgeNearGateRows: Int? = null,
    @SerialName("edge_past_gate_rows") val edgePastGateRows: Int? = null,
    @SerialName("longest_edge_past_gate_run") val longestEdgePastGateRun: Int? = null,
    @SerialName("used_gap_confirmation") val usedGapConfirmation: Boolean? = null,
    @SerialName("frame_cadence_gap_ms") val frameCadenceGapMs: Float? = null,
    @SerialName("thumbnail_storage_path") val thumbnailStoragePath: String? = null,
    @SerialName("frames_metadata") val framesMetadata: List<CrossingDebugFrameMetadataDto> = emptyList(),
    @SerialName("updated_at") val updatedAt: String
)

/**
 * DTO for `device_log_uploads`.
 * Mirrors iOS DeviceLogUploadRecord so backend review-log snapshots are searchable.
 */
@Serializable
data class DeviceLogUploadRecordDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("session_id") val sessionId: String? = null,
    val mode: String? = null,
    val role: String? = null,
    @SerialName("gate_index") val gateIndex: Int? = null,
    val reason: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("local_file_name") val localFileName: String,
    @SerialName("file_byte_count") val fileByteCount: Int,
    @SerialName("local_session_started_at") val localSessionStartedAt: String? = null,
    @SerialName("local_uploaded_at") val localUploadedAt: String
)

/**
 * DTO for the `timing_session_participants` Supabase table.
 *
 * Mirrors the iOS `TimingSessionParticipant` struct. Each row records that an
 * authenticated user/device joined a timing session — required by RLS on
 * race_events/crossings so reads and writes are scoped to participants.
 */
@Serializable
data class TimingSessionParticipantDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("created_at") val createdAt: String? = null
)

/**
 * DTO for the `device_tokens` Supabase table.
 *
 * Mirrors the iOS `DeviceTokenRecord` used by server-side push notification
 * delivery. Android registration depends on a real FCM token source, but the
 * backend schema and teardown path should still stay in sync.
 */
@Serializable
data class DeviceTokenRecordDto(
    @SerialName("device_id") val deviceId: String,
    val token: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("supabase_user_id") val supabaseUserId: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * DTO for the `pairing_requests` Supabase table.
 * Session code pairing for device discovery.
 */
@Serializable
data class PairingRequestDto(
    @SerialName("session_code") val sessionCode: String,
    @SerialName("host_device_id") val hostDeviceId: String,
    @SerialName("host_device_name") val hostDeviceName: String? = null,
    @SerialName("joiner_device_id") val joinerDeviceId: String? = null,
    @SerialName("joiner_device_name") val joinerDeviceName: String? = null,
    val status: String = "waiting", // waiting, matched, connected, expired
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

/**
 * DTO for the `athletes` Supabase table.
 * Athlete profiles.
 */
@Serializable
data class AthleteDto(
    val id: String? = null,
    @SerialName("device_id") val deviceId: String,
    /** Required by RLS post-lockdown: must equal auth.uid() at insert/update time. */
    @SerialName("user_id") val userId: String? = null,
    val name: String,
    val nickname: String? = null,
    val color: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    val birthdate: String? = null, // ISO date
    val gender: String? = null,
    @SerialName("personal_bests") val personalBests: Map<String, Double>? = emptyMap(),
    @SerialName("season_bests") val seasonBests: Map<String, Double>? = emptyMap(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * DTO for the `profiles` Supabase table.
 * User profiles synced to cloud.
 */
@Serializable
data class ProfileDto(
    val id: String? = null,
    @SerialName("supabase_user_id") val supabaseUserId: String,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    @SerialName("date_of_birth") val dateOfBirth: String? = null,
    val role: String? = null,
    @SerialName("primary_event") val primaryEvent: String? = null,
    @SerialName("personal_record") val personalRecord: Double? = null,
    @SerialName("flying_pr_distance") val flyingPrDistance: String? = null,
    @SerialName("flying_pr") val flyingPr: Double? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("onboarding_completed") val onboardingCompleted: Boolean = false,
    @SerialName("referral_code") val referralCode: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
