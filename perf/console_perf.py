#!/usr/bin/env python3
"""Times the list and dashboard requests of the Console on a store with many payments.

    python perf/console_perf.py seed  --database orvanta_perf --count 100000
    (start a server on that database:  java -jar orvanta-pay/target/orvanta-pay-0.1.0-all.jar
        --config=config/orvanta.yaml --store.database=orvanta_perf --units=api --server.port=8499 --workspace.writeBack=false)
    python perf/console_perf.py time  --url http://localhost:8499 --user operator1
    python perf/console_perf.py drop  --database orvanta_perf

'seed' writes made-up payments straight into MongoDB; it refuses the database named in config/orvanta.yaml.
'time' signs in with the password of the user from config/seed-users.yaml and prints the median and the
slowest of several runs per request, in milliseconds, measured at the client.
"""
import argparse
import datetime
import json
import os
import random
import re
import statistics
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATUSES = [("ACCEPTED", 86), ("REJECTED_BY_APPLICATION", 5), ("REJECTED_BY_EXTERNAL", 3), ("SENT", 2), ("HELD", 1),
            ("REPAIR", 1), ("WAITING", 1), ("CANCELLED", 1)]
ROUTES = [("ZA-RTC", "channels.ZaRtcOutbound", "ZAR"), ("SEPA-SCT", "rails.sepa.SctOutbound", "EUR"),
          ("SEPA-INST", "rails.sepa.SctInstOutbound", "EUR"), ("SWIFT", "channels.SwiftMtOutbound", "USD")]
FIRST = ["Thandiwe", "Pieter", "Aisha", "Johan", "Lerato", "Camille", "Marco", "Sophie", "Nomsa", "David", "Fatima", "Lukas"]
LAST = ["Mokoena", "van der Merwe", "Khan", "Botha", "Dlamini", "Laurent", "Rossi", "Peeters", "Zulu", "Naidoo", "Schmidt", "Okafor"]


def configured_database():
    text = open(os.path.join(ROOT, "config", "orvanta.yaml"), encoding="utf-8").read()
    m = re.search(r"^store:.*?^\s+database:\s*(\S+)", text, re.S | re.M)
    return m.group(1) if m else "orvanta"


def seed(args):
    import pymongo
    from bson.decimal128 import Decimal128
    if args.database == configured_database():
        sys.exit("refusing to write test payments into '%s', the database of config/orvanta.yaml" % args.database)
    coll = pymongo.MongoClient(args.mongo)[args.database]["orv_transaction"]
    rnd = random.Random(7)
    now = datetime.datetime.now(datetime.timezone.utc)
    weighted = [s for s, w in STATUSES for _ in range(w)]
    batch, started = [], time.time()
    for n in range(1, args.count + 1):
        scheme, channel, currency = rnd.choice(ROUTES)
        status = rnd.choice(weighted)
        created = now - datetime.timedelta(seconds=rnd.randint(0, 7 * 86400 - 1))
        stamp = created.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % rnd.randint(0, 999)
        txn_id = "PRFTXN%010d" % n
        doc = {"_id": txn_id, "id": txn_id, "instructionId": "PRFINS%010d" % (n // 500), "batchId": "PRFBAT%010d" % (n // 100),
               "channelIn": "channels.CorporateIsoInbound", "endToEndId": "E2E-%08d" % n,
               "amount": Decimal128("%d.%02d" % (rnd.randint(1, 250000), rnd.randint(0, 99))), "currency": currency,
               "debtor": {"name": "Karoo Mining Supplies (Pty) Ltd", "account": "40511%05d" % rnd.randint(0, 200)},
               "creditor": {"name": rnd.choice(FIRST) + " " + rnd.choice(LAST), "account": "62%09d" % rnd.randint(0, 999999999), "agentBic": "FIRNZAJJ"},
               "remittance": "Invoice %d" % rnd.randint(10000, 99999), "status": status, "createdAt": stamp, "updatedAt": stamp}
        if status not in ("REJECTED_BY_APPLICATION", "HELD", "REPAIR", "WAITING"):
            doc["route"] = {"scheme": scheme, "channel": channel}
        else:
            doc["reasonCode"], doc["reasonText"] = "AM02", "Made up for the timing test"
        batch.append(doc)
        if len(batch) == 5000:
            coll.insert_many(batch, ordered=False)
            batch = []
    if batch:
        coll.insert_many(batch, ordered=False)
    print("%d payments written to %s in %.1f s" % (args.count, args.database, time.time() - started))


def drop(args):
    import pymongo
    if args.database == configured_database():
        sys.exit("refusing to drop '%s', the database of config/orvanta.yaml" % args.database)
    pymongo.MongoClient(args.mongo).drop_database(args.database)
    print("dropped", args.database)


def password_of(user):
    text = open(os.path.join(ROOT, "config", "seed-users.yaml"), encoding="utf-8").read()
    m = re.search(r"username:\s*%s\b.*?password:\s*\"?([^\"\r\n]+)\"?" % re.escape(user), text, re.S)
    if not m:
        sys.exit("no user %s in config/seed-users.yaml" % user)
    return m.group(1).strip()


def call(url, token=None, body=None):
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method="GET" if body is None else "POST")
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read().decode() or "{}")


def timed(args):
    token = call(args.url + "/api/auth/login", body={"username": args.user, "password": password_of(args.user)})["token"]
    total = call(args.url + "/api/transactions?limit=1", token)["total"]
    middle = "PRFTXN%010d" % (total // 2)
    requests = [
        ("first page, newest first", "/api/transactions?limit=50"),
        ("page 100 (offset 5,000)", "/api/transactions?limit=50&offset=5000"),
        ("last page (offset near the end)", "/api/transactions?limit=50&offset=%d" % max(0, total - 50)),
        ("one status", "/api/transactions?limit=50&status=REPAIR"),
        ("status and route", "/api/transactions?limit=50&status=ACCEPTED&scheme=SWIFT"),
        ("one day", "/api/transactions?limit=50&from={0}&to={0}".format((datetime.date.today() - datetime.timedelta(days=2)).isoformat())),
        ("amount range", "/api/transactions?limit=50&minAmount=100000&maxAmount=100500"),
        ("sorted by amount", "/api/transactions?limit=50&sort=amount"),
        ("sorted by last change", "/api/transactions?limit=50&sort=updatedAt"),
        ("search: a whole id", "/api/transactions?limit=50&q=" + middle),
        ("search: part of a name", "/api/transactions?limit=50&q=mokoena"),
        ("search: text found nowhere", "/api/transactions?limit=50&q=zzzzqqqq"),
        ("search within one status", "/api/transactions?limit=50&status=HELD&q=khan"),
        ("one payment", "/api/transactions/" + middle),
        ("dashboard", "/api/dashboard"),
    ]
    rows = []
    print("%d payments in the store; %d runs per request\n" % (total, args.runs))
    print("%-36s %10s %10s %10s" % ("request", "median ms", "slowest ms", "matches"))
    for name, path in requests:
        times, matches = [], ""
        for _ in range(args.runs):
            started = time.perf_counter()
            reply = call(args.url + path, token)
            times.append((time.perf_counter() - started) * 1000)
            matches = reply.get("total", "")
        rows.append({"request": name, "path": path, "medianMs": round(statistics.median(times), 1), "slowestMs": round(max(times), 1), "matches": matches})
        print("%-36s %10.1f %10.1f %10s" % (name, statistics.median(times), max(times), matches))
    if args.json:
        os.makedirs(os.path.dirname(os.path.abspath(args.json)), exist_ok=True)
        json.dump({"payments": total, "runs": args.runs, "requests": rows}, open(args.json, "w"), indent=2)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["seed", "time", "drop"])
    p.add_argument("--mongo", default="mongodb://localhost:27017")
    p.add_argument("--database", default="orvanta_perf")
    p.add_argument("--count", type=int, default=100000)
    p.add_argument("--url", default="http://localhost:8499")
    p.add_argument("--user", default="operator1")
    p.add_argument("--runs", type=int, default=7)
    p.add_argument("--json")
    a = p.parse_args()
    {"seed": seed, "time": timed, "drop": drop}[a.command](a)
