#!/usr/bin/env python3
"""Looks for secrets that must not be in the repository: private keys, cloud and API tokens, and passwords
or keys written out in configuration and models instead of taken from the environment.

    python bin/secret-scan.py [root]

Exits 1 when something is found, so it can gate a build. A finding that is accepted on purpose (for example
the credentials of a shared development queue manager) is listed in .secret-scan-allow, one per line as
"<path>:<line text fragment> -- <reason>"; it is then reported as accepted, not as a finding. Test sources
are checked for keys and tokens but not for passwords: tests sign in with passwords of their own.
"""
import os
import re
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "."
SKIP_DIRS = {".git", "target", "node_modules", "test-results", "playwright-report", ".idea", "data"}
TEXT = (".java", ".js", ".ts", ".yaml", ".yml", ".json", ".properties", ".md", ".sh", ".cmd", ".xml", ".py", ".env", ".txt", ".cfg", ".conf", ".toml", ".ini", ".html", ".css", "")

RULES = [
    ("private key", re.compile(r"-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY( BLOCK)?-----")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b")),
    ("Slack token", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("JWT", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b")),
    ("Orvanta API key", re.compile(r"\borv_[a-z0-9-]+_[A-Za-z0-9]{32,}\b")),
    ("connection string with password", re.compile(r"\b[a-z][a-z0-9+.-]*://[^/\s:@]+:[^/\s@]+@[^\s]+", re.IGNORECASE)),
]
# a password, secret, token or key given a literal value in configuration or a model (not a placeholder, not empty)
LITERAL = re.compile(r"^\s*-?\s*(?:[A-Za-z_.]*(?:password|passphrase|secret|apiKey|api_key|token|clientSecret))\s*[:=]\s*(['\"]?)([^'\"\s#][^'\"#]*)\1\s*(#.*)?$", re.IGNORECASE)
PLACEHOLDER = re.compile(r"\$\{env\.[A-Za-z_][A-Za-z0-9_]*(:-([^}]*))?}")
HARMLESS_VALUES = {"", "null", "none", "true", "false", "cookie", "required", "optional"}
HARMLESS_FRAGMENTS = ("example", "placeholder", "changeme", "change-me", "<", "your-", "...", "set ORVANTA", "see ")
# keys that name where a secret is kept, not the secret itself (Kubernetes secret names and references)
REFERENCE_KEYS = ("existingsecret", "secretname", "secretref", "secretkeyref")

allow = {}
allow_path = os.path.join(root, ".secret-scan-allow")
if os.path.exists(allow_path):
    for line in open(allow_path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and " -- " in line:
            key, reason = line.split(" -- ", 1)
            allow[key.strip()] = reason.strip()

# what git ignores is not in the repository and is not scanned: the local seed file, .env, key stores
ignored_dirs, ignored_exts, ignored_paths = set(), set(), set()
gitignore = os.path.join(root, ".gitignore")
if os.path.exists(gitignore):
    for line in open(gitignore, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.endswith("/"):
            ignored_dirs.add(line.rstrip("/"))
        elif line.startswith("*."):
            ignored_exts.add(line[1:].lower())
        else:
            ignored_paths.add(line)

findings = []
accepted = []
skipped = []


def report(path, number, text, what):
    key = "%s:%s" % (path, text.strip()[:60])
    for allowed, reason in allow.items():
        allowed_path, _, fragment = allowed.partition(":")
        if allowed_path == path and fragment and fragment in text:
            accepted.append((path, number, what, reason))
            return
    findings.append((path, number, what, text.strip()[:120]))


for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and d not in ignored_dirs]
    for name in filenames:
        _, ext = os.path.splitext(name)
        if ext.lower() not in TEXT or name == "secret-scan.py":
            continue
        path = os.path.relpath(os.path.join(dirpath, name), root).replace("\\", "/")
        if path in ignored_paths or name in ignored_paths or ext.lower() in ignored_exts:
            skipped.append(path)
            continue
        try:
            lines = open(os.path.join(dirpath, name), encoding="utf-8", errors="replace").read().split("\n")
        except OSError:
            continue
        is_test = "/src/test/" in path or path.startswith("console-tests/") or "/tests/" in path
        is_config = ext.lower() in (".yaml", ".yml", ".properties", ".env", ".json", ".cfg", ".conf", ".toml", ".ini") or "/config/" in path
        for number, text in enumerate(lines, 1):
            for what, pattern in RULES:
                if pattern.search(text):
                    m = pattern.search(text)
                    # a connection string whose credentials are a placeholder or the well-known local default is fine
                    if what.startswith("connection string") and ("${env." in m.group(0) or "guest:guest@localhost" in m.group(0) or "user:password@" in m.group(0)):
                        continue
                    report(path, number, text, what)
            if is_config and not is_test:
                m = LITERAL.match(text)
                if m and text.split(":")[0].split("=")[0].strip().lstrip("- ").lower() in REFERENCE_KEYS:
                    m = None
                if m:
                    value = m.group(2).strip()
                    if value.lower() in HARMLESS_VALUES or any(f in value.lower() for f in HARMLESS_FRAGMENTS):
                        continue
                    placeholder = PLACEHOLDER.fullmatch(value)
                    if placeholder:
                        default = placeholder.group(2) or ""
                        if default == "" or default.lower() in HARMLESS_VALUES:
                            continue
                        # a placeholder whose default is a real-looking value still ships that value
                        report(path, number, text, "secret default in a placeholder")
                        continue
                    if "${" in value:
                        continue
                    report(path, number, text, "secret written out in configuration")

for path in skipped:
    print("skipped   %s  (ignored by git, so not in the repository)" % path)
for path, number, what, reason in accepted:
    print("accepted  %s:%d  %s -- %s" % (path, number, what, reason))
for path, number, what, text in findings:
    print("FINDING   %s:%d  %s: %s" % (path, number, what, text))
print("%d finding(s), %d accepted" % (len(findings), len(accepted)))
sys.exit(1 if findings else 0)
