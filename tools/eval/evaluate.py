"""Offline evaluation of NeoSextant fixes against a true position.

Reads a History export (files/exports/history_*.json, from the Export button)
and, optionally, the sensor CSVs (files/sensor_logs/). Recomputes every fix
with the app's own solver (celestial_navigator.py, imported, not copied) and
prints:

  1. reproduction: the recomputed fix against the fix the app showed
  2. error against the true position: per entry, per burst, per shot
  3. shot error against battery temperature, when the shots recorded it
  4. which sensor CSV covers each shot's exposure

Usage:
    python3 tools/eval/evaluate.py EXPORT.json [--sensor-logs DIR]
        [--truth LAT LON] [--since YYYY-MM-DD] [--max-truth-gap-s S]

The truth is the one typed for each entry, or --truth for all entries (a
session ashore). Needs numpy, astropy and pyerfa; never uses the network.
"""
import argparse, contextlib, glob, io, json, os, sys, types
import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "app", "src", "main", "python"))
sys.modules.setdefault("tetra3", types.ModuleType("tetra3"))  # plate solving is not redone
# The import starts an IERS refresh thread; keep it off the network.
from astropy.utils.data import conf as astropy_data_conf
astropy_data_conf.allow_internet = False
with contextlib.redirect_stdout(io.StringIO()):
    import celestial_navigator as cn

EARTH_RADIUS_NM = 3440.065


def distance_nm(lat1, lon1, lat2, lon2):
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dp, dl = p2 - p1, np.radians(lon2 - lon1)
    h = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return float(2 * EARTH_RADIUS_NM * np.arcsin(np.sqrt(min(max(h, 0.0), 1.0))))


def utc_seconds(iso):
    """Seconds since the epoch of a shot or truth timestamp (UTC, no zone)."""
    return np.datetime64(iso.rstrip("Z"), "ms").astype("int64") / 1000.0


def stats(values):
    v = np.asarray(values, dtype=float)
    if v.size == 0:
        return "n=0"
    return (f"n={v.size} median={np.median(v):.2f} p95={np.percentile(v, 95):.2f} "
            f"max={v.max():.2f} NM")


def solve_shot(shot, pressure, temperature):
    """The app's per-shot fix, or None. Same inputs as FixSolveTrigger sends."""
    g = shot["gravityVector"]
    with contextlib.redirect_stdout(io.StringIO()):
        r = json.loads(cn.solve_oneshot(shot["raDeg"], shot["decDeg"], shot.get("rollDeg", 0.0),
                                        g["x"], g["y"], g["z"], shot["timestamp"], "",
                                        pressure, temperature))
    if r.get("error"):
        return None
    return r["fixed_latitude"], r["fixed_longitude"]


def usable(shot):
    # FixSolveTrigger sends only solved shots that have a sensor altitude.
    return shot.get("solved") and "measuredHeight" in shot and "raDeg" in shot


def load_csv_spans(directory):
    """(name, first event ns, last event ns) for each sensor CSV with event times."""
    spans = []
    for path in sorted(glob.glob(os.path.join(directory, "*.csv"))):
        with open(path) as f:
            header = f.readline().strip().split(",")
            if "Event_Time_ns" not in header:
                continue
            col = header.index("Event_Time_ns")
            first = f.readline().split(",")
            last = first
            # The file can be tens of MB: read only its tail for the last row.
            f.seek(0, os.SEEK_END)
            f.seek(max(0, f.tell() - 4096))
            tail = f.read().strip().splitlines()
            if tail:
                last = tail[-1].split(",")
        try:
            spans.append((os.path.basename(path), int(first[col]), int(last[col])))
        except (ValueError, IndexError):
            continue
    return spans


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("export")
    ap.add_argument("--sensor-logs")
    ap.add_argument("--truth", nargs=2, type=float, metavar=("LAT", "LON"))
    ap.add_argument("--since", help="only entries saved on or after this date (YYYY-MM-DD)")
    ap.add_argument("--max-truth-gap-s", type=float, default=300.0,
                    help="ignore a typed truth read more than this long from the shots")
    args = ap.parse_args()

    with open(args.export) as f:
        export = json.load(f)
    entries = [e for e in export["entries"]
               if not args.since or e["savedLocal"][:10] >= args.since]
    entries.sort(key=lambda e: e["id"])
    print(f"{args.export}: {len(entries)} entries ({export.get('device', '?')})\n")

    repro_nm, entry_err, burst_err, shot_err = [], [], [], []
    temp_points = []  # (battery deg C, shot error NM)
    shots_with_window = []

    print("entry            saved                shots  repro   error  truth")
    for e in entries:
        groups, shot_fixes, first_time = [], [], None
        for img in e["images"]:
            model = img.get("sensorModel", {})
            pressure = model.get("pressure_hpa", 1013.25)
            temperature = model.get("temperature_c", 15.0)
            fixes = []
            for shot in img.get("burstSubResults", []):
                if not usable(shot):
                    continue
                first_time = first_time or shot["timestamp"]
                fix = solve_shot(shot, pressure, temperature)
                if fix is None:
                    continue
                fixes.append(fix)
                shot_fixes.append((shot, fix))
                rec = shot.get("sensorRecord")
                if rec and "exposureStartNs" in rec:
                    shots_with_window.append((e["id"], rec["exposureStartNs"], rec["exposureEndNs"]))
            if fixes:
                groups.append(fixes)
        if not groups:
            print(f"{e['id']}  {e['savedLocal']}  no usable shot")
            continue

        # Same two levels of median as solve_oneshot_burst_multi.
        slot = [cn._median_fix([f[0] for f in g], [f[1] for f in g])[:2] for g in groups]
        lat, lon = cn._median_fix([s[0] for s in slot], [s[1] for s in slot])[:2]
        repro = distance_nm(lat, lon, e["latitude"], e["longitude"])
        repro_nm.append(repro)

        truth, truth_note = None, ""
        if args.truth:
            truth = tuple(args.truth)
        elif e.get("truthLatitude") is not None:
            gap = (utc_seconds(e["truthTimeUtc"]) - utc_seconds(first_time)
                   if e.get("truthTimeUtc") and first_time else 0.0)
            if abs(gap) <= args.max_truth_gap_s:
                truth = (e["truthLatitude"], e["truthLongitude"])
                truth_note = f"gap {gap:+.0f} s"
            else:
                truth_note = f"ignored, gap {gap:+.0f} s"

        err_txt = "      -"
        if truth:
            err = distance_nm(e["latitude"], e["longitude"], *truth)
            entry_err.append(err)
            err_txt = f"{err:7.2f}"
            burst_err += [distance_nm(s[0], s[1], *truth) for s in slot]
            for shot, fix in shot_fixes:
                se = distance_nm(fix[0], fix[1], *truth)
                shot_err.append(se)
                t = (shot.get("sensorRecord") or {}).get("batteryTempC")
                if t is not None:
                    temp_points.append((t, se))
        print(f"{e['id']}  {e['savedLocal']}  {len(shot_fixes):5d}  {repro:5.2f} {err_txt}  {truth_note}")

    print("\n1. Reproduction (recomputed fix vs fix shown by the app)")
    print("   " + stats(repro_nm))
    print("   Small differences come from the IERS table and, for entries older than")
    print("   the shot record, the pressure and temperature (default 1013.25 hPa, 15 C).")

    print("\n2. Error against the true position")
    print("   per entry: " + stats(entry_err))
    print("   per burst: " + stats(burst_err))
    print("   per shot:  " + stats(shot_err))

    print("\n3. Shot error against battery temperature")
    if len(temp_points) >= 3:
        t, err = np.array(temp_points).T
        slope = np.polyfit(t, err, 1)[0] if np.ptp(t) > 0 else float("nan")
        print(f"   {len(t)} shots, {t.min():.1f} to {t.max():.1f} C, slope {slope:+.2f} NM/C")
    else:
        print("   not enough shots with a temperature and a truth")

    print("\n4. Sensor CSV coverage of the shot exposures")
    if not args.sensor_logs:
        print("   no --sensor-logs given")
    else:
        spans = load_csv_spans(args.sensor_logs)
        covered = sum(any(a <= s and t <= b for _, a, b in spans)
                      for _, s, t in shots_with_window)
        print(f"   {len(spans)} CSVs with event times; {covered}/{len(shots_with_window)} "
              f"shots with an exposure window are inside one")


if __name__ == "__main__":
    main()
