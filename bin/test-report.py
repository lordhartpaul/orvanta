#!/usr/bin/env python3
"""Builds the test report: every use case of the catalogue with the result of the tests that prove it.

    mvnw install -Dorvanta.it.rabbit=amqp://guest:guest@localhost:5672     Java tests (results in target/surefire-reports)
    cd console-tests && npm test                                           browser tests (results in target/console-tests)
    python bin/test-report.py                                              model tests are run here; writes target/test-report.md

Exits 1 when a use case has a failing test, a test that did not run, or names a test that does not
exist, and when a test belongs to no use case. So the report can gate a build.
"""
import glob
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

results = {}   # "kind:name" -> PASS | FAIL | SKIPPED

# ---- Java ----
for path in glob.glob("orvanta-*/target/surefire-reports/TEST-*.xml"):
    for case in ET.parse(path).getroot().iter("testcase"):
        outcome = "PASS"
        if case.find("failure") is not None or case.find("error") is not None:
            outcome = "FAIL"
        elif case.find("skipped") is not None:
            outcome = "SKIPPED"
        results["java:" + case.get("name").split("(")[0]] = outcome

# ---- models ----
jars = glob.glob("orvanta-pay/target/orvanta-pay-*-all.jar")
if jars:
    run = subprocess.run(["java", "-cp", jars[0], "io.orvanta.forge.ForgeCli", "test", "workspace"], capture_output=True, text=True)
    for line in run.stdout.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0] in ("PASS", "FAIL"):
            results["model:" + parts[1]] = parts[0]

# ---- browser ----
browser_titles = {}
browser_file = "target/console-tests/results.json"
if os.path.exists(browser_file):
    def walk(suite):
        for spec in suite.get("specs", []):
            ok = all(t.get("status") in ("expected", "flaky") for t in spec.get("tests", []))
            browser_titles[spec["title"]] = "PASS" if ok else "FAIL"
        for child in suite.get("suites", []):
            walk(child)
    for suite in json.load(open(browser_file, encoding="utf-8")).get("suites", []):
        walk(suite)
for title, outcome in browser_titles.items():
    results["browser:" + title] = outcome

catalogue = yaml.safe_load(open("docs/testing/use-cases.yaml", encoding="utf-8"))
used = set()
lines = []
bad = 0
by_area = {}
for case in catalogue:
    rows = []
    for ref in case["tests"]:
        if ref.startswith("browser:"):
            # a reference is the start of a title; a test that runs in several variants has several titles that start with it
            matches = [k for k in results if k.startswith("browser:") and ref[len("browser:"):] in k]
            if matches:
                for key in matches:
                    used.add(key)
                    rows.append((key if len(matches) > 1 else ref, results[key]))
                continue
            key = ref
        else:
            key = ref
        outcome = results.get(key, "NOT RUN" if ref.startswith("browser:") and not browser_titles else "MISSING")
        used.add(key)
        rows.append((ref, outcome))
    worst = "PASS"
    for _, outcome in rows:
        if outcome == "FAIL":
            worst = "FAIL"
        elif outcome != "PASS" and worst == "PASS":
            worst = outcome
    if worst != "PASS":
        bad += 1
    by_area.setdefault(case["area"], []).append((case, worst, rows))

unmapped = sorted(k for k in results if k not in used)
total = {o: sum(1 for v in results.values() if v == o) for o in ("PASS", "FAIL", "SKIPPED")}
kinds = {k: sum(1 for r in results if r.startswith(k + ":")) for k in ("java", "model", "browser")}

out = ["# Test report", "",
       "%d tests: %d Java, %d model, %d browser. %d passed, %d failed, %d skipped."
       % (len(results), kinds["java"], kinds["model"], kinds["browser"], total["PASS"], total["FAIL"], total["SKIPPED"]),
       "", "%d use cases, %d not fully proven." % (len(catalogue), bad), ""]
for area, cases in by_area.items():
    out += ["## " + area, "", "| Use case | Result | Tests |", "|---|---|---|"]
    for case, worst, rows in cases:
        detail = "<br>".join("%s%s" % (ref.split(":", 1)[1], "" if outcome == "PASS" else " (**%s**)" % outcome) for ref, outcome in rows)
        out.append("| %s %s | %s | %s |" % (case["id"], case["title"], worst, detail))
    out.append("")
if unmapped:
    out += ["## Tests that belong to no use case", ""] + ["- " + u for u in unmapped] + [""]
os.makedirs("target", exist_ok=True)
open("target/test-report.md", "w", encoding="utf-8", newline="\n").write("\n".join(out))

print(out[2])
print(out[4])
for area, cases in by_area.items():
    for case, worst, rows in cases:
        if worst != "PASS":
            print("  %s %s: %s" % (case["id"], worst, ", ".join(ref for ref, outcome in rows if outcome != "PASS")))
if unmapped:
    print("tests that belong to no use case:", ", ".join(unmapped))
print("report: target/test-report.md")
# a live test that was skipped because its system was not named (a broker, a queue manager) leaves its use case
# "not fully proven" in this run, which is said above; it is not a failure of the platform, so it does not fail the run
hard = sum(1 for cases in by_area.values() for case, worst, rows in cases if worst not in ("PASS", "SKIPPED"))
sys.exit(1 if hard or unmapped or total["FAIL"] else 0)
