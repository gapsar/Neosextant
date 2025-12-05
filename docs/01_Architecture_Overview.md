# Architecture Overview

## Introduction
Neosextant is a hybrid Android application designed for celestial navigation. It leverages modern mobile sensors and cameras to perform "sight reduction" automatically. The application captures images of the night sky, identifies stars to determine celestial coordinates, measures the device's inclination (altitude), and calculates a navigational fix using the method of Lines of Position (LOP).

## Technology Stack
The application is built using a multi-language architecture to optimize for UI responsiveness, mathematical accuracy, and performance:

* **Frontend (Kotlin):** Native Android UI built with **Jetpack Compose**. Handles camera interactions (CameraX), sensor fusion, and map rendering (OSMDroid).
* **Backend (Python):** Integrated via **Chaquopy**. Handles complex astrometry (Tetra3), celestial mechanics calculations (Astropy), and mathematical optimization (NumPy).
* **Native Optimization (Rust):** Handles high-performance image processing (Star Centroid Detection) via the `cedar-detect` library, compiled to a native shared object (`.so`).

## High-Level Data Flow

1.  **Acquisition:** The user captures an image via `CameraView`. The Android layer records the image file and the concurrent pitch/roll sensor data.
2.  **Processing (Python Bridge):**
    * The image path is passed to the Python module `celestial_navigator`.
    * **Centroid Extraction:** Python invokes the native Rust binary (`libcedar_cli.so`) to extract star centroids efficiently.
    * **Plate Solving:** Centroids are passed to the **Tetra3** library to solve for Right Ascension (RA) and Declination (Dec).
3.  **Navigation Calculation:**
    * **LOP:** Using the solved RA/Dec, time, and estimated position, the app calculates the Intercept and Azimuth using `Astropy`.
    * **Fix:** Once 3 valid LOPs are obtained, a Least Squares algorithm calculates the final Latitude/Longitude fix.
4.  **Visualization:** The results are returned to the Kotlin layer and plotted on an offline-capable map.

## Component Interaction Diagram

```mermaid
graph TD
    User[User] -->|Interacts| UI["Android UI (Compose)"]
    UI -->|Captures| Camera[CameraX]
    UI -->|Reads| Sensors["Rotation Vector Sensor"]
    
    Camera -->|Image File| Python["Python Backend (Chaquopy)"]
    Sensors -->|Pitch/Roll| Python
    
    subgraph Python_Layer ["Python Layer"]
        Python -->|Image Path| Rust["Rust Native Binary (Cedar Detect)"]
        Rust -->|Centroids| Python
        Python -->|Centroids| Tetra3["Tetra3 Solver"]
        Tetra3 -->|RA/Dec| Astro["Astropy Logic"]
        Astro -->|Intercept/Azimuth| Fix["Least Squares Solver"]
    end
    
    Fix -->|Lat/Lon| UI
    UI -->|Render| Map["OSMDroid Map"]
