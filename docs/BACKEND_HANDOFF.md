# OpenNest Phase 1 handoff

Status on 23 October 2026: Phase 1.9 implementation is being verified. Java 25 remains the required target. Phase 1.8 container proof and the new backend CI result must pass before Phase 1 is marked complete. No merge, deployment, release image, or public hosting is part of this handoff.

## CI contract

[Backend CI](../.github/workflows/backend.yml) runs on pull requests, pushes to main, and manual dispatch. It selects Eclipse Temurin Java 25 on an amd64 Ubuntu 24.04 runner. It uses the Maven Wrapper rather than the runner's Maven installation and enables both `schema-integration` and `isolation-integration`.

The existing [Foundation workflow](../.github/workflows/foundation.yml) remains unchanged. It continues to check Compose configuration, context limits, PostGIS, primary/sandbox network separation, and persistence across container replacement.

The backend workflow checks these gates:

- Verify the Java 25 POM target, both Maven Wrapper scripts against recorded Apache source hashes, and the Maven distribution checksum pin. Script checks normalize CRLF to LF. The wrapper validates the distribution checksum on download.
- Enforce context indexes below 80 lines and module contexts below 50 lines.
- Require Linux Docker and amd64, then run all default and real-store tests. Missing Docker is a failure, never a skip.
- Require reports from module/configuration/HTTP suites and both integration suites, with no failures, errors, or skipped tests.
- Inspect the executable JAR for V1/V2 and the absence of test-only controllers and persistence fixtures.
- Retain JUnit XML and the verification JSON for seven days. No deployment credential is supplied; workflow permissions are read-only. Actions use verified commit revisions.

The current suite inventory is 114 default tests, 49 migration/persistence tests, and 23 isolation tests, for 186 expected invocations. Counts are an inventory, not a claim that an uninspected run passed. The [verification helper](../.github/scripts/verify_backend.py) checks actual reports and records their counts.

## Local verification

Select a Java 25 JDK in the terminal or IDE. From the repository root:

```powershell
java --version
.\backend\mvnw.cmd --version
python .github/scripts/verify_backend.py
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml clean verify
```

The default build exercises module boundaries, configuration, errors/security, and servlet behavior without requiring database servers. It does not establish datastore isolation.

For the complete Phase 1 gate, start a Linux Docker engine accessible to Testcontainers, then run:

```powershell
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pschema-integration,isolation-integration' clean verify
python .github/scripts/verify_backend.py --reports
```

On Linux/macOS, replace `.\backend\mvnw.cmd` with `bash backend/mvnw` and `python` with `python3`. The PostGIS image requires amd64 support. The shared Maven settings mirror all dependency resolution to Central and avoid credentialed vendor feeds from transitive POMs. Do not supply the schema suite's external-store override variables when reproducing the container CI run. The isolation suite never accepts that fallback.

Surefire/Failsafe reports and `backend-verification.json` are under `backend/target`. `--reports` fails if either integration suite is absent, failed, or skipped. After a default-only build, that command is expected to fail because integration proof is missing. The checksum manifest is under `backend/.mvn/wrapper`; update it deliberately from Apache sources when upgrading the wrapper or Maven. References: [Apache Wrapper checksum settings](https://maven.apache.org/tools/wrapper/index.html#checksum-verification-of-downloaded-binaries), [Temurin Java 25 setup](https://github.com/actions/setup-java/tree/v5).

## Local startup

On a fresh checkout, copy `.env.example` to an untracked `.env` and set different local database passwords. Do not overwrite an existing `.env`. Start development stores:

```powershell
docker compose --profile sandbox config --quiet
docker compose --profile sandbox up -d --wait --wait-timeout 180
```

Compose reads `.env`; Spring Boot does not. Supply `DB_PASSWORD` privately through the primary terminal's environment or IDE, matching the local Compose value. If you customized database names, users, addresses, or ports, supply those documented inputs too. After building, launch primary:

```powershell
$env:OPENNEST_ENVIRONMENT = 'DEVELOPMENT'
$env:OPENNEST_DATA_MODE = 'PRIMARY'
java -jar backend/target/opennest-backend-0.0.1-SNAPSHOT.jar
```

In a separate terminal, supply only the matching `SANDBOX_DB_PASSWORD` and any customized sandbox inputs, then launch sandbox:

```powershell
$env:OPENNEST_ENVIRONMENT = 'DEVELOPMENT'
$env:OPENNEST_DATA_MODE = 'SANDBOX'
$env:SERVER_PORT = '8081'
java -jar backend/target/opennest-backend-0.0.1-SNAPSHOT.jar
```

Both processes apply the same migrations, then Hibernate validates. They stay running on separate HTTP ports. Redis is a lazy dependency; construction does not establish readiness. Normal unauthenticated requests return safe 401 responses, and mutations without a valid CSRF token return 403. There is no enabled login, default password, business handler, actuator, or diagnostic API. Logging emits fixed HTTP status/error-ID events only. These are development commands, not production configuration.

Stop each backend with Ctrl+C. `docker compose --profile sandbox down` stops development stores while retaining their named volumes. Production needs separately reviewed credentials, runtime roles, network/TLS configuration, and hosting. See [configuration](BACKEND_CONFIGURATION.md), [migration behavior](BACKEND_MIGRATIONS.md), and [deployment decisions](DEPLOYMENT_STACK.md).

## Scope and remaining work

Phase 1 provides five closed modules, guarded deployment configuration, shared Flyway schema/indexes, six internal persistence mappings, Hibernate validation, optimistic locking, fixed common errors, restrictive security, and real-store test suites. `common` exposes `RequestFailure`; modules do not expose entities or depend on one another's internals.

Phase 2 implements Google OIDC, Redis sessions, CSRF/logout, private profiles, and trust calculation after threshold approval. Phase 3 implements draft/upload/publication, verified-contact and quota gates, inventory/version checks, and paginated nationwide search. Phase 4 implements reveals/reviews/reports and isolated fabricated multi-city demo operations. Phase 5 implements the frontend. Phone verification/provider approval, public map coarsening, restricted production database roles, backups, capacity, and hosting remain explicit prerequisites. No feature endpoint or transactional business invariant is claimed by the foundation.

Actual local and GitHub results, the verified revision, and the review link are recorded in [the Phase 1.9 verification record](verification/backend-handoff/result.json). CI success is claimed only after inspecting its completed run. A draft PR does not authorize merge or deployment.
