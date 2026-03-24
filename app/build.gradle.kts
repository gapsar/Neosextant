import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("com.chaquo.python")
}

// Read local.properties for configurable Python path
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties()
if (localPropsFile.exists()) { localProps.load(localPropsFile.inputStream()) }
val chaquopyPython = localProps.getProperty("chaquopy.python", "python3")

android {
    namespace = "io.github.gapsar.neosextant"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.gapsar.neosextant"
        minSdk = 25
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            // Constrain native libraries to ARM only. This halves the Chaquopy footprint
            // by excluding x86 and x86_64, reducing the final APK size significantly.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        
        chaquopy {
            defaultConfig {
                version = "3.10"
                buildPython(chaquopyPython)
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
            isMinifyEnabled = true
            isShrinkResources = true
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
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    implementation(libs.work.runtime.ktx)
    implementation(libs.preference.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.exifinterface)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)

    // Coil for image loading
    implementation(libs.coil.compose)
    // Material Icons Extended
    implementation(libs.material.icons.extended)
    // Navigation Compose
    implementation(libs.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OSMDroid for offline maps
    implementation(libs.osmdroid.android)
}