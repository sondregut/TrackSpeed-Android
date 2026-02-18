package com.trackspeed.android.cloud

import android.util.Log
import com.trackspeed.android.cloud.dto.PairingRequestDto
import com.trackspeed.android.cloud.dto.RaceEventDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for interacting with Supabase race_events and pairing_requests tables.
 * Provides real-time subscriptions and CRUD operations for cross-device timing.
 */
@Singleton
class RaceEventService @Inject constructor(
    private val supabase: SupabaseClient
) {

    companion object {
        private const val TAG = "RaceEventService"
        private const val TABLE_RACE_EVENTS = "race_events"
        private const val TABLE_PAIRING_REQUESTS = "pairing_requests"
    }

    /**
     * Insert a race event (start or finish crossing) into the race_events table.
     */
    suspend fun insertRaceEvent(event: RaceEventDto) {
        try {
            supabase.from(TABLE_RACE_EVENTS).insert(event)
            Log.d(TAG, "Inserted race event: type=${event.eventType}, session=${event.sessionId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert race event", e)
            throw e
        }
    }

    /**
     * Subscribe to real-time race events for a specific session.
     * Returns a Flow that emits RaceEventDto whenever a new event is inserted
     * for the given sessionId.
     */
    fun subscribeToRaceEvents(sessionId: String): Flow<RaceEventDto> = flow {
        val channel = supabase.channel("race_events_$sessionId")

        val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(
            schema = "public"
        ) {
            table = TABLE_RACE_EVENTS
            filter("session_id", FilterOperator.EQ, sessionId)
        }

        channel.subscribe()
        Log.d(TAG, "Subscribed to race events for session: $sessionId")

        try {
            emitAll(
                changeFlow.mapNotNull { insertAction ->
                    try {
                        insertAction.decodeRecord<RaceEventDto>()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode race event from realtime", e)
                        null
                    }
                }
            )
        } finally {
            supabase.realtime.removeChannel(channel)
            Log.d(TAG, "Unsubscribed from race events for session: $sessionId")
        }
    }

    /**
     * Create a new pairing request. The host device creates this and shares
     * the session code with the joining device.
     */
    suspend fun createPairingRequest(
        code: String,
        deviceId: String,
        deviceName: String
    ) {
        try {
            val request = PairingRequestDto(
                sessionCode = code,
                hostDeviceId = deviceId,
                hostDeviceName = deviceName,
                status = "waiting"
            )
            supabase.from(TABLE_PAIRING_REQUESTS).insert(request)
            Log.d(TAG, "Created pairing request with code: $code")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create pairing request", e)
            throw e
        }
    }

    /**
     * Join an existing pairing request by updating it with the joiner's device info
     * and changing the status to "matched".
     */
    suspend fun joinPairingRequest(
        code: String,
        deviceId: String,
        deviceName: String
    ) {
        try {
            supabase.from(TABLE_PAIRING_REQUESTS).update(
                {
                    set("joiner_device_id", deviceId)
                    set("joiner_device_name", deviceName)
                    set("status", "matched")
                }
            ) {
                filter {
                    eq("session_code", code)
                    eq("status", "waiting")
                }
            }
            Log.d(TAG, "Joined pairing request with code: $code")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to join pairing request", e)
            throw e
        }
    }

    /**
     * Watch a pairing request for changes (e.g., a joiner connecting).
     * Emits updated PairingRequestDto whenever the row is updated.
     */
    fun watchPairingRequest(code: String): Flow<PairingRequestDto> = flow {
        val channel = supabase.channel("pairing_$code")

        val changeFlow = channel.postgresChangeFlow<PostgresAction>(
            schema = "public"
        ) {
            table = TABLE_PAIRING_REQUESTS
        }

        channel.subscribe()
        Log.d(TAG, "Watching pairing request: $code")

        try {
            emitAll(
                changeFlow.mapNotNull { action ->
                    try {
                        val dto = when (action) {
                            is PostgresAction.Insert -> action.decodeRecord<PairingRequestDto>()
                            is PostgresAction.Update -> action.decodeRecord<PairingRequestDto>()
                            else -> null
                        }
                        // Filter to only this pairing code
                        if (dto?.sessionCode == code) dto else null
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode pairing request from realtime", e)
                        null
                    }
                }
            )
        } finally {
            supabase.realtime.removeChannel(channel)
            Log.d(TAG, "Stopped watching pairing request: $code")
        }
    }

    /**
     * Fetch a pairing request by session code.
     */
    suspend fun getPairingRequest(code: String): PairingRequestDto? {
        return try {
            supabase.from(TABLE_PAIRING_REQUESTS)
                .select {
                    filter {
                        eq("session_code", code)
                    }
                }
                .decodeSingleOrNull<PairingRequestDto>()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch pairing request", e)
            null
        }
    }

    /**
     * Update pairing request status to "connected" once both devices confirm.
     */
    suspend fun confirmPairingConnection(code: String) {
        try {
            supabase.from(TABLE_PAIRING_REQUESTS).update(
                {
                    set("status", "connected")
                }
            ) {
                filter {
                    eq("session_code", code)
                }
            }
            Log.d(TAG, "Confirmed pairing connection for code: $code")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to confirm pairing connection", e)
            throw e
        }
    }
}
