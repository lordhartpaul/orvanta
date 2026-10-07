# Threat model

What Orvanta protects, who could attack it and how, and what stands in the way. Written 2026-10-06 from the
code as it is; to be reviewed with the bank's security team before production and whenever a component is
added. The method is STRIDE per component: spoofing, tampering, repudiation, information disclosure, denial
of service, elevation of privilege. Evidence for each control is in [owasp-top10.md](owasp-top10.md).

## What is worth protecting

| Asset | Why |
|---|---|
| Payment instructions and transactions (names, accounts, amounts, remittance) | money moves on them; personal and financial data |
| The low-code models (flows, rules, routing, limits) | they decide what is paid, where, and what is refused |
| Users, roles, approval limits, API keys, sessions | who may do what |
| Keys and secrets: JWT secret, OpenPGP keys, SFTP host keys, connector credentials, OAuth2 client secrets, IBM MQ and RabbitMQ credentials | with them an attacker is us |
| The audit trail: events, approvals, security log | proves what happened and who did it |
| Availability of processing | a stalled hub misses cut-offs |

## Who

- **An outsider on the network**: reaches the Console and API (if exposed), the system-to-system endpoints
  (`/in/<channel>`), nothing else.
- **A partner or corporate customer**: sends files and messages on a channel; may be careless or compromised.
- **An insider with a Console account**: operator, designer, approver, administrator; may make mistakes or act
  with intent; may have been phished.
- **A compromised external system**: a sanctions, fraud, account or FX service answering wrongly or slowly.
- **Someone with the database or the host**: outside this model's controls; see "What this model does not cover".

## Components and threats

### Console and API (`ApiServer`, Javalin on Jetty)

| Threat | Example | Controls |
|---|---|---|
| Spoofing | guessed or stolen password; replayed token; forged cookie request | password policy, lockout and throttling; signed short-lived tokens tied to a token version; HttpOnly SameSite=Strict cookie plus the `X-Orvanta-Console` header on writes; TOTP second step with recovery codes; single sign-on with the ID token checked against the provider's keys, state and nonce |
| Tampering | changing a payment, a model or a user directly | every change of a model, user, role, key, limit or payment action is a request a second person approves (maker ≠ checker); approval limits by amount; repairs limited to listed fields and recorded with before/after |
| Repudiation | "I did not approve that" | approvals carry maker, checker, comments and time; events on every payment; security log of sign-ins, denials, exports, key use; a system's key acts as `key:<name>` |
| Information disclosure | a viewer reading accounts and names; exports; metrics | permissions per route; masking for roles without `data.unmasked`; exports logged; scope by channel and debtor account; `/metrics` numbers only, loopback or token; errors carry no stack traces |
| Denial of service | login floods; huge uploads; slow lists | throttling per address and user; `maxBytes` per channel; paged lists with server-side limits; the inbound folder leaves `.tmp` files alone |
| Elevation of privilege | a key or a junior approver approving; a role granting itself `*` | a system's key may never approve or administer and cannot issue keys; roles with approve/admin permissions cannot be given to keys; role changes need approval; the built-in roles are fixed |

### Inbound channels (`IngestService`, transports: folder, REST push, HTTP pull, RabbitMQ, SFTP, Kafka, IBM MQ)

| Threat | Example | Controls |
|---|---|---|
| Spoofing | a file pretending to come from a partner | OpenPGP signature check (PGP_REFUSED), API key per channel of at least 16 characters, SFTP host key pinning, channel scope for users who submit |
| Tampering | a file altered in transit or at rest in a shared folder | OpenPGP encryption and signature; SHA-256 companion files for partners without PGP (CHECKSUM_MISMATCH refuses, content not kept) |
| Repudiation | "we never sent that file" | every message is stored with file name, channel, transport actor and time; rejected files are stored too |
| Information disclosure | payment data in an open queue or folder | TLS on the queue and SFTP connections where the server offers it; PGP for files; the data directory under the server's own permissions |
| Denial of service | a flood of files; a 2 GB file; a poison message | `maxBytes`; dead letters with retry caps; IBM MQ and Kafka listeners back out and reconnect; duplicates held or rejected |
| Elevation of privilege | a callback message writing fields it should not | `allowedFields` on callback channels; `consoleOnly` channels take nothing from outside; message schema or specification checks before anything is read |

### Processing engine and models (`Forge`, flows, connectors)

| Threat | Example | Controls |
|---|---|---|
| Tampering | a designer routing payments to an account of their own | models change only through approved requests; deployments are sealed and integrity-checked at start (a tampered deployment is not activated); rollback is itself approved |
| Information disclosure | a model calling an attacker's URL with payment data (SSRF) | connector and token URLs checked against `security.connectorHosts`; cloud metadata addresses and non-http schemes refused |
| Denial of service | a slow external system stalling every payment | connector timeouts and bounded retries; circuit breaker; parallel calls; payments parked in REPAIR rather than blocking |
| Elevation of privilege | a model-defined API doing more than its permission says | each Api model names the permission it needs; the OpenAPI description shows it; the server-side scope applies |

### Data store and queues (MongoDB, RabbitMQ, Kafka, IBM MQ)

| Threat | Controls |
|---|---|
| Spoofing / tampering by someone with network access to the store | credentials and TLS belong in the deployment (see "Not covered"); the application never trusts stored deployments without their seal |
| Information disclosure | secrets are not in documents except hashed (passwords, API keys, recovery codes); the TOTP secret is stored as it must be to compute codes — the database must be protected accordingly |

### Secrets and configuration

| Threat | Controls |
|---|---|
| Secrets in model text or in git | models take `${env.NAME:-}` placeholders; the shipped files carry no real secrets; the OSV scan and SpotBugs run in the build |
| Weak defaults | `security.jwtSecret` unset means a random secret per process (sessions do not survive a restart); the simulator answers only this machine; the first administrator password must pass the policy |

## What this model does not cover

- The host, the operating system, the network and the database server: an attacker there is outside the
  application's controls. Run the database and the queues with authentication and TLS, and the application
  under its own account with the data directory not readable by others.
- The identity provider for single sign-on, and the partners' own key handling.
- A malicious checker and maker in concert: two people with intent can approve anything the two of them
  may do; approval limits and the audit trail bound the damage and show it afterwards.
- Side channels, physical access, and the browser of a user with malware.

## Open

- An independent penetration test against a deployed installation (never claimed by this project).
- TLS and credentials for MongoDB and RabbitMQ in the shipped configuration files; image scan; signed
  artifacts; secret scanning in CI.
- Review of this document with the bank's security team.
