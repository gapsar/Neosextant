# Python Backend & Algorithms

The application relies on a Python backend running within the Android app via Chaquopy. The core logic resides in `celestial_navigator.py`.

## Image Processing (`image_processor`)
This function acts as the bridge between the raw image file and astronomical coordinates.

1.  **Input:** Image filename and absolute path.
2.  **Centroid Detection:**
    * Attempts to use the **Cedar Detect CLI** (native binary) first. This is invoked via `subprocess` to run `libcedar_cli.so`.
    * *Fallback:* If the binary fails, it defaults to `tetra3.get_centroids_from_image` (pure Python/NumPy implementation).
3.  **Plate Solving (Tetra3):**
    * The extracted centroids (top 30 brightest) are passed to `tetra3.solve_from_centroids`.
    * Returns: Right Ascension (RA), Declination (Dec), Roll, and Field of View (FOV).

## Sight Reduction (`lop_compute`)
This function implements the mathematical reduction of the sight using the **Marcq St. Hilaire** (Intercept) method.

* **Libraries:** Uses `astropy` for high-precision celestial mechanics.
* **Steps:**
    1.  **Dip Correction:** Calculates the dip based on Height of Eye (`1.758 * sqrt(height_m)`).
    2.  **Time Sync:** Converts the local timestamp to UTC.
    3.  **Coordinate Frame:** Creates an `AltAz` frame using the estimated position, time, and atmospheric conditions (pressure/temperature).
    4.  **Transformation:** Transforms the star's celestial coordinates (RA/Dec) into local coordinates (Computed Altitude `Hc` and Azimuth `Zn`).
    5.  **Intercept:** `Intercept = (Observed Altitude - Computed Altitude) * 60`.

## Position Fix (`lop_center_compute`)
Calculates the intersection of three lines of position.

* **Method:** Least Squares Estimation.
* **Algorithm:**
    * We solve for a correction vector $(d_{east}, d_{north})$ in nautical miles.
    * System of equations: $A x = b$
        * $A = [ [\sin(Az_1), \cos(Az_1)], ... ]$
        * $b = [Intercept_1, ...]$
    * Uses `numpy.linalg.lstsq` to solve for $x$.
* **Result Application:** The correction vector is converted to degrees of latitude and longitude and applied to the estimated position to yield the final Fix.
