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
    @SerialName("event_type") val eventType: String, // "start" or "finish"
    @SerialName("crossing_time_nanos") val crossingTimeNanos: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("clock_offset_nanos") val clockOffsetNanos: Long? = null,
    @SerialName("uncertainty_ms") val uncertaintyMs: Double? = null,
    @SerialName("created_at") val createdAt: String? = null
)

/**
 * DTO for the `sessions` Supabase table.
 * Training session metadata.
 */
@Serializable
data class SessionDto(
    val id: String? = null,
    @SerialName("device_id") val deviceId: String,
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
    val name: String,
    val nickname: String? = null,
    val color: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    val birthdate: String? = null, // ISO date
    val gender: String? = null,
    @SerialName("personal_bests") val personalBests: Map<String, Double> = emptyMap(),
    @SerialName("season_bests") val seasonBests: Map<String, Double> = emptyMap(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
