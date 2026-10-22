# TrackSpeed Android — iPhone parity update, 22 October 2026

This continues the existing native Kotlin app from the September import `a3ccfcc`; it is not a rewrite. The iPhone reference is build 131 source at `16733b928` (1.0.21), inspected read-only. Android changes are implemented and tested below. Full iPhone parity still has the explicitly listed gaps.

## Changes and evidence

| Area | Android change | Verification |
|---|---|---|
| Delayed start delivery | Keep up to eight finish candidates ordered by sensor time. Pick the earliest after the actual start, retaining that crossing’s photo, calibration and frame evidence. | Candidate ordering, stale/duplicate/invalid timestamps, evidence updates, bounded memory and reset tests. |
| Result integrity | Eliminate receipt-clock fallback for finish timestamps; reject unanchored/overflowed results. Freeze athlete, course, start type, session and calibration before asynchronous saves. | Timestamp/overflow tests; existing result/session regression suite. |
| Crossing direction | Current iPhone upper-body motion-centroid rule: use consecutive masks, upper 60% of accepted body, at least 20 motion pixels and configured minimum displacement; otherwise retain history/center fallback. | Motion/opposing-leg/noise/missing-mask tests; existing detector tests. Physical accuracy still requires real devices. |
| Start modes | Preserve Motion/Reaction identities across remote config, results, history and sharing. Native Motion and connected In-Frame now use front-camera pose onset, stillness and session readiness; In-Frame confirms departure while retaining the original onset. Reaction remains guarded on the local start phone. | 23 focused tests plus full JVM/build/lint checks. See [Motion/In-Frame details](motion-inframe-parity.md). Physical timing and cross-platform radio acceptance remain unverified. |
| Results | Only show a session best when distance, start method and topology are comparable. | JVM comparison tests plus native Room history tests for athlete/method separation, invalid timing, deletion and persistence. |
| Onboarding/navigation | Explicit conditional route, current feature photos, role/sport selection, removal of pain/goal detours, four-page getting-started guide, direct Start a session navigation, process restoration for step/role/sport. Preserve existing billing/early-access behavior. | Route/back/progress tests and native compact-screen/large-text guide tests. |
| Live photos | Preserve aspect rather than crop. Basic live row long edge 66→80 dp on comfortable screens; retain 66 compact. Expanded gate photo long edge 128→144, retain 128 compact. | Dimension/aspect tests. See comparison page for dimensions and iPhone proposal. |
| Sharing | One native Canvas renderer for preview and Media3 export. Large time, labeled final average speed, grouped splits and branding, rounded display hundredths with canonical timing unchanged. Clear the reused export bitmap each frame to prevent stale digits and card shapes. | Native preview viewport pixel equality; reused export layers match fresh frames exactly across start/finish/backward seeking; decoded actual MP4 comparison within mean channel-error tolerance 18/255 for H.264. |
| Editable metrics | Persist m/s / mph / km/h, time/speed/run label visibility, cumulative / segment / off splits, four positions, safe guides, original / 9:16 output. Segment durations use actual split values; average speed is not presented as instantaneous speed. | Exact conversion, invalid value, JSON round-trip, reflow and safe bounds tests. Native phase/render tests. |
| Portrait sharing | 1080×1920 social canvas aspect-fits landscape footage. A compact header uses upper letterbox space when sufficient room exists; no athlete crop. Original aspect remains available. | Actual m/s portrait, mph landscape and km/h landscape-on-portrait exports, orientation metadata assertions, layout separation regression. |
| Build maintenance | Repair corrupt Gradle wrapper shell escaping; fix the two prior Wi-Fi Aware indentation lint errors; use AndroidX Test JUnit 1.3.0 / Espresso 3.7.0 for Android 16 test compatibility. | Serial debug/unit/instrumentation APK/lint tasks. No production dependency or backend changes. |

## Social guidance and design interpretation

Checked 22 October 2026. Meta’s official indexed [Instagram Reels guidance](https://www.facebook.com/business/ads-guide/update/image/instagram-reels) and [Stories video guidance](https://www.facebook.com/business/ads-guide/update/video/instagram-story) describe approximately 14% top, 35% bottom and 6% side clearance. The full Meta pages were login-limited; those figures were verified from their official indexed excerpts. [TikTok’s official in-feed specifications](https://ads.tiktok.com/resources/help/article/tiktok-auction-in-feed-ads?lang=en), updated June 2026, explicitly vary safe zones with aspect ratio, captions, anchors and LTR/RTL, and warn that device display can differ from preview.

The shared portrait preset therefore uses **18% top / 35% bottom / 8% left / 16% right** as a conservative design inference. These are not universal official TikTok dimensions or a guarantee for all captions, RTL layouts or feed crops. Red/green review guides represent this shared design envelope, not a captured Instagram/TikTok interface. Guides never export. Review placement against the actual athlete; the app does not track the athlete to move the overlay automatically. Original landscape uses ordinary framing margins, not a promise that a later platform crop will preserve it; select 9:16 social for that use.

Speed converts canonical m/s using exactly `1 / 0.44704` for mph and `3.6` for km/h, displayed to one decimal. Video times display hundredths; the stored timing precision is unchanged. Cumulative/segment labels remain distinct. The overlay shows the most recent three revealed split rows.

## Native samples

The review assets use synthetic local test videos, no private recordings or public video uploads. “Before” images recreate the previous Android Canvas layout on the same fixture; “after/export-frame” images come from the new renderer and decoded real MP4s. See [the review page](samples/review.html) for phone-scale comparisons, guides, exports and actual onboarding screenshots. The thumbnail comparison is a dimension/layout proposal using an existing product photo, not a captured live timing screen.

## Verification

- Latest full JVM suite: **252 tests across 52 suites, 0 failures/errors/skips**, including 16 new onset/role cases. The focused Motion/In-Frame run passed 23 tests.
- Latest `:app:assembleDebug` and `:app:lintDebug` pass: 0 lint errors, 869 warnings, 3 information. APK SHA-256: `4eb473a42124f2da3ef2a99cd1a55259aa906d6060841a1b3a6cc6e138cdb896`. Instrumentation APK build passed at the prior `498e862` baseline; CI rebuilds it on the new commit.
- At baseline `498e862`, an isolated, offline, headless Android 16/API 36.1 arm64 emulator passed **10 native tests** (renderer/MP4 exports, onboarding, Room history). These unchanged paths were not rerun after the Motion/In-Frame follow-up; those tests do not validate the new camera capture route.
- Independent FFmpeg decoding also compares the full exported frame, including footage outside the card: mean channel error 1.45–3.12/255 and worst 8×8-grid tile below 5.33/255. One emulator MediaMetadataRetriever still extraction was incomplete even though the MP4 was correct; final review stills use the independent decoder. Reproduce with `python3 verify_overlay_exports.py samples` (FFmpeg required).
- No physical Android device was attached. No physical timing-error, rolling-shutter, radio, background-recovery, battery/thermal or mixed iPhone/Android gate claim is made.
- Git diff whitespace check passes. CI repeats JVM tests, lint and APK builds and retains debug artifacts; it does not publish to a store.

## Remaining gaps and release boundaries

1. Native Reaction Start remains unavailable on the local start phone: measured cue onset, signed reaction time, false-start classification and persistence need a dedicated implementation. Motion/In-Frame software semantics are implemented, but ML Kit/front-camera timing, mixed-device BLE, re-arm/background recovery and accessibility need physical acceptance. Start photos and richer per-onset diagnostic persistence remain separate work.
2. Apple Watch start, Apple-specific direct Wi-Fi transport, newest iPhone gate video clips and single-camera speed-trace/replay parity require separate Android implementation and real-device validation. Existing native BLE/Wi-Fi Aware/ML Kit paths were preserved.
3. The sharing-overlay request also applies to iPhone. A separate isolated iPhone branch now implements the timing-session editor, social-safe rendering and per-export units, plus unit selection in the separate one-phone flow. That one-phone animated layout and gate-clip layout still need full metric/safe-envelope integration. The active iPhone release/integration/tutorial worktrees and release artifacts remain preserved.
4. Current new copy falls back to English in untranslated locales. Existing translations and stable legacy analytics names are preserved. New role/sport screens have their own semantic analytics identifiers.
5. Android free-access code redemption against production and iPhone free-access codes were not tested by this task. Existing Android early-release access policy was preserved.
6. The local September import has separate Git history from remote `main` (`e8a697b`, August 9). The candidate is reviewed against an import-baseline branch, with no force push or replacement of remote main. Integrating those histories and choosing a release source remains explicit work before a production release.

No Play upload, App Store submission, production backend/security/credential change, paid-service introduction or cache deletion was part of this update. No keyboard typing was sent; desktop interaction stopped when requested.
