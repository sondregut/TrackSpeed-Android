package com.trackspeed.android.sync

import java.util.UUID

/**
 * Configuration constants for BLE clock synchronization.
 *
 * Based on docs/protocols/CLOCK_SYNC_DETAILS.md
 */
object ClockSyncConfig {
    // BLE Service and Characteristic UUIDs
    // Must match iOS app exactly for cross-platform compatibility
    val SERVICE_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567890")
    val PING_CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567891")
    val PONG_CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567892")

    // Full sync parameters (initial pairing)
    const val FULL_SYNC_SAMPLES = 80
    const val FULL_SYNC_INTERVAL_MS = 50L         // 20Hz
    const val FULL_SYNC_MAX_RTT_MS = 200L
    const val FULL_SYNC_RTT_FILTER_PERCENTILE = 0.15  // Tighter for BLE: 15%
    const val FULL_SYNC_MIN_VALID_SAMPLES = 10
    const val FULL_SYNC_TIMEOUT_MS = 5000L

    // Mini sync parameters (periodic refresh)
    const val MINI_SYNC_SAMPLES = 25
    const val MINI_SYNC_INTERVAL_MS = 100L        // 10Hz
    const val MINI_SYNC_MAX_RTT_MS = 350L

    // Binary message types
    const val MSG_TYPE_PING: Byte = 0x01
    const val MSG_TYPE_PONG: Byte = 0x02

    // Binary message sizes
    const val PING_MESSAGE_SIZE = 10   // 1B type + 1B seq + 8B timestamp
    const val PONG_MESSAGE_SIZE = 26   // 1B type + 1B seq + 8B t1 + 8B t2 + 8B t3

    // Validation gate thresholds (Photo Finish playbook)
    const val PRECISION_MODE_MIN_RTT_MS = 30.0      // Min RTT must be < 30ms
    const val PRECISION_MODE_MAX_JITTER_MS = 10.0   // p95-p50 must be < 10ms
    val PRECISION_MODE_MIN_QUALITY = SyncQuality.FAIR  // Must be FAIR or better

    // Sync age warnings
    const val SYNC_STALE_WARNING_SECONDS = 600L    // Warn after 10 minutes
}

/**
 * Sync quality tiers based on uncertainty.
 */
enum class SyncQuality(val maxUncertaintyMs: Double) {
    EXCELLENT(3.0),   // < 3ms - Professional grade
    GOOD(5.0),        // 3-5ms - Very reliable
    FAIR(10.0),       // 5-10ms - Acceptable
    POOR(15.0),       // 10-15ms - Marginal
    BAD(Double.MAX_VALUE);  // > 15ms - Unreliable

    companion object {
        fun fromUncertainty(uncertaintyMs: Double): SyncQuality {
            return when {
                uncertaintyMs < 3.0 -> EXCELLENT
                uncertaintyMs < 5.0 -> GOOD
                uncertaintyMs < 10.0 -> FAIR
                uncertaintyMs < 15.0 -> POOR
                else -> BAD
            }
        }
    }
}
