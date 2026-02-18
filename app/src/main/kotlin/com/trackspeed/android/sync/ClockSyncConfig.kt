package com.trackspeed.android.sync

import java.util.UUID

/**
 * Configuration constants for BLE clock synchronization.
 *
 * Based on docs/protocols/CLOCK_SYNC_DETAILS.md
 */
object ClockSyncConfig {
    // BLE Service and Characteristic UUIDs
    // Must match iOS BluetoothTransport exactly for cross-platform compatibility
    val SERVICE_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567890")

    // TX characteristic (host -> joiner): NOTIFY + READ
    // iOS name: txCharacteristicUUID
    val PING_CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567891")

    // RX characteristic (joiner -> host): WRITE + WRITE_WITHOUT_RESPONSE
    // iOS name: rxCharacteristicUUID
    val PONG_CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567892")

    // Full sync parameters (initial pairing)
    const val FULL_SYNC_SAMPLES = 100              // 100 pings for maximum accuracy
    const val FULL_SYNC_INTERVAL_MS = 50L         // 20Hz
    const val FULL_SYNC_MAX_RTT_MS = 200L
    const val FULL_SYNC_RTT_FILTER_PERCENTILE = 0.15  // Tighter for BLE: 15%
    const val FULL_SYNC_MIN_VALID_SAMPLES = 10
    const val FULL_SYNC_TIMEOUT_MS = 8000L

    // Mini sync parameters (periodic refresh during active race)
    const val MINI_SYNC_SAMPLES = 30
    const val MINI_SYNC_INTERVAL_MS = 100L        // 10Hz
    const val MINI_SYNC_MAX_RTT_MS = 350L
    const val MINI_SYNC_REFRESH_INTERVAL_S = 60L  // Re-sync every 60 seconds

    // Auto-retry
    const val MAX_SYNC_RETRIES = 3
    const val RETRY_DELAY_MS = 1000L

    // BLE MTU - JSON messages are ~250-400 bytes; request 512 to avoid fragmentation.
    // Most modern phones support this. BLE stack handles segmentation automatically.
    const val PREFERRED_MTU = 512

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
