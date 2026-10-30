# OpenNest Flyway migrations

Phase 1.5 implements schema/indexes; Phase 1.6 adds [mappings/Hibernate validation](BACKEND_PERSISTENCE.md). Phase 1.7 adds [HTTP errors and restrictive security](BACKEND_HTTP_ERRORS.md), without changing V1/V2. Business services/endpoints and publication/quota transactions remain later work. The [schema contract](SCHEMA_CONTRACT.md) defines storage semantics; its service invariants are not database authorization.

## Migration sequence

The application includes one shared `classpath:db/migration` directory:

| Version | Resource under backend/src/main/resources/db/migration | Effect |
| --- | --- | --- |
| 1 | `V1__create_application_schema.sql` | Enable PostGIS in public; create six application tables, generated geography/room totals/Kolkata dates, checks, unique keys, and immediate RESTRICT foreign keys |
| 2 | `V2__create_application_indexes.sql` | Create 15 nonunique indexes, including GiST geography, owner/status and city filters, plus nationwide/city newest and both rent directions with id ASC ties |

Primary and sandbox processes run these exact resources against their separately selected databases. A process migrates only its own selected datasource; it never connects to the other database to run a second migration. There are no environment-specific SQL branches, demo inserts, application fixtures, or row-level sandbox flags. PostGIS's reference data is extension infrastructure.

Spring Boot's Flyway starter and PostgreSQL Flyway module use Boot-managed Flyway 12.4.0. The deployment guard pins the location and public schema, enables validation and migration-name checking, disables automatic baselining and clean, and rejects direct `spring.flyway.*` inputs, including environment aliases. Flyway inherits the selected datasource; it cannot receive a second URL/user/password through those properties. Basic Spring SQL initialization remains disabled. [Spring Boot database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html) describes the startup integration.

An unreachable database, failed migration, or checksum mismatch must prevent startup. Never edit an applied migration, run automatic repair/baseline, or enable Hibernate schema updates to bypass failure. Future schema changes need a new ordered migration. A nonempty, unmanaged application schema requires deliberate reconciliation; automatic adoption is disabled. Existing PostGIS extension objects alone are supported without baselining.

## Applying the same schema to both modes

Build with Java 25, provision two distinct PostgreSQL/PostGIS targets, and supply their environment inputs using [backend configuration](BACKEND_CONFIGURATION.md). No deployment is performed by this document.

From the repository root, run each bootstrap separately with its authorized credentials:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml clean verify
$env:OPENNEST_ENVIRONMENT = 'DEVELOPMENT'
$env:OPENNEST_DATA_MODE = 'PRIMARY'
java -jar backend/target/opennest-backend-0.0.1-SNAPSHOT.jar
```

The HTTP server stays running. Stop it before switching modes in the same terminal, or launch sandbox in a separate terminal on another port:

```powershell
$env:OPENNEST_ENVIRONMENT = 'DEVELOPMENT'
$env:OPENNEST_DATA_MODE = 'SANDBOX'
$env:SERVER_PORT = '8081'
java -jar backend/target/opennest-backend-0.0.1-SNAPSHOT.jar
```

Each startup migrates its selected database, then validates mappings and runs the HTTP server with all normal requests denied. It does not provide a migration-only exit command. For production, explicitly select PRODUCTION/PRIMARY, supply only approved production secrets, and follow the [deployment plan](DEPLOYMENT_STACK.md); these examples are not production deployment procedures. Raw startup diagnostics are suppressed by the Phase 1.7 logging policy.

PostGIS must be installed on the PostgreSQL server and available in public. The identity executing V1 needs database DDL privileges and permission to create the extension, or an administrator must provision the extension in public first. Local Compose roles and temporary test roles are not approved production runtime roles. Separating restricted runtime privileges from migration/admin privileges remains a deployment task; no independent migration credentials are added in this phase.

## Repeatable integration checks

Normal `clean verify` now runs 130 unit/context/HTTP tests. No-connection test contexts explicitly exclude Flyway and Hibernate auto-configuration. Runtime startup excludes neither. The database profile adds 51 migration/persistence checks, starting each application's HTTP server on a random port. This includes timestamp clamping for future-created listings in both deployment modes.

Run real database checks with Docker available:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml -Pschema-integration clean verify
```

The profile adds integration-test sources and runs Failsafe after unit tests. By default, Testcontainers starts two separate `postgis/postgis:17-3.5` instances with different users, passwords, and database names. Missing Docker fails the integration run; the suite does not silently skip or fall back to an embedded database. Test containers stop at suite completion.

Without Docker, supply all six variables below for two fresh, dedicated PostgreSQL 17/PostGIS 3.5 instances, then run the same Maven command:

| Mode | URL | Credentials |
| --- | --- | --- |
| Primary | `OPENNEST_SCHEMA_TEST_PRIMARY_URL` | `OPENNEST_SCHEMA_TEST_PRIMARY_USER`, `OPENNEST_SCHEMA_TEST_PRIMARY_PASSWORD` |
| Sandbox | `OPENNEST_SCHEMA_TEST_SANDBOX_URL` | `OPENNEST_SCHEMA_TEST_SANDBOX_USER`, `OPENNEST_SCHEMA_TEST_SANDBOX_PASSWORD` |

External URLs must use `jdbc:postgresql://host:port/database`, without user-info/query parameters; database names must end in `_schema_test`. Targets must differ. The suite requires no existing application tables, applies real migrations through both application modes, and never drops, cleans, or resets an external schema. Use fresh dedicated databases for another run. Most fabricated rows roll back; Phase 1.6's stale-write test commits its dedicated fixture for overlapping transactions and deletes only those UUIDs in cleanup. Its schema-drift test temporarily renames a column and restores it in finally. Successful runs leave only schema/extension objects and migration history.

## Verified result and limits

At Phase 1.5 on 22 October 2026, the clean Java 25 build passed 52 unit/context/module tests and 25 integration tests, with zero failures/errors/skips. The integration run used two isolated, password-protected portable PostgreSQL 17.11 clusters with PostGIS 3.5.3 on loopback ports 55432/55433. This is database-engine evidence; the Docker image was not executed because Docker is unavailable locally. Phase 1.6 extends the same profile to 57 unit tests and 49 database tests; [persistence evidence](BACKEND_PERSISTENCE.md) records the passing result.

- Both application modes applied V1/V2 and validated them; immediate repeat migration executed zero migrations. One target had PostGIS preinstalled, while the other enabled it through V1.
- Both targets had identical 70-column schema definitions, constraints, indexes, and migration checksums, with no application fixture rows remaining.
- Tests exercised all three rental modes, studio flats, PAUSED defaults and published inventory consistency, NULL/mode rejection, capacities, price/range/timestamp rules, generated totals beyond integer range, generated-column write rejection, point orientation/bounds, and geography distances in metres.
- Tests exercised identity/contact uniqueness and nullable phones, aggregate shapes, image key/state/expiry constraints, Kolkata midnight under three session timezones, daily-pair/review uniqueness, owner consistency, self-action rejection, report shape, and restrictive deletion.
- Catalog checks confirmed seven immediate RESTRICT foreign keys, three stored generated columns, all 15 indexes, and newest/rent direction plus UUID tie-breaker definitions.

Temporary database binaries/data are under `%TEMP%/opennest-phase-1-5`; test servers were reused for isolated Phase 1.6 databases and stopped after verification. Local logs are under ignored `target/phase-1.5/` and `target/phase-1.6/`. Existing system PostgreSQL 18 data was not used. No hosted production/recruiter database or Compose volume was migrated. Docker/Compose execution, Redis isolation, feature service/quota concurrency, application CI, and deployment remain unverified; Hibernate validation and overlapping stale ORM writes are proven by Phase 1.6.
