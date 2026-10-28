plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.app"
    compileSdk = 34

    // Fixed debug key so every build (GitHub Actions or local) has the SAME signature.
    // That lets each new APK install as an update over the old one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("jarvis-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.jarvis.app.dev"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
