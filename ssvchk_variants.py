#!/usr/bin/env python3
"""
ssvchk_variants.py -- score LLM-written V2 variants against a V1 class that lives INSIDE a real project,
in place (nothing is moved or modified; all compiled output goes to a temp dir).

  python3 ssvchk_variants.py <V1.java> <variants_dir> [options]

  <V1.java>       the original class, wherever it lives (e.g. under X1Testing/.../src/main/java/...)
  <variants_dir>  folder you saved variants into. Either
                    class_NN_name/prompt_K/v2.java   (runner layout; NN gives the expected severity), or
                    any *.java files anywhere under it (put class_NN / cNN in the path to get a score)

Options:
  --project DIR   project root (default: nearest parent of V1.java containing pom.xml / build.gradle)
  --classes DIR   already-compiled classes dir (default: <project>/target/classes or build/classes/java/main)
  --cp STR        extra classpath (':'-joined jars/dirs); skips Maven dependency resolution
  --cp-file FILE  file containing a classpath string (e.g. from `mvn dependency:build-classpath`)
  --factory CLS   optional factory class (static createV1()/createV2()); default is AUTO-CONSTRUCTION of deps
  --java-home DIR JDK used to BUILD the project and to compile variants (e.g. a JDK 8 for jdk8_* benchmarks).
                  Needed when the default JDK can't read the project's old jars ("Invalid CEN header").
                  macOS: --java-home "$(/usr/libexec/java_home -v 1.8)"
  --exclude-jar RE  drop classpath entries matching this regex (default: aspectjweaver)
  --no-build      never run mvn/gradle; fail if classes are missing
  --strict        also score the exact 5-level verdict (REFACTOR/PATCH/CRASH_FIX/MINOR/MAJOR), not just 3 tiers
  --csv FILE      write per-variant results

No pom? Falls back to compiling all .java files in V1's directory (the old RBAC layout).
"""
import argparse, csv, os, re, subprocess, sys, tempfile, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
EXPECT = {**{i: "Patch" for i in (1, 2, 3, 4)}, **{i: "Minor" for i in (5, 6)}, **{i: "Major" for i in range(7, 13)}}
STRICT = {1: "REFACTOR", 2: "PATCH", 3: "CRASH_FIX", 4: "CRASH_FIX", 5: "MINOR", 6: "MINOR",
          **{i: "MAJOR" for i in range(7, 13)}}
# Labels follow the paper's Case 1-12 definitions (the classification the authors agreed to keep):
#   Case 4 (rejected input now accepted) is grouped with the crash-level repairs; Case 6 = a NEW function plus a crash fix,
#   which is minor because the highest level among the changes wins. FORMAL therefore equals STRICT.
FORMAL = {1: "REFACTOR", 2: "PATCH", 3: "CRASH_FIX", 4: "CRASH_FIX", 5: "MINOR", 6: "MINOR",
          **{i: "MAJOR" for i in range(7, 13)}}
MAP = {"MAJOR": "Major", "MINOR": "Minor", "PATCH": "Patch", "CRASH_FIX": "Patch", "REFACTOR": "Patch"}

JH = None
def run(cmd, **kw):
    if JH:
        env = dict(os.environ, JAVA_HOME=JH, PATH=os.path.join(JH, "bin") + os.pathsep + os.environ["PATH"])
        # only the build / variant compile use the chosen JDK; the checker itself (needs 11+) keeps the default java
        if cmd and cmd[0] in ("mvn", "gradle", "./gradlew") or (cmd and cmd[0] == "javac" and "-proc:none" in cmd):
            kw["env"] = env
            if cmd[0] == "javac": cmd = [os.path.join(JH, "bin", "javac")] + cmd[1:]
    return subprocess.run(cmd, capture_output=True, text=True, **kw)

def detect(src):
    t = open(src, encoding="utf-8", errors="replace").read()
    pkg = re.search(r"^\s*package\s+([\w.]+)\s*;", t, re.M)
    cls = re.search(r"^\s*(?:public\s+)?(?:final\s+|abstract\s+)*class\s+(\w+)", t, re.M)
    return (pkg.group(1) if pkg else ""), (cls.group(1) if cls else None)

def find_project(src):
    d = os.path.dirname(os.path.abspath(src))
    while d != os.path.dirname(d):
        for f in ("pom.xml", "build.gradle", "build.gradle.kts"):
            if os.path.exists(os.path.join(d, f)): return d
        d = os.path.dirname(d)
    return None

def maven_cp(project, cache):
    out = os.path.join(cache, "cp.txt")
    r = run(["mvn", "-q", "-B", "dependency:build-classpath", f"-Dmdep.outputFile={out}"], cwd=project)
    if r.returncode or not os.path.exists(out): sys.exit("mvn dependency:build-classpath failed:\n" + (r.stdout + r.stderr)[-1500:])
    return open(out).read().strip()

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("v1"); ap.add_argument("variants")
    ap.add_argument("--project"); ap.add_argument("--classes"); ap.add_argument("--cp", default="")
    ap.add_argument("--cp-file"); ap.add_argument("--factory"); ap.add_argument("--factory-src", help="Java source of a factory (static createV1()/createV2(), may read System props ssv.v1/ssv.v2); compiled for you, implies --factory"); ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--extra-src", default=None, help="folder of extra .java sources (package directories, e.g. youtube/data/X.java) overlaid on a TEMPORARY COPY of the project before it is built, for classes the benchmark copy lacks; the original project is never modified"); ap.add_argument("--http-5xx-crash", action="store_true", help="treat a JAX-RS/Spring response with a 5xx status as a CRASH (for services that catch every exception and answer 500)"); ap.add_argument("--include-nonpublic", action="store_true", help="also analyse package-private/protected methods (for classes with no public API)"); ap.add_argument("--csv"); ap.add_argument("--detail", help="regex; print the full checker report for variants whose path matches (e.g. class_09)"); ap.add_argument("--exclude-src", default=None, help="regex; build a TEMPORARY COPY of the project without source files whose path matches (e.g. '_V1\\.java$'); the original project is never modified"); ap.add_argument("--strict", action="store_true"); ap.add_argument("--equiv", default=None, help="declared output equivalence for PATCH: comma list of order,case,ws,num or 'none' (default: order,case,ws,num)"); ap.add_argument("--java-home"); ap.add_argument("--exclude-jar", default="aspectjweaver")
    ap.add_argument("--inputs", help="corpus.json of externally generated (Tecton) inputs, see tgen_to_corpus.py")
    ap.add_argument("--input-mode", choices=["typed", "tecton", "both"], default=None, help="typed = type-directed only (A, the default without --inputs); tecton = corpus only (B); both = union (C, the default with --inputs)")
    ap.add_argument("--corpus-key", default=None, help="controller name to look up in the corpus (default: the V1 class name)")
    a = ap.parse_args()
    if a.input_mode and a.input_mode != "typed" and not a.inputs: sys.exit("--input-mode=" + a.input_mode + " needs --inputs corpus.json")
    if a.inputs and not os.path.exists(a.inputs): sys.exit("corpus file not found: " + a.inputs)
    global JH; JH = a.java_home
    v1 = os.path.abspath(a.v1); pkg, v1cls = detect(v1)
    if not v1cls: sys.exit("cannot find 'public class X' in " + v1)
    work = tempfile.mkdtemp(prefix="ssv_"); out = os.path.join(work, "chk")
    os.makedirs(out)
    jar_cp = a.cp
    # --- compile the checker once
    r = run(["javac", "-d", out, os.path.join(HERE, "GenericSSVChk.java"), os.path.join(HERE, "CorpusInputs.java")])
    if r.returncode: sys.exit(r.stderr)

    project = a.project or find_project(v1)
    base_cp = []
    if project and (a.exclude_src or a.extra_src):
        copy = os.path.join(work, "projcopy")
        shutil.copytree(project, copy, ignore=shutil.ignore_patterns(*([".git", ".idea", "node_modules"] if a.no_build else ["target", "build", ".git", ".idea", "node_modules"])))
        removed = []
        for dp, dn, fn in os.walk(copy):
            for f in fn:
                fp = os.path.join(dp, f)
                if a.exclude_src and f.endswith(".java") and re.search(a.exclude_src, os.path.relpath(fp, copy)):
                    os.remove(fp); removed.append(os.path.relpath(fp, copy))
        if a.exclude_src:
            print(f"[exclude-src] built from a temporary copy of the project; left out {len(removed)} file(s):", *removed[:8], sep="\n    ")
        if a.extra_src:
            added = []
            for dp, dn, fn in os.walk(a.extra_src):
                for f in fn:
                    if f.endswith(".java"):
                        rel = os.path.relpath(os.path.join(dp, f), a.extra_src)
                        dst = os.path.join(copy, "src", "main", "java", rel)
                        os.makedirs(os.path.dirname(dst), exist_ok=True)
                        shutil.copy(os.path.join(dp, f), dst); added.append(rel)
            print(f"[extra-src] built from a temporary copy of the project; added {len(added)} source file(s):", *added[:8], sep="\n    ")
        project = copy
    if project:
        classes = a.classes
        if not classes:
            for c in ("target/classes", "build/classes/java/main"):
                if os.path.isdir(os.path.join(project, c)): classes = os.path.join(project, c); break
        if classes and not a.no_build and not a.classes and \
                not os.path.exists(os.path.join(classes, *(pkg.split(".") if pkg else []), v1cls + ".class")):
            print(f"[build] {classes} exists but has no {v1cls}.class (stale/partial build from an earlier failure) -> rebuilding")
            classes = None
        if not classes and not a.no_build:
            print(f"[build] compiling {project} (output stays in its target/ dir)")
            if os.path.exists(os.path.join(project, "pom.xml")):
                r = run(["mvn", "-q", "-B", "-DskipTests", "compile"], cwd=project)
            else:
                gw = "./gradlew" if os.path.exists(os.path.join(project, "gradlew")) else "gradle"
                r = run([gw, "-q", "classes", "-x", "test"], cwd=project)
            if r.returncode:
                msg = (r.stdout + r.stderr)
                hint = ""
                try: pomtxt = open(os.path.join(project, "pom.xml")).read().lower()
                except Exception: pomtxt = ""
                if "cannot find symbol" in msg and ("lombok" in pomtxt or re.search(r"symbol:\s+method (get|set|is)[A-Z]", msg)):
                    hint = ("\n\nHINT: this looks like Lombok-generated getters/setters not being generated. JDK 23+ no longer runs annotation\n"
                            "processors by default. Build with an older JDK, e.g. add  --java-home \"$(/usr/libexec/java_home -v 1.8)\"  (macOS; try -v 11 or -v 17 if 1.8 is not installed).")
                sys.exit("build failed:\n" + msg[-2000:] + hint)
            classes = os.path.join(project, "target/classes") if os.path.isdir(os.path.join(project, "target/classes")) \
                else os.path.join(project, "build/classes/java/main")
        if not classes or not os.path.isdir(classes): sys.exit("no compiled classes found; pass --classes")
        base_cp.append(classes)
        if a.cp_file: jar_cp = open(a.cp_file).read().strip()
        elif not jar_cp and os.path.exists(os.path.join(project, "pom.xml")): jar_cp = maven_cp(project, work)
        elif not jar_cp: print("[warn] gradle project: pass --cp or --cp-file with the dependency jars")
        print(f"[project] {project}\n[classes] {classes}")
    else:
        base = os.path.join(work, "v1classes"); os.makedirs(base)
        srcs = [os.path.join(os.path.dirname(v1), f) for f in os.listdir(os.path.dirname(v1)) if f.endswith(".java")]
        r = run(["javac", "-parameters", "-cp", jar_cp or ".", "-d", base] + srcs)
        if r.returncode: sys.exit("compiling V1 dir failed:\n" + r.stderr)
        base_cp.append(base); print("[no pom] compiled sibling .java files of V1")
    if jar_cp and a.exclude_jar:
        keep = [p for p in jar_cp.split(os.pathsep) if not re.search(a.exclude_jar, os.path.basename(p))]
        if len(keep) != len(jar_cp.split(os.pathsep)): print(f"[cp] dropped {len(jar_cp.split(os.pathsep)) - len(keep)} jar(s) matching {a.exclude_jar}")
        jar_cp = os.pathsep.join(keep)
    base_cp_str = os.pathsep.join(base_cp + ([jar_cp] if jar_cp else []))
    if a.factory_src:
        fpkg, fcls = detect(a.factory_src)
        fdir = os.path.join(work, "factory"); os.makedirs(fdir)
        r = run(["javac", "-proc:none", "-cp", base_cp_str, "-d", fdir, a.factory_src])
        if r.returncode: sys.exit("factory compile failed:\n" + "\n".join(l for l in r.stderr.splitlines() if "Picked up" not in l))
        base_cp.append(fdir); base_cp_str = os.pathsep.join(base_cp + ([jar_cp] if jar_cp else []))
        a.factory = (fpkg + "." if fpkg else "") + fcls
    if a.factory: print("[factory]", a.factory)

    # --- gather variants
    vs = []
    for root, _, files in os.walk(a.variants):
        for f in files:
            if f.endswith(".java"): vs.append(os.path.join(root, f))
    vs.sort()
    if not vs: sys.exit("no .java variants under " + a.variants)

    rows, agree, total, mism = [], 0, 0, []
    tec_ev = [0]
    stot = [0, 0]; ftot = [0]; by_class = {}; nblind = [0]; ctorerr = []; blind_all = set(); seen_all = set()
    for i, vf in enumerate(vs, 1):
        vpkg, vcls = detect(vf)
        rel = os.path.relpath(vf, a.variants)
        m = re.search(r"class_?0*(\d+)", rel, re.I)
        exp = EXPECT.get(int(m.group(1))) if m else None
        if not vcls:
            raw = open(vf, encoding="utf-8", errors="replace").read()
            print(f"[{i}] {rel}: no 'public class' found, skipped  ({len(raw)} bytes, starts: {raw.strip()[:60]!r})")
            rows.append((rel, exp, "NO_CLASS", "")); continue
        if vcls == v1cls and vpkg == pkg:
            print(f"[{i}] {rel}: variant class has the SAME name as V1 ({v1cls}); rename it (runner uses --out-class) -- skipped"); continue
        vdir = os.path.join(work, f"v{i}"); os.makedirs(vdir)
        vsrc = os.path.join(vdir, "src"); os.makedirs(vsrc)
        vcopy = os.path.join(vsrc, vcls + ".java"); shutil.copyfile(vf, vcopy)   # javac needs file name == public class name
        r = run(["javac", "-proc:none", "-parameters", "-cp", base_cp_str, "-d", vdir, vcopy])   # -parameters: keep parameter names, as Spring Boot builds do (@PathVariable without a name needs them)
        if r.returncode:
            print(f"[{i}] {rel}: COMPILE FAILED\n{chr(10).join(l for l in r.stderr.splitlines() if 'Picked up' not in l)[:600]}"); rows.append((rel, exp, "COMPILE_FAIL", "")); continue
        cp = os.pathsep.join([out, vdir, base_cp_str])
        cmd = ["java", "-cp", cp, "GenericSSVChk"] + ([f"--factory={a.factory}"] if a.factory else []) + ([f"--equiv={a.equiv}"] if a.equiv else []) + (["--include-nonpublic"] if a.include_nonpublic else []) + (["--http-5xx-crash"] if a.http_5xx_crash else []) + \
              ([f"--inputs={os.path.abspath(a.inputs)}", f"--input-mode={a.input_mode or 'both'}"] if a.inputs else []) + ([f"--corpus-key={a.corpus_key}"] if a.corpus_key else []) + \
              [f"{pkg + '.' if pkg else ''}{v1cls}", f"{vpkg + '.' if vpkg else ''}{vcls}", v1, vcopy]
        r = run(cmd)
        if a.detail and re.search(a.detail, rel): print(r.stdout); print(r.stderr[-3000:])
        v = next((l.split("]", 1)[1].strip() for l in r.stdout.splitlines() if l.startswith("[Verdict]")), "ERROR")
        om = re.search(r"\[Observability\] observed=\[(.*?)\] blind=\[(.*?)\]", r.stdout)
        seen_m = [x.strip() for x in om.group(1).split(",") if x.strip()] if om else []
        blind_m = [x.strip() for x in om.group(2).split(",") if x.strip()] if om else []
        blind_all.update(blind_m); seen_all.update(seen_m)
        if "{tecton}" in r.stdout: tec_ev[0] += 1
        blind = bool(om) and not seen_m and bool(blind_m)      # every probed method is unobservable
        if "cannot construct the class under test" in r.stderr and not ctorerr:
            ctorerr.append("\n".join(l for l in r.stderr.splitlines() if "[ERROR] cannot construct" in l or l.startswith("      at "))[:1500])
        if blind: nblind[0] += 1
        mapped = MAP.get(v, "ERROR")
        if v == "ERROR": print(r.stderr[-600:])
        tag = "  [BLIND: V1 never returns normally on any probed method]" if blind else ""
        if a.strict and m:
            c = int(m.group(1)); sx = STRICT[c]; fx = FORMAL[c]; stot[0] += 1
            if v == sx: stot[1] += 1
            else: tag += f"  [strict: expected {sx}]"
            if v == fx: ftot[0] += 1
            elif v != sx: tag += f"  [formal: expected {fx}]"
            by_class.setdefault(c, [0, 0, 0]); by_class[c][0] += 1; by_class[c][1] += (v == sx); by_class[c][2] += (v == fx)
        if exp:
            total += 1
            if mapped == exp: agree += 1
            else: mism.append(f"{rel}  expected={exp} got={mapped} ({v})"); tag = "  <-- MISMATCH"
        print(f"[{i}/{len(vs)}] {rel}: {v} -> {mapped}" + (f" (expected {exp})" if exp else "") + tag)
        rows.append((rel, exp, v, mapped))
    print("=" * 68)
    if a.inputs: print(f"[inputs] mode={a.input_mode or 'both'}  corpus={a.inputs}  variants whose reported evidence includes a Tecton input: {tec_ev[0]}")
    if total: print(f"SCORE: {agree}/{total} agree with expected severity")
    else: print("No class_NN in paths, so no expected labels; verdicts listed above.")
    if a.strict and stot[0]:
        print(f"STRICT (5-level, benchmark-derived labels) SCORE: {stot[1]}/{stot[0]}")
        print(f"FORMAL (5-level, paper-rule labels; same as STRICT since the Case 6 fix) SCORE: {ftot[0]}/{stot[0]}   [equivalence: {a.equiv or 'order,case,ws,num'}]")
        print("  per class (class: strict/formal of n):", "  ".join(f"{c}:{x[1]}/{x[2]} of {x[0]}" for c, x in sorted(by_class.items())))
    if ctorerr:
        print("\n*** The checker could NOT BUILD the class under test (first failure shown). Fix this first:")
        print(ctorerr[0])
    only_blind = sorted(blind_all - seen_all)
    if only_blind:
        print("\nObservability: methods on which V1 never returned normally (output changes there are invisible; crash/liveness changes still show): " + ", ".join(only_blind))
    if nblind[0]:
        print(f"\n*** WARNING: in {nblind[0]} variant(s) V1 never returned normally on ANY probed method, so changes are INVISIBLE")
        print("*** and the verdict (often REFACTOR) is not trustworthy. The service dependency is probably null/unfaked.")
        print("*** Pass --factory-src <Factory>.java (or fix the dependency) and re-run before using these numbers.")
    for x in mism: print("  -", x)
    if a.csv:
        with open(a.csv, "w", newline="") as fh:
            w = csv.writer(fh); w.writerow(["variant", "expected", "verdict", "mapped"]); w.writerows(rows)
    shutil.rmtree(work, ignore_errors=True)

if __name__ == "__main__": main()