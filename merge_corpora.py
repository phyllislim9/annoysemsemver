#!/usr/bin/env python3
"""merge_corpora.py PRIMARY.json FALLBACK.json OUT.json
Takes every controller/method from PRIMARY (e.g. the DeepSeek run); methods PRIMARY lacks (or has empty) come from FALLBACK (e.g. --rnd).
Prints how many methods came from each, and the fallback ones, so the paper can state it. Writes OUT.json plus OUT.fallback.txt."""
import json, sys
p, f, out = sys.argv[1:4]
P = json.load(open(p)); F = json.load(open(f))
M, fb, n_p = {}, [], 0
for c in sorted(set(P) | set(F)):
    for k in sorted(set(P.get(c, {})) | set(F.get(c, {}))):
        if P.get(c, {}).get(k):
            M.setdefault(c, {})[k] = P[c][k]; n_p += 1
        elif F.get(c, {}).get(k):
            M.setdefault(c, {})[k] = F[c][k]; fb.append(f"{c}.{k}")
json.dump(M, open(out, "w"), indent=1)
open(out.rsplit(".", 1)[0] + ".fallback.txt", "w").write("\n".join(fb) + "\n")
print(f"methods: {n_p + len(fb)}  from primary: {n_p}  from fallback: {len(fb)}")
for x in fb: print("  fallback:", x)