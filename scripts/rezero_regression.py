"""Regression suite for celestial_navigator.fit_rezero_rotation.

Runs on a desktop with synthetic pairs; stubs tetra3. Usage:

    python3 scripts/rezero_regression.py [path/to/python/dir]

  A) two tilts 60 deg apart recover a 0.4 deg rotation, and applying the
     rotation vector with Rodrigues' formula (as SensorCalibrator does)
     removes the misalignment
  B) two bursts at the same tilt are refused (rotation about "up" unseen)
  C) a 1.5 deg rotation is refused
  D) two bursts whose angle apart is 1 deg off are refused
"""
import sys, types, os, json
import numpy as np

SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "python")
sys.modules["tetra3"] = types.ModuleType("tetra3")
sys.path.insert(0, SRC)
# The import starts an IERS refresh thread; keep it off the network.
from astropy.utils.data import conf as astropy_data_conf
astropy_data_conf.allow_internet = False
import celestial_navigator as cn

rng = np.random.default_rng(3)
results = []
def check(name, ok, detail=""):
    results.append(ok)
    print(f"{name}: {'ok' if ok else 'FAILED'} {detail}")

def rot(axis, deg):
    """Rotation matrix about a unit axis (Rodrigues)."""
    k = np.asarray(axis, float) / np.linalg.norm(axis)
    K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    t = np.radians(deg)
    return np.eye(3) + np.sin(t) * K + (1 - np.cos(t)) * K @ K

def rodrigues_apply(rotvec, v):
    """The formula SensorCalibrator.applyRezero uses."""
    theta = np.linalg.norm(rotvec)
    if theta < 1e-12:
        return v
    k = rotvec / theta
    return v * np.cos(theta) + np.cross(k, v) * np.sin(theta) + k * np.dot(k, v) * (1 - np.cos(theta))

def tilt(deg_pitch, deg_roll):
    """An "up" direction in the sensor frame for a phone tilted this way."""
    p, r = np.radians(deg_pitch), np.radians(deg_roll)
    u = np.array([np.sin(r) * np.cos(p), np.sin(p), np.cos(r) * np.cos(p)])
    return u / np.linalg.norm(u)

# An affine model that is not the identity.
A = np.eye(3) + 0.01 * rng.standard_normal((3, 3))
b = 0.05 * rng.standard_normal(3)
AFFINE = json.dumps({"A": A.flatten().tolist(), "b": b.tolist()})

def pairs_for(ups, R_true, noise_deg=0.03, exp_extra=None):
    """Pairs whose affine "up" is each of `ups` and whose expected "up" is R_true . up."""
    out = []
    for i, u in enumerate(ups):
        a = np.linalg.solve(A, cn.G_STANDARD * u - b)       # raw accel giving this up
        e = R_true @ u
        e = rot(rng.standard_normal(3), noise_deg) @ e       # star-solve noise
        if exp_extra is not None and i == 0:
            e = exp_extra @ e
        out.append({"meas": (a / np.linalg.norm(a)).tolist(),
                    "mag": float(np.linalg.norm(a)), "exp": e.tolist()})
    return out

def fit(pairs):
    return json.loads(cn.fit_rezero_rotation(json.dumps(pairs), AFFINE))

# --- A: two tilts 60 deg apart, 0.4 deg rotation ---
R_true = rot([0.3, -0.5, 0.8], 0.4)
ups = [tilt(30, 0), tilt(30, 0), tilt(-30, 5), tilt(-30, 5)]
res = fit(pairs_for(ups, R_true))
rv = np.array(res["rotation"])
true_rv = cn._rotation_vector(R_true)
err_deg = np.degrees(np.linalg.norm(rv - true_rv))
check("A recovers rotation", res["healthy"] and err_deg < 0.05,
      f"angle={res['rotation_deg']:.3f} deg, error={err_deg:.3f} deg, residual={res['residual_deg']:.3f}")
after = max(np.degrees(np.arccos(np.clip(np.dot(rodrigues_apply(rv, u), R_true @ u), -1, 1)))
            for u in ups)
check("A Rodrigues matches", after < 0.05, f"worst after={after:.3f} deg")

# --- B: same tilt twice ---
res = fit(pairs_for([tilt(30, 0), tilt(33, 2)], R_true))
check("B same tilt refused", not res["healthy"] and "tilts" in res["reject_reasons"][0],
      f"spread={res['spread_deg']:.0f} deg")

# --- C: rotation too large ---
res = fit(pairs_for(ups, rot([1, 0, 0], 1.5)))
check("C 1.5 deg refused", not res["healthy"] and any("rotation" in r for r in res["reject_reasons"]),
      f"angle={res['rotation_deg']:.2f} deg")

# --- D: the angle between the two bursts is 1 deg off ---
# With two bursts a rotation absorbs any error except a change of the angle
# between them, so that is the inconsistency the residual can see.
u1, u2 = tilt(30, 0), tilt(-30, 5)
res = fit(pairs_for([u1, u2], R_true, exp_extra=rot(np.cross(u1, u2), 1.0)))
check("D inconsistent refused", not res["healthy"] and any("residual" in r for r in res["reject_reasons"]),
      f"residual={res['residual_deg']:.2f} deg")

print(f"\n{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
