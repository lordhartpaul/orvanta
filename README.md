# Orvanta Platform

A low-code payment platform in three parts:

| Part | Module | What it is |
|---|---|---|
| **Orvanta Forge** | `orvanta-forge` | The low-code tool. Compiles YAML models (flows, rule sets, mappings, decision tables) into Java classes, in memory, and loads them. Also the model test runner and a command line. |
| **Orvanta Core** | `orvanta-core` | The small runtime the generated code calls: data records, expression operators and functions, the flow API, and the generic SWIFT MT and ISO 20022 codecs. |
| **Orvanta Pay** | `orvanta-pay` | The payment engine built with the tool: services, REST API, authentication, entitlements, maker-checker, and the **Orvanta Console** web UI (which contains **Orvanta Studio**, the model editor). |

Business logic lives in `workspace/` as models. The Java in `orvanta-pay` is the framework around it
(receive, store, publish, bulk, send) and contains no payment rules.

```
workspace/*.yaml  --Forge-->  Java source  --javac (in memory)-->  classes in a Registry
                                                                      |
 pain.001 / MT101 --> ingest --> debulk --> process (flow) --> bulk --> dispatch --> pacs.008 / MT103
                                   |            |                                        |
                              mapping +     rules, enrichment,                  pacs.002 --> acknowledge
                              file rules    connector call, routing
```

## Run it

Three ways, from least to most set-up.

**From the source tree, in one command.** Needs JDK 17 (a JDK, not a JRE: models are compiled at run
time) and MongoDB on `localhost:27017`. Maven is downloaded by the wrapper.

```
bin\run.cmd            (Linux and macOS: bin/run.sh)
```

It builds what changed and starts the server. `mvnw install` followed by `bin\start.cmd` does the same in two steps.

**With Docker, everything included.** Needs only Docker. MongoDB, RabbitMQ, the console/API process
and the engine process each run in their own container.

```
copy .env.example .env      and set the four values in it
docker compose up -d --build
```

Sign in as `admin` with the `ORVANTA_ADMIN_PASSWORD` you set. `docker compose up -d --scale engine=3`
adds engine capacity. The compose file is for trying the platform and for development: MongoDB has no
authentication (and is not reachable from outside the containers) and the simulated external systems are on.

**From a release package.** `mvnw -Prelease install` produces `orvanta-dist/target/orvanta-<version>.zip`
and `.tar.gz` (server jar, start and Forge scripts, configuration, the workspace models), a SHA-256
file for each, and the software bill of materials `target/bom.json` (CycloneDX). Unpack, copy
`config/seed-users.example.yaml` to `seed-users.yaml` or set `ORVANTA_SECURITY_BOOTSTRAPPASSWORD`, and run `bin/start`.

Other deployment files: `Dockerfile` (multi-stage, runs as a non-root user), the Helm chart `deploy/helm/orvanta` (console and engine deployments, probes, resource limits, a config map for settings, an existing secret for everything secret, optional ingress with TLS, optional shared data volume and schema config map; `helm lint` clean), `deploy/k8s/orvanta.yaml`
(Kubernetes: console and engine deployments with probes, to be used with your own MongoDB and RabbitMQ),
`.github/workflows/build.yml` (build, all tests, packages, image; a `v*` tag publishes a release).

Then open http://localhost:8480/ . When started from the source tree, the users created at first start
are in `config/seed-users.yaml` (generated development credentials: change or remove them outside
development). Without that file, as in a container, `ORVANTA_SECURITY_BOOTSTRAPPASSWORD` creates one
user `admin` at the first start.

A process that does not run the API can serve `GET /health` on the port set as `health.port`
(`ORVANTA_HEALTH_PORT`), for container and cluster health checks. `/api/health` and `/health` report the build version.

| User | Role | Use it to |
|---|---|---|
| `operator1` | OPERATOR | submit instructions, follow payments, resubmit repairs |
| `maker1` | DESIGNER, USER_ADMIN, OPERATOR | edit models and request user changes (maker) |
| `checker1` | APPROVER | approve or decline those requests (checker) |
| `admin` | ADMIN | everything, except approving their own requests |

`operator1` can also request resubmission and cancellation of a payment; `checker1` approves those too.

Try it: sign in as `operator1`, **Instructions > Submit an instruction**, choose
`workspace/tests/messages/pain001-salaries.xml`. Its five transactions end as: two ACCEPTED, one
REJECTED BY APPLICATION for a zero amount (AM01), one REJECTED BY APPLICATION for a sanctions hit (SANC),
one REJECTED BY EXTERNAL (AC04, from the clearing simulator). `mt101-suppliers.fin` leaves as two MT103.

Without MongoDB: `bin\start.cmd --store.type=memory` (nothing is kept after a restart).

### As separate services

One jar holds every service; `units` chooses which of them a process runs. With the RabbitMQ bus the
services can run as separate processes and be scaled independently (subscribers of one service share a queue):

```
bin\start.cmd --bus.type=rabbit --bus.uri=amqp://guest:guest@localhost:5672 --units=api,ingest
bin\start.cmd --bus.type=rabbit --bus.uri=amqp://guest:guest@localhost:5672 --units=debulk,process,bulk,dispatch,acknowledge
```

Units: `api`, `ingest`, `debulk`, `process`, `bulk`, `dispatch`, `acknowledge`, `cancel`, `return`, `report`,
`recover`, `callback`, `post`, `reconcile`. All processes share the
MongoDB database. Set `ORVANTA_SECURITY_JWTSECRET` to the same value everywhere when more than one process runs `api`.
Every setting in `config/orvanta.yaml` can be overridden by `--name=value` or `ORVANTA_NAME` (dots become underscores).

## The low-code models

One YAML file per model, each with a `kind` and a unique dotted `name`. Eleven kinds:

| Kind | Purpose | Compiled to Java |
|---|---|---|
| `Flow` | ordered steps: `rules`, `map`, `set`, `decide`, `call`, `find`, `save`, `remove`, `forEach`, `parallel` (nested steps run at once on copies of the scope; what each changed meets in it; the first to end decides), `wait` (stop for an answer a callback fills in, or a time: `for`, `retrySeconds`, `timeoutSeconds`; the flow runs again from the start and goes on when the answer is there), `task` (a human task: the payment is held for people in a named operator queue with instructions; the review queue lists it under that queue; released with a second person, the flow goes on past the task), `flow`, `end`; each may have `when` | yes |
| `RuleSet` | validations: `assert` expression, reason `code`, `message`, `severity` | yes |
| `Mapping` | builds or enriches a record: `set`, `let`, `forEach`, `append`, `field` (MT) | yes |
| `DecisionTable` | first matching row sets values (routing) | yes |
| `TestCase` | runs any element on given data and checks `expect` expressions | yes |
| `Channel` | inbound (format, message types, mapping, validation) or outbound (mapping, bulking, destination) | configuration |
| `Connector` | an external system a flow can `call`: REST (`type: http`) with method, URL placeholders, headers, authentication, retries, a circuit breaker (`circuitBreaker: {failures, openSeconds}`: a system that keeps failing is left alone for a while, then tried once), mutual TLS (`tls: {keyStore, keyStorePassword, trustStore?, trustStorePassword?}`: our client certificate, and the certificates the other side is trusted by); an operation of a SOAP web service (`type: soap` with `url`, `operation`, `namespace`, optional `action` and `version`); or `mock` | configuration |
| `Schedule` | a job at the times a cron expression names, in a time zone: closing the ledger day, a customer statement, the account reports, or a flow with given data; runs once across processes (a claim per schedule and minute); listed under Schedules in the Console with next and last run, and run by hand there | configuration |
| `Format` | records of fixed width or delimited text (a legacy host's payment file, ACH-like layouts): record kinds told apart by a type code, fields by position or column, amounts with implied decimals; a channel of `format: flat` names it as `formatSpec` and reads and writes such files with it | configuration |
| `DataSet` | stored data a flow can read (`find`: equality, `in`, ranges `gt`/`gte`/`lt`/`lte`, `ne`, `sort`, `limit`, `offset` for pages) and change (`save`, `remove`): its own collection, or a read-only view of engine data | configuration |
| `Api` | serves a flow as a REST endpoint under `/api/x`: method, path with `{placeholders}`, required permission | configuration |
| `MessageSpec` | field rules of a SWIFT MT message type: fields in order, mandatory or optional, repeating, sequences, and the format of each in SWIFT notation | configuration |
| `ReferenceTable` (shipped tables) | besides the BIC directory, holidays, cut-offs, reason texts and settlement accounts: `reference.IbanStructures`, the IBAN length and bank-code position per country for `ibanProblem()` and `bicFromIban()`, and `reference.IbanBankCodes`, the BIC by bank code inside the IBAN — IBAN Plus in spirit; both samples to be filled from the bank's directories) |
| `ReferenceTable` | keyed rows read in expressions with `lookup('table', key).field` (BIC directory, later calendars and cut-offs) | configuration |

Expressions: paths (`txn.creditor.name`, `list[0]`, XML attributes as `Amt.@Ccy`), `and or not`,
`== != < <= > >=`, `in [..]`, `+ - * /`, `cond ? a : b`, and functions. Every public static method of
`io.orvanta.core.expr.Fn` is a function (`exists`, `coalesce`, `len`, `upper`, `concat`, `matches`, `num`,
`sumOf`, `collect`, `today`, `fmtDate`, `isIban`, `isBic`, `isCurrency`, the `mt*` helpers, ...); adding a
method adds a function. A missing value is null and null sets nothing, so optional elements need no `if`.
Quote YAML values that contain `: ` or that look like dates.

Working with models:

```
bin\forge.cmd validate workspace          compile everything, list problems with model and place
bin\forge.cmd test workspace              run every TestCase
bin\forge.cmd emit workspace target\gen   write the generated Java to read or debug
```

### Data and APIs from models

A `DataSet` with `collection:` owns a collection (stored with the prefix `orvd_`). One with `source:`
(`engine.transactions`, `engine.messages`, `engine.batches`, `engine.outbound`) is a read-only view;
users, roles, approvals and deployments cannot be exposed. A `find` condition whose value is missing
matches nothing rather than everything. One with `table:` and `datasource:` lives in a relational
database: the data source is named in the configuration (`datasources.<name>.url`, `.username`,
`.password`, the password also as `ORVANTA_DATASOURCES_<NAME>_PASSWORD` in the environment) and its JDBC
driver is put on the classpath. Rows are flat (a column per field, a nested value as JSON text); `find`
conditions, sort and limit become SQL with parameters; table and column names must be plain SQL names.
Tested with an in-memory H2 database; the bank's MySQL is to be tried against its own database.

An `Api` model hands the flow a `request` (`path`, `query`, `body`, `user`). The flow answers with the
scope variable `response`. The flow outcome decides the HTTP status: completed 200, `NOT_FOUND` 404,
`REJECTED` 422 with the violations, anything else 500 (`statusCodes:` overrides this). Callers
authenticate like any console user and need the permission the model names. An Api model with
`soap: {operation: Name}` is also a SOAP operation at `POST /api/soap` (document/literal, wrapped): the Body
holds `<Name>` with child elements named like the path placeholders and the body fields, the answer is
`<NameResponse>` in the namespace `urn:orvanta:api`, a refusal is a Fault with the detail, and
`GET /api/soap?wsdl` describes every operation. The same permission applies as to the REST form.

Shipped examples: `GET /api/x/payments/{id}` (payment status), and `GET`, `PUT`, `DELETE`
`/api/x/limits/{account}` (a per-transaction limit per debtor account, enforced by the processing flow).

`GET /api/openapi.json` (any signed-in caller or API key) describes every Api model of the active deployment
in OpenAPI 3.0: path, method, path parameters, the permission (`x-orvanta-permission`), the target model, the
ways to authenticate (bearer token, `X-Api-Key`, the Console's cookie) and, when the model gives `request:` and
`response:` examples, those. The Studio links to it. Import it into Postman, Insomnia or a client generator.

An Api model with `approval: true` puts a second person in front of every call that changes data (POST, PUT,
DELETE): the call answers 202 with the request, which an approver decides like any other (type API_CALL,
permission `payments.approve`); the flow then runs as the caller, with the approver named. A `comment` query
parameter goes to the approver. Reads (GET) are never held. The OpenAPI description marks such operations
with `x-orvanta-approval`.

Test cases and Studio runs use an in-memory copy of the data sets, filled from `data:` rows, and expose
what the run left behind as `stored`. They never change stored data.

### Where messages come in and go out

Transport is part of the Channel model, so it is changed and approved like any other model and takes
effect without a restart.

| Inbound `transport` | Meaning |
|---|---|
| `type: folder`, `path` | a directory of the channel's own under the data directory; handled files move to its `archive` |
| `type: rabbitmq`, `uri`, `queue`, optional `receipt: {queue}` | a queue; a message is acknowledged to the broker only after it is stored; with `receipt`, a JSON receipt per message stored (id here, status, reason when refused) goes to that queue (type `orvanta.receipt`, correlation id = the sender's message id) |
| `type: rest`, `apiKey` | system-to-system push: `POST /in/<channel name>` with header `X-Api-Key` (a key of at least 16 characters); the same as a SOAP web service at `POST /in/soap/<channel name>` (the message is the first element of the Body; the answer is an envelope with a receipt per message stored, 202, or 422 when every message was refused; a fault for a bad request or key). A system's own key (Users and roles → API keys, with `payments.submit` and, when limited, this channel among its channels) is accepted as well, so a partner's key is rotated or disabled there without a redeployment |
| `type: http`, `url`, `intervalSeconds`, `auth`, `acknowledge` | fetched from a remote endpoint: `GET` until it answers 204; each message is stored and then acknowledged (`DELETE` or `POST` to a URL with `{id}`, the id taken from the response header `X-Message-Id`). The URL must pass the same host policy as a connector |
| `type: sftp`, `host`, `port`, `username`, `password` or `keyFile`, `hostKey`, `path`, `archive`, `intervalSeconds` | files fetched from a directory on an SFTP server; each is stored and then moved to `archive` (or removed). Names starting with a dot or ending in `.tmp` or `.part` are left alone. `hostKey` is the SHA256 fingerprint of the server's key and is required: a server showing another key is not talked to |
| `type: kafka`, `bootstrapServers`, `topic`, `groupId`, optional `properties`, optional `receipt: {topic}` | a Kafka topic (with `receipt`, a JSON receipt per message stored goes to that topic); one record is one message, and the position in the topic is committed only after the message is stored. `properties` takes any Kafka client setting (security protocol, SASL) |
| `type: ibmmq`, `host`, `port`, `channel` (the MQ server-connection channel), `queueManager`, `queue`, `username`, `password`, `waitSeconds`, `enabled`, optional `receipt: {queue}` (a JSON receipt per message stored, on that queue of the same queue manager) | an IBM MQ queue, read as a client of the queue manager; a message is committed only after it is stored, so a crash leaves it on the queue |
| `pgp` on a folder or sftp transport: `decryptKeyFile`, `passphrase`, `verifyKeyFile` | OpenPGP (GnuPG-compatible): the file is decrypted with our key and its signature checked against the partner's key; a file that is not encrypted, not signed, or signed by another key is stored as rejected (PGP_REFUSED) without its content |
| `checksum: sha256` on a folder or sftp transport or destination | for partners without OpenPGP: every file we write gets a `<file>.sha256` companion (sha256sum format) after it; a file that arrives waits for its companion (ten minutes at most, then CHECKSUM_MISSING) and is taken only when the checksum agrees (otherwise CHECKSUM_MISMATCH, content not kept) |

**IBM MQ.** The sample channels `channels.SbgMqInbound` (pain.001 from the queue SBGQ1DEVH2H) and
`channels.SbgMqStatusOutbound` (pain.002 to a reply queue, a placeholder until it is named) connect to the
queue manager CMQPSB6093XDQM at 10.50.1.104:1600 over the channel SBG609SN.CH. They are off until
`ORVANTA_SBG_MQ_ENABLED=true`; every detail is an `${env.…}` placeholder with the development value as
default, so it can be set per environment, and the models can be changed in Studio at any time with a
second person approving: a changed transport replaces its listener without a restart. `enabled: false` on
a transport stops listening; on a destination it holds the files as created until it is true again (the
same as pausing the channel). `POST /api/channels/{name}/mq-check` tries the settings: connects, reads
the queue's depth, disconnects, puts nothing. The IBM MQ client library (`com.ibm.mq.allclient`) is IBM's
and comes under IBM's own licence, not an open-source one. The live test `IbmMqLiveTest` runs against a
queue manager named by system properties; its round trip needs a queue of its own.

A message arriving on a channel's transport is handed to that channel only; a wrong message type is
rejected, not passed to another channel. Besides these, a signed-in user can post to `/api/inbound`
and the default folder `data/inbound` recognises the channel from the message. `maxBytes` on a channel
rejects larger messages. `enabled: false` keeps a transport in the model without listening.

| Outbound `destination` | Meaning |
|---|---|
| `type: folder`, `path`, `extension` | a file, written under a temporary name and then renamed |
| `type: http`, `url`, optional `headers` and `auth` | an HTTP post; the same `auth` settings as a Connector |
| `type: sftp`, the same settings as the transport, `path`, `extension` | a file in a directory on an SFTP server, written under a temporary name and renamed when complete |
| `type: kafka`, `bootstrapServers`, `topic`, optional `properties` | a record on a Kafka topic, key = id of the file, headers `messageId`, `messageType`, `contentType`; sent when all in-sync replicas have it |
| `type: ibmmq`, the same settings, `queue`, `ccsid`, `enabled` | a persistent message put on an IBM MQ queue and committed; our file id is the correlation id, the message type the application id data |
| `pgp` on a folder or sftp destination: `encryptKeyFile`, `signKeyFile`, `passphrase`, `armor` | the file goes out encrypted to the partner's key and signed with ours (AES-256, SHA-256, compressed), named `.gpg` or `.asc` with armour |
| `type: rabbitmq`, `uri`, `queue` (or `exchange` and `routingKey`) | a persistent message; SENT means the broker confirmed it |

Secrets belong in the environment, referenced as `${env.NAME}` in the model; `${env.NAME:-default}` gives a default.
A rejected message can be received again from the Console (**Request replay**), with approval, for
example after a mapping was corrected.

After pulling a new version of `workspace/`, an installation that already has a deployment keeps
running its stored models. Start once with `--workspace.syncOnStart=true` to deploy the folder (this
skips approval, so use it for upgrades and development, not for day-to-day changes).

In the Console, **Studio** shows every deployed model with its generated Java, validates an edit against
the whole deployment, runs an element (including an unsaved edit) on sample data with a step trace, and
submits the edit for approval. On approval the full model set is recompiled, stored as a new deployment
version and activated in every running process without a restart. A build that fails changes nothing.
Each transaction records the deployment that processed it.

**Deployments** lists every version. A version shows what it changed compared with the one before, line by
line, and what going back to it would change now. **Request rollback to this version** and, in Studio,
**Request removal** of a model are requests like any other change: a second person approves them.
A rollback deploys the earlier models as a new version, so the history stays complete; it does not touch
stored data such as data set rows or payments already processed. A model that another model still uses
cannot be removed (the user of it is named), and a rollback is refused when another change was deployed
after it was requested, because the approver saw a difference that is no longer true.

Flows, rule sets, decision tables and mappings have a **Design** tab, and **New flow**, **New rule set**,
**New decision table** and **New mapping** start there. A mapping is a tree of rules: set a field, name a
variable, go through a list, add a record, add an MT field; loops and added records hold their own rules. A rule set is a list of rules, each filled in through a form (what
must be true, when it applies, the reason code, the message, error or warning). A decision table is a
grid: one line per row with its condition, one column per field the rows set; the first row whose
condition holds wins. For a flow: step types are dragged from a palette onto
the canvas (or clicked, to add after the chosen step), reordered by dragging or with Move up and Move down,
nested inside a For each step, and filled in through a form that lists the deployed rule sets, mappings,
decision tables, connectors, data sets and flows to choose from. The design and the model text are the
same thing: a change in the design rewrites the text, and Validate, Test run and Submit for approval work
on it as before. Comments in the text are not kept once a model is changed in a designer. Channels, connectors,
data sets, APIs and reference tables have a form on the Design tab too: every setting the kind knows, with transports,
destinations, authentication, circuit breaker and bulking as nested sections; an empty setting is left out of the
model. A test case has a form too: the model under test, what is given, the connector mocks and data set rows as JSON, and the expectations one per line. A message specification has a form as well: its sequences, and in each a grid of fields with tag, name, format in SWIFT notation (or letter options), what the content is checked as, required and repeating; New message specification starts one. A mapping is built rule by rule; there is no side-by-side view of source and target
message structures to draw lines between.

## Where a payment goes: country requirements, bank codes, IBAN parts

- **What a country needs** (`reference.CountryRouting`): IBAN, or an account number with a national bank code of a
  given form (sort code, IFSC, ABA, BSB, branch code), or a BIC. `accountRoutingProblem()` checks a creditor
  against it (RC01), and the form of a bank code given.
- **Bank codes and BICs both ways** (`reference.ClearingCodes`, `reference.BicClearingCodes`): `bicFromClearingCode()`
  and `clearingCodeFromBic()`; enrichment fills the creditor's BIC from a national code and the national code from the
  BIC where the country uses one. The canonical payment carries the code as `creditor.agentCode` (from
  `ClrSysMmbId` or `Othr/Id` of the creditor agent in a pain.001).
- **IBAN parts:** `decomposeIban()` gives country, check digits, bank code, branch code and account by the country's
  structure; `composeIban(country, bankCode, branchCode, account)` builds one with the check digits computed. An
  IBAN and a bank code given together must name the same bank (RC01).
- **National account checks:** the Dutch 11-test (`nlAccountValid`), the Hungarian check digits (`huAccountValid`) and the
  UK modulus check (`ukAccountProblem`) against `reference.UkModulusWeights`, which the bank loads from the published
  Vocalink table (nothing of it is shipped; ranges with exceptions are reported as not checked).
  The sample rows of all these tables are written from general knowledge of the schemes: load the bank's directories.

## Reference tables from a spreadsheet

A reference table (bank directory, holidays, cut-offs, settlement accounts) has a **Rows (CSV)** tab in the
Studio with the rows as a table to edit in place (cells, rows added and removed; submitted as the whole table)
and as a file: its rows go out as a CSV file (`GET /api/studio/reference-tables/{name}/rows.csv`, logged as an
export) and come back the same way (`POST .../imports` with `csv`, `mode: merge|replace`, `comment`). Merge
adds the file's rows to the table and changes the rows with the same key; replace makes the file the whole
table. The header line names the columns and must have the key column; a column whose rows were all numbers
or all true/false stays that way. The upload is compiled with the rest of the deployment and becomes an
ordinary MODEL_CHANGE request, so a second person approves it and the deployment history shows it as any
other change; the request says how many rows were added, changed and removed. Comments in the model text are
not kept, the description is.

## SWIFT relationships, text that must fit, message id templates

- **Relationship management (RMA):** `rails.swift.Relationships` names, per bank, the message types we may
  exchange with it and until when (`messageTypes`, `validFrom`, `validTo`). `rmaAllows(table, bic, type)`
  reads it; the SWIFT checks reject a payment (NO_RMA) whose MT103 or MT202 COV would go to a bank without
  the relationship, and the settlement method goes cover only when the creditor's bank allows an MT103.
- **Text that must fit an MT field:** `fit(text, 35)` cuts and marks with a trailing `+`, as the SWIFT
  usage guidelines have it for text truncated on the way from MX to MT.
- **Message id templates:** an outbound channel with `messageIdTemplate` (for example `ORV{yyyyMMdd}{seq:6}`:
  date patterns, `{seq:N}`, `{channel}`) gives every file it builds a message id of that form, within the
  SWIFT character set and 35 characters; the file keeps its own id inside the platform.

## OAuth2 client credentials

A connector (http or soap), an HTTP destination or an HTTP pull transport may authenticate with OAuth2 client
credentials besides basic, bearer and header: `auth: {type: oauth2, tokenUrl, clientId, clientSecret, scope?,
audience?, credentials: basic|body}`. The token is fetched from the token URL when a call is made (never at
deployment), kept until thirty seconds before it expires, shared by everything that uses the same client and
scope, and fetched anew once when the system refuses it with 401 (as after a key rotation). The token URL is
checked against `security.connectorHosts` like any URL the server calls. Secrets belong in the environment
(`${env.NAME:-}`), not in the model text.

## Message formats

The codecs are generic, so every message type is readable and writable without per-type code:

- **JSON**: any JSON document; its type is the top-level `messageType` field, or `json` without one.
- **ISO 20022** (`IsoXml`): any MX message becomes a record tree; the type (`pain.001.001.09`) is read
  from the namespace.
- **SWIFT MT** (`SwiftMt`): any MT message is read at block and tag level
  (`{type, sender, receiver, b3, fields:[{tag, value}]}`); mappings interpret fields with the `mt*` functions.
  Field-level network validation rules per MT type are not built in yet.

### Checks when a message is received or sent

A message that breaks the rules of its type is rejected at ingest, stored with the reason, and the reason
names the field. A type with nothing installed is not checked.

- **ISO 20022** messages are checked against the XSD of their type (reason code `SCHEMA_INVALID`). The
  problem names the element by its path, for example `Document/CstmrCdtTrfInitn/PmtInf[2]/CdtTrfTxInf[1]/Amt/InstdAmt`.
  Schemas are imported in the Console under **Message checks**: choose the `.xsd` file, the message type
  is read from its target namespace, and a second person approves the import. Imported schemas are kept in
  the store, so every process uses them. A file `<messageType>.xsd` in `schemas/iso20022/` works too.
  **No schemas are shipped**: download the ones you use from iso20022.org or your scheme. A schema must be
  complete in one file; it may not load other files or anything from the network.
- **SWIFT MT** messages are checked against the `MessageSpec` model of their type (reason code
  `FORMAT_INVALID`): unknown or misplaced fields, missing mandatory fields, and the format of every field,
  for example `field :32B: (currency and amount), occurrence 2 in sequence B (occurrence 2): 'EUR8300.40'
  does not have the format 3!a15d`. Specifications for MT101, MT103, MT202 (with the cover sequence) and
  MT940 are in `workspace/specs/swift/`. **They were written from the published field formats, not copied
  from a SWIFT standards release, and do not include the network validated rules that relate one field to
  another**; compare them with the release your bank is on. A new type is one more model.

A format is one line or a list of lines. `16x` is up to 16 characters of the SWIFT x set, `3!a` exactly
three letters, `15d` an amount with a decimal comma, `4*35x` up to four lines of 35, `[...]` optional.
`is: date`, `is: bic` or `is: currencyAmount` adds a check of the value itself. A field with letter options
lists them: `{tag: "59", options: {"": [...], A: [...], F: [...]}}`.

**Messages the platform builds are checked the same way before they are sent**: payment files, cancellation
requests and status reports. An invalid payment file is not created; its transactions go to REPAIR with
reason `OUTBOUND_INVALID` and the field named, and are resubmitted once the mapping, the schema or the
field rules are corrected. An invalid cancellation request is refused with the reason, and an invalid
status report shows under Failed events. A channel can switch the check off with `checkOutbound: false`,
for example while its field rules are still being compared with the standards release.

**Message checks** also tries any pasted message against what is installed without storing it, which shows
whether a customer's file, or a message the engine itself produces, would pass.

Supporting a new message type means writing a Mapping and a Channel model. Modelled so far:

| Direction | Messages |
|---|---|
| From the customer | pain.001 and MT101 credit transfer instructions, pain.008 direct debit collections, camt.055 and MT192 cancellation requests |
| To the customer | pain.002 status reports, MT199 status messages for MT101, camt.054 debit and credit notifications, camt.052 account reports, camt.029 and MT196 answers to cancellation requests |
| To the clearing system | pacs.008 payments, pacs.003 direct debit collections, camt.056 cancellation requests |
| To correspondents | MT103, MT202 COV |
| From correspondents | MT103 payments for our customers, MT940 statements |
| Back to correspondents | MT103 returns of payments we received |
| To the clearing system, for payments received | pacs.002 answers to instant payments (ACCP, RJCT) |
| From the clearing system | pacs.002 status reports, camt.029 cancellation answers, pacs.004 returns, pacs.008 payments for our customers, camt.056 recalls of those, pacs.003 collections against our customers |
| Back to the clearing system | pacs.004 returns of payments we received, camt.029 refusals of recalls |
| From correspondents | camt.053 and MT940 statements |
| From other systems | JSON callbacks (late fraud result) |

## What the engine does today

Receive (REST upload or `data/inbound` folder) > recognise format and type > find channel > debulk >
file validation and duplicate check > create batches and transactions > validation > enrichment >
sanctions screening call > routing decision > bulking per channel and currency > build message >
deliver to folder or HTTP > apply acknowledgements (accepted / rejected by external system).
Rejection by the application carries the reason code. Every step is in the audit trail of the transaction.

After that first pass, the lifecycle continues:

- **Customer status report.** The customer receives a pain.002 when every transaction of the file is
  final, or after the channel's `maxWaitSeconds` with unfinished ones marked pending. A later change
  (accepted then returned, pending then accepted) is reported again; a rejected file is reported at once.
- **Cancellation.** Requested by the customer (camt.055) or by an operator. A payment not yet sent is
  cancelled immediately. A payment already sent cannot be stopped here, so a camt.056 goes to the
  receiving side and the transaction changes only when the camt.029 answer says cancelled; a refusal is recorded.
- **Return of funds.** A pacs.004 for an accepted payment sets it to RETURNED with the reason.
- **Repair and retry.** A failed external call parks the transaction in REPAIR and retries it
  automatically with a growing delay (5 attempts by default); after that an operator requests resubmission.
  A system that answers and refuses the request (HTTP 4xx, for example no rate quoted for a currency) is
  not an outage: the payment goes to repair as CONNECTOR_REFUSED with what the system said, and nothing
  is tried again until an operator resubmits it.
- **Maker-checker on payment actions.** Resubmission and cancellation by an operator are requests that
  a second person with `payments.approve` must approve.
- **Recovery.** Work left in an in-between status by a crash or a lost event is put back on its path
  after `recovery.stuckSeconds`. An interrupted debulk is undone and repeated, so a file is never half processed.
- **Failed events.** An internal event whose handler fails three times is kept in a dead-letter list
  that an operator can inspect and requeue.

### External systems during processing

The processing flow asks other systems in this order, each through a Connector model:

| Step | System | What a negative answer does |
|---|---|---|
| Account lookup | account system | unknown, closed or blocked debtor account: rejected (AC01, AC04, AC06) |
| Sanctions screening | screening system | hit: rejected; possible match: held for review |
| Fraud check | fraud system | alert: held for review; result not ready: waits for the late answer |
| Compliance check | compliance system | review: held; may require a supporting document |
| Supporting document | documents API (`/api/x/documents/{reference}`) | required and missing: held until uploaded and released |
| FX rate | rate sheet | foreign currency payments are debited in rand at the quoted rate; no quote: repair |
| Liquidity check | liquidity system | insufficient: waits and tries again every few seconds |
| Account posting | posting system | debit of the customer against the scheme's settlement account (nostro, vostro, loro) |

- **Held** payments appear in the Console's **Review queue**. Release or rejection is requested by one
  person and approved by another. A release records the hold as overridden and runs the flow again;
  every other check still applies.
- **Waiting** payments continue by themselves, when the late answer arrives on a callback channel
  or when the retry time comes. After `engine.waitTimeoutSeconds` (one hour) without an answer they go to repair.
- The flow is **run again from the start** each time. A call marked `once: true` keeps the answer it
  already has, so no system is asked the same question twice and nothing is posted twice.
- **Posting is the last step**, and carries an idempotency key. When a posted payment later fails
  (rejected by the receiving side, returned, cancelled), the engine runs the reversal flow; a failed
  reversal is retried until it succeeds.
- A callback channel may only fill the transaction fields it lists as `allowedFields`.

The built-in simulator plays all of these systems. Magic words in the test data choose the outcome
(creditor name `BLOCKED`, `REVIEWME`, `FRAUDCHECK`, `SLOWCHECK`, `NOLIQUIDITY`, `REJECTME`, `NOCANCEL`;
remittance `COMPLIANCE`; debtor account starting `000`, `999`, `666`). To call real systems, change the
`url` of each Connector model, or set `ORVANTA_SIM_URL` to move them all. The account system (account
lookup, posting, reversal) has its own setting, `ORVANTA_ACCOUNTS_URL` (when unset, `ORVANTA_SIM_URL` applies), so that it can be the platform's
own ledger (see The ledger) while the other systems stay where they are.

### Rails

A rail is a folder of models, not Java. `workspace/rails/sepa/` holds the first two:

| Rail | Channel | Sending | Hours |
|---|---|---|---|
| SEPA Credit Transfer (bulk) | `rails.sepa.SctOutbound` | bulks of up to 100, pacs.008 | TARGET2 business days, 07:00 to 15:00 Berlin time |
| SEPA Instant (real time) | `rails.sepa.SctInstOutbound` | one payment per message the moment it is routed, pacs.008 with INST | around the clock |

How a rail plugs in: a row of the routing table chooses the rail and names its checks
(`txn.route.railFlow`); the main flow runs that flow as a step. For SEPA the checks are the scheme
rules (euro, valid IBANs of SEPA countries with the right length, the SEPA character set, name and
remittance lengths, the 100,000 euro instant limit), the duplicate check, and the timing. Adding a
rail means adding a folder and routing rows; the main flow does not change.

**SEPA Core Direct Debit** (also in `workspace/rails/sepa/`). Here the customer is the creditor collecting money:

- The customer sends a pain.008 on `rails.sepa.SddInbound`. That channel names its own processing flow
  (`processingFlow:`), marks its transactions as collections (`paymentType: DD`) and lists what a
  transaction takes from its batch (`inherit:` the creditor, the collection date, the sequence type).
- **Mandates.** Every collection is checked against the mandate stored under its reference: it must
  exist, be active, be given to this creditor and name this debtor account, and the sequence must fit
  (a first or one-off collection only on an unused mandate, a recurrent one only after the first).
  A passed collection is counted on the mandate once; a final or one-off collection uses the mandate up.
  Mandates are kept through `PUT`, `GET` and `DELETE /api/x/mandates/{mandateId}` (delete cancels, the record stays).
- **Timing.** A collection must reach the clearing one business day before its collection date, by the
  cut-off. Earlier it is warehoused until that day; later it is rejected with DT01. A collection date
  on a closed day moves to the next business day.
- It leaves as pacs.003 on `rails.sepa.SddOutbound`. The debtor's bank can reject it (pacs.002) or
  return it later (pacs.004); a refund the debtor asks for arrives the same way, with reason MD06.
  The creditor is told in the status report each time.

Sample: `workspace/tests/messages/pain008-collections.xml`.

**SWIFT cross-border** (`workspace/rails/swift/`) chooses how the payment reaches the creditor's bank:

| Method | When | Messages |
|---|---|---|
| Direct | the creditor's bank is our correspondent for the currency | one MT103 to it |
| Cover | we have a direct relationship with the creditor's bank | MT103 to that bank, plus an MT202 COV to our correspondent to move the funds |
| Serial | no direct relationship | one MT103 to our correspondent, naming the creditor's bank in field 57A |

The choice is a decision table over two reference tables (correspondent per currency, banks we have a
relationship with). A currency without a correspondent is rejected.

**Statements and reconciliation.** camt.053 and MT940 statements of our accounts arrive on statement
channels. Each entry is matched to the payment whose id is its reference: a debit of the same amount and
currency is MATCHED, a different amount or direction is a MISMATCH, anything else is UNMATCHED (charges,
incoming funds). On a channel with `settles: true` a matched debit turns a payment still waiting as SENT
into ACCEPTED, which is how SWIFT payments, which get no status report, are confirmed. The statement's
own arithmetic is checked: opening balance plus entries must give the closing balance. The Console has a
Statements page.

Shared by every rail:

- **Calendars and cut-offs.** `reference.Holidays` and `reference.CutOffs`, read by `isBusinessDay()`,
  `nextBusinessDay()`, `todayIn(zone)`, `timeIn(zone)` and `instantOf(date, time, zone)`.
- **Warehousing.** A flow that ends with status `WAREHOUSE` and `txn.warehouse.until` keeps the payment
  (status `WAREHOUSED`, nothing posted) until that moment, then processing runs again. Used for
  future-dated payments and for payments arriving after the cut-off or on a closed day.
- **Duplicate check.** `payments.flows.DuplicateCheck`: same debtor account, end-to-end id, amount and
  currency within five days is rejected with AM05.
- **Method of payment.** Every routing row sets `txn.route.methodOfPayment`.
- **Clock for tests.** `clock:` in a TestCase fixes the time, so timing rules give the same result every day.

Sample: `workspace/tests/messages/pain001-sepa.xml` (two normal payments, one with a name outside the
character set, one instant, one instant above the limit).

Statuses: `CREATED > PROCESSING > ROUTED > BULKED > SENT > ACCEPTED`, with `HELD`, `WAITING` and
`WAREHOUSED` as pauses during processing, or `REJECTED_BY_APPLICATION`, `REJECTED_BY_EXTERNAL`, `CANCELLED`, `RETURNED`, `REPAIR`.

Identifiers are a six letter prefix and ten digits: `ORVINS` instruction, `ORVACK` acknowledgement,
`ORVCXL` cancellation request, `ORVRES` cancellation answer,
`ORVRTN` return, `ORVCBK` late answer, `ORVSTM` statement, `ORVMSG` unrecognised message, `ORVBAT` batch, `ORVTXN` transaction, `ORVOUT` outbound file,
`ORVAPR` approval request, `ORVDEP` deployment. Collections are prefixed `orv_`, bus topics `orv.`.

## Answers to cancellation requests

A customer who asks for a payment to be cancelled (camt.055, or MT192 for a whole MT101) is told what
became of the request on the channel that the request channel names (`answerChannel`): camt.029 for
ISO 20022, MT196 for MT. Per payment the answer says cancelled (CNCL), refused with the reason (RJCR), or
still with the receiving side (PDCR), and gives one status for the whole request (PACR when some were
cancelled and some refused). When a request was passed on and the receiving side decides later, the
customer gets a second answer with the final word. A cancellation that bank staff asked for in the
Console produces no answer to the customer.

## Mandate messages

Besides the mandates API and the Console, the mandate register (`data.Mandates`) is maintained by the
mandate messages of the creditor's side: a new mandate (pain.009), an amendment (pain.010), a
cancellation (pain.011), on `rails.sepa.MandateInbound` (purpose `mandate`). Each is applied and answered
with an acceptance report (pain.012) on the channel's `answerChannel`: accepted, or refused with the
reason when a new mandate's reference exists already, an amendment or cancellation names an unknown
mandate, or the debtor has blocked collections by that creditor (`data.DebitBlocks`). A cancelled
mandate's row stays, as CANCELLED. Collections are checked against the register whichever way a mandate
got there. Not built: asking the debtor to confirm an electronic mandate, and mandate messages that our
customers send out as creditors.

## Requests to pay

A creditor's bank may ask our customer to pay (pain.013, `channels.RequestToPayInbound`, purpose
`requestToPay`). Each request waits for the customer's answer under the review queue and
`GET /api/requests-to-pay`. The customer accepts or refuses, through the Console on the customer's behalf
or through `POST /api/requests-to-pay/{id}/accept` or `/refuse` (permission `payments.submit`); both are requests a second person
approves. Accepted, a payment is created from the request (the customer's account, the creditor, the
amount, the requested day) and processed like any instruction; refused, the reason is kept. Either way
the creditor's bank is told with a pain.014 (ACCP or RJCT with the reason) on the channel's
`answerChannel`. A request past its expiry date without an answer is refused by itself (AB07). Not built:
presenting the request to the customer in a channel of their own (a banking app would call the API), and
requests to pay that our customers send out.

## Investigations

Another bank may ask about a payment: for information it needs to apply it (camt.026, "unable to apply"),
because its customer says the funds did not arrive (camt.027, claim of non-receipt), or for a change
(camt.087, request to modify). A channel with purpose `investigation` (`rails.sepa.InvestigationInbound`)
takes all three; each opens a case on the payment it names, ours or one we received, found by UETR or by
the ids in the request. Open cases are listed in the review queue and on the payment, where a person
answers: a request for information with text, sent as camt.028 on the channel's `infoChannel`; a claim or
a modification request with a confirmation code (IPAY the payment was made, MODI modified, RJCR refused,
RJNR no such payment), sent as camt.029 on the `answerChannel`. Every answer is a request a second person
approves. A case about a payment nobody here knows is answered RJNR at once. The answers carry the case,
the payment's ids and the text or code; nothing in them is decided by the platform, and a modification is
not carried out by it: the person who answers MODI changes the payment through the usual actions.

## Incoming payments and refunds

A payment that arrives for one of our customers (SEPA Credit Transfer, pacs.008 on channel
`rails.sepa.SctInbound`) becomes a transaction with `paymentType: IN` and runs through its own flow,
`rails.sepa.flows.IncomingCreditProcessing`. It ends in one of two ways and is never just dropped:

- **CREDITED**: the customer's account was credited against the settlement account of the scheme.
- **RETURNED**: the payment was sent back to the sender as a pacs.004 with an ISO return reason. This
  happens by itself when a rule fails (AC01 for an account that is not an IBAN or does not exist, AC04
  closed, AC06 blocked, AM05 duplicate, and so on): for an incoming payment the flow outcome REJECTED means
  "send it back". The inbound channel names the channel for returns (`returnChannel`), and the return
  quotes the ids of the payment as it was received.

A sender that matches a sanctions list **holds** the payment for the review queue: such funds are neither
credited nor returned by a machine. Released, the payment is credited; turned down, it is sent back.

A credited payment can be **refunded** (button on the payment, `POST /api/transactions/{id}/refund`):
a request that a second person approves. The credit to the account is reversed first, and only then is the
payment sent back, with reason CUST unless another code is given. An incoming payment cannot be cancelled.

A **recall** is the sender's bank asking for a payment back (camt.056 on channel `rails.sepa.RecallInbound`,
purpose `recall`, handled by the unit `recall`). What happens depends on where the payment is:

| The payment is | What happens |
|---|---|
| not credited yet (held, waiting, in repair) | it is sent back at once, reason FOCR |
| credited | the request waits in the review queue; two people accept it (the credit is reversed and the payment sent back, reason FOCR) or refuse it |
| on its way back already, for a reason of our own | nothing more: that return answers the recall |
| already sent back | the sender's bank is told so (camt.029, ARDT) |
| unknown to us | the sender's bank is told so (camt.029, NOOR) |

A recall channel can say within how many business days of which calendar the scheme wants an answer
(`answerWithinBusinessDays`, `calendar`); the date is kept on the payment and shown in the review queue,
marked when it has passed. Nothing is decided by itself when the date passes.

A refusal is answered with a camt.029 on the channel the recall channel names (`answerChannel`), status RJCR
and the reason given (CUST unless another code is chosen). An accepted recall needs no answer of its own:
the return of the payment is the answer.

**Direct debit collections against our customers** (SEPA Core, pacs.003 on channel
`rails.sepa.SddDebtorInbound`) are the other incoming kind: `paymentType: DD_IN`, flow
`rails.sepa.flows.IncomingDebitProcessing`. A collection ends **DEBITED**, when the customer's account
was debited against the settlement account, or **RETURNED** with a reason:

| Reason | When |
|---|---|
| AC01, AC04, AC06 | the account is not a valid IBAN or does not exist, is closed, is blocked |
| MD02 | the mandate reference or the creditor identifier is missing |
| SL01 | the customer has blocked this creditor, or the amount is above the limit the customer set for it |
| AM04 | the account does not have the funds |
| AM05 | a duplicate |

What a customer wants for a creditor is kept in the data set `data.DebitBlocks` and maintained through
`PUT` and `DELETE /api/x/debit-blocks/{account}/{creditorId}`: an empty body blocks the creditor,
`{"maxAmount": 100}` lets it collect up to that amount at a time. A debited collection is refunded like a
credited payment (same button, same approval): the debit is reversed first, then the creditor's bank is
told, with reason MD06 unless another code is given.

**Time limits.** The channel says how long a debit can be refunded (`refund.withinDays: 56`, and
`unauthorisedWithinDays: 395` for a collection the customer never authorised, which needs reason MD01).
A refund asked for later is refused with the reason. Channels without the setting have no limit.

**Reversal by the creditor.** The creditor's bank can take a collection back (pacs.007 on
`rails.sepa.SddReversalInbound`, purpose `reversal`): the debit is reversed on the account, the collection
ends as RETURNED, the customer is told, and nothing is sent back because the money comes to us. A
reversal for a collection we never received or that is no longer debited is reported on the message.

The bank of the debtor does not check the mandate itself in the Core scheme, and this flow does not either:
it checks only what the customer told the bank. A reject before settlement (pacs.002 instead of a return)
and the B2B scheme are not built.

**Payments received over SWIFT** (MT103 on channel `rails.swift.Mt103Inbound`, flow
`rails.swift.flows.IncomingSwiftProcessing`) follow the same pattern. Two things differ:

- **Currency.** When the account is in another currency than the payment, the account is credited with
  the value at the rate the rate sheet quotes; the payment keeps both amounts and the rate, and the
  customer's notification shows them.
- **Returns.** A payment that cannot be credited, or is refunded, goes back as a new MT103 to the sending
  bank with the parties swapped and field 72 saying `/RETN/59/`, the reason code and `/MREF/` with the
  sender's reference. What goes back is the amount received, in the currency it was received in.

- **Charges.** Our charge comes from the tariff `rails.swift.reference.IncomingCharges`, by the currency of
  the account. With SHA and BEN the beneficiary bears it: it comes off the amount credited and is booked
  on the income account of the tariff as a booking of its own, and the notification shows the net amount
  and the charge. With OUR the customer is credited in full and the charge is kept on the payment as a
  claim on the sending bank. A charge that would not be less than the amount holds the payment; released,
  it is credited without a charge. When a payment goes back, its charge is reversed with it, because the
  whole amount received is returned.

- **Claims.** The claim for an OUR payment is sent by itself as a request for payment of charges (MT191) on
  the channel the inbound channel names (`chargeClaimChannel`), once the customer has been credited. The
  Console lists the claims under **Charge claims**: not sent yet, sent and not paid, could not be sent,
  paid. When the money arrives, two people mark the claim as paid, with the reference of what was received.

Not built for SWIFT: matching an incoming payment of charges to its claim (it is marked by hand), reminders
for claims that stay unpaid, using a prepaid charge (71G) or the charges of earlier banks (71F), which are
read and shown, a rate for the way back when
a converted payment is refunded (the credit is reversed as it was booked), cover payments received
(MT202 COV), recalls by MT192, and gpi tracker updates.

**Sanctions hits.** An incoming payment whose sender matches a sanctions list is held for a person. On a
channel with `sanctionsHit: FREEZE` the funds are booked at the same time from the scheme's settlement
account to the seized-funds account (`reference.SettlementAccounts`, row FROZEN), so that they are
accounted for while the decision is pending. A release reverses that posting and credits the customer; a
rejection reverses it and sends the payment back (reason SANC). With the default, RETURN, nothing is booked
while the payment is held. Reporting frozen funds to the authorities is the bank's process, outside the platform.

**Instant and domestic payments.** Several inbound channels may take the same message type. A channel says
which messages are its own with `match` (a path in the message and the value it must have); such a channel
is asked first, and everything else goes to the channel without a match:

| Channel | Its own pacs.008 | Answer to the sender |
|---|---|---|
| `rails.sepa.SctInstInbound` | local instrument INST | at once: pacs.002 ACCP when credited, RJCT with the reason when refused; a later refund goes back as pacs.004 (`refundChannel`) |
| `channels.ZaRtcInbound` | clearing system ZA-RTC | none when credited; pacs.004 in rand when sent back |
| `rails.sepa.SctInbound` | everything else | none when credited; pacs.004 when sent back |

An inbound channel that names a `confirmationChannel` has every booked payment confirmed to the sender
the moment it is booked; the payment records how long after its arrival that was. Because the sender of
an instant payment waits, its flow holds nothing for a person: a sanctions match refuses the payment.
The ten seconds a scheme allows are enforced by the flow (see The time an instant payment has).

Not built: charges on SEPA and domestic payments, and asking the customer for consent
(the decision is taken by bank staff).

**The customer is told** what was booked (unit `notify`): a credit for a payment received, a debit for a
collection, and the reversal of either when the payment is sent back after it had been booked. The inbound
channel names the channel for this (`notificationChannel`); with the sample models that is a camt.054 per
account, holding the entries that were waiting when it was written (every `notify.intervalSeconds`, 3 by
default). Each booking is told once: the payment keeps what is still to be told and what was told, and the
payment page links to the notifications. A payment that was sent back without ever being booked is not
mentioned to the customer.

Not built here: choosing per customer whether and how to be notified (every notification goes to the one
channel), intraday or end-of-day account reports (camt.052, camt.053) for customers, and notifications
for outgoing payments, which are reported with pain.002.

## The time an instant payment has

**Received.** The flow for incoming instant payments checks, right before it would credit the account, how
long ago the payment arrived (`secondsSince(txn.createdAt) > 9`). A payment that took longer is refused
with a pacs.002 and reason AB05; it is never credited late. The limit is a line in the flow model.

**Sent.** An outbound channel can say how soon its rail answers (`answerWithinSeconds: 20` on
`rails.sepa.SctInstOutbound`). A payment that is sent and still unanswered after that time is marked
(`answerOverdue`, event ANSWER_OVERDUE) and listed in the review queue under "Sent, and not answered in
time", and by `GET /api/transactions?status=SENT&overdue=true`. Nothing is decided for it: whether the
money moved is known only to the clearing, and the answer decides when it comes. When the outbound
channel names an `enquiryChannel`, the rail is asked once, by itself, with a status enquiry (pacs.028,
`rails.sepa.StatusEnquiryOutbound`); the payment records the enquiry (STATUS_ENQUIRED). The answer, when
it comes, is the usual pacs.002.

**SWIFT network acknowledgements.** The SWIFT interface answers every message we send with an ACK
(taken by the network) or a NAK (refused, with an error code), each followed by a copy of our message. A
channel with purpose `deliveryNotification` (`rails.swift.SwiftDeliveryInbound`, message types ACK and NAK)
receives them: the payment the copy's field 20 names records the ACK as DELIVERED, or goes to repair with
the NAK's code (reason NAK) for an operator to correct and send again. An acknowledgement for a message
not ours is kept, matched to nothing. MT010/MT011 delivery notifications from the receiving bank are not read.

**Enquiries from other banks** (pacs.028 on `rails.sepa.StatusEnquiryInbound`, purpose `statusEnquiry`)
are answered with a pacs.002 on the channel's `answerChannel`: ACCP when the payment was booked, RJCT with
its reason when it went back, PDNG while it is still on its way, and RJCT with NOOR when nothing with these
references arrived. The payment records that it was asked about (STATUS_ASKED).

## Notification preferences

A customer can choose not to be told of bookings on an account, or to be told on another channel than
the usual one: a row in the data set `data.NotificationPreferences` (account, notifications ALL or NONE,
channel), kept in the Console under Customer instructions with a second person approving, or through
`PUT` and `DELETE /api/x/notification-preferences/{account}`. A payment whose notification is left out
says so (notify state SKIPPED, event NOT_NOTIFIED). Preferences per kind of booking or per amount are
not built.

## Account reports for customers

An account report (camt.052) lists everything this engine booked on one account in a period, oldest
first, with the number and sum of the credits and of the debits: payments the customer sent (a debit,
and a credit marked as a reversal when the payment came back), payments received, direct debits
collected, and their reversals.

- `GET /api/account-reports/entries?account=...&from=2026-10-06&to=2026-10-06` shows the bookings.
- `POST /api/account-reports` with `{"account": "...", "from": "...", "to": "..."}` writes the report and
  sends it on `channels.CustomerAccountReportOutbound` (or the `channel` named in the request). A report
  covers one to 31 days. It is listed with the outbound files, kind Account report.
- A customer who wants one every day has `accountReport: DAILY` in the notification preferences; the
  report of a day is written once, after the day is over (days are UTC days).

A user whose access is limited to certain accounts gets reports of those accounts only.

The report has no balances, and so it is a camt.052 and not a statement (camt.053): the balance of an
account is kept by the account system. With the platform's own ledger there is a statement with
balances instead (see The ledger). A payment the customer
sent is dated by the moment it was received here, because the posting does not record its own time.
There is no Console form for asking for a report; the API is the way.

## The ledger

The platform has a ledger of its own: accounts, a journal of double-entry postings, balances, the close
of a day, and statements. It is there in every installation and used when the account connectors point
at it: set `ORVANTA_ACCOUNTS_URL` to `http://<host>:<port>/ledger`. Without that, flows go on asking the
simulator (or the bank's account system), and the ledger stays empty.

**Accounts** have a number, a name, a type (CUSTOMER, NOSTRO, VOSTRO, LORO, SUSPENSE, FEE, POSITION), one currency,
a status (ACTIVE, BLOCKED, CLOSED) and, for customers, an overdraft limit. They are opened and changed in
the Console under Accounts, or with `POST /api/ledger/accounts`; a second person approves. The type and
the currency of an account do not change, and an account with a balance cannot be closed.

**Postings.** One posting is one journal entry: an amount, the account debited, the account credited.
Because it is one document, a posting is there completely or not at all. A flow posts through
`connectors.AccountPosting` with an idempotency key; asked again with the same key, the ledger answers
with the posting it made. It refuses, without booking anything, when an account is unknown, the debit
account is blocked or closed, the currencies differ, or a customer account would go below its overdraft
limit. A payment whose debit is refused is rejected with AM04. A reversal is an entry the other way
round; a posting can be reversed once.

**Two currencies.** A posting between accounts of different currencies names both amounts (`amount` and
`currency` debited, `creditAmount` and `creditCurrency` credited) and goes through the bank's position
accounts: the debit currency is credited to its position account and the credit currency is debited from
its own. It is one journal entry with four sides, so each currency balances by itself and the entry is
still there completely or not at all. The outgoing flow sends both amounts for a converted payment. What no payment books (cash paid in, a correction) is requested
under Accounts or with `POST /api/ledger/postings` and approved by a second person.

**Fees and value dates.** A posting may carry `feeAmount`, `feeAccount` and `feeText`: the fee is debited from
the same account and credited to the fee account (an active FEE account of the same currency, else
NO_FEE_ACCOUNT) as an entry of its own marked as the fee of the posting; the funds check covers amount and
fee; a reversal returns the fee too. The outgoing flow takes the fee from `reference.FeeSchedule` by the
route's scheme (`fee(amount, fixed, percent, min, max)`): a scheme without a row charges nothing. A posting
may carry a `valueDate` (the flow sends a requested execution date that lies ahead); `GET
/api/ledger/accounts/{id}?valueDate=` gives the balance by value date beside the booked one, and the Console
shows a value date that differs from the booking day. **The day's journal** is a CSV file:
`GET /api/ledger/days/{date}/entries.csv` (also under Accounts), one line per entry with every side, the value
date and the fee it belongs to, for the general ledger.

**Balances** are not stored with the account. A balance is the last closed day plus the entries since,
credits minus debits: for a customer a positive balance is money the customer has; for the mirror of an
account held at another bank a negative balance is money held there. `GET /api/ledger/accounts` lists
accounts with balances, `GET /api/ledger/accounts/{id}` gives the entries, each with the balance after it.

**End of day.** When a day (UTC) is over, the ledger writes for every account the balance it opened and
closed the day with; later balances start from there. It happens by itself in the unit `notify`, and
`POST /api/ledger/close-day` does it on request. A closed day stays as it is.

**Statements.** `POST /api/account-statements` with `{"account": "...", "date": "..."}` writes the camt.053
of a day that is over: opening balance, entries, closing balance, sent on
`channels.CustomerStatementOutbound`. There is one statement per account and day. A customer with
`accountReport: STATEMENT` in the notification preferences gets it every day.
A customer who works with MT gets the same day as an MT940: name `channels.CustomerMtStatementOutbound` as
`channel` in the request, or choose `STATEMENT_MT` in the preferences. The receiver BIC is a property of
that channel, so customers with different BICs need a channel each.

**Who may call `/ledger`.** Only this machine, unless `ledger.apiKey` is set; then every caller sends the
key in the header `X-Ledger-Key` (add it to the three connector models as a header).

Limits, said plainly:
- Two processes that debit the same customer account in the same instant can both pass the funds check;
  within one process postings to an account are made one at a time. Run the unit `api` that serves
  `/ledger` once, or accept that the check is not strict across instances.
- A converted payment needs a position account (type POSITION) for each of its two currencies; without
  them it is refused (NO_POSITION_ACCOUNT). The settlement account of a scheme has one currency, so a scheme
  that settles in several currencies has a row per currency in `reference.SettlementAccounts` (keyed
  `SWIFT|EUR`), which `settlementAccount(scheme, currency)` takes before the scheme's own row; the sample
  table has them for SWIFT. Positions are kept, not revalued: there is no profit and loss on exchange.
- Incoming payments credit the customer through the same connector, so their settlement accounts must
  be opened in the ledger as well.
- No interest; no liquidity or position reports. Fees are booked only as the posting names them (from
  `reference.FeeSchedule` in the outgoing flow); value dates are kept and reported, but the close of a day and
  statements go by the booking day.

## Customer instructions in the Console

What customers told the bank is kept in data sets that the flows read: account limits, direct debit
mandates and direct debit blocks. The Console shows them under **Customer instructions**, one tab per data
set, searched and paged like every other list. An entry is added, changed or removed with a **request that
a second person approves**:

- the values are tried against the rules of the data set's API at once, on a copy of the rows, so a request
  the rules refuse never reaches an approver;
- on approval the change is made through that same API, and the row records who asked and who approved.

Which data sets appear, their columns and their form come from the models, not from code: a `DataSet`
with a `console` section is offered.

```yaml
kind: DataSet
name: data.AccountLimits
collection: account_limits
key: account
console:
  title: Account limits
  columns: [account, perTransaction, updatedBy, updatedAt]
  search: [account]
  write:
    api: api.AccountLimitPut          # the Api model that stores a row
    fields:
      - {name: account, in: path, from: account, label: Account}
      - {name: perTransaction, in: body, type: number, label: Most per payment}
  remove:
    api: api.AccountLimitDelete
    path: {account: account}          # placeholder of the API path: field of the row
```

The APIs under `/api/x` stay as they are for other systems: a call there applies at once, with the
permission the Api model names. A user limited to certain channels or accounts does not see this page,
because rows are not tied to a user's payments.

## Tests

| Level | What | Where | Run |
|---|---|---|---|
| Model tests | each rule set, mapping, decision table and flow on given data, with a fixed clock where time matters | `TestCase` models in `workspace/` | `bin\forge.cmd test workspace` |
| Java tests | the compiler, the codecs, and the whole engine end to end on the in-memory store | `orvanta-*/src/test` | `mvnw install` |
| API scenarios | every use case over HTTP as a user of each role, with approvals on the real endpoints | `ApiScenarioTest` | part of `mvnw install` |
| Security | headers, HTTPS, sign-in protection, sessions, policies, tamper detection | `SecurityHttpTest` | part of `mvnw install` |
| Accessibility | every screen against WCAG 2.1 A and AA with axe-core, at desktop and phone width, light and dark | `console-tests/tests/z-accessibility.spec.js` | part of the browser tests |
| Negative samples | `workspace/tests/messages/negative/`: one refused file per way of being wrong, with the expected reason in `expected.yml`; `NegativeSamplesTest` checks them all | `orvanta-pay/src/test` | `mvnw test` |
| Browser tests | the Console in Chromium: sign-in, roles, file submission, Studio change with approval, a flow built in the designer, a rollback, review queue, security log | `console-tests/` | `cd console-tests && npm test` |

`bin\test-all.cmd` (or `bin/test-all.sh`) runs all of them and writes `target/test-report.md`.

`docs/testing/use-cases.yaml` is the catalogue of what the platform must do, each use case with the
tests that prove it. `bin/test-report.py` joins it with the results and fails when a use case has a
failing or missing test, or when a test belongs to no use case, so a new feature cannot be added
without saying which use case it serves. The sample messages and the words the simulator reacts to
are described in `workspace/tests/messages/README.md`.

The Console's transaction list is searched, filtered, ordered and paged by the store, so a page costs the
same however many payments there are: `GET /api/transactions` takes `q` (words that must all occur, each as
a whole word, in the id, end-to-end id, names, accounts or remittance), `status`, `scheme`, `currency`,
`from` and `to` (days), `minAmount`, `maxAmount`, `sort` with `dir`, `offset` and `limit`, and answers with
the page and the `total`. The address in the browser keeps the filters, so a filtered list can be
bookmarked. Instructions, responses, outbound files, approvals and the security log work the same way
(`q`, `from`, `to`, `sort`, `dir`, `offset`, `limit`; a filter value with commas means any of them). `perf/console_perf.py` times these requests on a store of made-up payments.

**Live updates.** The Console keeps one connection open (`GET /api/stream`, server-sent events) and is told
when payments, approvals or models change; the dashboard, the lists and the review queue then fetch their
data again, at most once every second and a half and not while the tab is in the background. A notice names
only the kind of thing that changed and carries no data, so what a user sees is still decided by the request
that follows. Each process collects its changes and announces them at most once a second over the bus, so
this works with the engine in other processes. The token travels in a header, never in the address. "Live"
or "Reconnecting…" under the user name shows the state; without the connection, pages ask every 15 seconds.

**Accessibility.** `console-tests/tests/z-accessibility.spec.js` opens every screen, including the four
designers and the detail pages, at 1280 and 390 pixels wide in the light and the dark theme, and fails on
any WCAG 2.1 A or AA problem that axe-core finds or on a page wider than its window. It is part of the
browser tests. An automated check finds only part of what a person would: it does not judge wording, reading
order or how a page sounds in a screen reader.

Non-functional tests run against the compose stack: `perf/orvanta_perf.py` (load with reconciliation,
latency) and `perf/scenarios.py` (scale, stress, faults, recovery, soak). The method and the measured
baseline are in `docs/testing/nonfunctional-baseline.md`. With the rabbit bus, `bus.consumers`
(`ORVANTA_BUS_CONSUMERS` in compose) sets how many messages of one service a process handles in parallel.

The browser tests start their own server (port 8590, in-memory store). The queue test runs only when a
broker is named: `-Dorvanta.it.rabbit=amqp://guest:guest@localhost:5672`. In the same way,
`-Dorvanta.it.mongo=mongodb://localhost:27017` puts the questions the lists ask (search, ranges, order,
pages, counts) to a real MongoDB as well as to the in-memory store and expects the same answers
(`StoreContractTest`); it works in a database of its own and drops it afterwards.

## Before a payment leaves

- **Compliance holds** (`payments.rules.Compliance`, run first in the outgoing flow): a suspended bank on
  either side (`reference.SuspendedParticipants`, code SUSP), a cross-border payment of 1,000 or more that
  does not name payer and payee with their accounts (the travel rule, TRVL), an amount at or above the
  bank's threshold for the currency (`reference.HighValue`, HVAL). Each puts the payment in the review
  queue; released, the override is recorded and the rule is not raised again. None decides by itself.
- **SEPA reachability** (`reference.SepaReachability`): a bank listed as not reachable in the scheme is not
  sent to (RR04). The scheme's register is the source; load it here.
- **Priority**: a payment the customer marks HIGH (`InstrPrty`) closes its bulk at once instead of
  waiting for it to fill or age.
- **Cut-off extensions**: when the clearing moves a day's cut-off, an operator enters the new time under
  Customer instructions > Cut-off extensions (`data.CutOffExtensions`, approved by a second person); the SEPA
  rail reads it for that day. The data set is in the Console only because the model has a `console`
  section; nothing else had to be built for it.

## Held payments that nobody decides

An inbound channel may say how long a payment waits for a person (`heldExpiryDays`). A payment held
longer is turned down the way that person would turn it down: an outgoing payment is rejected, an
incoming one goes back to its sender, each with the code it was held for and a reason that says it
expired (event HOLD_EXPIRED). Nothing is ever released by time alone. A channel without the setting keeps
its payments held until someone decides; the incident alerts say when that takes too long.

## Reconciliation by hand, and the day's figures

- **Matching by hand:** a statement entry the engine could not match (the correspondent used its own
  reference, or the amount differs by a charge) is matched by a person to the payment it is, under
  Statements; a second person approves. The payment is marked reconciled with the statement and who did
  it; on a settling channel a payment still SENT becomes ACCEPTED, as an automatic match does. An entry
  is matched once, and a payment reconciled once.
- **Daily report** (`#/daily`, `GET /api/reports/daily?date=`): per inbound channel the messages, rejected
  files, payments by status and total amount of the day; per outbound channel the files, sent and failed,
  payments by status and amount. Counted from the payments themselves, so the figures can be put next
  to what the clearing or the partner reports for the same day. Export CSV as everywhere.

## A payment from a form

**New payment** in the Console is a form for one payment: debtor, creditor, amount, currency, requested date,
remittance, charge bearer, purpose, priority, reference. Submitting it creates a PAYMENT_ACTION request
(`POST /api/payments/initiations`); the payment enters the engine only when a second person approves it,
within that person's approval limit, as a one-payment JSON instruction on the channel
`channels.ConsoleInitiation` (mapping `payments.inbound.ConsoleInitiationToCanonical`, the same validation,
duplicate check and processing as a pain.001). The channel is `consoleOnly`: it has no transport, a file
posted to `/api/inbound` cannot name it, and a JSON message that merely looks like a form payment finds no
channel. **Templates** (`/api/payments/templates`, permission `payments.submit`) keep the parties and the
standing fields for the next time; saving and removing one is logged. A user limited to certain debtor
accounts initiates payments only from those accounts.

## Repair, notes, early release

- **Repair with changes:** a payment parked for an operator is corrected on its page (creditor name, account
  and bank, debtor name, remittance, requested date, amount, currency, purpose, charge bearer — never its
  ids or what the engine decided) and resubmitted in one request a second person approves. The payment
  keeps every repair with before and after (event REPAIRED). Resubmitting unchanged still works.
- **Notes:** anyone who may see a payment can add a note to it; notes are kept with the payment with who
  and when, and shown on its page.
- **Release now:** a warehoused payment goes before its time at an operator's request, approved by a
  second person (event RELEASED, who released it).

## Operator controls

- **Stop all sending:** one request pauses every outbound channel (`POST /api/channels/stop-all`, approved by a
  second person, the kill switch for a clearing incident); `resume-all` sends what waited. Single channels are
  paused and resumed as before.
- **Purpose codes a channel takes:** an outbound channel with `allowedPurposeCodes` parks a payment whose code is
  not among them for repair (PURPOSE_NOT_ALLOWED); with `requirePurposeCode: true` a payment without one too.
- **Amounts a route takes:** an outbound channel with `minAmount` / `maxAmount` (a clearing's limit per payment, as
  `channels.ZaRtcOutbound` has 5,000,000) parks a payment outside the range for repair (AMOUNT_OUT_OF_RANGE): a
  person picks another route or splits it.
- **Daily limit per account:** the limits API and the Account limits page take `perDay` besides `perTransaction`;
  the processing flow adds up the account's payments of the day (rejected and cancelled ones not counted) and
  rejects the one that would exceed it (AM02). The sum is over the amounts as instructed, whatever their currency.
- **Corridor restrictions:** `reference.CorridorRestrictions` names country pairs and currencies (with `*`) that are
  BLOCKED (rejected, RR04, before anything is looked up) or under REVIEW (held for a person, code CORR); the most
  specific row wins. Expressions: `corridorRestriction(debtorCountry, creditorCountry, currency)`, and the list
  helpers `where(list, field, values)` and `without(list, field, values)`.
- **Bulking by fields:** `bulking.groupBy: [instructionId]` (or `debtor.account`, …) builds one file per value,
  besides per currency.
- **Time to route:** the daily report shows, per outbound channel, how long the day's payments took from arrival
  to their route, on average and for 95 of 100.
- **Suggested matches:** on a statement, "Suggest" lists the payments an unmatched entry could be (same amount and
  currency, around the value date, not matched yet), each matched with one click and a second person's approval.

Four things an operations team needs on a bad day, each a request that a second person approves:

- **Pause a channel** (`POST /api/channels/{name}/pause`, `/resume`): nothing leaves a paused outbound channel;
  its files wait as created and go when it is resumed. `GET /api/channels/paused` lists what is paused.
- **Send a file again** (`POST /api/outbound/{id}/resend`): a file that was sent is delivered once more, for a
  partner that lost it; the file records RESENT.
- **Cancel a whole file** (`POST /api/messages/{id}/cancel`): every payment of an instruction that can still be
  cancelled is cancelled, or asked to be cancelled when it has left; the answer says how many could not be.
- **Possible duplicates for a person**: an inbound channel with `duplicates: HOLD` holds a possible duplicate
  (code DUPL) in the review queue instead of rejecting it (the default, AM05); rejected there it is a
  duplicate, released it goes on as a payment in its own right. The duplicate check runs where a
  processing flow includes `payments.flows.DuplicateCheck`: the SEPA rail and every incoming flow do; the
  SWIFT and domestic outgoing rails do not yet.

A bulk also closes when its total would go over `bulking.maxAmount`, for a clearing that takes only so much
in one file.

## Languages

What the bank says to customers can be said in their language. `reference.ReasonTexts` holds the meaning
of every reason code per language (rows `CODE-lang`; samples in English, German and French), maintained
in Studio like any reference table. A mapping asks with `text(code, language)` and gets the text in that
language, or else in English, or else the code itself. The customer status report (pain.002) uses it when
the channel names a `language` property; without one it says what the engine said. The Console itself is
in English only: its texts are not in a table, and translating it is not planned until someone needs it.

## Export

Every list in the Console has **Export CSV**: the rows of the list with the same filters and order, up to
10,000 (`limit` up to 50,000 on the API), as a file a spreadsheet opens; every scalar field is a column,
nested fields have dotted names, message texts and events are left out. On the API it is `format=csv` on
any list endpoint. A limited user exports what that user may see. Every export is written to the security
log with who, what and how many rows, because it takes data out of the system. Cells that a spreadsheet
would run as a formula are neutralised.

## Monitoring

`GET /metrics` answers in the Prometheus text format: payments, messages and outbound files by status;
what waits for people (pending approvals, held and parked payments, open dead letters, overdue answers,
open recalls, failed notifications, locked users); API requests and their time per route; memory, threads
and CPU of the process. Numbers only, never payment data. It is answered to this machine only, unless
`metrics.token` is set; then the scraper sends `Authorization: Bearer <token>`. `metrics.enabled: false`
turns it off.

**Incidents.** The process that serves the Console also watches, once a minute (`alerts.intervalSeconds`),
for conditions that need a person: the store not answering, open dead letters, overdue answers, failed
notifications, undelivered outbound files, requests waiting longer than `alerts.approvalMinutes` (60) for a
second person, payments held longer than `alerts.heldMinutes` (120) or parked longer than
`alerts.repairMinutes` (30). When a condition first holds an incident is opened and, with `alerts.webhook`
set, posted as JSON (kind, count, time; never payment data; `alerts.token` goes as a bearer token); when it
stops holding, the incident is closed and posted once more. Open incidents are shown at the top of the
dashboard and under `GET /api/incidents`; `POST /api/incidents/check` runs the check now. No e-mail or
SMS: the webhook is for the system that pages people.

## Stopping

A process stops in an order that loses nothing: the health check answers `503 STOPPING` (so a load
balancer or Kubernetes sends nothing more), listeners and the bus stop taking messages, the work in hand is
given up to `shutdown.graceSeconds` (30) to finish, and only then are the API, the broker connections and
the store closed. What was not finished stays in the queues for the next start. The Kubernetes manifest
gives the pod 45 seconds to do this.

## Security

`config/orvanta-production.example.yaml` is a hardened configuration to start a production installation
from: TLS on the server port or behind a proxy, the store and the bus with credentials and TLS from the
environment, a sealed deployment history (`security.integrityKey`), the second step required for everyone,
shorter sessions, no seed file (one bootstrap administrator, then users with approval), the hosts models may
call listed, metrics behind a token, incidents posted to a webhook, the simulator off. Every line that differs
from the development configuration says why; nothing secret is in the file.


`docs/security/threat-model.md` is the threat model: assets, attackers, and STRIDE per component with the
controls that stand in the way, and what the model does not cover.
`docs/security/owasp-top10.md` goes through the OWASP Top 10 category by category: the control, where it
is in the code, the test that guards it, and what is still open. In short:

- **Access:** every API route names a permission; roles are read on each request; changes to models,
  users, roles and payments need a second person.
- **Sessions:** the Console's session token is in a cookie that scripts cannot read (`HttpOnly`,
  `SameSite=Strict`, `Secure` when the server runs with TLS); a request that changes something must also
  carry a header only the Console adds, so another site cannot act for a signed-in user. One browser has
  one session: signing in as someone else in a second tab replaces the first. Systems sign in with
  `POST /api/auth/login` and send the token they get as `Authorization: Bearer`.
- **Second step at sign-in:** under **My account** a user sets up one-time codes from an authenticator app
  (TOTP); signing in then takes password and code. `security.requireMfa: true` makes it mandatory: a user
  without it can sign in only to set it up. A user who lost the device is reset by an administrator
  (user change with `resetMfa`, approved by a second person). There are no recovery codes.
- **API keys for systems:** a system that calls the API without a person signing in sends `X-Api-Key:
  orv_<name>_<secret>`. Keys are issued, changed, rotated and disabled under Users and roles (`/api/keys`),
  each with a second person; the secret is shown once to the person asking and works once the request is
  approved; only its hash is kept. A key has roles like a user but may never approve or administer, cannot
  issue keys, and what it did is logged under `key:<name>`; a refused key is in the security log.
- **Recovery codes:** turning the second step on gives eight one-time recovery codes (shown once; only their
  hashes are kept). A user without the device signs in with the password and a recovery code instead of the
  app's code; each works once, the sign-in is logged as such, and the user is told how many are left. New
  codes are issued under My account against a current code from the app, voiding the old ones.
- **Single sign-on (OpenID Connect):** with `sso.enabled` and the provider's issuer, client id, client secret
  and redirect URI set (`ORVANTA_SSO_*`), the sign-in page offers a button that sends the browser to the
  provider (authorization code flow, endpoints from the provider's discovery document). The code comes back to
  `/api/auth/sso/callback` with a state this process signed (ten minutes), is exchanged for an ID token, and
  the token is checked against the provider's published keys (RS256 only): issuer, audience, expiry and nonce.
  The user named by the `preferred_username` claim (`sso.usernameClaim`) must exist in Orvanta and be active;
  roles, approval limits and scope stay Orvanta's, and nobody is created on the fly. Password sign-in stays
  available. Keycloak-compatible; tested against a stand-in provider, not yet against the bank's own.
- **Approval limits:** a role of your own may carry limits (`approvalLimits`: an amount per currency, `*` for
  any). A user whose roles all have limits may approve a payment action only up to the highest limit of
  those roles for the payment's currency; above it the request stays pending for someone else, and the
  refusal is in the security log. The built-in APPROVER has no limit. Approvals that are not about one
  payment (models, users, cancelling a whole file) are not limited by amount.
- **Masking:** a role without the permission `data.unmasked` sees account numbers with only the last four
  characters, names as initials and free text (remittance, reasons, notes) as `[hidden]`, in every API
  answer, so the Console, the exports and model-defined APIs show the same. Ids, amounts, statuses and dates
  are never masked. The built-in OPERATOR, APPROVER and ADMIN roles read in full; DESIGNER and USER_ADMIN do
  not; a role of your own chooses. Search still finds by the full value on the server.
- **Roles and limits:** besides the five built-in roles, roles of your own (a name and a set of
  permissions) are requested under **Users and roles** and approved by a second person. A user can also be
  **limited** to the payments received on certain inbound channels, of certain debtor accounts, in certain
  currencies, up to an amount, or any combination (a payment must fit every limit given).
  Permissions say what a user may do; the limit says on which payments. A limited user sees and acts on
  those payments only, a payment outside the limit answers "not found", and outbound files, statements,
  failed events and model-defined APIs are closed to them because they hold payments of everybody.
  A user limited by account, currency or amount sees no files and cannot submit any; a user limited to
  channels submits only to those channels. The Console form for initiating a payment keeps to the same limits.
- **Sign-in:** passwords hashed with PBKDF2; a password policy; failed sign-ins limited per address and
  per user; a lock that ends by itself; sign-out and credential changes end sessions on the server.
- **Transport and browser:** HTTPS from a keystore (`server.tls.keystore`) or a TLS proxy in front
  (`server.behindTlsProxy`); Content-Security-Policy and the other protective headers on every response.
- **Models:** model text reaches generated code only as escaped literals; connectors may not call
  metadata or link-local addresses and can be limited to an allow-list (`security.connectorHosts`);
  deployments can be sealed against changes made directly in the database (`security.integrityKey`).
- **Records:** a security log (Console: Security log) next to the audit trail of every payment.

Checks in the build:

```
mvnw -Psecurity verify -DskipTests           static analysis (SpotBugs with FindSecBugs); fails on a finding
mvnw -Prelease,sign install -Dgpg.keyname=…  the release build with every artifact and the SBOM signed with GPG (.asc next to each); the passphrase from the GPG agent or a CI secret
mvnw -Prelease deploy -Dorvanta.releases.url=…  publishes the artifacts to the bank's Maven repository (ids orvanta-releases / orvanta-snapshots, credentials in ~/.m2/settings.xml)
mvnw -Pcoverage verify                       the tests with JaCoCo measuring the lines of our code they run: <module>/target/site/jacoco/index.html
                                             (per module, by that module's own tests: the core is exercised mostly through the forge and pay tests)
mvnw -Prelease install                       software bill of materials (CycloneDX)
python bin/secret-scan.py                    private keys, cloud and API tokens, passwords written out in configuration or models; fails on any not accepted in .secret-scan-allow
python bin/osv-scan.py                       known vulnerabilities in the libraries of the SBOM (OSV database); fails on any
mvnw -Pdependency-scan verify -DskipTests    the same against the NVD (OWASP Dependency-Check; set NVD_API_KEY, the first run is slow)
```

Findings that were reviewed and accepted are listed with their reasons in
`config/security/spotbugs-accepted.xml`. The dynamic scan (OWASP ZAP baseline) runs against a started
server; the command is in the CI workflow.

Before going beyond development: turn the simulator off, set a JWT secret, enable TLS, set the
connector allow-list and the integrity key, give MongoDB and RabbitMQ credentials and TLS, and remove
`config/seed-users.yaml`.

## Not built yet

Connectors for SQL databases (data sets are on the document store only) and for message-queue request-reply;
WS-Security and SOAP headers on the SOAP connector (it sends a Body only, authenticated over HTTP or with a
client certificate); a SOAP client from a WSDL; a NACHA layout itself (the `Format` kind can describe it; the
specification is needed). Rails other than SEPA Credit Transfer, SEPA Instant and the ZA RTC sample; SEPA
rulebook points beyond those listed above (structured remittance); balance-of-payments code derivation (the
bank's code list); B2B direct debit; cover payments in ISO 20022 (pacs.009 COV) and an intermediary bank
(field 56A); intraday statements (camt.052, MT942). Real connections to external systems (only simulators
exist, and one development IBM MQ queue manager); merchant and partner liquidity as separate checks. A
side-by-side source and target view for mappings; a form for message specifications. Limits on what a user
sees by anything other than inbound channel and debtor account (branch, customer group, amount). SAML single
sign-on (OpenID Connect exists); the bank's own identity provider is still to be tried. MT network validated
rules across fields; a shipped ISO 20022 schema set (schemas are imported, not shipped). Publishing to a Maven
repository; the CI workflow has not run yet; the independent penetration test and accessibility audit.

## Layout

```
orvanta-core/    runtime library (Rec, Ops, Fn, flow API, IsoXml, SwiftMt)
orvanta-forge/   ModelSource, ExprCompiler, Generator, MemoryCompiler, Forge, TestRunner, ForgeCli
orvanta-pay/     kernel (Config, DocStore, Bus, Deployments), engine services, security, api, sim, console
workspace/       the low-code models and their tests (seed of the first deployment)
config/          orvanta.yaml, seed-users.yaml
orvanta-dist/    the release package (zip and tar.gz) and its scripts
bin/             run (build and start), start, forge, test-all, test-report.py, osv-scan.py, secret-scan.py
console-tests/   browser tests of the Console (Playwright)
perf/            load generator with reconciliation, non-functional scenarios
docs/            security review, use-case catalogue, non-functional baseline
deploy/k8s/      Kubernetes manifests
deploy/helm/     Helm chart (orvanta)
Dockerfile, docker-compose.yml, .env.example
data/            created at run time: inbound folder, outbound files
```
