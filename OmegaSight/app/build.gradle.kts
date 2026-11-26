import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// LiteRT (the TensorFlow Lite runtime that runs the vision / brain model files). CI picks the newest release and passes it in.
val litertVersion = (findProperty("litertVersion") as String?) ?: "1.4.0"
val litertGpu = ((findProperty("litertGpu") as String?) ?: "true") == "true"

android {
    namespace = "com.omega.sight"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omega.sight"
        minSdk = 26          // continued gesture strokes (the aim drag) need Android 8.0
        targetSdk = 34
        versionCode = ((findProperty("appVersionCode") as String?) ?: "1").toInt()
        versionName = (findProperty("appVersionName") as String?) ?: "1.0.0"
        // -Pabis=x86_64 builds the emulator preview
        ndk { abiFilters += ((findProperty("abis") as String?) ?: "arm64-v8a").split(',') }
    }

    signingConfigs {
        // a fixed key checked into the repo, so every build is signed the same way and updates install over each other
        create("sight") {
            storeFile = file("omega-sight.jks")
            storePassword = "omegasight"
            keyAlias = "sight"
            keyPassword = "omegasight"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("sight")
        }
        debug {
            signingConfig = signingConfigs.getByName("sight")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:$litertVersion")
    if (litertGpu) implementation("com.google.ai.edge.litert:litert-gpu:$litertVersion")

    testImplementation("junit:junit:4.13.2")
}

// the test suites print their numbers (aim errors, detection rates, timings): show them in the build log
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
