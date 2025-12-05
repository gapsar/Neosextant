# Build and Dependencies

## Requirements
* **Android Studio:** Koala or newer recommended.
* **JDK:** Version 17.
* **Android SDK:** Compile SDK 36, Min SDK 25.
* **Python:** Chaquopy installs its own Python environment (3.10), but you need Python installed locally if you wish to run the scripts independently.
* **Rust Toolchain:** Required if you intend to modify and recompile `cedar-detect`.

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
The `cedar-detect` source is included in the project. If you modify the Rust code, you must recompile the binaries and place the resulting `.so` files into the appropriate `jniLibs` folders, or configure a Cargo build task within Gradle (current setup assumes pre-built binaries or manual compilation).
