# TrackSpeed Android - Pricing & Subscription Setup Guide

**Last Updated:** February 2026

This document lists every manual step required to set up in-app subscriptions for TrackSpeed Android using RevenueCat and Google Play Billing. Steps are marked to distinguish what needs manual action from what is already handled in code.

---

## Table of Contents

1. [Google Play Console Setup](#1-google-play-console-setup)
2. [Google Play Billing - Subscription Products](#2-google-play-billing---subscription-products)
3. [RevenueCat Dashboard Setup](#3-revenuecat-dashboard-setup)
4. [RevenueCat Android SDK Configuration](#4-revenuecat-android-sdk-configuration)
5. [Testing Checklist](#5-testing-checklist)
6. [Pre-Launch Checklist](#6-pre-launch-checklist)
7. [RevenueCat Webhook Setup (Supabase Integration)](#7-revenuecat-webhook-setup-supabase-integration)

---

## Current State Summary

| Component | Status |
|-----------|--------|
| iOS RevenueCat integration | Done and live |
| iOS API key | `appl_XGiqCpycqHRisYTkyTSpTUgXHCm` |
| Entitlement ID (shared) | `Track Speed Pro` |
| iOS Products | `monthly` ($8.99/mo), `yearly` ($49.99/yr, 7-day trial) |
| Supabase `subscriptions` table | Exists, shared with iOS |
| RevenueCat webhook (website) | Exists at `website/src/app/api/webhooks/revenuecat/route.ts` |
| Android RevenueCat SDK in `build.gradle.kts` | **Not yet added** |
| Android `SubscriptionManager.kt` | **Not yet created** |
| Android paywall UI | **Not yet created** |
| Google Play Console app listing | **Not yet created** |
| Google Play subscription products | **Not yet created** |
| RevenueCat Android app entry | **Not yet created** |

---

## 1. Google Play Console Setup

### 1.1 Create the App Listing

> **Status:** MANUAL - must be done in Google Play Console

1. Go to [Google Play Console](https://play.google.com/console)
2. Click **Create app**
3. Fill in the required fields:
   - **App name:** TrackSpeed
   - **Default language:** English (United States)
   - **App or game:** App
   - **Free or paid:** Free (subscriptions are in-app purchases)
4. Accept the declarations (Developer Program Policies, US export laws)
5. Click **Create app**

### 1.2 Complete the Store Listing

> **Status:** MANUAL

1. Navigate to **Grow > Store presence > Main store listing**
2. Fill in:
   - **Short description** (80 chars max): "Professional sprint timing using your phone's camera"
   - **Full description** (4000 chars max): Describe the app features, mention cross-platform with iOS
   - **App icon:** 512x512 PNG (use the same icon as iOS, exported for Android dimensions)
   - **Feature graphic:** 1024x500 PNG
   - **Screenshots:** At least 2 phone screenshots, recommended 4-8
   - **Phone screenshots:** 16:9 or 9:16 aspect ratio, min 320px, max 3840px per side
3. Fill in **App category:**
   - **Category:** Sports
   - **Tags:** Sprint timing, Track and field, Stopwatch
4. Fill in **Contact details:**
   - Email address (required)
   - Phone number (optional)
   - Website: `https://mytrackspeed.com`

### 1.3 Complete App Content Declarations

> **Status:** MANUAL

1. Navigate to **Policy > App content**
2. Complete each declaration:
   - **Privacy policy:** Enter URL (see Section 6)
   - **Ads:** Declare no ads
   - **App access:** Provide any test credentials if needed (or mark as no special access)
   - **Content ratings:** Complete the IARC questionnaire
   - **Target audience:** 13+ (not designed for children)
   - **News apps:** Not a news app
   - **Data safety:** Complete the data safety form:
     - Camera data: collected, not shared, required
     - Device identifiers: collected, not shared, required
     - Purchase history: collected, not shared, required
     - Location: not collected (unless used for BLE, then declare optional)

### 1.4 Upload a Signed APK/AAB

> **Status:** MANUAL - requires signing key setup

1. Generate a signing key (if not already done):
   ```bash
   keytool -genkey -v -keystore trackspeed-release.keystore \
     -alias trackspeed -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Configure signing in `app/build.gradle.kts`:
   ```kotlin
   android {
       signingConfigs {
           create("release") {
               storeFile = file("path/to/trackspeed-release.keystore")
               storePassword = findProperty("KEYSTORE_PASSWORD") as String? ?: ""
               keyAlias = "trackspeed"
               keyPassword = findProperty("KEY_PASSWORD") as String? ?: ""
           }
       }
       buildTypes {
           release {
               signingConfig = signingConfigs.getByName("release")
           }
       }
   }
   ```
3. Add keystore passwords to `local.properties` (never commit this file):
   ```properties
   KEYSTORE_PASSWORD=your_keystore_password
   KEY_PASSWORD=your_key_password
   ```
4. Build the release AAB:
   ```bash
   ./gradlew bundleRelease
   ```
5. The AAB will be at `app/build/outputs/bundle/release/app-release.aab`

### 1.5 Set Up Internal Testing Track

> **Status:** MANUAL

1. Navigate to **Testing > Internal testing**
2. Click **Create new release**
3. If using Play App Signing (recommended):
   - Click **Continue** to let Google manage the signing key
   - Upload the AAB from step 1.4
4. If managing your own key:
   - Upload the AAB signed with your release key
5. Fill in **Release name** (e.g., "1.0.0-internal.1")
6. Add **Release notes** (e.g., "Initial internal test build")
7. Click **Save** then **Review release** then **Start rollout**

### 1.6 Create an Internal Testing Group

> **Status:** MANUAL

1. Navigate to **Testing > Internal testing > Testers**
2. Click **Create email list**
3. Name it (e.g., "TrackSpeed Testers")
4. Add the Google email addresses of all testers
5. Save the list
6. Share the opt-in URL with testers (they must accept the invitation)
7. Testers install via the Play Store using the opt-in link

**Important:** You MUST upload at least one APK/AAB to any testing track before you can create subscription products. Google Play requires an uploaded binary.

---

## 2. Google Play Billing - Subscription Products

### 2.1 Prerequisites

Before creating subscriptions, ensure:
- [x] App is created in Google Play Console
- [x] At least one APK/AAB is uploaded to any testing track (internal, closed, or open)
- [ ] The app's `build.gradle.kts` includes the billing dependency (see Section 4)

### 2.2 Navigate to Subscriptions

> **Status:** MANUAL

1. In Google Play Console, select your app
2. Go to **Monetization > Products > Subscriptions**
3. Click **Create subscription**

### 2.3 Create Monthly Subscription

> **Status:** MANUAL

1. Click **Create subscription**
2. Fill in:
   - **Product ID:** `trackspeed_pro_monthly`
     - IMPORTANT: This ID is permanent and cannot be changed after creation
     - Must match what the Android code will reference
   - **Name:** TrackSpeed Pro Monthly
   - **Description:** Full access to TrackSpeed Pro features including multi-phone timing, unlimited history, athlete profiles, and more.
3. Click **Save**

#### 2.3.1 Create Base Plan for Monthly

1. After saving the subscription, click **Add base plan**
2. Fill in:
   - **Base plan ID:** `monthly-base`
   - **Auto-renewing:** Yes
   - **Billing period:** 1 Month
   - **Grace period:** 3 days (recommended - matches iOS grace period behavior)
   - **Account hold:** 30 days (gives users time to fix payment issues)
   - **Resubscribe:** Enabled (allow users to resubscribe after cancellation)
3. Click **Set prices**
4. Set the default price:
   - **Price:** $8.99 USD
5. Google will auto-calculate prices for other countries based on the USD price
   - Review and adjust key markets as needed (EUR, GBP, CAD, AUD, etc.)
   - Click **Update** for each country you want to customize
6. Click **Save** then **Activate**

### 2.4 Create Yearly Subscription

> **Status:** MANUAL

1. Go back to **Monetization > Products > Subscriptions**
2. Click **Create subscription**
3. Fill in:
   - **Product ID:** `trackspeed_pro_yearly`
   - **Name:** TrackSpeed Pro Yearly
   - **Description:** Full access to TrackSpeed Pro features. Save 54% compared to monthly.
4. Click **Save**

#### 2.4.1 Create Base Plan for Yearly

1. Click **Add base plan**
2. Fill in:
   - **Base plan ID:** `yearly-base`
   - **Auto-renewing:** Yes
   - **Billing period:** 1 Year
   - **Grace period:** 7 days (recommended for yearly - longer since yearly is more valuable)
   - **Account hold:** 30 days
   - **Resubscribe:** Enabled
3. Click **Set prices**
4. Set the default price:
   - **Price:** $49.99 USD
5. Review auto-calculated international prices and adjust if needed
6. Click **Save** (do NOT activate yet - add the free trial offer first)

#### 2.4.2 Add 7-Day Free Trial to Yearly

1. On the yearly subscription page, under the base plan, click **Add offer**
2. Fill in:
   - **Offer ID:** `yearly-free-trial`
   - **Eligibility:** New customer acquisition (first-time subscribers only)
3. Under **Phases**, click **Add phase**:
   - **Type:** Free trial
   - **Duration:** 7 days
4. Click **Save** then **Activate** the offer
5. Now **Activate** the base plan

#### 2.4.3 (Optional) Add 30-Day Free Trial for Referrals/Influencers

If you want a separate offer for referred users or influencer codes:

1. On the yearly subscription, click **Add offer**
2. Fill in:
   - **Offer ID:** `yearly-referral-trial`
   - **Eligibility:** Developer determined (you control via code)
3. Under **Phases**, click **Add phase**:
   - **Type:** Free trial
   - **Duration:** 30 days
4. Click **Save** then **Activate**

### 2.5 Pricing Summary

| Product ID | Price | Trial | Grace Period |
|-----------|-------|-------|--------------|
| `trackspeed_pro_monthly` | $8.99/month | None | 3 days |
| `trackspeed_pro_yearly` | $49.99/year | 7-day free trial | 7 days |
| `trackspeed_pro_yearly` (referral offer) | $49.99/year | 30-day free trial | 7 days |

### 2.6 Country-Specific Pricing Adjustments

> **Status:** MANUAL - optional but recommended

Google auto-converts USD to local currencies, but some markets benefit from manual adjustment:

1. On each base plan, click **Manage prices**
2. Review and consider adjusting for:
   - **India (INR):** Consider a lower price point (e.g., INR 249/month) for market fit
   - **Brazil (BRL):** Consider a lower price point
   - **EU (EUR):** Usually $8.99 USD maps to ~8.99 EUR, which is fine
   - **UK (GBP):** Usually maps reasonably
   - **Japan (JPY):** Consider rounding to a "nice" JPY price
3. Make sure prices match (or are close to) the iOS pricing for parity

---

## 3. RevenueCat Dashboard Setup

### 3.1 Access the Existing RevenueCat Project

> **Status:** MANUAL

The iOS app already uses RevenueCat. You need to add Android to the same project.

1. Log in to [RevenueCat Dashboard](https://app.revenuecat.com)
2. Open the existing project (the one containing the iOS app with key `appl_XGiqCpycqHRisYTkyTSpTUgXHCm`)

### 3.2 Create a Google Play Service Account

> **Status:** MANUAL - required for RevenueCat to validate purchases

RevenueCat needs a Google Cloud service account with Play Developer API access to validate Android purchases and manage subscriptions.

#### Step 1: Enable the Google Play Developer API

1. Go to [Google Cloud Console](https://console.cloud.google.com)
2. Select (or create) the Google Cloud project linked to your Play Console
3. Navigate to **APIs & Services > Library**
4. Search for **Google Play Android Developer API**
5. Click **Enable**

#### Step 2: Create a Service Account

1. In Google Cloud Console, go to **IAM & Admin > Service Accounts**
2. Click **Create Service Account**
3. Fill in:
   - **Name:** RevenueCat Play Billing
   - **Description:** Service account for RevenueCat to access Play Developer API
4. Click **Create and Continue**
5. For the role, skip this step (permissions are granted in Play Console instead)
6. Click **Done**
7. Click on the newly created service account
8. Go to the **Keys** tab
9. Click **Add Key > Create new key**
10. Select **JSON** format
11. Click **Create** - a JSON file will download
12. **Save this file securely** - you will upload it to RevenueCat

#### Step 3: Grant Access in Google Play Console

1. Go to [Google Play Console](https://play.google.com/console)
2. Navigate to **Settings > API access** (under Setup in the left sidebar)
3. You should see the service account listed under "Service accounts"
   - If not, click **Link** next to your Google Cloud project
4. Click **Grant access** next to the service account
5. Set permissions:
   - **App permissions:** Select TrackSpeed
   - Under **Account permissions**, enable:
     - **Financial data, orders, and cancellation survey responses > View financial data, orders, and cancellation survey responses**: Yes
     - **Manage orders and subscriptions**: Yes
6. Click **Invite user**
7. **Wait 24-48 hours** for the service account to propagate (Google's delay)
   - RevenueCat purchase validation will not work until propagation completes

### 3.3 Add Android App in RevenueCat

> **Status:** MANUAL

1. In RevenueCat Dashboard, go to **Project Settings > Apps**
2. Click **+ New App**
3. Select **Google Play Store**
4. Fill in:
   - **App name:** TrackSpeed Android
   - **Package name:** `com.trackspeed.android`
5. Upload the service account JSON key file from Step 3.2
6. Click **Save**
7. RevenueCat will display a **Google API Key** with the `goog_` prefix
   - Example: `goog_XXXXXXXXXXXXXXXXXXXXXXXXXX`
   - **Save this key** - you will add it to `local.properties`

### 3.4 Configure Products in RevenueCat

> **Status:** MANUAL

#### 3.4.1 Add Android Products

1. Go to **Products** in the RevenueCat sidebar
2. Click **+ New Product**
3. Add the monthly product:
   - **App:** TrackSpeed Android (Google Play)
   - **Product identifier:** `trackspeed_pro_monthly:monthly-base`
     - Format for Google Play: `subscription_id:base_plan_id`
4. Click **Save**
5. Add the yearly product:
   - **App:** TrackSpeed Android (Google Play)
   - **Product identifier:** `trackspeed_pro_yearly:yearly-base`
6. Click **Save**

#### 3.4.2 Configure the Entitlement

1. Go to **Entitlements** in the sidebar
2. Open the existing **Track Speed Pro** entitlement
   - This entitlement already contains the iOS products
3. Click **Attach** to add Android products:
   - Attach `trackspeed_pro_monthly:monthly-base`
   - Attach `trackspeed_pro_yearly:yearly-base`
4. Click **Save**

Now the `Track Speed Pro` entitlement covers both iOS and Android products.

#### 3.4.3 Configure Offerings

1. Go to **Offerings** in the sidebar
2. Open the existing **default** offering (the one iOS uses)
3. Add the Android packages:
   - The **Monthly ($rc_monthly)** package should already have the iOS product. Click **Edit** and also add the Android product `trackspeed_pro_monthly:monthly-base`
   - The **Annual ($rc_annual)** package should have the iOS product. Click **Edit** and also add the Android product `trackspeed_pro_yearly:yearly-base`
4. If there is an **annual_referral** package (for 30-day trial offers), add the Android referral product if you created one in Step 2.4.3:
   - Add `trackspeed_pro_yearly:yearly-referral-trial`

**Note:** RevenueCat offerings are cross-platform. The same offering serves both iOS and Android, but each package contains platform-specific products.

### 3.5 Set Up Platform Server Notifications (Real-Time Developer Notifications)

> **Status:** MANUAL - important for accurate subscription tracking

Google Play uses Pub/Sub for real-time subscription notifications. This tells RevenueCat about renewals, cancellations, and billing issues without waiting for the app to open.

#### Step 1: Create a Google Cloud Pub/Sub Topic

1. Go to [Google Cloud Console > Pub/Sub](https://console.cloud.google.com/cloudpubsub)
2. Click **Create Topic**
3. Fill in:
   - **Topic ID:** `play-billing-notifications` (or similar)
4. Click **Create**

#### Step 2: Get RevenueCat's Pub/Sub Subscription

1. In RevenueCat Dashboard, go to **Project Settings > Apps > TrackSpeed Android**
2. Scroll to **Google Real-Time Developer Notifications**
3. RevenueCat will display:
   - A **Pub/Sub subscription name** or instructions to grant subscriber access
4. Copy the provided subscription name

#### Step 3: Grant RevenueCat Access to the Topic

1. Back in Google Cloud Console, click on your topic
2. Go to the **Subscriptions** tab
3. Create a subscription with the name RevenueCat provided, pointed at the topic
4. OR: If RevenueCat provides a service account email, go to the topic's **Permissions** tab and add that email with the **Pub/Sub Subscriber** role

#### Step 4: Configure in Google Play Console

1. Go to Google Play Console > **Settings > API access**
2. Scroll to **Real-time notifications**
3. Set the **Topic name** to the full Pub/Sub topic path:
   ```
   projects/YOUR_PROJECT_ID/topics/play-billing-notifications
   ```
4. Click **Save** then **Send test notification** to verify

#### Step 5: Verify in RevenueCat

1. Back in RevenueCat Dashboard, the notification status should show as connected
2. RevenueCat will now receive real-time updates about:
   - Subscription renewals
   - Cancellations
   - Billing issues / grace periods
   - Subscription pauses and resumes

---

## 4. RevenueCat Android SDK Configuration

### 4.1 Add Dependencies

> **Status:** CODE CHANGE NEEDED - add to `app/build.gradle.kts`

Add the RevenueCat SDK dependency:

```kotlin
// In app/build.gradle.kts dependencies block
implementation("com.revenuecat.purchases:purchases:8.10.7")
implementation("com.revenuecat.purchases:purchases-ui:8.10.7")  // Optional: for RevenueCat Paywalls
```

Add the billing permission to `AndroidManifest.xml` (RevenueCat handles this automatically via its manifest, but for clarity):

```xml
<uses-permission android:name="com.android.vending.BILLING" />
```

### 4.2 Add API Key to local.properties

> **Status:** MANUAL - after getting the key from RevenueCat dashboard (Section 3.3)

Add to `local.properties`:

```properties
REVENUECAT_API_KEY=goog_XXXXXXXXXXXXXXXXXXXXXXXXXX
```

### 4.3 Expose API Key via BuildConfig

> **Status:** CODE CHANGE NEEDED - modify `app/build.gradle.kts`

In the `defaultConfig` block of `app/build.gradle.kts`, add:

```kotlin
buildConfigField("String", "REVENUECAT_API_KEY", "\"${findProperty("REVENUECAT_API_KEY") ?: ""}\"")
```

This follows the same pattern already used for `SUPABASE_URL` and `SUPABASE_ANON_KEY`.

### 4.4 Initialize RevenueCat in Application Class

> **Status:** CODE CHANGE NEEDED - modify `TrackSpeedApp.kt`

The iOS app initializes RevenueCat in `SprintTimerApp.swift` via `SubscriptionManager.configure()`. The Android equivalent:

```kotlin
// In TrackSpeedApp.kt (Application class)
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration

class TrackSpeedApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configure RevenueCat
        val config = PurchasesConfiguration.Builder(this, BuildConfig.REVENUECAT_API_KEY)
            .build()
        Purchases.configure(config)

        // Enable debug logs in debug builds
        if (BuildConfig.DEBUG) {
            Purchases.logLevel = LogLevel.DEBUG
        }
    }
}
```

### 4.5 Create SubscriptionManager

> **Status:** CODE CHANGE NEEDED - create new file

Create `app/src/main/kotlin/com/trackspeed/android/subscription/SubscriptionManager.kt` matching the iOS `SubscriptionManager.swift` pattern. Key elements:

- Check entitlement `"Track Speed Pro"` for pro status
- Expose `isProUser`, `offerings`, `customerInfo` as state
- Handle promo access via Supabase (same as iOS)
- Match the iOS pro feature gating logic

### 4.6 Existing Code References

The iOS app uses these identifiers that must match on Android:

| Identifier | iOS Value | Android Value |
|-----------|-----------|---------------|
| RevenueCat API Key | `appl_XGiqCpycqHRisYTkyTSpTUgXHCm` | `goog_XXXXXXXXXX` (from Section 3.3) |
| Entitlement ID | `Track Speed Pro` | `Track Speed Pro` (same) |
| Monthly Product | `monthly` (App Store) | `trackspeed_pro_monthly:monthly-base` (Google Play) |
| Yearly Product | `yearly` (App Store) | `trackspeed_pro_yearly:yearly-base` (Google Play) |

### 4.7 Test Sandbox Purchases

> **Status:** MANUAL + CODE

For sandbox testing:

1. The RevenueCat SDK automatically detects sandbox vs production based on the build
2. In debug builds, purchases go through Google's test environment
3. License testers (see Section 5.1) can make test purchases without being charged

---

## 5. Testing Checklist

### 5.1 Add Google Accounts as License Testers

> **Status:** MANUAL

License testers can make purchases without being charged. This is essential for testing.

1. Go to Google Play Console > **Settings > License testing**
2. Add the Gmail addresses of all testers
3. Set **License response** to `RESPOND_NORMALLY` (for realistic testing)
4. Click **Save**

**Important:** License testers must:
- Use a Google account listed in the license testing settings
- Install the app from Google Play (via internal/closed testing track)
- NOT be the same Google account that owns the Play Console

### 5.2 Test Purchase Flow

> **Status:** MANUAL

- [ ] Open the paywall screen
- [ ] Verify monthly product shows correct price ($8.99/month)
- [ ] Verify yearly product shows correct price ($49.99/year)
- [ ] Verify yearly product shows "7-day free trial" label
- [ ] Tap monthly - Google Play purchase dialog appears
- [ ] Complete a test monthly purchase - entitlement becomes active
- [ ] Verify `isProUser` returns `true` immediately after purchase
- [ ] Verify pro features unlock (multi-phone timing, etc.)
- [ ] Cancel and test yearly with trial

### 5.3 Test Restore Purchases

> **Status:** MANUAL

- [ ] Purchase a subscription on one device
- [ ] Install the app on a second device (or reinstall)
- [ ] Tap "Restore Purchases"
- [ ] Verify entitlement is restored
- [ ] Verify this works with the same Google account

### 5.4 Test Subscription Cancellation

> **Status:** MANUAL

- [ ] Purchase a test subscription
- [ ] Cancel it via Google Play > Subscriptions
- [ ] Verify app detects cancellation (`willRenew` = false)
- [ ] Verify access continues until expiration date
- [ ] Verify access is revoked after expiration

### 5.5 Test Grace Period Behavior

> **Status:** MANUAL

- [ ] Google's test environment simulates billing issues automatically for test cards
- [ ] Verify app detects `isInBillingGracePeriod` when billing fails
- [ ] Verify billing issue banner is displayed (matching iOS `BillingIssueBanner`)
- [ ] Verify access continues during grace period
- [ ] Verify access is revoked after grace period expires

### 5.6 Test Promo Code Flow

> **Status:** MANUAL

The iOS app supports two types of promo codes via Supabase:

1. **Admin codes:** Instant Pro access (forever or time-limited)
2. **Influencer codes:** 30-day free trial via extended trial offer

Test both flows:
- [ ] Enter an admin promo code - verify instant Pro access
- [ ] Enter an influencer code - verify 30-day trial offer is presented
- [ ] Verify promo status is checked via Supabase `getActivePromoAccess()`

### 5.7 Test Cross-Platform Entitlements

> **Status:** MANUAL

If a user subscribes on iOS and then logs into the Android app with the same RevenueCat user ID:
- [ ] Verify Pro access is recognized on Android
- [ ] This requires the user to be logged into RevenueCat with the same ID on both platforms
- [ ] Test by calling `Purchases.shared.logIn(userId)` with the Supabase auth user ID

---

## 6. Pre-Launch Checklist

### 6.1 Privacy Policy URL

> **Status:** ALREADY EXISTS (verify URL is correct)

- URL: `https://mytrackspeed.com/privacy`
- This URL is already set up for the iOS app and covers both platforms
- Verify the privacy policy mentions:
  - [x] Camera usage for timing detection
  - [x] Bluetooth usage for device pairing
  - [x] Data collected and stored
  - [ ] Google Play-specific language (purchase data, Google account)
- Enter this URL in:
  - [ ] Google Play Console > Store listing > Privacy policy
  - [ ] Google Play Console > App content > Privacy policy

### 6.2 Terms of Service URL

> **Status:** ALREADY EXISTS (verify URL is correct)

- URL: `https://mytrackspeed.com/terms`
- Enter this URL in:
  - [ ] Google Play Console > Store listing (if required)

### 6.3 Subscription Disclosure Text

> **Status:** CODE CHANGE NEEDED - include in paywall UI

Google Play requires subscription disclosure text to be visible before purchase. Include the following on the paywall screen (adapted from the iOS `AppConfig.AppStore.subscriptionDisclosure`):

```
TrackSpeed Pro is available as a monthly ($8.99/month) or annual ($49.99/year)
subscription. Payment will be charged to your Google Play account at confirmation
of purchase. Subscription automatically renews unless canceled at least 24 hours
before the end of the current period. You can manage and cancel your subscriptions
in the Google Play Store app under Subscriptions. Any unused portion of a free
trial period will be forfeited when purchasing a subscription.
```

This text must be:
- Visible on the paywall screen before the user taps "Subscribe"
- In legible font size (not hidden in fine print)

### 6.4 Google Play Subscription Policy Compliance

> **Status:** MANUAL - review before launch

Google's subscription policies require:

- [ ] **Clear pricing:** Display the price, billing period, and trial length before purchase
- [ ] **Cancellation info:** Tell users how to cancel (Google Play > Subscriptions)
- [ ] **No misleading trials:** If offering a free trial, clearly state when billing begins
- [ ] **Renewal disclosure:** State that the subscription auto-renews
- [ ] **Offer code compliance:** If using promo/offer codes, they must comply with Google's policies
- [ ] **Subscription management link:** Provide a way for users to manage their subscription from within the app (deep link to Google Play subscription settings)

### 6.5 App Content Rating

> **Status:** MANUAL

1. Complete the IARC content rating questionnaire in Google Play Console
2. The app should receive a rating of **Everyone** or **Everyone 10+**

### 6.6 Data Safety Form

> **Status:** MANUAL

Complete the Data Safety form in Google Play Console:

| Data Type | Collected | Shared | Purpose |
|-----------|-----------|--------|---------|
| Camera data | Yes | No | App functionality (timing detection) |
| Purchase history | Yes | No | Subscription management |
| Device ID | Yes | No | Analytics, subscription tracking |
| Crash logs | Yes | No | App stability |
| Performance data | Yes | No | App quality |

---

## 7. RevenueCat Webhook Setup (Supabase Integration)

### 7.1 Overview

The existing webhook at `website/src/app/api/webhooks/revenuecat/route.ts` already handles RevenueCat events and writes to the Supabase `subscriptions` table. This webhook works for **both** iOS and Android purchases because RevenueCat sends the same event format regardless of platform.

The `subscriptions` table already has a `store` column that distinguishes `APP_STORE` vs `PLAY_STORE`.

### 7.2 Verify Webhook Configuration

> **Status:** MANUAL - verify existing webhook covers Android

1. Log in to [RevenueCat Dashboard](https://app.revenuecat.com)
2. Go to **Project Settings > Integrations > Webhooks**
3. Verify the webhook URL is set:
   - URL: `https://mytrackspeed.com/api/webhooks/revenuecat` (or wherever the website is deployed)
4. Verify the **Authorization header** is configured:
   - The webhook route expects `Authorization: Bearer <REVENUECAT_WEBHOOK_AUTH_KEY>`
   - The `REVENUECAT_WEBHOOK_AUTH_KEY` environment variable must be set on the website deployment
5. Verify the webhook is set to fire for the Android app:
   - In the webhook configuration, ensure **all apps** are selected (or explicitly include the Android app)

### 7.3 Webhook Events to Listen For

The existing webhook handler already processes these events (from `route.ts`):

| Event | Handler | Description |
|-------|---------|-------------|
| `INITIAL_PURCHASE` | Sets status `active` | First-time subscription purchase |
| `RENEWAL` | Sets status `active` | Subscription renewed |
| `CANCELLATION` | Sets status `cancelled` | User cancelled (still active until expiry) |
| `UNCANCELLATION` | Sets status `active` | User re-enabled auto-renewal |
| `EXPIRATION` | Sets status `expired` | Subscription expired |
| `BILLING_ISSUE` | Sets status `billing_issue` | Payment failed, grace period active |
| `TRIAL_STARTED` | Sets status `active`, `is_trial = true` | Free trial started |
| `TRIAL_CONVERTED` | Sets status `active`, `is_trial = false` | Trial converted to paid |
| `TRIAL_CANCELLED` | Sets status `cancelled` | Trial cancelled before conversion |
| `PRODUCT_CHANGE` | Updates `product_id` | User changed plan (monthly to yearly) |
| `TRANSFER` | Updates `app_user_id` | Subscription transferred between users |
| `SUBSCRIBER_ALIAS` | No-op logged | RevenueCat user alias created |

The handler also:
- Records payment events in `subscription_events` for revenue tracking
- Handles influencer commission calculation
- Handles referral rewards (grants 1 free month to referrer)
- Handles refund clawbacks for influencer commissions

### 7.4 Verify Supabase Tables Exist

> **Status:** ALREADY DONE - verify these tables exist in Supabase

The following tables should already exist (created by iOS migrations):

- `subscriptions` - Main subscription records (see `20260125_create_subscriptions.sql`)
- `subscription_events` - Payment event log (see `20260125_add_subscription_revenue.sql`)

Verify by running in Supabase SQL Editor:
```sql
SELECT * FROM subscriptions LIMIT 5;
SELECT * FROM subscription_events LIMIT 5;
```

### 7.5 Test the Webhook with Android Purchases

> **Status:** MANUAL - after completing all previous setup steps

1. Make a test purchase on Android (using a license tester account)
2. Check RevenueCat Dashboard > **Events** to verify the event was recorded
3. Check RevenueCat Dashboard > **Integrations > Webhooks** to verify the webhook was called
4. Check Supabase `subscriptions` table to verify the record was created:
   ```sql
   SELECT * FROM subscriptions WHERE store = 'PLAY_STORE' ORDER BY created_at DESC LIMIT 5;
   ```
5. Verify the `store` field is `PLAY_STORE` (not `APP_STORE`)

### 7.6 Environment Variables Required

The webhook (deployed as part of the website) needs these environment variables:

| Variable | Purpose | Status |
|----------|---------|--------|
| `REVENUECAT_WEBHOOK_AUTH_KEY` | Authenticate incoming webhooks | Should already be set |
| `REVENUECAT_SECRET_KEY` | Grant promotional entitlements (for referral rewards) | Should already be set |
| `SUPABASE_URL` | Connect to Supabase | Should already be set |
| `SUPABASE_SERVICE_ROLE_KEY` | Write to Supabase with elevated permissions | Should already be set |

No new environment variables are needed for Android support - the existing webhook handles both platforms.

---

## Appendix A: Quick Reference - Product ID Mapping

| Platform | Product ID (Store) | RevenueCat Product ID | Base Plan |
|----------|-------------------|----------------------|-----------|
| iOS | `monthly` | `monthly` | N/A (App Store) |
| iOS | `yearly` | `yearly` | N/A (App Store) |
| Android | `trackspeed_pro_monthly` | `trackspeed_pro_monthly:monthly-base` | `monthly-base` |
| Android | `trackspeed_pro_yearly` | `trackspeed_pro_yearly:yearly-base` | `yearly-base` |

## Appendix B: Estimated Timeline

| Step | Duration | Dependencies |
|------|----------|-------------|
| Google Play Console setup + listing | 1-2 hours | None |
| Upload signed AAB | 30 minutes | Signing key |
| Create subscription products | 30 minutes | AAB uploaded |
| Google Cloud service account | 1 hour | Google Cloud project |
| RevenueCat dashboard setup | 1 hour | Service account JSON key |
| Pub/Sub notifications setup | 30 minutes | Service account |
| SDK integration (code) | 2-3 days | All dashboard setup |
| Testing | 1-2 days | Service account propagation (24-48h) |
| **Total** | **~1 week** | - |

**Critical path:** The Google Cloud service account takes 24-48 hours to propagate after being granted access in Play Console. Plan accordingly.

## Appendix C: Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `app/build.gradle.kts` | Modify | Add RevenueCat dependency, `REVENUECAT_API_KEY` BuildConfig field |
| `local.properties` | Modify | Add `REVENUECAT_API_KEY=goog_XXX` |
| `app/src/main/kotlin/.../subscription/SubscriptionManager.kt` | Create | RevenueCat integration (port of iOS `SubscriptionManager.swift`) |
| `app/src/main/kotlin/.../ui/screens/paywall/PaywallScreen.kt` | Create | Subscription purchase UI |
| `app/src/main/kotlin/.../TrackSpeedApp.kt` | Modify | Initialize RevenueCat SDK |
| `proguard-rules.pro` | Modify | Add RevenueCat ProGuard rules (if needed) |
