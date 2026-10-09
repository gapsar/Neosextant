# Native Star Detection (Rust)

To ensure performance on mobile devices, the computationally intensive task of identifying stars in a noisy image is offloaded to Rust.

## Source Code
The source is not in this repository. It is in the fork [gapsar/cedar-detect](https://github.com/gapsar/cedar-detect), which is based on upstream [smroid/cedar-detect](https://github.com/smroid/cedar-detect) at commit `77b500c`. The fork adds:
* `src/bin/cedar_cli.rs`: the `cedar-cli` binary that the app ships as `libcedar_cli.so`. It reads one image and writes a JSON star list.
* Android support for `cedar-detect-server` (ashmem file descriptor instead of `shm_open()`, no `prctl` death signal). The app does not use the server.
* `Cargo.lock`, which pins the dependency versions of the shipped binary. Different JPEG decoder versions can move centroids by a sub-pixel amount.
* `.cargo/config.toml` (16 KB page alignment) and `Dockerfile.ndk` (NDK build image).

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
The app ships only a prebuilt `app/src/main/jniLibs/arm64-v8a/libcedar_cli.so`. Gradle does not compile Rust. To rebuild the binary, build `cedar-cli` from the fork for `aarch64-linux-android` (for example with `cargo ndk -t arm64-v8a build --release --bin cedar-cli`). Then copy `target/aarch64-linux-android/release/cedar-cli` to `app/src/main/jniLibs/arm64-v8a/libcedar_cli.so`.

`app/build.gradle.kts` sets `abiFilters` to `armeabi-v7a` and `arm64-v8a`, but there is no `armeabi-v7a` build of `libcedar_cli.so`. On a 32-bit ARM device, `celestial_navigator.py` cannot run the CLI and falls back to `tetra3.get_centroids_from_image`.
