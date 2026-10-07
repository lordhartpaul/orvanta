#!/usr/bin/env python3
"""Runs the non-functional scenarios against the compose stack and collects the figures.

    python perf/scenarios.py scale        throughput with 1 consumer, then 4, then 2 and 3 engine containers
    python perf/scenarios.py stress       a burst several times the normal load
    python perf/scenarios.py faults       kill an engine, restart the broker, restart the database, kill every engine
    python perf/scenarios.py recovery     only the last of those: every engine dies and comes back
    python perf/scenarios.py soak         a steady load for --minutes (default 10), with memory before and after

Needs the stack of docker-compose.yml with a .env (ORVANTA_PORT is read from it). Results go to target/perf/.
"""
import json
import os
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
OUT = os.path.join("target", "perf")
os.makedirs(OUT, exist_ok=True)
ENV = dict(l.strip().split("=", 1) for l in open(".env") if "=" in l)
URL = "http://localhost:" + ENV.get("ORVANTA_PORT", "8480")


def sh(*args, env=None, check=True):
    merged = dict(os.environ)
    merged.update(env or {})
    r = subprocess.run(list(args), capture_output=True, text=True, env=merged, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise RuntimeError("%s failed: %s" % (" ".join(args), (r.stderr or r.stdout)[-400:]))
    return r.stdout


def stack(engines, consumers):
    sh("docker", "compose", "up", "-d", "--scale", "engine=%d" % engines, env={"ORVANTA_BUS_CONSUMERS": str(consumers)})
    deadline = time.time() + 180
    while time.time() < deadline:
        states = [l for l in sh("docker", "compose", "ps", "--format", "{{.Service}} {{.Status}}").splitlines() if l]
        if states and all("healthy" in s and "starting" not in s for s in states) and sum(s.startswith("engine") for s in states) == engines:
            return
        time.sleep(2)
    raise RuntimeError("the stack did not become healthy: %s" % states)


def containers(service):
    return [l for l in sh("docker", "compose", "ps", "-q", service).split() if l]


def memory():
    out = {}
    for line in sh("docker", "stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}} {{.CPUPerc}}").splitlines():
        parts = line.split()
        if parts and parts[0].startswith("orvanta-"):
            out[parts[0]] = parts[1]
    return out


def load(name, files, size, rate=0, during=None, timeout=1800):
    """Runs the load generator; 'during' is called a few seconds into the run, to inject a fault."""
    target = os.path.join(OUT, name + ".json")
    if os.path.exists(target):
        os.remove(target)
    args = [sys.executable, "perf/orvanta_perf.py", "load", "--url", URL, "--files", str(files), "--size", str(size),
            "--tag", name.upper().replace("-", "")[:6], "--json", target, "--timeout", str(timeout)]
    if rate:
        args += ["--rate", str(rate)]
    note = {}
    if during:
        def later():
            time.sleep(6)
            try:
                note["fault"] = during()
            except Exception as e:   # a failed injection must be visible in the result, not hidden
                note["fault"] = "injection failed: %s" % e
        threading.Thread(target=later, daemon=True).start()
    started = time.time()
    run = subprocess.run(args, capture_output=True, text=True)
    result = json.load(open(target)) if os.path.exists(target) else {"reconciliation": "NO RESULT", "error": (run.stderr or run.stdout)[-600:]}
    result["wallSeconds"] = round(time.time() - started, 1)
    result.update(note)
    result["scenario"] = name
    json.dump(result, open(target, "w"), indent=2)
    line = "%-22s %6s payments  %7s s  %7s /s  final p95 %6s s  reconciliation %s %s" % (
        name, result.get("payments"), result.get("seconds"), result.get("paymentsPerSecond"),
        (result.get("secondsFileReceivedToFinal") or {}).get("p95"), result.get("reconciliation"), note.get("fault", ""))
    print(line, flush=True)
    for p in result.get("problems", [])[:5]:
        print("      " + p, flush=True)
    if "error" in result:
        print("      " + result["error"].strip().splitlines()[-1], flush=True)
    return result


def scale():
    for consumers, engines in ((1, 1), (4, 1), (4, 2), (4, 3)):
        stack(engines, consumers)
        r = load("scale-c%d-e%d" % (consumers, engines), 8, 1000)
        r["memory"] = memory()
        json.dump(r, open(os.path.join(OUT, "scale-c%d-e%d.json" % (consumers, engines)), "w"), indent=2)


def stress():
    stack(3, 4)
    r = load("stress-30000", 30, 1000, timeout=3600)
    r["memory"] = memory()
    json.dump(r, open(os.path.join(OUT, "stress-30000.json"), "w"), indent=2)


def faults():
    stack(2, 4)

    def kill_one_engine():
        victim = containers("engine")[0]
        sh("docker", "kill", victim)
        return "killed engine container %s" % victim[:12]
    load("fault-engine-killed", 6, 1000, during=kill_one_engine)
    stack(2, 4)

    def restart_broker():
        sh("docker", "compose", "restart", "rabbitmq")
        return "restarted the broker"
    load("fault-broker-restart", 6, 1000, during=restart_broker)
    stack(2, 4)

    def restart_database():
        sh("docker", "compose", "restart", "mongo")
        return "restarted the database"
    load("fault-database-restart", 6, 1000, during=restart_database)
    stack(2, 4)

    recovery()


def recovery():
    """Every engine dies in the middle of a run and comes back: nothing may be lost or done twice."""
    stack(2, 4)

    def kill_all_engines_then_start():
        for c in containers("engine"):
            sh("docker", "kill", c)
        time.sleep(20)
        sh("docker", "compose", "up", "-d", "--scale", "engine=2", env={"ORVANTA_BUS_CONSUMERS": "4"})
        return "killed every engine container, started them again after 20 s"
    load("fault-all-engines", 6, 1000, during=kill_all_engines_then_start)
    stack(2, 4)


def soak():
    minutes = int(sys.argv[sys.argv.index("--minutes") + 1]) if "--minutes" in sys.argv else 10
    stack(2, 4)
    before = memory()
    # one file of 100 payments every two seconds: 50 payments a second, well inside what the stack can do
    r = load("soak-%dmin" % minutes, minutes * 30, 100, rate=0.5, timeout=minutes * 60 + 900)
    r["memoryBefore"], r["memoryAfter"] = before, memory()
    json.dump(r, open(os.path.join(OUT, "soak-%dmin.json" % minutes), "w"), indent=2)
    print("      memory before", before, "\n      memory after ", r["memoryAfter"])


if __name__ == "__main__":
    {"scale": scale, "stress": stress, "faults": faults, "recovery": recovery, "soak": soak}[sys.argv[1]]()
