# TrackSpeed Cross-Platform Timing Protocol

**Wire protocol:** 7

**Reference implementation:** current iOS `TimingMessage.swift` and `BluetoothTransport.swift`

**Last verified:** July 21, 2026

This is the active interoperability contract for iOS↔Android and
Android↔Android timing. Source code remains authoritative when this document
and an implementation disagree.

## Transports

An active local session uses BLE as its primary path and Supabase Realtime
Broadcast as a redundant cloud path. Clock-sync ping/pong messages remain BLE
only because internet latency is not suitable for establishing the local
monotonic-clock offset. Receivers deduplicate messages that arrive over both
paths.

On iOS, the user must select the Bluetooth transport to connect to Android;
Apple Multipeer Connectivity is iOS-only. Android always uses the compatible
Bluetooth transport for a local session.

## BLE GATT contract

The UUIDs and characteristic directions match iOS exactly:

| Item | UUID | Direction and properties |
|---|---|---|
| Timing service | `A1B2C3D4-E5F6-7890-ABCD-EF1234567890` | Primary service |
| TX | `A1B2C3D4-E5F6-7890-ABCD-EF1234567891` | Host→joiner, notify + read |
| RX | `A1B2C3D4-E5F6-7890-ABCD-EF1234567892` | Joiner→host, write + write without response |

TX reads/notifications and RX writes require an encrypted BLE link. Android
requests bonding when the platform reports insufficient authentication. The
joiner is considered ready only after its Client Characteristic Configuration
descriptor write succeeds.

Android advertises the service UUID without a local-name field so legacy
31-byte advertising packets cannot overflow. Discovery must filter by service
UUID, not device name.

Both Android devices begin in dual advertise/scan mode. The first resolved
connection determines host/peripheral versus joiner/central roles. The host
maps each received `senderId` to its BLE device address, enabling targeted ACKs
and multi-peer fan-out.

## BLE message framing

The payload is UTF-8 JSON. If it fits in the current ATT value size it is sent
as raw JSON for backward compatibility. Larger payloads use the iOS `STB1`
framing format:

```text
byte 0..3   ASCII "STB1"
byte 4..7   unsigned big-endian payload length
byte 8..N   UTF-8 JSON payload
```

Framed bytes are split into ATT-sized packets and reassembled per peer. The
maximum declared payload is 1,000,000 bytes. Invalid magic, invalid lengths,
and oversized frames are rejected before JSON decoding. Android requests MTU
512 and uses the negotiated value minus the three-byte ATT overhead.

Do not wrap Bluetooth JSON in the HMAC envelope used by the iOS-only
Multipeer/Network peer transports. Current iOS Bluetooth sends and receives the
plain `TimingMessage` JSON described below.

## TimingMessage envelope

All field names are camelCase and use Swift-compatible JSON:

```json
{
  "protocolVersion": 7,
  "seq": 42,
  "senderId": "DEVICE-UUID",
  "sessionId": "SESSION-UUID",
  "messageId": "OPTIONAL-RETRY-UUID",
  "eventId": "OPTIONAL-STABLE-EVENT-ID",
  "targetDeviceId": null,
  "runId": "OPTIONAL-RUN-UUID",
  "payload": {
    "crossingEvent": {
      "gateId": "DEVICE-UUID",
      "role": "finishLine",
      "gateIndex": 1,
      "timestampNanos": 1234567890123,
      "confidence": 1.0,
      "thumbnailData": null
    }
  },
  "createdAtNanos": 1234567890999
}
```

Swift associated-value enum encoding is mandatory: `payload` contains exactly
one case-name key whose value is an object. Unit cases use an empty object, for
example `{ "armAll": {} }`. Kotlin polymorphic `type` discriminators are not
wire-compatible and must not be used.

The Android codec currently covers all 53 iOS payload cases. The shared roles
are `startLine`, `finishLine`, `lapGate`, and `controlOnly`.

Unknown top-level JSON fields may be ignored for forward compatibility, but a
session whose `protocolVersion` is incompatible must not silently reinterpret
payload semantics.

## Timebase and clock conversion

All race timestamps are monotonic nanoseconds, never wall-clock Unix time:

- Android: `SystemClock.elapsedRealtimeNanos()`.
- iOS: the monotonic time returned by `TimingMessage.monotonicNanos()`.
- Offset convention: `t_remote = t_local + offset`.

Android camera frames use Camera2 sensor timestamps. A sensor declaring
`SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` already shares Android's elapsed-time
domain. For an `UNKNOWN` source, Android estimates the timebase offset from the
minimum of ten sensor-to-callback deltas and withholds those calibration frames
from detection. Per-frame `SENSOR_EXPOSURE_TIME` is forwarded to the detector.

See [CLOCK_SYNC_DETAILS.md](CLOCK_SYNC_DETAILS.md) for estimator and rejection
rules.

## Critical delivery and deduplication

A critical message has a non-null `messageId`. The sender stores the complete
serialized envelope and retries that same envelope—without changing sequence,
IDs, run, event, or timestamp—using one-second exponential backoff with 0–20%
jitter for at most 12 retries.

The receiver ACKs the originating peer with `{ "ack": { "messageId": ... } }`.
On host broadcasts, every connected BLE peer is tracked independently; one
peer's ACK does not clear another peer's retry obligation. NACK ends the retry
for that recipient and exposes the failure to diagnostics.

`eventId` provides semantic deduplication across BLE, cloud, reconnection, and
process retries. Crossing IDs use the same iOS format:

```text
<first-8-of-runId>-<first-8-of-gateId>-<timestampNanos>
```

## Supabase Realtime fallback

Both platforms use:

- Channel: `timing_<lowercased-session-id>`
- Timing event: `timing_message`
- Body: `{ "payload": <TimingMessage> }`
- Presence event: `presence`

The Android relay authenticates (creating an anonymous session if needed),
subscribes, reconnects with bounded exponential delay, and suppresses its own
broadcasts. BLE and cloud delivery can occur together; receiver deduplication
is therefore required.

## Compatibility acceptance checklist

Before changing either app's protocol, verify:

1. protocol version and all 53 case names;
2. camelCase field names, optionals, role raw values, and unit-case encoding;
3. UUIDs, TX/RX direction, encryption, CCC readiness, MTU accounting, and
   `STB1` framing;
4. targeted ACK behavior and immutable retry envelopes;
5. monotonic timestamp domain and offset sign;
6. `timing_<session>` channel, `timing_message` event, and nested payload;
7. Android↔Android plus iOS Bluetooth host/joiner permutations on physical
   hardware.
