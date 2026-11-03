plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0

android {
    namespace = "com.autoclick.visual"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autoclick.visual"
        minSdk = 26
        targetSdk = 34
        versionCode = maxOf(buildNo, 1)
        versionName = "1.$buildNo"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("visualclicker.keystore")
            storePassword = "visualclicker"
            keyAlias = "visualclicker"
            keyPassword = "visualclicker"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.opencv:opencv:4.9.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
