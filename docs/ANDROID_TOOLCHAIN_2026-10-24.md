# Supported Android toolchain checkpoint — 24 October 2026

The parity branch now uses AGP8.11.1, Gradle8.13, Kotlin2.2.21, KSP2.2.21-2.0.5 (KSP2), Hilt2.58 and Room2.8.5. This is a staged compatible checkpoint, not a claim that every library is latest. Compile/target36, minimum26, JVM17, signing and timing policies are unchanged.

AGP8.11 explicitly supports compileSdk36; the previous unsupported-compileSdk suppression is removed. Kotlin compiler options use the typed JVM target. The Gradle distribution is SHA256-pinned and its wrapper implementation refreshed. Build Tools36.1.0 is explicit and matches the existing local package and CI setup; no SDK/license/security change was made.

## Validation

- Local full app/test APK builds and449 JVM tests passed, zero failures/errors/skips.
- Lint:0errors,895warnings,3hints with the newer checker. No suppression/baseline introduced.
- Native full suite71/72 passed; a demonstrated test-only colour capture race was corrected and both affected theme tests passed2/2. All72 distinct native cases have passing evidence across these runs; this was not one clean full run.
- New frozen Room2.6.1 version9 fixture opens under Room2.8.5, retains exact time/nanosecond timestamp/name values, accepts a write and reopens correctly. Generated schema identity is unchanged; no destructive migration fallback.
- APK permissions before/after are byte-identical. Actual MainActivity cold launch1042ms on task emulator5580/API36; existing synthetic history still has1session/2runs after the update.
- Original first packaging failure is preserved. Isolated rerun passed without changes, and serial full completion passed; the transient root cause is unproven.

No remote CI run, physical device operation, production backend write, new permission, credential, or store publication occurred. Real radio/camera/timing and billing/account acceptance remain separate.

## Remaining update stages

UI, networking, analytics, billing and unused CameraX cleanup remain separate reviewed candidates. Latest compatible combined toolchain/UI stages need compileSdk37 and broader migration testing; platform37 is not installed. Do not introduce metadata-validation bypasses or weaken timing bounds to make upgrades pass. No new license agreement should be accepted implicitly.
