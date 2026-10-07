# SBG / VolPay Platform – Deep Analysis

Date: 2026-09-14
Scope: SBG project (`F:\SBG\sd-bk`), VolPaySDK (`F:\Projects\VolPaySDK`), Designer, Volbase, VolPay 3x, VolPayUI 3x.

---

## 0. The product stack in one picture

```
Volante Designer 7.2.0  (IDE + code generator + runtime jars + volante-tasks maven plugin)
        │  produces: com.volantetech.volante:* runtime jars, volante-tasks plugin, .car cartridge compiler
        ▼
VolBase 4.3.1           (framework: services engine, persistence managers, transport, auth, health, liquibase mgr)
        │  groupId com.volantetech.services.engine / com.volantetech.volante.cartridge  version 4.3.1
        ▼
VolPay 3x 3.5.2.1       (payment rails: core + oms + swift/sepa/rtp/fednow/ach…; released as VolPaySDK zip)
        │  installed into local .m2 with version "1.0" via VolPaySDK/install.cmd  (3298 cartridge + 1346 engine artifacts)
        ▼
VolPayUI 3x             (Angular 11 metadata-driven SPA, shipped as a prebuilt WAR: volpayui / volpayzaui)
        ▼
SBG (sd-bk/sbg-impl)    (customer-onboarding implementation: adapters, task configs, validations, SBG data structures,
                         Liquibase scripts, 6 WARs, docker/helm, patches)  – tenant SBG_ZA, South Africa
```

Team ownership chain (as described by the user and confirmed by the code):
Designer team → Volbase (framework engineering) → VolPay (RAIL team, publishes VolPaySDK) → VolPayUI (UI only) → SBG (customer onboarding, consumes VolPaySDK + reference cartridges, writes SBG-specific cartridges).

---

## 1. SBG repository: `F:\SBG\sd-bk`

### 1.1 Repo root

| Item | Purpose |
|---|---|
| `build.properties` | Release manifest: VolPay 3.5.2.1 (build 67258), VolBase 4.3.1, Designer 7.2.0, BuildUtils 3.1.2, TestUtils 2.1.3, JDK 17, tenant `SBG_ZA`, products (OTT, DOMESTIC-URGENT/NORMAL, H2H-BATCH, XIAT, IAT, COLLECTION, SYNC-DR/DRCR, DEBIT-CHECK, FLIGHT-DECK, CDV-AMS…), rails (SWIFT, RTGS, ACH, XIAT, IAT, RTC), infra (MongoDB read/write, Zookeeper, Kafka, RabbitMQ, Tomcat 9.0.86), auth (AzureAD + PING external + internal JWT), last PROD tag `SBG-2026-06-22-V1.0-20AUGUST26-PROD-R62`. |
| `ServerJenkinsFile.groovy` | Legacy GitLab Jenkins pipeline: copies `VolPaySDK*.zip` from the `VolPay-Deploy` job, unzips into `./VolPaySDK`, writes `designer.cfg` with `volbase.datastructures` / `volpay.sdk` macros, then runs `CleanSource.sh → 1_BuildSourceCloud.sh → 2_BuildDeployment.sh → 3_ApplyPatch.sh` inside the `cicdcontainervol.azurecr.io/volpaysdk:<build>` docker image, then `awsSetup.sh` (docker build + ECR push). |
| `sbg-build.dockerfile` | Same four scripts run in image `vp3xcononprodacr1.azurecr.io/sbg-dc:6.6.0.3.1.12.1032` (Azure DevOps path). |
| `devops/pipelines/*.yaml` | Azure DevOps pipelines per env (Dev/QA/SIT/UAT): build, app deploy, script deploy, restart, logs, image promotion, MS Defender/Trivy config. |
| `docs/` | 70+ folders of runbooks/utilities: local setup guide, Liquibase guide, CI/CD guide, code quality checklist, Azure/PING auth integration, camel-route-runner, mongo utilities, vault integration, 2x→3x migration, etc. |
| `.gitignore` | Ignores `VolPaySDK/`, `VolBaseSDK/`, `**/java/` (Designer-generated code), `target/`, `generated-wars/`, `prepare-deployment/`, `**/WEB-INF/lib/` (jars are build outputs). |
| git | Remote `dev.azure.com/VolanteDevOps/SBG/_git/SBG`; current branch `migrate-latest-volpaysdk` (migration from VolPay 3.1.x/VolBase 1.1.x/Designer 6.6/Camel 2 → 3.5.2.1/4.3.1/7.2.0/Java 17/Spring 6/Camel 4). Other branches: develop, master, release, vulnerability-fix. |

**Note:** the SDK is *not* at `F:\SBG\sd-bk\VolPaySDK` (gitignored, absent). The consumed SDK lives at `F:\Projects\VolPaySDK` and the local `D:\Volante Designer 7\config\designer.cfg` points there (`volpay.sdk=F:\Projects\VolPaySDK`, `volbase.datastructures=F:\Projects\VolPaySDK\core\data-structure\VolBase`). An older SDK copy is at `F:\Projects\SBG Old SDK\VolPaySDK`.

### 1.2 `sbg-impl` layout

```
sbg-impl/
├── pom.xml                       root reactor (profile mongo-mongo-1 → adapter, sbg-data-structure, application)
├── adapter/                      Designer project (adapter.vpj) – ingress/egress transformations
│   └── src/main/volante/{interface-adapter, interface-impl, isosbg-adapter, pain-008-001-10, sbg-account-lookup-adapter}
├── sbg-data-structure/           pom aggregator
│   ├── refdata-core-structures/  19 cartridges: *-task-config, hold-transaction-config, jolokia, EntityCatlogInheritEP…
│   │   └── src/main/resources/   Liquibase changelogs for restresource, menuresourceassociation, metaviewinfo, searchmetainfo, tenantspace, transportcontext, localemaster, msgfieldinfo
│   └── txn-sbg-structures/       acct-lookup-structures, btch-cstmr-cdt-trf-trace, bulk-cdv-lookup-structures, cstmr-cdt-trf-trace, document-strucutes
├── application/                  pom aggregator → 6 WARs
│   ├── transaction-webapp/       finalName transaction – 47 cartridges (validations, enrichment, PGP, IBM MQ, warehouse, schedulers…) + 12 Java classes
│   ├── rest-webapp/              finalName rest – 7 cartridges (api-orchestrator, sbg-authentication, keycloak-authentication-services…) + PING/ExtAuth servlet filters
│   ├── sync-webapp/              finalName sync – 5 cartridges (ack generation, batch txn flow, document upload)
│   ├── simulator-webapp/         finalName simulator – 7 cartridges (IBM API gateway, PGP→MQ, rest-invoker, utility-impl)
│   ├── script-webapp/            finalName script – Liquibase runner (LiquibaseContext.xml, 73 beans) + sbg-delete-script
│   └── utility-webapp/           finalName utility – standalone Spring Boot 2.1 + Camel 2.x S3 uploader (NOT a Volante app)
├── config/                       common-configs, rest, sync, file-upload, PersistenceModels/PersistenceModel-mm.json, VolanteMigrator
├── scripts/                      Liquibase: non-tenant-scripts/v1.0 + south-africa/v1.0 … v50.0 (+ adhoc per env)
├── libs/                         VolPay-license.jar, script-app-sprint-1.0-SNAPSHOT.jar (installed to .m2 as com.volante:*:1.0.0)
├── script-batch/                 executeScripts.{sh,bat}: `java -cp .:lib:lib/*:classes:config com.volantetech.services.ScriptAppMain`
├── deployment/docker/            base-image-{ubuntu,centos} (Tomcat 9.0.97, JDK 11, certs, PGP keys, keystores), docker-build.sh (ECR push)
├── deployment/helm/all-helm/     charts sbg-bo-initial / sbg-bo-business / sbg-bo-perimeter + per-env values (dev/qa/sit/uat/nft/prod)
├── patch/                        LEGACY binary hot-patches (VolPay 3.1.x era) – kept as archive, not applied
├── patch-compatible.example/     README describing the new overlay mechanism used by 3_ApplyPatch
├── patch-product-impl/           SBG-modified copies of product cartridges (.car) + Fix_details.txt per fix (account-lookup, batch-processor, daily-operating-window, rule-profile, service-validations, transaction-processor…)
├── azure-ext-auth-impl/          AzureADAuthHandler.java + sso-config.json (OpenID Connect)
├── monitor-impl/                 metrics patch jars (1.1.22-8), studio-ui.zip, volpayui.zip
├── sbg-tools/                    Designer utility cartridges (pain1v3→v11 converter, ref-data conversion, transport corrections, demo rest client)
├── tenantId-updater-tool/        velocity-based tool to re-tenant scripts
├── samples/                      pain.001/002, MT103/MT900, DebiCheck, holiday JSONs
├── volpayui/, volpayzaui/        prebuilt VolPayUI WAR (exploded) – identical; tenant SBG_ZA, SSO on, uiConcurrency on
├── volpay-edge/                  another prebuilt Angular UI build
├── payment-initiation-ui/        separate SBG-built Java/Camel/MQ pain.001 initiation console (com.codex.payment)
└── mongoDB*.js, awsSetup.sh, healthCheck.sh, uploads3.sh
```

### 1.3 Maven model

**Root `pom.xml`** – `com.volantetech.services.engine:sbg-impl:1.0.0`, packaging pom.

Key properties:

| Property | Value | Meaning |
|---|---|---|
| `volante.build.version` | 7.2.0 | Designer runtime + `volante-tasks` plugin version |
| `volbase.version` | 4.3.1 | all `com.volantetech.services.engine:*` and `com.volantetech.volante.cartridge:*` framework artifacts |
| `volpay.version` | **1.0** | VolPay SDK artifacts as installed by `VolPaySDK/install.cmd` (SDK re-versions everything to 1.0 locally) |
| `spring.version` | 6.2.17 | Spring 6 (Jakarta) |
| `camel.version` | 4.4.2 | Camel 4 |
| `kafka.version` / `activemq.version` / `zookeeper.version` | 4.2.0 / 6.1.6 / 3.9.5 | |
| `liquibase.version` | 4.19.0 | |
| `log4j.version` / `slf4j.version` | 2.25.4 / 2.0.16 | |
| `netty.version` | 4.2.13.Final | |
| `TenantId` | SBG_ZA | |
| `maven.compiler.release` | 17 | |
| `wartype` (profile) | mongo-mongo-1 | read DB = Mongo, write DB = Mongo; drives `generated-wars/${wartype}` |

Only profile `mongo-mongo-1` lists modules, so every mvn call must pass `-P mongo-mongo-1` (all scripts do).

Root build plugins:
- `com.volantetech.volante:volante-tasks:7.2.0` – the Designer Maven plugin. Root config: `rebuild=true`, `projectFile=${basedir}\${project.artifactId}.vpj`, `targetVersion=17`, `excludeVolanteDependencies=true`, `home=${env.VOLANTE_HOME}` (**requires `VOLANTE_HOME`**, locally `D:\Volante Designer 7`), `useMavenJarName=true`, junit report to target. Executions bound to phase `none` at root; each module rebinds `build` to `compile` (and `test` to `test`).
- `maven-clean-plugin` 3.5.0 deletes Designer-generated `**/java/` folders, `target/`, `WEB-INF/lib/`, `generated-wars/${wartype}`.

**Module pattern (adapter, refdata-core-structures, txn-sbg-structures):** packaging jar, only plugin = `volante-tasks` `build` goal at `compile`, `targetDir=${basedir}/target`. The plugin opens the `.vpj`, compiles every referenced `.car` cartridge to Java, javac's it and emits one jar per cartridge (`cartridge-<name>-1.0.jar`) into target.

**WAR modules (transaction/rest/sync/simulator/script):**
- `volante-tasks` with `targetDir=${basedir}/src/main/webapp/WEB-INF/lib` → generated cartridge jars land directly in WEB-INF/lib (which is why `WEB-INF/lib` is gitignored). `rest-webapp` also sets `clearMavenDependencyCache=true` and stages `jakarta.servlet-api-6.0.0.jar` into WEB-INF/lib at generate-resources so Designer can compile servlet-facing Java.
- `maven-war-plugin` 3.5.1: `outputDirectory=../../generated-wars/${wartype}`; `webResources` pull sibling module jars (`adapter/target`, `sbg-data-structure/*/target`, `libs/`) into WEB-INF/lib; a very long `packagingExcludes` list strips conflicting transitive jars (old spring 5.x, javax servlet, jaxb 2.x, netty 4.1.x, zookeeper 3.7/3.9.x duplicates, log4j 1.x, logback, Designer 6.3 dynamicforms, `composer/designer/dynamicforms/javaCG-7.2.0.jar` etc.). This list is effectively the dependency-conflict resolution strategy of the whole build.
- `maven-resources-plugin` (validate): copies `config/common-configs`, `config/rest`, `config/file-upload` (filtered) into `target/<war>/WEB-INF/classes`; script-webapp copies the whole `scripts/` tree.
- `copy-rename-maven-plugin`: `config/PersistenceModels/PersistenceModel-mm.json → WEB-INF/classes/PersistenceModel.json` (`mm` = mongo/mongo), `config/VolanteMigrator/RestResource-delete.xml`.
- `maven-compiler-plugin` 3.14.1, source/target 17.
- script-webapp includes `cartridge-global-validations-impl-1.0.jar` from transaction-webapp's WEB-INF/lib (PSATaskConfig validation needs that internal message at script time) – so module order matters: transaction builds before script.

Dependencies by WAR (all versions via properties):

| WAR | Volbase (4.3.1) | VolPay (1.0) | Third-party highlights |
|---|---|---|---|
| transaction | warm-up-listener, identity-warm-up-listener, cartridge-crypto-profile | cartridge-btch-cstmr-cdt-trf, cartridge-cstmr-cdt-trf-trace-structure, cartridge-gpi-identification, cartridge-gpi-initiation | camel-{core,jms,jsonpath,servlet,amqp,spring-rabbitmq,http-common,timer}, IBM MQ jakarta client 9.4.1.1, rabbitmq-jms, okhttp, groovy 3, gremlin-driver |
| rest | authentication, authentication-external-saml, security-adapter, warm-up listeners, keycloak-adapter-spi(4.3.1), cartridge-instruction-out-processor | cache-preload-listener, manual-payment-initiation, cartridge-keycloak-authentication-services | keycloak-core 25, nimbus-jose-jwt 9.47, auth0 java-jwt/jwks-rsa, jose4j, cxf jose, jasperreports 7, liquibase, zookeeper |
| sync | application-metrics, health-check, warm-up listeners | cache-preload-listener, cartridge-cstmr-cdt-trf-trace-structure | camel, rabbitmq, IBM MQ |
| simulator | ~25 framework artifacts: persistence managers (mongodb/rdbms/cassandra/kafka), kafka-split, mongo-split, transport-manager-v2, instance-manager, health-check(-v2), bulking-utils; framework cartridges (app-env, event-base/receive, task-master, task-association, rest-resources, framework-change-log, party-base) | ~30 VolPay ref-data cartridges (branch, calendar, country, currency, office, party, region, daily-operating-window + *-ext-base), adapters (account-lookup, account-posting, charges, fraud, funds-control, fx, limit, liquidity, sanction, document-*), volpay-util-{flows,messages,resources} | volante-{rest,xml,resource-manager,services-manager}, jta, sigar, xz |
| script | security-adapter, warm-up-listener | core-script-artifacts, oms-script-artifacts, concurrency-manager, menu-resource-association-delete | liquibase (via framework), camel-amqp |
| utility | none (Spring Boot 2.1.1 parent, Camel 2.17/2.25, aws-java-sdk-s3, IBM MQ allclient 9.1) | | Not part of the Volante stack; a separate microservice that still outputs into generated-wars |

### 1.4 Build scripts (what each does, in order)

| Script | Steps |
|---|---|
| `CleanSource` | `mvn clean -P mongo-mongo-1` (clean plugin filesets above). |
| `BuildSource` (local) | 1) `MAVEN_OPTS=-Xmx4g`; 2) install `libs/VolPay-license.jar` → `com.volante:VolPay-license:1.0.0` and `libs/script-app-sprint-1.0-SNAPSHOT.jar` → `com.volante:script-app:1.0.0` into `.m2`; 3) `mvn package -P mongo-mongo-1`; 4) explode `generated-wars/mongo-mongo-1/script.war` into `script/`, rename `META-INF→config`, move `WEB-INF/lib→lib`, `WEB-INF/classes→classes`; 5) copy `script-batch/*` into it → a runnable Liquibase script-app (`executeScripts.bat`). |
| `1_BuildSourceCloud` | Same as BuildSource steps 1–3, then explodes ALL six wars (transaction, rest, simulator, sync, utility, script) into folders and overlays `deployment/log4j.properties` + `deployment/log4j2-<app>.xml` into each `WEB-INF/classes`. |
| `2_BuildDeployment` | Recreates `prepare-deployment/` from `deployment/docker/*` (Dockerfiles per app + base images), copies each exploded war into `prepare-deployment/<app>/<app>`, copies `volpayzaui` (and on .sh also `volpayui`, `payment-initiation-ui`) into `prepare-deployment/ui`. `docker-build.sh` then builds/pushes `script-app, transaction-app, rest-app, simulator-app, sync-app, ui-app, utility-app` images to AWS ECR `463859415203.dkr.ecr.us-east-1.amazonaws.com` with tag `SBG-<date>-Vx`. |
| `3_ApplyPatch` | Overlay-copies `patch-compatible/<app>/...` onto `prepare-deployment/<app>/<app>/` for transaction, rest, script, simulator, sync, utility. Exits 0 with a message if `patch-compatible` does not exist (current state: only the `.example` README exists). Explicitly refuses the legacy `patch/` binaries (VolPay 3.1.x / Designer 6.6 / Camel 2 jars). |

Local flow: `CleanSource → BuildSource → 3_ApplyPatch` (3_ApplyPatch is a no-op locally unless `patch-compatible` and `prepare-deployment` exist; the legacy local doc instead says copy wars from `generated-wars` to Tomcat webapps and run `executeScripts-local.bat`).
Cloud flow: `CleanSource → 1_BuildSourceCloud → 2_BuildDeployment → 3_ApplyPatch → docker-build.sh`.

Prerequisites the scripts assume: JDK 17, Maven, `VOLANTE_HOME` set to a Designer 7.2.0 install with a runtime license, `designer.cfg` `[Variables]` pointing to the SDK (cartridge references use `${volpay.sdk}` and `${volbase.datastructures}` macros), and the SDK already installed into `.m2` (see §2).

### 1.5 Local Maven repository (`C:\Users\Sunny\.m2\repository\com`)

| Path | Contents |
|---|---|
| `volantetech/volante/` | Designer 7.2.0 runtime: `volante-core`, `volante-rest`, `volante-xml`, `volante-swift`, `volante-sepa`, `volante-mongodb`, `volante-dynamicforms`, `camel4-volante`, `spring-volante`, `volante-tasks` (7.2.0 & 7.1.0), plus `cartridge/` (**3649** cartridge artifacts: 3298 at `1.0` = VolPay SDK, 318 at `4.3.1` = Volbase, 315 at `2.0.0` = older Volbase) |
| `volantetech/services/engine/` | **1429** artifacts: 1346 at `1.0` (VolPay SDK incl. `oms-script-artifacts`, `core-script-artifacts`, adapters, services), 71 at `4.3.1` (Volbase framework: persistence managers, authentication, health-check, kafka-utils, liquibase-manager, transport-manager-v2 …), 73 at `2.0.0` |
| `volante/` | `VolPay-license:1.0.0`, `script-app:1.0.0` (installed by BuildSource) |
| `volpay/volpay-vault-adapter:1.0.0` | HashiCorp Vault adapter used by the ubuntu base image |
| `volante_SBG/`, `volantetech_SBG/` | **Renamed backups of the OLD SBG repo**: 464 artifacts at `3.1.12-14204` (VolPay 3.1.12), Volbase `1.1.20 / 1.1.22-8 / 2.0.0`, Designer `composer/designer/javaCG` jars. Not referenced by the current poms (they resolve `com.volantetech`, not `com.volantetech_SBG`); they only matter if you swap folder names back to build the pre-migration branch. |
| `volante_v3.5.0/`, `volantetech_v3.5.0/`, `*_SCBNACHA/` | Same trick for other SDK versions / projects. |

The SDK artifact poms carry parent chains like `oms-script-artifacts:1.0 → oms-artifacts-default:1.0` and pin their own log4j/slf4j versions, which is why the WAR poms need the big `packagingExcludes` lists.

### 1.6 Runtime wiring (transaction-webapp `web.xml`, representative)

- Jakarta EE 10 `web-app 6.0`, `metadata-complete=true`.
- Context params: `TransportScope=property,DeadLetter,beancommon,beantransaction,credit-transfer,Cache,common,commoninstruction,transaction` (which transport-context groups this WAR loads), `WORK_DIR=../VPH_messages/work`.
- Listeners: `com.volantetech.services.engine.AppContextListener` (SBG-owned; env checks/thread pool), `com.volantetech.services.engine.server.ApplicationManager` (Volbase bootstrap: loads AppEnv, bean definitions, transport contexts, message functions from Mongo).
- Servlets: `VolanteRestServlet` (Designer REST runtime, `/rest/*`, error handler `VolPayExceptionHandler`), `CamelHttpTransportServlet` (`/transport/*`), `VolBaseHealthServletV2` (`/health`), `VolBaseReadinessServlet` (`/ready`), `VolBaseApplicationMetricsServlet` (`/metrics`).
- rest-webapp adds servlet filters `PingAuthFilter` (validates SBG PING JWT via JWKS with Nimbus, then mints an internal VolPay JWT by invoking Designer message flows through `LookupContextFactory.lookupMessageFlow`), `ExtAuthCookieFilter`, `ThreadingFilter`, `EnvVariableCheckListener`.

### 1.7 Liquibase / scripts

- `application/script-webapp/src/main/webapp/META-INF/LiquibaseContext.xml`: Spring bean file with **73** `com.volantetech.services.engine.VolanteCommonLiquibase` beans, each = (dataSource `readdb` ×30 or `writedb1` ×43, changeLog classpath). Order: framework DDL (`db.framework.main.changelog.xml`), SBG tenant setup (`south-africa/v1.0/application-setup/application.core.tenant.changelog.xml`), product migration, controller/sync/workflow masters, message-function configs, instruction/CCT/ack DDL for both DBs, trace snapshots, duplicate registries, warehouse, account postings, DbtCdtNtfctn trace etc.
- `scripts/non-tenant-scripts/v1.0` (23 files: app-env, log-manager, message formats/types/functions, mongo indexes, rest) and `scripts/south-africa/v1.0 … v50.0` (+ `-adhoc-<env>` folders) – each version folder has `application-setup`, `message-functions`, `reference-data`, `rest`, `entitlements-policy`, `interfaces`. Changesets use custom change types `VolanteInsert`/`VolanteUpdate` with `messageName` + XML data file. Versioning is additive/backward compatible (documented in `docs/volpay-local-setup-guide/Liquibase.txt`).
- `sbg-data-structure/refdata-core-structures/src/main/resources/*.changelog.xml` register SBG REST resources, permissions, menu associations, meta-view/search info, transport contexts and locale entries for the SBG cartridges.
- The `script.war` is exploded into a CLI app and run via `script-batch/executeScripts.sh` (`ScriptAppMain`) or as a K8s job (`SBG-*-Scripts-Deployment.yaml`).

### 1.8 Designer cartridges in SBG (what SBG actually builds)

`.car` files are **XML** (`<cartridge version="6.6.0" …>`), containing Internal Messages, Message Flows (flowelements/links), Formula Functions (Java in `src/`), Validations, Mappings, plus `<references>` to SDK cartridges resolved via macros (`${volpay.sdk}/core/data-structure/...`, `${volbase.datastructures}/src/main/volante/util-flows/util-flows.car`). Each cartridge folder may carry `lib/` (extra jars, e.g. bouncycastle, IBM MQ) and `src/` (hand-written Java formula functions / external classes). `.vpj` = Designer project, `.vpw` = workspace, `.vcw` = cartridge workspace. Generated `java/` folders are gitignored.

Cartridge inventory by module:
- **adapter (5):** interface-adapter (account-posting, pain.001/002 v11 XSDs), interface-impl, isosbg-adapter (+ `XMLDuplicateRemover`), pain-008-001-10 (direct debit), sbg-account-lookup-adapter.
- **refdata-core-structures (19):** PSA task configs (beneficiary-bank-check-oms, beneficiary-field-length, dbtr-cdtr-acct-nb, enrich-bic-from-branch-id, enrich-payment-scheme-oms, membership-oms, multipl-cutoffs, skip-back-dated-payment, transaction-valdate-check, special-character-validation, coll-service-validations, sbg-syncdrcr-beft), hold-transaction-config, cache-reload-impl, batch-response-message, btch-ct-pmtinf-link, btch-ct-warehouse-link, EntityCatlogInheritEP, jolokia.
- **txn-sbg-structures (5):** acct-lookup-structures, bulk-cdv-lookup-structures, cstmr-cdt-trf-trace, btch-cstmr-cdt-trf-trace (SBG extensions of the canonical CCT trace), document-strucutes.
- **transaction-webapp (47):** business-function implementations wired to task configs above: validations (global, control-sum, zero-amount, special-character, beneficiary field length, transaction value date), enrichment (credit-party, BIC from branch, payment scheme), warehouse handling (skip-warehouse, deWarehouse-status-check, domurgent-warehouse-cancel), batch/ack (btch-ack-processing, btch-ack-vets-unpaid, update-btch-tx-ack-sts, persis-btch-pmt-pmtinf-link), bulk CDV lookup, integration (ibm-mq-utility, fetch-token, wrapper-ping-token, update-endpoint-api, push-cct-aws, socket-check, mongodb gridfs/inserter), crypto (pgp-encrypt-decrypt-sign, h2h-crypto-compress-decompress, insert-crypto-ifd, compress-decompress LZMA2), schedulers (bulk-scheduler, transaction-recovery-scheduler), exception handling, document-process, hold-transaction, multiple-cutoffs, sbg-syncdrcr-beft-daycheck, channel-feedback-response, cct-fire-audir-incidence, transaction-processcode-switch-flow.
- **rest-webapp (7):** api-orchestrator (GenericRestInvoker, MongoDBDataFetcher, CurrencyHolidayAggregator, CutoffTimesConverter), mongodb-data-fetcher-impl, sbg-authentication, keycloak-authentication-services, user-role-association-mapping, hold-transaction, view-instr-crypto-impl.
- **sync-webapp (5):** action-handler-aggregation, fetch-upload-document-impl, instruction-ack-generation-impl, sbg-sync-batch-transaction-flow, sbg-transaction-ack-gen.
- **simulator-webapp (7):** Ibm-api-gateway, Ibm-gateway-connectivity-check, cached-object-impl, pgp-encrypt-send-mq-impl, rest-invoker-impl, temenos-ee-doc-impl, utility-impl (JVM monitor, thread/heap dumps, Kafka sender, ZooKeeper API, Mongo sequence ids, Tomcat reloader…).
- **patch-product-impl:** SBG-patched versions of product cartridges (`account-lookup`, `batch-processor`, `common-task-processor`, `credit-party-endpoint-derivation`, `daily-operating-window(+ext-base)`, `debit-party-derivation`, `dispatch-identifier-processor`, `parent-child-info`, `rule-profile`, `service-validations`, `transaction-processor`, `tx-ack-generation`, `volpay-common-flows`, `bcdd/ct-to-ack-mapping`, `payment-prevalidations-check` scripts…) each with `Fix_details.txt`. Their poms have parent `com.volantetech.services.engine:volpay-core:1.0` and use `volante-tasks` – i.e. they are built inside the SDK tree and the resulting jar replaces the SDK jar. This is the "cherry-pick and override" strategy.

Hand-written Java (77 files) is thin and infrastructural: servlet filters/listeners (PING JWT, ext-auth cookies, env checks, custom thread pool), Camel processors (`GenericLoggingProcessor`, `JsonFieldNullProcessor`, `MongoIdempotentCheckProcessor`, `SBGPollingConsumerPollStrategy`), IBM API/DIP connectivity auth, PGP/LZMA helpers, Mongo GridFS/diagnostics, and formula-function classes referenced from cartridges. Business logic lives in cartridges (message flows), per the SBG code-quality checklist (`docs/code-quality-checklist`).

### 1.9 Deployment & infra

- Base image `deployment/docker/base-image-ubuntu/Dockerfile`: Ubuntu 22.04, OpenJDK 11 (note: build is Java 17 – base image should move to 17), Tomcat 9.0.97, mongosh, Kafka 3.8, ZooKeeper 3.8.4, imports AWS/PING/API-gateway certs into cacerts, keystores (`volpay.jks/.p12`, server.*.jks), PGP key rings (`*.asc`, `*.bpg`), Tomcat `server.xml/context.xml/web.xml`, helper jars (connectivity, camel-route-runner, vault adapter), non-root `tomcat` user.
- Per-app Dockerfiles under `deployment/docker/<app>` (copied to `prepare-deployment`) layer the exploded WAR on the base image.
- Helm: three charts (`sbg-bo-initial`, `sbg-bo-business`, `sbg-bo-perimeter`) with values per env; scripts `dev|qa|sit|uat|prod-{deployment,script,microservices,volpayui,logs}.sh`. Target: AWS EKS (+ EC2), ECR registry.
- Health check `healthCheck.sh` polls `/rest/health`, `/ack/health`, `/cct/health`, `/simulator/health`, `/sync/health` (ack/cct names are legacy 2.x app names).

### 1.10 Observations / risks worth flagging

1. **Secrets committed in plain text:** AWS access key + secret in `sbg-impl/awsSetup.sh` and `uploads3.sh`; Azure AD client secret in `azure-ext-auth-impl/Readme.txt`; keystores, PGP private keys (`Volante_Private*.asc`, `secret.bpg`, `SBG_esbsecret.asc`) and prod certs under `deployment/docker/base-image-*`; a Google `clientSecret` in the upstream VolPayUI `sso-config.json`. These should be rotated and moved to Vault/KeyVault (the repo already has a vault adapter and `docs/hashicorp-vault-integration`).
2. **Version drift inside the build:** base image JDK 11 vs Maven `release 17`; `camel-timer` pinned to 3.11.2 while everything else is Camel 4.4.2; `utility-webapp` is Spring Boot 2.1.1 / Camel 2.17 / logback and is excluded from the Volante dependency model; `kotlin-stdlib 1.6.10`, `bcprov 1.60`, `commons-httpclient 3.1`, cxf 3.1.7 are old.
3. **`.car` files still declare `version="6.6.0"`** and carry absolute paths from the original author's machine (`D:/Sripal/...`); resolution works only via the macro path, so every developer/CI must have `designer.cfg` variables set exactly as in the Jenkinsfile.
4. `3_ApplyPatch` is now effectively a no-op (no `patch-compatible` dir), which is intentional post-migration; the legacy `patch/` dir and `monitor-impl` jars (1.1.22-8) are dead weight.
5. `patch-product-impl` cartridges reference parent `volpay-core:1.0` with `relativePath ../../../` – they can only be built when dropped into the SDK tree at the same depth.
6. `packagingExcludes` lists are the de-facto dependency management; a `dependencyManagement`/BOM approach (as Volbase's `volbase-parent` does) would be more robust.

---

## 2. VolPaySDK (`F:\Projects\VolPaySDK`) – what SBG actually consumes

VolPay 3.5.2.1 (build 67258, commit `b916bdcb…`, branch `release`) / VolBase 4.3.1 (build 65796) / Designer 7.2.0 / BuildUtils 3.1.2 / TestUtils 2.1.3 / JDK 17. Not a git repo (extracted zip, 2026-08-17).

### 2.1 Layout
| Path | Contents |
|---|---|
| `bin/` | **The artifact payload: 1,325 module folders, 4,713 jars** (3,529 `cartridge-<name>-1.0.jar`) + poms. Installed into `.m2`. |
| `volbase-bin/` | VolBase 4.3.1 binaries: `volbase-parent/pom.xml` (the real BOM, 1,206 lines), `pom.xml` (`volbase`), `framework/` (69 modules jar+pom), `installVolbaseJars.xml`. |
| `BuildUtilsSDK/` | `bin/` jars v3.1.2 (`sdk-utilites`, `vol-sdkinstaller-maven-plugin`, `volbase-code-generation`, `volbase-utility-addin`), `install.cmd/.sh`, release-tools. |
| `core/`, `chips/`, `egach/`, `fednow/`, `fedwire/`, `lynx/`, `rtp/`, `sepact/`, `sepadd/`, `sepaip/`, `sic/`, `swift/`, `usach/` | `<rail>-data-structure/` Designer sources (`.vpj`/`.car`; core alone 160 vpj / 882 car) + `studio-config/`. Cartridges cross-reference by relative path, so these must ship as source. |
| `oms/`, `tips/` | **`studio-config/` only – no `.car`/`.vpj`.** OMS is binary-only (12 `bin/oms*` modules) + config/scripts under `implementation/`. |
| `core/data-structure/VolBase/` | VolBase `data-structure` sources = `${volbase.datastructures}` macro target. |
| `implementation/` | Reference implementation: `<rail>-impl` shims, `webapps/` (legacy) and `webapps-1/` (current; rest, script, transaction, sync-processor, simulator, dashboardsync, stmt), `volpay-configs/` (`db-configs/{mm,cm,rdbms}-configs`, `core-configs/*`, `<rail>-configs/`), `volpay-scripts/` (`core`, `bank`, `endpoint`, `configs`, `task-changelog`), `studio/` (`switch.json`, `queues/`, `transport-templates/` per version 3.3.0→3.5.2.1, `interface-scripts/`, `interface-liquibase-contexts/`, `process-flow-scripts/<rail>/{business-function-definition,process-flow-definition,task-association}.xml` incl. a `std-implementation/` baseline with only BF definition + task association), `samples/` (150 payloads; none for OMS), `generated-wars/mongo-mongo-1/` (proof build: `volpay-{rest,transaction,sync,dbscripts,stmt,dashboardsync,simulator}-OMS.war`). |
| `core-tools/` | `Data_Dictionary`, `EntityListGenerator`, `VolPaySDK_Lite`, `html-doc-generator`, `sdk-utilities` (`set-volbase-macro`, `generate-bin-folder`, `volbase-sdk-installer`, version/field updaters), `tenantId-updater-tool`, `test-utilities`. |
| `volpayui/`, `volpay-edge/` | Exploded Angular UIs (the source of SBG's `volpayui`/`volpayzaui`/`volpay-edge` folders). |
| `docs/` | 9 module user-guide PDFs only – no install/deployment guide, no release notes (the old SDK had 30+ docs). |
| root | `install.cmd/.sh`, `installJars.*`, `generateInstallscript.sh`, `installVolpayJars.xml` (2.9 MB generated), `pom.xml`, `build.properties`, `license/VolPay3x_Libraries.xlsm`. |

### 2.2 `install.cmd` / `install.sh` – exact behaviour (first-time consumption)
Preconditions: `VOLANTE_HOME` set (Designer 7.2.0), Designer **closed** (checks `tasklist` for the stale string `Designer 6.exe` / `ps -ef | grep designer`), `BuildUtilsSDK` present, `mvn` + JDK 17 on PATH.
1. `BuildUtilsSDK/install.*`: installs `volbase-build-utils:3.1.2` and `build-utils:3.1.2` poms; for each jar extracts the embedded pom with `com.tplus.transform.util.POMUtil` (from `%VOLANTE_HOME%\lib\generalutils.jar`) and `install-file`s it (`volbase-code-generation`, `volbase-utility-addin`, `sdk-utilites`, `vol-sdkinstaller-maven-plugin`); **copies `volbase-utility-addin-3.1.2.jar` into `%VOLANTE_HOME%\plugin`** (why Designer must be closed).
2. Installs `volbase-bin/volbase-parent/pom.xml` → `com.volantetech.services.engine:volbase-parent:4.3.1`; `volbase-bin/pom.xml` → `volbase:4.3.1`; `framework/pom.xml` → `volbase-framework:4.3.1`.
3. `mvn -f installVolbaseJars.xml install` → 413 `maven-install-plugin:3.1.1:install-file` executions bound to `compile` (all 69 VolBase framework jars + poms).
4. Installs `bin/pom.xml` → `volpay-core:1.0` and `bin/business-functions/pom.xml` → `business-functions:1.0` (parents of every cartridge pom).
5. `mvn -f installVolpayJars.xml install` → **5,985 install-file executions** (4,712 jars + 1,273 poms) – every VolPay cartridge/module lands in `.m2` at version **1.0** under `com.volantetech.services.engine` and `com.volantetech.volante.cartridge`.
6. `dependency:copy-dependencies` (excluding designer/javaCG/composer), `mvn install` on root pom, second `copy-dependencies` excluding `volante-*`/`camel-volante` with `-Dmdep.copyPom -Dmdep.addParentPoms` → `target/dependency/`.
7. `java -cp .;%VOLANTE_HOME%/lib/*;…;bin/sdk-utilities/* com.volantetech.volpay.utils.SetVolPayDataStructures` (twice, second with arg `volpay.sdk`) → rewrites Designer `designer.cfg` `[Variables]` so `.car` references via `${volpay.sdk}` / `${volbase.datastructures}` / `${volbase.sdk}` resolve. (`install.sh` omits `$VOLANTE_HOME/runtime/*` from this classpath – a Linux/Windows divergence.)

`installJars.*` = targeted re-install of `bin/` (walks `*.pom`, installs jar+pom or pom-only). `generateInstallscript.sh` regenerates the two install manifests after `bin/` changes. `BuildUtilsSDK/release-tools/deploy.*` push to internal Nexus `200.200.200.172` (Volante-internal only).

### 2.3 POM model
- Root/`bin/pom.xml`: `com.volantetech.services.engine:volpay-core:1.0` (pom). Properties `volante.build.version 7.2.0`, `volante.version [7.2.0]` (**hard range pin** – cartridges will not resolve against another Designer runtime), `volbase.version 4.3.1`, `buildutils 3.1.2`, `testutils 2.1.3`, `volpay.version 1.0`, Camel 4.4.2, Spring 6.2.17, ActiveMQ 6.1.6, Log4j 2.25.4, Netty 4.2.13, ZooKeeper 3.9.5, `skipTest=true`, `copyartifacts.dir=${basedir}/bin`. Plugins: `volante-tasks` 7.2.0 (`rebuild=true`, `projectFile=${basedir}\${project.artifactId}.vpj`, `targetVersion 17`, `home=${env.VOLANTE_HOME}`, `useMavenJarName`, `generatePomDependenciesFile`), `volbase-code-generation:3.1.2` (install phase), `maven-install-plugin:3.1.4` with default-install disabled, compiler 3.15.0 (17), **`maven-clean-plugin` fileset includes `bin/` – never run `mvn clean` at SDK root**, resources plugin copying `target/*.jar`+pom into `bin/` at install. Profiles: numbered core shards + one per rail + `MANDATE`.
- `volbase-bin/volbase-parent/pom.xml` 4.3.1 = the real BOM (`dependencyManagement` for all `com.volantetech.volante:volante-*` at 7.2.0, `sdk-utilites`, test utils; `pluginManagement`; extra repo `shibboleth` for OpenSAML → build needs network or a mirror).
- Cartridge pom pattern: parent `business-functions:1.0` (or `core`/`oms`/`volpay-core`), artifact `<name>:1.0`. Bundle poms `core-artifacts-default` → `core-{script,transaction,rest,sync-processor}-artifacts`; `oms-artifacts-default` → `oms-{rest,script,stmt,sync-processor,transaction}-artifacts` (pom-only aggregations of ~160 cartridge dependencies each).
- Three groupIds: `com.volantetech.services.engine` (modules/bundles, 1.0 / VolBase 4.3.1 / BuildUtils 3.1.2), `com.volantetech.volante.cartridge` (every compiled cartridge, 1.0), `com.volantetech.volante` (Designer runtime + `volante-tasks`, 7.2.0). Verified in SBG `.m2`: 3,649 cartridge artifacts, 1,429 engine artifacts.
- `com.volante:VolPay-license:1.0.0` and `com.volante:script-app:1.0.0` are **not** referenced by the SDK; they come from SBG's own `libs/` (installed by `BuildSource`).

### 2.4 Consumption contract (`implementation/pom.xml`)
Two-axis profiles: rail (`CORE, RTP, RTP5, TIPS, FEDNOW, SWIFT, FEDWIRE, CHIPS, LYNX, SEPACT, SEPADD, SIC, SEPAIP, SEPAIP-2025, EGACH, USACH, MANDATE, OMS, VERAFIN` – each sets `railconfig-folder` and `append.war.version.rail`, activates `<rail>-impl`) × war-type (`mongo-mongo`, **`mongo-mongo-1`** → `webapps-1`, `cassandra-mongo`). Build: `mvn install -P OMS,mongo-mongo-1` → `implementation/generated-wars/mongo-mongo-1/volpay-*-OMS.war`. Properties a customer fork overrides: `TenantId` (MASTER), `war.version`, `webapps.folder`, `*.webapp` names, `liquibase 4.19.0`, `jasperreports 7.0.6`, `rabbitmq 1.15.2` – **SBG's root pom is this property block verbatim** with `TenantId=SBG_ZA`.

`oms-impl/oms-rest/pom.xml` shows the whole pattern: depend on `oms-rest-artifacts:1.0`, `copy-dependencies` with `includeGroupIds=com.volantetech.services.engine,com.volantetech.volante.cartridge` into `webapps-1/rest-webapp-1/temp/volpay-rest-OMS/WEB-INF/lib`, `copy-resources` from `volpay-configs/oms-configs/common-configs` into `WEB-INF/classes`. `oms-scripts` copies `volpay-scripts/endpoint/oms` + `task-changelog/oms` into `WEB-INF/classes/endpoint/oms` and `oms-configs/liquibase` (`oms_bankliquibase_context.xml`, `oms_endpointliquibase_context.xml`) into `META-INF`. SBG replaced the shim layer with direct dependencies on `oms-script-artifacts`/`core-script-artifacts` + individual cartridges, and its own `config/` + `scripts/` + `LiquibaseContext.xml`.

Liquibase in the SDK: contexts named `<x>liquibase_context.xml` (rail `_bank`/`_endpoint` variants, interface contexts for accountlookup/accountposting/charges/document*/fraud/funds/fx/liquidity/sanction, `DfltPrcFlowCfg`); changelogs under `volpay-scripts/{endpoint/<rail>, bank/<rail> (PFD-*.xml process flows), core, configs}` plus the `bank/sample-*` onboarding set (sample-bank-setup, sample-customer, sample-users, sample-routing, sample-oms…). Transport templates versioned 3.3.0…3.5.2.1; latest OMS template is 3.5.0.1.

### 2.5 Old SDK (`F:\Projects\SBG Old SDK\VolPaySDK`, VolPay 3.1.12-14204 / VolBase 1.1.22-8 / Designer 6.6.0 / JDK 11 / BuildUtils 1.9.2, branch `v3.1.12-EMEA-OMS`)
- Old root pom was a real BOM `volpay:3.1.12-14204` listing every module at `${project.version}`; new SDK is `volpay-core:1.0` with everything at 1.0 – the release version is only in `build.properties`, so two SDK drops overwrite each other in `.m2` (hence the user's renamed `*_SBG` backup folders).
- Old `sample-impl/` was a customer-extension cookbook (adapter-impl, bank-routing-extension, bulking-impl, glueback-impl, id-generation, keycloak-impl, encrypt_decrypt, eg-ach…); new `implementation/` has none of these (they live only in the VolPay3x repo `core/sample-impl`). Old `docs/` had 30+ guides (Installation, Deployment, Transport, Reference Data, Interface Manager, Warehousing, Incidence/Action frameworks…); new has 9.
- Old install used inline `for /R` loops with `POMUtil` + `maven-install-plugin:2.5.2`; new pre-generates the XML manifests. Old root `release-tools/` migration utilities are gone.

### 2.5a Disk footprint (measured)
`F:\Projects\VolPaySDK` is **17 GB**. `implementation/` alone is 14 GB / 60,928 files, almost all of it `target/` and `temp/` output left behind by the last OMS build (exploded `temp/volpay-*-OMS/WEB-INF/lib` in every module plus `generated-wars/`). A clean copy is a small fraction of that, so do not version or copy the folder wholesale. `bin/` is 704 MB / 5,991 files and matches the 5,985 `install-file` executions in `installVolpayJars.xml`, so the manifest is current and `generateInstallscript.sh` does not need re-running before a first install. `tips/` holds a single 1 KB file (an empty placeholder despite having root and implementation profiles), and `oms/` is 46 files / 2.4 MB of reference data only.

### 2.6 Risks for SBG
No onboarding docs in the SDK; no GAV pins the VolPay release; `mvn clean` at SDK root wipes `bin/`; OMS is binary-only so customisation is by layered cartridges (SBG's `patch-product-impl` `.car` sources come from the *old* 3.1.x SDK and are version-stamped 3.1.10/3.1.11); `VOLANTE_HOME` is load-bearing in four places; `tools/volbase-release-updater` ships only its README.

---

## 3. VolPay 3x 3.5.2.1 – the RAIL product (`E:\Projects\Volpay3x-SingleSDK-v3.5.2.1\VolPay3x`)

### 3.1 Shape
Not primarily Java: 1,605 poms, 3,864 `.car`, 1,391 `.vpj`, 1,725 XSDs, only 144 Java files. Payment logic = Designer cartridges + reference-data XML (`ProcessFlowDefinition`, `TaskMaster`, `TaskAssociation`, `IncidenceMaster`, `BusinessFunctionDefinition`) driving a data-driven state machine. `build.properties`: VolPay 3.5.2.1, VolBase 4.3.1 (build 65796), Designer `Studio_7_2_4-Designer7_2_0`.

```
VolPay3x/
├── pom.xml            com.volantetech.services.engine:volpay-core:1.0 (pom) – ALL modules are version 1.0; modules selected only by profiles
├── core/              rail-agnostic engine: data-structure (104 modules, canonical "trace" model), functional-components/
│                      {business-functions (359), interfaces (16 external adapters: sanction, fraud, funds-control, liquidity, limit,
│                      fx, charges, account-lookup/posting, document-*, mandate-lookup, verafin), iso-adapter (52 ISO cartridges),
│                      manual-adapter, sync-flows, Reverse-Sync-flows, tx-ack, read-to-write-mapping, translator-processor,
│                      volpay-business-rules, liquibase-extender}, rest-services (111), listeners (cache-preload, exception-handler,
│                      external-auth, oauth-utils, sso, space-validator), migration-utils (24), core-artifacts-default, sample-impl (30+ how-to samples), tools
├── oms/               the OMS rail (thin: adapters + 6 BFs + artifacts + studio-config reference data) – what SBG consumes
├── chips/ egach/ fednow/ fedwire/ lynx/ rtp/ sepact/ sepadd/ sepaip/ sic/ swift/ tips/ usach/   uniform rail template
├── implementation/    reference implementation / customer template: <rail>-impl shims, webapps(-1)/, volpay-configs(-1)/, volpay-scripts(-1)/, studio/
├── std-implementation/ustchrtp/   productised TCH-RTP template (NOT shipped in SDK zip)
├── tools/ (build-utils, tenantId-updater-tool, volbase-release-updater), devops/ (32 Azure pipelines, k8s manifests, setupfiles), license/
└── build_volpay.sh/.bat, build_dev.sh, build_nacha.sh, build-changed-modules.sh, find-changed-files.sh, generateSDK.sh (dead),
    generateInstallscript.sh, Generate_VolPaySDK_Lite.*, install.cmd/.sh, installJars.*, update-volbase.*, pomversionUpdater.sh, deployFile.sh, 28 Dockerfiles
```

Each rail: `<rail>-data-structure/`, `<rail>-functional-components/{<rail>-adapter, <rail>-business-functions}`, `<rail>-rest-services/`, `<rail>-artifacts-default/{rest,script,transaction,sync-processor[,stmt,simulator]}-artifacts` (pom-only bundles of ~160 cartridge deps per WAR), `studio-config/{data,feature,studio-settings.json}`.

### 3.2 Maven
- Root properties: `volante.build.version 7.2.0`, `volbase.version 4.3.1`, `buildutils 3.1.2`, `testutils 2.1.3`, Camel 4.4.2, Spring 6.2.17, ActiveMQ 6.1.6, Log4j 2.25.4, Netty 4.2.13, ZooKeeper 3.9.5, Java 17, `skipTest=true`, `copyartifacts.dir=${basedir}/bin`. No parent chain to Volbase; Volbase artifacts are plain dependencies.
- Root profiles: `core-data-structure[,1,2,3]`, `core-business-functions1..4`, `core-functional-components`, `core-rest-services1..3`, `core-tools`, `core-artifacts-default`, one per rail (`rtp fednow lynx chips swift fedwire sepact sepadd sic sepaip tips egach usach oms`), `MANDATE`.
- `volante-tasks` config identical in spirit to Volbase/SBG (`rebuild=true`, `projectFile=${basedir}\${project.artifactId}.vpj`, `targetVersion 17`, `home=${env.VOLANTE_HOME}`); leaf cartridge modules bind `build` at `compile`, `test` at `test`. `maven-resources-plugin` copies `target/*.jar` + `pom.xml` into `bin/` at install (this populates the SDK `bin/` tree). `volbase-code-generation:3.1.2` bound to install.
- `implementation/pom.xml`: rail profiles (`CORE, RTP, RTP5, TIPS, FEDNOW, VERAFIN, SWIFT, FEDWIRE, CHIPS, LYNX, SEPACT, SEPADD, SIC, SEPAIP, SEPAIP-2025, EGACH, USACH, MANDATE, OMS`) + DB profiles `mongo-mongo`, **`mongo-mongo-1`** (`wartype=mongo-mongo-1`, `config-folder=mm-configs`, modules `webapps-1`), `cassandra-mongo`; folder properties `webapps-1`, `rest-webapp-1`, `script-webapp-1`, `simulator-webapp-1`, `transaction-webapp-1`, `sync-processor-webapp-1`, `dashboardsync-webapp-1`, `stmt-webapp-1`; `TenantId=MASTER`; `append.war.version`. `volante-tasks` disabled here (assembly only). **SBG's root pom properties are a direct copy of this file** (same property names, `mongo-mongo-1`, `webapps-1`, `TenantId` switched to `SBG_ZA`).
- WAR poms (`webapps-1/rest-webapp-1/pom.xml` etc.): one profile per rail adding `<rail>-rest-artifacts:1.0`; resources copied from `volpay-configs-1/{db-configs/${config-folder}, core-configs/common-configs, core-configs/rest, <rail>-configs}`; `copy-dependencies` into `temp/.../WEB-INF/lib`; `maven-war-plugin` output `../../generated-wars/${wartype}` with ~140-entry `packagingExcludes`. `script-webapp-1` (`volpay-dbscripts-*.war`) bundles `volpay-scripts-1/{bank,core,endpoint,index-changelog}`. SBG's WAR poms are trimmed copies of these.

### 3.3 Build and SDK generation
- `build_volpay.sh` (CI): parallel `run_parallel()` with auto-retry; order: `core-data-structure` → `core-data-structure1/2/3` → `core-business-functions1..4` → `core-functional-components` + `core-rest-services1..3` → `core-tools`, `core-artifacts-default` → rails A (`rtp swift fedwire sic sepact sepadd`) → rails B (`fednow oms sepaip tips usach lynx chips MANDATE`). `egach` is missing here (present in `.bat`). `build_volpay.bat`/`build_nacha.sh` reference a stale profile name.
- Incremental "Golden Copy" build: `find-changed-files.sh` (git diff since `*-GoldenCopy/commitId.txt` + `additional-build-files.txt` forced cartridges) → `build-changed-modules.sh` (`mvn clean install -Dmaven.repo.local=<GoldenCopy .m2>`) → `update-commit-id.sh`.
- `core/tools/sdk-utilities/generate-bin-folder.sh` (`BinFolderGenerator`) builds the `bin/` tree of cartridge jars + poms.
- `generateInstallscript.sh` writes synthetic poms `installVolbaseJars.xml` (from `volbase-bin/framework`) and `installVolpayJars.xml` (from `bin/`) with one `maven-install-plugin:install-file` execution per jar/pom.
- **`install.sh`/`install.cmd` (what SBG runs once per SDK drop):** requires `VOLANTE_HOME`, refuses if Designer is running; `BuildUtilsSDK/install.sh`; install `volbase-bin/volbase-parent`, `volbase-bin` and `framework` poms; `mvn -f installVolbaseJars.xml install`; install `bin/pom.xml` + `bin/business-functions/pom.xml`; `mvn -f installVolpayJars.xml install`; `dependency:copy-dependencies` (excluding designer/javaCG/composer, volante-*); finally `SetVolPayDataStructures` rewrites Designer's `designer.cfg` `[Variables]` (`volbase.datastructures`, `volbase.sdk`, `volpay.sdk`). This is why SBG's `.m2` has 1346 engine + 3298 cartridge artifacts at version 1.0.
- `Generate_VolPaySDK_Lite.*`: cut-down SDK (subset of VolBase data structures + ~22 core trace structures with heavy sub-cartridges pruned) with its own poms and `set-volbase-macro_LiteSDK.sh`.
- `update-volbase.sh` → `update_volbase_release.py` rewrites `build.properties`, Dockerfile `volbase-master:latest-downstream-<n>` tags, `<volbase.version>` and literal engine dependency versions in all poms. `pomversionUpdater.sh` = sed-based version bump. `deployFile.sh` publishes `bin/**` to an internal Nexus.
- **SDK zip assembly = `volpay3x-release.Dockerfile`** (no maven-assembly): stage `build` (`FROM volbase-master:latest-downstream-65796`; builds VolPayUI, azcopy's `VolBaseSDK-master-<n>-<v>.zip`, renames `bin→volbase-bin`, `data-structure→VolBase`; runs `build_volpay.sh`, `generate-bin-folder.sh`, `rm -rf std-implementation`) → stage `sdk` (assembles `VolPaySDK-release-<ver>-<build>/`: every rail's `*-data-structure` + `studio-config` (oms/tips only studio-config), `core-tools`, `tools`, `bin`, `volbase-bin`, docs, license, `BuildUtilsSDK`, `volpayui`, optional `volpay-edge`, `VolBase` → `core/data-structure/VolBase`, the whole `implementation/`, install scripts, root pom (also into `bin/`), merged `build.properties`; runs `generateInstallscript.sh`; zips) → stage `war` (`mvn package -P CORE,RTP,RTP5,FEDNOW,SWIFT,SEPAIP,SEPAIP-2025,SEPACT,USACH,FEDWIRE,MANDATE,CHIPS,LYNX,OMS,SIC,SEPADD,mongo-mongo-1`, optional `patchJarInWarFiles`) → stage `co` (customer-onboarding image with `designer.cfg` + SDK). The SDK ships rail data-structure *sources* (cartridges cross-reference by relative path) but functional-components/rest-services only as jars in `bin/`.
- Azure release pipeline (`azure-pipelines-release-volpay3x.yml`): manual approval gate, self-hosted VM pool, Golden Copy `.m2` zip, SDK upload to blob `volpay3x-valuestream`, WAR generation, **Jakarta migration step** (javax→jakarta on WARs), 7 runtime images (rest, scripts, transaction, sync, stmt, simulator, dashboardsync) + Trivy/MS Defender scans, optional CO image and NFT images. 28 Dockerfiles map to value streams: develop/core/instant (CORE+SEPAIP)/batch (CORE,RTP,FEDNOW,SWIFT,SEPAIP,SEPACT,OMS,USACH,MANDATE,SEPADD)/rtgs/cbpr/utilities/release/master, `-GoldenCopy` and `-regression` variants, `sonarqube-instant`.

### 3.4 OMS rail (what SBG consumes directly)
Thin (148 files): no `oms-data-structure`, no `oms-rest-services`; reuses `core/data-structure`. Contents: `oms-adapter-impl` (oms-iso-adapter head.001, pain.001.001.08, pain.002.001.11, pain.008.001.10, pacs.002/003/004, camt.053/054, pgp-adapter-test), 6 business functions (`EPP-identify-outmsgtype`, `consolidate-errors`, `ct-to-ack-mapping-btch`, `endpoint-compliance-check`, `oms-bic-or-aba-enrichment`, `skip-warehouse`), `oms-artifacts-default/{rest,script,stmt,sync-processor,transaction}-artifacts`, `studio-config` (`{"acronym":"OMS","type":"RAIL","isStatementWarNeeded":true}`, 39 reference-data XMLs incl. a 32k-line `ProcessFlowDefinition.xml`, TaskMaster/TaskAssociation, Party/Service/Endpoint associations, PSA/PEA task configs, BulkProfile, RuleProfile, IncidenceMaster/Config, TransportContext, SchedulerDefinitions, LocaleMaster, RestResourcePermission…). `implementation/oms-impl/{oms-rest, oms-scripts(-1), oms-stmt, oms-sync-processor, oms-transaction}` are pom shims. SBG's script-webapp depends on `oms-script-artifacts` + `core-script-artifacts`.

OMS process flows (22, keyed `(PrcCd, MsgFctnCd, WorkflwCd)`): `ENDPOINT_OMS_INCOMING` (303 BFs), `PFD_OMS_ACK_ENDPOINT_OMS_OUTGOING` (313), `PFD_OMS_CCT_TRANSACTION_IN` (147), RESPONSE flows, BCT endpoint, CDD transaction/endpoint/response, batch CDD, `OMS_INCOMING_INPUTBATCH`, ACK transaction/endpoint, INSTRUCTIONOUT/INSTRUCTIONPROCESS, statement flows. Workflow codes: `ENDPOINT`, `TRANSACTION`, `RESPONSE`, `INSTRUCTIONOUT`, `INSTRUCTIONPROCESS`, `INPUTBATCH`.

### 3.5 Processing model (the state machine)
`ProcessFlowDefinition` rows hold ordered `<BizFctns>` each with `BizFctnRef`, `NtryStsCd` (entry guard), `StsCd`, `TakeSnpshot`, `IsCmmtPt` (commit point), `ExitStsCds` (status → next status map), and `ExitCfgtns/SwtchPrcFlow` chaining to the next flow. Universal exits: `COMPLETED`, `REPAIR`, `REJECTED`. The 147-step OMS CCT TRANSACTION flow reads: GENERATEIDENTIFIER → CLONETRACE → FIELDDUPLICATECHECK → BUILDCHAIN → DERIVEINITIALDIRECTION → VALIDATETIME → BRANCHIDENTIFICATION → IBANDECONSTRUCTION → SERVICELEVELVALIDATIONS → VALIDATEPAYMENT → PMTPREVALIDATIONSCHECK → BICENRICHMENT → CONSTRUCTDEBITCHAIN → DERIVE* (agents/debtor/sender) → FXCheckDebit → SANCTION → PREPAREACCTGNTRIES → FUNDSCONTROL/LIQUIDITYCONTROL/ACCOUNTPOSTING (seize, multi-debit, reversal on fraud) → ENDPOINTDERIVATION → BOOK/AGENT DERIVATION → FXCheckCredit → FRAUDCHECK → SKIPWAREHOUSING → WAREHOUSE → UPDATEVD → credit-side accounting → PEADERIVATION → duplicate accept/reject → PREPAREFORREPAIR → TIMEDEWAREHOUSE → INTERIM_CUTOFF_PASS → RESUMEAFTERCUTOFF → HANDLEPARSEVALIDATE → RESUMEFROMSTART. SBG's task-config cartridges (`*-task-config` in refdata-core-structures) and transaction-webapp BFs (skip-warehouse, multiple-cutoffs, hold-transaction, global-validations…) plug into exactly these hooks via PSA/PEA task configs and TaskAssociation rows.

The 359 core BFs group into: lifecycle/identity, validation (status-validation variants, prevalidations, service validations, time/backdated/rejection-window), enrichment/derivation (BIC, IBAN, branch, debit/credit party, PEA derivation, FX, UETR), routing/endpoint, external interfaces (sanction, fraud, funds/liquidity/limit/charges/fx, account lookup/posting, documents, mandate, high-dollar, dodd-frank, travel-rule), accounting entries (+ reversal/seize/fraud/sanction), matching (25+ `match-*`), warehousing/scheduling/timeouts/cutoffs, exception/repair/manual actions, rules/extensibility (business-rules-executor, callback-flows, interface skip/execute, glueback).

### 3.6 Tech stack, patterns, extension points
- Java 17, Spring Framework 6.2.17 (no Boot), Camel 4.4.2, Kafka 4.2.0, RabbitMQ (amqp-client + rabbitmq-jms 1.15.2), ActiveMQ 6.1.6, IBM MQ (NFT), MongoDB (`mm-configs`), Cassandra (`cm-configs`), RDBMS/CockroachDB, ZooKeeper 3.9.5 + Curator 5.9.0, Liquibase 4.19.0 (+ `liquibase-extender`), Log4j2 2.25.4, Netty 4.2.13, JasperReports 7.0.6, Gremlin 3.6.4, Velocity 1.7, JUnit 4.13.2, Tomcat 10/Jakarta at runtime (pipeline runs `jakartaee-migration` on WARs). Auth: SAML, Keycloak (`keycloakauth`), Azure AD SSO (`com.volante.azureadsso`), SiteMinder simulator, OAuth utils.
- Patterns: model-driven/low-code; data-driven FSM; pipes-and-filters BFs with entry/exit states; core + plugin rails with identical folder template; profile-based composition; artifacts-bundle indirection; compensating transactions (`*REV*`, `*SEIZE*`); trace/snapshot event sourcing; CQRS-flavoured read/write split (`read-data-structures`, `sync-flows`, `Reverse-Sync-flows`, sync-processor/dashboardsync WARs); multi-tenancy via `${TenantId}` templating (tenantId-updater-tool) and `TenantId=MASTER`; Golden-Copy incremental builds.
- Customer hooks (in priority order): reference-data overrides in project Liquibase changelogs (ProcessFlowDefinition/TaskMaster/TaskAssociation/IncidenceMaster/BusinessFunctionDefinition); callback flows (`callback-flows`, `call-back-to-skip-or-execute`, `invocation-point-config`); interface skip/execute; business rules (`RuleProfile`/`RuleMetaInfo`); Designer user-defined functions (`com.volantetech.services.functions.extension`); `core/sample-impl` recipes (Universal-Inheritance, controldata-extension, bank-routing-extension, adapter-impl, glueback-impl, id-generation, step-accounting, bulking-impl, encrypt_decrypt, keycloak-impl, SiteMinder-simulator, mongo-index, restructing-impl/micro-service-wars); listeners; migration-utils.
- `implementation/webapps-1` WARs: `rest` (`volpay-rest-*`), `script` (`volpay-dbscripts-*`), `transaction`, `sync-processor`, `simulator` (fakes every `interfaces/*` counterpart: sanction, fx, charges, account posting, document*), `stmt` (rails with `isStatementWarNeeded`: SWIFT, OMS, EGACH, USACH), `dashboardsync`. `volpay-configs-1/{core-configs/{common-configs,rest,transaction,sync-processor,scripts,simulator,ui-configs,liquibase}, db-configs/{mm,cm,rdbms}-configs, <rail>-configs}` and `volpay-scripts-1/{core,bank,endpoint,configs,task-changelog}` (4,760 files, `<Entity>-insertifnotpresent-{read|common}.xml` idempotent seeds). SBG's `config/` and `scripts/` folders mirror this layout (`common-configs`, `rest`, `sync`, `PersistenceModel-mm.json`).

### 3.7 Observations
- `egach` absent from `build_volpay.sh` and from the release `mvn package -P` list although its data-structure is shipped.
- `README.md` still says Designer 7.0.0/7.0.1 (stale); release-notes PDF content is from 3.3.2 and contains the "standalone rail vs SingleSDK" gap table (e.g. OMS 1.0.20 standalone vs 1.0.10 SingleSDK, missing PGP encrypt/decrypt/compress). SBG implements PGP/LZMA itself in transaction-webapp, consistent with that gap.
- `std-implementation/` is not in the SDK zip; hardcoded internal Nexus/Sonar IPs in `deployFile.sh` / `sonarqube-instant.Dockerfile`.

---

## 4. VolBase 4.3.1 – the framework (`E:\Projects\Volbase-SingleSDK-v4.3.1\VolBase`)

### 4.1 Versioning reality
- Every Maven artifact is `com.volantetech.services.engine:*:2.0.0` in source. "4.3.1" is the *release label* stamped into `.car` files at release time by `CartridgePropertyModifierHelper` (config `CartridgePropertyModifier.xml`: `setVersion` + `markReadOnly`, excluding `tools`). The published SDK jars carry 4.3.1, which is what SBG's `.m2` holds.
- `build.properties`: VolBase 4.3.1, Designer 7.2.0 (docker image `Studio_7_2_4-Designer7_2_0`), BuildUtils 3.1.2, TestUtils 2.1.3, JDK 17. Branch `hotfix/Arion/4.3.1`.

### 4.2 Layout
| Path | Contents |
|---|---|
| `volbase-parent/pom.xml` | 1206-line master POM: all `dependencyManagement`/`pluginManagement`, properties (Spring 6.2.17, Camel 4.4.2, Kafka 4.2.0, Mongo driver 5.2.1, ZooKeeper 3.9.5 + Curator 5.9.0, Liquibase 4.19.0, Log4j 2.25.4, Netty 4.2.13, CXF 4.1.3, BouncyCastle 1.84, ESAPI 2.7, Jackson 2.17.1, Micrometer 1.12 + Prometheus, `release 17`). Extra repo `shibboleth` for OpenSAML. |
| `pom.xml` (root, `volbase-framework`) | Child of `volbase-parent` (inverted parent/aggregator). Profiles: `default` (~70 modules), `sample`, `release-tool`, `merge-report`, `cartridge-updater`. |
| `framework/` (48 modules) | The framework proper, see 4.4. |
| `data-structure/` | 195 cartridges = canonical domain model + 58 Liquibase DDL changelogs (`db.framework.main.1.0.8…1.0.33`, each with a `.cockroachdb.` twin). One jar `data-structure`. This is `${volbase.datastructures}` in SBG's designer.cfg. |
| `sample-impl/` | Reference downstream implementation (blueprint SBG followed), see 4.6. |
| `deployment-manifest/volbase/` | Helm chart (deployment, hpa, ingress, job for migration, configmaps, secrets). |
| `tools/` | release-tools (`versionupdater`, `deploy`), cartridge-updater, catridge-read-only, designer-addins (Designer archetypes), sdk-installer (`install.cmd/sh`), sdk-tools (dependency-generator/installer, framework-pom-generate), NFTUtility, offset-intializer (Kafka offsets). |
| `devops/` | Azure DevOps pipelines (develop/master/release/veracode), k8s manifests, NFT harness, Trivy/Defender config. |
| `cartridge-utils/` | `CartridgeVersionUpdater` rewrites `<version>` inside `.car` XML. |
| `build-windows.ps1` | Builds one module per JVM because the Designer runtime licence loads `LicenseParser.dll` via JNI and cannot be loaded in a second classloader ("INVALID VOLANTE RUNTIME LICENSE"). A plain `mvn install` at root fails on Windows past the first module. |
| `master/develop/release.Dockerfile` | Real CI: base `testutils:v2.1.3` image, install BuildUtils/TestUtils, azcopy UI zip, version stamping, `mvn install`, merge-report, assemble `VolBaseSDK-<build>/` (data-structure, bin, sample-impl, release-tools, designer-addins). |

### 4.3 The Designer Maven plugin (`volante-tasks`)
Declared in `volbase-parent` with `rebuild=false`, `projectFile=${basedir}\${project.artifactId}.vpj`, `targetVersion=17`, `excludeVolanteDependencies=true`, `generatePomDependenciesFile=true`, `home=${env.VOLANTE_HOME}`, `useMavenJarName=true`; executions bound to `none` and re-bound per module to `compile`/`test`. Output: one jar per cartridge `cartridge-<name>-<version>.jar` under groupId `com.volantetech.volante.cartridge`, plus `<name>-build-info.xml`, plus JUnit XML from Designer test suites listed in the `.vpj`. SBG copied this configuration nearly verbatim into its root pom (with `rebuild=true`).

Other plugins: `volbase-code-generation:3.1.2` (BuildUtils), aggressive `maven-clean-plugin` (deletes generated `java/`, `bin/`, `VPH_messages`, `generated-war`), `maven-resources-plugin` copying jars+pom to `bin/`, `maven-war-plugin 3.5.1`, `exec-maven-plugin`, `zookeeper-maven-plugin`, `jetty-maven-plugin`. No liquibase-maven-plugin (Liquibase runs at app startup), no assembly/docker plugins.

`.mvn/jvm.config` adds `--add-opens java.base/{java.lang,java.lang.reflect,java.util,java.io}=ALL-UNNAMED`.

### 4.4 Framework modules by concern
- **Bootstrap/lifecycle:** `instance-manager` (`ApplicationManager` ServletContextListener scans classpath for `ApplicationListener.yaml`, orders listeners by `startUpOrder` desc), `warm-up-listener`, `identity-warm-up-listener`, `plugin-registry` (`@Plugin(pluginName)` + ClassGraph scan of `com.volantetech`, first-wins), `framework-utils`, `framework-common-utils`.
- **Persistence:** `persistence-manager/{persistence-utils, rdbms-, mongodb-, cassandra-persistence-manager}` (custom `IPersistenceManager`/`IPersistenceModel`, c3p0/Hikari JDBC, no JPA/Hibernate, CockroachDB supported), `liquibase-manager` (custom change types `VolanteInsert`, `VolanteUpdate`, `VolanteInsertOrUpdate`, `VolanteAppend`, `VolantePreCondition`, `VolanteMigrator`, `AutoScriptExecutor`), `query-parser` (ANTLR VQL to DB queries), `cache-manager` (home-grown LRU/expiring caches, write-behind, coherence via Kafka+ZooKeeper; no Redis/Hazelcast), `rollback`, `multi-tenant` (tenant-master/tenant-space, datasource per tenant).
- **Advanced persistence matrix** (`advanced-utils/advanced-persistence-manager`): `kafka-persistence-manager`, `sql-kafka(-split)`, `sql-split`, `cassandra-kafka(-split)`, `cassandra-split`, `mongo-kafka(-split)`, `mongo-split`, `kafka-split`: store x transport x sharding cross product. SBG's simulator pom pulls most of these.
- **Processing/workflow:** `controller` = the process-flow engine (`ProcessFlowExecutor`, `TaskExecutor`, `SwitchHandler`, `BusinessFunctionExecutorObject`, callbacks `ITaskCallBack`/`IBusinessFunctionCallBack`), `processor` (24 cartridges: instr-receive, vb-pre-validator, dispatch-identifier, duplicate-check, debulk, instr-processor with Sync/Async flows, adapter, instruction-out-processor; deadletter, cancel, resubmit, override-v4, bulk-scheduler), `bulking-processor`, `statement-processor`, `interface-manager`, `approval`, `process-approval`, `rule-manager`, `predicate-evaluator`, `incident-manager`, `reprocess-service/{jms,rabbitmq,asb}`.
- **Transport:** `transport-manager-v2` (dynamic Camel contexts from DB: `DynamicCamelContextFactory`, `RouteRegistry`/`ZookeeperRouteRegistry`, `BeanDefinitionManager`, `SchedulerRouteHandler`, dead-letter/redelivery, idempotent registries, `ConcurrencyManager`, `TenantLogHandler`, `SOAPServlet`, ZooKeeper service discovery), `transport-rest-services`.
- **REST:** `rest` (Volante REST servlet runtime, filter chain: auth, request/response hooks, localization, webform config), `rest-services` (~45 service cartridges: menus, policies, dashboards, data-masking, export, business rules, locale). No Spring MVC/Jersey.
- **Security:** `authentication`, `authentication-legacy` (JWS/JWT via auth0 + nimbus), `authentication-external-saml` (OpenSAML), `authentication-service`, `security-adapter` (Strategy: AES/ECDSA/SHA/PGP, `IHSMInvoker`), `security-utils`, `data-masking`. No Keycloak/Spring Security in Volbase itself (SBG adds keycloak-core + `cartridge-keycloak-authentication-services` from VolPay).
- **Observability:** `health-check`, `health-check-v2`, `kubernetes-health-check`, `application-metrics` (Micrometer/Prometheus), `monitoring` (New Relic NRQL handlers), `logging` (Log4j2 JSON `transformToJsonLogger`), `system-audit-manager`, `dashboard-ds-sync`.
- **Other:** `identity-manager/{core,rdbms,cassandra}` (ID generation), `locale-manager`, `avro-converter`, `azure-service-bus-utils`, `zookeeper-utils`, `kafka-utils`, `bulking-utils`.

Stats: 539 `.car`, 44 `.vpj`, 889 hand-written Java classes (mostly verb-named flow-invokable helpers like `GetTenantSpace`, `PutValueCache`), ~1200 message flows, ~360 internal messages, 187 persistence managers, 409 formula functions, 96 DynamicForms definitions.

### 4.5 Design patterns / principles
Template Method (`Abstract*` bases), Strategy (`ISecurityAdapter`, `IMaskingStrategy`, export hooks), Factory (`HealthCheckerFactory`, `DynamicCamelContextFactory`), Registry/Service Locator (many `*Registry` singletons), Observer/Callback (ordered `IApplicationListener`, task callbacks), Chain of Responsibility (Camel routes, REST filter chain), Adapter, Singleton (near universal). DI is Spring XML only (`/META-INF/*Context.xml`, `data-sources.xml`) plus DB-driven bean definitions; no annotations/component scan. Event-driven, Kafka-first. Config as JSON in `WEB-INF/classes` (`Settings.json`, `PersistenceModel.json`, `TopicResourceMapper.json`). Conventions: `I*` interfaces, `Volante*`/`VolBase*` impls, `v2/v4` suffix evolution, wildcard `com.tplus.transform.runtime.*` imports (Designer codegen).

### 4.6 `sample-impl`: the template SBG followed
- `sample-impl/pom.xml` sets `<volbase.version>${project.parent.version}</volbase.version>`; downstream projects point it at the released VolBase, exactly SBG's `volbase.version=4.3.1`.
- One Maven profile per WAR (`processor-app`, `sync-app`, `scheduler-app`, `simulator-app`, `rest-app`, `common-scripts-app`, `dashboard-ds-sync-app`, `hsm-module-crypto-handler`), each listing only its modules and setting `volpay.targetDir`, `war.name`. SBG simplified this to a single `mongo-mongo-1` profile with a fixed module list.
- WAR poms: `maven-war-plugin` to `generated-war/`, ~100-line `packagingExcludes`, `copy-dependencies` into `WEB-INF/lib`. This is the origin of SBG's WAR poms.
- `web.xml` contract: `contextConfigLocation=/META-INF/*Context.xml`, `TransportScope`, `ApplicationName`, `WORK_DIR`, `ContextLoaderListener` + `ApplicationManager`, `CamelHttpTransportServlet`, `VolanteRestServlet`, health/ready/metrics servlets. Identical shape to SBG's transaction `web.xml`.
- Liquibase: `common-scripts-app/META-INF/Liquibase_Context.xml` with one `VolanteCommonLiquibase` bean per changelog per datasource (`-read`/write), the model for SBG's `LiquibaseContext.xml` (73 beans). Changelogs in three tiers: framework DDL (`data-structure/src/main/resources`), cartridge-local `*-scripts/` folders (DDL + reference-data seeds such as `BusinessFunctionDefinition-*.xml`, `TaskMaster-*.xml`, `TaskAssociation-*.xml`, `MsgFunctionConfig-*.xml`), implementation DML (`common-scripts-migration`, 516 files in feature folders like `onhold-scripts`, `repair-scripts`, `warmup-scripts`). A "patch" = new `<feature>-scripts` folder + bean in the Liquibase context; no Liquibase `context=` attributes are used.
- `test-volpay-messages/` (~90 cartridges) = sample domain model; `processor-sample/{transaction-processor, processor-intermediaries, sample-adapter, hsm-module-crypto-handler}`; `rest-sample/{rest-service-sample, rest-abac-approval, external-auth, service-authentication, siteminder-simulator, soap-sample}`.
- Designer archetypes (`tools/designer-addins/config/volbase`): VolBase Cart, Adapter, Rest Service(+Test), Reference Data (`.car` + changelog + cockroachdb changelog + `_TenantSpace.xml` + catalog files). This is the canonical "how to add an entity" recipe.

### 4.7 Docs
`README.md` (profiles cheat-sheet), `CONTRIBUTING.md` (stale GitLab URL; repo now on Azure DevOps), `docs/Packaging-Guide.adoc` (build order is manual; `installSDK` modifies tracked `dependency.car`/`framework/pom.xml`), `docs/Liquibase.adoc` (binary-embedded). Siblings `Volbase-BuildUtils` (3.1.2: `volbase-code-generation` plugin, `vol-sdkinstaller-maven-plugin`, `volbase-utility-addin`) and `Volbase-TestUtils` (2.1.3: `test-utility-module`, `embedded-mongodb`) must be installed first.

---

## 5. VolPayUI 3x (`E:\Projects\VolPayUI3x`)

- Angular **11.2** (`vol-framework-ui`), ViewEngine (`enableIvy:false`, `postinstall: ngcc`) because of the proprietary local lib `dynamic-forms-2.2.0.tgz` (internal version 2.11.1: `DynamicFormsModule` + `DynamicGridModule`, formula-function engine, custom field components). UI kit: PrimeNG 9 + ngx-bootstrap + Bootstrap 4 + jQuery, d3 3.x/nvd3 charts, ngx-translate, @azure/msal-angular 1.x, angular-oauth2-oidc, ng-idle, xlsx/file-saver. No NgRx, no Material, no Keycloak, no Cypress; Karma/Jasmine scaffolds only, Protractor stub, TSLint.
- Built into a **Java WAR layout**: `src/WEB-INF/web.xml` registers servlets `UIConfigProvider` (`/rest/config.json` from `ui_config.yaml`), `ThemeDependencyServlet` (`/rest/theme.json`), `FetchEnvironmentVariables` (`/rest/fetchvars`), plus `ResponseHeaderSetter` (CSP/HSTS) and a CORS filter exposing headers `TENANTID, languagecode, External-Auth, ActnId, RsrcId, totalCount, masked`.
- Architecture: single eager `AppModule` (~130 components, `NO_ERRORS_SCHEMA`), hash routing, **fully metadata-driven**: `/app/:name` renders `CrudComponent` (list/search), `/app/:name/:key/:method` renders `WebformComponent` (dynamic form). Screens come from backend `/resource/{RsrcId}` descriptors (operations `LIST, COUNT, CREATE, UPDATE, FETCH_NEXT, MANUAL_OPERATION`), `/menu`, `/localize`, `/dynamicwebformshelper`, `/permission/*`, `/uiconcurrency/*` (record locking), `/dashboardconfig/v3`, `/search/*` (rule builder). State = `BehaviorSubject` services + `sessionStorage`; one HTTP interceptor adds `Authorization: Bearer:<token>`, tenant header, locale headers, loader, 401 refresh retry.
- Auth modes: Internal (`/user/gettoken` JWT), SSO (azure via MSAL, oidc, saml via `/sso/saml/auth`), External (SiteMinder cookies).
- **SBG customisation model:** no fork of the Angular source. SBG ships the prebuilt WAR twice (`volpayui`, `volpayzaui`, byte-identical) and changes only `WEB-INF/classes/ui_config.yaml` (`tenantid: SBG_ZA`, `ssoLogin: true`, `uiConcurrency: true`) and `assets/config/sso-config.json` (single Azure AD entry pointing at `prd-sbg-volpay.volantetech.com/volpayzaui`). Everything else (menus, screens, labels, entitlements) is data seeded by SBG's Liquibase scripts (`restresource`, `menuresourceassociation`, `metaviewinfo`, `searchmetainfo`, `localemaster` changelogs). `payment-initiation-ui` is a separate SBG-built Java/Camel/IBM-MQ pain.001 console, not VolPayUI.
- CI: `develop/master/release.Dockerfile` (`angularnode:11`, `ng build --prod --aot`, `VolBaseUI-<build>.zip`), Azure pipelines; the dev pipeline runs OWASP dependency-check + Sonar.

---

## 6. Volante Designer 7.2.0 (`E:\Projects\Designer\VolanteDesigner`)

Git: Azure DevOps `Designer/_git/VolanteDesigner`, at `Release7.2.0-GA`, 6,797 commits. 12,731 Java files. Installed copy: `D:\Volante Designer 7` (= `VOLANTE_HOME`).

### 6.1 Layout
| Dir | Contents |
|---|---|
| `src/designer/composer/core` (1,124 java) | `composer.jar`: design model (`com.tplus.transform.design.*`: `Cartridge`, `InternalMessage`, `ExternalMessage`, `MessageMapping`, `ValidationRule`, `DataField`, `FieldDictionary`), Swing UI (`design.ui` 678), codegen (`design.codegen` 340), serialization (`design.serial` 325: xml/xsd/dtd/html/schematron), formula (`design.formula` 201), inspector, lineinfo, vcs/git. |
| `src/designer/composer/addin` (~48 addins, 2,489 java) | `javacg` (Java emitters incl. EJB/OSGi/WAR packaging), `conductor` (Message Flow, 207), `rest` (REST design + RAML/OpenAPI import, 215), `inspector`, `maven` (`com.volante.designer.mavenaddin`), `GitIntegration`, `versioncontrolsystem`, `DynamicForms/DynamicGrids/WebForms Designer`, `simulator`, `report`, `installer`, `xsdaddin`, `restservice`. |
| `src/designer/composer/plugin` (~95 format plugins, 1,207 java) | External message formats: ISO 20022, SWIFT MT/MX, ISO 8583, FIX/FIXML/FAST, NACHA, Fedwire(+ISO), FedNow, CHIPS(+ISO), SEPA, TARGET2, CBPR+, US TCH RTP, Lynx, SIC/EuroSIC, BACS, CHAPS, EDI, IDOC, COBOL Copybook, Excel, JSON, XML, POJO, Document, Universal, plus ~50 regional ISO variants. Each registers via `META-INF/designer.xml` `<Format>` with `ExternalFormat` class + `MessageCatalog`. |
| `src/designer/designer` (324) | `designer.jar`: app shell (`DesignerMain`, `ConsoleDesigner` headless), diff/merge, `.car`/`.vpj` serialization. |
| `src/designer/utils/*` (~1,500) | `general`/`advutils`/`miscutils`/`ui` (Swing widgets, `com.tplus.transform.swing` 469), **`tasks` (`com.volante.util.tasks`: Ant tasks, Maven mojos, Gradle plugin → `volante-tasks.jar`)**, `generator` (OSGi/build descriptors), `LG` (License Generator), `LLM` (Local License Manager – licensing, not AI), `sqlutils`, `classreader`, `volante-test`. |
| `src/runtime/core` (760) + ~70 runtime modules | `transformrt.jar` = `com.tplus.transform.runtime` (3,014 classes): `DataObject`, `AbstractInternalMessage`, `AbstractExternalMessage`, `AbstractMessageMapping`, `AbstractInputParser`, `AbstractValidationRules`, `AbstractMessageFlow`, `AbstractPersistenceManager`, `BusinessTransaction`, `LookupContextFactory`/`MessageFlow`/`TransformContext` (what SBG's `PingAuthFilter` calls), `cache`, `database`, `formula`, `json`, `xml`, `jms`, `metrics`; `runtime/cp` = Command Processor (`runtime.external`, 307); `runtime/rest` → `volante-restrt.jar` (`VolanteRestServlet`); swift, hadoop, grpc, protobuf, mongodb, cassandra, osgi, webforms, dynamicforms, services-manager, resource-manager, client. |
| `src/integration` | `camel-volante`/`camel3-volante`/`camel4-volante` (camel4 built with JDK17), `mule(4)-volante`, `spring-volante`, IBM ACE/IIB node, BIRT, Eclipse plugins (Tycho). |
| `src/dynamicFormsAngular` | Angular 7 `volante-dynamic-forms` component/preview/grid-preview → `runtime/angular/dynamic-forms-2.1.0.tgz` (ancestor of VolPayUI's `dynamic-forms-2.2.0.tgz`). |
| `src/cppruntime`, `src/runtime.net`, `redist/` | C++ and .NET runtimes + redistributable headers. |
| `lib/`, `plugin/`, `lib/runtime/`, `lib/ext/` | Built jars (gitignored): `designer.jar`, `composer.jar`, `volante-tasks.jar`, ~80 `*rt.jar`, ~95 `*-plugin.jar`, `lib/compiler/jre8|11|17/ecj` (Eclipse compiler used for in-process javac). |
| `config/` | `designer.cfg` template, `formula/*.xml` (function library), `maven/{pom.xml,pom-dependencies.xml,templates/install,deploy}` Velocity templates, `ant/`, `gradle/`, `filetemplates/{Java,CPP}`, `swift/` dictionaries, `conductor/` flow templates, `inspector/`, `dynamicforms/formula-whitelist.xml`, per-format dirs. |
| `bin/` | `designer.exe/.bat/.sh`, `codegen.bat` (`ConsoleDesigner CartridgeCodeGenerator`), `setenv`, `designer.properties` (license URL `server1.volantetech.com:8443/lg`), `designer.vmoptions`, DLLs (`transformrt.dll`, **`LicenseParser.dll`** – the JNI lib behind Volbase's one-JVM-per-module build rule). |
| `jre/` | Bundled Oracle JRE 1.8.0_172. `tools/` ~50 CLIs (codegen, cartridgepublisher, standalone-test, mapper, diffMessages, schema2car, json2car, mergejars, reportgen, message-pack downloader/installer). `nexus/` publishing harness. `SampleProjects/` ~350 vpj. `docs/` API + HTML help. `license/` 40+ OSS texts. |

### 6.2 Build
- **Ant is the build** (`src/build.xml` → subant into utils, runtime, designer, integration; ~150 per-module `build.xml`s; variants `build_vpf*.xml`/`build_designer_air/lite.xml` rebrand the same code as VolPay Foundation/Air/Lite via resource swaps). Everything compiles at **Java 1.8 source/target** (camel4 module on JDK17). Maven is used only for publishing (`nexus/pom.xml`, 2,101 lines, `com.volantetech.volante:cloud-designer` with ~145 `deploy-file` executions + `designer-base` assembly bundling config/lib/runtime/plugin/SampleProjects) and for the `volante-tasks` plugin itself (`src/designer/utils/tasks/META-INF/maven/com.volantetech.volante/volante-tasks/pom.xml`, packaging `maven-plugin`, 7.2.0). Gradle exists only as the customer-facing `gradle-tasks` plugin.
- Jenkins (`Jenkinsfile`, `Jenkinsfile_Cloud`): ant clean → `ant -f src/build.xml -Dbuild.number` → `tools/maven/mvninstall.bat lib/volante-tasks.jar` → camel4 (JDK17) → Angular → smoke test → Veracode zip → regression → Nexus deploy (`mvn -f nexus/pom.xml -Dvolante.version=7.2.0 -Dnexus.volante.url=http://200.200.200.172:8081/nexus/.../designer_releases/ deploy`) → install4j installers.
- Artifacts: groupId `com.volantetech.volante`, version 7.2.0: `designer`, `designer-base`, `volante-core`, `volante-utils`, `volante-tasks`, `volante-client`, `volante-test`, `volante-swift(-resources)`, `volante-xml`, `volante-rest`, `volante-mongodb`, `volante-cassandra`, `volante-grpc`, `volante-protobuf`, `volante-avro`, `volante-metrics`, `volante-webforms`, `volante-dynamicforms`, `volante-dynamicgrids`, `volante-services-manager`, `volante-command-processor`, `volante-resource-manager`, `camel(3|4)-volante`, `mule-volante`, `spring-volante`, plus 145 dependency poms (`swift-plugin`, `iso20022-plugin`, `composer`, `conductor`, `simulator`, `transformer`…). Exactly the set present in SBG's `.m2/com/volantetech/volante`.

### 6.3 Core concepts (the "DSL")
- `.car` = cartridge, `.vpj` = project (constants in `DuplicateCheckerUtil`: `PROJECT_EXTENSION="vpj"`, `CARTRIDGE_EXTENSION="car"`); `.vpw`/`.vcw` workspaces. A cartridge is one XML document holding `internalmessage`, `externalmessage`, `messagemapping`, `validationrules`, `messageflow`, `persistencemanager`, `FormulaFunctionDef`, `references` (relative-path + `${macro}` + absolute path) and per-element codegen property maps (`Java Package Name`, `Jar Name`, `Manifest Entries`).
- Design elements registered declaratively in `composer/core/META-INF/designer.xml` (`<DesignElement>` with `Class`, `UIClass`, per-type `SerialClass`): `VolanteProject`, `Cartridge`, `InternalMessage` (alias `BusinessTransaction`), `ExternalMessageFormat`, `InputFormat`/`OutputFormat`, `MessageMapping` (+ Input/Output/External/ManyToMany mapping rules), `ValidationRules`, `MessageValidation`, `MessageProcessing`, `PersistenceManager`, `DatabaseTable`, `QueryManager`, `DatabaseMapping`, `NormalizedFormat`/`NormalizedObject*`, `ProcessingRules`, `Triggers`, `JavaFunctionDef`, `FormulaFunctionDef`, `DBQuery/DBUpdateFunctionDef`, `HTMLElement`, `FolderElement`, `Resources`. Pipeline: External Message → Input Mapping → Internal Message/Normalized Object → Output Mapping → External Message, with validations and processing rules at each stage; Message Flow (Conductor addin) orchestrates flows with Start/Stop/Invoke/If/Loop/Create/Parse/Throw/Exception nodes (the `flowelement`/`link` XML seen in SBG `.car` files).
- Formula language: real expression DSL, grammar `formula.jlr`/`formula.ycc` compiled with **Sandstone VisualParse++** (not ANTLR/JavaCC); function library data-driven from `config/formula/*.xml` mapping to static runtime methods.
- Code generation is programmatic (`CartridgeCodeGenerator`, `CGManager`, `BuildManager` incremental, `GeneratedClass`/`GeneratedMethod`, `NamespaceManager`; Java emitters in the `javacg` addin), compiled in-process with the Eclipse compiler; Velocity only for build-file scaffolding (`config/maven/pom.xml` template etc.). Constants: `VOLANTE_GROUP_ID="com.volantetech.volante"`, `VOLANTE_CARTRIDGE_GROUP_ID="com.volantetech.volante.cartridge"`.
- Format metadata (SWIFT dictionaries, ISO 20022 repository) under `config/<format>/`; extra formats delivered as downloadable "Message Packs".

### 6.4 How Volbase/VolPay/SBG consume Designer
1. **`volante-tasks` Maven plugin** (mojos `build`=`VolanteCGMojo`, `test`, `standalone-test`, `report`, `coverage-report`, `mig-report`, `update-cartridge`). `VolanteCGMojo` parameters: `home`, `projectFile`/`cartridgeFile`, `platform` (default `Java/EJB`), `rebuild`, `noDebug`, `targetDir`, `targetVersion`, `cartridgeVersion`, `groupId`, `includeDependencies`, `excludeVolanteDependencies`, `excludeThirdPartyDependencies`, `useMavenJarName`, `generatePomDependenciesFile`, `mavenHome`, `clearMavenDependencyCache`, `snapshotBuild`. It attaches generated cartridge jars to the reactor as real artifacts (`CartridgeArtifactConstructor`, groupId `com.volantetech.volante.cartridge`) and writes `pom-dependencies.xml`. Older name `volante-maven-plugin` (4.1.0) still present in `utils/generator/test`. Ant (`CGTask`, `TestTask`) and Gradle (`gradle-tasks` plugin, `volanteCG` task) equivalents exist.
2. **Runtime jars from Nexus** (`volante-core` = `transformrt.jar`, `volante-rest`, `volante-xml`…) and `designer-base` (config+lib bundle so CI can run codegen without a full install). Local dev install via `tools/maven/mvninstall.bat` (`POMUtil` extracts the embedded pom) / `installVolanteMaven.bat`.
3. **Container adapters**: `camel4-volante`, `spring-volante`, `VolanteRestServlet`, Command Processor; generated packaging controlled by `codegen.java.options` (`isWAR`, `isOSGI`, `isEJB`…).

### 6.5 Patterns, stack, licensing
- Swing (JGoodies Looks, MigLayout, Darcula, GlazedLists, SwingX, JavaHelp) – **not Eclipse RCP**. Plugin/extension registry via `designer.xml` descriptors and reflective loading; MVC (`Class`/`UIClass`); Strategy for serializers; Abstract Factory/Template Method hierarchies in design and runtime; Builder/Visitor in codegen; ServiceLoader in runtime; perspectives (`CartridgePerspective`, `VolanteProjectPerspective`).
- Libraries: Xerces, Saxon 9, Log4j2/SLF4J 2.0.16, JAXB, BeanShell + JRuby scripting, Velocity, JEXL, xsom/dtdparser, cb2xml, POI/jxl, OpenPDF/Flying Saucer/asciidoctorj, RAML parser + swagger-codegen, protobuf 3.25.5, classgraph, HSQLDB (simulator), JaCoCo, JGit.
- Versioning: `src/designer/resources/messages/designer.properties` (`designer.version=7.2.0`, product id 10); manifests `Implementation-Version 7.2.0`; cartridge XML schema version independent (`version="6.6.0"` in SBG cars, `"7.2.0"` in Volbase cars). Rebrands: VolPay Foundation/Air/Lite.
- Licensing: `com.tplus.license.*` (`lib/lic.jar`, `bin/LicenseParser.dll`, server `server1.volantetech.com:8443/lg/lg/v2`, files `designer.key`/`designer.perm` gitignored; runtime licence generated by `tools/register/registerRuntimeLicense` → `volante-runtime-license-1.0.jar` + `runtime.key/.perm`, consumed through `VOLANTE_RUNTIME_HOME`); License Generator (LG) and Local License Manager (LLM, Spring Boot 2.1.7 + Angular) apps; plugins can be licence-gated (`<License name=…>`).
- Observations: fresh clone cannot run until `ant -f src/build.xml` builds `lib/`; `config/maven/pom.xml` and Gradle/Ant samples still hard-code `volante-tasks:7.0.0`; internal hosts committed in plain text (Nexus `200.200.200.172`, build share `10.0.4.50`).

---

## 7. How it all fits together (synthesis)

### 7.1 Dependency and version chain as seen from SBG

| Layer | Source of truth | Version SBG builds against | Where it lands on this machine |
|---|---|---|---|
| Designer | `E:\Projects\Designer\VolanteDesigner` (Ant build, Java 8 source) | 7.2.0 (`volante.build.version`, `volante.version=[7.2.0]`) | Installed `D:\Volante Designer 7` (`VOLANTE_HOME`); runtime jars `.m2/com/volantetech/volante/*:7.2.0`; `volante-tasks` plugin |
| VolBase | `E:\Projects\Volbase-SingleSDK-v4.3.1\VolBase` (source version 2.0.0, released as 4.3.1) | 4.3.1 (`volbase.version`) | `.m2/com/volantetech/services/engine/*:4.3.1` (71) + `volante/cartridge/*:4.3.1` (318), via `VolPaySDK/volbase-bin` |
| VolPay | `E:\Projects\Volpay3x-SingleSDK-v3.5.2.1\VolPay3x` (all modules 1.0, product 3.5.2.1) | 1.0 (`volpay.version`) | `.m2/.../engine/*:1.0` (1,346) + `volante/cartridge/*:1.0` (3,298), via `F:\Projects\VolPaySDK\install.cmd` |
| VolPayUI | `E:\Projects\VolPayUI3x` (Angular 11) | UI build 63928 | Prebuilt WAR copied into `sbg-impl/volpayui`, `volpayzaui`, `volpay-edge` |
| SBG | `F:\SBG\sd-bk\sbg-impl` | 1.0.0 | `generated-wars/mongo-mongo-1/*.war` → `prepare-deployment/` → ECR images → EKS via Helm |

Every layer uses the same three mechanisms, so once you understand them in one place you understand them everywhere:
1. **Cartridges** (`.car` XML) compiled by `volante-tasks` from a `.vpj` into `cartridge-<name>-<ver>.jar` (groupId `com.volantetech.volante.cartridge`). Cross-cartridge references are relative paths or `${volpay.sdk}`/`${volbase.datastructures}` macros from `designer.cfg`.
2. **Profile-driven Maven reactors** with pom-only "artifacts" bundles per WAR scope (`*-{rest,script,transaction,sync-processor,stmt}-artifacts`) and `maven-war-plugin` `packagingExcludes` as the conflict-resolution mechanism.
3. **Reference data as code**: Liquibase changelogs with `VolanteInsert`/`VolanteUpdate` custom changes seed `ProcessFlowDefinition`, `TaskMaster`, `TaskAssociation`, `PSA/PEA TaskConfig`, `TransportContext`, `RestResource`, `Menu`, `LocaleMaster`… The runtime (Volbase `controller` + `transport-manager-v2` + `rest`) reads this data at startup, so most "features" are data + a business-function cartridge, not Java.

### 7.2 What SBG cherry-picked from where
- **Root pom properties and profile `mongo-mongo-1`, `webapps-1` names, `TenantId`** → from `VolPaySDK/implementation/pom.xml`.
- **WAR pom structure** (`volante-tasks` into `WEB-INF/lib`, `maven-war-plugin` to `generated-wars/${wartype}`, `packagingExcludes`, `copy-resources` from `config/*`, `copy-rename` of `PersistenceModel-mm.json`) → from `implementation/webapps-1/*-webapp-1/pom.xml` and Volbase `sample-impl` WAR poms.
- **`web.xml` servlet/listener set** (`ApplicationManager`, `VolanteRestServlet`, `CamelHttpTransportServlet`, health/ready/metrics servlets, `TransportScope`) → Volbase `sample-impl/processor-sample/*-app/web.xml`.
- **`LiquibaseContext.xml` bean-per-changelog pattern** → Volbase `common-scripts-app/META-INF/Liquibase_Context.xml`; the scripts folder taxonomy (`application-setup`, `message-functions`, `reference-data`, `rest`, `entitlements-policy`) → VolPay `volpay-scripts` + `docs/volpay-local-setup-guide/DB Scripts.txt` template.
- **`config/` contents** (`common-configs`, `rest`, `sync`, `PersistenceModels`, `file-upload` catalog inherits) → `implementation/volpay-configs/{core-configs,db-configs/mm-configs,oms-configs}`.
- **Task-config cartridges** (`*-task-config` in `refdata-core-structures`) and business-function cartridges in `transaction-webapp` → modelled on VolPay `business-functions/<bf>/` (each ships `business-function-definition-*.xml`, `task-master-*.xml`, `task-association-*.xml`, `incidence-master-*.xml`) and plug into the OMS `ProcessFlowDefinition` via PSA/PEA task configs.
- **`patch-product-impl`** → `.car` sources of product cartridges taken from the *old* 3.1.x SDK/VolPay repo (OMS ships binary-only in the new SDK), modified and rebuilt with parent `volpay-core:1.0`; the resulting jar overrides the SDK jar in the WAR. This is the riskiest part of the migration because those sources are 3.1.10/3.1.11 vintage.
- **UI** → prebuilt `volpayui` from the SDK with only `ui_config.yaml` + `sso-config.json` changed; screens/menus defined by SBG Liquibase data.
- **Deployment** → SBG-authored (Docker base images, Helm charts, Azure DevOps pipelines, AWS ECR/EKS); Volbase/VolPay only provide k8s manifests and Dockerfiles as examples.

### 7.3 Design principles worth carrying into new SBG work
- Prefer a **new cartridge + Liquibase task association** over touching product cartridges; use `callback-flows` / `interface-to-skip-or-execute` / `RuleProfile` hooks before forking a BF.
- Keep hand-written Java to formula functions, Camel processors, servlet filters and listeners (what SBG already does); business flow logic belongs in Message Flows so Designer tooling (inspector, tests, coverage, MIG reports) still applies.
- Every new reference-data entity needs the Designer "Reference Data" archetype set: `.car` + changelog + `_TenantSpace.xml` + catalog + REST resource/menu/locale rows (see `refdata-core-structures/src/main/resources/*.changelog.xml`).
- Build order and environment (profile flag, `VOLANTE_HOME`, `designer.cfg` macros, transaction-before-script) are non-negotiable; the Volbase Windows licence/JNI constraint means large multi-module builds may need one JVM per module.
- Follow the SBG code-quality checklist (`docs/code-quality-checklist`), notably: JWT on every REST API, no hard-coded environment values, backward-compatible versioned Liquibase scripts, Camel dead-letter handling, Designer naming conventions.

### 7.4 Open items to resolve
1. Rotate and externalise the committed AWS keys, Azure client secret, PGP private keys and keystores (Vault adapter already exists in the base image).
2. Align the Docker base image (JDK 11) with the Java 17 build; move `utility-webapp` (Spring Boot 2.1/Camel 2.17) onto the shared stack or isolate it explicitly.
3. Re-validate `patch-product-impl` cartridges against 3.5.2.1 sources (VolPay3x repo `core/functional-components/business-functions/<bf>`), then either drop them or rebuild under `patch-compatible/`.
4. Decide how to pin the SDK drop (all artifacts are `1.0`): a per-project local repo or a version-rewrite step, instead of renaming `.m2` folders.
5. Pull the missing docs from the old SDK `docs/` folder (Deployment Guide, Transport Configuration, Reference Data, Warehousing) into `sd-bk/docs`, since the new SDK ships none.
