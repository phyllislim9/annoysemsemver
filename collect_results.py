#!/usr/bin/env python3
"""collect_results.py results/*.csv  -> per-benchmark accuracy, per-class breakdown, and confusion matrix (paper table).
CSV files come from: ssvchk_variants.py V1.java variants_dir --csv results/<benchmark>.csv"""
import csv, os, re, sys, collections
tot = collections.Counter(); ok = collections.Counter(); cls = collections.defaultdict(lambda: [0, 0]); conf = collections.Counter()
print(f"{'benchmark':28s} {'agree':>7s}  {'compile-fail':>12s}")
for f in sys.argv[1:]:
    b = os.path.splitext(os.path.basename(f))[0]; a = n = cf = 0
    for r in csv.DictReader(open(f)):
        if r["verdict"] == "COMPILE_FAIL": cf += 1; continue
        if not r["expected"]: continue
        n += 1; hit = r["expected"] == r["mapped"]; a += hit
        m = re.search(r"class_?0*(\d+)", r["variant"], re.I); k = int(m.group(1)) if m else 0
        cls[k][0] += hit; cls[k][1] += 1; conf[(r["expected"], r["mapped"])] += 1
    print(f"{b:28s} {a:>3d}/{n:<3d}  {cf:>12d}"); tot[b] = n; ok[b] = a
A, N = sum(ok.values()), sum(tot.values())
print(f"{'ALL':28s} {A:>3d}/{N:<3d}  ({100*A/max(N,1):.1f}%)")
print("\nper class (all benchmarks):")
for k in sorted(cls): print(f"  class {k:2d}: {cls[k][0]}/{cls[k][1]}")
print("\nconfusion (rows expected, cols got):")
L = ["Patch", "Minor", "Major"]
print("          " + "".join(f"{x:>7s}" for x in L))
for e in L: print(f"  {e:8s}" + "".join(f"{conf[(e, g)]:>7d}" for g in L))
