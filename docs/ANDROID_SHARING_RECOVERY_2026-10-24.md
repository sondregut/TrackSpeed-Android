# Sharing recovery and coroutine maintenance — 24 October 2026

This increment follows preserved checkpoint b7e790a. The production loader fix is commit 1435f38; the final validated candidate also includes kotlinx-coroutines 1.11.0 and two native feedback screenshots. The final manifest records the complete commit and APK hash.

## Confirmed fixes

- Run and session image-sharing initial read failures end loading and offer localized Retry.
- Refresh failures retain the last successful card. A confirmed missing record is distinct from a failed read and clears stale content.
- Retry preserves theme, Story/Feed format, photos, names and split choices. Cancellation and repeated retry reject late results.
- A speed unit selected while photo loading is suspended wins over the earlier loaded unit. Conversion uses canonical distance/time, with no timing threshold or precision changes.
- Coroutines 1.9.0 → 1.11.0 passes on the existing Kotlin 2.2.21 toolchain. This is a bounded library maintenance update, not a claim about the cause of the physical pairing incident.

## Verification

- 492 JVM tests passed with no failures, errors or skips, including 14 new sharing recovery cases. The 143 targeted timing/protocol/diagnostics/loader cases are part of that total, not additional tests.
- 94 applicable native tests passed in one complete run on the task-owned offline Android 16/API 36 emulator. Coverage includes Room v9 preservation, navigation, loading/retry, saved sharing choices, actual PNG capture and synthetic MP4 export. Physical Wi-Fi Aware probes and data-seeding fixture helpers were deliberately excluded.
- Debug application and test APK builds passed. Lint: 0 errors, 894 warnings, 3 hints; no new suppression or baseline.
- APK permissions are byte-identical to b7e790a and the signing certificate matches. Compile/target 36 and minimum 26 are unchanged.
- Installed emulator APK SHA256 matches the built APK. MainActivity freshly cold-launched in 1,214 ms. Version remains 1.0.10 (11); distinguish candidates by commit and SHA256, not reused version alone.
- The two new screenshots are native production feedback components with isolated state. Repository failures are exercised by production loader/reducer tests; an actual Hilt database failure was not injected into the running app. Earlier real navigation screenshots retain their original source fingerprints.

## Acceptance limits

The Pixel has not received this increment. APK Library storage does not mean delivery as a chat attachment or installation. No physical phone was accessed. Camera timing, BLE Android↔iOS/Android↔Android, both host roles, radio reconnect/load, API 26–28 media saving, live account/backend/billing and recipient handoff remain unverified.

Full parity and latest-library migration remain open. SDK 37 is absent; the latest Compose/Core/Navigation/Lifecycle family requires a coordinated SDK/compiler/AGP migration. This is a package and validation prerequisite, not an access denial or physical-device requirement. See full-app-parity-20261024/dependency-stages/EXECUTION-CHECKPOINT.md for reviewed families and sources.

No Sondre17 access, desktop input, new CI, cache deletion, production write, credential/security change, store release, or denied PR retry occurred.
