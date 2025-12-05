# Native Star Detection (Rust)

To ensure performance on mobile devices, the computationally intensive task of identifying stars in a noisy image is offloaded to Rust.

## Source Code
The source is located in `cedar-detect-source/`. It is a Rust crate that implements custom image processing algorithms.

## Key Features
* **Localized Thresholding:** Adapts to varying background light levels across the image (e.g., light pollution gradients).
* **Hot Pixel Rejection:** Distinguishes between sensor noise and actual stars.
* **Performance:** Designed to process megapixels in milliseconds.

## Integration Mechanism
1.  **Compilation:** The Rust project is compiled into a shared library (`.so` file).
2.  **Deployment:** The Android build process (Gradle) includes this shared library in the APK's native library path (`/data/app/.../lib/arm64/libcedar_cli.so`).
3.  **Invocation:**
    * The Python script `celestial_navigator.py` locates the native library using `context.getApplicationInfo().nativeLibraryDir`.
    * It executes the library as a subprocess:
        ```python
        subprocess.check_output([binary_path, "--input", image_path, ...])
        ```
    * The binary outputs the centroids in JSON format, which Python parses and feeds into Tetra3.

## Build Configuration
The `build.gradle.kts` file ensures the correct ABIs are targeted:
```kotlin
ndk {
    abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
}
