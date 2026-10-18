import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Gemini key lives in ../.env (git-ignored) and is compiled into BuildConfig.
val env = Properties().apply {
    rootProject.file(".env").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.happypipaliya.mealtracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.happypipaliya.mealtracker"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"${env.getProperty("GEMINI_API_KEY", "")}\"")
        // Optional personal data.gov.in key (more rows per request); the public sample key is used otherwise.
        buildConfigField("String", "DATA_GOV_KEY", "\"${env.getProperty("DATA_GOV_KEY", "")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":sleepcore"))
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
