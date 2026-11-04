plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Stable signing key committed to the repo so CI and local builds share a signature.
val ciKeystore = rootProject.file("ci-signing.keystore")

android {
    namespace = "com.system.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.system.monitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (ciKeystore.exists()) {
            create("ci") {
                storeFile = ciKeystore
                storePassword = "ciandroid"
                keyAlias = "ci"
                keyPassword = "ciandroid"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (ciKeystore.exists()) signingConfig = signingConfigs.getByName("ci")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (ciKeystore.exists()) signingConfig = signingConfigs.getByName("ci")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
}
