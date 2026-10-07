# Orvanta roadmap

Phase 1 (done, 2026-10-04): Forge compiler, Core runtime, Pay engine (receive, debulk, validate, enrich,
external call, route, bulk, send, acknowledgement, rejection by application and by external system, repair),
Console with authentication, role permissions, maker-checker and Studio. See README.md.

Each phase below ends with exit criteria: the checks that must pass before the phase counts as done.

## Phase 2: payment hub core

Too large for one step, so it is five sub-phases. Each is usable on its own. 2B comes first because
everything after it depends on the tool being able to talk to other systems and to data.

### 2A: complete the credit transfer lifecycle

Done 2026-10-04:
1. Status report back to the customer (pain.002), including repeat reports when a status changes later.
2. Cancellation: camt.055 from the customer or an operator request; immediate before sending, camt.056
   forwarded and camt.029 answer applied after sending.
3. Return of funds (pacs.004) for payments already accepted.
5. Recovery of interrupted work, automatic retry with back-off, dead-letter list with requeue.
6. Maker-checker on payment actions (resubmit, cancel).

Open:
4. Refund: a debtor's refund of a direct debit is handled (it arrives as a return). A refund of a credit
   transfer we received still needs incoming credit transfers, which do not exist yet.
7. Done 2026-10-05: MT192 cancellation in, answered with MT196. Done 2026-10-06: MT199 status messages for MT101.
8. Done 2026-10-05: camt.029 back to the customer for every cancellation request, cancelled, refused or pending,
   and again when the receiving side has decided.

### 2B: tool capabilities for integration (Forge and Core)

What the tool cannot do today and must learn, as model kinds and flow steps:

1. **Connector types**: REST client with any method, URL placeholders, headers, basic, bearer and header
   authentication, timeouts and retry (done 2026-10-04). OAuth2 client credentials, a circuit breaker and mutual TLS done 2026-10-06. Open:
   SOAP client from a WSDL; MQ send and request-reply (RabbitMQ, IBM MQ, Kafka); database connector.
2. **Data access from models**: `DataSet` model kind with `find`, `save` and `remove` flow steps, on the
   document store (MongoDB), with read-only views of engine data (done 2026-10-04). Range, "in" and "not" conditions and pages (offset) done 2026-10-06. JDBC data sets (`table` and `datasource`, any driver on the classpath; tested with H2) done 2026-10-07; MySQL itself is to be tried against the bank's database.
3. **API creation from models**: `Api` model kind serving a flow as a REST endpoint with path, method and
   required permission (done 2026-10-04). API keys for system callers and an OpenAPI description done 2026-10-06. SOAP operations (`soap: {operation}` on an Api model, `POST /api/soap`, WSDL at `GET /api/soap?wsdl`) done 2026-10-07.
4. **Formats**: JSON as a message format (done 2026-10-04); delimited and fixed-width records through the `Format` model kind
   (done 2026-10-06: a layout is a model, read and written by it; sample `formats.PaymentsFixed` on `channels.FlatFileInbound`). Open: a NACHA layout
   itself, to be written from the specification.
5. **Reference data**: a `ReferenceTable` model kind with `lookup()` in expressions, maintained through
   Studio with maker-checker like any model (done 2026-10-04, with a sample BIC directory). A table
   editor in the Studio and CSV upload done 2026-10-06. Open: the calendar, cut-off and limit tables themselves (the bank's data).
6. **Flow steps**: `forEach` (done 2026-10-04), `parallel` (done 2026-10-06: several external calls at once), `wait` (done 2026-10-07:
   for an asynchronous answer or a time, with its own time limit). `task` (done 2026-10-07: a human task in a named operator queue with instructions, listed per queue in the review queue). Nothing open here.

### 2C: ingress and egress framework (Pay)

Channel models carry the protocol as configuration, per channel, with one ingest path behind all of them.

Done 2026-10-04: inbound `transport` of type folder, rabbitmq and rest (push with a per-channel API key);
outbound `destination` of type folder, http (with headers and authentication) and rabbitmq (with broker
confirmation); listeners follow the active deployment without a restart; per-channel size limit; replay
of a rejected message with approval.

Done 2026-10-06: pulling from a remote REST endpoint (transport type `http`, with authentication and acknowledgement).
Done 2026-10-06: SOAP as a connector type (calling an operation of a web service).
Done 2026-10-06: SFTP in and out (Apache MINA SSHD), with the server's key fingerprint required.
Done 2026-10-06: Kafka topics in and out (tested against a real broker when one is named). OpenPGP on folder and SFTP
transports and destinations (encrypt, sign, decrypt, verify; Bouncy Castle).
Done 2026-10-06 at the user's request: IBM MQ in and out (`type: ibmmq`), with the SBG development queue manager as the sample.
Checksum companion files and receiving SOAP (`/in/soap/<channel>`) done 2026-10-06.
Receipts per message stored on RabbitMQ, Kafka and IBM MQ transports done 2026-10-06. Rotation of partner keys without a redeployment done 2026-10-06 (a system's own key on `/in/<channel>`).

### 2D: external system calls in the lifecycle

Each is a Connector model, a `call` step in the flow, a canonical request and reply, an outcome mapping
(continue, reject, hold, repair) and a simulator so the flow can be tested without the real system:

| Call | When in the flow | Outcome if negative |
|---|---|---|
| Account lookup | after validation | reject (AC01/AC04) |
| Sanctions check | before routing (exists today, simulated) | hold for review or reject |
| Fraud check | before routing | hold for review |
| Compliance check | before routing | hold for review |
| FX rate sheet | when debit and credit currencies differ | repair if no rate |
| Merchant and partner liquidity check | before sending | queue until funded |
| Account posting (nostro, vostro, loro; debit, credit, reversal) | at acceptance, at send, on return | repair; never silently skipped |
| Document fetch and upload | when a rule demands supporting documents | hold until present |

Done 2026-10-04: every call in the table as a Connector model, a flow step, an outcome and a simulator
(one liquidity check stands for clearing, partner and merchant; documents are stored and fetched through
the platform's own API rather than an external document system). Engine pieces: HELD status with a review
queue and maker-checker release or rejection, WAITING status with late answers on a callback channel and
timed retries, `once` on call steps, idempotency keys, reversal of postings when a posted payment fails.

Open: connections to real systems; separate merchant and partner liquidity checks; posting at acceptance
and at settlement as distinct entries; an external document system.
Done 2026-10-06: an answer that refuses the request (HTTP 4xx, for example "no rate quoted") is CONNECTOR_REFUSED:
it goes straight to an operator with what the system said and is not retried; only outages (CONNECTOR_ERROR) are.

### 2E: validations, scheduling and rails

Done 2026-10-04: the rail pack structure with SEPA Credit Transfer (bulk) and SEPA Instant (real time);
routing by rail with the rail's own check flow; IBAN country and length check, SEPA character set with
transliteration, duplicate payment check with a five day window, method of payment on the route;
holiday calendar, cut-off times, date and time warehousing with a WAREHOUSED status; a fixable clock for tests.

Also done 2026-10-04: SWIFT direct, cover (MT103 + MT202 COV) and serial settlement, chosen by a decision
table over correspondent and relationship tables; inbound statements (camt.053, MT940) reconciled against
sent payments, with settlement confirmation for SWIFT payments and a balance check.

Also done 2026-10-04: SEPA Core Direct Debit for the creditor's bank: pain.008 in, mandate store with
its API and sequence rules, submission timing with warehousing, pacs.003 out, rejects and returns
(including the debtor's refund request) through the existing acknowledgement and return services.

Open: the other rails; balance-of-payments code derivation (needs the bank's code list); BIC directory
import; account number rules per country beyond IBAN; field-level rules per message type; the instant
10 second time-out; the debtor side of direct debits, mandate messages, B2B;
incoming credit transfers and their refund; cover in ISO 20022 (pacs.009 COV); intermediary banks;
intraday statements; statement generation (needs the ledger of phase 4).

**Common checks** (RuleSets and functions over reference tables, reusable by every rail):
BIC existence and enrichment, IBAN structure and national check digits, account number rules per country,
duplicate payment detection (configurable key and window), permitted character sets with transliteration,
field-level rules per message type, method-of-payment and balance-of-payments code derivation (DecisionTables).

**Scheduling**: business calendars per country, currency and rail; weekends and holidays; cut-off times;
date warehousing (future-dated payments) and time warehousing (after cut-off, release at next window).
Needs a WAREHOUSED status and a scheduler service.

**Rail packs**: a rail is not a Java module. It is a folder of models under `workspace/rails/<rail>/`:
channels, format mappings, rule sets, routing rows, calendar and cut-off tables, limits, and its tests.

| Rail | Format family | Notes |
|---|---|---|
| SWIFT (FIN and CBPR+) | MT and ISO 20022 | serial (pacs.008 chain, MT103) and cover method (pacs.008 + pacs.009 COV, MT103 + MT202 COV); needs correspondent and nostro data |
| SEPA CT, SEPA Instant | ISO 20022 (EPC rulebooks) | Instant needs a 10 second end-to-end budget |
| SEPA DD | ISO 20022 | mandate store, pre-notification, R-transactions (reject, return, refund, reversal) |
| RTGS, CHAPS, Fedwire | ISO 20022 | single high-value payments, no bulking |
| RTP (US) | ISO 20022 | real time, request for payment |
| FedACH | NACHA fixed-length | needs the fixed-length format from 2B |
| NEFT, IMPS, UPI (India) | ISO 20022 and scheme APIs | API based |

Start with two rails, one bulk and one real time, to prove the pack structure before adding the rest.
What a rail pack gives is format and rulebook conformance. Connecting to the real network also needs the
scheme's connectivity, certificates and certification, which are outside the codebase.

**Other message families**
- Statements and advices in (camt.052, camt.053, camt.054, MT940, MT942, MT900, MT910) for reconciliation
  against sent payments. Producing statements needs the account and posting ledger (phase 4).
- Customer direct debit: pain.008 in, pacs.003 out, mandate management (pain.009 to pain.012) with a
  mandate store and maker-checker, R-transactions.

Exit for phase 2: every flow has TestCase models and an end-to-end test; a process killed mid-run loses no
transaction; no external call can post twice; two rail packs pass their rulebook test sets.

Done 2026-10-05, from the leftovers of phase 2E: incoming SEPA Credit Transfers (pacs.008 in) credited to the
customer or sent back as pacs.004 with the reason; a sanctions match on the sender is held for a person;
refund of a credited payment with maker-checker, the credit reversed before the return is sent.
Also recall requests from the sender's bank (camt.056 in): sent back when not yet credited, a decision by two
people when credited, a camt.029 when refused, already returned or unknown.
Also the debtor side of SEPA Core Direct Debit (pacs.003 in): debited or sent back, a block or limit per
creditor that the customer sets, refund with reason MD06.
Also a Console page for customer instructions (limits, mandates, direct debit blocks): data sets that a model
offers with a `console` section, changed by requests with maker-checker.
Also debit and credit notifications to the customer (camt.054), with reversals, once per booking.
Also payments received over SWIFT (MT103 in): credited, converted at the quoted rate, or returned by MT103.
Also our charge on payments received over SWIFT: a tariff, deducted for SHA and BEN, a recorded claim for OUR.
Also the claim for OUR charges: sent as MT191, listed in the Console, marked as paid by two people.
Also incoming SEPA Instant (confirmed or refused at once with pacs.002) and incoming ZA-RTC, each on a channel
that names its own messages with `match`.
Also refund time limits for collections, reversal of a collection by the creditor's bank (pacs.007), and the
day a recall has to be answered by.
Also the time an instant payment has: one received that could not be finished in time is refused (AB05), one sent
without an answer in time is pointed out in the review queue. And notification preferences per account.
Also account reports for customers (camt.052): what was booked on an account in a period, on request and daily.
Statements with balances (camt.053) came with the ledger of phase 4.

From the VolPay rest-services (`VolPay3x/core/rest-services`, 424 cartridges read by name, compared 2026-10-06). Built the
same day: repair with field changes, notes on a payment, early release of a warehoused payment. Also 2026-10-06: approval limits by amount per role; CSV download and upload of a reference table with approval; payment initiation from a Console form with templates; single sign-on (OpenID Connect, tested against a stand-in provider). Worth taking next:
manual payment initiation from a form with templates; single sign-on (OIDC, Keycloak-compatible). Already there in
Orvanta's form: entity maintenance (reference tables, data sets, users, roles), matching forms (manual matching),
interface request/response views (connector calls on the payment), dashboards and monitoring, prevalidation,
reason codes, replay, participant and endpoint suspension. Not for Orvanta: Dodd-Frank disclosures, cheques,
wallets, gpi directory, billing reports, VolPay's trace-table services.

From the VolPay rails and implementation modules (`VolPay3x/oms`, `swift`, `implementation/*-impl`, `volpay-configs-1`,
`volpay-scripts-1`, `webapps-1`; read by names and configuration shapes, compared 2026-10-07). Already in Orvanta's form:
one process per unit (console, engine), the simulator with its interface schemas, versioned reference data through
deployments (their Liquibase scripts), manual payment initiation with templates, kill-switch-like channel pause and
participant suspension, skip-warehouse (release now), repair and reject actions with approval, incident codes, masked
fields, graceful stop, monitoring statuses, UETR on SWIFT output, cover and serial routing, debit/credit notifications,
statement reconciliation with manual matching, gpi-style status enquiries, investigations (camt.110/111 in spirit),
SAML attribute mapping (OIDC done; SAML open). Done 2026-10-07: per-country IBAN structures and the bank
code inside the IBAN (IBAN Plus: BIC derived when the customer gives none). Scheduled jobs as a model (`Schedule`: cron, time zone, job) done 2026-10-07. Done 2026-10-07: SWIFT relationship management (message types and validity per bank, checked before sending), text
that must fit an MT field (`fit()`), outbound message id templates. Done 2026-10-07: bulking grouped by fields, allowed category purposes per outbound channel, the one-click stop of all
sending with approval, time to route per channel in the daily report, suggested matches for unmatched statement entries.
Nothing of that list is open. Not for Orvanta: CBPR+ MT/MX
translation libraries and SRG release packs (the bank's licensed usage guidelines), cheques (camt.107-109), multi-tenant
spaces, their menu/resource/entitlement tables (roles and permissions cover it), dashboard widget configuration.

From Earthport's validation engine (`earthport/service/ve-webapp/src/main/volante`: global validations and enrichments,
route selection, PEA derivation, the notification switch; the payout validation specification; the bank-partner
validations; read by names and the specification, compared 2026-10-07). Already in Orvanta's form: IBAN check digits, BIC
format and directory enrichment, settlement date from cut-offs and calendars, FX at a quoted rate, per-transaction
account limits, routing by table, reachability, speed/priority on bulking, notifications. Taken: which identifier a
destination country needs and its format (their routing type per country), a clearing-code directory with BIC and
bank-code enrichment both ways and the check that an IBAN and a bank code given together agree, IBAN compose and
decompose, country account checks (the Dutch 11-test, Hungarian check digits, the UK modulus check with the bank's
loadable weight table), route minimum and maximum amounts, a daily cumulative limit per debtor account, corridor
restrictions (restricted currencies and country pairs). Not for Orvanta: the Accuity directory itself (licensed data;
the tables are loaded from the bank's copy), bank-partner-specific validations, the Visa payout API shapes.
All of the taken items done 2026-10-07 (increments 88 and 89). Still open from it: the UK modulus exceptions 1 to 14 (the
weight table loads, ranges with an exception are reported as not checked), and the directories themselves (country routing
rows, clearing codes both ways, Vocalink weights) which the bank loads through the Rows tab or CSV import.

## Phase 3: low-code tool depth

Drag-and-drop flow canvas and visual mapping editor; ISO 20022 XSD import and a shipped schema set;
MT field-level validation per message type; deployment rollback, model deletion and version diff;
role editing and data-level entitlements (by channel or party).

Done 2026-10-05: the flow designer in Studio (tab Design on every Flow, and New flow). Steps are dragged
from a palette onto the canvas, reordered by dragging, nested inside For each, and filled in through forms
that offer the deployed rule sets, mappings, decision tables, connectors and data sets. Validate, Test run
and Submit for approval work on the design. Comments of the model text are lost when it is changed there.

Also done 2026-10-05: a Deployments page with every version, what each changed (line by line) and what
going back to it would change now; rollback to an earlier deployment and removal of a model, both as
requests that a second person approves. A rollback makes a new version, so history is kept. A model that
is still used cannot be removed, and a rollback approved after another change came in is refused.

Also done 2026-10-05: designers for rule sets (a list of rules, each edited in a form, reordered by
dragging) and decision tables (a grid: one line per row, one column per field the rows set; rows moved by
dragging or with the arrows), with New rule set and New decision table.

Also done 2026-10-05: message checks at ingest. ISO 20022 schemas are imported in the Console (with
approval), kept in the store and used by every process; a problem names the element by its path. SWIFT MT
field rules are a new model kind, MessageSpec, with specifications for MT101, MT103, MT202 and MT940; a
problem names the field, its occurrence and the expected format. A Message checks page tries any message
against what is installed. What the engine itself sends (MT103, MT202 COV) is tested against the same rules.

Also done 2026-10-05: a designer for mappings (rules as a tree, nested inside loops and added records),
which completes the exit criterion: flows, rule sets, decision tables and mappings are built, tested and
deployed without typing YAML.

Forms for channels, connectors, data sets, APIs and reference tables done 2026-10-06 (the Design tab; nested transports,
destinations, authentication and bulking as sections). Test case forms done 2026-10-07; message specification forms (sequences and fields in a grid, letter options, what a field is checked as) done 2026-10-07. Open: a side-by-side source and target view for mappings,
which needs the message structures from the schemas; a shipped schema set (ISO 20022 schemas are
imported, not shipped); MT rules that relate one field to another (network validated rules) and
specifications for more MT types, all to be compared with the bank's SWIFT standards release.

Also done 2026-10-05: messages the platform builds (payment files, cancellation requests, status reports)
are checked against the same schemas and field rules before they are sent; an invalid payment file is not
created and its transactions go to REPAIR with the field named. `checkOutbound: false` on a channel
switches it off.

Also done 2026-10-05: roles of one's own (requested and approved under Users and roles; built-in roles are
fixed), and a limit per user to the payments of certain inbound channels, debtor accounts, or both,
enforced on every payment endpoint and shown in the Console. Limits by currency and by amount done 2026-10-07.
Open here: limits by branch or customer group (the canonical payment carries neither; they need a field the bank
fills at ingest); applying a limit inside model-defined APIs.

Exit: a flow can be built, tested and deployed without typing YAML; an invalid MT or MX message is
rejected at ingest with the failing field named.

## Phase 4: banking functions

The platform's own ledger rather than calls to someone else's: accounts (customer, nostro, vostro, loro,
suspense), double-entry postings and balances, statement generation (camt.053, MT940), fees and charges,
FX deals and positions, limits, liquidity management, end-of-day. Phase 2D calls an external ledger for
these; phase 4 is only needed if Orvanta itself is to be the core banking system.

Done 2026-10-06, first step: a ledger of its own (accounts opened with a second person, journal of
double-entry postings with idempotency, balances as the last closed day plus the entries since, refusal
for lack of funds, reversal, close of day), answering flows on the contract of the account connectors
(`ORVANTA_ACCOUNTS_URL` pointing at `/ledger`), Console pages for accounts, entries and postings, and
statements to customers with balances (camt.053 and MT940), on request and daily.

Done 2026-10-06: postings across currencies through position accounts (one entry, four sides).
Done 2026-10-07: fees booked with the posting from a schedule per scheme (`reference.FeeSchedule`), value dates on
entries with the balance by value date, the day's journal as a CSV file for the general ledger.
Settlement accounts per scheme and currency done 2026-10-07 (`settlementAccount()`).
Open: revaluation of positions; a funds check that is strict across several instances; interest; limits and
liquidity management.

## Phase 10: operations (from the VolBase comparison of 2026-10-06)

Picked from the comparison with the VolBase framework as concepts worth having, built in Orvanta's own
way: metrics endpoint, incident alerts, list export, data masking by role, signed and encrypted files on
folder and SFTP transports, graceful stop, language tables.

Done 2026-10-06: `GET /metrics` in the Prometheus text format (counts by status, what waits for people,
API request histogram, process figures), loopback-only or by token. Incidents: conditions watched
once a minute, opened and closed once each, told to a webhook, shown on the dashboard. Export of every
list as CSV with the same filters, logged in the security log. Masking of accounts, names and free text
for roles without `data.unmasked`. OpenPGP for files (see 2C). Graceful stop (health says STOPPING, work in
hand finishes, nothing new taken). Reason texts per language for customer messages (`reference.ReasonTexts`,
`text(code, language)`); the Console stays English.

From the processor cartridges (`framework/processor/src/main/volante`), compared 2026-10-06 and built the same day:
pause and resume of a channel, sending a file again, cancelling a whole instruction, possible duplicates held
for a person (`duplicates: HOLD`), bulks closed by total amount (`bulking.maxAmount`). Already there in
Orvanta's form: receive, debulk, duplicate registry, dispatch identification, bulking by count and time,
configurable retry, dead letters, override (release), resubmit, crypto handler (OpenPGP), acknowledgement
scheduling, instruction groups (batches). Not taken: synchronous processing of a payment within the request
(API customers poll the status report), endpoint availability checks before transmitting, and anything bound
to Volante's task and incident master tables.

From the VolPay functional components (`VolPay3x/core/functional-components`, 945 cartridges read by name,
compared 2026-10-06). Built the same day: compliance holds (suspended participants, travel rule, high value),
SEPA reachability, HIGH priority payments, cut-off extensions by an operator. Done 2026-10-06: a payment status enquiry (pacs.028) out for unanswered payments and in, answered with pacs.002.
Done 2026-10-06: SWIFT network ACK/NAK (purpose `deliveryNotification`).
Done 2026-10-06: a sanctions hit on an incoming payment freezes the funds (`sanctionsHit: FREEZE`).
Done 2026-10-06: investigations (camt.026/027/087 in, camt.028/029 out) as cases on a payment.
Done 2026-10-06: statement entries matched by hand; a daily reconciliation report per channel.
Done 2026-10-06: expiry of held payments (`heldExpiryDays` on the inbound channel).
Done 2026-10-06: request to pay (pain.013 in, pain.014 out, a payment on acceptance).
Done 2026-10-06: mandate messages (pain.009/010/011 in, pain.012 out). The list from the VolPay comparison is complete. Already there in Orvanta's form: account lookup and posting, the external checks, duplicate
check, bulking, warehousing and cut-offs, acknowledgements, returns, recalls, cancellations, statements in
and out, notifications, mandates, direct debits, cover (MT202 COV), charges, repair and resubmit, callbacks,
dead letters, pre-validation, reason texts, BIC enrichment, documents, UETR. Not for Orvanta: wallet transfers,
cheques, Fedwire and NACHA specifics (US rails are not built), the Verafin adapter, the AI scoring cartridge.

## Phase 5: security

Order matters: fix by design first, then scan, then have it attacked.

1. **OWASP Top 10 (2021) review and fixes**, one checklist entry per category with evidence.
   Known gaps going in:
   - A02 Cryptographic failures: no TLS; JWT secret is random per start unless configured.
   - A05 Security misconfiguration: no security headers (CSP, HSTS, X-Frame-Options, nosniff); simulator
     endpoints are unauthenticated and on by default; generated development passwords sit in `config/seed-users.yaml`.
   - A07 Authentication failures: no rate limit on login (only lock after five failures), no password
     policy beyond length, no token revocation or logout on the server, token kept in browser sessionStorage.
   - A10 SSRF and A03 Injection: a Connector model names any URL the server will call, and Studio test runs
     call connectors for real; needs an allow-list. Model text is compiled to Java, so the generator must be
     proven to never emit user text outside string literals.
   - A09 Logging failures: no security event log (logins, refusals, permission denials), no alerting.
   - A01 Access control: permissions are per function only; no data-level scoping (phase 3).
   - A08 Integrity: deployments are not signed; inbound files are not authenticated (no signature or API key per channel).
2. **Static analysis (SAST)**: SpotBugs with FindSecBugs and Semgrep in the Maven build; build fails on high findings.
3. **Software composition analysis (SCA)**: OWASP Dependency-Check and a CycloneDX SBOM in the build;
   build fails on a known critical or high vulnerability; licence report.
4. **Dynamic scan (DAST)**: OWASP ZAP baseline and authenticated active scan against a running instance, in CI.
5. **Penetration test readiness**: threat model, test accounts per role, scope document, and a fix-and-retest
   loop. The penetration test itself must be done by an independent tester; passing internal scans is not
   the same thing as passing a penetration test.
6. Secrets from environment or a vault only; secret scanning (gitleaks) in the build.

Done 2026-10-04: the OWASP review with evidence (`docs/security/owasp-top10.md`) and these fixes: HTTPS from
a keystore, security headers and CSP, no server version disclosure, generic error answers, login throttling,
timed lock-out, password policy, server-side sign-out and session revocation, simulator limited to the local
machine, connector URL policy and allow-list, deployment seal, security event log, proof that model text
cannot inject code. Static analysis (SpotBugs with FindSecBugs) is a failing build gate with a reviewed
exception list. The ZAP baseline scan passed (64 rules, 0 failures). The dependency scan found 28 known
vulnerabilities in Jackson, Jetty and the RabbitMQ client; all were removed by upgrading (Jackson 2.21.7,
Javalin 7 with Jetty 12, RabbitMQ client 5.37.0), and the rescan is clean. Both scans are in the CI workflow.

Done 2026-10-06: the Console's session token is in an HttpOnly, SameSite=Strict cookie.
Done 2026-10-06: a second step at sign-in with one-time codes from an authenticator app (TOTP), set up by the user,
required for everyone with `security.requireMfa`, reset by an administrator with approval.
Single sign-on with an OpenID Connect provider done 2026-10-06 (tested against a stand-in provider; the bank's own is still to be tried). Recovery codes for the second step done 2026-10-06. Approval for model-defined APIs that change data and a hardened production configuration (`config/orvanta-production.example.yaml`) done 2026-10-06. Open: SAML; alerting; TLS and
(signed artifacts through the `sign` Maven profile with GPG, and MongoDB and RabbitMQ with credentials from .env in the compose file, done 2026-10-07; the signing key itself is the bank's to issue); (the image scan in the build done 2026-10-07: Trivy on the built image, high and critical with a fix);
the independent penetration test itself. Secret scanning in the build done 2026-10-06 (`bin/secret-scan.py`, accepted findings in `.secret-scan-allow`). A threat model (STRIDE per component) written 2026-10-06: `docs/security/threat-model.md`, to be reviewed with the bank's security team.

Exit: OWASP checklist complete with evidence; SAST, SCA and DAST run in the build with zero open high or
critical findings (or a signed-off exception each); findings of an independent penetration test closed and retested.

## Phase 6: Console interface

Design system (tokens, components, light and dark themes); proper build (TypeScript, bundling, code
splitting, cached static assets); paged and virtualised tables with server-side filtering, sorting and search;
live updates over server-sent events instead of polling; payment timeline and flow visualisations;
dashboards with charts; keyboard navigation and WCAG 2.1 AA accessibility; responsive layout; a code editor
with YAML and expression completion in Studio.

Done 2026-10-05, first step: the transaction list is searched, filtered, sorted and paged by the store,
with the filters kept in the address; the payment page has a summary with the progress of the payment and
its details in groups; the dashboard has a seven-day chart, payments by status and a refresh that does not
redraw the page; tiles and rows are reachable from the keyboard; motion is switched off for those who ask
for that. Measured on 100,000 payments (`docs/testing/nonfunctional-baseline.md`): most list requests take
10 to 35 ms and the dashboard 133 ms; a search for a common word (254 ms) and the last page of the whole
list are at or over the 200 ms goal.

Second step, same day: instructions, responses, outbound files, approvals and the security log use the
same list (search, filters, sortable columns, pages, filters kept in the address), from one shared
component. `StoreContractTest` puts the list queries to MongoDB and to the in-memory store and expects
the same answers.

Third step, same day: live updates over server-sent events replace the 5-second polling (dashboard, lists,
review queue), and every screen passes an automated accessibility check (axe-core, WCAG 2.1 A and AA) and
fits the window at 1280 and 390 pixels in both themes. The check found and led to fixes for: fields without
a label, scroll areas that could not be reached from the keyboard, buttons inside buttons in the designers,
low-contrast text for skipped steps, and a link marked only by colour.

Open: an accessibility audit by a person with a screen reader (the automated check covers only part);
Done 2026-10-06: statements and failed events are searched, filtered, sorted and paged like the other lists.
The review queue is still plain sections; only the transaction list was
timed on a large store, and the other collections have no word index, so their search reads every
document; no build step, TypeScript or code editor; no accessibility audit; mobile widths were
only looked at for the three screens changed here.

Exit: first load under 2 seconds and list interactions under 200 ms on 100,000 transactions; accessibility
audit passes; every screen checked at desktop and mobile widths in both themes.

## Phase 7: build, packaging and delivery

1. Run directly from the codebase: one command to build and start (`mvn` wrapper, dev profile, workspace
   sync), documented for a clean machine.
2. Versioned release artifacts: server jar, Forge CLI distribution (zip with scripts), workspace bundle,
   checksums and SBOM, published to a Maven repository.
3. Docker image: multi-stage build, non-root user, minimal JDK base (a JDK is needed at run time because
   models are compiled there), health check, configuration by environment.
4. `docker compose` for the full stack: MongoDB, RabbitMQ, API, engine services.
5. Kubernetes manifests or a Helm chart: one deployment per unit, probes, resource limits, secrets,
   horizontal scaling.
6. CI pipeline: build, test, scans (phase 5), image build, image scan, release.

Done 2026-10-04: Maven wrapper and `bin/run` (item 1); release package as zip and tar.gz with SHA-256
files and a CycloneDX SBOM under the `release` profile (item 2); multi-stage Dockerfile, non-root, JDK
runtime (item 3); compose stack with MongoDB, RabbitMQ, console and a scalable engine (item 4); first
administrator from the environment and a health port for processes without the API.

Written but not run: the Kubernetes manifests (no cluster was available) and the GitHub Actions workflow
(the project is not in a repository yet). A Helm chart (`deploy/helm/orvanta`) done 2026-10-06. Publishing settings (distributionManagement, repository ids orvanta-releases/-snapshots, URLs and credentials from the installation) done 2026-10-07. Open: an
image registry; a smaller runtime image (jlink); signing of images (packages are signed with the `sign` profile; the image scan runs in the workflow, both since 2026-10-07).

Exit: a tagged commit produces every artifact without manual steps; `docker compose up` gives a working
platform on a machine with only Docker installed.

## Phase 8: non-functional testing

For each test type: a written scenario, data set, tool, pass criteria agreed before the run, and a report.

| Type | Question it answers |
|---|---|
| Sanity / smoke | Is a fresh deployment alive and able to process one file end to end? |
| Latency | Time per stage and end to end, at p50, p95, p99. |
| Throughput | Transactions per second sustained at a fixed configuration. |
| Load | Behaviour at the expected peak for the expected duration. |
| Stress | Where it breaks, and whether it fails cleanly and recovers. |
| Scalability | Does throughput rise in proportion as service instances are added? |
| Stability / soak | Does it hold for 8 to 24 hours without leaks or drift? |
| High availability | Kill one instance of each service, the broker, a database node: is service continuous? |
| Resiliency | Slow or failing external systems, network faults, full disk: is the damage contained? |
| Recovery | After a crash or restore, is every transaction accounted for, with none lost or duplicated? |

Deliverables: a load generator producing pain.001 and MT101 files of configurable size and error mix; a
reconciliation checker that proves no transaction was lost or duplicated; a test harness (Gatling or k6 for
HTTP, file drops for bulk); fault injection scripts; a results report per run with environment, numbers and verdict.

Targets must be set with the business before testing; without agreed numbers a run can only be described, not passed.

Done 2026-10-05, as a baseline without a verdict (`docs/testing/nonfunctional-baseline.md`): a load generator
for pain.001 with an error mix and a reconciliation checker (`perf/orvanta_perf.py`); scenarios with fault
injection on the compose stack (`perf/scenarios.py`); sanity, latency, throughput, scalability with one to
three engines, stress with 30,000 payments, a 10-minute soak, and four faults (one engine killed, broker
restart, database restart, every engine killed). Nothing was lost or duplicated in any run. Two defects
were found and fixed: events published before a queue existed, and the simulator not answering a file
delivered by a killed engine.

Open: agreed targets; slow and failing external systems under load, network faults, full disk; loss of the
console container; database replica set and broker cluster; restore from backup; a soak of 8 to 24 hours;
the breaking point and what limits scaling beyond two engines; an MT101 generator and the other rails under
load; a run on production-like hardware.

## Phase 9: automated functional test suite

A regression suite on three levels: TestCase models for every element (already started), API-level
scenario tests for every use case (each lifecycle path, each rejection reason, maker-checker, permissions),
and browser tests for the Console. Sample message library covering every supported message type, valid and
invalid. Use-case catalogue mapping each requirement to its tests. Coverage and results report in the build.

Done 2026-10-04: the use-case catalogue (37 use cases) checked against the results by `bin/test-report.py`;
API scenario tests for every lifecycle path, role and approval; browser tests of the Console; a described
sample message library; one command for everything (`bin/test-all`); the steps added to the CI workflow.

Browser tests of the statements page added 2026-10-06; users, outbound files and responses had them. Code coverage measurement done 2026-10-06 (`mvnw -Pcoverage verify`, JaCoCo). Negative samples as files with the expected refusals done 2026-10-07 (`workspace/tests/messages/negative/`). Open: the CI workflow has not run.

Exit: every use case in the catalogue has an automated test; the suite runs in CI on every change.

## Suggested order

2A and 2B, then 7 (items 1 to 4) and 9 early so everything after is built and tested the same way, then
2C, 2D, 2E, then 5, 8, 3, 6, 4.
Security (5) and non-functional testing (8) should also be repeated before any release, not done once.
