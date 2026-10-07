#!/usr/bin/env python3
"""Checks the libraries in the software bill of materials against the OSV vulnerability database.

    mvnw -Prelease install -DskipTests     (writes target/bom.json)
    python bin/osv-scan.py [target/bom.json]

Sends only library names and versions to api.osv.dev. Exits 1 when a vulnerability is known for a
library we ship, so it can gate a build. A quick complement to the Dependency-Check profile, which
needs the full NVD database.
"""
import json
import sys
import urllib.request

bom_path = sys.argv[1] if len(sys.argv) > 1 else "target/bom.json"
components = [c for c in json.load(open(bom_path, encoding="utf-8")).get("components", []) if c.get("purl")]
# our own modules are not in any vulnerability database
components = [c for c in components if not c["purl"].startswith("pkg:maven/io.orvanta/")]


def post(url, body):
    request = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


results = post("https://api.osv.dev/v1/querybatch", {"queries": [{"package": {"purl": c["purl"].split("?")[0]}} for c in components]})["results"]
found = 0
print("%d libraries checked" % len(components))
for component, result in zip(components, results):
    for vuln in result.get("vulns", []):
        found += 1
        detail = {}
        try:
            with urllib.request.urlopen("https://api.osv.dev/v1/vulns/" + vuln["id"], timeout=60) as response:
                detail = json.load(response)
        except Exception as error:  # the id alone is still worth reporting
            detail = {"summary": "(details not available: %s)" % error}
        fixed = sorted({event["fixed"] for affected in detail.get("affected", []) for r in affected.get("ranges", [])
                        for event in r.get("events", []) if "fixed" in event})
        aliases = [a for a in detail.get("aliases", []) if a.startswith("CVE-")]
        print("  %s:%s %s  %s %s\n      %s\n      fixed in: %s" % (
            component.get("group", ""), component["name"], component["version"], vuln["id"], " ".join(aliases),
            (detail.get("summary") or "").strip()[:160], ", ".join(fixed) or "no fixed version listed"))
print("%d known vulnerabilit%s" % (found, "y" if found == 1 else "ies"))
sys.exit(1 if found else 0)
