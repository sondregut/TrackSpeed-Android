# Clock Synchronization Contract

**Reference:** current iOS `ClockSyncService.swift` BLE behavior

**Last verified:** July 21, 2026

TrackSpeed uses an NTP-style exchange to express a joiner's local monotonic
timestamps in the host/reference device's monotonic clock domain.

```text
t_remote = t_local + offset
```

A positive offset means the local clock is behind the remote clock. Android
uses `SystemClock.elapsedRealtimeNanos()` for ping/pong receipt, send, race,
audio, and camera-frame timestamps.

## Sample exchange

```text
joiner/local                         host/reference
    t1  ───── syncPing(t1) ─────────────►
                     receive t2; send t3
    t4  ◄── syncPong(t1,t2,t3) ─────────
```

For a sample:

```text
rtt    = (t4 - t1) - (t3 - t2)
offset = ((t2 - t1) + (t3 - t4)) / 2
```

## Current BLE parameters

| Parameter | Full sync | Mini sync |
|---|---:|---:|
| Requested samples | 80 | 30 |
| Interval | 50 ms (20 Hz) | 100 ms (10 Hz) |
| Maximum RTT | 200 ms | 350 ms |
| Minimum valid samples | 5 | 10 |
| Lowest-RTT fraction | 15% | 15% |
| Full-sync timeout | 8 s | — |

A mini-sync receives the previously applied offset as its baseline. It must
not mutate the established result if it fails.

## Per-sample rejection

A sample is rejected when any of these conditions holds:

- computed RTT is negative or above the mode's limit;
- reference processing time `t3 - t2` is negative or greater than 5 ms;
- during a mini-sync, the candidate offset differs from the applied baseline
  by more than 2 ms;
- the pong does not match the outstanding ping ID, original `t1`, or expected
  reference sender.

These checks prevent delayed/spoofed pongs and reference-side stalls from
moving a healthy offset.

## Estimator

The Android estimator mirrors current iOS BLE selection:

1. Require the mode's minimum number of accepted samples.
2. Find the minimum RTT and retain samples at or below
   `max(2 × minimumRTT, 5 ms)`.
3. With at least ten remaining samples, remove RTT values above
   `medianRTT + 2 × standardDeviation`.
4. Sort again by RTT and keep the lowest 15%, with at least three samples.
5. Use the minimum-RTT sample's offset unless it differs from the inverse-RTT
   weighted offset by more than half that sample's RTT; in that case use the
   weighted offset.
6. Estimate uncertainty as
   `minimumRTT / 2 + medianAbsoluteOffsetDeviation`.

If adaptive filtering leaves fewer than three samples, the estimator falls
back to the lowest full-sync minimum sample count and still requires at least
three.

## Quality and validation

| Quality | Uncertainty |
|---|---:|
| Excellent | < 3 ms |
| Good | < 5 ms |
| Fair | < 10 ms |
| Poor | < 15 ms |
| Bad | ≥ 15 ms |

A minimum RTT above 25 ms caps an otherwise Excellent/Good result at Fair.
Poor remains usable for ordinary synchronization; Bad is rejected. The
precision validation gate additionally requires:

- minimum RTT < 30 ms;
- RTT jitter (`p95 - p50`) < 10 ms;
- quality Fair or better.

Quality enum order is best-to-worst. Code must use `SyncQuality.isAtLeast()`
rather than direct enum comparisons so Excellent is never mistaken for worse
than Fair.

## Applying timestamps

The joiner converts local events for the host with:

```kotlin
val hostTimestamp = localTimestamp + offsetNanos
```

The inverse conversion is:

```kotlin
val localTimestamp = hostTimestamp - offsetNanos
```

Never mix wall clock, `System.nanoTime()`, image-callback receipt time, or Unix
milliseconds into a race event. Camera2 sensor timestamps must first be mapped
to the elapsed-realtime domain as described in
[CROSS_PLATFORM_PROTOCOL.md](CROSS_PLATFORM_PROTOCOL.md).
