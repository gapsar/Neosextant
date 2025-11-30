plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.chaquo.python")
}

android {
    namespace = "com.example.basic_neosextant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.basic_neosextant"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Specify the ABIs you want to support
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        chaquopy {
            defaultConfig {
                version = "3.10"
                buildPython("/home/gapsar/.pyenv/versions/3.10.12/bin/python3.10")
                pip {
                    install("Pillow")
                    install("scipy")
                    install("astropy")
                    install("pytz")
                    install("grpcio")
                    install("protobuf")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // CameraX core library
    implementation("androidx.camera:camera-core:1.3.1")
    // CameraX Camera2 extensions
    implementation("androidx.camera:camera-camera2:1.3.1")
    // CameraX Lifecycle library
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    // CameraX View class
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("androidx.camera:camera-extensions:1.4.2")
    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.4.0")
    // Material Icons Extended
    implementation("androidx.compose.material:material-icons-extended:1.6.3")
    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // OSMDroid for offline maps
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}