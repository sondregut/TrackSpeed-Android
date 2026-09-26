package com.trackspeed.android.cloud

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable per-device identifier shared by every BLE and Supabase service.
 *
 * Android restores SharedPreferences during device migration. A bare UUID in
 * preferences can therefore be restored onto a second phone, making two
 * physical timing gates advertise the same identity. We bind the UUID to a
 * hash of ANDROID_ID and rotate it when a future backup is restored on a
 * different device. Existing installations without a fingerprint are adopted
 * once so upgrades do not unnecessarily change identity.
 */
@Singleton
class DeviceIdProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val deviceId: String by lazy { getOrCreate() }

    @SuppressLint("HardwareIds", "ApplySharedPref")
    private fun getOrCreate(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        val currentFingerprint = deviceFingerprint(androidId.orEmpty())
        val resolution = resolveDeviceIdentity(
            existingDeviceId = prefs.getString(KEY_DEVICE_ID, null),
            storedFingerprint = prefs.getString(KEY_DEVICE_FINGERPRINT, null),
            currentFingerprint = currentFingerprint,
            generatedDeviceId = UUID.randomUUID().toString()
        )

        if (resolution.mustPersist) {
            // commit() is intentional: legacy callers still read this shared
            // preference directly, so the canonical identity must be visible
            // before another service starts a timing session.
            val persisted = prefs.edit()
                .putString(KEY_DEVICE_ID, resolution.deviceId)
                .putString(KEY_DEVICE_FINGERPRINT, currentFingerprint)
                .commit()
            if (!persisted) {
                Log.e(TAG, "Failed to persist canonical device identity")
            }
        }
        if (resolution.rotatedAfterRestore) {
            Log.w(TAG, "Regenerated device identity after cross-device backup restore")
        }
        return resolution.deviceId
    }

    companion object {
        private const val TAG = "DeviceIdProvider"
        private const val PREFS_NAME = "trackspeed"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_FINGERPRINT = "device_identity_fingerprint"

        internal data class Resolution(
            val deviceId: String,
            val mustPersist: Boolean,
            val rotatedAfterRestore: Boolean
        )

        internal fun resolveDeviceIdentity(
            existingDeviceId: String?,
            storedFingerprint: String?,
            currentFingerprint: String,
            generatedDeviceId: String
        ): Resolution {
            val existing = existingDeviceId?.takeIf { it.isNotBlank() }
            val restoredOnDifferentDevice = existing != null &&
                !storedFingerprint.isNullOrBlank() &&
                storedFingerprint != currentFingerprint
            return when {
                existing == null -> Resolution(
                    deviceId = generatedDeviceId,
                    mustPersist = true,
                    rotatedAfterRestore = false
                )
                restoredOnDifferentDevice -> Resolution(
                    deviceId = generatedDeviceId,
                    mustPersist = true,
                    rotatedAfterRestore = true
                )
                storedFingerprint != currentFingerprint -> Resolution(
                    deviceId = existing,
                    mustPersist = true,
                    rotatedAfterRestore = false
                )
                else -> Resolution(
                    deviceId = existing,
                    mustPersist = false,
                    rotatedAfterRestore = false
                )
            }
        }

        internal fun deviceFingerprint(androidId: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(androidId.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }
        }
    }
}
