plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.macrorecorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.macrorecorder"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // A fixed key checked into the repo, so every build is signed the same way and a new APK
    // installs over the old one (a throwaway key for personal sideloading, not for the Play Store).
    signingConfigs {
        create("shared") {
            storeFile = file("macro.jks")
            storePassword = "macrorecorder"
            keyAlias = "macro"
            keyPassword = "macrorecorder"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // No external libraries needed: the app uses only the Android framework.
}
