import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// LiteRT-LM (runs the local .litertlm model). CI can override with -PlitertlmVersion=x.y.z
val litertlmVersion = (findProperty("litertlmVersion") as String?) ?: "0.18.1"

android {
    namespace = "com.omega.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omega.keyboard"
        minSdk = 24          // Android 7.0 and newer (the owner's phone is Android 12)
        targetSdk = 34
        versionCode = ((findProperty("appVersionCode") as String?) ?: "1").toInt()
        versionName = (findProperty("appVersionName") as String?) ?: "1.0.0"
        // 64-bit is where the local model runs; 32-bit is kept so the keyboard still installs on old phones.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // A fixed key checked into the repo so every build is signed the same way and updates install over each other.
        create("omega") {
            storeFile = file("omega-release.jks")
            storePassword = "omegakeyboard"
            keyAlias = "omega"
            keyPassword = "omegakeyboard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("omega")
        }
        debug {
            signingConfig = signingConfigs.getByName("omega")
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
                "META-INF/*.kotlin_module.bak",
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
        // the LiteRT-LM AAR carries Kotlin 2.4 metadata
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:$litertlmVersion")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    testImplementation("junit:junit:4.13.2")
}
