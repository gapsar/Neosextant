# celestial_navigator.py
#
# A consolidated Python script for celestial navigation calculations, designed
# for integration with an Android application.
#
# This script performs two main tasks:
# 1. Image Solving: Analyzes an image of the sky to determine the celestial
#    coordinates (RA/Dec) of the center of the image using Tetra3.
# 2. Intercept Calculation: Computes the celestial navigation intercept (distance
#    and direction to a line of position) based on the image solution and
#    observer data.
#
# REQUIRED DEPENDENCIES:
# - tetra3
# - astropy
# - pytz
# - Pillow
# - numpy


import os
import math
import traceback
import json
from datetime import datetime
import threading

# --- Third-party library imports ---
try:
    from PIL import Image
    import numpy as np
    import pytz
    import tetra3
    import astropy.units as u
    from astropy.time import Time
    from astropy.coordinates import SkyCoord, EarthLocation, AltAz
except ImportError as e:
    # This will help diagnose missing libraries if the script fails to load.
    raise ImportError(f"A required library is missing. Please ensure all dependencies are installed. Error: {e}")


# =============================================================================
# SECTION 1: ASTROPY IERS CONFIGURATION
# =============================================================================

import urllib.request
from astropy.utils import iers
import warnings

def _setup_iers():
    """
    Configures Astropy's Earth rotation (IERS) data fetching.
    If online, downloads the latest tables for sub-arcsecond accuracy.
    If offline, suppresses expiration errors and falls back to bundled tables.
    """
    warnings.filterwarnings('ignore', module='erfa') # Suppress ERFA warnings about future years
    try:
        urllib.request.urlopen("https://datacenter.iers.org", timeout=0.5)
        iers.conf.auto_download = True
        print("Python: Internet available. Astropy will auto-download IERS data.")
    except Exception:
        iers.conf.auto_download = False
        iers.conf.auto_max_age = None
        iers.conf.iers_degraded_accuracy = 'ignore'
        print("Python: Offline mode. Astropy using bundled IERS data.")

threading.Thread(target=_setup_iers, daemon=True).start()

# =============================================================================
# SECTION 2: IMAGE SOLVING (ASTROMETRY)
# =============================================================================

T3_INSTANCE = None
INITIALIZATION_ERROR = None

try:
    print("Python: Initializing Tetra3 Astrometry Solver...")
    # 'load_database' should point to the location of your Tetra3 database file.
    # 'default_database' assumes it's in the default location.
    T3_INSTANCE = tetra3.Tetra3(load_database='default_database')
    if T3_INSTANCE.has_database:
        print("Python: Tetra3 Solver initialized successfully.")
    else:
        INITIALIZATION_ERROR = "Tetra3 instance created but database FAILED to load."
        print(f"Python: ERROR - {INITIALIZATION_ERROR}")

except Exception as e_init:
    INITIALIZATION_ERROR = f"FATAL ERROR initializing Tetra3: {e_init}\n{traceback.format_exc()}"
    print(f"Python: {INITIALIZATION_ERROR}")

def detect_centroids_cli(image_path):
    """
    Runs the cedar_cli binary on the given image path.
    """
    import subprocess
    import json
    from com.chaquo.python import Python

    try:
        context = Python.getPlatform().getApplication()
        native_lib_dir = context.getApplicationInfo().nativeLibraryDir
        binary_path = os.path.join(native_lib_dir, "libcedar_cli.so")

        print(f"Python: Using binary at: {binary_path}")

        # Native libraries in /data/app are read-only but already executable.
        if not os.access(binary_path, os.X_OK):
            return {"error": f"Binary is not executable: {binary_path}"}

        # Create a temporary file path for the output
        output_path = os.path.join(
            context.getCacheDir().getAbsolutePath(),
            f"cedar_out_{os.path.basename(image_path)}.json"
        )

        result = subprocess.check_output(
            [binary_path, "--input", image_path, "--output", output_path, "--sigma", "5.0"],
            stderr=subprocess.STDOUT,
            timeout=30
        )

        # Read the JSON output from the generated file
        with open(output_path, 'r') as f:
            data = json.load(f)

        # Clean up the temporary file
        try:
            os.remove(output_path)
        except OSError:
            pass

        return data
    except subprocess.TimeoutExpired as e:
        return {"error": f"Cedar CLI timed out after 30s: {e}"}
    except subprocess.CalledProcessError as e:
        return {"error": str(e), "output": e.output.decode("utf-8")}
    except Exception as e:
        return {"error": str(e)}

def image_processor(image_name, image_path):
    """
    Analyzes an image from a given file path to find celestial coordinates.
    This function replaces the mock function from the test script.

    Args:
        image_name (str): The name of the image (used for logging).
        image_path (str): The absolute file path to the image.

    Returns:
        str: A JSON string containing the solution or an error message.
             Example success: '{"solved": 1, "ra_deg": 216.4, "dec_deg": 15.8, "error_message": null}'
             Example failure: '{"solved": 0, "error_message": "Image file not found"}'
    """
    print(f"Python: image_processor received image name: {image_name}")
    print(f"Python: image_processor received image path: {image_path}")

    # --- Initial Checks ---
    if T3_INSTANCE is None:
        error_msg = INITIALIZATION_ERROR or "Tetra3 Solver is not initialized."
        print(f"Python: Error - {error_msg}")
        return json.dumps({"solved": 0, "error_message": error_msg})

    if not os.path.exists(image_path):
        error_msg = f"Image file does not exist at path: {image_path}"
        print(f"Python: Error - {error_msg}")
        return json.dumps({"solved": 0, "error_message": error_msg})

    # --- Main Processing Logic ---
    try:
        print(f"Python: Opening image: {image_path}...")
        with Image.open(image_path) as img:
            orig_width, orig_height = img.width, img.height
            # H-12: Cap image resolution to avoid excessive memory usage
            MAX_DIM = 4000
            ratio = 1.0
            if img.width > MAX_DIM or img.height > MAX_DIM:
                ratio = min(MAX_DIM / img.width, MAX_DIM / img.height)
                new_size = (int(img.width * ratio), int(img.height * ratio))
                print(f"Python: Downscaling image from {img.width}x{img.height} to {new_size[0]}x{new_size[1]}")
                img = img.resize(new_size, Image.LANCZOS)
            img_gray = img.convert(mode='L')
            np_image = np.asarray(img_gray, dtype=np.uint8)
            height, width = np_image.shape
        print(f"Python: Image loaded successfully ({width}x{height}).")

        centroids = []

        print("Python: Extracting centroids using Cedar Detect CLI...")
        cedar_result = detect_centroids_cli(image_path)

        if "error" in cedar_result:
            print(f"Python: Cedar Detect CLI error: {cedar_result['error']}")
            if "output" in cedar_result:
                print(f"Python: CLI output: {cedar_result['output']}")
            print("Python: Falling back to default Tetra3 extraction...")
            centroids = tetra3.get_centroids_from_image(np_image)
            if ratio != 1.0:
                centroids = [(c[0] / ratio, c[1] / ratio) for c in centroids]
        else:
            # Cedar Detect CLI returns a dict with "stars" list of points
            # Each point has x, y, brightness
            if "stars" in cedar_result:
                # Convert to list of (y, x) tuples as expected by Tetra3 solver (or check what it expects)
                # Tetra3's solve_from_centroids expects a list of (y, x) tuples or [y, x] lists.
                # Note: Cedar returns x, y. Tetra3 usually works with (y, x) (row, col).
                # Let's verify: tetra3.get_centroids_from_image returns (y, x).
                # So we need to swap x and y from Cedar result.
                cedar_stars = cedar_result["stars"]
                centroids = [(star["y"], star["x"]) for star in cedar_stars]
                print(f"Python: Cedar Detect found {len(centroids)} centroids.")
            else:
                print("Python: Cedar Detect returned no stars data.")
                centroids = tetra3.get_centroids_from_image(np_image)
                if ratio != 1.0:
                    centroids = [(c[0] / ratio, c[1] / ratio) for c in centroids]

        if len(centroids) == 0:
            print("Python: No centroids found in the image.")
            return json.dumps({"solved": 0, "error_message": "No stars (centroids) found in image."})

        # Use the 30 brightest centroids for solving
        # If we used Cedar, they might not be sorted by brightness.
        # If we used Tetra3, they are sorted.
        # We can sort by brightness if we had it, but for now just taking first 30 is usually okay if Cedar returns them in order.
        # Cedar CLI usually returns them sorted by brightness descending.

        # Convert to list of lists if it's not already compatible
        # Ensure elements are native Python floats, not np.float32, for JSON serialization
        centroids_list = [[float(c[0]), float(c[1])] for c in centroids]

        trimmed_centroids = centroids_list[:30]
        print(f"Python: Found {len(centroids)} centroids, using {len(trimmed_centroids)} for solving.")

        # Solve for astrometry
        solution = T3_INSTANCE.solve_from_centroids(
            trimmed_centroids,
            (orig_height, orig_width),
            solve_timeout=10000  # 10-second timeout
        )

        print("Python: Tetra3 solving complete.")
        if solution.get('RA') is not None:
            final_result = {
                "solved": 1,
                "ra_deg": solution.get('RA'),
                "dec_deg": solution.get('Dec'),
                "roll_deg": solution.get('Roll'),
                "fov_deg": solution.get('FOV'),
                "centroids": trimmed_centroids,
                "error_message": None
            }
            print(f"Python: Solution FOUND: RA={final_result['ra_deg']:.4f}, Dec={final_result['dec_deg']:.4f}")
            return json.dumps(final_result)
        else:
            print(f"Python: Solution NOT found. Status: {solution.get('status', 'Unknown')}")
            return json.dumps({"solved": 0, "centroids": trimmed_centroids, "error_message": f"No match found. Tetra3 status: {solution.get('status')}"})

    except Exception as e:
        error_msg = f"An exception occurred in image_processor: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"solved": 0, "error_message": error_msg})


# =============================================================================
# SECTION 2: LINE OF POSITION (LOP) CALCULATION
# =============================================================================

def _calculate_dip_correction_deg(height_of_eye_m):
    """(Internal helper) Calculates the dip correction in degrees."""
    # If height of eye is 0 (e.g. artificial horizon or calibrated sensor), dip is 0.
    if height_of_eye_m <= 0:
        return 0.0
    # Formula: dip in arcminutes = 1.758 * sqrt(height in meters)
    dip_arcmin = 1.758 * math.sqrt(float(height_of_eye_m))
    return dip_arcmin / 60.0

def _calculate_gp(ra, dec, observation_time):
    """
    Calculates the Geographic Position (GP) of the star at the given time.
    Returns (lat_deg, lon_deg) where longitude is East-positive.
    """
    # GP Latitude = Declination
    gp_lat = dec

    # GP Longitude (East positive) = RA - GST
    # GST is the Greenwich Sidereal Time (angle of Aries relative to Greenwich)
    # RA is the Right Ascension (angle of Star relative to Aries)
    # Longitude (East +) = RA - GST
    gst = observation_time.sidereal_time('mean', 'greenwich')
    gp_lon = ra - gst.deg
    gp_lon = (gp_lon + 180) % 360 - 180
    return gp_lat, gp_lon


def lop_compute(
    ra_from_image,
    dec_from_image,
    estimated_latitude,
    estimated_longitude,
    height_of_eye_m,
    pressure_hpa,
    temperature_celsius,
    sextant_altitude_deg,
    local_date_str,
    local_time_str,
    timezone_str
):
    """
    Calculates the intercept from a celestial observation.
    This function replaces the mock function from the test script and uses
    the real astropy-based calculation logic.

    Args:
        ra_from_image (float): Right Ascension from the image solver.
        dec_from_image (float): Declination from the image solver.
        estimated_latitude (float): Assumed latitude in degrees.
        estimated_longitude (float): Assumed longitude in degrees.
        height_of_eye_m (float): Observer's height of eye in meters.
        pressure_hpa (float): Atmospheric pressure in hPa.
        temperature_celsius (float): Air temperature in Celsius.
        sextant_altitude_deg (float): The raw altitude measured with the sextant.
        local_date_str (str): Local date of observation ("YYYY-MM-DD").
        local_time_str (str): Local time of observation ("HH:MM:SS.fff").
        timezone_str (str): Observer's IANA timezone (e.g., 'Europe/Paris').

    Returns:
        str: A JSON string with the calculation results or an error.
             Example success: '{"intercept_nm": -2.5, "azimuth_deg": 245.1, "error": null}'
             Example failure: '{"error": "Invalid timezone specified"}'
    """
    print("Python: lop_compute function started.")
    try:
        # 1. Correct sextant altitude for dip to get Observed Altitude (Ho).
        dip_correction_deg = _calculate_dip_correction_deg(height_of_eye_m)
        ho_deg = sextant_altitude_deg - dip_correction_deg

        # 2. Combine and convert observation time to UTC.
        local_datetime_str = f"{local_date_str} {local_time_str}"
        try:
            naive_dt = datetime.strptime(local_datetime_str, "%Y-%m-%d %H:%M:%S.%f")
        except ValueError:
            naive_dt = datetime.strptime(local_datetime_str, "%Y-%m-%d %H:%M:%S")

        local_tz = pytz.timezone(timezone_str)
        utc_dt = local_tz.localize(naive_dt).astimezone(pytz.utc)

        # 3. Set up Astropy objects for calculation.
        observation_time = Time(utc_dt)
        observer_location = EarthLocation(
            lat=estimated_latitude * u.deg,
            lon=estimated_longitude * u.deg,
            height=height_of_eye_m * u.m
        )
        celestial_body = SkyCoord(
            ra=ra_from_image * u.deg,
            dec=dec_from_image * u.deg,
            frame='icrs'
        )
        altaz_frame = AltAz(
            obstime=observation_time,
            location=observer_location,
            pressure=pressure_hpa * u.hPa,
            temperature=temperature_celsius * u.deg_C
        )

        # 4. Calculate Computed Altitude (Hc) and Azimuth (Zn).
        body_in_local_sky = celestial_body.transform_to(altaz_frame)
        hc_deg = body_in_local_sky.alt.degree
        azimuth_deg = body_in_local_sky.az.degree

        # 5. Calculate intercept (difference between Ho and Hc in nautical miles).
        intercept_nm = (ho_deg - hc_deg) * 60.0

        result = {
            'intercept_nm': intercept_nm,
            'azimuth_deg': azimuth_deg,
            'observed_altitude_deg': ho_deg,
            'computed_altitude_deg': hc_deg,
            'error': None
        }
        print(f"Python: lop_compute successful. Intercept: {intercept_nm:.2f} NM")
        return json.dumps(result)

    except Exception as e:
        error_msg = f"An exception occurred in lop_compute: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({'error': error_msg})


# =============================================================================
# SECTION 3: POSITION FIX CALCULATION
# =============================================================================

def lop_center_compute(lop_1_json, lop_2_json, lop_3_json, estimated_latitude, estimated_longitude):
    """
    Calculates the final position fix from three LOPs using the method of least squares.
    This method finds the point that is closest to all three lines of position, which
    corresponds to the center of the small triangle formed by their intersections.

    Args:
        lop_1_json (str): JSON string result from the first lop_compute call.
        lop_2_json (str): JSON string result from the second lop_compute call.
        lop_3_json (str): JSON string result from the third lop_compute call.
        estimated_latitude (float): The assumed latitude used for the LOP calculations.
        estimated_longitude (float): The assumed longitude used for the LOP calculations.

    Returns:
        str: A JSON string with the calculated fix or an error message.
    """
    print("Python: lop_center_compute called.")
    try:
        # --- 1. Parse LOP data from JSON ---
        lops = [json.loads(lop_json) for lop_json in [lop_1_json, lop_2_json, lop_3_json]]

        # Check for errors in LOP data
        for i, lop in enumerate(lops):
            if lop.get('error') is not None:
                raise ValueError(f"Error in LOP {i+1}: {lop['error']}")

        intercepts = np.array([lop['intercept_nm'] for lop in lops])
        azimuths_deg = np.array([lop['azimuth_deg'] for lop in lops])
        azimuths_rad = np.deg2rad(azimuths_deg)

        # --- 2. Solve for position correction using least squares ---
        # We want to find a correction (d_east, d_north) in nautical miles from the
        # assumed position. The equation for each LOP is:
        # intercept = d_east * sin(azimuth) + d_north * cos(azimuth)

        # Set up the matrix 'A' and vector 'b' for the system Ax = b
        A = np.array([
            [np.sin(az), np.cos(az)] for az in azimuths_rad
        ])
        b = intercepts

        # Use numpy's least-squares solver to find x = [d_east, d_north]
        correction, residuals, rank, s = np.linalg.lstsq(A, b, rcond=None)
        d_east_nm, d_north_nm = correction[0], correction[1]

        # --- 3. Apply correction to assumed position ---
        # Convert corrections from nautical miles to degrees.
        # 1 degree of latitude = 60 NM
        # 1 degree of longitude = 60 * cos(latitude) NM
        lat_correction_deg = d_north_nm / 60.0
        lon_correction_deg = d_east_nm / (60.0 * np.cos(np.deg2rad(estimated_latitude)))

        fixed_latitude = estimated_latitude + lat_correction_deg
        fixed_longitude = estimated_longitude + lon_correction_deg

        # --- 4. Estimate the error ---
        # The residual is the sum of squared errors. A good error estimate is the
        # Root Mean Square Error (RMSE) of the distances from the fix to each LOP.
        if residuals.size > 0:
            # The number of degrees of freedom is (number of LOPs - number of variables)
            degrees_of_freedom = len(lops) - 2
            if degrees_of_freedom > 0:
                error_estimate_nm = np.sqrt(residuals[0] / degrees_of_freedom)
            else:
                error_estimate_nm = 0.0 # Cannot estimate error with 2 or fewer LOPs
        else:
            # If there are no residuals, the solution perfectly fits the LOPs (they intersect at one point).
            error_estimate_nm = 0.0

        result = {
            "fixed_latitude": fixed_latitude,
            "fixed_longitude": fixed_longitude,
            "error_estimate_nm": error_estimate_nm
        }
        print(f"Python: Fix calculated: Lat={fixed_latitude:.4f}, Lon={fixed_longitude:.4f}")
        return json.dumps(result)

    except Exception as e:
        error_msg = f"An exception occurred in lop_center_compute: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({'error': error_msg})




# --- Refactored Core Logic for Iteration ---

def core_compute_intercept(ra, dec, time_obj, lat, lon, alt_obs, height_m=0.0, pressure_hpa=1013.25, temperature_c=15.0):
    # Setup
    loc = EarthLocation(lat=lat*u.deg, lon=lon*u.deg, height=height_m*u.m)
    body = SkyCoord(ra=ra*u.deg, dec=dec*u.deg, frame='icrs')
    frame = AltAz(obstime=time_obj, location=loc, pressure=pressure_hpa*u.hPa, temperature=temperature_c*u.deg_C)

    sky = body.transform_to(frame)
    hc = sky.alt.degree
    az = sky.az.degree

    intercept = (alt_obs - hc) * 60.0
    return intercept, az

def solve_iterative(obs_list_json, estimated_lat, estimated_lon, height_m=0.0, pressure_hpa=1013.25, temperature_c=15.0):
    """
    Iterative least-squares position solver.

    obs_list_json: JSON string of list of dicts:
       [{'ra':, 'dec':, 'alt':, 'time_iso':}, ...]
    estimated_lat: The user provided estimated latitude (float)
    estimated_lon: The user provided estimated longitude (float)
    height_m: Observer height above sea level in meters
    pressure_hpa: Atmospheric pressure in hPa for refraction correction
    temperature_c: Air temperature in Celsius for refraction correction

    Returns JSON with: fixed_latitude, fixed_longitude, iterations, final_shift_nm
    On error: returns JSON with 'error' key.
    """
    try:
        obs_list = json.loads(obs_list_json)

        if len(obs_list) < 2:
            return json.dumps({"error": "Need at least 2 observations for iterative solve"})

        # 1. Parse Times
        times = []
        for obs in obs_list:
            t = Time(obs['time_iso'])
            times.append(t)

        # Start at the user-provided Estimated Position
        current_lat = float(estimated_lat)
        current_lon = float(estimated_lon)

        print(f"Python: Seeding iterative solver at {current_lat:.4f}, {current_lon:.4f}")

        # 2. Iterate with convergence and divergence checks
        MAX_ITERATIONS = 20
        CONVERGENCE_THRESHOLD = 0.01  # NM
        MAX_DIVERGENCE_STREAK = 3

        prev_shift = float('inf')
        divergence_count = 0
        final_shift = 0.0
        iterations_done = 0

        for i in range(MAX_ITERATIONS):
            intercepts = []
            azimuths = []

            for j, obs in enumerate(obs_list):
                ic, az = core_compute_intercept(
                    obs['ra'], obs['dec'], times[j],
                    current_lat, current_lon, obs['alt'],
                    height_m=height_m, pressure_hpa=pressure_hpa, temperature_c=temperature_c
                )
                intercepts.append(ic)
                azimuths.append(az)

            # Solve Linear Shift
            A = np.array([[np.sin(np.deg2rad(az)), np.cos(np.deg2rad(az))] for az in azimuths])
            b = np.array(intercepts)

            correction, _, _, _ = np.linalg.lstsq(A, b, rcond=None)
            d_east, d_north = correction[0], correction[1]

            shift_mag = np.sqrt(d_east**2 + d_north**2)
            final_shift = float(shift_mag)
            iterations_done = i + 1

            # Apply shift
            lat_rad = np.deg2rad(current_lat)
            current_lat += d_north / 60.0
            current_lon += d_east / (60.0 * np.cos(lat_rad))

            print(f"Python: Iteration {i+1}: Shift {shift_mag:.4f} NM. Pos: {current_lat:.4f}, {current_lon:.4f}")

            # Check convergence
            if shift_mag < CONVERGENCE_THRESHOLD:
                print(f"Python: Converged after {i+1} iterations (shift {shift_mag:.4f} < {CONVERGENCE_THRESHOLD} NM)")
                break

            # Check divergence (shift increasing for too many consecutive iterations)
            if shift_mag >= prev_shift:
                divergence_count += 1
                if divergence_count >= MAX_DIVERGENCE_STREAK:
                    print(f"Python: WARNING - Divergence detected after {i+1} iterations (shift increasing {divergence_count}x)")
                    break
            else:
                divergence_count = 0

            prev_shift = shift_mag
        else:
            print(f"Python: WARNING - Max iterations ({MAX_ITERATIONS}) reached without convergence. Final shift: {final_shift:.4f} NM")

        return json.dumps({
            "fixed_latitude": float(current_lat),
            "fixed_longitude": float(current_lon),
            "iterations": iterations_done,
            "final_shift_nm": final_shift
        })

    except Exception as e:
        error_msg = f"Error in solve_iterative: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})
