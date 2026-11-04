plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "at.mikag.alarmspike"
    compileSdk = 35

    defaultConfig {
        // Throwaway test app: deliberately a different ID from the real app.
        applicationId = "at.mikag.alarmspike"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-test"
    }

    signingConfigs {
        // Shared test key, so builds from any computer or from GitHub install over each other.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
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
}
