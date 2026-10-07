
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
