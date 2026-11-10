plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.vaila.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vaila.launcher"
        minSdk = 26
        // 28 on purpose: apps targeting 29+ may not exec binaries from their
        // own data dir, and we need to run php and mariadbd from there.
        targetSdk = 28
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    signingConfigs {
        create("release") {
            storeFile = file("vaila.keystore")
            storePassword = "vailalauncher"
            keyAlias = "vaila"
            keyPassword = "vailalauncher"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("zip")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
