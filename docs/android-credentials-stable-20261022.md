# Stable Android Credential Manager — 22 October 2026

Both `androidx.credentials:credentials` and `credentials-play-services-auth` move from **1.5.0-alpha05 to 1.6.0**, the current official stable release. The existing Google ID request/response validation and provider configuration are unchanged. No credential creation, service call, sign-in, permission or account setting was performed.

Google's [release notes](https://developer.android.com/jetpack/androidx/releases/credentials) identify 1.6.0 as stable. Both official AARs require compileSdk 35 and AGP 8.6.0, met by this project's declared 36/8.7.3. The Kotlin 2.1.20 runtime dependency was checked by the actual compiler/build, without bypassing metadata checks; the current Kotlin 2.0.21 compiler successfully compiled this candidate. An intermediate 1.5.0 hold was unnecessary. This does not resolve the broader existing API-36/AGP support mismatch or modernize the entire toolchain.

Full local Gradle validation passed **22:04:33 UTC**: dependency metadata, 316 unit tests, lint (0 errors, 888 warnings, 3 information), app APK and test APK. No hosted CI. The new install-ready APK has SHA-256 `69dd5e1970cf59048832559a881990c385bca3c55213efaf7b4488ed5594e653` and the preserved debug certificate. It is not installed or launched. The source-only runtime fingerprint remains `e58b3e6b83331d2e`; use the APK hash and build-input manifest to distinguish this dependency update.

Actual Google sign-in, cancellation and older-device fallback remain native acceptance items. No dependency unit test substitutes for provider UI/runtime verification. Remaining library families and the supported toolchain migration are tracked separately in the dependency audit; no camera, timing, transport, backend, SDK target, billing or security behavior was changed here.

Evidence is in the task folder `full-app-parity-20261022/credentials-stable`: `validation/build-status.json`, `validation/build.log`, `artifact/manifest.json`, `artifact/source-manifest.json`. Public AAR metadata was saved under `dependencies/credentials-1.6-metadata.json`.
