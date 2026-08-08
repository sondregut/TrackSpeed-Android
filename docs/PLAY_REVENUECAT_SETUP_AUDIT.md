# Play Console and RevenueCat Setup Audit

Last verified: 2026-06-14

## Local Android Configuration

- Android application ID: `com.trackspeed.android`
- Release bundle path: `app/build/outputs/bundle/release/app-release.aab`
- Release bundle status: signed successfully with local upload key at `/Users/sondre/.android/trackspeed-upload.jks`
- RevenueCat public key: present in `local.properties`, `goog_` key, verified against RevenueCat `Track Speed Android`
- RevenueCat entitlement checked in app code: `Track Speed Pro`
- RevenueCat package identifiers used by app code:
  - `weekly_default`
  - `annual_default_full`
  - `annual_default_discount`
  - `annual_discount`
  - `annual_referral`

## RevenueCat Project

Project: `Track Speed` (`17ef16d7`)

Apps in the project:

- `Track Speed Android`
  - Store: Google Play
  - Package: `com.trackspeed.android`
  - App ID: `app3dab75525e`
- `Track Speed iOS`
  - Store: App Store
  - Bundle ID: `app.trackspeed.ios`
  - App ID: `appb8198c504c`

Entitlement:

- `Track Speed Pro` exists and includes Android `trackspeed_pro:weekly`, `trackspeed_pro:monthly`, and `trackspeed_pro:yearly`.

Offering:

- Active/default offering: `default`
- Packages:
  - `weekly_default`: has iOS `trackspeed_weekly` and Android `trackspeed_pro:weekly`
  - `annual_default_full`: has iOS `trackspeed_yearly` and Android `trackspeed_pro:yearly`
  - `annual_default_discount`: has iOS `trackspeed_yearly_discount` and Android `trackspeed_pro:yearly`

Android app settings:

- Offerings compatibility mode is set to `Only Android SDK v6+`.
- Android app uses RevenueCat SDK `8.12.1`.

Product catalog naming:

- Android weekly product exists as `trackspeed_pro:weekly` with display name `Weekly $7.99`.
- Android yearly display name is `Yearly $59.99`.
- iOS yearly display name is `Yearly $59.99`.
- Legacy iOS products that still contain `jumpersworld` in immutable product identifiers have dashboard display names changed to `Monthly Legacy $7.99`, `Yearly Legacy $59`, and `Yearly Legacy Discount $49`.

RevenueCat blockers:

- Service account credentials are not uploaded for `Track Speed Android`.
- Google developer notifications cannot be connected until service account credentials are uploaded and saved.
- Store status for Android products is `Could not check info`.
- Android annual discount package currently points to the same Android yearly product as annual full, so there is no real Android discount product/offer yet.

## Google Play Console

Developer account: `Athlete Mindset`

App:

- Name in console: `Trackspeed`
- App ID: `4974495987164553839`
- Application ID: `com.trackspeed.android`
- Status: Draft

Release status:

- Signed AAB uploaded to internal testing.
- Internal testing release `1.0.0 internal test` is active with tester list `Internal testers`.
- Production track is not submitted yet.

Store listing blockers:

- Feature graphic is missing.
- Only one phone screenshot is uploaded; Play requires 2-8 phone screenshots.
- Tablet screenshots are required because the Play form factors currently include tablets.
- Current selected form factors show `Phones, Tablets, Chrome OS, Android XR`.
- Full description repeats the `MULTIPLE START METHODS` section.
- Full description should avoid unverified claims such as `Sub-4ms timing accuracy` unless validated for the Android release build.

Generated local Play assets:

- Feature graphic: `play-assets/feature-graphic-1024x500.png`
- Phone screenshots:
  - `play-assets/upload/phone-01-welcome.png`
  - `play-assets/upload/phone-02-timing.png`
  - `play-assets/upload/phone-03-goals.png`
  - `play-assets/upload/phone-04-progress.png`
  - `play-assets/upload/phone-05-painpoints.png`
- The screenshots are 1080x1920 PNGs from the Android emulator and are suitable for phone upload slots. If tablet form factors remain enabled, these same 9:16 assets can be tried in the 7-inch and 10-inch tablet slots, or tablet/Chrome/XR support should be disabled before submission.

## Product Setup

Google Play model now in use:

- One subscription product ID: `trackspeed_pro`
- Base plans:
  - `weekly`
    - Auto-renewing
    - Billing period: Weekly
    - Google Play price: USD 7.99
    - RevenueCat product identifier: `trackspeed_pro:weekly`
    - Attach to entitlement `Track Speed Pro`
    - Attach to offering package `weekly_default`
  - `yearly`
    - Auto-renewing
    - Billing period: Yearly
    - Google Play price: USD 59.99
    - RevenueCat product identifier: `trackspeed_pro:yearly`
    - Attach to entitlement `Track Speed Pro`
    - Attach to offering package `annual_default_full`
  - `monthly`
    - Auto-renewing
    - Billing period: Monthly
    - Google Play price: USD 8.99
    - RevenueCat product identifier: `trackspeed_pro:monthly`
    - Present for compatibility, but Android UI currently follows iOS and does not show monthly in the main paywall.

Recommended Google Play offers:

- Yearly free-trial offer for first-time users, matching the iOS yearly trial.
- Optional yearly discount offer if Android should match iOS `annual_default_discount`.
- Optional referral/influencer yearly offer if Android should match iOS referral behavior.

## Required Order

1. Upload RevenueCat Google Play service account JSON to `Track Speed Android`.
2. Configure Google real-time developer notifications.
3. Sync/check Android products in RevenueCat until store status is valid.
4. Add a real Android discount product/offer to `annual_default_discount`, or keep annual discount hidden on Android until the product exists.
5. Add license testers and install from Play internal testing.
6. Test yearly and weekly purchases from the Play-installed app.
7. Complete Play store listing/app content requirements.
8. Submit production release after final user confirmation.
