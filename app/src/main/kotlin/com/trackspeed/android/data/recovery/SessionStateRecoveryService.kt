package com.trackspeed.android.data.recovery

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PersistedSessionState(
    val savedAtMillis: Long,
    val sessionId: String,
    val role: String,
    val runNumber: Int,
    val timerStartTimeNanos: Long?,
    val resilientCrossingTimestampNanos: Long?,
    val peerDeviceIds: List<String>,
    val distance: Double,
    val startType: String,
    val numberOfGates: Int,
    val isHost: Boolean
) {
    fun isValid(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return nowMillis - savedAtMillis in 0 until VALIDITY_MS
    }

    companion object {
        const val VALIDITY_MS = 5 * 60 * 1000L
    }
}

@Singleton
class SessionStateRecoveryService @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun saveActiveSession(state: PersistedSessionState) {
        runCatching {
            prefs.edit()
                .putString(KEY_ACTIVE_SESSION_STATE, json.encodeToString(state))
                .apply()
            Log.d(TAG, "Saved active session ${state.sessionId.take(8)} as ${state.role}")
        }.onFailure { error ->
            Log.w(TAG, "Failed to save active session state", error)
        }
    }

    fun getActiveSession(): PersistedSessionState? {
        val raw = prefs.getString(KEY_ACTIVE_SESSION_STATE, null) ?: return null
        val state = runCatching { json.decodeFromString<PersistedSessionState>(raw) }
            .onFailure { error ->
                Log.w(TAG, "Invalid active session state; clearing", error)
                clearActiveSession()
            }
            .getOrNull()
            ?: return null

        if (!state.isValid()) {
            Log.d(TAG, "Active session state expired; clearing")
            clearActiveSession()
            return null
        }

        return state
    }

    fun hasRecoverableSession(): Boolean = getActiveSession() != null

    fun clearActiveSession() {
        prefs.edit().remove(KEY_ACTIVE_SESSION_STATE).apply()
    }

    private companion object {
        const val TAG = "SessionStateRecovery"
        const val PREFS_NAME = "session_state_recovery"
        const val KEY_ACTIVE_SESSION_STATE = "active_session_state"
    }
}
