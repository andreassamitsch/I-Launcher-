plugins {
    id("com.android.application")
}

val servusVersionCode = providers.gradleProperty("servusVersionCode")
    .orNull
    ?.toIntOrNull()
    ?: 1
val servusVersionName = providers.gradleProperty("servusVersionName")
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?: "0.1.0-prototype"

val developmentSigningStoreFile = System.getenv("IL_SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
val developmentSigningStorePassword = System.getenv("IL_SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val developmentSigningKeyAlias = System.getenv("IL_SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val developmentSigningKeyPassword = System.getenv("IL_SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val developmentSigningConfigured = listOf(
    developmentSigningStoreFile,
    developmentSigningStorePassword,
    developmentSigningKeyAlias,
    developmentSigningKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.andreassamitsch.servusprovider"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andreassamitsch.servusprovider"
        minSdk = 26
        targetSdk = 36
        versionCode = servusVersionCode
        versionName = servusVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (developmentSigningConfigured) {
            create("development") {
                storeFile = file(requireNotNull(developmentSigningStoreFile))
                storePassword = developmentSigningStorePassword
                keyAlias = developmentSigningKeyAlias
                keyPassword = developmentSigningKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (developmentSigningConfigured) {
                signingConfig = signingConfigs.getByName("development")
            }
        }

        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    implementation("androidx.tvprovider:tvprovider:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.0"))
    implementation("com.squareup.okhttp3:okhttp")

    testImplementation("junit:junit:4.13.2")
}
