plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // Ensure this is present if you have it in your libs.versions.toml
    // If you don't have libs.plugins.kotlin.compose, it might be just:
    // id("org.jetbrains.kotlin.plugin.compose") version "YourComposePluginVersion"
    id("com.chaquo.python")
}

android {
    namespace = "com.example.neosextant"
    compileSdk = 35 // Or your preferred SDK version, ensure it's recent

    defaultConfig {
        applicationId = "com.example.neosextant"
        minSdk = 24 // CameraX and other modern APIs work best with minSdk 24+
        targetSdk = 35 // Or your preferred target SDK
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // Specify the ABIs you want to support
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        chaquopy {
            defaultConfig {
                version = "3.10"
                buildPython("/home/gapsar/.pyenv/versions/3.10.12/bin/python3.10")
                pip {
                    install("scipy")
                    install("astropy")
                    install("src/main/python/cedar-solve/.")
                }
                extractPackages("astropy")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11 // Or JavaVersion.VERSION_1_8 if you prefer, but 11 is good
        targetCompatibility = JavaVersion.VERSION_11 // Or JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "11" // Or "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get() // Ensure this matches your compose version if using libs
        // Or directly: kotlinCompilerExtensionVersion = "YourComposeCompilerVersion"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // CameraX dependencies (ensure these are the latest stable versions)
    implementation("androidx.camera:camera-core:1.3.3")
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")
    implementation("androidx.camera:camera-view:1.3.3") // For PreviewView
    implementation("androidx.camera:camera-extensions:1.4.2")

    // Coil for image loading in Compose (ensure this is the latest stable version)
    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation("androidx.compose.material:material-icons-core:1.6.7")
    implementation("androidx.compose.material:material-icons-extended:1.6.7") // For extended icons

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}