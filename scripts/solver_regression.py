"""Solver regression suite for celestial_navigator.py.

Runs on a desktop with astropy installed; stubs out tetra3 and the Android
bits, so it exercises the real solver maths without a device or a star
database. Usage:

    python3 scripts/solver_regression.py [path/to/python/dir]

  A) solve_oneshot recovers a known observer from a perfect synthetic solve
  B) calibrate -> apply -> solve round trip returns to truth
  C) _median_fix / _lsq_position_uncertainty behave sanely
"""
import sys, types, json, numpy as np
import os
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "python")
t3=types.ModuleType("tetra3")
class _T3:
    has_database=False; database_properties={}
    def __init__(s,*a,**k): pass
t3.Tetra3=_T3; t3.get_centroids_from_image=lambda *a,**k:[]
sys.modules["tetra3"]=t3
sc=types.ModuleType("self_calibration")
sc.active_params=lambda:{"db_name":"x","fov_estimate":45.8,"fov_max_error":.68,"k1":0.,"k2":0.}
sc.MATCH_PARAMS={"pattern_checking_stars":10,"match_radius":.005,"match_threshold":.006}
sc.record_sample=lambda *a,**k:0; sc.maybe_start_calibration=lambda:False
sys.modules["self_calibration"]=sc
sys.path.insert(0,SRC)
import celestial_navigator as cn, erfa
# celestial_navigator installs a fresher IERS table on a background thread.
# Let it land before computing truth, or ERA shifts by ~1.4 arcsec mid-test and
# the harness measures its own race rather than the solver.
import time as _t
for _ in range(60):
    if cn.IERS_STATUS != "bundled": break
    _t.sleep(0.25)
_t.sleep(1.0)
from astropy.time import Time
from astropy.utils import iers
iers.conf.auto_download=False; iers.conf.auto_max_age=None; iers.conf.iers_degraded_accuracy='ignore'

def build_R(ra,dec,roll):
    ra,dec,roll=map(np.radians,(ra,dec,roll))
    cra,sra,cdec,sdec,cr,sr=np.cos(ra),np.sin(ra),np.cos(dec),np.sin(dec),np.cos(roll),np.sin(roll)
    b=np.array([cdec*cra,cdec*sra,sdec]); E=np.array([-sra,cra,0.]); N=np.array([-sdec*cra,-sdec*sra,cdec])
    return np.array([b, cr*E+sr*N, -sr*E+cr*N])
def extract(R):
    return (np.degrees(np.arctan2(R[0,1],R[0,0]))%360,
            np.degrees(np.arctan2(R[0,2],np.linalg.norm(R[1:3,2]))),
            np.degrees(np.arctan2(R[1,2],R[2,2]))%360)
def applyOffsets(g,p,r):
    x,y,z=g
    rr=np.radians(r); cr,sr=np.cos(rr),np.sin(rr); x,y=x*cr+y*sr,-x*sr+y*cr
    pp=np.radians(p); cp,sp=np.cos(pp),np.sin(pp); y,z=y*cp-z*sp,y*sp+z*cp
    return np.array([x,y,z])

tlat,tlon=-21.001443,55.278543
rng=np.random.default_rng(11)

# --- A: perfect solve ---
errsA=[]
for stamp in ["2026-09-19T22:00:00","2027-03-02T04:30:00"]:
    t=Time(stamp); era=t.earth_rotation_angle('tio').deg; M=erfa.c2i06a(t.tt.jd1,t.tt.jd2)
    for lat0,lon0 in [(-35,-140),(0,0),(48,5)]:
        la,lo=np.radians([lat0,lon0]); th=np.radians(era)
        z_itrs=np.array([np.cos(la)*np.cos(lo),np.cos(la)*np.sin(lo),np.sin(la)])
        z_cirs=np.array([[np.cos(th),-np.sin(th),0],[np.sin(th),np.cos(th),0],[0,0,1]])@z_itrs
        z=M.T@z_cirs
        for _ in range(500):
            Q,_=np.linalg.qr(rng.normal(size=(3,3)))
            if np.linalg.det(Q)<0: Q[2]*=-1
            alt=np.degrees(np.arcsin(np.clip(Q[0]@z,-1,1)))
            if not (25<alt<75): continue
            ra,dec,roll=extract(Q); v=Q@z
            # no refraction injected -> disable it by passing the vector through untouched
            gx,gy,gz=-v[1],v[2],-v[0]
            r=json.loads(cn.solve_oneshot(ra,dec,roll,gx,gy,gz,stamp,"",0.0,15.0))
            errsA.append(np.hypot((r["fixed_latitude"]-lat0)*60,
                        ((r["fixed_longitude"]-lon0+180)%360-180)*60*np.cos(la)))
            break
print(f"A) solve_oneshot vs truth (P=0, no refraction): max {max(errsA):.4f} nmi  [expect ~0]")

# --- B: round trip ---
stamp="2026-09-19T22:00:00"
t=Time(stamp); era=t.earth_rotation_angle('tio').deg; M=erfa.c2i06a(t.tt.jd1,t.tt.jd2)
la,lo=np.radians([tlat,(tlon+era)%360])
z_cirs=np.array([np.cos(la)*np.cos(lo),np.cos(la)*np.sin(lo),np.sin(la)])
z_icrs=M.T@z_cirs
errsB=[];found=0
for _ in range(4000):
    Q,_=np.linalg.qr(rng.normal(size=(3,3)))
    if np.linalg.det(Q)<0: Q[2]*=-1
    v=Q@z_icrs; alt=np.degrees(np.arcsin(np.clip(v[0],-1,1)))
    if not (30<alt<65): continue
    ra,dec,roll=extract(Q)
    g_true=np.array([-v[1],v[2],-v[0]])
    tp,tr=0.5332,-0.6995
    g_meas=applyOffsets(g_true,-tp,-tr)
    c=json.loads(cn.solve_calibration_offsets(ra,dec,roll,*g_meas,tlat,tlon,stamp,1000.0,20.0))
    if c.get("error"): continue
    g_corr=applyOffsets(g_meas,c["pitch_offset_deg"],c["roll_offset_deg"])
    f=json.loads(cn.solve_oneshot(ra,dec,roll,*g_corr,stamp,"",1000.0,20.0))
    errsB.append(np.hypot((f["fixed_latitude"]-tlat)*60,
                ((f["fixed_longitude"]-tlon+180)%360-180)*60*np.cos(la)))
    found+=1
    if found>=6: break
print(f"B) calibrate->solve round trip: mean {np.mean(errsB):.4f} max {np.max(errsB):.4f} nmi  [expect <0.1]")

# --- C: statistics ---
_,_,r1=cn._median_fix([1.0],[2.0])
_,_,r7=cn._median_fix(list(rng.normal(0,2/60,7)),list(rng.normal(0,2/60,7)))
u_good=cn._lsq_position_uncertainty([10.,130.,250.],[1.,-1.,.5])
u_bad=cn._lsq_position_uncertainty([10.,20.,30.],[1.,-1.,.5])
print(f"C) n=1 -> {r1} (expect nan), n=7 -> {r7:.2f} nmi")
print(f"   geometry: spread azimuths {u_good:.2f} nmi vs clustered {u_bad:.2f} nmi  [bad must be larger]")
ok = max(errsA)<0.01 and np.max(errsB)<0.1 and r1!=r1 and u_bad>u_good
print(f"\n{'PASS' if ok else 'FAIL'}")
