# Security review against the OWASP Top 10 (2021)

Reviewed 2026-10-04, build 0.1.0. For each category: what is in place, where the evidence is, and
what is still open. "Test" names a method in the automated suite that fails if the control stops working.

This is a self-assessment. It does not replace an independent penetration test; the last section
says what such a test needs from us.

## A01 Broken access control

| Control | Evidence |
|---|---|
| Every `/api` route needs a signed-in user except sign-in and health; every route names the permission it needs | `ApiServer`: the `before("/api/*")` filter and `need(ctx, permission)` on each route. Test `SecurityHttpTest.headersSessionsThrottlingAndTheSecurityLog` (401 without a token, 403 without the permission) |
| Roles and status are read on every request, so a role change or a disabled user takes effect at once | `AuthService.authenticate` |
| Maker-checker: model, user and payment changes take effect only after a different user with the approval permission approves | `ApprovalService.decide`. Test `EndToEndTest.aModelChangeNeedsASecondPersonAndThenChangesBehaviour` |
| Models reach only their own data sets or read-only views of engine data; users, roles, approvals and deployments cannot be exposed | `Forge.ENGINE_SOURCES`, `StoreDataAccess`. Test `ForgeTest.dataSetAndApiModelsAreChecked` |
| A callback can fill only the transaction fields its channel lists | `CallbackService` (`allowedFields`) |
| The simulated external systems, which have no authentication, answer only the local machine | `ApiServer` (`/sim/*` filter) |

| A user can be limited to the payments received on certain channels, of certain debtor accounts, or both; a payment outside the limit does not exist for that user (404), lists and counts are filtered in the store, and what holds everybody's payments (outbound files, statements, failed events, model APIs) is refused | `AuthService.Scope`, `ApiServer.visibleTxn`, `visibleMessage`, `unscoped`, `paymentInScope`. Test `ApiScenarioTest.aRoleOfOnesOwnAndALimitToChannelsOrAccountsDecideWhatAUserReaches` |
| Roles of one's own are created, changed and removed only with a second person; built-in roles cannot be changed; full access (`*`) cannot be given to a role of one's own; a role in use cannot be removed | `ApiServer` (`/api/roles`, `ROLE_CHANGE`, `ROLE_REMOVE`). Same test |

Open: the limit is by inbound channel and debtor account only (not by branch, customer group, currency or
amount), and a user limited to accounts cannot see or submit files at all. The limit covers the REST API
and the Console; it is not applied inside flows, which is why model-defined APIs are closed to limited
users rather than filtered. Model-defined APIs that change data (limits, mandates, blocks, documents) apply at
once when called directly under `/api/x`, without a second approver; in the Console the same changes are
requests that a second person approves (`DATA_CHANGE`).

## A02 Cryptographic failures

| Control | Evidence |
|---|---|
| Passwords stored as PBKDF2-HMAC-SHA256, 210,000 iterations, random 16 byte salt, constant-time comparison | `Crypto.hashPassword`, `Crypto.verifyPassword` |
| Session tokens signed with HMAC-SHA256, expiry checked, signature compared in constant time | `Crypto.signToken`, `Crypto.verifyToken` |
| HTTPS on the server port from a keystore; TLS 1.0, 1.1 and SSLv3 excluded; HSTS sent when TLS is on | `ApiServer.start`. Test `SecurityHttpTest.theServerSpeaksHttpsFromAKeystoreAndOnlyHttps` |
| Channel API keys compared in constant time; an unknown channel, a channel without a key and a wrong key get the same answer | `ApiServer` (`/in/{channel}`) |
| Secrets come from the environment, not from model or configuration files | `${env.NAME}` in models, `ORVANTA_*` settings, `.env` excluded from git and the image |
| Secrets stay out of the repository: `bin/secret-scan.py` fails the build on a private key, a cloud or API token, a connection string with credentials, or a password, secret or key written out in configuration or a model (also as the default of an `${env.NAME:-...}` placeholder); what git ignores (the local seed file, `.env`, key stores) is not scanned; accepted findings are listed with a reason in `.secret-scan-allow` | `bin/secret-scan.py`, CI step "Secret scan" |

Open: TLS is off by default; an installation must configure a keystore or put a TLS proxy in front.
MongoDB and RabbitMQ connections use TLS only if their URIs say so. Data at rest is not encrypted by
the platform (database-level encryption is the operator's choice). Account numbers are stored in clear.
The token signing secret is random per start unless `ORVANTA_SECURITY_JWTSECRET` is set.

## A03 Injection

| Control | Evidence |
|---|---|
| Model text becomes Java only as escaped string literals; the one thing that becomes an identifier, the model name, must match a strict pattern | `ExprCompiler.javaString`, `ModelSource.parse`. Test `ForgeTest.modelTextCannotBreakOutOfTheGeneratedCode` |
| Expressions are compiled by our own parser into calls on a fixed function library; a model cannot name a Java class or method | `ExprCompiler.call` (only public static methods of `Fn`) |
| Database access uses the driver's filter objects with equality on values; no query text is built from input. A search condition whose value is missing matches nothing | `MongoDocStore.toFilter`, generator `find` step |
| XML is parsed with DTDs and external entities disabled | `IsoXml.parse`. Test `FormatsTest.externalEntitiesAreRefused` |
| The console builds the page from text nodes, never from HTML strings; Content-Security-Policy allows scripts only from the same origin, no inline script | `app.js` (`h`), `ApiServer` headers. Test `SecurityHttpTest` (CSP, no inline script) |
| Log lines cannot be forged with line breaks in user-supplied text | `SecurityLog.clean`, request path in `ApiServer` |

Open: none known. Model authors (permission `studio.edit`) are trusted to write business logic; the
generated code runs with the server's rights, which is why every model change needs approval.

## A04 Insecure design

| Control | Evidence |
|---|---|
| Four-eyes on every change that alters behaviour or money: models, users, release of held payments, cancellation, resubmission, replay | `ApprovalService`; approval types MODEL_CHANGE, USER_CHANGE, PAYMENT_ACTION |
| A build either succeeds completely or changes nothing; every transaction records the deployment that processed it | `Forge.build`, `ProcessingService` |
| Status changes are compare-and-set, so two processes cannot both act on one item; posting carries an idempotency key | `DocStore.updateIf`, `connectors.AccountPosting` |
| Nothing received is dropped: an unreadable or unroutable message is stored as rejected with the reason | `IngestService.reject` |
| Failed sign-ins are limited per address and per user name; a locked user unlocks after a set time | `LoginThrottle`, `AuthService.login`. Test `SecurityHttpTest` (429, lock) |

Open: no threat model document yet (see the last section). No limit on the number of requests
other than sign-in. No limit on how much a single user may approve.

## A05 Security misconfiguration

| Control | Evidence |
|---|---|
| Security headers on every response: CSP, X-Content-Type-Options, X-Frame-Options, Referrer-Policy, Permissions-Policy, cross-origin policies | `ApiServer` `before` filter. Test `SecurityHttpTest` |
| The server does not announce its software or version; unexpected errors return a reference number, not a stack trace | `setSendServerVersion(false)`, the `Exception` handler in `ApiServer` |
| The container image runs as a non-root user and has the simulator off | `Dockerfile` |
| The feature that deploys models without approval (`workspace.syncOnStart`) is off by default | `config/orvanta.yaml` |
| Generated development credentials are excluded from git and from the image | `.gitignore`, `.dockerignore` |
| A hardened configuration to start production from: TLS, store and bus with credentials from the environment, sealed deployments, the second step required, shorter sessions, no seed file, connector hosts listed, metrics behind a token, the simulator off; nothing secret in the file | `config/orvanta-production.example.yaml` |

Open: `config/orvanta.yaml` as shipped is a development configuration: the simulator is on and
MongoDB is used without credentials; `config/orvanta-production.example.yaml` is the one to start
production from. The compose file runs MongoDB and RabbitMQ with passwords from `.env`, not published outside the containers.

## A06 Vulnerable and outdated components

| Control | Evidence |
|---|---|
| The container image scanned for known vulnerabilities in its operating system packages and shipped libraries; high and critical findings with a fix fail the build | CI step "Image scan (Trivy)" |
| Release artifacts and the SBOM signed with GPG when the `sign` profile is on (`mvnw -Prelease,sign install -Dgpg.keyname=…`); the key and passphrase never in the repository | parent `pom.xml`, profile `sign` |
| A software bill of materials for every build | `mvnw -Prelease install` writes `target/bom.json` (CycloneDX) |
| A dependency scan that fails on any known vulnerability in a shipped library | `bin/osv-scan.py` over the SBOM (OSV database), in the CI workflow |
| A second, more thorough dependency scan that fails on a CVSS score of 7 or higher | profile `dependency-scan` (OWASP Dependency-Check; needs an NVD API key to be practical) |

Results of the scan: see "Scan results" below.

Open: no automatic update proposals (Dependabot or Renovate) because the project is not in a
repository yet. The container base image is not scanned.

## A07 Identification and authentication failures

| Control | Evidence |
|---|---|
| Password policy: at least 12 characters, not containing the user name, not a common password, not one repeated character | `Policies.passwordProblem`. Test `SecurityHttpTest.passwordAndUrlPolicies` |
| The same answer and the same work for an unknown user and a wrong password | `AuthService.login` (dummy hash), `ApiServer` login. Test `SecurityHttpTest` |
| Sign-out ends the session on the server; a password, role or status change ends all sessions of the user | `AuthService.revokeTokens`, token version in the token. Test `SecurityHttpTest` (token refused after sign-out) |
| Sessions expire (60 minutes by default) | `security.tokenMinutes` |
| A second step at sign-in: one-time codes (RFC 6238), a code accepted once, a wrong code counted like a wrong password; `security.requireMfa` leaves a user without it no permission but to set it up | `Crypto.totp`, `AuthService.login`, `/api/me/mfa/*`. Test `ApiScenarioTest` (second step) |
| Recovery codes for the second step: eight one-time codes shown once, kept hashed, each accepted once in place of the app's code, the sign-in logged as such; a new set against a current code voids the old | `AuthService.login`, `mfaConfirm`, `mfaRecoveryCodes`, `/api/me/mfa/recovery`. Test `ApiScenarioTest` (second step) |
| Keys for systems (`X-Api-Key`): issued, rotated and disabled with a second person, the secret shown once and kept as a SHA-256 hash of a 256-bit random value, roles that never approve or administer, a refused key logged | `AuthService.authenticateApiKey`, `/api/keys`. Test `ApiScenarioTest` (API key) |
| The Console's token is in a cookie no script can read (`HttpOnly`, `SameSite=Strict`, `Path=/api`, `Secure` with TLS), and is not in the sign-in answer | `ApiServer` login with `session: cookie`, `sessionCookie`. Test `ApiScenarioTest` (cookie session) |
| A request that changes something on a cookie session needs the header `X-Orvanta-Console`, which a form on another site cannot send | `ApiServer` filter on `/api/*`. Test `ApiScenarioTest` (forged sign-out is refused) |
| The first administrator password comes from the environment and must pass the policy | `AuthService.seed`. Test `BootstrapTest` |
| Single sign-on (OpenID Connect, authorization code flow): the state is signed by this process and lives ten minutes, the ID token is checked against the provider's published keys (RS256 only) for issuer, audience, expiry and nonce, and only an active Orvanta user by the token's name gets a session; nobody is created on the fly | `OpenIdConnect`, `AuthService.loginExternal`, `/api/auth/sso/*`. Test `SsoTest` |

Open: no SAML. Single sign-on has been
tested against a stand-in provider only, not yet against the bank's own; the second step is left to the provider for users who sign in that way.
The secret of the second step is stored as it is in the user's record, because the server has to compute
codes from it; protect the database accordingly. No password expiry or
history. Failed sign-ins are counted per process. The session cookie has no `Secure` attribute unless
the server runs with TLS or `server.behindTlsProxy`; run it that way outside development.

## A08 Software and data integrity failures

| Control | Evidence |
|---|---|
| Deployments can be sealed with a keyed digest; a deployment changed directly in the database is refused | `Deployments.seal`, `security.integrityKey`. Test `SecurityHttpTest.aDeploymentChangedInTheDatabaseIsNotRun` |
| Release packages come with SHA-256 files | `orvanta-dist` |
| YAML is loaded with the safe constructor: no arbitrary object creation | `ModelSource.parse`, `Config.load` |
| A channel can require an API key and limit message size | channel `transport` and `maxBytes` |

Open: the seal is off until a key is configured. Release packages and images are not signed.
Inbound files are not checked for a signature or checksum from the sender.

## A09 Security logging and monitoring failures

| Control | Evidence |
|---|---|
| A security log of sign-ins, failures, throttling, lock-outs, sign-outs and refused permissions, with user and address, stored and written to the log | `SecurityLog`, collection `orv_security`, Console "Security log". Test `SecurityHttpTest` |
| An audit trail of every step of every payment, message, outbound file and approval, with the acting user | `Platform.event`, collection `orv_event` |
| Passwords and tokens never reach a log or an event | Test `SecurityHttpTest` (log content), compose test (no secret in container logs) |

Open: no alerting (nothing notifies anyone when sign-ins fail repeatedly). Logs are plain text;
shipping them to a monitoring system and a structured format are deployment work. The audit trail
can be changed by anyone with write access to the database.

## A10 Server-side request forgery

| Control | Evidence |
|---|---|
| A Connector or HTTP destination may only use http or https and may never point at a cloud metadata address or a link-local address; checked when the model is validated, so a bad URL cannot be deployed | `Policies.urlProblem`, `Deployments.build`. Test `SecurityHttpTest` (metadata address, file URL refused) |
| An allow-list of hosts the server may call | `security.connectorHosts` |
| Only users with `studio.edit` can propose a connector, and a second user must approve it | maker-checker |

Open: the allow-list is empty by default, which allows any public host. A host name that resolves
to an internal address is not detected (DNS rebinding); the allow-list is the defence.

## What an independent penetration test needs

| Item | State |
|---|---|
| A test environment separate from development, with TLS, the simulator off, an allow-list and the integrity key set | to be set up; the compose file is the starting point |
| Test accounts for each role (administrator, operator, designer, approver, a user with no role) | create through the Console; the roles exist |
| Scope: the Console, the REST API (`/api`, `/api/x`, `/in`), file and queue channels, the model compiler | this document and `README.md` describe them |
| A threat model | not written yet |
| Sample messages for every supported type | `workspace/tests/messages/` |
| A process for fixing and retesting findings | to be agreed with the tester |

## Scan results

Run on 2026-10-04 against build 0.1.0.

**Static analysis** (`mvnw -Psecurity verify -DskipTests`, SpotBugs with FindSecBugs, security and
correctness categories, effort Max): passes with no open finding. Three defects it found were fixed
(a null dereference in duplicate handling, a sleep while holding a lock, inconsistent locking in the
transport service). Four finding types were reviewed and accepted, with the reasons recorded in
`config/security/spotbugs-accepted.xml`: regular expression denial of service (the flagged
expressions cannot backtrack), path traversal in schema lookup (the name is checked first), line
breaks in log messages (caller-supplied text is cleaned), Unicode case handling (protocol constants only).

**Dynamic scan** (OWASP ZAP baseline, passive, unauthenticated, against a running server): 64 rules
passed, 0 failed, 3 warnings.

| Warning | Assessment |
|---|---|
| Cross-Origin-Embedder-Policy header missing | fixed: the header is now sent |
| Storable and cacheable content | accepted: the console's static files carry no user data; every `/api` response is `no-store` |
| Modern web application | informational: the console is a single-page application |

The baseline scan does not sign in and does not attack. An authenticated active scan is still to be run.

**Dependency scan** (`python bin/osv-scan.py`, the libraries of the SBOM checked against the OSV
database). First run: 28 known vulnerabilities in three library families. All were removed by upgrading:

| Library | Was | Now | Why |
|---|---|---|---|
| Jackson (databind, core) | 2.17.2 | 2.21.7 | 15 advisories, among them a type validator bypass and several denial-of-service issues |
| Javalin with Jetty | 6.3.0 with Jetty 11.0.23 | 7.2.3 with Jetty 12 | 6 advisories in Jetty 11, three of them fixed only in Jetty 12 (request smuggling, URI and authority parsing) |
| RabbitMQ Java client | 5.21.0 | 5.37.0 | 7 advisories (frame size and memory exhaustion, class loading in JSON-RPC, TLS trust default) |
| bcpkix, bcutil (come with the IBM MQ client) | 1.81 | 1.86 | 1 advisory in bcpkix (CVE-2026-5588, a weak algorithm); found when the IBM MQ client 9.4.4.1 was added on 2026-10-06 and pinned to the platform's Bouncy Castle version |
| lz4-java (comes with the Kafka client) | 1.10.2 | 1.11.1 | 1 advisory (CVE-2026-59949: invalid ranges can crash the JVM); found when the Kafka client 4.3.1 was added on 2026-10-06 and pinned in the parent POM |

Second run after the upgrade: 57 libraries checked, 0 known vulnerabilities. All tests pass on the new versions.

Scan of 2026-10-06, after Apache MINA SSHD 2.19.0 (SFTP) and the Kafka client 4.3.1 were added: 65 libraries, 0 known vulnerabilities.
Bouncy Castle 1.86 (`bcpg-jdk18on`, `bcprov-jdk18on`) was added the same day for OpenPGP on file transports: 68 libraries, 0 known vulnerabilities.

The OWASP Dependency-Check profile (`-Pdependency-scan`) is also in the build. Its first run downloads
the whole NVD database and did not finish in a practical time here without an NVD API key; with a key
(`NVD_API_KEY`) it is the more thorough check and should run in the pipeline.
