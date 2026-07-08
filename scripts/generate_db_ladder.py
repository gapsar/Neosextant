#!/usr/bin/env python3
"""Generate the on-device calibration database ladder.

The app's self-calibration (app/src/main/python/self_calibration.py) discovers
a phone's field of view by strict-solving stored centroids against a ladder of
tetra3 databases covering common phone main-camera FOVs, then freezes the
best-fitting one. This script builds those databases.

Ladder geometry: solve-time FOVs (portrait width) from 35 deg in x1.07 steps —
step mismatch is at most ~3.5%, well inside what a database tolerates. Each
database max_fov = solve_fov * 1.0625, the ratio validated by the desktop
optimizer on Pixel 8a field data (solve fov 45.83 <-> db max_fov 48.69).
Catalog settings match the deployed db_calib_fov48.69_mag7.0.npz.

Run from a desktop checkout of cedar-solve (needs the hip_main catalog):
    python3 scripts/generate_db_ladder.py <cedar-solve-dir> <output-dir>
"""
import sys
from pathlib import Path

CEDAR_SOLVE = sys.argv[1] if len(sys.argv) > 1 else "."
OUT_DIR = Path(sys.argv[2] if len(sys.argv) > 2 else
               "app/src/main/python/cedar-solve/tetra3/data")

# Keep in sync with LADDER_FOVS in app/src/main/python/self_calibration.py.
# Explicit literals (not computed) so filenames can never drift from values.
SOLVE_FOVS = [35.0, 37.5, 40.1, 42.9, 45.9, 49.1, 52.5, 56.2, 60.1, 64.3]
DB_FOV_RATIO = 1.0625            # db max_fov / solve-time portrait-width fov


def ladder_db_name(fov: float) -> str:
    # dot-free stem: tetra3 resolves save/load paths with with_suffix('.npz'),
    # which mangles any other dot in the name ("f35.0" -> "f35.npz").
    return "db_ladder_f" + f"{fov:.1f}".replace(".", "_") + ".npz"


sys.path.insert(0, CEDAR_SOLVE)
import tetra3  # noqa: E402

OUT_DIR.mkdir(parents=True, exist_ok=True)
for fov in SOLVE_FOVS:
    name = ladder_db_name(fov)
    out = (OUT_DIR / name).resolve()
    if out.exists():
        print(f"{name}: exists, skipping")
        continue
    print(f"{name}: building (max_fov={round(fov * DB_FOV_RATIO, 3)})")
    t3 = tetra3.Tetra3(load_database=None)
    t3.generate_database(
        max_fov=round(fov * DB_FOV_RATIO, 3),
        min_fov=None,
        star_catalog="hip_main",
        pattern_stars_per_fov=10,
        verification_stars_per_fov=30,
        star_max_magnitude=7.0,
        pattern_max_error=0.005,
        save_as=out.with_suffix(""),   # tetra3 re-appends .npz
    )
print("ladder FOVs:", SOLVE_FOVS)
