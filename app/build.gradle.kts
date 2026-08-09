import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// Load local.properties so secrets are available via localProp()
val localProperties = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}
fun localProp(key: String): String = localProperties.getProperty(key) ?: ""

val releaseStoreFilePath = localProp("TRACKSPEED_RELEASE_STORE_FILE")
val hasReleaseSigning = releaseStoreFilePath.isNotBlank() &&
    localProp("TRACKSPEED_RELEASE_STORE_PASSWORD").isNotBlank() &&
    localProp("TRACKSPEED_RELEASE_KEY_ALIAS").isNotBlank() &&
    localProp("TRACKSPEED_RELEASE_KEY_PASSWORD").isNotBlank()

android {
    namespace = "com.trackspeed.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trackspeed.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Supabase configuration - will be loaded from local.properties
        buildConfigField("String", "SUPABASE_URL", "\"https://hkvrttatbpjwzuuckbqj.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProp("SUPABASE_ANON_KEY")}\"")

        // RevenueCat API key - loaded from local.properties
        buildConfigField("String", "REVENUECAT_API_KEY", "\"${localProp("REVENUECAT_API_KEY")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localProp("GOOGLE_WEB_CLIENT_ID")}\"")
        buildConfigField("String", "POSTHOG_API_KEY", "\"phc_p86OOiNE9o0I8AlDXwRBd7bmcpG4wz7SFt1RSjFey0r\"")
        buildConfigField("String", "POSTHOG_HOST", "\"https://eu.i.posthog.com\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = localProp("TRACKSPEED_RELEASE_STORE_PASSWORD")
                keyAlias = localProp("TRACKSPEED_RELEASE_KEY_ALIAS")
                keyPassword = localProp("TRACKSPEED_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The in-app language picker must work immediately for every advertised
    // locale, including offline installs delivered from an Android App Bundle.
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    // Android Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.foundation)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.android)

    // Kotlin
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Splash Screen
    implementation(libs.androidx.splashscreen)

    // AppCompat (per-app language support on API < 33)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Credential Manager (Google Sign-In)
    implementation(libs.credential.manager)
    implementation(libs.credential.manager.play.services)
    implementation(libs.google.id)

    // Camera (Camera2 only - no CameraX needed for Photo Finish mode)
    implementation(libs.camera.camera2)

    // Video overlay export (matches iOS VideoOverlayFlow)
    implementation(libs.media3.common)
    implementation(libs.media3.effect)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)
    implementation(libs.media3.ui)

    // RevenueCat (subscriptions)
    implementation(libs.revenuecat.purchases)
    implementation(libs.revenuecat.purchases.ui)

    // PostHog (analytics + crash diagnostics)
    implementation(libs.posthog.android)

    // Firebase Analytics (Google Ads attribution + conversion measurement)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    // Google Play In-App Review
    implementation("com.google.android.play:review:2.0.2")
    implementation("com.google.android.play:review-ktx:2.0.2")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
