plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gamevision.bot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gamevision.bot"
        // RedMagic 6 Pro ships Android 12 (API 31); minSdk 26 so AccessibilityService.takeScreenshot()
        // (added in API 30) and dispatchGesture() (API 24+) are both safely available at runtime.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No keystore secret is configured yet: ship an installable debug-signed release APK.
            // Once a real signing key exists, point this at it instead (see README "Signing").
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
