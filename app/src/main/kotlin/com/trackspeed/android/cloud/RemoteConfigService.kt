package com.trackspeed.android.cloud

import android.content.Context
import android.util.Log
import com.trackspeed.android.BuildConfig
import com.trackspeed.android.detection.ReplicaDetectionConfiguration
import com.trackspeed.android.detection.ReplicaDetectionConfigurationStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class RemoteConfigState(
    val values: Map<String, String> = emptyMap(),
    val isLoaded: Boolean = false,
    val lastError: String? = null
) {
    val isKillSwitchEnabled: Boolean
        get() = isFeatureEnabled("kill_switch_enabled")

    val isMaintenanceMode: Boolean
        get() = isFeatureEnabled("maintenance_mode")

    val minSupportedVersion: String?
        get() = values["min_supported_version"]?.takeIf { it.isNotBlank() }

    val paywallVariant: String
        get() = values["paywall_variant"] ?: "default"

    fun isFeatureEnabled(key: String): Boolean {
        return when (values[key]?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            else -> false
        }
    }

    fun string(key: String, defaultValue: String? = null): String? {
        return values[key] ?: defaultValue
    }
}

@Singleton
class RemoteConfigService @Inject constructor(
    @ApplicationContext context: Context,
    private val supabase: SupabaseClient,
    private val deviceIdProvider: DeviceIdProvider
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(RemoteConfigState())
    val state: StateFlow<RemoteConfigState> = _state.asStateFlow()

    init {
        val hadCache = prefs.contains(CACHE_KEY)
        val cached = loadCachedConfig().toMutableMap()
        val hadDetectionProfile = cached.containsKey(ReplicaDetectionConfiguration.REMOTE_CONFIG_KEY)
        val validatedProfile = applyDetectionConfiguration(
            values = cached,
            preserveCurrentOnInvalid = false,
            previousValues = emptyMap(),
            origin = "cache"
        )
        if (hadDetectionProfile && validatedProfile == null) {
            cached.remove(ReplicaDetectionConfiguration.REMOTE_CONFIG_KEY)
            persistValues(cached)
        }
        _state.value = RemoteConfigState(values = cached, isLoaded = hadCache)
    }

    suspend fun refresh() {
        refreshMutex.withLock { refreshLocked() }
    }

    suspend fun refreshIfStale(minimumIntervalMillis: Long = REFRESH_INTERVAL_MILLIS) {
        refreshMutex.withLock {
            val lastRefresh = prefs.getLong(LAST_REFRESH_KEY, 0L)
            if (lastRefresh > 0L && System.currentTimeMillis() - lastRefresh < minimumIntervalMillis) {
                return
            }
            refreshLocked()
        }
    }

    private suspend fun refreshLocked() {
        try {
            val records = supabase.postgrest["remote_config"]
                .select()
                .decodeList<RemoteConfigRecord>()

            val previousValues = _state.value.values
            val values = records.associate { it.key to it.value }.toMutableMap()
            val detectionValueToCache = applyDetectionConfiguration(
                values = values,
                preserveCurrentOnInvalid = true,
                previousValues = previousValues,
                origin = "network"
            )
            if (detectionValueToCache != null) {
                values[ReplicaDetectionConfiguration.REMOTE_CONFIG_KEY] = detectionValueToCache
            } else {
                values.remove(ReplicaDetectionConfiguration.REMOTE_CONFIG_KEY)
            }
            persistValues(values)
            prefs.edit().putLong(LAST_REFRESH_KEY, System.currentTimeMillis()).apply()

            _state.value = RemoteConfigState(values = values, isLoaded = true)
            Log.d(TAG, "Refreshed ${values.size} remote config values")
        } catch (e: Exception) {
            Log.w(TAG, "Remote config refresh failed; using cached values: ${e.safeCloudErrorCode()}")
            _state.update { current ->
                current.copy(
                    isLoaded = current.isLoaded || current.values.isNotEmpty(),
                    lastError = e.safeCloudErrorCode()
                )
            }
        }
    }

    fun cachedString(key: String, defaultValue: String? = null): String? {
        return state.value.string(key, defaultValue)
    }

    fun isFeatureEnabled(key: String): Boolean {
        return state.value.isFeatureEnabled(key)
    }

    private fun loadCachedConfig(): Map<String, String> {
        val raw = prefs.getString(CACHE_KEY, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }
            .onFailure { Log.w(TAG, "Invalid cached remote config; ignoring: ${it.safeCloudErrorCode()}") }
            .getOrDefault(emptyMap())
    }

    private fun persistValues(values: Map<String, String>) {
        prefs.edit().putString(CACHE_KEY, json.encodeToString(values)).apply()
    }

    /**
     * Apply only a strictly validated profile. Invalid network data preserves
     * the previous known-good profile; invalid cache data falls back to the
     * reviewed bundled parameters.
     */
    private fun applyDetectionConfiguration(
        values: Map<String, String>,
        preserveCurrentOnInvalid: Boolean,
        previousValues: Map<String, String>,
        origin: String
    ): String? {
        val profileJson = values[ReplicaDetectionConfiguration.REMOTE_CONFIG_KEY]
        if (profileJson == null) {
            ReplicaDetectionConfigurationStore.replace(ReplicaDetectionConfiguration.bundled)
            Log.i(TAG, "Detection profile source=bundled reason=missing origin=$origin")
            return null
        }

        return try {
            val bucket = ReplicaDetectionConfiguration.stableRolloutBucket(deviceIdProvider.deviceId)
            val resolved = ReplicaDetectionConfiguration.resolveRemoteJson(
                rawJson = profileJson,
                appVersion = BuildConfig.VERSION_NAME,
                rolloutBucket = bucket
            )
            val selected = resolved ?: ReplicaDetectionConfiguration.bundled
            ReplicaDetectionConfigurationStore.replace(selected)
            Log.i(
                TAG,
                "Detection profile revision=${selected.revision} source=${selected.source} " +
                    "origin=$origin bucket=$bucket"
            )
            profileJson
        } catch (error: Exception) {
            Log.e(TAG, "Rejected detection profile origin=$origin: ${error.message}")
            if (preserveCurrentOnInvalid) {
                previousValues[ReplicaDetectionConfiguration.REMOTE_CONFIG_KEY]
            } else {
                ReplicaDetectionConfigurationStore.replace(ReplicaDetectionConfiguration.bundled)
                null
            }
        }
    }

    @Serializable
    private data class RemoteConfigRecord(
        val key: String,
        val value: String,
        @SerialName("updated_at") val updatedAt: String? = null
    )

    private companion object {
        const val TAG = "RemoteConfigService"
        const val PREFS_NAME = "remote_config"
        const val CACHE_KEY = "remote_config_cache"
        const val LAST_REFRESH_KEY = "remote_config_last_refresh"
        const val REFRESH_INTERVAL_MILLIS = 15 * 60 * 1000L
    }
}
