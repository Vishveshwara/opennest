# OpenNest backend dependency verification

Checked on 22 October 2026 for Phase 1.1. Java 25 is the maintainer-selected target. The planned public website is `https://opennest.co.in`.

Status: Phase 1.1 complete. Official release/POM evidence, Java 25 wrapper execution, dependency resolution, managed-version checks, and the supplied skeleton's build/test passed. Phase 1.5/1.6 migration/persistence and Phase 1.7 HTTP/security evidence follow below. Session, business service, and provider integration remain later work; Phase 1 is incomplete.

## Initializr input at Phase 1.1

- At Phase 1.1, `backend/pom.xml` used `spring-boot-starter-parent:4.1.1`, requested Java 25, and declared only `spring-boot-starter` and `spring-boot-starter-test`.
- The supplied wrapper pins Maven 3.10.0 and Wrapper 3.3.4 with `distributionType=only-script`. Retain those supplied pins; the earlier candidate Maven 3.9.16 was not installed or selected.
- The input main/test classes used `in.co.opennest`. Phase 1.2 aligned them to the required `in.opennest` root.
- The input application name was `backend`, with empty POM name/description/license metadata. Phase 1.2 applied OpenNest naming, website metadata, and AGPL-3.0-only licensing metadata.
- The default shell still selects Oracle Java 21.0.8. Verification explicitly used a portable Eclipse Temurin 25.0.4.1+1 JDK through process-local `JAVA_HOME` and `PATH`, plus a temporary Maven cache; system Java settings were not changed.
- No backend source, POM, wrapper, application configuration, migration, or feature module was changed during Phase 1.1.

## Version selection and evidence

Use the Boot parent/BOM as the version authority. The values below were read from the published [Spring Boot 4.1.1 dependency BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom) and checked against the resolved [dependency probe](verification/backend-dependencies/pom.xml). The probe also covers future libraries; installed application dependencies are recorded by implementation task below.

| Component | Selected version | Compatibility or integration rule |
| --- | --- | --- |
| Java | 25 | [OpenJDK 25](https://openjdk.org/projects/jdk/25/) is released; Boot 4.1.1 supports Java 17 through 26. Verification used Temurin 25.0.4.1+1; production runtime selection remains separate. |
| Spring Boot parent and plugin | 4.1.1 | Requested release exists; [system requirements](https://docs.spring.io/spring-boot/system-requirements.html) include Maven 3.6.3 or later. |
| Maven / Wrapper | 3.10.0 / 3.3.4 | Supplied wrapper pins; [Maven release history](https://maven.apache.org/docs/history.html) identifies 3.10.0 as a GA release. Wrapper execution under Java 25 passed. |
| Spring Modulith BOM and starters | 2.1.1 | Separate BOM import; [release notes](https://github.com/spring-projects/spring-modulith/releases/tag/2.1.1) explicitly upgrade to Boot 4.1.1. |
| Spring Framework / Security | 7.0.9 / 7.1.1 | Boot-managed; do not override independently. |
| Spring Data BOM / Spring Session | 2026.0.1 / 4.1.1 | Boot-managed; Redis browser sessions belong to Phase 2. |
| Hibernate ORM and Spatial | 7.4.5.Final | Keep core and spatial aligned through Boot management. [Hibernate 7.4 compatibility](https://hibernate.org/orm/releases/7.4/) includes Java 25 and Boot 4.1. |
| Flyway core and PostgreSQL module | 12.4.0 | Use `org.flywaydb:flyway-database-postgresql` alongside core; no schema auto-update. |
| PostgreSQL JDBC | 42.7.13 | Boot-managed `org.postgresql:postgresql`; database baseline remains PostgreSQL 17/PostGIS 3.5. |
| Lettuce | 7.5.2.RELEASE | Boot-managed Redis client; local Redis baseline remains 7.4. |
| JUnit Jupiter / Testcontainers | 6.0.3 / 2.0.5 | Boot-managed; real PostGIS/Redis tests remain later Phase 1 work. |
| Resilience4j Boot 4 integration | 2.4.0 | Optional probe profile resolves under Java 25 with Boot-managed Spring versions; circuit-breaker behavior remains untested and deferred. |

The [Modulith BOM](https://repo.maven.apache.org/maven2/org/springframework/modulith/spring-modulith-bom/2.1.1/spring-modulith-bom-2.1.1.pom) and [core starter POM](https://repo.maven.apache.org/maven2/org/springframework/modulith/spring-modulith-starter-core/2.1.1/spring-modulith-starter-core-2.1.1.pom) were retrieved successfully. The starter declares Boot 4.1.1, corroborating the release notes. The generic Modulith appendix still showed an older compatibility table, so that table alone was not used to select this release.

The [Flyway PostgreSQL guide](https://documentation.red-gate.com/fd/postgresql-database-277579325.html) requires its separate PostgreSQL module. Its [database support table](https://documentation.red-gate.com/fd/database-version-support-143754067.html) includes PostgreSQL 17. This is documentation evidence; Flyway migrations and PostGIS geometry/geography mappings still require execution against the real database image.

[Resilience4j 2.4.0 release notes](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0) introduce Boot 4 support and a Java 21 baseline. The published [Boot 4 adapter POM](https://repo.maven.apache.org/maven2/io/github/resilience4j/resilience4j-spring-boot4/2.4.0/resilience4j-spring-boot4-2.4.0.pom) declares Boot 4.0.0 and Framework 7.0.2. Its optional probe profile resolved with Boot 4.1.1 and Framework 7.0.9, retaining Boot's managed versions. Runtime behavior still needs testing before use. Do not substitute the Boot 3 adapter. Circuit-breaker configuration and Overpass integration remain Phase 3 work.

## Executed evidence

- Downloaded the portable [Temurin 25.0.4.1+1 Windows x64 JDK](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1) through the Adoptium API and verified its SHA-256: `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283`. Both `java` and `javac` reported 25.0.4.1.
- The existing `backend/mvnw.cmd -version` reported Maven 3.10.0, Eclipse Adoptium Java 25.0.4.1, Windows amd64.
- Baseline probe: effective POM, dependency resolution, and JSON dependency tree passed; 171 unique dependency artifacts resolved. An offline rerun with the checked-in Central-only settings also passed.
- Optional `resilience4j-check` profile: resolution and JSON dependency tree passed; 186 unique dependency artifacts resolved using only Maven Central.
- Graph assertions matched the selected versions for Boot, Framework, Security, Modulith, Hibernate core/spatial, Flyway core/PostgreSQL, JDBC, Lettuce, Spring Session, JUnit, and Testcontainers. All selected Spring Framework modules use 7.0.9, Security modules 7.1.1, and Boot modules 4.1.1 in both graphs; neither graph contains snapshot versions.
- Phase 1.1 backend: `verify` passed, with one context-load test, zero failures/errors/skips, and a locally packaged executable JAR. The compiled main class had major version 69, confirming Java 25 bytecode. This test did not exercise planned feature modules or database behavior.
- During Phase 1.1, SHA-256 comparisons confirmed all ten existing backend input files stayed unchanged. Verification outputs remained in ignored `target/` directories. No application dependency or feature was added at that step.
- The passing test emitted Mockito/Byte Buddy dynamic-agent warnings. Review test-agent configuration during bootstrap if needed; no test failure or product-code change resulted.

## Phase 1.2 bootstrap

- The application now uses Maven coordinates `in.opennest:opennest-backend:0.0.1-SNAPSHOT`, entry point `in.opennest.OpenNestApplication`, application name OpenNest, website metadata, and AGPL-3.0-only licensing metadata.
- The backend imports `spring-modulith-bom:2.1.1` and declares its core/test starters, alongside the existing Boot core/test starters. Boot-managed dependencies remain versionless. No datastore, web, authentication, messaging, or event-registry starter was added.
- Five closed package-level modules are declared: common, identity, listing, interaction, sandbox. No public contracts or cross-module dependencies exist yet. Internals remain nested packages; introducing an interface requires an explicit consumer dependency declaration.
- A clean build under the same Temurin 25.0.4.1/Maven 3.10.0 toolchain passed three tests: context startup, exact discovery of all five modules, and `ApplicationModules.verify()` for structural boundaries. No failures/errors/skips occurred.
- Output JAR: `backend/target/opennest-backend-0.0.1-SNAPSHOT.jar`. Local build/test logs remain under ignored `target/phase-1.2/`; CI was not run. Module context and bootstrap instructions: [backend context](../backend/MODULE_CONTEXT.md).

## Phase 1.3 configuration

- Added Boot-managed JDBC and Redis starters plus the PostgreSQL runtime driver, retaining the verified Boot/Modulith pins. No Flyway, JPA, web, authentication, or messaging starter was added.
- Common's internal environment post-processor runs after config-data loading, validates deployment policy, and supplies the selected datasource/Redis properties before client construction. Immutable settings redact datastore descriptions; binding failures do not retain input-bearing causes.
- Hikari maximum/minimum idle are 5/1; virtual threads default true and are exercised by a task-execution test. SQL script initialization and Redis repositories are disabled; no undocumented lazy-connection setting is used.
- A clean Java 25 build passed 46 tests with zero failures/errors/skips. Coverage includes valid development primary, development sandbox and production contexts; invalid/unsafe settings; alternate Boot overrides; credential redaction; virtual-thread execution; and module boundaries.
- The packaged executable JAR also rejected production plus demo-reset configuration with exit code 1 before client construction, confirming the environment post-processor is discovered outside the test classpath.
- Clients are constructed without executing queries or Redis commands. This is not proof of live connectivity, server credential rejection, schema validity, or session isolation. Configuration inputs and limitations: [backend configuration](BACKEND_CONFIGURATION.md). CI was not run.

## Phase 1.5 Flyway integration

- Added Boot-managed Flyway starter/PostgreSQL module (Flyway 12.4.0), retaining Java 25/Boot 4.1.1/Modulith 2.1.1. The opt-in schema integration profile uses the already verified Testcontainers 2.0.5 PostgreSQL module and Maven Failsafe; no JPA/web/authentication dependency was added.
- V1/V2 resources create the agreed PostGIS schema and indexes without application fixtures. The environment guard reserves Flyway properties and uses the selected datasource, with validation enabled and baseline/clean disabled.
- Clean Java 25 build passed 52 unit/context/module tests and 25 real PostgreSQL 17.11/PostGIS 3.5.3 integration tests across separate primary/sandbox targets. Schema/checksums matched; repeat migration did no work. Docker execution remains unverified locally. [Migration record](BACKEND_MIGRATIONS.md) contains commands, evidence, and limits.

## Phase 1.6 persistence integration

- Replaced the direct JDBC starter with the Boot-managed JPA starter, which retains JDBC/Hikari transitively. Added matching Hibernate Spatial 7.4.5.Final and Boot-managed Jackson databind 3.1.5 for JSONB; transitive JTS is 1.19.0. No framework pins changed and no web/authentication starter was added.
- Six package-private module-owned mappings use UUID foreign references without entity associations, Instant/LocalDate timestamps, string enums, numeric/JSONB/geography types, read-only generated values, and listing @Version. Flyway remains the sole schema writer; Hibernate is forced to validate with direct schema-action inputs rejected.
- The clean Java 25 build passed 57 unit/context/module tests and 49 PostgreSQL 17.11/PostGIS 3.5.3 tests across both modes, including entity round trips, stale overlapping writes, and schema-drift startup rejection. [Persistence record](BACKEND_PERSISTENCE.md) explains explicit generated-field refresh after updates on the pinned Hibernate release.

## Phase 1.7 HTTP and security

- Added Boot-managed MVC, validation, and Security starters, plus MVC/Security test support. Packaged Framework MVC is 7.0.9, Security core/web/config 7.1.1, Hibernate Validator 9.1.3.Final, and Tomcat 11.0.24; existing Java 25/Boot 4.1.1 and other pins remain unchanged.
- Common's fixed RequestFailure contract, Problem Details adapters, deny-all security, and allowlisted status/error-ID logging add no business controller, login provider, password user, or diagnostic API. Test fixtures are absent from the packaged JAR.
- Clean Java 25 full build passed 114 unit/context/HTTP and 49 PostgreSQL/PostGIS tests, zero failures/errors/skips. Fifty HTTP tests cover known versus unexpected failures, safe responses/logs, CSRF/security denial, and live servlet handling. [HTTP/security evidence](BACKEND_HTTP_ERRORS.md) records the contract and operational logging limits.

## Remaining implementation checks

- Phase 1.2 reused the supplied skeleton and retained Java 25, Boot 4.1.1, and the verified wrapper. Keep Boot-managed dependencies versionless as later tasks add capabilities.
- Select only the dependencies needed for the current task. Phase 1 needs web/validation, persistence/Flyway, Redis configuration, baseline security, module verification, and integration-test support. OIDC/session behavior and enrichment remain later phases.
- Use Testcontainers 2 coordinates such as `org.testcontainers:testcontainers-postgresql` and `org.testcontainers:testcontainers-junit-jupiter`; verify the resolved graph before implementing tests.
- Do not enable Modulith JDBC/JPA event-publication stores merely to verify module boundaries; the planned schema does not include an event registry.

To reproduce from the repository root, set `JAVA_HOME` to an available Java 25 JDK. The verification JDK is currently under `%TEMP%/opennest-phase-1-1/jdk/jdk-25.0.4.1+1`; temporary files may be removed by the operating system. These toolchain choices do not select a production image or change system defaults.

```powershell
$env:JAVA_HOME = Join-Path $env:TEMP 'opennest-phase-1-1/jdk/jdk-25.0.4.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:MAVEN_USER_HOME = Join-Path $env:TEMP 'opennest-phase-1-1/maven-user-home'
$artifactCache = Join-Path $env:TEMP 'opennest-phase-1-1/repository'
$checkDir = (Resolve-Path 'docs/verification/backend-dependencies').Path
$settings = Join-Path $checkDir 'settings.xml'
$fixture = Join-Path $checkDir 'pom.xml'
$mavenArgs = @('-B', '-ntp', '-s', $settings, '-gs', $settings, "-Dmaven.repo.local=$artifactCache")
java -version
.\backend\mvnw.cmd -version
.\backend\mvnw.cmd @mavenArgs -f $fixture org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom "-Doutput=$checkDir/target/effective-pom.xml"
.\backend\mvnw.cmd @mavenArgs -f $fixture org.apache.maven.plugins:maven-dependency-plugin:3.10.0:resolve org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree -DoutputType=json "-DoutputFile=$checkDir/target/dependency-tree.json"
.\backend\mvnw.cmd @mavenArgs -f $fixture -Presilience4j-check org.apache.maven.plugins:maven-dependency-plugin:3.10.0:resolve org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree -DoutputType=json "-DoutputFile=$checkDir/target/resilience4j-tree.json"
.\backend\mvnw.cmd @mavenArgs -f backend/pom.xml verify
```

The probe is a packaging-only POM with no Java application sources. It covers planned dependencies without installing them into the backend. Its [settings file](verification/backend-dependencies/settings.xml) mirrors all repositories to Maven Central, excluding credentialed vendor feeds declared by transitive POMs. After the initial download, add `-o` for cached resolution checks. Repeat graph inspection when actual application dependencies change; successful resolution alone does not establish feature-level runtime compatibility.

Checks not executed or claimed: feature service integration, Docker image execution, Redis integration/isolation, sessions/OIDC, circuit-breaker behavior, application CI, image builds, or deployment. These belong to their implementation tasks. Structural module verification runs in the backend test suite; Flyway/PostGIS schema integration, Hibernate validation, persistence round trips, and stale ORM writes are proven on isolated local database engines. No release artifact was published.
