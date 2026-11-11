import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
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
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "src/main/assets-kb")
        }
    }

    androidResources {
        noCompress += listOf("zip")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Terminal engine from Termux (Apache 2.0)
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    // Claude API (AI assistant screen)
    implementation("com.anthropic:anthropic-java:3.3.0")
}
