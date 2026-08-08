plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val iLauncherVersionCode = providers.gradleProperty("iLauncherVersionCode")
    .orNull
    ?.toIntOrNull()
    ?: 1
val iLauncherVersionName = providers.gradleProperty("iLauncherVersionName")
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?: "0.1.0"

android {
    namespace = "com.andreassamitsch.ilauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andreassamitsch.ilauncher"
        minSdk = 26
        targetSdk = 36
        versionCode = iLauncherVersionCode
        versionName = iLauncherVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
