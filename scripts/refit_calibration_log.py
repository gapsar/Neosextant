"""Refit the affine calibration from the app's calibration shot log.

The calibration screen appends one JSON object per shot to
files/calibration_shots.jsonl. Pull it from the phone with:

    adb shell run-as io.github.gapsar.neosextant cat files/calibration_shots.jsonl > shots.jsonl

then run:

    python3 scripts/refit_calibration_log.py shots.jsonl [--session ID] [--max-misalignment 5]

Bursts are reduced exactly like the app does (unit mean of the kept shots'
raw and expected directions, mean magnitude), and the pairs go through the
same fit_affine_calibration as on the phone, gates included. Without
--session the most recent session in the file is used.
"""
import argparse, json, sys, types, os
from collections import OrderedDict
import numpy as np

SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "python")
# celestial_navigator imports these at module level; the fit needs neither.
t3 = types.ModuleType("tetra3")
class _T3:
    has_database = False; database_properties = {}
    def __init__(s, *a, **k): pass
t3.Tetra3 = _T3; t3.get_centroids_from_image = lambda *a, **k: []
sys.modules["tetra3"] = t3
sc = types.ModuleType("self_calibration")
sc.active_params = lambda: {"db_name": "x", "fov_estimate": 45.8, "fov_max_error": .68, "k1": 0., "k2": 0.}
sc.MATCH_PARAMS = {"pattern_checking_stars": 10, "match_radius": .005, "match_threshold": .006}
sc.record_sample = lambda *a, **k: 0; sc.maybe_start_calibration = lambda: False
sys.modules["self_calibration"] = sc
sys.path.insert(0, SRC)
import celestial_navigator as cn


def unit_mean(vs):
    m = np.sum(np.asarray(vs, dtype=float), axis=0)
    return m / np.linalg.norm(m)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("log")
    ap.add_argument("--session", type=int, help="session id (default: the last one)")
    ap.add_argument("--max-misalignment", type=float, default=None,
                    help="re-gate shots at this many degrees instead of the app's 'kept' flag")
    args = ap.parse_args()

    shots = [json.loads(l) for l in open(args.log) if l.strip()]
    if not shots:
        sys.exit("empty log")
    session = args.session if args.session is not None else shots[-1]["session"]
    shots = [s for s in shots if s["session"] == session]
    print(f"session {session}: {len(shots)} shots")

    bursts = OrderedDict()
    for s in shots:
        bursts.setdefault(s["burst"], []).append(s)

    pairs = []
    print(f"{'burst':>14} {'time':>12} {'kept':>5} {'misalign':>9} {'motion':>7}")
    for bid, group in bursts.items():
        if args.max_misalignment is None:
            kept = [s for s in group if s.get("kept")]
        else:
            kept = [s for s in group if s.get("misalignment_deg") is not None
                    and s["misalignment_deg"] <= args.max_misalignment and "exp" in s]
        usable = [s for s in kept if "raw_dir" in s]
        line = f"{bid:>14} {group[0]['time'][11:23]:>12} {len(kept)}/{len(group)}"
        if len(usable) < 3:
            print(line + "   (skipped: fewer than 3 usable shots)")
            continue
        g = [np.asarray(s["cal_g"]) / np.linalg.norm(s["cal_g"]) for s in usable]
        gm = unit_mean(g)
        motion = max(np.degrees(np.arccos(np.clip(v @ gm, -1, 1))) for v in g)
        mis = float(np.median([s["misalignment_deg"] for s in usable]))
        print(line + f" {mis:8.2f}° {motion:6.2f}°")
        if motion > 1.0:
            continue
        pairs.append({"meas": list(unit_mean([s["raw_dir"] for s in usable])),
                      "mag": float(np.mean([s["raw_mag"] for s in usable])),
                      "exp": list(unit_mean([s["exp"] for s in usable]))})

    print(f"\n{len(pairs)} pairs")
    fit = json.loads(cn.fit_affine_calibration(json.dumps(pairs)))
    for k in ("healthy", "reject_reasons", "n_pairs", "n_clusters", "attitude_spread_deg",
              "cond", "in_sample_rms_deg", "loco_rms_deg", "error"):
        if k in fit:
            print(f"  {k}: {fit[k]}")


if __name__ == "__main__":
    main()
