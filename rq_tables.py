#!/usr/bin/env python3
"""rq_tables.py -- RQ1/RQ2 tables from per-variant CSVs (variant,expected,verdict,mapped).
usage: python3 rq_tables.py results_A=results results_B=results_B results_C=results_C [--time timings.csv ...]
Five-level expected label comes from the class number: 1 Refactor, 2 Patch, 3-4 Crash, 5-6 Minor, 7-12 Major.
Severity order: Major > Minor > Patch > Crash > Refactor. Lower than expected = MISSED, higher = CONSERVATIVE.
"""
import csv, glob, os, re, sys, collections
EXP = {1:"Refactor",2:"Patch",3:"Crash",4:"Crash",5:"Minor",6:"Minor",**{i:"Major" for i in range(7,13)}}
SEV = {"Refactor":0,"Crash":1,"Patch":2,"Minor":3,"Major":4}
NORM = {"MAJOR":"Major","MINOR":"Minor","PATCH":"Patch","CRASH_FIX":"Crash","CRASH":"Crash","REFACTOR":"Refactor"}
LV = ["Major","Minor","Patch","Crash","Refactor"]

def load(root):
    rows = []
    for f in sorted(glob.glob(os.path.join(root, "*", "*.csv"))):
        svc, ctl = os.path.basename(os.path.dirname(f)), os.path.basename(f)[:-4]
        for r in csv.DictReader(open(f)):
            m = re.match(r"class_(\d+)", r["variant"])
            if not m: continue
            exp = EXP[int(m.group(1))]; got = NORM.get(r["verdict"].strip().upper(), r["verdict"])
            d = "correct" if got == exp else ("missed" if SEV.get(got,0) < SEV[exp] else "conservative")
            rows.append(dict(svc=svc, ctl=ctl, v=r["variant"], exp=exp, got=got, d=d))
    return rows

for arg in sys.argv[1:]:
    if "=" not in arg: continue
    name, root = arg.split("=", 1); R = load(root)
    if not R: print(f"{name}: no CSVs in {root}\n"); continue
    n = len(R); ok = sum(r["d"]=="correct" for r in R)
    print(f"=== {name}  ({root}): {ok}/{n} = {100*ok/n:.1f}% strict, {len({r['ctl'] for r in R})} controllers")
    print(f"{'expected':10}{'n':>6}{'correct':>9}{'missed':>8}{'conserv':>9}{'%':>7}")
    for L in LV:
        s = [r for r in R if r["exp"]==L]
        if s: print(f"{L:10}{len(s):>6}{sum(r['d']=='correct' for r in s):>9}{sum(r['d']=='missed' for r in s):>8}{sum(r['d']=='conservative' for r in s):>9}{100*sum(r['d']=='correct' for r in s)/len(s):>7.1f}")
    print("expected (rows) vs reported (columns); diagonal = pass")
    print(f"{'':10}" + "".join(f"{L:>9}" for L in LV) + f"{'n':>6}")
    for E in LV:
        s_ = [r for r in R if r["exp"]==E]
        if s_: print(f"{E:10}" + "".join(f"{sum(r['got']==G for r in s_):>9}" for G in LV) + f"{len(s_):>6}")
    print("failing variants (service / controller / variant: expected -> reported):")
    for r in sorted((r for r in R if r["d"]!="correct"), key=lambda r:(SEV[r["exp"]]*-1, r["svc"], r["ctl"], r["v"])):
        print(f"  {r['svc']} / {r['ctl']} / {r['v']}: {r['exp']} -> {r['got']}")
    print("errors by service:")
    c = collections.Counter((r["svc"], r["exp"], r["d"]) for r in R if r["d"]!="correct")
    for (s,e,d),k in sorted(c.items()): print(f"  {s:28}{d:13}{e:9}{k}")
    print()

# cell-by-cell A vs C (first two named roots)
named = [a.split("=",1) for a in sys.argv[1:] if "=" in a and not a.startswith("--")]
if len(named) >= 2:
    A = {(r["ctl"], r["v"]): r for r in load(named[0][1])}
    for nm, root in named[1:]:
        B = {(r["ctl"], r["v"]): r for r in load(root)}
        common = set(A) & set(B)
        fixed = [k for k in common if A[k]["d"]!="correct" and B[k]["d"]=="correct"]
        broke = [k for k in common if A[k]["d"]=="correct" and B[k]["d"]!="correct"]
        chg = [k for k in common if A[k]["got"]!=B[k]["got"]]
        print(f"{named[0][0]} -> {nm}: {len(common)} common cells; fixed {len(fixed)}, broken {len(broke)}, verdict changed {len(chg)}; only in {nm}: {len(set(B)-set(A))}")
        for k in sorted(fixed): print("  FIXED ", k, A[k]["got"], "->", B[k]["got"], "expected", A[k]["exp"])
        for k in sorted(broke): print("  BROKEN", k, A[k]["got"], "->", B[k]["got"], "expected", A[k]["exp"])