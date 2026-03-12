# Neosextant

**A modern, real-time celestial navigation tool for Android.**

Neosextant tries to bring back the ancient art of celestial navigation, turning your smartphone into a powerful sextant. By capturing images of the night sky, the app performs on-device astrometry (plate-solving) to precisely identify celestial bodies, and uses an advanced iterative solver to calculate your geographic position on Earth—completely offline.

-----

## What's New?
The latest versions of Neosextant represent a massive architectural leap:
*   **Iterative Celestial Solver**: The app now uses a more refined way to compute the position using multiple iterative shifts from the original estimated position, recomputing the position until the shift is small enough that you are at this poisiton. With a bit more tinkering, it could normally do the same without an estimated position at all.
*   **Time Synchronization Engine**: Precise time is critical for celestial navigation. The app now features opportunistic absolute-time syncing via network NTP, with GPS fallbacks.
*   **History Management**: Persistent viewing, management, and review of all your celestial observations via a local Room database.
*   **Continuous Calibration**: Automatic calibration tracking, horizon alignment, and a background worker to remind you to recalibrate your sensors for pitch accuracy.
*   **First-Launch Tutorial**: A seamless onboarding experience to help new users learn how to use the app effectively.

## How It Works

Neosextant combines modern computer vision with time-tested astronomical calculations. The process involves three main steps:

1.  **Observation Acquisition**: The user points the device at a star-filled sky and captures an image. The app automatically averages the phone's orientation (pitch) using its calibrated internal sensors to measure the body's sextant altitude accurately.
2.  **Astrometry (Plate-Solving)**: The captured image is analyzed natively on-device.
      *   **Star Detection**: Utilizing **`cedar-detect`**, a high-performance Rust executable, star centroids are rapidly extracted.
      *   **Solving**: The pattern of stars is matched against a bundled star catalog using `tetra3` (bridged via Python) to determine the exact celestial coordinates (Right Ascension and Declination) the camera was pointing at.
3.  **Position Calculation (Iterative Solver)**: Once three observations are collected, the backend calculations in `celestial_navigator.py` take over:
      *   The app calculates the Geographic Position (GP) of the observed stars to guess a "Seed" position.
      *   It calculates calculated Altitudes (Hc) and Azimuths, forming theoretical Lines of Position (LOP).
      *   An iterative Least Squares mathematical fit shifts the seed position, repeating until it converges on a tight, accurate latitude and longitude fix.

## Features

  - **Real-time, Offline Navigation**: Get a positional fix using just your phone's camera and sensors, no internet required.
  - **Zero-Input Iterative Solver**: Automated position calculation eliminates the need to manually input an Estimated Position.
  - **Hybrid Rust/Python Engine**: Leverages the blinding speed of Rust (`cedar-detect`) for image processing and the flexibility of Python (`tetra3`, `astropy`) for scientific astrometry.
  - **Advanced Time Sync**: Ensures astrometric accuracy by syncing the device clock against trusted NTP/GPS time.
  - **Robust Sensor Calibration**: Horizon-based calibration methods to minimize device pitch errors and improve altitude readings.
  - **Observation History**: Store, manage, and review your navigational fixes seamlessly.
  - **Clean UI**: A completely modern 100% Jetpack Compose interface, complete with a dark night-mode for preserving night vision.

## Core Technologies

  - **Frontend**: 100% Kotlin with [Jetpack Compose](https://developer.android.com/jetpack/compose) and Compose Navigation.
  - **Backend Layer**: Python scripts running on-device via the [Chaquopy](https://chaquo.com/chaquopy/) SDK.
  - **Star Detection**: **Rust** (`cedar-detect`) bundled as a native executable for extreme centroid extraction performance.
  - **Astrometry & Math**: `tetra3` for plate-solving, backed by `astropy` for astronomical frame transformations and Time formatting.
  - **Data Persistence**: Android Room Database (`HistoryRepository.kt`).
  - **Mapping**: [osmdroid](https://github.com/osmdroid/osmdroid) for offline-capable map rendering.

## Getting Started

### Prerequisites

  - An Android device with Camera, Accelerometer, and Gyroscope sensors.
  - **Java 17** (required for building).
  - Android Studio to build the project.

### Building from Source

1.  Clone the repository:
    ```bash
    git clone https://github.com/gapsar/Neosextant.git
    ```
2.  Open the project in Android Studio.
3.  Allow Gradle to sync the project dependencies. The Chaquopy SDK will automatically configure the Python environment and its PIP dependencies.
4.  Build the project and install it on a physical Android device. *(Emulators lack the realistic sensor outputs necessary for accurate celestial observations).*

## How to Use the App

1.  **Onboarding**: On first launch, follow the interactive tutorial to familiarize yourself with the interface.
2.  **Calibrate the Sensors**: This is a crucial step for accuracy.
      * Go to the Settings or Calibration screen and choose **Calibrate from Horizon**.
      * Follow the on-screen instructions to align your phone with a flat, level horizon. This eliminates manufacturer mounting errors in your device's pitch sensor.
3.  **Take Observations**:
      * Aim your camera at a part of the night sky containing visible stars.
      * Capture an image. The app will immediately run the astrometry engine to plate-solve the frame.
      * Repeat this for a total of **three** parts of the sky, preferably spread out in different directions, for the strongest possible fix.
4.  **View Results**:
      * As images are solved, they appear in the bottom sheet.
      * Once three valid observations are collected, the internal **Iterative Solver** runs automatically.
      * Your highly-accurate final calculated position (Latitude, Longitude) and estimated error will be displayed!
      * You can view your past fixes in the **History** tab.
  
> [!NOTE]
> Full disclosure, the core of this project (the Python part) was created by myself entirely, but since then I've used AI to port it to Kotlin, a language I'm not fluent in, and to add some functionalities. So if you see any weird code or things like this, please open an issue and we'll try to resolve it !

## Acknowledgements

This project was made possible thanks to the following incredible open-source projects:

* [Cedar-detect](https://github.com/smroid/cedar-detect) - Used for fast and accurate centroid detection.
* [Tetra3](https://github.com/esa/tetra3) - Handled the fast plate solving part.

## License

This project is licensed under the Apache License 2.0. See the `LICENSE.md` file for details.

The included astrometry software, based heavily on ESA projects (`tetra3`, `cedar-detect`, `cedar-solve`), retains their respective Apache License 2.0 terms.

## Documentation

For deep technical dives into the application architecture, the Iterative Solver logic, and build constraints, please refer to the markdown files in the [docs](docs/) directory.
