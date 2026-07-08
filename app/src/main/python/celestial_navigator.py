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
USE_PER_IMAGE_SELF_CALIBRATION = False

# EXIF orientation tag values -> PIL transpose operations (same mapping as
# ImageOps.exif_transpose). Applied manually because exif_transpose also
# re-serializes the EXIF block, which crashes on Pixel camera EXIF containing
# rationals with a zero denominator (e.g. DigitalZoomRatio = 0/0):
# ZeroDivisionError in PIL TiffImagePlugin.write_rational.
_ORIENTATION_TRANSPOSES = {
    2: Image.FLIP_LEFT_RIGHT,
    3: Image.ROTATE_180,
    4: Image.FLIP_TOP_BOTTOM,
    5: Image.TRANSPOSE,
    6: Image.ROTATE_270,
    7: Image.TRANSVERSE,
    8: Image.ROTATE_90,
}


def _apply_exif_orientation(img):
    """Rotates/flips pixels per the EXIF orientation tag WITHOUT rewriting the
    EXIF data (reading the tag is safe; re-serializing broken rationals is not).
    The stale EXIF is dropped from the result so nothing downstream re-applies it.
    """
    try:
        orientation = img.getexif().get(0x0112, 1)
    except Exception:
        orientation = 1
    method = _ORIENTATION_TRANSPOSES.get(orientation)
    if method is not None:
        img = img.transpose(method)
        img.info.pop('exif', None)
    return img

# Solver parameters come from self_calibration.active_params(): the shipped
# Pixel 8a calibration (desktop optimizer v3 run, 2026-07-08: database
# db_calib_fov48.69_mag7.0.npz, field replay 116/126 solved, 0 false
# positives) until the on-device self-calibration adopts values for THIS
# phone (FOV, database from the shipped ladder, lens distortion). See
# self_calibration.py for the full design.
import self_calibration

T3_DB_NAME = None
_T3_LOCK = threading.Lock()


def _get_solver(db_name):
    """Tetra3 instance for db_name, reloading when the on-device calibration
    switches databases. Keeps the previous instance if loading fails."""
    global T3_INSTANCE, T3_DB_NAME
    with _T3_LOCK:
        if T3_INSTANCE is not None and T3_DB_NAME == db_name:
            return T3_INSTANCE
        try:
            t3 = tetra3.Tetra3(load_database=db_name)
            if t3.has_database:
                T3_INSTANCE, T3_DB_NAME = t3, db_name
                print(f"Python: Tetra3 solver using database {db_name}")
            else:
                print(f"Python: database {db_name} failed to load; keeping "
                      f"{T3_DB_NAME}")
        except Exception as e_load:
            print(f"Python: error loading database {db_name}: {e_load}; "
                  f"keeping {T3_DB_NAME}")
        return T3_INSTANCE


try:
    print("Python: Initializing Tetra3 Astrometry Solver...")
    _get_solver(self_calibration.active_params()["db_name"])
    if T3_INSTANCE is not None and T3_INSTANCE.has_database:
        print("Python: Tetra3 Solver initialized successfully.")
    else:
        INITIALIZATION_ERROR = "Tetra3 instance created but database FAILED to load."
        print(f"Python: ERROR - {INITIALIZATION_ERROR}")

except Exception as e_init:
    INITIALIZATION_ERROR = f"FATAL ERROR initializing Tetra3: {e_init}\n{traceback.format_exc()}"
    print(f"Python: {INITIALIZATION_ERROR}")

# =============================================================================
# HIP Star Name Lookup Table
# Maps Hipparcos catalogue IDs to (common_name, IAU_constellation_abbreviation)
# Covers the ~150 brightest / most recognizable navigational stars.
# =============================================================================
HIP_STAR_NAMES = {
    # --- Canis Major (CMa) ---
    32349: ("Sirius", "CMa"),
    33579: ("Wezen", "CMa"),
    34444: ("Aludra", "CMa"),
    31592: ("Mirzam", "CMa"),
    35904: ("Furud", "CMa"),
    33856: ("Adhara", "CMa"),
    # --- Orion (Ori) ---
    27989: ("Betelgeuse", "Ori"),
    24436: ("Rigel", "Ori"),
    26311: ("Bellatrix", "Ori"),
    26727: ("Mintaka", "Ori"),
    25336: ("Alnilam", "Ori"),
    25930: ("Alnitak", "Ori"),
    22449: ("Saiph", "Ori"),
    # --- Canis Minor (CMi) ---
    37279: ("Procyon", "CMi"),
    36188: ("Gomeisa", "CMi"),
    # --- Taurus (Tau) ---
    21421: ("Aldebaran", "Tau"),
    25428: ("Elnath", "Tau"),
    20889: ("Ain", "Tau"),
    # --- Gemini (Gem) ---
    36850: ("Castor", "Gem"),
    37826: ("Pollux", "Gem"),
    31681: ("Alhena", "Gem"),
    35550: ("Mebsuta", "Gem"),
    # --- Leo (Leo) ---
    49669: ("Regulus", "Leo"),
    57632: ("Denebola", "Leo"),
    50583: ("Algieba", "Leo"),
    54872: ("Zosma", "Leo"),
    # --- Virgo (Vir) ---
    65474: ("Spica", "Vir"),
    63608: ("Vindemiatrix", "Vir"),
    # --- Boötes (Boo) ---
    69673: ("Arcturus", "Boo"),
    67927: ("Izar", "Boo"),
    # --- Scorpius (Sco) ---
    80763: ("Antares", "Sco"),
    78820: ("Dschubba", "Sco"),
    84143: ("Shaula", "Sco"),
    82396: ("Sargas", "Sco"),
    86228: ("Lesath", "Sco"),
    78265: ("Graffias", "Sco"),
    # --- Lyra (Lyr) ---
    91262: ("Vega", "Lyr"),
    91971: ("Sheliak", "Lyr"),
    92420: ("Sulafat", "Lyr"),
    # --- Aquila (Aql) ---
    97649: ("Altair", "Aql"),
    97278: ("Tarazed", "Aql"),
    # --- Cygnus (Cyg) ---
    102098: ("Deneb", "Cyg"),
    95947: ("Sadr", "Cyg"),
    100453: ("Gienah Cygni", "Cyg"),
    94779: ("Albireo", "Cyg"),
    # --- Ursa Major (UMa) ---
    54061: ("Alioth", "UMa"),
    62956: ("Alkaid", "UMa"),
    53910: ("Megrez", "UMa"),
    58001: ("Mizar", "UMa"),
    59774: ("Alcor", "UMa"),
    48319: ("Phad", "UMa"),
    46733: ("Merak", "UMa"),
    50801: ("Dubhe", "UMa"),
    # --- Ursa Minor (UMi) ---
    11767: ("Polaris", "UMi"),
    72607: ("Kochab", "UMi"),
    75097: ("Pherkad", "UMi"),
    # --- Cassiopeia (Cas) ---
    3179: ("Schedar", "Cas"),
    746: ("Caph", "Cas"),
    4427: ("Tsih", "Cas"),
    6686: ("Ruchbah", "Cas"),
    # --- Centaurus (Cen) ---
    71683: ("Alpha Centauri", "Cen"),
    68702: ("Hadar", "Cen"),
    71681: ("Toliman", "Cen"),
    61932: ("Menkent", "Cen"),
    # --- Crux (Cru) ---
    60718: ("Acrux", "Cru"),
    62434: ("Mimosa", "Cru"),
    61084: ("Gacrux", "Cru"),
    # --- Carina (Car) ---
    30438: ("Canopus", "Car"),
    45238: ("Avior", "Car"),
    41037: ("Miaplacidus", "Car"),
    # --- Eridanus (Eri) ---
    7588: ("Achernar", "Eri"),
    23875: ("Cursa", "Eri"),
    # --- Piscis Austrinus (PsA) ---
    113368: ("Fomalhaut", "PsA"),
    # --- Perseus (Per) ---
    15863: ("Mirfak", "Per"),
    14576: ("Algol", "Per"),
    # --- Auriga (Aur) ---
    24608: ("Capella", "Aur"),
    28360: ("Menkalinan", "Aur"),
    # --- Sagittarius (Sgr) ---
    90185: ("Kaus Australis", "Sgr"),
    89931: ("Nunki", "Sgr"),
    88635: ("Ascella", "Sgr"),
    86414: ("Kaus Media", "Sgr"),
    85927: ("Kaus Borealis", "Sgr"),
    # --- Pegasus (Peg) ---
    113963: ("Markab", "Peg"),
    112158: ("Scheat", "Peg"),
    1067: ("Algenib", "Peg"),
    109410: ("Enif", "Peg"),
    # --- Andromeda (And) ---
    677: ("Alpheratz", "And"),
    5447: ("Mirach", "And"),
    9640: ("Almach", "And"),
    # --- Aries (Ari) ---
    9884: ("Hamal", "Ari"),
    8903: ("Sheratan", "Ari"),
    # --- Libra (Lib) ---
    72622: ("Zubenelgenubi", "Lib"),
    74785: ("Zubeneschamali", "Lib"),
    # --- Corona Borealis (CrB) ---
    76267: ("Alphecca", "CrB"),
    # --- Capricornus (Cap) ---
    100345: ("Algedi", "Cap"),
    104139: ("Deneb Algedi", "Cap"),
    # --- Aquarius (Aqr) ---
    109074: ("Sadalsuud", "Aqr"),
    106278: ("Sadalmelik", "Aqr"),
    # --- Pisces (Psc) ---
    7097: ("Alrescha", "Psc"),
    # --- Ophiuchus (Oph) ---
    86032: ("Rasalhague", "Oph"),
    84012: ("Sabik", "Oph"),
    # --- Hercules (Her) ---
    84345: ("Rasalgethi", "Her"),
    79593: ("Kornephoros", "Her"),
    # --- Draco (Dra) ---
    87833: ("Eltanin", "Dra"),
    85670: ("Rastaban", "Dra"),
    # --- Lupus (Lup) ---
    70576: ("Men", "Lup"),
    # --- Hydra (Hya) ---
    46390: ("Alphard", "Hya"),
    # --- Corvus (Crv) ---
    59316: ("Gienah", "Crv"),
    # --- Crater (Crt) ---
    # --- Puppis (Pup) ---
    39953: ("Naos", "Pup"),
    # --- Vela (Vel) ---
    44816: ("Suhail", "Vel"),
    # --- Columba (Col) ---
    25985: ("Phact", "Col"),
    # --- Triangulum Australe (TrA) ---
    82273: ("Atria", "TrA"),
    # --- Pavo (Pav) ---
    100751: ("Peacock", "Pav"),
    # --- Grus (Gru) ---
    109268: ("Alnair", "Gru"),
    # --- Phoenix (Phe) ---
    2081: ("Ankaa", "Phe"),
    # --- Tucana (Tuc) ---
    114996: ("Alpha Tucanae", "Tuc"),
}

# IAU Constellation full names from abbreviations
CONSTELLATION_NAMES = {
    "CMa": "Canis Major", "Ori": "Orion", "CMi": "Canis Minor",
    "Tau": "Taurus", "Gem": "Gemini", "Leo": "Leo", "Vir": "Virgo",
    "Boo": "Boötes", "Sco": "Scorpius", "Lyr": "Lyra", "Aql": "Aquila",
    "Cyg": "Cygnus", "UMa": "Ursa Major", "UMi": "Ursa Minor",
    "Cas": "Cassiopeia", "Cen": "Centaurus", "Cru": "Crux",
    "Car": "Carina", "Eri": "Eridanus", "PsA": "Piscis Austrinus",
    "Per": "Perseus", "Aur": "Auriga", "Sgr": "Sagittarius",
    "Peg": "Pegasus", "And": "Andromeda", "Ari": "Aries",
    "Lib": "Libra", "CrB": "Corona Borealis", "Cap": "Capricornus",
    "Aqr": "Aquarius", "Psc": "Pisces", "Oph": "Ophiuchus",
    "Her": "Hercules", "Dra": "Draco", "Lup": "Lupus",
    "Hya": "Hydra", "Crv": "Corvus", "Pup": "Puppis",
    "Vel": "Vela", "Col": "Columba", "TrA": "Triangulum Australe",
    "Pav": "Pavo", "Gru": "Grus", "Phe": "Phoenix", "Tuc": "Tucana",
}

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

        cmd = [
            binary_path, "--input", image_path, "--output", output_path,
            "--sigma", "4.305311087485253", "--binning", "2", "--hot-pixels", "true"
        ]
        print(f"Python: Running cedar_cli with args: {cmd}")
        result = subprocess.check_output(
            cmd,
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

def image_processor(image_name, image_path, intrinsics_json_str="{}"):
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
    print(f"Python: image_processor received intrinsics: {intrinsics_json_str}")

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
    rotated_cedar_input_path = None
    try:
        print(f"Python: Opening image: {image_path}...")
        with Image.open(image_path) as img:
            # Step 2: Fix geometry by rotating landscape images to portrait.
            # Apply the EXIF orientation tag (which the raw CameraX capture sets,
            # since it stores pixels landscape with a rotation tag) so the pixel
            # buffer we hand to cedar_cli agrees with the size we later declare
            # to the solver. Fall back to the width>height heuristic only when
            # there is no usable EXIF tag.
            orig_size_before = (img.width, img.height)
            img = _apply_exif_orientation(img)
            if (img.width, img.height) == orig_size_before and img.width > img.height:
                print(f"Python: No EXIF orientation; image is landscape ({img.width}x{img.height}), "
                      f"rotating 90 deg to portrait.")
                img = img.transpose(Image.ROTATE_90)

            if (img.width, img.height) != orig_size_before:
                print(f"Python: Rotated image to {img.width}x{img.height} to correct orientation.")
                # The pixels cedar_cli reads must match the rotation we just applied
                # in-memory, otherwise centroids end up in a different frame than the
                # (orig_height, orig_width) we declare to the solver below. Persist the
                # rotated image and point the CLI at that instead of the raw file.
                rotated_cedar_input_path = image_path + ".rotated.jpg"
                img.save(rotated_cedar_input_path, quality=95)

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

        cedar_input_path = rotated_cedar_input_path or image_path
        print(f"Python: Extracting centroids using Cedar Detect CLI on {cedar_input_path}...")
        cedar_result = detect_centroids_cli(cedar_input_path)

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
                # Step 1b: Sort descending by brightness so we keep the real stars
                cedar_stars.sort(key=lambda s: s.get("brightness", 0), reverse=True)
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

        # Use the 75 brightest centroids for solving
        # If we used Cedar, they might not be sorted by brightness.
        # If we used Tetra3, they are sorted.
        # We can sort by brightness if we had it, but for now just taking first 75 is usually okay if Cedar returns them in order.
        # Cedar CLI usually returns them sorted by brightness descending.
        #
        # Cap at the database's verification_stars_per_fov as well: upstream
        # tetra3 computes pattern indices BEFORE trimming to that limit and
        # then indexes out of bounds (see cedar-solve/LOCAL_PATCHES.md) —
        # capping here keeps that code path from ever being entered.
        # Active calibration: shipped Pixel 8a values until the on-device
        # self-calibration adopts values for this phone.
        cal = self_calibration.active_params()
        t3 = _get_solver(cal["db_name"]) or T3_INSTANCE

        max_centroids = 75
        try:
            vspf = t3.database_properties.get('verification_stars_per_fov')
            if vspf:
                max_centroids = min(max_centroids, int(vspf))
        except Exception:
            pass
        centroids = centroids[:max_centroids]

        # Convert to list of lists if it's not already compatible
        # Ensure elements are native Python floats, not np.float32, for JSON serialization
        centroids_list = [[float(c[0]), float(c[1])] for c in centroids]

        print(f"Python: Found {len(centroids)} centroids, using {len(centroids_list)} for solving.")

        # Record raw (pre-undistortion) centroids for the background
        # self-calibration, and start a pass if one is due. The pass runs in
        # a daemon thread and never blocks this solve.
        try:
            self_calibration.record_sample(centroids_list, orig_width, orig_height)
            if self_calibration.maybe_start_calibration():
                print("Python: self-calibration pass started in background.")
        except Exception as e_cal:
            print(f"Python: self-calibration bookkeeping failed: {e_cal}")

        # Parse intrinsics
        try:
            camera_info = json.loads(intrinsics_json_str)
        except Exception:
            camera_info = {}

        # Step 0: Initial distortion estimate (Camera2 metadata)
        cx, cy = orig_width / 2.0, orig_height / 2.0
        fx, fy = cx, cy # Fallback
        intr = camera_info.get("intrinsics")
        if intr and len(intr) >= 5:
            fx, fy, cx, cy, _ = intr
            
        dist = camera_info.get("distortion")
        if dist is None:
            dist = camera_info.get("radial_distortion")
            
        first_solve_centroids = []
        # Step 3: Gate off Step 0 pre-correction
        if False and dist and len(dist) >= 5:
            k1_init, k2_init, k3_init, p1_init, p2_init = dist[:5]
            for y, x in centroids_list:
                nx = (x - cx) / fx
                ny = (y - cy) / fy
                r2 = nx*nx + ny*ny
                r4 = r2*r2
                r6 = r4*r2
                radial = 1 + k1_init*r2 + k2_init*r4 + k3_init*r6
                dx = nx * radial + 2*p1_init*nx*ny + p2_init*(r2 + 2*nx*nx)
                dy = ny * radial + p1_init*(r2 + 2*ny*ny) + 2*p2_init*nx*ny
                
                corr_x = dx * fx + cx
                corr_y = dy * fy + cy
                first_solve_centroids.append([corr_y, corr_x])
            print("Python: Pre-corrected centroids using Camera2 LENS_DISTORTION.")
        else:
            first_solve_centroids = list(centroids_list)

        # Step 0b: Frozen lens calibration. Two-term radial undistortion
        # (k1/k2 from the active calibration: shipped Pixel 8a fit from 960
        # matched stars, or this phone's own self-calibration), applied to
        # centroids before solving with tetra3's internal distortion at 0.
        # r is normalised by the focal length implied by the calibrated FOV
        # over the portrait width.
        f_cal = (orig_width / 2.0) / np.tan(np.radians(cal["fov_estimate"]) / 2.0)
        ccx, ccy = orig_width / 2.0, orig_height / 2.0
        undistorted = []
        for y, x in first_solve_centroids:
            xn = (x - ccx) / f_cal
            yn = (y - ccy) / f_cal
            r2 = xn * xn + yn * yn
            d_scale = 1.0 + cal["k1"] * r2 + cal["k2"] * r2 * r2
            undistorted.append([ccy + f_cal * yn * d_scale,
                                ccx + f_cal * xn * d_scale])
        first_solve_centroids = undistorted

        # Step 1: First (coarse) solve
        # Pipeline shape (db + sigma + undistortion + match params) frozen by
        # the optimize_neosextant.py v3 run (2026-07-08): detection via the
        # app-identical cedar-cli binary at full res, ground truth from
        # astrometry.net over 126 field frames. Field replay: 116/126 solved
        # (was 103), 83/84 truth frames correct, 0 false positives,
        # p90 boresight error 0.098 deg. fov/db/k1/k2 come from the active
        # calibration; the match params are fixed (see self_calibration.py
        # for why they are never tuned on-device). The loose match_threshold
        # admits borderline-but-correct solves; the tight fov_max_error is
        # the false-positive guard.
        solution = t3.solve_from_centroids(
            first_solve_centroids,
            (orig_height, orig_width),
            fov_estimate=cal["fov_estimate"],
            fov_max_error=cal["fov_max_error"],
            pattern_checking_stars=self_calibration.MATCH_PARAMS["pattern_checking_stars"],
            match_radius=self_calibration.MATCH_PARAMS["match_radius"],
            match_threshold=self_calibration.MATCH_PARAMS["match_threshold"],
            solve_timeout=10000,
            return_matches=True
        )

        print("Python: Tetra3 first solve complete.")
        centroids_to_return = first_solve_centroids
        
        if solution.get('RA') is not None:
            if USE_PER_IMAGE_SELF_CALIBRATION:
                # Step 2: Fit distortion from matches
                matched_centroids_raw = solution.get('matched_centroids', [])
                matched_stars_data = solution.get('matched_stars', [])
                
                ra_deg = solution.get('RA')
                dec_deg = solution.get('Dec')
                roll_deg = solution.get('Roll')
                fov_deg = solution.get('FOV')
                
                # Reconstruct Rotation matrix R (ICRS to Camera)
                ra_rad = np.radians(ra_deg)
                dec_rad = np.radians(dec_deg)
                roll_rad = np.radians(roll_deg)
                
                cos_ra, sin_ra = np.cos(ra_rad), np.sin(ra_rad)
                cos_dec, sin_dec = np.cos(dec_rad), np.sin(dec_rad)
                cos_roll, sin_roll = np.cos(roll_rad), np.sin(roll_rad)
                
                b = np.array([cos_dec * cos_ra, cos_dec * sin_ra, sin_dec])
                E = np.array([-sin_ra, cos_ra, 0.0])
                N = np.array([-sin_dec * cos_ra, -sin_dec * sin_ra, cos_dec])
                up = cos_roll * N + sin_roll * E
                left = np.cross(up, b)
                
                R = np.array([b, left, up])
                
                # Calculate focal length in pixels using FOV
                fit_fx = (orig_width / 2.0) / np.tan(np.radians(fov_deg) / 2.0)
                fit_fy = fit_fx
                fit_cx = orig_width / 2.0
                fit_cy = orig_height / 2.0
                
                matched_uncorrected = []
                expected_positions = []
                
                for i in range(len(matched_centroids_raw)):
                    my_y, my_x = matched_centroids_raw[i]
                    # Find closest in first_solve_centroids to get original
                    dists = [(my_y - c[0])**2 + (my_x - c[1])**2 for c in first_solve_centroids]
                    best_idx = np.argmin(dists)
                    orig_y, orig_x = centroids_list[best_idx]
                    matched_uncorrected.append((orig_y, orig_x))
                    
                    # Compute expected position
                    s_ra = np.radians(matched_stars_data[i][0])
                    s_dec = np.radians(matched_stars_data[i][1])
                    s_v = np.array([np.cos(s_dec)*np.cos(s_ra), np.cos(s_dec)*np.sin(s_ra), np.sin(s_dec)])
                    
                    s_cam = R @ s_v
                    vz = s_cam[0]
                    vx = -s_cam[1]
                    vy = -s_cam[2]
                    
                    x_exp = fit_fx * (vx / vz) + fit_cx
                    y_exp = fit_fy * (vy / vz) + fit_cy
                    expected_positions.append((y_exp, x_exp))
                    
                # Least squares for k1, k2
                A = []
                B = []
                for i in range(len(matched_uncorrected)):
                    y_meas, x_meas = matched_uncorrected[i]
                    y_exp, x_exp = expected_positions[i]
                    
                    dx_p = x_exp - fit_cx
                    dy_p = y_exp - fit_cy
                    r = np.sqrt(dx_p*dx_p + dy_p*dy_p)
                    r2 = r*r
                    r4 = r2*r2
                    
                    A.append([dx_p * r2, dx_p * r4])
                    B.append(x_meas - x_exp)
                    
                    A.append([dy_p * r2, dy_p * r4])
                    B.append(y_meas - y_exp)
                    
                A = np.array(A)
                B = np.array(B)
                
                num_matches = len(matched_uncorrected)
                k1_fit, k2_fit = 0.0, 0.0
                skip_second_solve = False
                
                if num_matches >= 8:
                    res, residuals, _, _ = np.linalg.lstsq(A, B, rcond=None)
                    k1_fit, k2_fit = res[0], res[1]
                elif num_matches >= 4:
                    A1 = A[:, 0:1]
                    res, residuals, _, _ = np.linalg.lstsq(A1, B, rcond=None)
                    k1_fit = res[0]
                else:
                    print("Python: Not enough matches for self-calibration, skipping second solve.")
                    skip_second_solve = True
                    
                if not skip_second_solve:
                    print(f"Python: Self-calibration fitted k1={k1_fit:e}, k2={k2_fit:e} from {num_matches} matches")
                    
                    # Step 3: Undistort all centroids and re-solve
                    final_centroids = []
                    for y, x in centroids_list:
                        dx_p = x - fit_cx
                        dy_p = y - fit_cy
                        r = np.sqrt(dx_p*dx_p + dy_p*dy_p)
                        scale = 1 + k1_fit*(r**2) + k2_fit*(r**4)
                        x_corr = fit_cx + dx_p / scale
                        y_corr = fit_cy + dy_p / scale
                        final_centroids.append([y_corr, x_corr])
                        
                    centroids_to_return = final_centroids
                    
                    # Pass distortion=None to disable tetra3's internal single-parameter distortion
                    solution2 = T3_INSTANCE.solve_from_centroids(
                        final_centroids,
                        (orig_height, orig_width),
                        fov_estimate=fov_deg,
                        solve_timeout=10000,
                        return_matches=True,
                        distortion=None
                    )
                    
                    if solution2.get('RA') is not None:
                        print("Python: Second (refined) solve successful.")
                        solution = solution2
                    else:
                        print("Python: Second solve failed, falling back to first solve.")
                        centroids_to_return = first_solve_centroids

            # Build matched star info with names from HIP lookup
            matched_star_list = []
            matched_centroids_raw = solution.get('matched_centroids', [])
            matched_cat_ids = solution.get('matched_catID', [])
            matched_stars_data = solution.get('matched_stars', [])

            if matched_centroids_raw and matched_cat_ids:
                for idx in range(len(matched_centroids_raw)):
                    centroid = matched_centroids_raw[idx]
                    cat_id = matched_cat_ids[idx] if idx < len(matched_cat_ids) else None
                    star_data = matched_stars_data[idx] if idx < len(matched_stars_data) else None

                    if cat_id is not None:
                        if isinstance(cat_id, (list, tuple, np.ndarray)):
                            hip_id = int(cat_id[0]) if len(cat_id) > 0 else -1
                        else:
                            hip_id = int(cat_id)
                    else:
                        hip_id = -1
                    name_info = HIP_STAR_NAMES.get(hip_id)
                    star_name = name_info[0] if name_info else None
                    constellation = name_info[1] if name_info else None
                    magnitude = float(star_data[2]) if star_data and len(star_data) > 2 else None

                    matched_star_list.append({
                        "name": star_name,
                        "constellation": constellation,
                        "hip_id": hip_id,
                        "y": float(centroid[0]),
                        "x": float(centroid[1]),
                        "magnitude": magnitude
                    })

                print(f"Python: Matched {len(matched_star_list)} stars, {sum(1 for s in matched_star_list if s['name'])} named.")

            final_result = {
                "solved": 1,
                "ra_deg": solution.get('RA'),
                "dec_deg": solution.get('Dec'),
                "roll_deg": solution.get('Roll'),
                "fov_deg": solution.get('FOV'),
                "centroids": centroids_to_return,
                "matched_stars": matched_star_list,
                "error_message": None
            }
            print(f"Python: Solution FOUND: RA={final_result['ra_deg']:.4f}, Dec={final_result['dec_deg']:.4f}")
            return json.dumps(final_result)
        else:
            print(f"Python: Solution NOT found. Status: {solution.get('status', 'Unknown')}")
            return json.dumps({"solved": 0, "centroids": centroids_to_return, "error_message": f"No match found. Tetra3 status: {solution.get('status')}"})

    except Exception as e:
        error_msg = f"An exception occurred in image_processor: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"solved": 0, "error_message": error_msg})
    finally:
        if rotated_cedar_input_path:
            try:
                os.remove(rotated_cedar_input_path)
            except OSError:
                pass


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
    time_iso
):
    """
    Calculates the intercept from a celestial observation.

    Args:
        ra_from_image (float): Right Ascension from the image solver.
        dec_from_image (float): Declination from the image solver.
        estimated_latitude (float): Assumed latitude in degrees.
        estimated_longitude (float): Assumed longitude in degrees.
        height_of_eye_m (float): Observer's height of eye in meters (0 for sensor-based altitude).
        pressure_hpa (float): Atmospheric pressure in hPa.
        temperature_celsius (float): Air temperature in Celsius.
        sextant_altitude_deg (float): The raw altitude measured with the sextant.
        time_iso (str): Observation time as ISO 8601 UTC string (e.g. '2026-05-08T12:00:00.000').

    Returns:
        str: A JSON string with the calculation results or an error.
    """
    print("Python: lop_compute function started.")
    try:
        # 1. Correct sextant altitude for dip to get Observed Altitude (Ho).
        dip_correction_deg = _calculate_dip_correction_deg(height_of_eye_m)
        ho_deg = sextant_altitude_deg - dip_correction_deg

        # 2. Parse observation time (already UTC ISO 8601).
        observation_time = Time(time_iso)

        # 3. Set up Astropy objects for calculation.
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


def solve_lop_iterative(obs_list_json, estimated_lat, estimated_lon, height_m=0.0, pressure_hpa=1013.25, temperature_c=15.0):
    """
    Iterative LOP position solver.

    Like solve_iterative, this re-computes intercepts from the corrected
    position at each iteration so that the linear approximation stays valid
    even when the assumed position is far from truth.

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

        if len(obs_list) < 3:
            return json.dumps({"error": "Need at least 3 observations for LOP solve"})

        # 1. Parse Times
        times = [Time(obs['time_iso']) for obs in obs_list]

        # Start at the user-provided Estimated Position
        current_lat = float(estimated_lat)
        current_lon = float(estimated_lon)

        print(f"Python: LOP iterative solver seeded at {current_lat:.4f}, {current_lon:.4f}")

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

            print(f"Python: LOP Iteration {i+1}: Shift {shift_mag:.4f} NM. Pos: {current_lat:.4f}, {current_lon:.4f}")

            # Check convergence
            if shift_mag < CONVERGENCE_THRESHOLD:
                print(f"Python: LOP converged after {i+1} iterations (shift {shift_mag:.4f} < {CONVERGENCE_THRESHOLD} NM)")
                break

            # Check divergence
            if shift_mag >= prev_shift:
                divergence_count += 1
                if divergence_count >= MAX_DIVERGENCE_STREAK:
                    print(f"Python: LOP WARNING - Divergence detected after {i+1} iterations")
                    break
            else:
                divergence_count = 0

            prev_shift = shift_mag
        else:
            print(f"Python: LOP WARNING - Max iterations ({MAX_ITERATIONS}) reached. Final shift: {final_shift:.4f} NM")

        return json.dumps({
            "fixed_latitude": float(current_lat),
            "fixed_longitude": float(current_lon),
            "iterations": iterations_done,
            "final_shift_nm": final_shift
        })

    except Exception as e:
        error_msg = f"Error in solve_lop_iterative: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})




# --- 1-SHOT FIX CALCULATION ---
def solve_oneshot(ra_deg, dec_deg, roll_deg, gx, gy, gz, time_iso, image_path):
    """
    Computes a 1-Shot geographic fix from a single image using the
    3D Gravity Vector (zenith direction) and the plate-solve orientation.

    Algorithm:
    1. Reconstruct tetra3's rotation matrix R (ICRS → camera) from RA/Dec/Roll.
    2. Map the zenith vector from Android sensor frame to tetra3's camera frame.
    3. Transform zenith from camera frame to ICRS: zenith_icrs = R^T @ zenith_cam.
    4. Extract latitude = arcsin(z_icrs), LST = atan2(y_icrs, x_icrs).
    5. Longitude = LST − GST.
    """
    try:
        import numpy as np
        from numpy.linalg import norm as np_norm
        from astropy.time import Time
        from PIL import Image
        import json
        import traceback

        # --- 1. Time and Earth Rotation Angle ---
        # We use ERA (Earth Rotation Angle) instead of GST (Greenwich Sidereal Time)
        # because the plate-solved RA is in the ICRS/J2000 frame, and ERA is
        # measured from the Celestial Intermediate Origin (CIO) which is fixed
        # in ICRS.  GST is measured from the mean equinox of date, which drifts
        # ~0.014°/year due to precession — causing a systematic westward
        # longitude error (~20 nm by 2026).
        t = Time(time_iso)
        era_deg = t.earth_rotation_angle('tio').deg
        print(f"Python: 1-Shot: time={time_iso}, ERA={era_deg:.4f}°")

        # --- 2. Reconstruct tetra3's rotation matrix from RA/Dec/Roll ---
        # tetra3 defines R as mapping ICRS vectors to camera-frame vectors.
        # Rows of R are the camera axes expressed in ICRS coordinates:
        #   Row 0 = boresight (into sky)
        #   Row 1 = "left" in stored image
        #   Row 2 = "up" in stored image
        #
        # tetra3 extracts angles as:
        #   RA   = atan2(R[0,1], R[0,0])
        #   Dec  = atan2(R[0,2], norm(R[1:3, 2]))
        #   Roll = atan2(R[1,2], R[2,2])

        ra_rad  = np.radians(float(ra_deg))
        dec_rad = np.radians(float(dec_deg))
        roll_rad = np.radians(float(roll_deg))

        cos_ra, sin_ra   = np.cos(ra_rad),  np.sin(ra_rad)
        cos_dec, sin_dec = np.cos(dec_rad), np.sin(dec_rad)
        cos_roll, sin_roll = np.cos(roll_rad), np.sin(roll_rad)

        # Boresight in ICRS (Row 0)
        b = np.array([cos_dec * cos_ra, cos_dec * sin_ra, sin_dec])

        # "East" direction (perpendicular to boresight in equatorial plane)
        E = np.array([-sin_ra, cos_ra, 0.0])

        # "North" direction (toward NCP, projected perpendicular to boresight)
        N = np.array([-sin_dec * cos_ra, -sin_dec * sin_ra, cos_dec])

        # Apply roll rotation around boresight
        # Row 1 (camera "left") = cos(roll)*E + sin(roll)*N
        # Row 2 (camera "up")   = -sin(roll)*E + cos(roll)*N
        row1 = cos_roll * E + sin_roll * N
        row2 = -sin_roll * E + cos_roll * N

        # Full rotation matrix: ICRS → camera
        R = np.array([b, row1, row2])

        print(f"Python: 1-Shot: RA={ra_deg:.4f}°, Dec={dec_deg:.4f}°, Roll={roll_deg:.4f}°")
        print(f"Python: 1-Shot: R[0]={b}")

        # --- 3. Map zenith from Android sensor frame to tetra3 camera frame ---
        # Android accelerometer already outputs the ZENITH direction (reaction
        # force = upward). No negation needed.
        #
        # The sensor pipeline normalizes and applies calibration+pitch offset,
        # so (gx, gy, gz) is a unit vector pointing toward zenith in sensor coords:
        #   Sensor X = right, Y = up (top of phone), Z = out of screen
        #
        # tetra3's camera frame axes (derived from _compute_vectors in tetra3.py):
        #   Axis 0 = boresight (into sky)
        #   Axis 1 = "left" in solved image
        #   Axis 2 = "up" in solved image
        #
        # image_processor rotates the stored landscape pixels 90° CW (per the
        # EXIF orientation tag) into the PORTRAIT frame before solving, so the
        # solved image's axes map to phone axes as:
        #   Boresight (axis 0) = -Z_sensor  (camera looks through back of phone)
        #   Left in solved image (axis 1) = -X_sensor  (toward left of phone)
        #   Up in solved image (axis 2)   = +Y_sensor  (toward top of phone)
        #
        # Hence v_cam = [-gz, -gx, gy]. (The pre-rotation landscape mapping was
        # [-gz, gy, gx]; using it with portrait solves shifts roll by 90° and
        # throws fixes off by thousands of km. Validated synthetically:
        # portrait mapping recovers a known observer position exactly.)

        print(f"Python: 1-Shot: sensor g=({gx:.4f}, {gy:.4f}, {gz:.4f})")

        v_cam = np.array([-float(gz), -float(gx), float(gy)])

        # Normalize
        v_norm = np_norm(v_cam)
        if v_norm < 1e-6:
            return json.dumps({"error": "Gravity vector is zero"})
        v_cam = v_cam / v_norm

        print(f"Python: 1-Shot: zenith in camera frame = {v_cam}")

        # --- 4. Transform zenith from camera frame to ICRS ---
        # R maps ICRS → camera, so R^T maps camera → ICRS
        zenith_icrs = R.T @ v_cam

        print(f"Python: 1-Shot: zenith in ICRS = {zenith_icrs}")

        # --- 5. Extract latitude and longitude ---
        # Zenith declination = observer's latitude
        lat_rad = np.arcsin(np.clip(zenith_icrs[2], -1.0, 1.0))
        lat_deg_val = np.degrees(lat_rad)

        # Zenith RA in ICRS
        lst_rad = np.arctan2(zenith_icrs[1], zenith_icrs[0])
        lst_deg = np.degrees(lst_rad) % 360

        # Longitude = RA_icrs − ERA
        lon_deg_val = (lst_deg - era_deg + 180) % 360 - 180

        result = {
            "fixed_latitude": float(lat_deg_val),
            "fixed_longitude": float(lon_deg_val),
            "final_shift_nm": 0.0
        }
        print(f"Python: 1-Shot Fix: Lat={lat_deg_val:.4f}°, Lon={lon_deg_val:.4f}°")
        print(f"Python: 1-Shot Fix: LST_icrs={lst_deg:.4f}°, ERA={era_deg:.4f}°")
        return json.dumps(result)

    except Exception as e:
        import traceback
        error_msg = f"Error in solve_oneshot: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})

def _solve_obs_group(obs_group, label):
    """
    Solves each observation of a group independently via solve_oneshot.
    Returns (lats, lons) of the successful fixes; failures are logged.
    """
    lats = []
    lons = []
    for i, obs in enumerate(obs_group):
        result_json = solve_oneshot(
            obs['ra'], obs['dec'], obs['roll'],
            obs['gx'], obs['gy'], obs['gz'],
            obs['time_iso'],
            ""  # image_path not needed for solve
        )
        result = json.loads(result_json)

        if 'error' not in result or result.get('error') is None:
            lats.append(result['fixed_latitude'])
            lons.append(result['fixed_longitude'])
            print(f"Python: {label} fix {i+1}/{len(obs_group)}: Lat={result['fixed_latitude']:.4f}, Lon={result['fixed_longitude']:.4f}")
        else:
            print(f"Python: {label} fix {i+1}/{len(obs_group)} FAILED: {result['error']}")
    return lats, lons


def _median_fix(lats, lons):
    """
    Component-wise median of fixes (robust to outliers) plus a MAD-based
    spread estimate converted to nautical miles.
    Returns (median_lat, median_lon, spread_nm). For a single fix the
    median is the fix itself and the spread is 0.
    """
    median_lat = float(np.median(lats))
    median_lon = float(np.median(lons))
    lat_mad = float(np.median(np.abs(np.array(lats) - median_lat))) * 60.0  # deg to NM
    lon_mad = float(np.median(np.abs(np.array(lons) - median_lon))) * 60.0 * abs(np.cos(np.radians(median_lat)))
    spread_nm = float(np.sqrt(lat_mad**2 + lon_mad**2))
    return median_lat, median_lon, spread_nm


def solve_oneshot_multi(obs_list_json):
    """
    Multi-shot 1-Shot solver: solves each observation independently
    and returns the component-wise median position.

    This is dramatically more accurate than a single fix because the median
    is robust to outliers (bad plate-solve roll, sensor glitch, etc.).

    obs_list_json: JSON string of list of dicts:
       [{'ra':, 'dec':, 'roll':, 'gx':, 'gy':, 'gz':, 'time_iso':}, ...]

    Returns JSON with: fixed_latitude, fixed_longitude, error_estimate_nm, num_fixes
    On error: returns JSON with 'error' key.
    """
    try:
        obs_list = json.loads(obs_list_json)

        lats, lons = _solve_obs_group(obs_list, "Multi-shot")

        if len(lats) == 0:
            return json.dumps({"error": "No valid fixes from any observation"})

        median_lat, median_lon, spread_nm = _median_fix(lats, lons)

        print(f"Python: Multi-shot MEDIAN from {len(lats)} fixes: Lat={median_lat:.4f}, Lon={median_lon:.4f}, spread={spread_nm:.2f} NM")

        return json.dumps({
            "fixed_latitude": median_lat,
            "fixed_longitude": median_lon,
            "error_estimate_nm": spread_nm,
            "final_shift_nm": spread_nm,
            "num_fixes": len(lats),
            "individual_fixes": [{"lat": la, "lon": lo} for la, lo in zip(lats, lons)]
        })

    except Exception as e:
        error_msg = f"Error in solve_oneshot_multi: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})


def solve_oneshot_burst_multi(burst_groups_json):
    """
    Two-level median solver for burst capture mode.

    Level 1 (intra-burst): For each burst group (typically 7 images captured
    in ~1.5s), solve each observation independently and take the component-wise
    median to produce one noise-reduced fix per slot.

    Level 2 (inter-slot): Median across all slots' burst-median fixes to
    produce the final position.

    burst_groups_json: JSON string of array of arrays:
       [[{obs1}, {obs2}, ...], [{obs1}, {obs2}, ...], ...]
       Each inner array is a burst group. Each obs has:
       {'ra':, 'dec':, 'roll':, 'gx':, 'gy':, 'gz':, 'time_iso':}

    Returns JSON with: fixed_latitude, fixed_longitude, error_estimate_nm,
                        num_slots, burst_details
    """
    try:
        burst_groups = json.loads(burst_groups_json)

        slot_lats = []
        slot_lons = []
        burst_details = []

        for slot_idx, burst_group in enumerate(burst_groups):
            # Level 1: Solve each observation in this burst independently
            lats, lons = _solve_obs_group(burst_group, f"Burst slot {slot_idx+1}")

            if len(lats) == 0:
                print(f"Python: Burst slot {slot_idx+1}: No valid fixes from any burst image")
                burst_details.append({"slot": slot_idx + 1, "valid_fixes": 0, "error": "No valid fixes"})
                continue

            # Intra-burst median, with spread for diagnostics
            burst_median_lat, burst_median_lon, burst_spread = _median_fix(lats, lons)

            slot_lats.append(burst_median_lat)
            slot_lons.append(burst_median_lon)

            print(f"Python: Burst slot {slot_idx+1}: median from {len(lats)} fixes: "
                  f"Lat={burst_median_lat:.4f}, Lon={burst_median_lon:.4f}, spread={burst_spread:.2f} NM")

            burst_details.append({
                "slot": slot_idx + 1,
                "valid_fixes": len(lats),
                "median_lat": burst_median_lat,
                "median_lon": burst_median_lon,
                "intra_burst_spread_nm": burst_spread
            })

        if len(slot_lats) == 0:
            return json.dumps({"error": "No valid fixes from any burst slot"})

        # Level 2: Inter-slot median
        final_lat, final_lon, total_spread = _median_fix(slot_lats, slot_lons)

        print(f"Python: Burst-multi FINAL from {len(slot_lats)} slots: "
              f"Lat={final_lat:.4f}, Lon={final_lon:.4f}, spread={total_spread:.2f} NM")

        return json.dumps({
            "fixed_latitude": final_lat,
            "fixed_longitude": final_lon,
            "error_estimate_nm": total_spread,
            "final_shift_nm": total_spread,
            "num_slots": len(slot_lats),
            "burst_details": burst_details,
            "individual_fixes": [{"lat": la, "lon": lo} for la, lo in zip(slot_lats, slot_lons)]
        })

    except Exception as e:
        error_msg = f"Error in solve_oneshot_burst_multi: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})


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
            return json.dumps({"error": "Need at least 3 observations for iterative solve"})

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


def solve_calibration_offsets(ra_deg, dec_deg, roll_deg, gx, gy, gz, lat_deg, lon_deg, time_iso):
    """
    Computes pitch_offset and roll_offset (in degrees) that align the measured gravity
    vector (gx, gy, gz) in the sensor frame with the expected gravity vector computed
    from the true latitude/longitude, time, and image orientation.
    """
    try:
        import numpy as np
        from astropy.time import Time

        # 1. Expected Zenith in ICRS
        t = Time(time_iso)
        era_deg = t.earth_rotation_angle('tio').deg
        lst_deg = (lon_deg + era_deg) % 360

        lat_rad = np.radians(lat_deg)
        lst_rad = np.radians(lst_deg)
        zenith_icrs = np.array([
            np.cos(lat_rad) * np.cos(lst_rad),
            np.cos(lat_rad) * np.sin(lst_rad),
            np.sin(lat_rad)
        ])

        # 2. Camera rotation matrix R (ICRS -> camera)
        ra_rad = np.radians(float(ra_deg))
        dec_rad = np.radians(float(dec_deg))
        roll_rad = np.radians(float(roll_deg))

        cos_ra, sin_ra = np.cos(ra_rad), np.sin(ra_rad)
        cos_dec, sin_dec = np.cos(dec_rad), np.sin(dec_rad)
        cos_roll, sin_roll = np.cos(roll_rad), np.sin(roll_rad)

        b = np.array([cos_dec * cos_ra, cos_dec * sin_ra, sin_dec])
        E = np.array([-sin_ra, cos_ra, 0.0])
        N = np.array([-sin_dec * cos_ra, -sin_dec * sin_ra, cos_dec])

        row1 = cos_roll * E + sin_roll * N
        row2 = -sin_roll * E + cos_roll * N
        R = np.array([b, row1, row2])

        # 3. Expected Zenith in camera frame
        v_cam_exp = R @ zenith_icrs

        # 4. Expected gravity in sensor frame
        # v_cam = [-gz, -gx, gy] (portrait solve frame; must match solve_oneshot)
        # So expected gravity in sensor:
        gx_exp = -v_cam_exp[1]
        gy_exp = v_cam_exp[2]
        gz_exp = -v_cam_exp[0]
        v_exp = np.array([gx_exp, gy_exp, gz_exp])

        # Normalize expected vector
        v_exp_norm = np.linalg.norm(v_exp)
        if v_exp_norm > 1e-6:
            v_exp = v_exp / v_exp_norm

        # Measured gravity in sensor frame
        v_meas = np.array([float(gx), float(gy), float(gz)])
        v_meas_norm = np.linalg.norm(v_meas)
        if v_meas_norm > 1e-6:
            v_meas = v_meas / v_meas_norm

        # 5. Solve for roll_offset and pitch_offset analytically
        xm, ym, zm = v_meas
        xe, ye, ze = v_exp

        r_xy = np.sqrt(xm**2 + ym**2)
        phi = np.arctan2(ym, xm)

        candidates = []
        if r_xy > 1e-6:
            val = np.clip(xe / r_xy, -1.0, 1.0)
            alpha = np.arccos(val)
            for sign in [-1.0, 1.0]:
                theta_r = phi + sign * alpha

                # Compute y'
                yp = -xm * np.sin(theta_r) + ym * np.cos(theta_r)

                # Solve for theta_p
                denom = yp**2 + zm**2
                if denom > 1e-6:
                    cos_p = (yp * ye + zm * ze) / denom
                    sin_p = (-zm * ye + yp * ze) / denom
                    theta_p = np.arctan2(sin_p, cos_p)

                    # Verify
                    # Z-rotation:
                    xr = xm * np.cos(theta_r) + ym * np.sin(theta_r)
                    yr = -xm * np.sin(theta_r) + ym * np.cos(theta_r)
                    zr = zm
                    # X-rotation:
                    x_final = xr
                    y_final = yr * np.cos(theta_p) - zr * np.sin(theta_p)
                    z_final = yr * np.sin(theta_p) + zr * np.cos(theta_p)

                    v_final = np.array([x_final, y_final, z_final])
                    err = np.linalg.norm(v_final - v_exp)
                    candidates.append((err, np.degrees(theta_p), np.degrees(theta_r)))

        if not candidates:
            return json.dumps({"error": "Failed to solve calibration rotation"})

        # Pick candidate with smallest error
        candidates.sort()
        best_err, best_pitch_deg, best_roll_deg = candidates[0]

        if best_err > 0.15:
            return json.dumps({"error": f"Calibration solution has high error: {best_err:.4f}"})

        return json.dumps({
            "pitch_offset_deg": float(best_pitch_deg),
            "roll_offset_deg": float(best_roll_deg),
            "error": None
        })

    except Exception as e:
        import traceback
        error_msg = f"Error in solve_calibration_offsets: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})


# =============================================================================
# SECTION 5: SENSOR WAVE MODELING
# =============================================================================

def build_wave_model(json_data_str):
    """
    Analyzes 30s of gravity vectors to separate the true gravity (DC)
    from the wave motion (AC).
    Input: JSON string of list of [x, y, z] lists.
    Returns: JSON string with true gravity and wave stats.
    """
    try:
        import json
        import numpy as np
        
        data = json.loads(json_data_str)
        if not data or len(data) < 10:
            return json.dumps({"success": False, "error": "Insufficient data"})
            
        arr = np.array(data) # shape (N, 3)
        
        # 1. True gravity is the arithmetic mean
        true_g = np.mean(arr, axis=0)
        norm = np.linalg.norm(true_g)
        if norm > 0:
            true_g = true_g / norm
            
        # 2. Extract Wave Properties via residuals
        # Analyze the angle variation from the mean true gravity vector.
        dots = np.dot(arr, true_g)
        # Normalize the arr rows to avoid domain errors in arccos if vectors aren't perfectly unit length
        norms = np.linalg.norm(arr, axis=1)
        dots = dots / (norms + 1e-9)
        dots = np.clip(dots, -1.0, 1.0)
        angles = np.arccos(dots) # angle in radians
        
        mean_angle = np.mean(angles)
        residuals = angles - mean_angle
        
        # Max amplitude in degrees
        max_amplitude_deg = float(np.max(np.abs(residuals)) * (180.0 / np.pi))
        
        # FFT to find dominant period
        # Assume sample rate is roughly constant. N samples over 30 seconds.
        N = len(arr)
        dt = 30.0 / N
        
        fft_vals = np.fft.rfft(residuals)
        fft_mag = np.abs(fft_vals)
        freqs = np.fft.rfftfreq(N, d=dt)
        
        # Find peak frequency (ignore DC at index 0 and ultra-low frequencies)
        fft_mag[freqs < 0.05] = 0 # Ignore periods > 20s as likely drift, not ocean waves
        if np.max(fft_mag) > 0:
            peak_idx = np.argmax(fft_mag)
            peak_freq = freqs[peak_idx]
            period = 1.0 / peak_freq if peak_freq > 0 else 0.0
        else:
            period = 0.0
            
        result = {
            "success": True,
            "true_gravity": [float(true_g[0]), float(true_g[1]), float(true_g[2])],
            "wave_amplitude_deg": round(max_amplitude_deg, 2),
            "wave_period_sec": round(float(period), 1)
        }
        return json.dumps(result)
        
    except Exception as e:
        import traceback
        return json.dumps({"success": False, "error": str(e), "trace": traceback.format_exc()})

