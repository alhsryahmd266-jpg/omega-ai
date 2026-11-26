import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.omega.sight.testgame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omega.sight.testgame"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "x86_64" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
