#!/usr/bin/env python3
"""tgen_to_corpus.py -- turn Tecton (tgen) test files into an input corpus.

Inputs
  --runs  tgen_runs.tsv   written by run_tgen.sh (service, controller, function, test dir)
  --plan  plan.json       written by tgen_plan.py
Each test dir holds test_N.json, e.g.
  {"item": {"kind": 2, "value": {"productId": "648", "quantity": 43}}}

Output corpus.json
  { "<Controller>": { "<javaMethod>/<arity>": [ [arg1, arg2, ...], ... ] } }
Arguments are positional, in spec-parameter order (framework parameters such
as Principal are not included; the harness supplies them).  A parameter that
is a KindedDTO<T> in Java is written {"kind": "T", "value": {...}}: the
numeric kind Tecton emits is replaced by the type name the Java code checks.
Identical argument lists are kept once.
"""
import argparse, json
from pathlib import Path


def normalize(arg, wrap_kind):
    is_kinded = isinstance(arg, dict) and 'kind' in arg and 'value' in arg
    if wrap_kind:
        inner = arg['value'] if is_kinded else arg
        return {'kind': wrap_kind, 'value': inner}
    return arg['value'] if is_kinded else arg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--runs', default='tgen_runs.tsv')
    ap.add_argument('--plan', default='tgen_out/plan.json')
    ap.add_argument('--out', default='corpus.json')
    a = ap.parse_args()

    plan = {(r['controller'], r['function']): r for r in json.load(open(a.plan))}
    corpus, stats, problems = {}, [], []
    for line in Path(a.runs).read_text().splitlines():
        if not line.strip():
            continue
        service, ctrl, fn, testdir = line.split('\t')
        rec = plan.get((ctrl, fn))
        if rec is None or rec.get('status') != 'OK':
            problems.append(f'{ctrl}.{fn}: not in plan / no Java match')
            continue
        names = [p['name'] for p in rec['spec_params']]
        wraps = {w['param']: w['kind'] for w in rec['java']['wrappers']}
        files = sorted(Path(testdir).glob('test_*.json'),
                       key=lambda p: int(p.stem.split('_')[1]))
        seen, args_lists = set(), []
        for f in files:
            obj = json.loads(f.read_text())
            if any(n not in obj for n in names):
                problems.append(f'{f}: missing parameter')
                continue
            args = [normalize(obj[n], wraps.get(n)) for n in names]
            key = json.dumps(args, sort_keys=True)
            if key not in seen:
                seen.add(key)
                args_lists.append(args)
        mkey = f"{rec['java']['java_method']}/{len(names)}"
        corpus.setdefault(ctrl, {}).setdefault(mkey, []).extend(args_lists)
        stats.append((ctrl, mkey, len(files), len(args_lists)))

    json.dump(corpus, open(a.out, 'w'), indent=1)
    print(f"{'controller':34}{'method/arity':28}{'files':>6}{'distinct':>9}")
    for c, m, n, d in stats:
        print(f'{c:34}{m:28}{n:>6}{d:>9}')
    if problems:
        print('\nproblems:')
        for p in problems:
            print('  ', p)
    print(f'\nwrote {a.out}')


if __name__ == '__main__':
    main()