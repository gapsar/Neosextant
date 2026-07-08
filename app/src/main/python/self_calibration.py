# self_calibration.py
#
# On-device continuous calibration for the NeoSextant solver.
#
# The shipped solve parameters (see DEFAULTS) were calibrated for the Pixel 8a
# with astrometry.net ground truth on desktop. Other phones have different
# FOVs and lens distortion, so this module learns them on the device itself:
#
#   1. Every processed frame's centroids are recorded (record_sample). The
#      sidecar is tiny (~2 KB); a rolling window of MAX_SAMPLES is kept.
#   2. Once enough frames accumulate (and then every RECAL_INTERVAL frames), a
#      background daemon thread re-solves the stored centroids against a
#      LADDER of star databases covering common phone FOVs, using a VERY
#      STRICT match_threshold. Strict solves are self-verifying (tetra3's
#      mismatch probability is the referee), so they can be trusted as
#      pseudo-ground-truth without astrometry.net.
#   3. From the confident solves it freezes: the best ladder database, the
#      median solved FOV, and a two-term radial distortion fit (same
#      closed-form least squares the desktop optimizer uses).
#   4. A candidate calibration is only ADOPTED if it strict-solves at least
#      as many stored frames as the currently active one (guarded update).
#
# Deliberately NOT tuned on-device: match_threshold, match_radius, sigma.
# Loosening the acceptance gates without independent ground truth invites
# confidently-wrong fixes, which are worse than no fix at sea.
#
# No Kotlin/Java changes are required: celestial_navigator.py calls
# record_sample() + maybe_start_calibration() per frame and reads
# active_params() before each solve.

import json
import math
import os
import threading
import time
from pathlib import Path

import numpy as np
import tetra3

# ---------------------------------------------------------------------------
# Shipped defaults: Pixel 8a calibration (desktop optimizer v3 run,
# 2026-07-08, astrometry.net ground truth; field replay 116/126, 0 FP).
# active_params() returns these until an on-device calibration is adopted.
# ---------------------------------------------------------------------------
DEFAULTS = {
    "db_name": "db_calib_fov48.69_mag7.0.npz",
    "fov_estimate": 45.82680502099258,
    "fov_max_error": 0.6838895754335503,
    "k1": 0.017625943890428975,
    "k2": -0.09260975433568791,
}

# Match params shared by normal solving and calibration validation. These are
# fixed on-device (see module docstring).
MATCH_PARAMS = {
    "pattern_checking_stars": 10,
    "match_radius": 0.005579387610925111,
    "match_threshold": 0.006734320623386061,
}

# Database ladder: solve-time (portrait-width) FOVs covered by shipped
# databases. Keep in sync with scripts/generate_db_ladder.py.
LADDER_FOVS = [35.0, 37.5, 40.1, 42.9, 45.9, 49.1, 52.5, 56.2, 60.1, 64.3]


def ladder_db_name(fov):
    return "db_ladder_f" + f"{fov:.1f}".replace(".", "_") + ".npz"


# Calibration behaviour knobs
RECAL_INTERVAL = 50        # frames between recalibration passes
BOOTSTRAP_MIN_SAMPLES = 20  # frames needed before the first pass
MAX_SAMPLES = 200          # rolling sample window
BOOTSTRAP_WINDOW = 60      # newest samples used during a pass (CPU bound)
STRICT_THRESHOLD = 1e-5    # pseudo-truth gate: tetra3 mismatch probability
MIN_MATCHES = 10           # additional confidence gate on strict solves
MIN_CONFIDENT = 6          # confident solves required to freeze a calibration
MIN_DISTORTION_PAIRS = 150  # matched-star pairs required to fit k1/k2
CAL_SOLVE_TIMEOUT_MS = 1000
PROBE_SUBSET = 12          # samples used to cheaply screen a ladder rung
PROBE_MIN = 2              # probe solves needed before a full-window pass
FOV_LADDER_SEARCH_FRAC = 0.05   # fov_max_error during ladder probing
FOV_FROZEN_ERROR_FRAC = 0.015   # fov_max_error after freezing (Pixel 8a ratio)

_lock = threading.Lock()
_running = False
_cached_cal = None
_cached_mtime = None


def _storage_dir() -> Path:
    """App files dir on Android; env override for desktop testing."""
    env = os.environ.get("NEOSEXTANT_CALIB_DIR")
    if env:
        d = Path(env)
    else:
        try:
            from com.chaquo.python import Python
            ctx = Python.getPlatform().getApplication()
            d = Path(str(ctx.getFilesDir().getAbsolutePath())) / "selfcal"
        except Exception:
            d = Path.home() / ".neosextant_selfcal"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _samples_path() -> Path:
    return _storage_dir() / "samples.jsonl"


def _state_path() -> Path:
    return _storage_dir() / "calibration.json"


# ---------------------------------------------------------------------------
# Public API used by celestial_navigator.py
# ---------------------------------------------------------------------------

def active_params() -> dict:
    """Solve parameters to use right now: adopted calibration or DEFAULTS.
    Cached; re-read only when calibration.json changes on disk."""
    global _cached_cal, _cached_mtime
    p = _state_path()
    try:
        mtime = p.stat().st_mtime
    except OSError:
        _cached_cal, _cached_mtime = None, None
        return dict(DEFAULTS)
    if _cached_mtime != mtime:
        try:
            cal = json.loads(p.read_text())
            if cal.get("status") == "calibrated":
                _cached_cal = {k: cal[k] for k in DEFAULTS}
            else:
                _cached_cal = None
        except Exception:
            _cached_cal = None
        _cached_mtime = mtime
    return dict(_cached_cal) if _cached_cal else dict(DEFAULTS)


def record_sample(centroids, width, height) -> int:
    """Append one frame's raw (pre-undistortion) centroids to the rolling
    store. Returns the total number of samples recorded so far."""
    path = _samples_path()
    rec = {"t": time.time(), "w": int(width), "h": int(height),
           "c": [[float(y), float(x)] for y, x in centroids]}
    with _lock:
        with open(path, "a") as fh:
            fh.write(json.dumps(rec) + "\n")
        n = sum(1 for _ in open(path))
        if n > int(MAX_SAMPLES * 1.5):        # occasional trim, keep newest
            lines = open(path).readlines()[-MAX_SAMPLES:]
            tmp = path.with_suffix(".tmp")
            tmp.write_text("".join(lines))
            tmp.replace(path)
            n = len(lines)
    return n


def maybe_start_calibration() -> bool:
    """Spawn a background calibration pass if one is due. Returns True if a
    pass was started. Never blocks the caller's solve."""
    global _running
    with _lock:
        if _running:
            return False
        try:
            n = sum(1 for _ in open(_samples_path()))
        except OSError:
            return False
        state = {}
        try:
            state = json.loads(_state_path().read_text())
        except Exception:
            pass
        seen = int(state.get("samples_seen_at_last_run", 0))
        first_run = "status" not in state
        due = (n >= BOOTSTRAP_MIN_SAMPLES) if first_run \
            else (n - seen >= RECAL_INTERVAL)
        if not due:
            return False
        _running = True
    t = threading.Thread(target=_calibration_thread, daemon=True,
                         name="selfcal")
    t.start()
    return True


def calibration_status() -> dict:
    """Status snapshot (for UI/debugging)."""
    try:
        state = json.loads(_state_path().read_text())
    except Exception:
        state = {"status": "uncalibrated"}
    try:
        state["samples_recorded"] = sum(1 for _ in open(_samples_path()))
    except OSError:
        state["samples_recorded"] = 0
    state["running"] = _running
    return state


# ---------------------------------------------------------------------------
# Calibration pass
# ---------------------------------------------------------------------------

def _calibration_thread():
    global _running
    try:
        run_calibration()
    except Exception as e:  # never crash the app from the daemon thread
        print(f"Python selfcal: calibration pass failed: {e}")
    finally:
        with _lock:
            _running = False


def _load_samples():
    out = []
    try:
        for line in open(_samples_path()):
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    except OSError:
        pass
    return out[-BOOTSTRAP_WINDOW:]


def _strict_solve(t3, sample, fov, fov_err, k1=0.0, k2=0.0):
    """Solve one stored sample with the strict pseudo-truth gate. Returns the
    tetra3 solution dict or None."""
    w, h = sample["w"], sample["h"]
    cents = sample["c"][:30]
    if k1 or k2:
        cents = _undistort(cents, w, h, fov, k1, k2)
    try:
        sol = t3.solve_from_centroids(
            cents, (h, w),
            fov_estimate=fov, fov_max_error=fov_err,
            pattern_checking_stars=MATCH_PARAMS["pattern_checking_stars"],
            match_radius=MATCH_PARAMS["match_radius"],
            match_threshold=STRICT_THRESHOLD,
            solve_timeout=CAL_SOLVE_TIMEOUT_MS,
            distortion=0, return_matches=True)
    except Exception:
        return None
    if sol.get("RA") is None or int(sol.get("Matches") or 0) < MIN_MATCHES:
        return None
    return sol


def _undistort(cents, w, h, fov, k1, k2):
    f = (w / 2.0) / math.tan(math.radians(fov) / 2.0)
    cx, cy = w / 2.0, h / 2.0
    out = []
    for y, x in cents:
        xn, yn = (x - cx) / f, (y - cy) / f
        r2 = xn * xn + yn * yn
        d = 1.0 + k1 * r2 + k2 * r2 * r2
        out.append([cy + f * yn * d, cx + f * xn * d])
    return out


def _angular_sep_deg(ra1, dec1, ra2, dec2):
    r1, d1, r2, d2 = map(math.radians, (ra1, dec1, ra2, dec2))
    c = (math.sin(d1) * math.sin(d2) +
         math.cos(d1) * math.cos(d2) * math.cos(r1 - r2))
    return math.degrees(math.acos(max(-1.0, min(1.0, c))))


def _fit_distortion(solves_with_samples):
    """Closed-form two-term radial fit from matched stars of strict solves.
    Same maths as the desktop optimizer's Stage 3. Returns (k1, k2, n_pairs)."""
    r_obs_all, r_ideal_all = [], []
    for sample, sol in solves_with_samples:
        w, h = sample["w"], sample["h"]
        fov = float(sol.get("FOV") or 0)
        if not fov or not sol.get("matched_stars"):
            continue
        f = (w / 2.0) / math.tan(math.radians(fov) / 2.0)
        cx, cy = w / 2.0, h / 2.0
        ra0, dec0 = float(sol["RA"]), float(sol["Dec"])
        for star, cent in zip(sol["matched_stars"], sol["matched_centroids"]):
            sra, sdec = float(star[0]), float(star[1])
            cyy, cxx = float(cent[0]), float(cent[1])
            theta = math.radians(_angular_sep_deg(ra0, dec0, sra, sdec))
            r_obs_all.append(math.hypot(cxx - cx, cyy - cy) / f)
            r_ideal_all.append(math.tan(theta))
    n = len(r_obs_all)
    if n < MIN_DISTORTION_PAIRS:
        return 0.0, 0.0, n
    r_obs = np.asarray(r_obs_all)
    r_ideal = np.asarray(r_ideal_all)
    A = np.column_stack([r_obs ** 3, r_obs ** 5])
    coef, *_ = np.linalg.lstsq(A, r_ideal - r_obs, rcond=None)
    k1, k2 = float(coef[0]), float(coef[1])
    if abs(k1) > 0.5 or abs(k2) > 1.0:      # implausible fit, reject
        return 0.0, 0.0, n
    return k1, k2, n


def _score_config(samples, db_name, fov, fov_err, k1, k2):
    """Strict-solve count of a config over the samples (guarded adoption)."""
    try:
        t3 = tetra3.Tetra3(load_database=db_name)
    except Exception:
        return -1
    return sum(1 for s in samples
               if _strict_solve(t3, s, fov, fov_err, k1, k2) is not None)


def run_calibration() -> dict:
    """One full calibration pass (synchronous; normally run via the daemon
    thread). Returns the state dict that was written."""
    t0 = time.time()
    samples = _load_samples()
    n_total = 0
    try:
        n_total = sum(1 for _ in open(_samples_path()))
    except OSError:
        pass
    print(f"Python selfcal: pass starting on {len(samples)} samples")

    # --- Stage 1: ladder probe -> best database + frozen FOV --------------
    # Cost control: each rung is first screened on a small sample subset
    # (wrong rungs essentially never strict-solve); only promising rungs get
    # the full window. Once calibrated, only the rung nearest the active FOV
    # and its neighbours are probed — the full ladder re-runs only if they
    # stop solving (e.g. the phone/lens actually changed).
    active_fov = active_params()["fov_estimate"]
    order = sorted(range(len(LADDER_FOVS)),
                   key=lambda i: abs(LADDER_FOVS[i] - active_fov))
    already_calibrated = _read_state().get("status") == "calibrated"
    rungs = sorted(order[:3]) if already_calibrated else order

    def _probe_rung(fov):
        name = ladder_db_name(fov)
        try:
            t3 = tetra3.Tetra3(load_database=name)
        except Exception as e:
            print(f"Python selfcal: cannot load {name}: {e}")
            return None
        fov_err = fov * FOV_LADDER_SEARCH_FRAC
        probe_hits = sum(
            1 for s in samples[-PROBE_SUBSET:]
            if _strict_solve(t3, s, fov, fov_err) is not None)
        if probe_hits < PROBE_MIN:
            print(f"Python selfcal: ladder {name}: screened out "
                  f"({probe_hits}/{min(PROBE_SUBSET, len(samples))})")
            return None
        solves = []
        for s in samples:
            sol = _strict_solve(t3, s, fov, fov_err)
            if sol is not None:
                solves.append((s, sol))
        print(f"Python selfcal: ladder {name}: {len(solves)} confident solves")
        if not solves:
            return None
        mean_m = sum(int(x[1]["Matches"]) for x in solves) / len(solves)
        return (len(solves), mean_m, fov, solves)

    best = None   # (n_confident, mean_matches, fov_ladder, solves)
    for i in rungs:
        cand = _probe_rung(LADDER_FOVS[i])
        if cand and (best is None or (cand[0], cand[1]) > (best[0], best[1])):
            best = cand
    if (best is None or best[0] < MIN_CONFIDENT) and already_calibrated:
        # neighbourhood failed: the camera may have changed -> full ladder
        print("Python selfcal: neighbourhood rungs failed; probing full ladder")
        for i in order[3:]:
            cand = _probe_rung(LADDER_FOVS[i])
            if cand and (best is None or
                         (cand[0], cand[1]) > (best[0], best[1])):
                best = cand

    state = {"version": 1, "samples_seen_at_last_run": n_total,
             "updated": time.strftime("%Y-%m-%dT%H:%M:%S")}
    if best is None or best[0] < MIN_CONFIDENT:
        state["status"] = "insufficient"
        state["n_confident"] = 0 if best is None else best[0]
        _write_state(state)
        print("Python selfcal: not enough confident solves; keeping current "
              "calibration")
        return state

    n_conf, _, fov_ladder, solves = best
    db_name = ladder_db_name(fov_ladder)
    fovs = sorted(float(sol["FOV"]) for _, sol in solves)
    fov_frozen = fovs[len(fovs) // 2]
    fov_err = max(0.4, fov_frozen * FOV_FROZEN_ERROR_FRAC)

    # --- Stage 2: distortion fit ------------------------------------------
    k1, k2, n_pairs = _fit_distortion(solves)

    # --- Stage 3: guarded adoption ----------------------------------------
    cur = active_params()
    score_cur = _score_config(samples, cur["db_name"], cur["fov_estimate"],
                              cur["fov_max_error"], cur["k1"], cur["k2"])
    score_new = _score_config(samples, db_name, fov_frozen, fov_err, k1, k2)
    print(f"Python selfcal: candidate fov={fov_frozen:.2f} db={db_name} "
          f"k1={k1:.4g} k2={k2:.4g} ({n_pairs} pairs); strict score "
          f"{score_new} vs current {score_cur}")

    if score_new >= max(score_cur, MIN_CONFIDENT):
        state.update({"status": "calibrated", "db_name": db_name,
                      "fov_estimate": fov_frozen, "fov_max_error": fov_err,
                      "k1": k1, "k2": k2, "n_confident": n_conf,
                      "n_distortion_pairs": n_pairs,
                      "score_new": score_new, "score_previous": score_cur})
    else:
        state.update({"status": "rejected", "n_confident": n_conf,
                      "score_new": score_new, "score_previous": score_cur})
        # keep an adopted calibration from an earlier pass, if any
        prev = {}
        try:
            prev = json.loads(_state_path().read_text())
        except Exception:
            pass
        if prev.get("status") == "calibrated":
            state.update({k: prev[k] for k in DEFAULTS})
            state["status"] = "calibrated"
    _write_state(state)
    print(f"Python selfcal: pass finished in {time.time() - t0:.1f}s "
          f"-> {state['status']}")
    return state


def _read_state() -> dict:
    try:
        return json.loads(_state_path().read_text())
    except Exception:
        return {}


def _write_state(state: dict):
    p = _state_path()
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=2))
    tmp.replace(p)
