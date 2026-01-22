# Clock Synchronization - Detailed Specification

**Version:** 1.0
**Last Updated:** January 2026

This document provides the complete clock synchronization specification from the iOS app.

---

## 1. Overview

Clock sync uses an **NTP-style protocol** with RTT filtering and drift tracking. The goal is < 5ms uncertainty between devices.

### Offset Convention

```
t_remote ≈ t_local + offset

- Positive offset: local clock is BEHIND remote
- Negative offset: local clock is AHEAD of remote
```

To convert local time to remote time:
```kotlin
val remoteTime = localTime + clockOffset
```

---

## 2. Sync Parameters

### 2.1 Multipeer/WiFi Sync (Primary)

```kotlin
object MultipeerSyncConfig {
    // Full sync (initial pairing)
    const val FULL_SYNC_SAMPLES = 100
    const val FULL_SYNC_INTERVAL_MS = 50       // 20Hz
    const val FULL_SYNC_MAX_RTT_MS = 50
    const val FULL_SYNC_RTT_FILTER_PERCENTILE = 0.20  // Keep lowest 20%
    const val FULL_SYNC_MIN_VALID_SAMPLES = 10
    const val FULL_SYNC_TIMEOUT_MS = 2000

    // Mini sync (periodic refresh)
    const val MINI_SYNC_SAMPLES = 30
    const val MINI_SYNC_INTERVAL_MS = 100      // 10Hz
    const val MINI_SYNC_MAX_RTT_MS = 150
    const val MINI_SYNC_REFRESH_INTERVAL_S = 60  // Every 60 seconds
}
```

### 2.2 BLE Sync (Fallback)

```kotlin
object BleSyncConfig {
    // Full sync
    const val FULL_SYNC_SAMPLES = 80
    const val FULL_SYNC_INTERVAL_MS = 50       // 20Hz
    const val FULL_SYNC_MAX_RTT_MS = 200
    const val FULL_SYNC_RTT_FILTER_PERCENTILE = 0.15  // Tighter: 15%

    // Mini sync
    const val MINI_SYNC_SAMPLES = 25
    const val MINI_SYNC_INTERVAL_MS = 100
    const val MINI_SYNC_MAX_RTT_MS = 350
}
```

---

## 3. Quality Tiers

```kotlin
enum class SyncQuality(val maxUncertaintyMs: Double) {
    EXCELLENT(3.0),   // < 3ms - Professional grade
    GOOD(5.0),        // 3-5ms - Very reliable
    FAIR(10.0),       // 5-10ms - Acceptable
    POOR(15.0),       // 10-15ms - Marginal
    BAD(Double.MAX_VALUE)  // > 15ms - Unreliable
}

fun SyncQuality.Companion.fromUncertainty(uncertaintyMs: Double): SyncQuality {
    return when {
        uncertaintyMs < 3.0 -> EXCELLENT
        uncertaintyMs < 5.0 -> GOOD
        uncertaintyMs < 10.0 -> FAIR
        uncertaintyMs < 15.0 -> POOR
        else -> BAD
    }
}
```

**Minimum requirement**: `FAIR` or better for timing sessions.

---

## 4. NTP Algorithm

### 4.1 Single Sample Calculation

```
Device A (Client)                    Device B (Server)
────────────────                    ────────────────
      │                                   │
 T1 = now()                               │
      │────────── PING(T1) ──────────────►│
      │                                   │ T2 = receiveTime()
      │                                   │ T3 = sendTime()
      │◄───────── PONG(T1,T2,T3) ─────────│
 T4 = now()                               │
      │                                   │
```

```kotlin
data class SyncSample(
    val t1: Long,  // Client send time (nanos)
    val t2: Long,  // Server receive time (nanos)
    val t3: Long,  // Server send time (nanos)
    val t4: Long   // Client receive time (nanos)
) {
    // Round-trip time (excluding server processing)
    val rtt: Long get() = (t4 - t1) - (t3 - t2)

    // Clock offset (positive = client behind server)
    val offset: Long get() = ((t2 - t1) + (t3 - t4)) / 2

    // Uncertainty is half RTT (worst case)
    val uncertaintyNanos: Long get() = rtt / 2
    val uncertaintyMs: Double get() = uncertaintyNanos / 1_000_000.0
}
```

### 4.2 Multi-Sample Processing

```kotlin
class ClockSyncService {
    private val samples = mutableListOf<SyncSample>()

    fun addSample(sample: SyncSample) {
        // Filter by max RTT
        val maxRtt = if (isFullSync) FULL_SYNC_MAX_RTT_MS else MINI_SYNC_MAX_RTT_MS
        if (sample.rtt / 1_000_000 > maxRtt) {
            return  // Reject high-latency sample
        }
        samples.add(sample)
    }

    fun calculateOffset(): SyncResult? {
        if (samples.size < FULL_SYNC_MIN_VALID_SAMPLES) {
            return null  // Not enough samples
        }

        // Sort by RTT (lowest = most accurate)
        val sortedByRtt = samples.sortedBy { it.rtt }

        // Keep lowest 20% RTT samples
        val filterCount = (samples.size * FULL_SYNC_RTT_FILTER_PERCENTILE).toInt()
            .coerceAtLeast(FULL_SYNC_MIN_VALID_SAMPLES)
        val filtered = sortedByRtt.take(filterCount)

        // Calculate median offset (robust to outliers)
        val offsets = filtered.map { it.offset }.sorted()
        val medianOffset = offsets[offsets.size / 2]

        // Calculate uncertainty from filtered samples
        val maxUncertainty = filtered.maxOf { it.uncertaintyMs }

        return SyncResult(
            offsetNanos = medianOffset,
            uncertaintyMs = maxUncertainty,
            samplesUsed = filtered.size,
            quality = SyncQuality.fromUncertainty(maxUncertainty)
        )
    }
}
```

---

## 5. Drift Tracking

For long sessions (> 30 seconds), track clock drift over time.

```kotlin
class DriftTracker {
    private data class OffsetSample(
        val timestamp: Long,    // Local time when measured
        val offset: Long        // Measured offset at that time
    )

    private val history = mutableListOf<OffsetSample>()
    private val maxHistoryDuration = 10 * 60 * 1_000_000_000L  // 10 minutes in nanos

    fun addMeasurement(localTime: Long, offset: Long) {
        // Prune old samples
        val cutoff = localTime - maxHistoryDuration
        history.removeAll { it.timestamp < cutoff }

        history.add(OffsetSample(localTime, offset))
    }

    /**
     * Returns drift rate in nanoseconds per second.
     * Positive = remote getting further ahead.
     * Requires at least 30 seconds of data.
     */
    fun calculateDriftRate(): Double? {
        if (history.size < 2) return null

        val duration = history.last().timestamp - history.first().timestamp
        if (duration < 30_000_000_000L) return null  // Need 30+ seconds

        // Linear regression: offset = baseOffset + driftRate * time
        val n = history.size
        val sumX = history.sumOf { it.timestamp.toDouble() }
        val sumY = history.sumOf { it.offset.toDouble() }
        val sumXY = history.sumOf { it.timestamp.toDouble() * it.offset }
        val sumX2 = history.sumOf { it.timestamp.toDouble() * it.timestamp }

        val driftRate = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)

        // Convert from nanos/nano to nanos/second
        return driftRate * 1_000_000_000.0
    }

    /**
     * Predict offset at a future time, accounting for drift.
     */
    fun predictOffset(atTime: Long): Long {
        val lastSample = history.lastOrNull() ?: return 0
        val driftRate = calculateDriftRate() ?: return lastSample.offset

        val elapsed = atTime - lastSample.timestamp
        val driftCorrection = (driftRate * elapsed / 1_000_000_000.0).toLong()

        return lastSample.offset + driftCorrection
    }
}
```

---

## 6. Sync Protocol Messages

### 6.1 JSON Format

```json
// PING
{
  "type": "clock_sync_ping",
  "timestamp": 1706000010000,
  "deviceId": "abc-123",
  "payload": {
    "pingId": 42,
    "t1": 1706000010000123456,
    "requesterId": "abc-123"
  }
}

// PONG
{
  "type": "clock_sync_pong",
  "timestamp": 1706000010005,
  "deviceId": "def-789",
  "payload": {
    "pingId": 42,
    "t1": 1706000010000123456,
    "t2": 1706000010002345678,
    "t3": 1706000010003456789,
    "requesterId": "abc-123"
  }
}

// RESULT
{
  "type": "clock_sync_result",
  "timestamp": 1706000020000,
  "deviceId": "abc-123",
  "payload": {
    "offsetNanos": -5000000,
    "rttNanos": 10000000,
    "uncertaintyMs": 2.5,
    "quality": "good",
    "samplesUsed": 18
  }
}
```

### 6.2 Binary Format (BLE Optimization)

For low-latency BLE sync, use compact binary:

```
PING (10 bytes):
┌─────┬─────┬────────────────────┐
│Type │SeqNo│        T1          │
│ 1B  │ 1B  │        8B          │
└─────┴─────┴────────────────────┘

PONG (26 bytes):
┌─────┬─────┬────────────────────┬────────────────────┬────────────────────┐
│Type │SeqNo│        T1          │        T2          │        T3          │
│ 1B  │ 1B  │        8B          │        8B          │        8B          │
└─────┴─────┴────────────────────┴────────────────────┴────────────────────┘

Type: 0x01 = PING, 0x02 = PONG
SeqNo: 0-255, wraps
T1/T2/T3: int64, little-endian, nanoseconds
```

---

## 7. Full Sync Flow

```kotlin
class ClockSyncService(
    private val transport: Transport
) {
    suspend fun performFullSync(): SyncResult {
        val syncService = ClockSyncCalculator()

        // Send 100 pings at 20Hz
        repeat(FULL_SYNC_SAMPLES) { index ->
            val t1 = System.nanoTime()
            val pingId = index

            // Send ping
            transport.send(TimingMessage.ClockSyncPing(pingId, t1))

            // Wait for pong (with timeout)
            val pong = withTimeoutOrNull(FULL_SYNC_TIMEOUT_MS) {
                transport.incomingMessages
                    .filterIsInstance<TimingMessage.ClockSyncPong>()
                    .first { it.pingId == pingId }
            }

            if (pong != null) {
                val t4 = System.nanoTime()
                syncService.addSample(SyncSample(
                    t1 = pong.t1,
                    t2 = pong.t2,
                    t3 = pong.t3,
                    t4 = t4
                ))
            }

            // Wait between pings
            delay(FULL_SYNC_INTERVAL_MS)
        }

        return syncService.calculateOffset()
            ?: throw SyncException("Failed to achieve sync - not enough valid samples")
    }
}
```

---

## 8. Applying Offset to Timing Events

### 8.1 Start Event from Remote Device

```kotlin
// When receiving a start event from the other phone:
fun handleRemoteStartEvent(event: StartEvent) {
    // Convert remote timestamp to local time
    val localStartTime = event.crossingTimeNanos - clockOffset

    // Store for split calculation
    this.startTimeLocalNanos = localStartTime
}
```

### 8.2 Split Time Calculation

```kotlin
fun calculateSplitTime(
    startEvent: CrossingEvent,   // From start phone
    finishEvent: CrossingEvent   // From finish phone (local)
): SplitTimeResult {
    // Adjust start time to local clock reference
    val adjustedStartTime = startEvent.crossingTimeNanos - clockOffset

    // Finish time is already in local reference
    val finishTime = finishEvent.crossingTimeNanos

    // Split time in nanoseconds
    val splitNanos = finishTime - adjustedStartTime

    // Combine uncertainties
    val totalUncertaintyMs = sqrt(
        startEvent.uncertaintyMs.pow(2) +
        finishEvent.uncertaintyMs.pow(2) +
        syncUncertaintyMs.pow(2)
    )

    return SplitTimeResult(
        splitNanos = splitNanos,
        splitMs = splitNanos / 1_000_000.0,
        splitSeconds = splitNanos / 1_000_000_000.0,
        uncertaintyMs = totalUncertaintyMs
    )
}
```

---

## 9. Clock References by Platform

### Android

```kotlin
// Primary: Monotonic, survives deep sleep
val timestamp = SystemClock.elapsedRealtimeNanos()

// Alternative: Higher resolution but may jump on suspend
val timestamp = System.nanoTime()
```

**Recommendation**: Use `elapsedRealtimeNanos()` for all timing.

### iOS

```swift
// Monotonic clock
let timestamp = mach_absolute_time()

// Convert to nanoseconds
var timebaseInfo = mach_timebase_info_data_t()
mach_timebase_info(&timebaseInfo)
let nanos = timestamp * UInt64(timebaseInfo.numer) / UInt64(timebaseInfo.denom)
```

### Cross-Platform Compatibility

Both clocks are:
- Monotonic (never go backwards)
- Independent of system time changes
- Nanosecond precision

The offset calculation handles the difference automatically - we only care about relative timing between devices, not absolute time.

---

## 10. Error Handling

### 10.1 Sync Failures

```kotlin
sealed class SyncException : Exception() {
    object Timeout : SyncException()
    object InsufficientSamples : SyncException()
    object QualityTooLow : SyncException()
    data class TransportError(val cause: Throwable) : SyncException()
}

// Retry strategy
suspend fun syncWithRetry(maxAttempts: Int = 3): SyncResult {
    repeat(maxAttempts) { attempt ->
        try {
            val result = performFullSync()
            if (result.quality >= SyncQuality.FAIR) {
                return result
            }
            // Quality too low, retry
        } catch (e: SyncException.Timeout) {
            // Timeout, retry
        }
        delay(1000L * (attempt + 1))  // Exponential backoff
    }
    throw SyncException.InsufficientSamples
}
```

### 10.2 Quality Degradation

If sync quality drops during a session:

1. Show warning to user ("Sync quality degraded")
2. Increase uncertainty in split time display
3. Attempt mini-sync in background
4. If critical (POOR/BAD), prompt for re-sync

---

## 11. Testing Considerations

### 11.1 Simulator Testing

Simulated clocks should add artificial offset and jitter:

```kotlin
class MockSyncTransport : Transport {
    var simulatedOffset = 5_000_000L  // 5ms offset
    var simulatedJitter = 2_000_000L  // ±2ms jitter

    override suspend fun send(message: TimingMessage) {
        if (message is TimingMessage.ClockSyncPing) {
            // Simulate network delay + jitter
            delay(Random.nextLong(5, 15))

            val jitter = Random.nextLong(-simulatedJitter, simulatedJitter)
            val pong = TimingMessage.ClockSyncPong(
                pingId = message.pingId,
                t1 = message.t1,
                t2 = message.t1 + simulatedOffset + jitter,
                t3 = message.t1 + simulatedOffset + jitter + 1_000_000
            )
            incomingMessages.emit(pong)
        }
    }
}
```

### 11.2 Real-World Testing

Test sync quality on:
- Same WiFi network (best case)
- Different WiFi networks via cloud
- BLE direct connection
- Mixed: one on WiFi, one on cellular

Expected quality:
- Same WiFi: EXCELLENT (< 3ms)
- BLE direct: GOOD (3-5ms)
- Cross-network: FAIR-POOR (5-15ms)
