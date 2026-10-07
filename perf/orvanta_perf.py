#!/usr/bin/env python3
"""Load generation, measurement and reconciliation for Orvanta Pay.

Every run does the same three things: submit generated payment files, wait until every payment has
reached its final status, and reconcile: each submitted payment exists exactly once, ended the way
its content says it must, sits in exactly one outbound file if it was sent, and was posted once
(and reversed if it failed after posting). A run that loses or duplicates a payment fails, whatever
its speed.

    python perf/orvanta_perf.py load --files 10 --size 500          10 files of 500 payments
    python perf/orvanta_perf.py load --files 60 --size 20 --rate 2   2 files a second (soak)
    python perf/orvanta_perf.py latency --count 30                   single payments, one after another
    python perf/orvanta_perf.py instant --count 30                   SEPA Instant payments

Options: --url (default http://localhost:8580), --user and --password (default admin and
ORVANTA_ADMIN_PASSWORD from .env), --json FILE to save the figures.
"""
import argparse
import datetime
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
import zoneinfo

FINAL = {"ACCEPTED", "REJECTED_BY_APPLICATION", "REJECTED_BY_EXTERNAL", "CANCELLED", "RETURNED"}


class Api:
    def __init__(self, url, user, password):
        self.url = url.rstrip("/")
        self.token = self.call("POST", "/api/auth/login", {"username": user, "password": password}, auth=False)["token"]

    def call(self, method, path, body=None, raw=None, auth=True):
        data = raw.encode("utf-8") if raw is not None else (json.dumps(body).encode() if body is not None else None)
        headers = {"Content-Type": "text/plain; charset=utf-8" if raw is not None else "application/json"}
        if auth:
            headers["Authorization"] = "Bearer " + self.token
        last = None
        for attempt in range(8):   # the API may be briefly unavailable in a fault test; the load generator must not give up
            try:
                with urllib.request.urlopen(urllib.request.Request(self.url + path, data=data, method=method, headers=headers), timeout=60) as r:
                    return json.loads(r.read() or b"null")
            except urllib.error.HTTPError as e:
                if e.code < 500:
                    raise RuntimeError("%s %s answered %d: %s" % (method, path, e.code, e.read()[:200]))
                last = e
            except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
                last = e
            time.sleep(2)
        raise RuntimeError("%s %s failed: %s" % (method, path, last))


def parse(ts):
    return datetime.datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp()


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p / 100.0 * (len(ordered) - 1))))]


def stats(values):
    return None if not values else {"p50": round(percentile(values, 50), 3), "p95": round(percentile(values, 95), 3),
                                    "p99": round(percentile(values, 99), 3), "max": round(max(values), 3)}


# ---- generated files: the creditor name decides what the simulated systems answer ----

def credit_transfer_file(msg_id, size, rng, error_mix):
    expected = {}
    txns = []
    total = 0
    for i in range(size):
        e2e = "%s-%05d" % (msg_id, i)
        amount = rng.randint(1000, 500000) / 100.0
        name, outcome = "Supplier %d" % rng.randint(1, 9999), "ACCEPTED"
        roll = rng.random()
        if error_mix and roll < 0.02:
            amount, outcome = 0.0, "REJECTED_BY_APPLICATION"
        elif error_mix and roll < 0.03:
            name, outcome = "BLOCKED Trading %d" % i, "REJECTED_BY_APPLICATION"
        elif error_mix and roll < 0.04:
            name, outcome = "REJECTME Closed %d" % i, "REJECTED_BY_EXTERNAL"
        total += round(amount * 100)
        expected[e2e] = outcome
        txns.append('<CdtTrfTxInf><PmtId><EndToEndId>%s</EndToEndId></PmtId><Amt><InstdAmt Ccy="ZAR">%.2f</InstdAmt></Amt>'
                    '<CdtrAgt><FinInstnId><BICFI>FIRNZAJJ</BICFI></FinInstnId></CdtrAgt><Cdtr><Nm>%s</Nm></Cdtr>'
                    '<CdtrAcct><Id><Othr><Id>62%09d</Id></Othr></Id></CdtrAcct><RmtInf><Ustrd>Load test</Ustrd></RmtInf></CdtTrfTxInf>'
                    % (e2e, amount, name, rng.randint(0, 999999999)))
    xml = ('<?xml version="1.0" encoding="UTF-8"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"><CstmrCdtTrfInitn>'
           '<GrpHdr><MsgId>%s</MsgId><CreDtTm>%s</CreDtTm><NbOfTxs>%d</NbOfTxs><CtrlSum>%.2f</CtrlSum><InitgPty><Nm>Load Test</Nm></InitgPty></GrpHdr>'
           '<PmtInf><PmtInfId>%s-1</PmtInfId><PmtMtd>TRF</PmtMtd><ReqdExctnDt><Dt>%s</Dt></ReqdExctnDt><Dbtr><Nm>Karoo Mining Supplies</Nm></Dbtr>'
           '<DbtrAcct><Id><Othr><Id>4051122334</Id></Othr></Id></DbtrAcct><DbtrAgt><FinInstnId><BICFI>ORVAZAJJ</BICFI></FinInstnId></DbtrAgt>'
           '%s</PmtInf></CstmrCdtTrfInitn></Document>'
           % (msg_id, datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S"), size, total / 100.0, msg_id,
              datetime.date.today().isoformat(), "".join(txns)))
    return xml, expected


def instant_file(msg_id):
    today = datetime.datetime.now(zoneinfo.ZoneInfo("Europe/Berlin")).date().isoformat()
    xml = ('<?xml version="1.0" encoding="UTF-8"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"><CstmrCdtTrfInitn>'
           '<GrpHdr><MsgId>%s</MsgId><CreDtTm>%sT00:00:00</CreDtTm><NbOfTxs>1</NbOfTxs></GrpHdr><PmtInf><PmtInfId>%s-1</PmtInfId><PmtMtd>TRF</PmtMtd>'
           '<PmtTpInf><SvcLvl><Cd>SEPA</Cd></SvcLvl><LclInstrm><Cd>INST</Cd></LclInstrm></PmtTpInf><ReqdExctnDt><Dt>%s</Dt></ReqdExctnDt>'
           '<Dbtr><Nm>Rheinland Pumpen GmbH</Nm></Dbtr><DbtrAcct><Id><IBAN>DE89370400440532013000</IBAN></Id></DbtrAcct>'
           '<DbtrAgt><FinInstnId><BICFI>DEUTDEFF</BICFI></FinInstnId></DbtrAgt><ChrgBr>SLEV</ChrgBr>'
           '<CdtTrfTxInf><PmtId><EndToEndId>%s</EndToEndId></PmtId><Amt><InstdAmt Ccy="EUR">150.00</InstdAmt></Amt>'
           '<CdtrAgt><FinInstnId><BICFI>BPPIITRR</BICFI></FinInstnId></CdtrAgt><Cdtr><Nm>Trattoria Bella Srl</Nm></Cdtr>'
           '<CdtrAcct><Id><IBAN>IT60X0542811101000000123456</IBAN></Id></CdtrAcct></CdtTrfTxInf></PmtInf></CstmrCdtTrfInitn></Document>'
           % (msg_id, today, msg_id, today, msg_id))
    return xml, {msg_id: "ACCEPTED"}


# ---- one run ----

def submit(api, xml, name):
    started = time.time()
    message = api.call("POST", "/api/inbound?fileName=%s.xml" % name, raw=xml)["messages"][0]
    if message["status"] != "RECEIVED":
        raise RuntimeError("file %s was not accepted: %s %s" % (name, message.get("reasonCode"), message.get("reasonText")))
    return message["id"], started


def wait_final(api, instructions, expected_total, timeout, progress=True):
    """Polls until every payment of every instruction is final. Returns the transactions by instruction."""
    deadline = time.time() + timeout
    done = {}
    last_print = 0
    while time.time() < deadline:
        for ins in instructions:
            if ins in done:
                continue
            items = api.call("GET", "/api/transactions?instructionId=%s&limit=1000" % ins)["items"]
            message = api.call("GET", "/api/messages/" + ins)
            if message["status"] == "REJECTED":
                raise RuntimeError("instruction %s was rejected: %s %s" % (ins, message.get("reasonCode"), message.get("reasonText")))
            if message["status"] == "DEBULKED" and len(items) == message["transactionCount"] and all(t["status"] in FINAL for t in items):
                done[ins] = (message, items)
        if len(done) == len(instructions):
            return done
        if progress and time.time() - last_print > 15:
            last_print = time.time()
            counts = api.call("GET", "/api/dashboard")["transactions"]
            print("   ... %d of %d files finished; %s" % (len(done), len(instructions), {k: v for k, v in counts.items() if v}), flush=True)
        time.sleep(1)
    raise RuntimeError("timed out: %d of %d files finished after %d s" % (len(done), len(instructions), timeout))


def reconcile(api, done, expected):
    """Returns the list of problems; empty when every payment is accounted for."""
    problems = []
    seen = {}
    outbound_of = {}
    for ins, (message, items) in done.items():
        for t in items:
            e2e = t["endToEndId"]
            if e2e in seen:
                problems.append("payment %s exists twice (%s and %s)" % (e2e, seen[e2e], t["id"]))
            seen[e2e] = t["id"]
            want = expected.get(e2e)
            if want is None:
                problems.append("payment %s was never submitted" % e2e)
            elif t["status"] != want:
                problems.append("payment %s ended %s (%s), expected %s" % (e2e, t["status"], t.get("reasonCode"), want))
            sent = t["status"] in ("ACCEPTED", "REJECTED_BY_EXTERNAL")
            if sent:
                if not t.get("outboundId"):
                    problems.append("payment %s was sent but names no outbound file" % e2e)
                else:
                    outbound_of.setdefault(t["outboundId"], []).append(t["id"])
            elif t.get("outboundId"):
                problems.append("payment %s was not sent but is in outbound file %s" % (e2e, t["outboundId"]))
            posting = (t.get("posting") or {}).get("status")
            if t["status"] == "ACCEPTED" and posting != "POSTED":
                problems.append("accepted payment %s has posting %s" % (e2e, posting))
            if t["status"] == "REJECTED_BY_EXTERNAL" and posting not in ("REVERSED", "POSTED"):
                problems.append("externally rejected payment %s has posting %s" % (e2e, posting))
            if t["status"] == "REJECTED_BY_APPLICATION" and posting is not None:
                problems.append("payment %s was rejected by the application but has posting %s" % (e2e, posting))
    for e2e in expected:
        if e2e not in seen:
            problems.append("payment %s was submitted and is LOST" % e2e)
    # every outbound file must list exactly the payments that name it
    for out_id, txn_ids in outbound_of.items():
        listed = api.call("GET", "/api/outbound/" + out_id)["transactionIds"]
        missing = set(txn_ids) - set(listed)
        if missing:
            problems.append("outbound file %s does not list %d payment(s) that name it" % (out_id, len(missing)))
        if len(listed) != len(set(listed)):
            problems.append("outbound file %s lists a payment twice" % out_id)
    return problems, len(outbound_of)


def await_reversals(api, done, timeout=180):
    """A reversal follows the rejection by a moment; give it time before judging."""
    pending = [t["id"] for _, items in done.values() for t in items
               if t["status"] == "REJECTED_BY_EXTERNAL" and (t.get("posting") or {}).get("status") == "POSTED"]
    deadline = time.time() + timeout
    while pending and time.time() < deadline:
        pending = [i for i in pending if (api.call("GET", "/api/transactions/" + i).get("posting") or {}).get("status") != "REVERSED"]
        if pending:
            time.sleep(2)
    return pending


def measure(done, started_at):
    received = min(parse(m["receivedAt"]) for m, _ in done.values())
    finished = max(parse(t["updatedAt"]) for _, items in done.values() for t in items)
    total = sum(len(items) for _, items in done.values())
    to_routed, to_sent, to_final = [], [], []
    for message, items in done.values():
        file_received = parse(message["receivedAt"])
        for t in items:
            if t.get("routedAt"):
                to_routed.append(parse(t["routedAt"]) - file_received)
            if t.get("sentAt"):
                to_sent.append(parse(t["sentAt"]) - file_received)
            to_final.append(parse(t["updatedAt"]) - file_received)
    elapsed = finished - received
    by_status = {}
    for _, items in done.values():
        for t in items:
            by_status[t["status"]] = by_status.get(t["status"], 0) + 1
    return {"payments": total, "files": len(done), "seconds": round(elapsed, 1), "paymentsPerSecond": round(total / elapsed, 1) if elapsed > 0 else None,
            "outcomes": by_status, "secondsFileReceivedToRouted": stats(to_routed), "secondsFileReceivedToSent": stats(to_sent),
            "secondsFileReceivedToFinal": stats(to_final)}


def run_load(api, files, size, rate, error_mix, timeout, tag):
    rng = random.Random(42)
    expected, instructions = {}, []
    prefix = "%s%s" % (tag, datetime.datetime.now().strftime("%m%d%H%M%S"))
    print("submitting %d file(s) of %d payment(s)%s" % (files, size, " at %.1f files a second" % rate if rate else ""), flush=True)
    for n in range(files):
        xml, exp = credit_transfer_file("%s-%03d" % (prefix, n), size, rng, error_mix)
        expected.update(exp)
        ins, _ = submit(api, xml, "%s-%03d" % (prefix, n))
        instructions.append(ins)
        if rate:
            time.sleep(1.0 / rate)
    done = wait_final(api, instructions, len(expected), timeout)
    unreversed = await_reversals(api, done)
    done = {ins: (api.call("GET", "/api/messages/" + ins), api.call("GET", "/api/transactions?instructionId=%s&limit=1000" % ins)["items"]) for ins in done}
    problems, outbound_files = reconcile(api, done, expected)
    problems += ["posting of %s was not reversed" % i for i in unreversed]
    result = measure(done, None)
    result["outboundFiles"] = outbound_files
    result["reconciliation"] = "OK" if not problems else "FAILED"
    result["problems"] = problems[:20]
    return result


def run_singles(api, count, instant, timeout):
    """One payment per file, one after another: the time a single payment takes, without queueing behind others."""
    seconds = []
    prefix = ("INS" if instant else "ONE") + datetime.datetime.now().strftime("%m%d%H%M%S")
    for n in range(count):
        msg_id = "%s-%03d" % (prefix, n)
        xml, expected = instant_file(msg_id) if instant else credit_transfer_file(msg_id, 1, random.Random(n), False)
        ins, started = submit(api, xml, msg_id)
        done = wait_final_fast(api, ins, timeout)
        status = done["status"]
        if status != "ACCEPTED":
            raise RuntimeError("payment %s ended %s %s %s" % (msg_id, status, done.get("reasonCode"), done.get("reasonText")))
        seconds.append(time.time() - started)
    return {"payments": count, "secondsSubmitToAccepted": stats(seconds), "mean": round(sum(seconds) / len(seconds), 3)}


def wait_final_fast(api, ins, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        items = api.call("GET", "/api/transactions?instructionId=" + ins)["items"]
        if items and items[0]["status"] in FINAL | {"WAREHOUSED", "REPAIR", "HELD"}:
            return items[0]
        time.sleep(0.05)
    raise RuntimeError("payment of %s did not finish in %d s" % (ins, timeout))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=["load", "latency", "instant"])
    parser.add_argument("--url", default="http://localhost:8580")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password")
    parser.add_argument("--files", type=int, default=4)
    parser.add_argument("--size", type=int, default=250)
    parser.add_argument("--rate", type=float, default=0, help="files a second; 0 submits them as fast as possible")
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--no-errors", action="store_true", help="only payments that will be accepted")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--tag", default="LT")
    parser.add_argument("--json")
    args = parser.parse_args()
    if args.size > 1000:
        sys.exit("--size is limited to 1000 payments per file (the transaction list of the API returns at most 1000)")
    password = args.password
    if not password:
        env = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env")
        password = dict(l.strip().split("=", 1) for l in open(env) if "=" in l)["ORVANTA_ADMIN_PASSWORD"]
    api = Api(args.url, args.user, password)
    if args.mode == "load":
        result = run_load(api, args.files, args.size, args.rate, not args.no_errors, args.timeout, args.tag)
    else:
        result = run_singles(api, args.count, args.mode == "instant", args.timeout)
    print(json.dumps(result, indent=2))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(result, f, indent=2)
    sys.exit(1 if result.get("reconciliation") == "FAILED" else 0)


if __name__ == "__main__":
    main()
