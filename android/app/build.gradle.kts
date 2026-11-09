import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val props = Properties().apply {
    val f = rootProject.file("drivelink.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String = ""): String {
    val v = props.getProperty(key)?.trim().orEmpty().ifEmpty { System.getenv(key)?.trim().orEmpty() }
    return v.ifEmpty { default }
}

android {
    namespace = "com.drivelink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.drivelink"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // Public client values only. Never put the service_role key here.
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL", "https://zfvbaicrqejktfiszefr.supabase.co")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("SUPABASE_ANON_KEY", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpmdmJhaWNycWVqa3RmaXN6ZWZyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTM5NjM5NjgsImV4cCI6MjEwOTUzOTk2OH0.vOGLqCB2w8EmTb90BrXIWtFV9rGusYF56X8z3mjmD9E")}\"")
        // Map + routing providers (no API keys needed). Override in drivelink.properties / env for self-hosted servers.
        buildConfigField("String", "MAP_STYLE_LIGHT_URL", "\"${cfg("MAP_STYLE_LIGHT_URL", "https://tiles.openfreemap.org/styles/liberty")}\"")
        buildConfigField("String", "MAP_STYLE_DARK_URL", "\"${cfg("MAP_STYLE_DARK_URL", "https://tiles.openfreemap.org/styles/dark")}\"")
        buildConfigField("String", "ROUTING_BASE_URL", "\"${cfg("ROUTING_BASE_URL", "https://router.project-osrm.org")}\"")
        buildConfigField("String", "PHOTON_BASE_URL", "\"${cfg("PHOTON_BASE_URL", "https://photon.komoot.io")}\"")
        buildConfigField("String", "GEOCODER_BASE_URL", "\"${cfg("GEOCODER_BASE_URL", "https://nominatim.openstreetmap.org")}\"")
        buildConfigField("String", "CRED_PUBLIC_KEY_B64", "\"${cfg("CRED_PUBLIC_KEY_B64")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // MapLibre Native (OpenGL ES build). 11.x matches this project's Kotlin 1.9 / compileSdk 34 toolchain; 13.x needs Kotlin 2.2.
    implementation("org.maplibre.gl:android-sdk:11.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
