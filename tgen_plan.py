#!/usr/bin/env python3
"""tgen_plan.py -- read the Bosque API specs (.bsq) and plan the Tecton runs.

For every <Controller>.bsq under --spec-root it extracts
  * the namespace            (declare namespace X)   -> tgen --namespace
  * every public function    (public function f(...)) -> tgen --function
  * the spec parameter list  (name: BosqueType)
and cross-checks them against <Controller>.java under --java-root:
  * is there a public Java method with the same name?
  * after dropping framework-injected parameters (Principal, ...), does the
    Java parameter count equal the spec parameter count?
  * is a Java parameter a KindedDTO<T> wrapper around the spec type T?

Outputs (in --out):
  plan.json      one record per (controller, function)
  run_tgen.sh    one tgen command per function that has spec parameters
Prints a per-service summary and every mismatch.

Nothing here runs tgen.  Flags follow the Tecton README:
  node build/tgen.js --dir <spec dir> --namespace <ns> --function <fn> --combinatorial|--rnd
Confirm that --dir should be the service folder (assumed) with one run.
"""
import argparse, json, re, sys
from pathlib import Path

NS_RE = re.compile(r'declare\s+namespace\s+(\w+)')
FN_RE = re.compile(r'\bpublic\s+function\s+(\w+)\s*\(')
ENT_RE = re.compile(r'\bentity\s+(\w+)\s*\{(.*?)\n\}', re.S)
FIELD_RE = re.compile(r'\bfield\s+(\w+)\s*:\s*([^;]+);')
INV_RE = re.compile(r'\binvariant\s+([^;]+);')
JAVA_M = re.compile(r'\bpublic\s+(?!class\b|interface\b|enum\b)(?:static\s+)?'
                    r'([\w<>\[\]?,.\s]+?)\s+(\w+)\s*\(')
INJECTED = {'Principal', 'Authentication', 'HttpServletRequest',
            'HttpServletResponse', 'HttpSession', 'BindingResult', 'Model',
            'Locale', 'WebRequest', 'UriComponentsBuilder'}


def strip_bsq_comments(s):
    return re.sub(r'%%[^\n]*', '', s)


def split_top(s, sep=','):
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch in '<([{':
            depth += 1
        elif ch in '>)]}':
            depth -= 1
        if ch == sep and depth == 0:
            out.append(cur)
            cur = ''
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return [x.strip() for x in out if x.strip()]


def balanced(text, i):
    """text[i-1] == '('; return (inside, index after ')')."""
    depth, j = 1, i
    while j < len(text) and depth:
        depth += (text[j] == '(') - (text[j] == ')')
        j += 1
    return text[i:j - 1], j


def parse_bsq(path):
    text = strip_bsq_comments(path.read_text(errors='replace'))
    ns = NS_RE.search(text)
    fns = []
    for m in FN_RE.finditer(text):
        inside, j = balanced(text, m.end())
        rm = re.match(r'\s*:\s*([^{]+?)\s*\{', text[j:])
        params = []
        for p in split_top(inside):
            n, _, t = p.partition(':')
            params.append({'name': n.strip(), 'type': t.strip()})
        fns.append({'name': m.group(1), 'params': params,
                    'returns': rm.group(1) if rm else '?'})
    ents = {}
    for em in ENT_RE.finditer(text):
        ents[em.group(1)] = {
            'fields': [{'name': a, 'type': b.strip()}
                       for a, b in FIELD_RE.findall(em.group(2))],
            'invariants': [i.strip() for i in INV_RE.findall(em.group(2))]}
    return (ns.group(1) if ns else None), fns, ents


ANN_RE = re.compile(r'@\w+(?:\([^)]*\))?\s*')


def parse_java(path):
    text = path.read_text(errors='replace')
    cls = path.stem
    methods = {}
    for m in JAVA_M.finditer(text):
        name = m.group(2)
        if name == cls:
            continue
        inside, _ = balanced(text, m.end())
        params = []
        for p in split_top(ANN_RE.sub('', inside)):
            parts = p.rsplit(None, 1)
            if len(parts) == 2:
                params.append({'type': parts[0].replace('final ', '').strip(),
                               'name': parts[1]})
        methods.setdefault(name, []).append(params)   # overloads kept
    return methods


def check(fn, jmethods):
    # overloads are renamed in the specs (getPersons_0, getPersons_1)
    jname = fn['name']
    if jname not in jmethods:
        # spec names carry suffixes: getPersons_0, findAll_0_Department,
        # findByUsername_Credential -> Java name is the part before the first '_'
        jname = jname.split('_')[0]
    if jname not in jmethods:
        return 'NO_JAVA_METHOD', None
    best = None
    for jp in jmethods[jname]:
        real = [p for p in jp if p['type'].split('<')[0] not in INJECTED]
        if len(real) == len(fn['params']):
            best = (jp, real)
            break
    if best is None:
        return 'ARITY_MISMATCH', {'java_method': jname,
                                  'java_params': jmethods[jname][0],
                                  'real': [], 'wrappers': []}
    jp, real = best
    notes = []
    for sp, jr in zip(fn['params'], real):
        if jr['type'].startswith('KindedDTO<'):
            inner = jr['type'][len('KindedDTO<'):-1]
            notes.append({'param': sp['name'], 'wrap': 'KindedDTO',
                          'kind': inner, 'ok': inner == sp['type']})
    return 'OK', {'java_method': jname, 'java_params': jp, 'real': real,
                  'wrappers': notes}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--spec-root', required=True,
                    help='e.g. benchmarks/api_spec (one folder per service)')
    ap.add_argument('--java-root', required=True, nargs='+',
                    help='folder(s) containing the original <Controller>.java files')
    ap.add_argument('--out', default='tgen_out')
    ap.add_argument('--mode', default='--combinatorial',
                    choices=['--combinatorial', '--rnd'])
    ap.add_argument('--tgen', default='node build/tgen.js')
    ap.add_argument('--tgen-js', default='build/tgen.js',
                    help='tgen.js, to read its routeMap (function -> route)')
    a = ap.parse_args()

    routes = None
    tj = Path(a.tgen_js)
    if tj.exists():
        routes = set(re.findall(r'^\s*(\w+)\s*:\s*\{\s*method\s*:',
                                tj.read_text(errors='replace'), re.M))

    spec_root, out = Path(a.spec_root), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    javas = {}
    for jr in a.java_root:
        for p in Path(jr).rglob('*.java'):
            javas.setdefault(p.stem, []).append(p)

    plan, cmds, summary, problems = [], [], {}, []
    for bsq in sorted(spec_root.rglob('*.bsq')):
        service = bsq.parent.relative_to(spec_root).parts[0] \
            if bsq.parent != spec_root else '.'
        ns, fns, ents = parse_bsq(bsq)
        ctrl = bsq.stem
        cands = javas.get(ctrl, [])
        if len(cands) > 1:
            pref = [c for c in cands if service.lower() in str(c).lower()]
            cands = pref or cands
        jm = parse_java(cands[0]) if cands else None
        s = summary.setdefault(service, dict(controllers=0, functions=0,
                                             with_params=0, zero_params=0,
                                             problems=0))
        s['controllers'] += 1
        if ns is None:
            problems.append((service, ctrl, '-', 'NO_NAMESPACE'))
        if jm is None:
            problems.append((service, ctrl, '-', 'NO_JAVA_FILE'))
        for fn in fns:
            s['functions'] += 1
            status, detail = ('NO_JAVA_FILE', None) if jm is None else check(fn, jm)
            if routes is not None and fn['name'] not in routes:
                problems.append((service, ctrl, fn['name'],
                                 'NO_ROUTE_IN_TGEN_JS (tgen cannot place it)'))
                s['problems'] += 1
            has = bool(fn['params'])
            s['with_params' if has else 'zero_params'] += 1
            if status != 'OK':
                s['problems'] += 1
                why = status
                if status == 'NO_JAVA_METHOD' and jm:
                    why += '  | java has: ' + ', '.join(sorted(jm)[:14])
                if status == 'ARITY_MISMATCH' and detail:
                    why += '  | spec(' + ', '.join(
                        p['name'] + ':' + p['type'] for p in fn['params']) + ')'
                    why += ' java(' + ', '.join(
                        p['type'] + ' ' + p['name']
                        for p in detail['java_params']) + ')'
                problems.append((service, ctrl, fn['name'], why))
            for w in (detail or {}).get('wrappers', []):
                if not w['ok']:
                    problems.append((service, ctrl, fn['name'],
                                     'WRAPPER_TYPE_DIFFERS ' + str(w)))
            plan.append(dict(service=service, controller=ctrl, namespace=ns,
                             function=fn['name'], spec_params=fn['params'],
                             returns=fn['returns'], status=status,
                             java=detail, spec_file=str(bsq),
                             java_file=str(cands[0]) if cands else None,
                             entities=ents))
            if has and ns and status == 'OK':
                cmds.append(f"run {service} {ctrl} {bsq.parent} {ns} {fn['name']}")

    (out / 'plan.json').write_text(json.dumps(plan, indent=1))
    header = r'''#!/bin/bash
# One tgen run per function that has spec parameters.
# usage (from X1_Testing_Code):  bash run_tgen.sh [--rnd|--combinatorial]
# --rnd needs no LLM key.  --combinatorial sends a prompt to an LLM.
# Writes tgen_runs.tsv: service, controller, function, test dir; and tgen_times.tsv: service, controller, function, start epoch, seconds, tries.
# TRIES=5 bash run_tgen.sh --combinatorial   to allow more retries (default 3).
set -u
MODE=${1:---rnd}
: > tgen_runs.tsv
: > tgen_times.tsv   # service, controller, function, start epoch, seconds, tries (match the start time to the LLM provider's usage log for cost)
run() {  # service controller specdir namespace function
  # an LLM can answer one value request wrongly, which aborts the whole function: retry (TRIES, default 3). Each try is paid for.
  t0=$(date +%s); n=0; line=""
  while [ "$n" -lt "${TRIES:-3}" ] && [ -z "$line" ]; do
    n=$((n+1))
    out=$(@TGEN@ --dir "$3" --namespace "$4" --function "$5" $MODE 2>&1)
    line=$(printf '%s\n' "$out" | grep 'Test suite generated in' | tail -1)
  done
  t1=$(date +%s)
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$5" "$t0" "$((t1-t0))" "$n" >> tgen_times.tsv
  if [ -z "$line" ]; then
    echo "FAIL $1/$2 $5 (after $n tries)"; printf '%s\n' "$out" | grep -E "what\(\)|Error|error" | head -3; return
  fi
  testdir=$(printf '%s' "$line" | sed 's/.*generated in: \(.*\) for function:.*/\1/')
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$5" "$testdir" >> tgen_runs.tsv
  echo "ok   $1/$2 $5 (tries: $n)"
}
'''.replace('@TGEN@', a.tgen)
    (out / 'run_tgen.sh').write_text(header + '\n'.join(cmds) + '\n')

    print(f"{'service':26}{'ctrl':>5}{'fns':>6}{'params':>8}{'zero':>6}{'prob':>6}")
    for k, v in summary.items():
        print(f"{k:26}{v['controllers']:>5}{v['functions']:>6}"
              f"{v['with_params']:>8}{v['zero_params']:>6}{v['problems']:>6}")
    t = lambda key: sum(v[key] for v in summary.values())
    print(f"{'TOTAL':26}{t('controllers'):>5}{t('functions'):>6}"
          f"{t('with_params'):>8}{t('zero_params'):>6}{t('problems'):>6}")
    if problems:
        print('\nneeds a look:')
        for p in problems:
            print('  ', *p)
    print(f"\nwrote {out/'plan.json'} and {out/'run_tgen.sh'} "
          f"({len(cmds)} tgen commands)")


if __name__ == '__main__':
    main()