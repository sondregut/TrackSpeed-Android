# Android parity increment — 24 October 2026

Reference: released iOS 1.0.23 (134), source de9cd581, plus the separately tested editable video-overlay work. Existing Android timing/session changes remain intact. This checkpoint is not a full parity or physical timing acceptance claim.

## Implemented

- Released Light/Dark surfaces, readable neutral action colours and status/navigation contrast; preserve existing appearance preferences.
- Profile returns to the same tab/scroll after Settings and guest sign-in. Guest sign-in is actionable. Manual event/flying personal bests have native validation, explicit Save/Cancel, exact canonical values and serialized, account/revision-aware synchronization.
- Tutorial actions remain reachable on compact screens and large text. Race permission and connection labels distinguish link connection from timing readiness.
- Video editor labelled controls, restoration, unit/metric persistence, preview-only safe guides and export locking.
- Retained result/detail read errors with Retry instead of endless loading or lost content.
- Help/private reports, public ideas and Web Dashboard account gate use existing contracts. Drafts stay local until Send, image metadata is removed, public posting is explicit, uncertain writes are not blindly retried. No backend operation was exercised in validation.
- 119 new strings across all 31 supported translations; owned iOS wording reused where exact. Structural validation passed; independent native-language acceptance remains outstanding.
- Bounded fresh GATT handshake after invalid handle/service change, with stale callback/result rejection and fresh notification readiness. This addresses missing recovery observed in the preserved Pixel trace; the underlying peer-side handle change and physical recovery are not proven.
- Custom distances accept comma/period decimals and reject non-finite, zero and negative values. Canonical metres and existing positive sub-metre behavior are preserved.

## Executed validation

- 432 JVM tests passed, no failures/errors/skips.
- Debug application and instrumentation APKs built; lint passed with 0 errors, 823 warnings and 3 informational findings. No lint baseline/suppression was added.
- Offline Android 16/API36 task emulator: 63 distinct native UI/export/database checks covered by passing results. The first full run passed60/63; six affected checks passed on targeted rerun after fixing test Back dispatch and waiting for RenderThread ripple settlement. Exact colour assertions were retained; settled fill grew from0/16 pixels to over85,000 expected pixels.
- Installed app APK bytes matched SHA256 `59d18afe8cbcc31d1e784163ba6790d669dc0fdadb74dca188ff8fa3877d3bd5`; source fingerprint `222131476f7cedb2`; version1.0.10(11). Fresh launch succeeded in1099ms.
- Actual MainActivity navigation verified Profile→Settings→Back and Profile→Sign In→Back retain Profile/scroll; personal-best Cancel makes no write. Help/private composer/Back and public-board offline/error routes were reached without submission.
- Existing synthetic fixture remains offline. No physical phone, live account, public post, purchase or production backend was touched. No remote CI launched.

## Remaining acceptance and work

Physical camera/frame timing, BLE Android↔iOS and Android↔Android in both roles, reconnect under load, and strict uncertainty below10ms still require coordinated device tests. A screenshot or unit test does not establish these.

Named-account dashboard linking, existing backend policy/receipt behavior, public board permissions and billing need authorized live acceptance. Native UI tests use fake callbacks and no network.

Still-image sharing has separate mixed-athlete naming and export-error/state defects under active follow-up; Story/Feed, photo and visibility parity is not yet complete. Toolchain/library upgrades are prepared but not part of this validated checkpoint. Apple-only capabilities and measured Reaction Start remain unavailable unless independently supported and validated.

The existing Library gallery keeps earlier captures with their own source fingerprints; new captures must be labelled by this checkpoint. Component renders remain distinct from actual app navigation.
