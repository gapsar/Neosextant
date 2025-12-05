# Android Application Logic

## Entry Point: `MainActivity.kt`
The `MainActivity` serves as the orchestration layer. It initializes the Python environment (Chaquopy) and manages the global sensor lifecycle.

* **Sensor Management:** Implements `SensorEventListener` to listen to `Sensor.TYPE_ROTATION_VECTOR`.
    * Converts the rotation vector to a rotation matrix.
    * Calculates the camera's pointing vector to derive the **Pitch** (Altitude) of the phone.
    * This pitch is critical as it acts as the "Sextant Altitude" (Hs) for navigation.
* **Job Management:** Maintains a `analysisJobs` map to track the asynchronous Python processing of captured images, allowing the UI to remain responsive while heavy calculations occur in the background.

## UI Architecture (Jetpack Compose)
The app uses a single-activity architecture with a `NavHost` defined in `AppNavigator`.

### 1. CameraView
This is the main interaction screen.
* **CameraX Integration:** Uses `ProcessCameraProvider` to bind `Preview` and `ImageCapture` use cases.
    * *Night Mode:* Checks for `ExtensionMode.NIGHT` support to enhance star visibility.
    * *Fallback:* If night mode is unavailable, it manually configures the camera for high sensitivity (ISO 1600) and longer exposure (200ms).
* **Workflow:**
    * User captures an image.
    * The app averages the sensor pitch readings during the image capture delay to reduce noise.
    * This averaged pitch is adjusted by the **Calibration Offset**.
    * A background coroutine launches the Python `image_processor`.
    * State is managed via `capturedImages` (a `SnapshotStateList`), which triggers UI updates upon success/failure of the analysis.

### 2. SettingsScreen
Allows the user to input Dead Reckoning (DR) data required for the sight reduction:
* Estimated Latitude/Longitude.
* Vessel speed and heading.
* Environmental data (Temperature/Pressure) for refraction corrections.

### 3. CalibrationScreen
A utility screen to calibrate the device's accelerometer/gyroscope zero-point against the true horizon.
* **Logic:** `True Horizon = Measured Pitch + Offset`.
* Users align a visual crosshair with the sea horizon. The app calculates the required offset, accounting for **Dip** (height of eye correction).

### 4. MapScreen
Visualizes the results using **OSMDroid**.
* **Markers:** Plots the Estimated Position (DR) and the Computed Fix.
* **LOP Visualization:** Draws the Lines of Position. Since an LOP is perpendicular to the azimuth, the app calculates two points 500km apart perpendicular to the star's azimuth to render the line.

## Data Models
* `ImageData`: Stores URI, timestamp, measured altitude, and analysis results (`Tetra3AnalysisResult`).
* `LineOfPositionData`: Stores the calculated Intercept and Azimuth.
