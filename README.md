# Neosextant

**A modern, real-time celestial navigation tool for Android.**

Neosextant revitalizes the ancient art of celestial navigation, turning your smartphone into a powerful sextant. By capturing images of the night sky, the app performs on-device astrometry (plate-solving) to precisely identify celestial bodies and calculates your geographic position on Earth using classic navigational principles.

-----

## How It Works

Neosextant combines modern technology with time-tested astronomical calculations to determine your location. The process involves three main steps:

1.  **Observation Acquisition**: The user points the device at a celestial object (like a star or planet) and captures an image. At the moment of capture, the app records the precise UTC time and the phone's orientation (pitch, azimuth, and roll) using its internal sensors.

2.  **Astrometry (Plate-Solving)**: The captured image is passed to a powerful, on-device Python-based analysis engine.

      * This engine, using a fork of the `tetra3` library called **`cedar-solve`**, analyzes the star patterns in the image.
      * By matching these patterns against a star catalog, it calculates the exact celestial coordinates (Right Ascension and Declination) the camera was pointing at. This entire process is handled by `image_processor.py`.

3.  **Position Calculation**: With the celestial object's calculated position and its observed altitude (derived from the phone's calibrated pitch sensor), the app calculates a **Line of Position (LOP)** using the Marcq St. Hilaire intercept method.

      * This calculation, performed by `astro_navigator.py`, determines a line on which the observer is located.
      * By taking observations of **three different objects**, the app generates three intersecting LOPs, providing a precise latitude and longitude "fix".

## Features

  - **Real-time Celestial Navigation**: Get a positional fix using just your phone's camera and sensors.
  - **Powerful Plate-Solving**: Integrates the `cedar-solve` library for fast and reliable on-device star identification.
  - **Accurate Altitude Measurement**: Features robust sensor calibration routines, including both horizon and zenith-based methods, to correct for device pitch error.
  - **Single and Multi-LOP Support**: Calculate a single Line of Position or a full 3-LOP fix.
  - **Flexible Image Input**: Analyze images taken directly with the camera or import them from your device's storage.
  - **User-Friendly Interface**: A clean Jetpack Compose UI to manage observations, enter your estimated position, and view results.
  - **Night Mode**: Includes an optional night mode for better viewing in dark conditions.

## Core Technologies

  - **Frontend**: 100% Kotlin with [Jetpack Compose](https://developer.android.com/jetpack/compose) for a modern, declarative UI.
  - **Backend**: Python scripts running on-device via the [Chaquopy](https://chaquo.com/chaquopy/) SDK.
  - **Astrometry Engine**: `cedar-solve` (a high-performance fork of `tetra3`), responsible for the core plate-solving logic.
  - **Navigational Calculations**: Custom Python scripts (`astro_navigator.py`) implementing celestial navigation formulae for LOP and fix calculations.

## Getting Started

### Prerequisites

  - An Android device.
  - Required hardware sensors: Camera, Accelerometer, Gyroscope.
  - Android Studio to build the project.

### Building from Source

1.  Clone the repository:
    ```bash
    git clone https://github.com/your-username/neosextant.git
    ```
2.  Open the project in Android Studio.
3.  Let Gradle sync the project dependencies. The Chaquopy SDK will automatically configure the Python environment.
4.  Build the project and run it on an Android device or emulator.

## How to Use the App

1.  **Enter Estimated Position (EP)**: Navigate to the **Settings** screen and input your approximate latitude and longitude. This is required for the LOP calculations.
2.  **Calibrate the Sensors**: This is a crucial step for accuracy.
      * In Settings, choose either **Calibrate from Horizon** or **Start Pitch Calibration (Zenith Method)**.
      * Follow the on-screen instructions to align your phone with the horizon or zenith. This corrects any mounting error in the phone's pitch sensor.
3.  **Take Observations**:
      * From the main camera screen, aim at a part of the sky with star or celestial object.
      * Tap the camera button to capture an image. The app will immediately begin processing.
      * Repeat this for three parts, preferably at different azimuths and altitudes, to get a strong fix.
4.  **View Results**:
      * The bottom sheet shows your captured images and their analysis status.
      * Once an image is solved and its LOP is calculated, the intercept and azimuth will be displayed.
      * After three valid LOPs are available, the app automatically calculates and displays your final position fix.

## License

This project is licensed under the Apache License 2.0. See the `LICENSE.md` file for details.

The included `cedar-solve` library is distributed under the Apache License 2.0.
