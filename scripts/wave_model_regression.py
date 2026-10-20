"""Regression suite for celestial_navigator.build_wave_model.

Runs on a desktop with synthetic accelerometer data; stubs tetra3. Usage:

    python3 scripts/wave_model_regression.py [path/to/python/dir]

  A) a +-2 deg roll at 7 s, with noise and a jittery 50 Hz rate, gives 7 s
     (the old angle-to-the-mean analysis gave half the period)
  B) a roll and a pitch at 11 s on different axes give 11 s
  C) a phone tilted 40 deg off vertical, with heave, still gives the period
  D) the real duration is used: 45 s of data, not the assumed 30 s
  E) too short a recording is refused
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

G = 9.81
rng = np.random.default_rng(1)
results = []
def check(name, ok, detail=""):
    results.append(ok)
    print(f"{name}: {'ok' if ok else 'FAILED'} {detail}")

def times(duration_s, rate_hz=50.0, jitter=0.3):
    """Sample times with a jittery interval, as Android delivers them."""
    dt = (1.0 + jitter * (rng.random(int(duration_s * rate_hz * 1.2)) - 0.5)) / rate_hz
    t = np.cumsum(dt)
    return t[t <= duration_s]

def samples(t, roll_deg, pitch_deg, base_tilt_deg=0.0, heave=0.0, noise=0.05):
    """Accelerometer rows [t, x, y, z] for a phone tilted by the wave."""
    r = np.radians(roll_deg); p = np.radians(pitch_deg); b = np.radians(base_tilt_deg)
    up = np.column_stack((np.sin(r), np.sin(p), np.cos(r) * np.cos(p)))
    # A fixed tilt of the phone about its x axis.
    up = up @ np.array([[1, 0, 0], [0, np.cos(b), -np.sin(b)], [0, np.sin(b), np.cos(b)]]).T
    acc = up * (G + heave)[:, None] if np.ndim(heave) else up * (G + heave)
    acc = acc + noise * rng.standard_normal(acc.shape)
    return np.column_stack((t, acc)).tolist()

def run(rows):
    return json.loads(cn.build_wave_model(json.dumps(rows)))

# --- A: one axis, 7 s ---
t = times(30.0)
res = run(samples(t, 2.0 * np.sin(2 * np.pi * t / 7.0), 0.0 * t))
check("A 7 s roll", res.get("success") and abs(res["wave_period_sec"] - 7.0) <= 0.3,
      f"period={res.get('wave_period_sec')} amp={res.get('wave_amplitude_deg')}")
check("A amplitude", res.get("success") and 1.6 <= res["wave_amplitude_deg"] <= 2.4,
      f"amp={res.get('wave_amplitude_deg')}")

# --- B: two axes, 11 s ---
t = times(40.0)
res = run(samples(t, 3.0 * np.sin(2 * np.pi * t / 11.0),
                  1.5 * np.sin(2 * np.pi * t / 11.0 + 1.0)))
check("B 11 s roll+pitch", res.get("success") and abs(res["wave_period_sec"] - 11.0) <= 0.5,
      f"period={res.get('wave_period_sec')}")

# --- C: phone tilted 40 deg, heave at the same period ---
t = times(30.0)
res = run(samples(t, 2.0 * np.sin(2 * np.pi * t / 6.0), 0.0 * t, base_tilt_deg=40.0,
                  heave=0.3 * np.sin(2 * np.pi * t / 6.0)))
g = np.array(res.get("true_gravity", [0, 0, 0]))
expected = np.array([0.0, -np.sin(np.radians(40.0)), np.cos(np.radians(40.0))])
check("C tilted phone period", res.get("success") and abs(res["wave_period_sec"] - 6.0) <= 0.3,
      f"period={res.get('wave_period_sec')}")
check("C mean up", np.degrees(np.arccos(np.clip(g @ expected, -1, 1))) < 0.3,
      f"error={np.degrees(np.arccos(np.clip(g @ expected, -1, 1))):.2f} deg")

# --- D: 45 s at 25 Hz, 9 s period ---
t = times(45.0, rate_hz=25.0)
res = run(samples(t, 2.0 * np.sin(2 * np.pi * t / 9.0), 0.0 * t))
check("D real duration", res.get("success") and abs(res["wave_period_sec"] - 9.0) <= 0.4
      and abs(res["duration_sec"] - 45.0) < 0.5,
      f"period={res.get('wave_period_sec')} duration={res.get('duration_sec')}")

# --- E: too short ---
t = times(5.0)
res = run(samples(t, 2.0 * np.sin(2 * np.pi * t / 7.0), 0.0 * t))
check("E short refused", res.get("success") is False, res.get("error", ""))

print(f"\n{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
