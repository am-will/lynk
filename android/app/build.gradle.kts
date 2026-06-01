plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val uploadSigningProperties = Properties().apply {
    val file = file("${System.getProperty("user.home")}/.android/lynk-upload.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

android {
    namespace = "dev.androidagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lynk"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = uploadSigningProperties.getProperty("storeFile")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = uploadSigningProperties.getProperty("storePassword")
                keyAlias = uploadSigningProperties.getProperty("keyAlias")
                keyPassword = uploadSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.github.webrtc-sdk:android:144.7559.05")
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    val markwon = "4.6.2"
    implementation("io.noties.markwon:core:$markwon")
    implementation("io.noties.markwon:linkify:$markwon")
    implementation("io.noties.markwon:ext-strikethrough:$markwon")
    implementation("io.noties.markwon:ext-tables:$markwon")
    implementation("io.noties.markwon:html:$markwon")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
