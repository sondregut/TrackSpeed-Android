# Android full-app visual increment — 22 October 2026

This is a tested source/APK checkpoint, not a declaration of complete iPhone parity or physical acceptance.

## Scope

The existing branch `codex/android-onboarding-visual-parity-20261022` preserves clock-sync correction `770371f` and the published consent-gated diagnostics, onboarding restoration and session classification fixes. The visual reference is the current iPhone release source `16733b928` / build 131 and equivalent onboarding integration source `e0ac7028a`; no iOS checkout was edited in this increment.

- Onboarding: shared owned imagery, photographic welcome/profile/camera pages, current tour sequence/copy, native permission/back controls, restored and repeated flow guards. Eleven shared image assets are byte-identical to iPhone, including six newly imported assets.
- Home/history: setup cards, Profile/Join hierarchy, larger latest-session thumbnail, recent session grouping, native dates/plurals, actionable empty/filter states.
- Setup/recording/results: scrollable native setup and pairing, Record/Results panels preserving the mounted camera subtree, compact timer/camera layout, status strip, host-only athlete assignment.
- Saved detail/replay: time/photo hierarchy, actionable missing-result state, labelled frame controls and compact-height scrolling.
- Settings/sharing: current section hierarchy and badge treatment, large-font reflow, inline canvas and speed units, bounded scrolling with preview space retained. Canonical metric conversion/export code is unchanged by this visual increment.
- Localization: 2,573 localized string entries and 31 plural resources across all 31 existing locale directories. 937 entries reuse the owned iPhone catalog or existing Android strings; 1,636 new Android translations need native-language visual review. Placeholder/key checks passed. No MissingTranslation suppression or copied-English placeholder resources were added.
- Runtime attribution: `BUILD_MARKER=android-iphone-visual-parity-2026-10-22`, `SOURCE_FINGERPRINT=fe5c4cec3247e11e`. Fingerprinting now includes packaged imagery. Version remains 1.0.10 (11); version number alone is not candidate proof.

## Completed validation

Offline local Gradle validation finished **2026-10-22 21:38:43 UTC**:

```
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

- 307 unit tests passed; zero failures/errors/skips. Includes onboarding/permission restoration, diagnostics queue/privacy, clock submission/uncertainty, overlay conversions and five new current-run display regressions. New run zero does not flash the preceding result; stored and displayed precision is preserved.
- Lint: zero errors/fatals; 894 warnings and three informational findings remain. This is not a warning-free codebase.
- Debug app and instrumentation APKs compile and signatures verify. New native fixtures cover compact Home navigation, Settings units and onboarding restoration, but **were not executed**.
- Source hashes recorded before build still match the candidate; `git diff --check` passes.

App APK SHA-256: `13a19ebbcdf413a74dd4dc25315f35b09da2cfe7f9148958adfb1df7bbb5a417`.
Debug certificate SHA-256: `3b72625ae30727178ac6902b5428b2c6158f271a05636372349ab730885838b6`.

## Exact acceptance blockers and remaining parity

**Pixel unavailable:** ADB inventory and existing mDNS discovery both return no physical Android device. Last verified Pixel 7a installation remains `770371f`, version 1.0.10 (11), with data preserved. This visual APK has not been installed or freshly launched. Reconnect the same previously authorized Pixel; verify serial/model/signature, use an update install, then confirm the new process and source fingerprint. No uninstall, data clear, permission changes or new pairing grants.

**Native render blocked:** existing AVD reported a pending snapshot operation and was left untouched. A separate task-owned headless AVD failed to boot with `mprotect failed: Permission denied` using both documented software and host GPU modes. Both task-owned emulator processes were stopped. No security settings, caches or user emulator files were changed. No native screenshot/contact sheet is claimed.

**Full-app parity remains open:** auth/referral/paywall alignment, distinct history loading/error contracts, one-phone per-run replay/share and gate-clip export integration, authoritative timestamp selection in frame replay, all clock-attention states, supported missing Settings flows, and device/accessibility/lifecycle/permission checks. Generic sharing-editor changes do not establish one-phone/gate-clip parity.

Dependencies were audited but not migrated. Current toolchain remains AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21, with documented stable-version migration work outstanding. Preserve the installed sync baseline before expanding the dependency/transport matrix. No claim that every library is current.

Android↔iPhone and Android↔Android physical clock/transport accuracy remains unverified. The strict timing uncertainty limit is unchanged. Apple Watch/Health/Multipeer are not presented as Android features; Reaction Start remains unsupported pending measured cue-onset validation.

Sondre17 monitoring is stopped. Host-only process verification at 21:39:09 UTC found no capture owned by this task; no phone/backend check was made. No Sondre17 work may resume without new authorization.

No new CI run, store release, production/security change, physical device mutation or manual cache deletion occurred in this increment. This candidate descends from the existing clock-sync branch, following the existing PR chain; the repository workflow only triggers PR validation for `main`. Local full validation above is the evidence for this draft.
