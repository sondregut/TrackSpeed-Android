# TrackSpeed Android - Dependencies & Requirements

**Last Updated:** January 2026

---

## 1. Gradle Dependencies

### Core Android

```kotlin
// build.gradle.kts (app)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.trackspeed.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trackspeed.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Hilt (Dependency Injection)
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")

    // Room (Local Database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Camera
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // ML Kit Pose Detection
    implementation("com.google.mlkit:pose-detection:18.0.0-beta3")
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta3")

    // Supabase
    implementation(platform("io.github.jan-tennert.supabase:bom:2.0.4"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:gotrue-kt")

    // Ktor (HTTP client for Supabase)
    implementation("io.ktor:ktor-client-android:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
```

### Version Catalog (Optional)

```toml
# gradle/libs.versions.toml

[versions]
kotlin = "1.9.22"
compose-bom = "2024.01.00"
compose-compiler = "1.5.8"
hilt = "2.50"
room = "2.6.1"
camera = "1.3.1"
mlkit-pose = "18.0.0-beta3"
supabase = "2.0.4"
ktor = "2.3.7"
coroutines = "1.7.3"
navigation = "2.7.6"
lifecycle = "2.7.0"

[libraries]
# Kotlin
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
kotlinx-coroutines = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-serialization = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version = "1.6.2" }

# Compose
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-tooling = { module = "androidx.compose.ui:ui-tooling" }

# Hilt
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-navigation = { module = "androidx.hilt:hilt-navigation-compose", version = "1.1.0" }

# Room
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }

# Camera
camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camera" }
camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camera" }

# ML Kit
mlkit-pose = { module = "com.google.mlkit:pose-detection", version.ref = "mlkit-pose" }

# Supabase
supabase-bom = { module = "io.github.jan-tennert.supabase:bom", version.ref = "supabase" }
supabase-postgrest = { module = "io.github.jan-tennert.supabase:postgrest-kt" }
supabase-realtime = { module = "io.github.jan-tennert.supabase:realtime-kt" }
supabase-storage = { module = "io.github.jan-tennert.supabase:storage-kt" }
supabase-gotrue = { module = "io.github.jan-tennert.supabase:gotrue-kt" }

# Ktor
ktor-android = { module = "io.ktor:ktor-client-android", version.ref = "ktor" }

[bundles]
compose = ["compose-ui", "compose-material3", "compose-preview"]
supabase = ["supabase-postgrest", "supabase-realtime", "supabase-storage", "supabase-gotrue"]

[plugins]
android-application = { id = "com.android.application", version = "8.2.2" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version = "1.9.22-1.0.17" }
```

---

## 2. Android Manifest

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Camera -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />

    <!-- Bluetooth -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation"
        tools:targetApi="s" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

    <!-- Location (required for BLE scanning on older Android) -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <!-- Network -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Foreground Service (for timing) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />

    <!-- Vibration -->
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:name=".TrackSpeedApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.TrackSpeed">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.TrackSpeed">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Foreground service for active timing -->
        <service
            android:name=".service.TimingService"
            android:foregroundServiceType="camera"
            android:exported="false" />

    </application>

</manifest>
```

---

## 3. ProGuard Rules

```proguard
# proguard-rules.pro

# Keep Kotlin metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Kotlinx Serialization
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.trackspeed.android.**$$serializer { *; }
-keepclassmembers class com.trackspeed.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.trackspeed.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Supabase / Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.atomicfu.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
```

---

## 4. Build Configuration

```kotlin
// build.gradle.kts (app)

android {
    buildTypes {
        debug {
            buildConfigField("String", "SUPABASE_URL", "\"${findProperty("SUPABASE_URL") ?: ""}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${findProperty("SUPABASE_ANON_KEY") ?: ""}\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "SUPABASE_URL", "\"${findProperty("SUPABASE_URL") ?: ""}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${findProperty("SUPABASE_ANON_KEY") ?: ""}\"")
        }
    }
}
```

```properties
# local.properties (DO NOT COMMIT)
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
```

---

## 5. Device Requirements

### Minimum Requirements

| Requirement | Specification |
|-------------|---------------|
| Android Version | 8.0 (API 26) |
| Camera | Any rear camera |
| Bluetooth | BLE 4.0+ |
| RAM | 2GB+ |

### Recommended for Best Performance

| Requirement | Specification |
|-------------|---------------|
| Android Version | 12+ (API 31) |
| Camera | High-speed capture (120-240fps) |
| Processor | Snapdragon 8 Gen 1+ / Tensor G2+ |
| RAM | 6GB+ |

### High-Speed Camera Support

| Device | Max FPS | Notes |
|--------|---------|-------|
| Pixel 8 Pro | 240 | Full support |
| Pixel 8 | 240 | Full support |
| Pixel 7 Pro | 240 | Full support |
| Samsung S24 Ultra | 240 | Full support |
| Samsung S24 | 240 | Full support |
| Samsung S23 | 240 | Full support |
| OnePlus 12 | 240 | Full support |
| Pixel 6 | 120 | Good |
| Samsung A54 | 60 | Basic |

---

## 6. External Services

### Supabase

```
Required Supabase features:
- Authentication (optional for MVP)
- Database (PostgreSQL)
- Realtime (WebSocket subscriptions)
- Storage (image uploads - future)

Tables:
- race_events (existing, shared with iOS)
- user_profiles (future)
- sessions (future)
```

### RevenueCat (Post-MVP)

```
Required for:
- Subscription management
- In-app purchases
- Cross-platform entitlements

Products:
- trackspeed_monthly
- trackspeed_yearly
```

---

## 7. SDK Versions Summary

| SDK | Version | Purpose |
|-----|---------|---------|
| Kotlin | 1.9.22 | Language |
| Compose BOM | 2024.01.00 | UI |
| Compose Compiler | 1.5.8 | Compose compilation |
| Hilt | 2.50 | DI |
| Room | 2.6.1 | Database |
| CameraX | 1.3.1 | Camera |
| ML Kit Pose | 18.0.0-beta3 | Pose detection |
| Supabase | 2.0.4 | Backend |
| Ktor | 2.3.7 | HTTP |
| Navigation | 2.7.6 | Navigation |
| Lifecycle | 2.7.0 | Lifecycle |

---

## 8. Development Tools

### Required

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34
- Kotlin plugin 1.9.22

### Recommended

- Ktlint for code formatting
- Detekt for static analysis
- LeakCanary for memory leak detection (debug builds)

```kotlin
// Debug dependencies
debugImplementation("com.squareup.leakcanary:leakcanary-android:2.13")
```
