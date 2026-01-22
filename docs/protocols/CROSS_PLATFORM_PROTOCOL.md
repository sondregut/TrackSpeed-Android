# Cross-Platform Communication Protocol

**Version:** 1.0
**Last Updated:** January 2026

This document defines the communication protocol between TrackSpeed iOS and Android apps for multi-device timing sessions.

---

## 1. Overview

### 1.1 Communication Modes

| Mode | Transport | Use Case | Range |
|------|-----------|----------|-------|
| **Local** | BLE | Devices at same track | ~30-50m |
| **Remote** | Supabase Realtime | Devices anywhere | Unlimited |
| **Hybrid** | BLE + Supabase | Clock sync local, events remote | Mixed |

### 1.2 Session Phases

```
┌─────────────────────────────────────────────────────────────┐
│                      SESSION LIFECYCLE                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. DISCOVERY       Find other devices (BLE or session code)│
│         │                                                   │
│         ▼                                                   │
│  2. PAIRING         Establish connection, exchange info     │
│         │                                                   │
│         ▼                                                   │
│  3. CLOCK SYNC      NTP-style synchronization (requires BLE)│
│         │                                                   │
│         ▼                                                   │
│  4. CALIBRATION     Each device calibrates its gate         │
│         │                                                   │
│         ▼                                                   │
│  5. ARMED           All gates ready, waiting for athlete    │
│         │                                                   │
│         ▼                                                   │
│  6. TIMING          Detect crossings, exchange events       │
│         │                                                   │
│         ▼                                                   │
│  7. RESULTS         Calculate split time, display           │
│         │                                                   │
│         ▼                                                   │
│  8. RESET           Ready for next run                      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. BLE Protocol

### 2.1 GATT Service Definition

```
SERVICE: TrackSpeed Timing Service
UUID: 12345678-0000-1000-8000-00805F9B34FB

CHARACTERISTICS:

1. Timing Messages (Read/Write/Notify)
   UUID: 12345678-0001-1000-8000-00805F9B34FB
   Properties: Read, Write, Notify
   Description: Primary channel for timing protocol messages

2. Device Info (Read)
   UUID: 12345678-0002-1000-8000-00805F9B34FB
   Properties: Read
   Description: Device identification and capabilities
   Format: JSON { deviceId, deviceName, platform, appVersion }

3. Clock Sync (Write/Notify)
   UUID: 12345678-0003-1000-8000-00805F9B34FB
   Properties: Write, Notify
   Description: Dedicated channel for low-latency clock sync
   Format: Binary (24 bytes: type[1] + t1[8] + t2[8] + t3[8])
```

### 2.2 BLE Connection Flow

```
┌──────────────┐                     ┌──────────────┐
│    DEVICE A  │                     │    DEVICE B  │
│  (Start Gate)│                     │ (Finish Gate)│
└──────┬───────┘                     └──────┬───────┘
       │                                    │
       │ 1. Start Advertising               │
       │    (Service UUID)                  │
       │◄───────────────────────────────────│
       │                                    │
       │ 2. Scan for Service                │
       │────────────────────────────────────►
       │                                    │
       │ 3. Connect                         │
       │────────────────────────────────────►
       │                                    │
       │ 4. Discover Services               │
       │────────────────────────────────────►
       │                                    │
       │ 5. Read Device Info                │
       │────────────────────────────────────►
       │                    { deviceId, ... }
       │◄───────────────────────────────────│
       │                                    │
       │ 6. Subscribe to Notifications      │
       │────────────────────────────────────►
       │                                    │
       │ 7. Exchange Session Info           │
       │◄──────────────────────────────────►│
       │                                    │
       │         [CONNECTED]                │
       │                                    │
```

### 2.3 MTU Negotiation

```
Default MTU: 23 bytes (20 bytes payload)
Requested MTU: 512 bytes (509 bytes payload)

Negotiation:
1. Central requests MTU change: requestMtu(512)
2. Peripheral responds with supported MTU
3. Use minimum of requested and supported

For messages > MTU:
- Fragment message into chunks
- Include sequence number and total count
- Reassemble on receiver
```

---

## 3. Message Protocol

### 3.1 Message Format (JSON)

All messages use JSON encoding for cross-platform compatibility.

```json
{
  "type": "string",           // Message type identifier
  "timestamp": 1234567890,    // Unix timestamp (milliseconds)
  "deviceId": "string",       // Sender device UUID
  "payload": { }              // Type-specific payload
}
```

### 3.2 Message Types

#### Session Management

```json
// SESSION_CREATE - Host creates new session
{
  "type": "session_create",
  "timestamp": 1706000000000,
  "deviceId": "abc-123",
  "payload": {
    "sessionId": "sess-456",
    "sessionCode": "123456",      // 6-digit join code
    "hostRole": "start",          // Host's gate role
    "settings": {
      "distance": 40,             // meters (optional)
      "startType": "flying"       // flying, touchRelease, audioGun
    }
  }
}

// SESSION_JOIN - Device joins session
{
  "type": "session_join",
  "timestamp": 1706000001000,
  "deviceId": "def-789",
  "payload": {
    "sessionId": "sess-456",
    "requestedRole": "finish"
  }
}

// SESSION_JOIN_ACCEPTED
{
  "type": "session_join_accepted",
  "timestamp": 1706000002000,
  "deviceId": "abc-123",
  "payload": {
    "sessionId": "sess-456",
    "assignedRole": "finish",
    "devices": [
      { "deviceId": "abc-123", "role": "start", "name": "iPhone 15" },
      { "deviceId": "def-789", "role": "finish", "name": "Pixel 8" }
    ]
  }
}

// SESSION_END
{
  "type": "session_end",
  "timestamp": 1706001000000,
  "deviceId": "abc-123",
  "payload": {
    "sessionId": "sess-456",
    "reason": "user_ended"       // user_ended, timeout, error
  }
}
```

#### Clock Synchronization

```json
// CLOCK_SYNC_PING - Initiator sends
{
  "type": "clock_sync_ping",
  "timestamp": 1706000010000,
  "deviceId": "abc-123",
  "payload": {
    "sequence": 1,
    "t1": 1706000010000123456    // Nanoseconds (monotonic clock)
  }
}

// CLOCK_SYNC_PONG - Responder replies
{
  "type": "clock_sync_pong",
  "timestamp": 1706000010005,
  "deviceId": "def-789",
  "payload": {
    "sequence": 1,
    "t1": 1706000010000123456,   // Echo back
    "t2": 1706000010002345678,   // Receive time (nanoseconds)
    "t3": 1706000010003456789    // Send time (nanoseconds)
  }
}

// CLOCK_SYNC_RESULT - Sync quality report
{
  "type": "clock_sync_result",
  "timestamp": 1706000020000,
  "deviceId": "abc-123",
  "payload": {
    "offsetNanos": -5000000,     // Device B is 5ms behind
    "rttNanos": 10000000,        // 10ms round trip
    "uncertaintyMs": 2.5,        // ±2.5ms uncertainty
    "quality": "good",           // excellent, good, fair, poor
    "samplesUsed": 5
  }
}
```

#### Gate Status

```json
// GATE_CALIBRATING
{
  "type": "gate_calibrating",
  "timestamp": 1706000030000,
  "deviceId": "abc-123",
  "payload": {
    "role": "start",
    "progress": 0.5              // 0.0 to 1.0
  }
}

// GATE_READY
{
  "type": "gate_ready",
  "timestamp": 1706000035000,
  "deviceId": "abc-123",
  "payload": {
    "role": "start",
    "gatePosition": 0.45,        // Normalized 0-1
    "capturedFps": 240
  }
}

// GATE_NOT_READY
{
  "type": "gate_not_ready",
  "timestamp": 1706000040000,
  "deviceId": "abc-123",
  "payload": {
    "role": "start",
    "reason": "calibration_failed"
  }
}
```

#### Timing Events

```json
// CROSSING_EVENT - Gate crossing detected
{
  "type": "crossing_event",
  "timestamp": 1706000100000,
  "deviceId": "abc-123",
  "payload": {
    "role": "start",
    "crossingTimeNanos": 1706000100123456789,  // Local monotonic time
    "clockOffsetNanos": -5000000,              // From clock sync
    "frameIndex": 1234,
    "occupancyAtTrigger": 0.42,
    "interpolationOffsetMs": 0.73,             // Sub-frame interpolation
    "uncertaintyMs": 1.2
  }
}

// SPLIT_TIME_CALCULATED - Result computed
{
  "type": "split_time_calculated",
  "timestamp": 1706000105000,
  "deviceId": "abc-123",
  "payload": {
    "runId": "run-001",
    "splitTimeMs": 4827.3,        // 4.8273 seconds
    "startTimeNanos": 1706000095123456789,
    "finishTimeNanos": 1706000100123456789,
    "totalUncertaintyMs": 2.4
  }
}
```

#### Heartbeat & Keep-Alive

```json
// HEARTBEAT - Periodic keep-alive
{
  "type": "heartbeat",
  "timestamp": 1706000200000,
  "deviceId": "abc-123",
  "payload": {
    "state": "armed",            // idle, calibrating, armed, timing
    "batteryLevel": 0.85,
    "thermalState": "nominal"    // nominal, fair, serious, critical
  }
}
```

#### Reset & Control

```json
// RESET_FOR_NEXT_RUN
{
  "type": "reset_for_next_run",
  "timestamp": 1706000300000,
  "deviceId": "abc-123",
  "payload": {
    "keepCalibration": true
  }
}

// ARM_GATES - All gates ready, start timing
{
  "type": "arm_gates",
  "timestamp": 1706000400000,
  "deviceId": "abc-123",
  "payload": {
    "runNumber": 2
  }
}
```

### 3.3 Binary Format (for Clock Sync over BLE)

For lowest latency clock sync, use compact binary format:

```
Offset  Size  Field
──────────────────────
0       1     Message Type (0x01=PING, 0x02=PONG)
1       1     Sequence Number (0-255)
2       8     T1 (int64, nanoseconds)
10      8     T2 (int64, nanoseconds) - PONG only
18      8     T3 (int64, nanoseconds) - PONG only
──────────────────────
Total: 2 bytes (PING) or 26 bytes (PONG)
```

---

## 4. Clock Synchronization Algorithm

### 4.1 NTP-Style Protocol

```
Device A (Client)                     Device B (Server)
─────────────────                     ─────────────────
     │                                      │
     │  T1 = current_time()                 │
     │                                      │
     │──────────── PING(T1) ───────────────►│
     │                                      │
     │                          T2 = receive_time()
     │                          T3 = send_time()
     │                                      │
     │◄─────── PONG(T1, T2, T3) ────────────│
     │                                      │
     │  T4 = receive_time()                 │
     │                                      │
     │  RTT = (T4 - T1) - (T3 - T2)         │
     │  Offset = ((T2 - T1) + (T3 - T4)) / 2│
     │                                      │
```

### 4.2 Implementation

```kotlin
// Android
fun calculateClockOffset(t1: Long, t2: Long, t3: Long, t4: Long): SyncResult {
    // Round-trip time (excluding server processing)
    val rtt = (t4 - t1) - (t3 - t2)

    // Clock offset (positive = server is ahead)
    val offset = ((t2 - t1) + (t3 - t4)) / 2

    // Uncertainty is half RTT (worst case)
    val uncertaintyMs = rtt / 2_000_000.0

    return SyncResult(
        offsetNanos = offset,
        rttNanos = rtt,
        uncertaintyMs = uncertaintyMs
    )
}
```

```swift
// iOS
func calculateClockOffset(t1: UInt64, t2: UInt64, t3: UInt64, t4: UInt64) -> SyncResult {
    let rtt = Int64(t4 - t1) - Int64(t3 - t2)
    let offset = (Int64(t2 - t1) + Int64(t3 - t4)) / 2
    let uncertaintyMs = Double(rtt) / 2_000_000.0

    return SyncResult(
        offsetNanos: offset,
        rttNanos: rtt,
        uncertaintyMs: uncertaintyMs
    )
}
```

### 4.3 Multi-Sample Averaging

```
Perform 10 sync rounds:
1. Discard outliers (> 2 standard deviations)
2. Average remaining offsets
3. Report uncertainty as max(uncertainties)

Quality Tiers:
- EXCELLENT: uncertainty < 3ms
- GOOD:      uncertainty 3-5ms
- FAIR:      uncertainty 5-10ms
- POOR:      uncertainty > 10ms

Minimum requirement: FAIR or better for timing
```

### 4.4 Monotonic Clock References

| Platform | Clock Function | Notes |
|----------|---------------|-------|
| **iOS** | `mach_absolute_time()` | Convert to nanos with timebase |
| **Android** | `SystemClock.elapsedRealtimeNanos()` | Monotonic, survives deep sleep |

Both clocks:
- Do not jump with system time changes
- Monotonically increasing
- Nanosecond precision

---

## 5. Supabase Realtime Protocol

### 5.1 Channel Structure

```
Channel: trackspeed:session:{sessionId}

Broadcast events:
- timing_message: All protocol messages
- presence: Device online/offline status
```

### 5.2 Message Wrapping

```json
{
  "type": "broadcast",
  "event": "timing_message",
  "payload": {
    // Standard TimingMessage JSON
    "type": "crossing_event",
    "timestamp": 1706000100000,
    "deviceId": "abc-123",
    "payload": { ... }
  }
}
```

### 5.3 Presence Tracking

```json
// Presence state
{
  "deviceId": "abc-123",
  "deviceName": "iPhone 15",
  "platform": "ios",
  "role": "start",
  "state": "armed",
  "joinedAt": "2024-01-23T10:00:00Z"
}
```

### 5.4 Latency Considerations

```
BLE Latency:       5-20ms (local)
Supabase Latency:  50-200ms (depends on network)

For clock sync:    BLE required (too much jitter over internet)
For timing events: Supabase acceptable (events already timestamped)
```

---

## 6. Split Time Calculation

### 6.1 Formula

```
Given:
- Start crossing at time Ts (local nanoseconds)
- Finish crossing at time Tf (local nanoseconds)
- Clock offset O (nanoseconds, positive if finish device ahead)

Adjusted finish time:
Tf_adjusted = Tf - O

Split time:
Split = (Tf_adjusted - Ts) / 1_000_000  // Convert to milliseconds
```

### 6.2 Uncertainty Propagation

```
Total uncertainty = sqrt(
  start_detection_uncertainty² +
  finish_detection_uncertainty² +
  clock_sync_uncertainty²
)

Example:
- Start detection: ±1ms (sub-frame interpolation)
- Finish detection: ±1ms
- Clock sync: ±3ms

Total = sqrt(1² + 1² + 3²) = sqrt(11) ≈ ±3.3ms
```

---

## 7. Error Handling

### 7.1 Connection Errors

| Error | Recovery |
|-------|----------|
| BLE disconnect | Attempt reconnect 3 times, fallback to cloud |
| Supabase disconnect | Reconnect with exponential backoff |
| Clock sync timeout | Retry sync, warn user if quality poor |

### 7.2 Protocol Errors

| Error | Action |
|-------|--------|
| Unknown message type | Log and ignore (forward compatibility) |
| Invalid JSON | Log error, request retransmit |
| Sequence gap | Request missing messages |

### 7.3 Error Messages

```json
// ERROR message
{
  "type": "error",
  "timestamp": 1706000500000,
  "deviceId": "abc-123",
  "payload": {
    "code": "CLOCK_SYNC_FAILED",
    "message": "Unable to achieve acceptable clock sync quality",
    "details": {
      "lastUncertaintyMs": 25.4,
      "requiredMs": 10.0
    }
  }
}
```

---

## 8. Security Considerations

### 8.1 BLE Security

- Use BLE bonding for repeated connections
- Session IDs prevent replay attacks
- Device IDs verified on connection

### 8.2 Supabase Security

- Row-level security on race_events table
- User must be authenticated
- Session creator controls access

### 8.3 Data Privacy

- No PII transmitted in timing messages
- Device IDs are random UUIDs
- Session data can be deleted by user

---

## 9. Versioning & Compatibility

### 9.1 Protocol Version

```json
// Include in SESSION_CREATE and SESSION_JOIN
{
  "payload": {
    "protocolVersion": "1.0",
    "appVersion": "1.2.3",
    "platform": "android"
  }
}
```

### 9.2 Compatibility Rules

1. **Minor version**: Backward compatible, new optional fields
2. **Major version**: Breaking changes, negotiate common version
3. **Unknown fields**: Ignore (forward compatibility)

### 9.3 Feature Flags

```json
{
  "payload": {
    "capabilities": {
      "highSpeedCamera": true,
      "maxFps": 240,
      "binaryClockSync": true,
      "multiGate": false
    }
  }
}
```

---

## 10. Implementation Checklist

### iOS Changes Required

- [ ] Add BLE GATT server support (currently only Multipeer)
- [ ] Implement binary clock sync characteristic
- [ ] Ensure TimingMessage JSON matches this spec
- [ ] Add Supabase Realtime channel support
- [ ] Handle Android-specific device info

### Android Implementation

- [ ] BLE central and peripheral modes
- [ ] GATT service with 3 characteristics
- [ ] JSON message serialization (Kotlinx.serialization)
- [ ] Binary clock sync encoding/decoding
- [ ] Supabase Kotlin Realtime integration
- [ ] Clock offset application in split calculation

---

## Appendix A: Example Session Flow

```
Time    Device A (iOS, Start)          Device B (Android, Finish)
─────   ─────────────────────          ──────────────────────────
0:00    Start advertising (BLE)
0:01                                    Scan, find Device A
0:02                                    Connect to Device A
0:03    Accept connection
0:04    ◄─── SESSION_JOIN ────
0:05    ──── SESSION_JOIN_ACCEPTED ───►
0:06    ◄─── CLOCK_SYNC_PING ────
0:07    ──── CLOCK_SYNC_PONG ────►
        ... (repeat 10 times)
0:10    ◄─── CLOCK_SYNC_RESULT ────
0:11    ──── GATE_CALIBRATING (0.5) ──►
0:15    ──── GATE_READY ──────────────►
0:16    ◄─── GATE_CALIBRATING (0.7) ────
0:20    ◄─── GATE_READY ────────────────
0:21    ──── ARM_GATES ───────────────►

        [Both gates armed, waiting for athlete]

0:45    Athlete crosses start gate
0:45    ──── CROSSING_EVENT ──────────►
0:50                                    Athlete crosses finish gate
0:50    ◄─── CROSSING_EVENT ────────────
0:51    Calculate split time
0:51    ──── SPLIT_TIME_CALCULATED ───►
0:52    Display: 4.827 seconds
0:52                                    Display: 4.827 seconds
```

---

## Appendix B: BLE UUIDs (Final)

```
// Base UUID: 12345678-XXXX-1000-8000-00805F9B34FB

Service UUID:
0x1234 (short) = 12345678-1234-1000-8000-00805F9B34FB

Characteristic UUIDs:
Timing Messages:  12345678-0001-1000-8000-00805F9B34FB
Device Info:      12345678-0002-1000-8000-00805F9B34FB
Clock Sync:       12345678-0003-1000-8000-00805F9B34FB
```

Note: These are placeholder UUIDs. For production, generate proper random UUIDs or register with Bluetooth SIG.
