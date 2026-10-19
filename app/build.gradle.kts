plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dante.callblock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dante.callblock"
        minSdk = 30
        targetSdk = 34
        versionCode = 29874108
        versionName = "1.0"
    }

    // Fixed signing key (committed on purpose) so every new build installs
    // OVER the old one — you keep permissions + the call-screening role.
    signingConfigs {
        create("stable") {
            storeFile = file("callblock.jks")
            storePassword = "callblock"
            keyAlias = "callblock"
            keyPassword = "callblock"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
