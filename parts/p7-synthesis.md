
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
