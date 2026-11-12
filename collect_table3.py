#!/usr/bin/env python3
"""collect_table3.py results/*.csv  -> Table 3 rows (correct/missed/conservative per category) as text and LaTeX.
Input: CSVs written by ssvchk_variants.py --csv (columns: variant, expected, verdict, mapped).
Expected level comes from the class number in the variant path (class_01 ... class_12), using the paper's cases:
 1 refactor | 2 patch | 3,4 crash | 5,6 minor | 7-12 major.   Verdict is the checker's five-level output.
Order of severity: REFACTOR < CRASH < PATCH < MINOR < MAJOR.
 correct = verdict == expected; missed = verdict LESS severe than expected; conservative = MORE severe.
Variants that did not build (COMPILE_FAIL) or errored are counted separately and never as correct."""
import csv, os, re, sys, collections
ORDER = ["REFACTOR", "CRASH", "PATCH", "MINOR", "MAJOR"]
EXP = {1: "REFACTOR", 2: "PATCH", 3: "CRASH", 4: "CRASH", 5: "MINOR", 6: "MINOR", 7: "MAJOR", 8: "MAJOR", 9: "MAJOR", 10: "MAJOR", 11: "MAJOR", 12: "MAJOR"}
V = {"CRASH_FIX": "CRASH"}
rows = {}
for f in sys.argv[1:]:
    b = os.path.splitext(os.path.basename(f))[0]
    c = {lv: [0, 0, 0] for lv in ORDER}; bad = 0; n = 0
    for r in csv.DictReader(open(f)):
        m = re.search(r"class_?0*(\d+)", r["variant"], re.I)
        if not m or int(m.group(1)) not in EXP: continue
        n += 1; e = EXP[int(m.group(1))]; v = V.get(r["verdict"], r["verdict"])
        if v not in ORDER: bad += 1; continue
        d = ORDER.index(v) - ORDER.index(e)
        c[e][0 if d == 0 else 1 if d < 0 else 2] += 1
    rows[b] = (c, bad, n)
hdr = ["MAJOR", "MINOR", "PATCH", "CRASH", "REFACTOR"]
print(f"{'service':30s}" + "".join(f"{h:>12s}" for h in hdr) + "   not-run  total")
tot = {lv: [0, 0, 0] for lv in ORDER}
for b, (c, bad, n) in rows.items():
    print(f"{b:30s}" + "".join(f"{'/'.join(map(str, c[h])):>12s}" for h in hdr) + f"   {bad:>5d}  {n:>5d}")
    for h in hdr:
        for i in range(3): tot[h][i] += c[h][i]
print(f"{'ALL':30s}" + "".join(f"{'/'.join(map(str, tot[h])):>12s}" for h in hdr))
print("\ncells are correct/missed/conservative. LaTeX rows:")
for b, (c, bad, n) in rows.items():
    print(b.replace("_", r"\_") + " & " + " & ".join("/".join(map(str, c[h])) for h in hdr) + r" \\")
