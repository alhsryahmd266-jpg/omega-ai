plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ahmedxg.gamebot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ahmedxg.gamebot"
        minSdk = 30
        // targetSdk 31 on purpose: sideloaded tool, avoids Android 14 foreground-service-type and
        // per-session MediaProjection enforcement. Runs on Android 11-15.
        targetSdk = 31
        versionCode = 3
        versionName = "1.2"
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // personal sideload build: sign with the debug key so the APK is installable
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
