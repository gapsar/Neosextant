"""Regression suite for self_calibration.py scheduling and state handling.

Runs on a desktop; stubs tetra3, uses a temporary storage dir, and replaces
the expensive solve pass where only the bookkeeping is under test. Usage:

    python3 scripts/selfcal_regression.py [path/to/python/dir]

  A) a pass is started once the solver goes quiet, and never during a burst
  B) recalibration still becomes due after the sample file has been trimmed
  C) a pass that finds too few confident solves keeps an adopted calibration
"""
import sys, types, os, json, time, tempfile, importlib

SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "python")
t3 = types.ModuleType("tetra3")
class _T3:
    def __init__(s, *a, **k): pass
    def solve_from_centroids(s, *a, **k): return {"RA": None}
t3.Tetra3 = _T3
sys.modules["tetra3"] = t3
sys.path.insert(0, SRC)

def fresh_module():
    """A clean import of self_calibration over an empty storage dir."""
    os.environ["NEOSEXTANT_CALIB_DIR"] = tempfile.mkdtemp(prefix="selfcal_")
    sys.modules.pop("self_calibration", None)
    return importlib.import_module("self_calibration")

CENTS = [[10.0, 20.0], [30.0, 40.0]]
results = []
def check(name, ok, detail=""):
    results.append(ok)
    print(f"{name}: {'ok' if ok else 'FAILED'} {detail}")

# --- A: quiet-period scheduling ---
sc = fresh_module()
sc.QUIET_PERIOD_S = 0.3
sc.BOOTSTRAP_MIN_SAMPLES = 3
runs = []
sc.run_calibration = lambda: runs.append(time.time())
for _ in range(3):
    sc.record_sample(CENTS, 100, 200)
scheduled = sc.maybe_start_calibration()
check("A1 pass scheduled once due", scheduled)
check("A2 not started while frames are arriving", not runs)
check("A3 no second pass while one is pending", not sc.maybe_start_calibration())
for _ in range(3):                        # the rest of the burst
    time.sleep(0.15)
    sc.record_sample(CENTS, 100, 200)
    check("A4 still waiting mid-burst", not runs)
last_frame = time.time()
time.sleep(0.6)
check("A5 started after the quiet period", len(runs) == 1,
      f"(runs={len(runs)})")
check("A6 waited for the LAST frame, not the first",
      bool(runs) and runs[0] - last_frame >= sc.QUIET_PERIOD_S - 0.02)

# --- B: due-ness survives trimming ---
sc = fresh_module()
sc.QUIET_PERIOD_S = 0.0
sc.MAX_SAMPLES = 10                       # trims at 16 lines back to 10
sc.RECAL_INTERVAL = 8
runs = []
sc.run_calibration = lambda: runs.append(1)
n = 0
for _ in range(14):
    n = sc.record_sample(CENTS, 100, 200)
sc._write_state({"status": "calibrated", "samples_seen_at_last_run": n,
                 **sc.DEFAULTS})
due_early = sc.maybe_start_calibration()
for _ in range(sc.RECAL_INTERVAL):       # crosses a trim on the way
    sc.record_sample(CENTS, 100, 200)
lines = sc._count_lines(sc._samples_path())
due_after = sc.maybe_start_calibration()
check("B1 not due before RECAL_INTERVAL new samples", not due_early)
check("B2 file was trimmed", lines < n + sc.RECAL_INTERVAL, f"(lines={lines})")
check("B3 due after RECAL_INTERVAL new samples despite the trim", due_after)

# --- C: a failed pass keeps the adopted calibration ---
sc = fresh_module()
adopted = {"db_name": "db_ladder_f52_5.npz", "fov_estimate": 52.1,
           "fov_max_error": 0.78, "k1": 0.01, "k2": -0.05}
sc._write_state({"status": "calibrated", "samples_seen_at_last_run": 0,
                 **adopted})
for _ in range(5):                        # nothing here will strict-solve
    sc.record_sample(CENTS, 100, 200)
state = sc.run_calibration()
active = sc.active_params()
check("C1 pass reported insufficient", state.get("last_pass") == "insufficient",
      f"(last_pass={state.get('last_pass')})")
check("C2 adopted calibration still active",
      all(active[k] == adopted[k] for k in adopted), f"(active={active})")

# a pass with no earlier calibration still reports its own outcome
sc = fresh_module()
for _ in range(5):
    sc.record_sample(CENTS, 100, 200)
state = sc.run_calibration()
check("C3 uncalibrated phone stays on defaults",
      state.get("status") == "insufficient" and sc.active_params() == sc.DEFAULTS)

print(f"\n{'PASS' if all(results) else 'FAIL'}")
