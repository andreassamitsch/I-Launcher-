plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val joynVersionCode = providers.gradleProperty("joynVersionCode")
    .orNull
    ?.toIntOrNull()
    ?: 1
val joynVersionName = providers.gradleProperty("joynVersionName")
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?: "0.1.0"

val stableSigningStoreFile = System.getenv("IL_SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
val stableSigningStorePassword = System.getenv("IL_SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val stableSigningKeyAlias = System.getenv("IL_SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val stableSigningKeyPassword = System.getenv("IL_SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val stableSigningConfigured = listOf(
    stableSigningStoreFile,
    stableSigningStorePassword,
    stableSigningKeyAlias,
    stableSigningKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.andreassamitsch.joyntv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andreassamitsch.joyntv"
        minSdk = 26
        targetSdk = 36
        versionCode = joynVersionCode
        versionName = joynVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (stableSigningConfigured) {
            create("stable") {
                storeFile = file(requireNotNull(stableSigningStoreFile))
                storePassword = stableSigningStorePassword
                keyAlias = stableSigningKeyAlias
                keyPassword = stableSigningKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (stableSigningConfigured) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }

        release {
            isMinifyEnabled = false
            if (stableSigningConfigured) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.0"))
    implementation("com.squareup.okhttp3:okhttp")

    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
