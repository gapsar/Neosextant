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
    import erfa  # ships with astropy (pyerfa); IAU SOFA routines
    from astropy.time import Time
    from astropy.coordinates import SkyCoord, EarthLocation, AltAz
except ImportError as e:
    # This will help diagnose missing libraries if the script fails to load.
    raise ImportError(f"A required library is missing. Please ensure all dependencies are installed. Error: {e}")


# =============================================================================
# SECTION 1: ASTROPY IERS CONFIGURATION
# =============================================================================

from astropy.utils import iers
import warnings

IERS_STATUS = "bundled"   # bundled | cached | downloaded  (diagnostics only)


def _configure_iers():
    """
    Put astropy's Earth-rotation handling into a state that CANNOT raise, and do
    it synchronously at import, before anything can trigger a time conversion.

    The previous version did this on a daemon thread, which left two holes:

      1. A race. Until the thread ran, iers.conf kept its defaults —
         auto_download=True, auto_max_age=30 days. A solve starting in that
         window, with a stale table and no usable network, raises
             ValueError: interpolating from IERS_Auto using predictive values
             that are more than 30.0 days old
         and the fix fails outright rather than being slightly less accurate.
      2. The online branch set auto_download=True but left auto_max_age at 30
         days, so the same exception was reachable whenever the reachability
         probe succeeded but the actual table was stale or the download failed.
         A captive portal (marina/harbour wifi) answers any request with a 200,
         so the probe reported "online" exactly where it was least true.

    auto_download stays False permanently: no solve should ever block on the
    network. Fresh data is installed explicitly by _refresh_iers_background().
    """
    warnings.filterwarnings('ignore', module='erfa')  # future-year ERFA warnings
    iers.conf.auto_download = False
    iers.conf.auto_max_age = None
    iers.conf.iers_degraded_accuracy = 'ignore'


# Synchronous, at import: no window in which a solve can hit the defaults.
_configure_iers()


def _table_covers_now(table, margin_days=30.0):
    """True if the table still has data margin_days beyond today."""
    try:
        from astropy.time import Time
        mjd = table['MJD']
        last = float(getattr(mjd, 'value', mjd)[-1])
        return (last - Time.now().mjd) > margin_days
    except Exception:
        return False


def _refresh_iers_background():
    """
    Best effort: install the most current IERS-A table we can get, without ever
    putting the config back into a state that can raise.

    cache=True means a table fetched once ashore is reused with NO network at
    all, so a single sync before leaving keeps working at sea. astropy's own
    bundled IERS-B ends 2022-08-30, so without this the app extrapolates UT1-UTC
    from a four-year-old table; that is only ~0.1-0.2 nmi of longitude, but it
    is free to remove when a network is available.
    """
    global IERS_STATUS
    for url in (iers.IERS_A_URL, iers.IERS_A_URL_MIRROR):
        try:
            table = iers.IERS_A.open(url, cache=True)
            iers.earth_orientation_table.set(table)
            IERS_STATUS = "cached"
            print(f"Python: IERS-A table installed from cache/{url}")
            # A cached table is never re-downloaded, and IERS-A only predicts
            # about a year ahead, so force one refresh when it runs short.
            if not _table_covers_now(table):
                try:
                    fresh = iers.IERS_A.open(url, cache="update")
                    iers.earth_orientation_table.set(fresh)
                    IERS_STATUS = "downloaded"
                    print("Python: IERS-A table refreshed over the network")
                except Exception as e_up:
                    print(f"Python: IERS-A refresh failed ({e_up}); keeping cached table")
            return
        except Exception as e:
            print(f"Python: IERS-A unavailable from {url}: {e}")
    print("Python: using astropy's bundled IERS-B (ends 2022-08-30); UT1-UTC "
          "extrapolated, ~0.1-0.2 nmi of longitude")


threading.Thread(target=_refresh_iers_background, daemon=True).start()

# =============================================================================
# SECTION 2: IMAGE SOLVING (ASTROMETRY)
# =============================================================================

T3_INSTANCE = None
INITIALIZATION_ERROR = None

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

    Returns (img, applied). `applied` must be used rather than comparing image
    dimensions: orientations 2, 3 and 4 (mirror, 180 deg, flip) transpose the
    pixels while leaving width and height unchanged, so a size comparison misses
    them and cedar_cli would then read pixels in a different frame from the one
    declared to the solver.
    """
    try:
        orientation = img.getexif().get(0x0112, 1)
    except Exception:
        orientation = 1
    method = _ORIENTATION_TRANSPOSES.get(orientation)
    if method is None:
        return img, False
    img = img.transpose(method)
    img.info.pop('exif', None)
    return img, True

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
            img, reoriented = _apply_exif_orientation(img)
            if not reoriented and img.width > img.height:
                print(f"Python: No EXIF orientation; image is landscape ({img.width}x{img.height}), "
                      f"rotating 90 deg to portrait.")
                img = img.transpose(Image.ROTATE_90)
                reoriented = True

            if reoriented:
                print(f"Python: Reoriented image to {img.width}x{img.height}.")
                # The pixels cedar_cli reads must match the transpose we just applied
                # in-memory, otherwise centroids end up in a different frame than the
                # (orig_height, orig_width) we declare to the solver below. Persist the
                # reoriented image and point the CLI at that instead of the raw file.
                rotated_cedar_input_path = image_path + ".rotated.jpg"
                img.save(rotated_cedar_input_path, quality=95)

            orig_width, orig_height = img.width, img.height
        print(f"Python: Image loaded successfully ({orig_width}x{orig_height}).")

        centroids = []

        cedar_input_path = rotated_cedar_input_path or image_path

        # The grayscale numpy array below is ONLY consumed by the tetra3 fallback,
        # which runs when cedar_cli fails — i.e. almost never. Building it eagerly
        # cost a LANCZOS downscale plus a full-resolution convert() on every frame,
        # seven times per burst, for a result that was then thrown away.
        # Deferred behind a closure so the cost is paid only when it is used.
        MAX_DIM = 4000   # H-12: cap resolution to avoid excessive memory use
        _fallback_cache = {}

        def _fallback_centroids():
            """Grayscale + downscale on demand, returning centroids in ORIGINAL
            full-resolution coordinates. Reads cedar_input_path so it sees the
            same pixels, in the same orientation, that cedar_cli was given."""
            if "np" not in _fallback_cache:
                with Image.open(cedar_input_path) as fb:
                    ratio = 1.0
                    if fb.width > MAX_DIM or fb.height > MAX_DIM:
                        ratio = min(MAX_DIM / fb.width, MAX_DIM / fb.height)
                        new_size = (int(fb.width * ratio), int(fb.height * ratio))
                        print(f"Python: Downscaling image from {fb.width}x{fb.height} "
                              f"to {new_size[0]}x{new_size[1]}")
                        fb = fb.resize(new_size, Image.LANCZOS)
                    _fallback_cache["ratio"] = ratio
                    _fallback_cache["np"] = np.asarray(fb.convert(mode='L'), dtype=np.uint8)
            ratio = _fallback_cache["ratio"]
            cents = tetra3.get_centroids_from_image(_fallback_cache["np"])
            if ratio != 1.0:
                cents = [(c[0] / ratio, c[1] / ratio) for c in cents]
            return cents

        print(f"Python: Extracting centroids using Cedar Detect CLI on {cedar_input_path}...")
        cedar_result = detect_centroids_cli(cedar_input_path)

        if "error" in cedar_result:
            print(f"Python: Cedar Detect CLI error: {cedar_result['error']}")
            if "output" in cedar_result:
                print(f"Python: CLI output: {cedar_result['output']}")
            print("Python: Falling back to default Tetra3 extraction...")
            centroids = _fallback_centroids()
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
                centroids = _fallback_centroids()

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
                print("Python: self-calibration pass scheduled (runs once the solver is idle).")
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
            
        # Camera2 LENS_DISTORTION pre-correction was tried and gated off: the
        # frozen k1/k2 fit below (Step 0b) superseded it and the two together
        # double-corrected. The metadata is still parsed above because the
        # intrinsics feed the principal point.
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


# SECTION 3: POSITION FIX CALCULATION
# =============================================================================


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

        # Residual-based uncertainty from the converged fit. final_shift_nm is a
        # CONVERGENCE metric — it tends to 0 as the solver settles no matter how
        # wrong the answer is — so it must never be shown as an error bar.
        uncertainty_nm = _lsq_position_uncertainty(azimuths, intercepts)

        return json.dumps({
            "fixed_latitude": float(current_lat),
            "fixed_longitude": float(current_lon),
            "iterations": iterations_done,
            "final_shift_nm": final_shift,
            "error_estimate_nm": uncertainty_nm,
            "repeatability_nm": uncertainty_nm,
            "repeatability_basis": "%d sights, least-squares residuals" % len(obs_list)
        })

    except Exception as e:
        error_msg = f"Error in solve_lop_iterative: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})




# Refraction model inputs shared by the 1-Shot path. Relative humidity and
# wavelength feed erfa.refco; 0.55 µm is the visual band the camera actually
# responds to. (astropy's AltAz defaults to 1 µm, which under-refracts by ~1.5%
# — 0.014' at 45°, far below this path's error floor, so the two paths agree
# to well inside their own accuracy.)
REFRACTION_RH = 0.0
REFRACTION_WAVELENGTH_UM = 0.55
# Below this altitude the SOFA refraction model degrades quickly and the plate
# solve is unreliable anyway; leave the vector uncorrected rather than apply a
# wrong correction.
MIN_REFRACTION_ALT_DEG = 5.0


def _refract_zenith_in_camera(v_cam, pressure_hpa, temperature_c, sign, label=""):
    """
    Rotate the zenith vector in tetra3's camera frame by the refraction at the
    boresight, in the plane the zenith and boresight span.

    sign=+1  observed -> catalog  (z_true = z_obs + R): what solve_oneshot needs,
             because tetra3 reports the CATALOG direction of the object at frame
             centre while the accelerometer measured the zenith relative to where
             the camera PHYSICALLY pointed.
    sign=-1  catalog -> observed: what the calibration needs, to predict what the
             sensor should actually read.

    Both directions live here on purpose. Applying refraction on only one side of
    the calibrate/solve round trip silently biases every fix by R(alt) — roughly
    1 nmi at 45 deg — and that is exactly how the frame bug above went unnoticed
    for so long: two errors that cancelled while both paths were wrong.
    """
    import numpy as np
    boresight = np.array([1.0, 0.0, 0.0])
    cos_zb = float(np.clip(float(np.dot(v_cam, boresight)), -1.0, 1.0))
    z_rad = np.arccos(cos_zb)
    alt_deg = 90.0 - np.degrees(z_rad)
    perp = v_cam - cos_zb * boresight
    perp_norm = float(np.linalg.norm(perp))

    if alt_deg <= MIN_REFRACTION_ALT_DEG:
        print(f"Python: {label}boresight altitude {alt_deg:.2f}° too low "
              f"(<{MIN_REFRACTION_ALT_DEG}°); skipping refraction correction")
        return v_cam
    if perp_norm < 1e-9:
        print(f"Python: {label}boresight at zenith; no refraction correction")
        return v_cam

    refa, refb = erfa.refco(float(pressure_hpa), float(temperature_c),
                            REFRACTION_RH, REFRACTION_WAVELENGTH_UM)
    tan_z = np.tan(z_rad)
    dz_rad = refa * tan_z + refb * tan_z ** 3
    z_new = z_rad + sign * dz_rad
    out = np.cos(z_new) * boresight + np.sin(z_new) * (perp / perp_norm)
    print(f"Python: {label}refraction at alt={alt_deg:.2f}° "
          f"(P={pressure_hpa:.1f} hPa, T={temperature_c:.1f}°C) = "
          f"{np.degrees(dz_rad) * 60:.3f}' (sign {sign:+d})")
    return out


def _camera_rotation(ra_deg, dec_deg, roll_deg):
    """tetra3's rotation matrix R (ICRS -> camera) rebuilt from RA/Dec/Roll.

    Rows of R are the camera axes expressed in ICRS:
      Row 0 = boresight (into sky)
      Row 1 = "left" in the solved image = cos(roll)*E + sin(roll)*N
      Row 2 = "up" in the solved image   = -sin(roll)*E + cos(roll)*N
    where E is east in the equatorial plane and N points toward the NCP,
    both perpendicular to the boresight. tetra3 extracts its angles as
      RA   = atan2(R[0,1], R[0,0])
      Dec  = atan2(R[0,2], norm(R[1:3, 2]))
      Roll = atan2(R[1,2], R[2,2])
    """
    ra_rad = np.radians(float(ra_deg))
    dec_rad = np.radians(float(dec_deg))
    roll_rad = np.radians(float(roll_deg))

    cos_ra, sin_ra = np.cos(ra_rad), np.sin(ra_rad)
    cos_dec, sin_dec = np.cos(dec_rad), np.sin(dec_rad)
    cos_roll, sin_roll = np.cos(roll_rad), np.sin(roll_rad)

    b = np.array([cos_dec * cos_ra, cos_dec * sin_ra, sin_dec])
    E = np.array([-sin_ra, cos_ra, 0.0])
    N = np.array([-sin_dec * cos_ra, -sin_dec * sin_ra, cos_dec])

    return np.array([b, cos_roll * E + sin_roll * N, -sin_roll * E + cos_roll * N])


# --- 1-SHOT FIX CALCULATION ---
def solve_oneshot(ra_deg, dec_deg, roll_deg, gx, gy, gz, time_iso, image_path,
                  pressure_hpa=1013.25, temperature_c=15.0):
    """
    Computes a 1-Shot geographic fix from a single image using the
    3D Gravity Vector (zenith direction) and the plate-solve orientation.

    Algorithm:
    1. Reconstruct tetra3's rotation matrix R (ICRS → camera) from RA/Dec/Roll.
    2. Map the zenith vector from Android sensor frame to tetra3's camera frame.
    3. Correct the zenith for atmospheric refraction at the boresight.
    4. Transform zenith from camera frame to ICRS, then rotate into CIRS.
    5. Extract latitude = arcsin(z_cirs), LST = atan2(y_cirs, x_cirs).
    6. Longitude = LST − ERA.
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

        # Celestial-to-intermediate rotation (ICRS/GCRS -> CIRS) for this epoch,
        # IAU 2006 precession + IAU 2000A nutation. Needed in step 4 below; see
        # the long comment there for why. pyerfa ships inside astropy, so this
        # adds no new dependency.
        _tt = t.tt
        bpn_icrs_to_cirs = erfa.c2i06a(_tt.jd1, _tt.jd2)

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

        R = _camera_rotation(ra_deg, dec_deg, roll_deg)

        print(f"Python: 1-Shot: RA={ra_deg:.4f}°, Dec={dec_deg:.4f}°, Roll={roll_deg:.4f}°")
        print(f"Python: 1-Shot: R[0]={R[0]}")

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

        # --- 3b. Atmospheric refraction ---
        # Refraction lifts stars TOWARD the zenith, so the object imaged at the
        # frame centre sits at apparent altitude alt_obs while its CATALOG
        # altitude is alt_obs − R. tetra3 matched against the catalog, so R's
        # boresight row is the catalog direction — but the accelerometer measured
        # the zenith relative to where the camera PHYSICALLY pointed, i.e. the
        # apparent direction. The two differ by exactly R, so the reconstructed
        # zenith ends up R too close to the boresight.
        #
        # Correcting is a rotation of the zenith AWAY from the boresight by R,
        # in the plane the two span (boresight is axis 0 of the camera frame):
        #     z_true = z_obs + R
        # R comes from erfa.refco, the same SOFA model astropy's AltAz uses for
        # the LOP/iterative paths, evaluated at the OBSERVED zenith distance —
        # verified against astropy to 1e-4 arcmin.
        #
        # This is one-sided (always the same sense) and identical across a
        # burst, so — unlike random sensor noise — the two-level median cannot
        # reduce it. Magnitude: 0.55' at 60° altitude, 0.96' at 45°, 1.7' at 30°,
        # i.e. 0.5–1.7 nmi of position.
        #
        # First order only: this applies the refraction at the frame CENTRE.
        # Across a ~46° field refraction also varies star-to-star (0.4' at the
        # top vs 2.4' at the bottom for a 45° boresight), which distorts the
        # pattern tetra3 fits. Removing that needs the centroids de-refracted
        # before solving, using the zenith direction in the image.
        v_cam = _refract_zenith_in_camera(v_cam, pressure_hpa, temperature_c,
                                          sign=+1, label="1-Shot: ")

        # --- 4. Transform zenith from camera frame to ICRS, then to CIRS ---
        # R maps ICRS → camera, so R^T maps camera → ICRS
        zenith_icrs = R.T @ v_cam

        # ERA is the rotation angle of the Earth about the CELESTIAL INTERMEDIATE
        # POLE, measured from the CIO — i.e. it is an angle in the CIRS frame, not
        # in ICRS. Switching GST→ERA (commit d33c9df) removed the precession-in-RA
        # term because the CIO is constructed to have no rotation about the pole
        # relative to ICRS. But the POLE itself still moves: precession/nutation
        # carry the CIP ~20"/yr away from the ICRS z-axis, so by 2026 the two
        # frames differ by a ~0.15° rotation about an equatorial axis.
        #
        # Reading latitude off the ICRS z-axis and longitude off the ICRS x-axis
        # therefore mixes that 0.15° straight into the fix. Measured against a
        # synthetic perfect plate solve + perfect accelerometer, the residual was
        # 4–9 nmi depending on observer position and date (worst 8.95 nmi), fully
        # systematic — a burst median cannot remove it.
        #
        # Rotating the zenith into CIRS before extracting lat/lon is exact and
        # also handles roll for free: with v_cam = R_icrs·v_icrs = R_cirs·v_cirs
        # and v_cirs = M·v_icrs, we get R_cirs = R_icrs·Mᵀ and hence
        # zenith_cirs = R_cirsᵀ·v_cam = M·(R_icrsᵀ·v_cam) = M·zenith_icrs.
        # No change to R is needed. Same synthetic test after the fix: 0.0000 nmi.
        #
        # Not corrected here: annual aberration (≤20.5" ≈ 0.34 nmi), which
        # displaces the apparent star field and so leaks into the solved
        # boresight. That is well under the current error floor.
        zenith_cirs = bpn_icrs_to_cirs @ zenith_icrs

        print(f"Python: 1-Shot: zenith in ICRS = {zenith_icrs}")
        print(f"Python: 1-Shot: zenith in CIRS = {zenith_cirs}")

        # --- 5. Extract latitude and longitude ---
        # Zenith declination (CIRS) = observer's latitude
        lat_rad = np.arcsin(np.clip(zenith_cirs[2], -1.0, 1.0))
        lat_deg_val = np.degrees(lat_rad)

        # Zenith RA in CIRS — the frame ERA is measured in
        lst_rad = np.arctan2(zenith_cirs[1], zenith_cirs[0])
        lst_deg = np.degrees(lst_rad) % 360

        # Longitude = RA_cirs − ERA
        lon_deg_val = (lst_deg - era_deg + 180) % 360 - 180

        result = {
            "fixed_latitude": float(lat_deg_val),
            "fixed_longitude": float(lon_deg_val),
            "final_shift_nm": 0.0
        }
        print(f"Python: 1-Shot Fix: Lat={lat_deg_val:.4f}°, Lon={lon_deg_val:.4f}°")
        print(f"Python: 1-Shot Fix: LST_cirs={lst_deg:.4f}°, ERA={era_deg:.4f}°")
        return json.dumps(result)

    except Exception as e:
        import traceback
        error_msg = f"Error in solve_oneshot: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})

def _solve_obs_group(obs_group, label, pressure_hpa=1013.25, temperature_c=15.0):
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
            "",  # image_path not needed for solve
            pressure_hpa, temperature_c
        )
        result = json.loads(result_json)

        if 'error' not in result or result.get('error') is None:
            lats.append(result['fixed_latitude'])
            lons.append(result['fixed_longitude'])
            print(f"Python: {label} fix {i+1}/{len(obs_group)}: Lat={result['fixed_latitude']:.4f}, Lon={result['fixed_longitude']:.4f}")
        else:
            print(f"Python: {label} fix {i+1}/{len(obs_group)} FAILED: {result['error']}")
    return lats, lons


# MAD -> sigma for normally distributed data. Without it the raw MAD
# understates the spread by a third.
MAD_TO_SIGMA = 1.4826
# Standard error of a MEDIAN is sqrt(pi/2) times that of a mean, for the same n.
MEDIAN_SE_FACTOR = 1.2533
# Fewer than this many fixes and a robust spread estimate is meaningless — a
# single fix has a MAD of exactly zero, which must never be reported as "0.00
# NM of error".
MIN_FIXES_FOR_SPREAD = 3


def _median_fix(lats, lons):
    """
    Component-wise median of fixes (robust to outliers) plus an estimate of how
    REPRODUCIBLE that median is, in nautical miles.

    Returns (median_lat, median_lon, repeatability_nm).

    repeatability_nm is the standard error of the median: how much the answer
    would move if the same shots were taken again. It is NOT the distance to
    the true position. Errors shared by every shot in a burst — accelerometer
    calibration, residual refraction, clock offset — are identical in all of
    them and therefore contribute exactly ZERO to this number. A burst can be
    beautifully repeatable and still be miles off.

    NaN when there are too few fixes to say anything, rather than 0.0.
    """
    median_lat = float(np.median(lats))
    median_lon = float(np.median(lons))
    n = len(lats)
    if n < MIN_FIXES_FOR_SPREAD:
        return median_lat, median_lon, float("nan")

    lat_sigma = float(np.median(np.abs(np.array(lats) - median_lat))) * 60.0 * MAD_TO_SIGMA
    lon_sigma = (float(np.median(np.abs(np.array(lons) - median_lon)))
                 * 60.0 * abs(np.cos(np.radians(median_lat))) * MAD_TO_SIGMA)
    sigma_nm = float(np.sqrt(lat_sigma ** 2 + lon_sigma ** 2))
    return median_lat, median_lon, MEDIAN_SE_FACTOR * sigma_nm / np.sqrt(n)


def solve_oneshot_burst_multi(burst_groups_json, pressure_hpa=1013.25,
                              temperature_c=15.0):
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
            lats, lons = _solve_obs_group(burst_group, f"Burst slot {slot_idx+1}",
                                          pressure_hpa, temperature_c)

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

        # With one or two slots there is no inter-slot spread to measure, but
        # the single slot's own burst still has ~7 fixes. Report that rather
        # than NaN — and never 0.00, which the old code produced for one slot.
        repeatability = total_spread
        basis = "%d slots" % len(slot_lats)
        if repeatability != repeatability:  # NaN
            intra = [d.get("intra_burst_spread_nm") for d in burst_details
                     if d.get("intra_burst_spread_nm") is not None]
            intra = [v for v in intra if v == v]
            if intra:
                repeatability = float(max(intra))
                if len(intra) == 1:
                    basis = "single burst of %d fixes" % max(
                        d.get("valid_fixes", 0) for d in burst_details)
                else:
                    # Two slots: no inter-slot spread to measure yet, so report
                    # the worse of the two bursts' own spreads.
                    basis = "worst of %d bursts" % len(intra)

        print(f"Python: Burst-multi FINAL from {len(slot_lats)} slots: "
              f"Lat={final_lat:.4f}, Lon={final_lon:.4f}, "
              f"repeatability={repeatability:.2f} NM ({basis})")

        return json.dumps({
            "fixed_latitude": final_lat,
            "fixed_longitude": final_lon,
            # Repeatability, NOT accuracy — see _median_fix. Systematic errors
            # common to every shot cancel out of this entirely.
            "repeatability_nm": repeatability,
            "repeatability_basis": basis,
            "error_estimate_nm": repeatability,
            "num_slots": len(slot_lats),
            "burst_details": burst_details,
            "individual_fixes": [{"lat": la, "lon": lo} for la, lo in zip(slot_lats, slot_lons)]
        })

    except Exception as e:
        error_msg = f"Error in solve_oneshot_burst_multi: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})


# --- Refactored Core Logic for Iteration ---

def _lsq_position_uncertainty(azimuths_deg, intercepts_nm):
    """
    1-sigma position uncertainty (DRMS, nautical miles) from the least-squares
    fit of the sights, or NaN if there is no redundancy.

    At convergence the LSQ shift is ~0, so the remaining intercepts ARE the
    misfits between the sights. With m sights and 2 unknowns:
        sigma^2 = SSR / (m - 2)
        cov     = sigma^2 * (A^T A)^-1
        DRMS    = sqrt(trace(cov))
    This captures both measurement scatter and the geometry (a tight cluster of
    azimuths gives a large uncertainty along the unconstrained direction, which
    the old convergence-shift number was completely blind to).

    Still not accuracy: a bias common to every sight is partly absorbed into the
    position rather than showing up in the residuals.
    """
    m = len(intercepts_nm)
    if m < 3:
        return float("nan")
    A = np.array([[np.sin(np.deg2rad(az)), np.cos(np.deg2rad(az))]
                  for az in azimuths_deg])
    r = np.asarray(intercepts_nm, dtype=float)
    try:
        ata_inv = np.linalg.inv(A.T @ A)
    except np.linalg.LinAlgError:
        return float("nan")      # degenerate geometry: all sights same bearing
    ssr = float(np.sum(r ** 2))
    sigma_sq = ssr / (m - 2)
    cov = sigma_sq * ata_inv
    trace = float(cov[0, 0] + cov[1, 1])
    return float(np.sqrt(trace)) if trace > 0 else float("nan")


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

        # Residual-based uncertainty from the converged fit. final_shift_nm is a
        # CONVERGENCE metric — it tends to 0 as the solver settles no matter how
        # wrong the answer is — so it must never be shown as an error bar.
        uncertainty_nm = _lsq_position_uncertainty(azimuths, intercepts)

        return json.dumps({
            "fixed_latitude": float(current_lat),
            "fixed_longitude": float(current_lon),
            "iterations": iterations_done,
            "final_shift_nm": final_shift,
            "error_estimate_nm": uncertainty_nm,
            "repeatability_nm": uncertainty_nm,
            "repeatability_basis": "%d sights, least-squares residuals" % len(obs_list)
        })

    except Exception as e:
        error_msg = f"Error in solve_iterative: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})


# Residuals within this of the best count as an equally good fit when choosing
# between the two branches of the pitch/roll solution (unit-vector chord length;
# exact solutions differ only by float rounding, ~1e-16).
CAL_BRANCH_TIE_TOL = 1e-6


def _wrap_deg(a):
    """Angle in degrees wrapped to [-180, 180)."""
    return (float(a) + 180.0) % 360.0 - 180.0


def solve_calibration_offsets(ra_deg, dec_deg, roll_deg, gx, gy, gz, lat_deg, lon_deg,
                              time_iso, pressure_hpa=1013.25, temperature_c=15.0):
    """
    Computes pitch_offset and roll_offset (in degrees) that align the measured gravity
    vector (gx, gy, gz) in the sensor frame with the expected gravity vector computed
    from the true latitude/longitude, time, and image orientation.
    """
    try:
        # Expected gravity in the sensor frame, refraction included: the one
        # geometry shared with the affine pair collector.
        v_exp = _expected_gravity_sensor(ra_deg, dec_deg, roll_deg, lat_deg,
                                         lon_deg, time_iso, pressure_hpa,
                                         temperature_c)

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
                    candidates.append((err, _wrap_deg(np.degrees(theta_p)),
                                       _wrap_deg(np.degrees(theta_r))))

        if not candidates:
            return json.dumps({"error": "Failed to solve calibration rotation"})

        # Both branches are usually EXACT solutions: next to the real offset there
        # is a mirror one with roll ~180 deg away (e.g. 0.3/-0.5 vs 116/180).
        # Their residuals are both ~1e-16, so ranking by residual alone picked
        # the mirror about half the time, and the +/-5 deg gate in the app then
        # threw away a perfectly good shot. Among the solutions that fit, take
        # the smallest rotation.
        best_err = min(c[0] for c in candidates)
        fitting = [c for c in candidates if c[0] <= best_err + CAL_BRANCH_TIE_TOL]
        best_err, best_pitch_deg, best_roll_deg = min(
            fitting, key=lambda c: abs(c[1]) + abs(c[2]))

        if best_err > 0.15:
            return json.dumps({"error": f"Calibration solution has high error: {best_err:.4f}"})

        # The real angle between the two vectors. Unlike the pitch/roll pair it
        # stays meaningful in every attitude: with the phone in landscape
        # (gravity along the sensor x axis) both rotations move gravity the same
        # way, so a 0.4 deg misalignment decomposes into ~6 deg of pitch AND
        # ~7 deg of roll. Gate false matches on this, not on the decomposition.
        misalignment_deg = float(np.degrees(np.arccos(
            np.clip(float(np.dot(v_meas, v_exp)), -1.0, 1.0))))

        return json.dumps({
            "pitch_offset_deg": float(best_pitch_deg),
            "roll_offset_deg": float(best_roll_deg),
            "misalignment_deg": misalignment_deg,
            "error": None
        })

    except Exception as e:
        import traceback
        error_msg = f"Error in solve_calibration_offsets: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})


# -----------------------------------------------------------------------------
# Affine 1-Shot calibration (full 3x3 A + bias b)
#
# The existing solve_calibration_offsets() collapses each shot into a 2-param
# pitch/roll rotation, which cannot represent cross-axis coupling or the
# camera<->IMU mounting rotation (orientation-dependent errors). The two
# functions below instead retain the raw (measured, expected) gravity pairs
# across several attitudes and fit a full affine model:
#
#     up_sensor  ~=  A . a_raw  +  b        (A: 3x3, b: 3-vector, 12 params)
#
# solve_calibration_pair() returns ONLY the expected gravity direction in the
# sensor frame for one shot (identical geometry to solve_calibration_offsets);
# the measured raw direction + magnitude are supplied by the app. The app
# accumulates these pairs and calls fit_affine_calibration() once enough
# attitude spread is collected. Neither function touches the legacy path.
# -----------------------------------------------------------------------------

def _expected_gravity_sensor(ra_deg, dec_deg, roll_deg, lat_deg, lon_deg, time_iso,
                             pressure_hpa=1013.25, temperature_c=15.0):
    """Expected zenith ("up") unit vector in the sensor frame for a shot whose
    plate solve is (ra, dec, roll), taken at a known position and time.

    This is what the accelerometer SHOULD read, so it includes refraction:
    solve_oneshot adds R(alt) back to the measured zenith, and anything fitted
    against this vector (pitch/roll offsets or the affine model) must see the
    same refraction removed, or every later fix is biased by R(alt)."""
    from astropy.time import Time

    t = Time(time_iso)
    era_deg = t.earth_rotation_angle('tio').deg
    lst_deg = (lon_deg + era_deg) % 360

    # lon + ERA is a right ascension in CIRS, not ICRS: ERA is measured about
    # the CIP from the CIO. R, from tetra3, maps ICRS -> camera, so the zenith
    # must be rotated out of CIRS first. Omitting this bakes the ~0.15 deg CIP
    # offset (see solve_oneshot) into the fitted calibration, which then
    # over-corrects every later fix by that amount.
    lat_rad = np.radians(lat_deg)
    lst_rad = np.radians(lst_deg)
    zenith_cirs = np.array([
        np.cos(lat_rad) * np.cos(lst_rad),
        np.cos(lat_rad) * np.sin(lst_rad),
        np.sin(lat_rad)
    ])
    _tt = t.tt
    zenith_icrs = erfa.c2i06a(_tt.jd1, _tt.jd2).T @ zenith_cirs

    v_cam_exp = _camera_rotation(ra_deg, dec_deg, roll_deg) @ zenith_icrs

    # The sensor does not measure the zenith relative to the CATALOG boresight
    # that tetra3 reports — it measures it relative to where the camera
    # physically pointed, which refraction places R(alt) away. Undo that here
    # (sign=-1) so the fitted calibration describes the sensor alone.
    # solve_oneshot applies the same rotation with sign=+1; the two must always
    # be a matched pair.
    v_cam_exp = _refract_zenith_in_camera(v_cam_exp, pressure_hpa,
                                          temperature_c, sign=-1,
                                          label="Calib: ")

    # Camera -> sensor, the inverse of solve_oneshot's portrait mapping
    #   v_cam = [-gz, -gx, gy]  ->  gx = -v_cam[1], gy = v_cam[2], gz = -v_cam[0]
    v_exp = np.array([-v_cam_exp[1], v_cam_exp[2], -v_cam_exp[0]])
    n = np.linalg.norm(v_exp)
    if n > 1e-6:
        v_exp = v_exp / n
    return v_exp


def solve_calibration_pair(ra_deg, dec_deg, roll_deg, lat_deg, lon_deg, time_iso,
                           pressure_hpa=1013.25, temperature_c=15.0):
    """Returns the expected gravity direction (sensor frame, unit) for one
    1-Shot capture, so the app can pair it with the measured raw gravity for an
    affine fit. Does not solve or collapse anything."""
    try:
        v_exp = _expected_gravity_sensor(
            ra_deg, dec_deg, roll_deg, lat_deg, lon_deg, time_iso,
            pressure_hpa, temperature_c
        )
        return json.dumps({
            "exp": [float(v_exp[0]), float(v_exp[1]), float(v_exp[2])],
            "error": None
        })
    except Exception as e:
        import traceback
        error_msg = f"Error in solve_calibration_pair: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})


G_STANDARD = 9.807  # m/s^2, target magnitude for the affine fit

# ---------------------------------------------------------------------------
# Acceptance gates for the affine fit.
#
# Calibrated by simulation over a realistic session model (n distinct attitudes,
# several shots each with ~2 deg hand jitter, 0.004 m/s^2 accel noise, 0.05 deg
# star-solve noise on the expected direction), scored against a held-out set
# covering the full attitude sphere. Findings:
#
#   * In-sample RMS is WORTHLESS as a quality signal: 0.03-0.06 deg whether the
#     true held-out error is 0.07 deg or 17.9 deg. A 12-parameter model fits 6
#     points almost exactly no matter how bad it is.
#   * Leave-one-SAMPLE-out is nearly as bad: it never exceeded 0.084 deg across
#     the whole sweep, because the held-out shot's own cluster is still in the
#     training set. Tagging every shot as its own group silently degrades
#     leave-one-group-out into exactly this.
#   * What does predict truth is the CONDITIONING of the regressor together with
#     the number of genuinely distinct attitudes, plus leave-one-CLUSTER-out.
#
# Correlation of each diagnostic with true held-out error (log space, 420 fits):
#     cond        +0.92     attitude spread  -0.91
#     n_clusters  -0.80     leave-one-cluster-out  +0.30
#     n_pairs     -0.21     IN-SAMPLE RMS    -0.05   <- no signal at all
#
# Thresholds were tuned on 420 simulated sessions and then validated on 1008
# sessions from independent seeds: 138 admitted, mean true error 0.053 deg
# (3.2 nmi), max 0.167 deg, none above 0.20 deg.
#
# What this demands of a calibration session, measured:
#     spread  shots   pass rate   true error
#      15-45 deg  any      0%       0.25-4.3 deg   (correctly always rejected)
#      60 deg     24+     14-36%    0.14-0.20 deg
#      90 deg     24+     79-86%    0.06-0.09 deg  <- the target
# i.e. ~6-8 genuinely different tilts spanning as wide a range as possible,
# 3-4 shots each. Six shots at one attitude — the original requirement — cannot
# produce a usable 12-parameter fit at any threshold.
#
# Gates are deliberately conservative: a rejected fit costs the user nothing
# (the legacy pitch/roll offset path still runs, ~20 nmi), while an accepted bad
# fit is catastrophic — 1 deg of vertical error is 60 nmi of position error, so
# a bad affine model is far worse than none.
# ---------------------------------------------------------------------------
AFFINE_MIN_PAIRS = 24          # shots required before a fit is even attempted
AFFINE_MIN_CLUSTERS = 4        # distinct attitudes required (hard floor)
AFFINE_CLUSTER_TOL_DEG = 15.0  # shots within this angle are the same attitude
AFFINE_MAX_COND = 60.0         # regressor condition number — the primary gate
AFFINE_MAX_LOCO_DEG = 0.20     # leave-one-cluster-out RMS direction error
AFFINE_MAX_BIAS = 0.5          # m/s^2
AFFINE_MAX_GAIN_SPREAD = 0.15  # ~15% spread across the diagonal of A


def _cluster_attitudes(dirs, tol_deg=AFFINE_CLUSTER_TOL_DEG):
    """Greedy angular clustering of measured gravity directions. Returns a
    per-sample cluster label. Derived from the DATA, never from UI bookkeeping:
    six shots the user thought were 'different attitudes' can easily be one."""
    import numpy as np
    centres = []
    labels = []
    for u in dirs:
        best_d, best_i = 1e9, -1
        for i, c in enumerate(centres):
            d = np.degrees(np.arccos(np.clip(float(np.dot(u, c)), -1.0, 1.0)))
            if d < best_d:
                best_d, best_i = d, i
        if best_i >= 0 and best_d <= tol_deg:
            labels.append(best_i)
        else:
            centres.append(u)
            labels.append(len(centres) - 1)
    return labels, centres


def fit_affine_calibration(pairs_json):
    """
    Fit up_sensor ~= A . a_raw + b from a set of (measured, expected) pairs.

    Input JSON: list of objects, each:
        {"meas": [x,y,z],   # raw-accel gravity DIRECTION (unit), sensor frame
         "mag":  float,     # raw |accel| at that shot (m/s^2)
         "exp":  [x,y,z]}   # expected gravity DIRECTION (unit), sensor frame
    Any "group" field is IGNORED: attitude grouping is derived from the measured
    directions themselves (see _cluster_attitudes).

    The true raw acceleration used as the regressor is a = mag * meas_unit, so
    per-axis gain is observable. The target is G_STANDARD * exp_unit, so the fit
    is constrained in BOTH direction and magnitude (this is what stops the gain
    terms from drifting). Each output row is an independent 4-param linear LSQ:
        [a_x a_y a_z 1] . [A_i0 A_i1 A_i2 b_i]^T = G_STANDARD * exp_i

    Returns A (row-major 9), b (3), the diagnostics the gates are based on, a
    `healthy` verdict, and `reject_reasons` explaining any refusal so the UI can
    tell the user what to do differently.
    """
    try:
        import numpy as np

        pairs = json.loads(pairs_json)
        if not isinstance(pairs, list) or len(pairs) < AFFINE_MIN_PAIRS:
            return json.dumps({
                "healthy": False,
                "n_pairs": len(pairs) if isinstance(pairs, list) else 0,
                "reject_reasons": [
                    "need %d shots, have %d" % (
                        AFFINE_MIN_PAIRS,
                        len(pairs) if isinstance(pairs, list) else 0)
                ],
                "error": None,
            })

        A_reg = []   # regressor rows [ax, ay, az, 1]
        Y = []       # target rows    G * exp_unit
        meas_dirs = []
        for p in pairs:
            m = np.array(p["meas"], dtype=float)
            mn = np.linalg.norm(m)
            if mn > 1e-6:
                m = m / mn
            mag = float(p.get("mag", G_STANDARD))
            if mag < 1e-3:
                mag = G_STANDARD
            a = m * mag
            e = np.array(p["exp"], dtype=float)
            en = np.linalg.norm(e)
            if en > 1e-6:
                e = e / en
            A_reg.append([a[0], a[1], a[2], 1.0])
            Y.append(e * G_STANDARD)
            meas_dirs.append(m)

        A_reg = np.array(A_reg)
        Y = np.array(Y)

        def _fit(rows):
            # Solve the 4-param linear system for each of the 3 output axes.
            M = A_reg[rows]
            params = np.zeros((3, 4))
            for axis in range(3):
                sol, _res, _rank, _sv = np.linalg.lstsq(M, Y[rows][:, axis], rcond=None)
                params[axis] = sol
            A = params[:, :3]
            b = params[:, 3]
            return A, b

        def _dir_err_deg(A, b, rows):
            errs = []
            for i in rows:
                a = A_reg[i][:3]
                pred = A @ a + b
                pn = np.linalg.norm(pred)
                if pn < 1e-9:
                    continue
                pred_u = pred / pn
                exp_u = Y[i] / np.linalg.norm(Y[i])
                dot = np.clip(np.dot(pred_u, exp_u), -1.0, 1.0)
                errs.append(np.degrees(np.arccos(dot)))
            return float(np.sqrt(np.mean(np.square(errs)))) if errs else float("nan")

        all_rows = list(range(len(A_reg)))
        A_full, b_full = _fit(all_rows)
        in_sample_rms = _dir_err_deg(A_full, b_full, all_rows)

        # --- Attitude clustering, from the data ---
        labels, centres = _cluster_attitudes(meas_dirs)
        n_clusters = len(centres)

        # Widest angle between any two attitude centres: the headline number for
        # telling the user whether they actually moved the phone around.
        spread_deg = 0.0
        for i in range(len(centres)):
            for j in range(i + 1, len(centres)):
                d = np.degrees(np.arccos(
                    np.clip(float(np.dot(centres[i], centres[j])), -1.0, 1.0)))
                spread_deg = max(spread_deg, d)

        # --- Conditioning of the regressor ---
        # If every shot was taken at nearly the same attitude, the columns
        # [ax ay az 1] are nearly collinear. lstsq then returns a minimum-norm
        # solution with NO warning and a near-zero in-sample residual, while the
        # model is meaningless anywhere else on the sphere. This is the single
        # most likely way to end up with a confidently wrong calibration.
        cond = float(np.linalg.cond(A_reg))

        # --- Leave-one-CLUSTER-out validation ---
        # Whole attitudes are held out, so the fit gets no help from neighbours
        # of the held-out shot. This is the only validation here that responds
        # to a badly-spread session.
        loco_errs = []
        for g in range(n_clusters):
            train = [i for i in all_rows if labels[i] != g]
            test = [i for i in all_rows if labels[i] == g]
            # 4 params per output axis; require real overdetermination
            if len(train) < 8 or not test:
                continue
            A_t, b_t = _fit(train)
            e = _dir_err_deg(A_t, b_t, test)
            if e == e:  # not NaN
                loco_errs.append(e)
        loco_rms = (float(np.sqrt(np.mean(np.square(loco_errs))))
                    if loco_errs else float("nan"))

        # --- Health metrics ---
        gains = np.abs(np.diag(A_full))
        gain_spread = float(gains.max() - gains.min()) if gains.size else 0.0
        bias_mag = float(np.linalg.norm(b_full))

        # --- Gates ---
        reasons = []
        if len(all_rows) < AFFINE_MIN_PAIRS:
            reasons.append("need %d shots, have %d"
                           % (AFFINE_MIN_PAIRS, len(all_rows)))
        if n_clusters < AFFINE_MIN_CLUSTERS:
            reasons.append("need %d distinct attitudes, have %d (vary the phone's "
                           "tilt more between shots)"
                           % (AFFINE_MIN_CLUSTERS, n_clusters))
        if not (cond == cond) or cond > AFFINE_MAX_COND:
            reasons.append("attitudes too alike (conditioning %.0f > %.0f)"
                           % (cond, AFFINE_MAX_COND))
        if not (loco_rms == loco_rms):
            reasons.append("could not validate (too few attitudes to hold one out)")
        elif loco_rms > AFFINE_MAX_LOCO_DEG:
            reasons.append("held-out error %.2f deg > %.2f deg"
                           % (loco_rms, AFFINE_MAX_LOCO_DEG))
        if bias_mag >= AFFINE_MAX_BIAS:
            reasons.append("bias %.2f m/s2 implausible" % bias_mag)
        if gain_spread >= AFFINE_MAX_GAIN_SPREAD:
            reasons.append("gain spread %.3f implausible" % gain_spread)

        healthy = not reasons

        print("Python: affine fit n=%d clusters=%d spread=%.1f deg cond=%.0f "
              "in_sample=%.3f LOCO=%.3f -> %s"
              % (len(all_rows), n_clusters, spread_deg, cond, in_sample_rms,
                 loco_rms, "ACCEPTED" if healthy else "REJECTED (%s)"
                 % "; ".join(reasons)))

        return json.dumps({
            "A": [float(x) for x in A_full.flatten()],  # row-major 3x3
            "b": [float(x) for x in b_full],
            "in_sample_rms_deg": in_sample_rms,
            "loco_rms_deg": loco_rms,
            "cond": cond,
            "attitude_spread_deg": spread_deg,
            "gain_spread": gain_spread,
            "bias_mag": bias_mag,
            "n_pairs": len(all_rows),
            "n_clusters": n_clusters,
            "healthy": bool(healthy),
            "reject_reasons": reasons,
            "error": None
        })

    except Exception as e:
        import traceback
        error_msg = f"Error in fit_affine_calibration: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})


# ---------------------------------------------------------------------------
# Quick re-zero: a small rotation applied after the affine model.
#
# The accelerometer offset drifts from day to day (on the Pi instrument, jumps
# of more than 20 nmi). The full multi-attitude affine fit does not need
# repeating for that; a small rotation on top of it does. One attitude cannot
# see the rotation about its own "up" axis, so two attitudes are needed, and
# they must differ in the phone's tilt: two sky directions at the same altitude
# give the same "up" in the sensor frame.
# ---------------------------------------------------------------------------
REZERO_MIN_SPREAD_DEG = 30.0    # between the two most different attitudes
REZERO_MAX_ROTATION_DEG = 1.0   # larger is a broken model, not a drift
REZERO_MAX_RESIDUAL_DEG = 0.3   # what the rotation cannot explain


def _rotation_vector(R):
    """Axis * angle (radians) of a rotation matrix."""
    import numpy as np
    angle = float(np.arccos(np.clip((np.trace(R) - 1.0) / 2.0, -1.0, 1.0)))
    if angle < 1e-12:
        return np.zeros(3)
    axis = np.array([R[2, 1] - R[1, 2], R[0, 2] - R[2, 0], R[1, 0] - R[0, 1]])
    return axis / (2.0 * np.sin(angle)) * angle


def fit_rezero_rotation(pairs_json, affine_json):
    """
    Fit the rotation R that best maps the affine "up" onto the expected "up":
        up = R . normalize(A . a_raw + b)

    pairs_json: list of {"meas": [x,y,z] raw direction (unit), "mag": |a| (m/s^2),
                         "exp": [x,y,z] expected up (unit)}, one per burst,
                the same pairs as fit_affine_calibration.
    affine_json: {"A": [9] row-major, "b": [3]}, the model in use.

    The rotation is fitted against A and b only, so a new re-zero replaces
    the previous one instead of adding to it.

    Returns the rotation vector (radians), its angle, the residual, the
    attitude spread, the misalignment of each pair before and after, a
    `healthy` verdict and the reasons for a refusal.
    """
    try:
        import numpy as np
        pairs = json.loads(pairs_json)
        model = json.loads(affine_json)
        A = np.array(model["A"], dtype=float).reshape(3, 3)
        b = np.array(model["b"], dtype=float)

        ups, exps = [], []
        for p in pairs:
            m = np.array(p["meas"], dtype=float)
            m = m / np.linalg.norm(m)
            up = A @ (m * float(p.get("mag", G_STANDARD))) + b
            ups.append(up / np.linalg.norm(up))
            e = np.array(p["exp"], dtype=float)
            exps.append(e / np.linalg.norm(e))
        ups, exps = np.array(ups), np.array(exps)

        def angles_deg(u, e):
            return np.degrees(np.arccos(np.clip(np.sum(u * e, axis=1), -1.0, 1.0)))

        _labels, centres = _cluster_attitudes(list(ups))
        spread = 0.0
        for i in range(len(centres)):
            for j in range(i + 1, len(centres)):
                spread = max(spread, float(np.degrees(np.arccos(
                    np.clip(np.dot(centres[i], centres[j]), -1.0, 1.0)))))

        # Wahba's problem: the rotation that minimises sum |e_i - R u_i|^2.
        U, _S, Vt = np.linalg.svd(exps.T @ ups)
        d = np.sign(np.linalg.det(U) * np.linalg.det(Vt))
        R = U @ np.diag([1.0, 1.0, d]) @ Vt
        rotvec = _rotation_vector(R)
        angle_deg = float(np.degrees(np.linalg.norm(rotvec)))
        before = angles_deg(ups, exps)
        after = angles_deg(ups @ R.T, exps)
        residual = float(np.sqrt(np.mean(after ** 2)))

        reasons = []
        if len(centres) < 2 or spread < REZERO_MIN_SPREAD_DEG:
            reasons.append("need two tilts at least %.0f deg apart, have %.0f deg "
                           "(change the phone's altitude, not only its direction)"
                           % (REZERO_MIN_SPREAD_DEG, spread))
        if angle_deg > REZERO_MAX_ROTATION_DEG:
            reasons.append("rotation %.2f deg > %.1f deg: redo the full calibration"
                           % (angle_deg, REZERO_MAX_ROTATION_DEG))
        if residual > REZERO_MAX_RESIDUAL_DEG:
            reasons.append("residual %.2f deg > %.1f deg: the bursts disagree"
                           % (residual, REZERO_MAX_RESIDUAL_DEG))
        healthy = not reasons

        print("Python: re-zero n=%d spread=%.0f deg rotation=%.3f deg residual=%.3f deg -> %s"
              % (len(pairs), spread, angle_deg, residual,
                 "ACCEPTED" if healthy else "REJECTED (%s)" % "; ".join(reasons)))
        return json.dumps({
            "rotation": [float(x) for x in rotvec],
            "rotation_deg": angle_deg,
            "residual_deg": residual,
            "spread_deg": spread,
            "before_deg": [float(x) for x in before],
            "after_deg": [float(x) for x in after],
            "n_pairs": len(pairs),
            "healthy": bool(healthy),
            "reject_reasons": reasons,
            "error": None
        })
    except Exception as e:
        import traceback
        error_msg = f"Error in fit_rezero_rotation: {e}"
        print(f"Python: {error_msg}\n{traceback.format_exc()}")
        return json.dumps({"error": error_msg})


# =============================================================================
# SECTION 5: SENSOR WAVE MODELING
# =============================================================================

WAVE_MIN_PERIOD_S = 2.0    # shorter is hand tremor, not a ship's motion
WAVE_MAX_PERIOD_S = 20.0   # longer is drift
WAVE_MIN_DURATION_S = 10.0


def build_wave_model(json_data_str):
    """
    Measures the main wave period from a few tens of seconds of accelerometer
    samples.
    Input: JSON string of a list of [t_s, x, y, z] rows: the sample time in
    seconds and the acceleration (any unit; only its direction is used).
    Returns: JSON string with the mean "up" direction and the wave stats.

    The wave tilts the measured "up" to both sides of the mean. Each sample is
    projected on two axes perpendicular to the mean, so the swing keeps its
    sign: the angle to the mean (always >= 0) would fold the swing and double
    the frequency. The period is the peak of the summed power spectra of the
    two components, on a uniform grid built from the real sample times.

    The analysis is in the phone frame: a phone that rotates during the
    recording adds its rotation to the result. Hold it as still as possible.
    """
    try:
        import json
        import numpy as np

        data = np.array(json.loads(json_data_str), dtype=float)
        if data.ndim != 2 or data.shape[0] < 10 or data.shape[1] != 4:
            return json.dumps({"success": False, "error": "Insufficient data"})

        data = data[np.argsort(data[:, 0])]
        t = data[:, 0]
        keep = np.concatenate(([True], np.diff(t) > 0))
        t, acc = t[keep], data[keep, 1:]
        duration = float(t[-1] - t[0])
        if duration < WAVE_MIN_DURATION_S:
            return json.dumps({"success": False,
                               "error": f"Recording too short ({duration:.1f} s)"})

        # 1. Mean "up" direction: the wave acceleration averages out.
        mean = np.mean(acc, axis=0)
        mean = mean / np.linalg.norm(mean)

        # 2. Two axes perpendicular to the mean, and the signed tilt on each.
        helper = np.zeros(3)
        helper[np.argmin(np.abs(mean))] = 1.0
        e1 = np.cross(mean, helper)
        e1 /= np.linalg.norm(e1)
        e2 = np.cross(mean, e1)
        units = acc / np.linalg.norm(acc, axis=1, keepdims=True)
        tilt = np.column_stack((units @ e1, units @ e2))  # radians, small angles

        # 3. Uniform grid at the mean sample rate (the real rate jitters).
        rate = (len(t) - 1) / duration
        grid = t[0] + np.arange(int(duration * rate) + 1) / rate
        tilt = np.column_stack([np.interp(grid, t, tilt[:, k]) for k in range(2)])
        tilt -= tilt.mean(axis=0)

        # Amplitude: 95th percentile of the tilt away from the mean.
        amplitude_deg = float(np.degrees(np.percentile(np.linalg.norm(tilt, axis=1), 95)))

        # 4. Summed power spectrum. The window lowers the leakage; the zero
        #    padding interpolates the spectrum, because 30 s gives bins only
        #    1/30 Hz apart (6 s and 7.5 s are neighbours).
        n = len(grid)
        n_fft = 16 * n
        window = np.hanning(n)[:, None]
        power = np.sum(np.abs(np.fft.rfft(tilt * window, n=n_fft, axis=0)) ** 2, axis=1)
        freqs = np.fft.rfftfreq(n_fft, d=1.0 / rate)
        band = (freqs >= 1.0 / WAVE_MAX_PERIOD_S) & (freqs <= 1.0 / WAVE_MIN_PERIOD_S)
        if not np.any(band) or np.max(power[band]) <= 0:
            period = 0.0
        else:
            peak_freq = freqs[band][np.argmax(power[band])]
            period = float(1.0 / peak_freq)

        result = {
            "success": True,
            "true_gravity": [float(mean[0]), float(mean[1]), float(mean[2])],
            "wave_amplitude_deg": round(amplitude_deg, 2),
            "wave_period_sec": round(period, 1),
            "duration_sec": round(duration, 1),
            "sample_rate_hz": round(float(rate), 1)
        }
        return json.dumps(result)

    except Exception as e:
        import traceback
        return json.dumps({"success": False, "error": str(e), "trace": traceback.format_exc()})

