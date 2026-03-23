# User Guide

## 1. Preparation
Before taking sights, ensure the app is calibrated and has your estimated position.

1.  **Settings:** Navigate to the Settings screen (Gear icon).
    * Enter your **Estimated Latitude** and **Longitude**. This is your Dead Reckoning (DR) position, required for both LOP and Iterative solver modes for now, even though it is not used in the latter.
    * Enter the **Height of Eye** (distance from sea level to the phone camera) and environmental conditions.
    * **System Parameters:** Tap "System Parameters" to toggle **Red Tint Mode** (for preserving night vision), change the **Language** (English, French, Spanish), or replay the interactive tutorial.
2.  **Calibration:**
    * Go to **Calibrate Horizon** from settings.
    * Hold the phone upright.
    * Align the red horizontal line on the screen with the actual sea horizon.
    * Tap **Calibrate**. This compensates for the specific tilt of your phone case or holding style.

## 2. Taking Sights
1.  Return to the main **Camera** screen.
2.  Point the camera at a clear patch of sky containing stars.
    * *Note:* Ensure the phone is held relatively steady.
3.  Tap the **Camera Icon** to take a photo.
4.  **Repeat:** Take exactly **3 photos** of different parts of the sky. Widely spaced azimuths (e.g., one East, one South, one West) yield the best accuracy.

## 3. Analysis
* As you take photos, the app immediately processes them in the background.
* **Blue Border:** Processing.
* **Green Border:** Solved successfully.
* **Red Border:** Failed to solve (try a clearer patch of sky).
* Tap on a thumbnail to see detailed data (Star names, RA/Dec, calculated Intercept).

## 4. The Fix
* Once 3 images are successfully solved, the app will automatically navigate to the Map tab.
* Alternatively, you can manually tap the Map Icon in the navigation bar to view your position.
    * **Red/Green/Blue Lines:** Your Lines of Position.
    * **Markers:** Comparison between your estimated position and the calculated celestial fix.
