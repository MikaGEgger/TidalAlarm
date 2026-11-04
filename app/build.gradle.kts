plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "at.mikag.lavender"
    compileSdk = 36

    defaultConfig {
        applicationId = "at.mikag.lavender"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        val clientId = (project.findProperty("tidalClientId") as String?).orEmpty().trim()
        buildConfigField("String", "TIDAL_CLIENT_ID", "\"$clientId\"")
        buildConfigField("String", "TIDAL_REDIRECT_URI", "\"tidalarm://auth\"")
    }

    signingConfigs {
        // Development key, committed on purpose: builds from any computer or from GitHub
        // install over each other. A separate upload key is used for the Play Store.
        create("dev") {
            storeFile = file("dev.keystore")
            storePassword = "lavender-dev"
            keyAlias = "lavender"
            keyPassword = "lavender-dev"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Lets Android 13+ offer English/German as a per-app language in system settings.
        generateLocaleConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.browser)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
