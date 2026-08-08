# Android two-phone end-to-end test

## Test access

Use this server-backed code on each Android phone:

```text
ANDROIDTEST2026
```

The code grants 30 days of TrackSpeed Pro to each device. It accepts up to 20
distinct test-device redemptions and can be entered through either the
onboarding promo field or **Paywall → Have a code?**. The code itself expires
at 23:59:59 UTC on August 31, 2026.

Promo access is device-scoped, matching iOS. If the same account is used on
both phones, redeem the code once on each phone. The server returns the
existing active grant when the same phone enters it again, so retrying is
safe. After one online verification, the last verified Pro state remains
available during an offline BLE-only test.

## Build and install

Enable Developer options and USB debugging on both phones, connect both by
USB, accept their authorization prompts, and run:

```bash
./scripts/install_two_android_devices.sh
```

The script builds one debug APK and installs that exact binary on the first two
authorized Android devices. To avoid ambiguity when more devices are attached,
pass the two serials explicitly:

```bash
./scripts/install_two_android_devices.sh HOST_SERIAL JOINER_SERIAL
```

## Access and pairing sequence

1. Keep internet enabled initially. Sign in or create an account on both
   phones; the same account or two test accounts are both supported.
2. Enter `ANDROIDTEST2026` on both phones and confirm **Pro Activated!**.
3. Grant Nearby devices, camera, microphone, and notification permissions.
4. On phone A open multi-phone timing and choose **Host Session**.
5. On phone B open multi-phone timing and choose **Join Session**.
6. Confirm both phones report connected gates and a usable synchronized clock
   before arming.
7. Run a start-to-finish crossing and verify the same result, run identity,
   gate roles, thumbnails, and segment total appear on both devices.
8. Disconnect and reconnect, reverse host/joiner roles, and repeat.
9. Disable internet on both phones and repeat once more to exercise BLE-only
   delivery and the verified offline Pro cache.

Capture Android bug reports or at least filtered logcat from both serials if a
step fails. The parity acceptance matrix in
`docs/PARITY_VERIFICATION_2026-07-21.md` contains the additional iOS/Android and
camera-negative permutations.
