package com.trackspeed.android.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    private object Keys {
        val DEFAULT_DISTANCE = doublePreferencesKey("default_distance")
        val START_TYPE = stringPreferencesKey("start_type")
        val DETECTION_SENSITIVITY = floatPreferencesKey("detection_sensitivity")
        val SPEED_UNIT = stringPreferencesKey("speed_unit")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val PREFERRED_FPS = intPreferencesKey("preferred_fps")
    }

    object Defaults {
        const val DISTANCE = 60.0
        const val START_TYPE = "standing"
        const val SENSITIVITY = 0.5f
        const val SPEED_UNIT = "m/s"
        const val DARK_MODE = true
        const val ONBOARDING_COMPLETED = false
        const val PREFERRED_FPS = 120
    }

    // --- Flows ---

    val defaultDistance: Flow<Double> = dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_DISTANCE] ?: Defaults.DISTANCE
    }

    val startType: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.START_TYPE] ?: Defaults.START_TYPE
    }

    val detectionSensitivity: Flow<Float> = dataStore.data.map { prefs ->
        prefs[Keys.DETECTION_SENSITIVITY] ?: Defaults.SENSITIVITY
    }

    val speedUnit: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SPEED_UNIT] ?: Defaults.SPEED_UNIT
    }

    val darkMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.DARK_MODE] ?: Defaults.DARK_MODE
    }

    val onboardingCompleted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: Defaults.ONBOARDING_COMPLETED
    }

    val preferredFps: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.PREFERRED_FPS] ?: Defaults.PREFERRED_FPS
    }

    // --- Update functions ---

    suspend fun setDefaultDistance(distance: Double) {
        dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_DISTANCE] = distance
        }
    }

    suspend fun setStartType(startType: String) {
        dataStore.edit { prefs ->
            prefs[Keys.START_TYPE] = startType
        }
    }

    suspend fun setDetectionSensitivity(sensitivity: Float) {
        dataStore.edit { prefs ->
            prefs[Keys.DETECTION_SENSITIVITY] = sensitivity
        }
    }

    suspend fun setSpeedUnit(unit: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SPEED_UNIT] = unit
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DARK_MODE] = enabled
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setPreferredFps(fps: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.PREFERRED_FPS] = fps
        }
    }

    suspend fun getPreferredFpsOnce(): Int {
        return dataStore.data.first()[Keys.PREFERRED_FPS] ?: Defaults.PREFERRED_FPS
    }
}
