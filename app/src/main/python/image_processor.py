# src/main/python/image_processor.py

from PIL import Image
import numpy as np
import tetra3
import traceback # For detailed error logging
import os # For path operations

# --- Tetra3 Instance and Initialization Error ---
T3_INSTANCE = None
INITIALIZATION_ERROR = None

# --- Initialize Tetra3 Astrometry Solver (runs when module is first imported) ---
try:
    print("Python (image_processor.py): Initializing Tetra3 Astrometry Solver...")
    T3_INSTANCE = tetra3.Tetra3(load_database='default_database')
    if T3_INSTANCE.has_database:
        print("Python (image_processor.py): Tetra3 Astrometry Solver initialized successfully and database loaded.")
        print(f"Python (image_processor.py): DB Max FOV: {T3_INSTANCE.database_properties.get('max_fov')}")
        print(f"Python (image_processor.py): DB Min FOV: {T3_INSTANCE.database_properties.get('min_fov')}")
        print(f"Python (image_processor.py): DB Num Patterns: {T3_INSTANCE.num_patterns}")
    else:
        INITIALIZATION_ERROR = "Tetra3 instance created but database FAILED to load."
        print(f"Python (image_processor.py): ERROR - {INITIALIZATION_ERROR}")

except Exception as e_t3_init:
    INITIALIZATION_ERROR = f"FATAL ERROR initializing Tetra3 Astrometry Solver: {str(e_t3_init)}\n{traceback.format_exc()}"
    print(f"Python (image_processor.py): {INITIALIZATION_ERROR}")
    # T3_INSTANCE will remain None if an exception occurs here

# --- CedarDetect is explicitly disabled for now ---
USE_CEDAR_DETECT = False
CEDAR_DETECT_INSTANCE = None
# Cleanup function for CedarDetect related resources (if it were used)
# For now, it does nothing as CedarDetect is disabled.
def cleanup_resources():
    """
    Cleans up resources. Currently a placeholder as CedarDetect is not used.
    """
    print("Python (image_processor.py): cleanup_resources called. No specific cleanup actions for now as CedarDetect is disabled.")
    pass


def solve_image_from_path(image_path_str, solve_timeout_ms=10000, distortion_param=None): # Defaulting to 10s timeout and distortion=None
    """
    Analyzes an image from a given file path using Tetra3.
    CedarDetect is currently disabled.
    Returns 1 for solved=True, 0 for solved=False.
    """
    print(f"Python (image_processor.py): solve_image_from_path called with path='{image_path_str}', timeout={solve_timeout_ms}ms, distortion={distortion_param}")

    if T3_INSTANCE is None:
        error_msg = INITIALIZATION_ERROR or "Tetra3 Astrometry Solver not initialized (T3_INSTANCE is None)."
        print(f"Python (image_processor.py): Error - {error_msg}")
        return {
            "solved": 0, # Use 0 for False
            "error_message": error_msg,
            "raw_solution_str": "Solver not initialized"
        }

    if not os.path.exists(image_path_str):
        error_msg = f"Image file does not exist at path: {image_path_str}"
        print(f"Python (image_processor.py): Error - {error_msg}")
        return {
            "solved": 0, # Use 0 for False
            "error_message": error_msg,
            "raw_solution_str": "Image file not found"
        }

    try:
        print(f"Python (image_processor.py): Opening image from path: {image_path_str}...")
        with Image.open(image_path_str) as img:
            img_gray = img.convert(mode='L')
            np_image = np.asarray(img_gray, dtype=np.uint8)
            height, width = np_image.shape
        print(f"Python (image_processor.py): Image loaded: {width}x{height}, Mode: {img_gray.mode}")

        print("Python (image_processor.py): Extracting centroids using tetra3.get_centroids_from_image...")
        # CedarDetect is disabled, so we always use the default Tetra3 centroiding
        centroids = tetra3.get_centroids_from_image(np_image)

        if centroids is None or len(centroids) == 0:
            print("Python (image_processor.py): No centroids found in the image.")
            return {
                "solved": 0, # Use 0 for False
                "error_message": "No centroids found in image by tetra3.get_centroids_from_image.",
                "raw_solution_str": "No centroids found"
            }
        print(f"Python (image_processor.py): tetra3.get_centroids_from_image extracted {len(centroids)} centroids.")

        trimmed_centroids = centroids[:30] # Tetra3 generally doesn't need more
        print(f"Python (image_processor.py): Using {len(trimmed_centroids)} brightest centroids for solving.")

        print(f"Python (image_processor.py): Solving with Tetra3 using distortion={distortion_param}, timeout={solve_timeout_ms}ms...")
        solution = T3_INSTANCE.solve_from_centroids(
            trimmed_centroids,
            (height, width),
            distortion=distortion_param,
            solve_timeout=solve_timeout_ms,
            return_matches=True,
            return_catalog=False
        )
        print("Python (image_processor.py): Tetra3 astrometry solving complete.")
        print(f"Python (image_processor.py) DEBUG: Raw solution object from tetra3: {solution}")
        print(f"Python (image_processor.py) DEBUG: Type of solution object: {type(solution)}")


        if isinstance(solution, dict) and solution.get('RA') is not None and solution.get('Dec') is not None:
            # Successfully solved
            final_result = {
                "solved": 1, # Use 1 for True
                "ra_deg": solution.get('RA'),
                "dec_deg": solution.get('Dec'),
                "roll_deg": solution.get('Roll'),
                "fov_deg": solution.get('FOV'),
                "error_arcsec": solution.get('RMSE'),
                "raw_solution_str": str(solution)
            }
            print(f"Python (image_processor.py): Solution FOUND: {final_result}")
            return final_result
        elif isinstance(solution, dict): # It's a dict, but not a successful solve (RA/Dec missing)
            error_msg_from_solve = "Partial solution or error from Tetra3 solve_from_centroids. "
            status_map = {
                2: "NO_MATCH", 3: "TIMEOUT", 4: "CANCELLED", 5: "TOO_FEW (centroids)"
            }
            error_msg_from_solve += f"Status: {status_map.get(solution.get('status'), 'UnknownStatus')}."

            print(f"Python (image_processor.py): Solution NOT found or incomplete. Details: {error_msg_from_solve} Raw: {solution}")
            return {
                "solved": 0, # Use 0 for False
                "error_message": error_msg_from_solve,
                "raw_solution_str": str(solution)
            }
        else: # solve_from_centroids returned None or unexpected type
            error_msg = "No solution found by Tetra3 (solve_from_centroids returned None or unexpected type)."
            print(f"Python (image_processor.py): Error - {error_msg}. Returned type: {type(solution).__name__}")
            return {
                "solved": 0, # Use 0 for False
                "error_message": error_msg,
                "raw_solution_str": "solve_from_centroids returned None or unexpected type"
            }

    except Exception as e:
        # Catch any other unexpected errors during the process
        error_msg = f"Error processing image '{image_path_str}': {str(e)}\n{traceback.format_exc()}"
        print(f"Python (image_processor.py): Exception - {error_msg}")
        return {
            "solved": 0, # Use 0 for False
            "error_message": error_msg,
            "raw_solution_str": "Exception in solve_image_from_path"
        }
