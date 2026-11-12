#!/usr/bin/env python3
import re, sys, os
fail, logdir = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "logs/D")
want = {}
for ln in open(fail):
    m = re.match(r"\s*(\S+) / (\S+) / (\S+?):\s*(\w+) -> (\w+)", ln)
    if m: want.setdefault(m.group(2), []).append((m.group(3), m.group(4), m.group(5)))
for ctl, vs in sorted(want.items()):
    path = os.path.join(logdir, ctl + ".log")
    if not os.path.exists(path): print(f"### {ctl}: no log {path}\n"); continue
    lines = open(path, errors="replace").read().splitlines()
    summ = [i for i, l in enumerate(lines) if re.match(r"\[\d+/\d+\] \S+\.java:", l)]
    for rel, exp, got in vs:
        idx = next((i for i in summ if lines[i].split("] ", 1)[1].startswith(rel + ":")), None)
        print("=" * 100); print(f"### {ctl} / {rel}: expected {exp}, reported {got}")
        if idx is None: print("   (variant not found in the log; was it run with --detail matching this class?)"); continue
        prev = max([i for i in summ if i < idx], default=-1)
        block = lines[prev + 1:idx + 1]
        for l in block[-80:]: print("  " + l[:300])
        print()