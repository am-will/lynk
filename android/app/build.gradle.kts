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
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.lynk"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-28",
                    "-DGGML_VULKAN=ON",
                    "-DGGML_CPU_KLEIDIAI=ON",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_UI=OFF",
                    "-DGGML_OPENMP=OFF"
                )
                // Keep generic native code on the arm64-v8a ABI baseline. llama.cpp/ggml
                // feature-gates optimized ARM kernels; global -march flags bypass that safety.
                cFlags += "-O3"
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = file("src/main/cpp/CMakeLists.txt")
        }
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
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
