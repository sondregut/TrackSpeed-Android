# TrackSpeed Cross-Platform Timing Protocol

**Wire protocol:** 7

**Reference implementation:** current iOS `TimingMessage.swift` and `BluetoothTransport.swift`

**Last verified:** August 22, 2026

This is the active interoperability contract for iOS↔Android and
Android↔Android timing. Source code remains authoritative when this document
and an implementation disagree.

## Transports

An active local session uses BLE as its authenticated bootstrap and primary
protocol path, with Supabase Realtime Broadcast as a redundant cloud path.
Current Android peers exchange an Android-only capability over encrypted BLE;
when both advertise support, clock samples upgrade to a passphrase-secured
Wi-Fi Aware NDP-bound socket. Session configuration, roles, `SyncComplete`,
timing events, and fallback remain on BLE/cloud. iPhone↔Android clock samples
remain BLE because internet latency is unsuitable for establishing the local
monotonic-clock offset.

On iOS, the user must currently select the Bluetooth transport to connect to
Android; Apple Multipeer Connectivity is iOS-only. Android currently uses the
compatible Bluetooth transport for a local session.

Apple's iOS 26 Wi-Fi Aware implementation is standards-based and explicitly
allows a non-Apple phone or tablet to act as a peer. That is still a hardware
and firmware contract, not an API-level guarantee. Apple's Accessory Design
Guidelines require Wi-Fi Aware 4.0 NIK-cached pairing, pairwise and group
data/management protection, beacon protection, and post-pair exchange of PTK,
GTK, IGTK, and BIGTK. Android exposes the required capability check as
`Characteristics.isAwarePairingSupported()` on Android 14 and later.

The two Pixel 7a phones used for the August 22, 2026 physical probe run API 37
and API 36 and declare `android.hardware.wifi.aware`, but both firmware builds
report `isNanPairingSupported=false` and
`supportedPairingCipherSuites=0`. They therefore cannot enter Apple's secure
paired data path. This is a device capability failure even though basic Android
Wi-Fi Aware discovery and secured NDPs are available.

Android-to-Android is a separate case. Both Pixel phones completed secure
discovery, a passphrase-secured NCS-SK-128 NDP, an NDP-bound TCP socket, and
256/256 ordered clock messages with zero duplicates. The standalone physical
probe passed at `±3.004 ms`; the exact production clock lane passed at
`±6.558 ms`; and the complete BLE-bootstrap/Aware-upgrade handshake passed at
`±6.613 ms`, after which the host recorded one of one remote clients synced
and two of two total gates ready.

TrackSpeed now contains an **Android-capability-gated Wi-Fi Aware clock
candidate**. It never opens an unprotected data path: both apps first prove the
Android extension over encrypted BLE, derive a session/identity-scoped
passphrase, bind the socket to the secured NDP, and retain the unchanged strict
`<10 ms` gate. Missing permission, capability, path, socket, or accuracy falls
back to BLE. This one-pair pass does not yet promote the route as field-reliable;
five cold setups plus timing-event and path-loss recovery remain required.

Authorities: [Apple Wi-Fi Aware](https://developer.apple.com/documentation/WiFiAware),
[Apple Accessory Design Guidelines](https://developer.apple.com/accessories/),
[Android Wi-Fi Aware network specifier](https://developer.android.com/reference/android/net/wifi/aware/WifiAwareNetworkSpecifier.Builder), and
[Android Wi-Fi Aware pairing characteristics](https://developer.android.com/reference/android/net/wifi/aware/Characteristics#isAwarePairingSupported()).

Physical probe evidence and the next numeric gates are recorded in
`docs/WIFI_AWARE_FIELD_LOG.md`.

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
descriptor write succeeds. Android enforces that in `onDescriptorWrite`; iOS
enforces it in `didUpdateNotificationStateFor`. Service discovery alone is not
an application-ready connection because the first handshake or sync response
could otherwise be sent before host-to-joiner notifications are active.

On Android 13 and later, characteristic writes, descriptor writes, and server
notifications use the value-taking GATT APIs. Older devices retain the
deprecated calls only inside SDK-gated compatibility helpers. Android requests
high connection priority only for a full or mini clock-sync window and restores
balanced priority when that job ends. Sync ping/pong packets use
write-without-response; critical setup packets keep acknowledged writes.
Android 14 may negotiate MTU 517 for the first GATT-client request regardless
of the requested value, so packet sizing always follows the callback's actual
negotiated MTU rather than assuming 512.

Android advertises the service UUID without a local-name field so legacy
31-byte advertising packets cannot overflow. Discovery must filter by service
UUID, not device name.

Android's automatic pairing entry point begins in dual advertise/scan mode and
uses a deterministic device token so two Android phones cannot both elect
themselves as the client. Explicit Create/Join flows retain their requested
host/peripheral and joiner/central roles. The host keeps advertising after the
first connection, maps each received `senderId` to its BLE device address, and
supports targeted ACKs and multi-peer fan-out.

The Android host does not advertise until `BluetoothGattServerCallback` reports
that the timing service was added successfully. The Android joiner requests a
larger MTU, falls back to service discovery at the default MTU if that request
cannot be queued, and ignores callbacks from a stale GATT instance after a
reconnect. These ordering rules prevent a fast peer from discovering an empty
GATT database or an old callback from corrupting a replacement connection.

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

The Android codec covers all 53 iOS payload cases plus the guarded
`androidTransportCapabilities` extension. Android sends that extension only to
a peer first identified by the 12-byte Android advertisement token, so it is
not placed on an iOS wire. The shared roles are `startLine`, `finishLine`,
`lapGate`, and `controlOnly`.

Unknown top-level JSON fields may be ignored for forward compatibility, but a
session whose `protocolVersion` is incompatible must not silently reinterpret
payload semantics. BLE ingress on both platforms and Android cloud ingress
reject an explicit version mismatch before clock samples, ACK tracking, sender
mapping, or race state are updated, and show the update-all-phones error.

`TimingSessionConfig` carries optional `hostDeviceId` and
`timingGateDeviceIds`. These are stable TrackSpeed app identities. Android BLE
addresses and CoreBluetooth peripheral UUIDs are delivery routes only and must
never be persisted or compared as application identities.

Current iOS reserves sequence `9000` for a temporary pre-session
`roleAssigned(startLine)` bootstrap, `9001` for pre-session sync completion,
and `9002` for terminal pre-session sync failure. On sequence 9000, an Android
joiner adopts the host session, configures itself as the start reference, and
announces its stable identity with `roleRequest`; it does not treat the
temporary role as the authoritative session assignment. An iPhone may target
that identity announcement to its phone-local CoreBluetooth peripheral ID.
Android accepts that mismatch only for a matching `roleRequest` delivered over
the exact encrypted GATT server link. Every other target mismatch fails closed.

A joiner opens timing only after all four facts are true, in any arrival order:

1. authoritative session config received;
2. authoritative gate or role assignment received (sequence 9000 does not count);
3. clock-sync uncertainty is below ±10 ms; and
4. the host's `startTiming` barrier was received.

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

The August 22 implementation and JVM compatibility tests cover the wire,
identity, estimator, and state-barrier contracts. They do not replace the final
physical Android↔Android and iOS↔Android run matrix; record device/build markers,
sync estimator and uncertainty, event IDs, ACKs, and visible results for that
gate.
