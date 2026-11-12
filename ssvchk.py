#!/usr/bin/env python3  
import argparse, json, os, shlex, signal, subprocess, sys, time, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
CFG = json.load(open(os.path.join(HERE, "benchmarks.json")))
D = CFG["defaults"]

def sh(cmd, **kw): return subprocess.run(cmd, shell=True, **kw)

def compile_checkers():
    for f in ("GenericSSVChk.java", "HttpSSVChk.java"):
        r = subprocess.run([D["checker_java"].replace("java", "javac"), "-d", HERE, os.path.join(HERE, f)],
                           capture_output=True, text=True)
        if r.returncode: sys.exit(f"compile {f} failed:\n{r.stderr}")

def wait_up(url, timeout):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            urllib.request.urlopen(url, timeout=2); return True
        except urllib.error.HTTPError: return True          # any HTTP answer = up
        except Exception: time.sleep(1)
    return False

def boot(b, which, port):
    if b.get(f"start_{which}"):          # arbitrary launcher (e.g. python3 run_service_linux.py ...), {port} substituted
        cmd = b[f"start_{which}"].format(port=port)
        log = open(f"/tmp/ssv_{which}_{port}.log", "w")
        p = subprocess.Popen(cmd, shell=True, cwd=b.get("cwd"), stdout=log, stderr=log, preexec_fn=os.setsid)
        if not wait_up(b.get("ready_url", "http://localhost:{port}/").format(port=port), b.get("startup_timeout_s", D["startup_timeout_s"])):
            os.killpg(p.pid, signal.SIGTERM); sys.exit(f"{which} did not come up on {port}; see {log.name}")
        return p
    jar = b[f"{which}_jar"]
    if jar.startswith("TODO"): sys.exit(f"{which}_jar not configured for this benchmark")
    java = os.path.join(b.get("jdk", ""), "bin", "java") if b.get("jdk") else "java"
    opts = [o.format(port=port, tag=which) for o in b.get("java_opts", [])]
    log = open(f"/tmp/ssv_{which}_{port}.log", "w")
    p = subprocess.Popen([java, *opts, "-jar", jar], stdout=log, stderr=log, preexec_fn=os.setsid)
    if not wait_up(f"http://localhost:{port}/", D["startup_timeout_s"]):
        os.killpg(p.pid, signal.SIGTERM); sys.exit(f"{which} did not come up on {port}; see {log.name}")
    return p

def run_http(name, b, a):
    procs = []
    try:
        if not a.v1_url:
            for c in b.get("pre", []): sh(c)
            v2which = "v1" if a.null_test else "v2"
            procs = [boot(b, "v1", b["port_v1"]), boot(b, v2which, b["port_v2"])]
            a.v1_url, a.v2_url = f"http://localhost:{b['port_v1']}", f"http://localhost:{b['port_v2']}"
        cmd = [D["checker_java"], "-cp", HERE, "HttpSSVChk", f"--v1={a.v1_url}", f"--v2={a.v2_url}",
               f"--timeout={D['timeout_s']}"]
        for k in ("auth", "skip", "only", "ignore"):
            if b.get(k): cmd.append(f"--{k}={b[k]}")
        if b.get("probes_file"): cmd.append(f"--probes={os.path.join(HERE, b['probes_file'])}")
        r = subprocess.run(cmd, capture_output=True, text=True)
        print(r.stdout); sys.stderr.write(r.stderr[-1500:])
        verdict = next((l.split("]")[1].strip() for l in r.stdout.splitlines() if l.startswith("[Verdict]")), "ERROR")
        return verdict
    finally:
        for p in procs:
            try: os.killpg(p.pid, signal.SIGTERM)
            except Exception: pass

def run_reflect(name, b, a):
    cmd = [sys.executable, os.path.join(HERE, "ssvchk_variants.py"), b["v1_src"], b["variants_dir"]]
    for k in ("project", "classes", "cp", "cp_file", "factory"):
        if b.get(k): cmd += [f"--{k.replace('_', '-')}", b[k]]
    r = subprocess.run(cmd, capture_output=True, text=True); print(r.stdout); sys.stderr.write(r.stderr[-1500:])
    last = [l for l in r.stdout.splitlines() if l.startswith("SCORE")]
    return last[0] if last else "ERROR"

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("cmd", choices=["list", "check"]); ap.add_argument("name", nargs="?")
    ap.add_argument("--null-test", action="store_true"); ap.add_argument("--v1-url"); ap.add_argument("--v2-url")
    a = ap.parse_args()
    if a.cmd == "list":
        for k, v in CFG["benchmarks"].items(): print(f"{k:20s} {v['mode']}")
        return
    b = CFG["benchmarks"][a.name]; compile_checkers()
    res = run_http(a.name, b, a) if b["mode"] == "http" else run_reflect(a.name, b, a)
    rec = {"benchmark": a.name, "mode": b["mode"], "null_test": a.null_test, "result": res, "ts": time.time()}
    open(os.path.join(HERE, "results.jsonl"), "a").write(json.dumps(rec) + "\n")
    print("RESULT:", res)

if __name__ == "__main__": main()
