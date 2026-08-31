plugins {
    id("com.android.application")
}

android {
    namespace = "com.andreassamitsch.servusprovider"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andreassamitsch.servusprovider"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-prototype"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
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
