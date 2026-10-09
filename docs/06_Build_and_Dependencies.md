# Build and Dependencies

## Requirements
* **Android Studio:** Koala or newer recommended.
* **JDK:** Version 17.
* **Android SDK:** Compile SDK 36, Min SDK 25.
* **Python:** Chaquopy installs its own Python environment (3.10), but you need Python installed locally if you wish to run the scripts independently.
* **Rust Toolchain:** Required only if you intend to modify and recompile `cedar-detect` (from the fork, see below).

## Dependencies

### Android (Gradle)
* **CameraX:** For camera hardware abstraction.
* **Jetpack Compose:** For UI.
* **OSMDroid:** For offline mapping.
* **Chaquopy:** For Python integration.

### Python (pip)
Defined in `build.gradle.kts`, these are installed automatically by Chaquopy:
* `tetra3`: Plate solving.
* `astropy`: Celestial mechanics.
* `numpy`: Numerical analysis.
* `Pillow`: Image manipulation.
* `pytz`: Timezone handling.

## Building the Project
1.  **Clone the repository.**
2.  **Sync Gradle:** Android Studio should automatically detect the Chaquopy plugin and configure the Python environment.
3.  **Run:** Connect a physical device (Emulators may struggle with Sensor simulation and Camera inputs) and click Run.

## Note on Rust Compilation
The `cedar-detect` source is not included in the project. It is in the fork [gapsar/cedar-detect](https://github.com/gapsar/cedar-detect). If you modify the Rust code, rebuild `cedar-cli` and copy it into `app/src/main/jniLibs/arm64-v8a/libcedar_cli.so`. Gradle uses this prebuilt binary and does not compile Rust. See [04_Native_Star_Detection.md](04_Native_Star_Detection.md).
