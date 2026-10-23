# OpenNest backend configuration

Phase 1.3 configures deployment guards/clients; Phase 1.5 adds Flyway migrations, Phase 1.6 adds mappings/validation, and Phase 1.7 adds HTTP errors with restrictive security. The server denies every normal request. No login, demo operations, repositories, business REST routes, or diagnostic endpoint is implemented.

## Deployment selection

`OPENNEST_ENVIRONMENT` selects runtime policy independently of `OPENNEST_DATA_MODE`, which selects one datastore pair at process startup. Both are server configuration, never request inputs. Spring profiles do not select either setting.

| Environment | Data mode | Startup policy |
| --- | --- | --- |
| DEVELOPMENT | PRIMARY | Default combination; active database password required. |
| DEVELOPMENT | SANDBOX | Explicit opt-in; sandbox database password required and primary secrets unnecessary. |
| PRODUCTION | PRIMARY | Database and Redis passwords required; all development/demo flags false; no sandbox datastore secrets supplied. |
| PRODUCTION | SANDBOX | Rejected before clients are created. |

Current sandbox configuration is for isolated development/preview work. This does not establish public recruiter hosting readiness or authorize production deployment.

The deployment post-processor runs after Spring config-data loading and before client construction. It binds immutable `opennest.deployment` settings, validates policy, then supplies the selected Boot datasource and Redis properties. Unknown properties under this prefix, unknown enum values, malformed booleans, and invalid ports fail startup. Configuration errors use fixed messages without echoing credentials or retaining binding exceptions containing input values.

## Environment inputs

The root [.env.example](../.env.example) is a development template. Docker Compose reads `.env`; Spring Boot does not automatically read this file. Supply backend variables through the launching process or IDE environment. Never pass the whole development file to a production process.

| Variables | Defaults and meaning |
| --- | --- |
| `OPENNEST_ENVIRONMENT` | `DEVELOPMENT`; accepts DEVELOPMENT or PRODUCTION. Set PRODUCTION explicitly for the real-data production backend. |
| `OPENNEST_DATA_MODE` | `PRIMARY`; accepts PRIMARY or SANDBOX. |
| `OPENNEST_DEVELOPMENT_AUTH` | false; true is allowed only in DEVELOPMENT. This reserves policy for later substitutes; it does not implement authentication. |
| `OPENNEST_PERSONA_SWITCHING`, `OPENNEST_DEMO_RESET`, `OPENNEST_DEMO_TELEMETRY` | false; true requires SANDBOX and is forbidden in PRODUCTION. No corresponding operations exist yet. |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | Primary PostgreSQL; defaults `127.0.0.1`, 5432, `opennest`, `opennest`, blank password. |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_USER`, `REDIS_PASSWORD` | Primary Redis; defaults `127.0.0.1`, 6379, blank ACL username/password. Production requires a password; an optional username requires a password in every environment. |
| `SANDBOX_DB_HOST`, `SANDBOX_DB_PORT`, `SANDBOX_DB_NAME`, `SANDBOX_DB_USER`, `SANDBOX_DB_PASSWORD` | Sandbox PostgreSQL; defaults `127.0.0.1`, 5433, `opennest_sandbox`, `opennest_sandbox`, blank password. |
| `SANDBOX_REDIS_HOST`, `SANDBOX_REDIS_PORT`, `SANDBOX_REDIS_USER`, `SANDBOX_REDIS_PASSWORD` | Sandbox Redis; defaults `127.0.0.1`, 6380, blank ACL username/password. |

Hostnames or IP literals and ports 1..65535 are accepted; do not supply URLs, user-info, or query strings as hosts. Database names use letters, digits, underscores, or hyphens. JDBC URLs are derived from host/port/name without credentials or query parameters.

Configure primary and sandbox PostgreSQL on distinct host/port targets with different database names and users. If both passwords are supplied, they must differ. Redis also requires distinct host/port targets; logical databases alone do not provide isolation, so both clients use database 0. Redis passwords must differ when both are supplied. Host comparisons are case-insensitive and recognize common localhost/IPv4/IPv6 loopback aliases.

Inactive-store non-secret declarations participate in overlap checks. Inactive-store passwords are optional; production rejects any nonblank sandbox database or Redis password. A sandbox process needs only its own secrets. Distinct aliases can still resolve to the same remote server; these checks validate declared settings and do not prove infrastructure isolation, database privileges, or credential rejection by real servers.

The following settings are generated from the validated selection:

- PostgreSQL URL/user/password via Boot's JDBC auto-configuration; Hikari maximum pool size 5 and minimum idle 1.
- Redis host/port/user/password via Boot's Lettuce client auto-configuration; database 0 and Redis repositories disabled.
- Basic SQL script initialization disabled; Flyway applies the shared V1/V2 migrations on startup, with validation enabled, no automatic baseline, and clean disabled. Hibernate then validates six mapped entities; schema creation/update is never enabled.
- Hibernate uses UTC JDBC timestamps and Jackson 3 JSON formatting; Open EntityManager in View and JPA repository auto-configuration are disabled. There are no repository interfaces yet.
- Phase 1.7 pins safe error output and a Logback allowlist of fixed HTTP status/error-ID events. Raw framework/startup/SQL/request/exception output, Tomcat access logging, debug, and trace are disabled. Direct logging/error-policy overrides are rejected; see [HTTP errors/security](BACKEND_HTTP_ERRORS.md).
- Virtual threads enabled by default through the documented `spring.threads.virtual.enabled=true` setting. No undocumented lazy-connection property is used.

Direct `spring.datasource.*`, `spring.data.redis.*`, legacy `spring.redis.*`, `spring.flyway.*`, `spring.jpa.*`, raw `hibernate.*`, and Jakarta/legacy persistence inputs are rejected, including equivalent environment-variable forms. Configure targets through the documented variables or `opennest.deployment` properties. This prevents independent client/migration connection overrides and alternate ORM schema-generation actions. Client objects are constructed once at startup; headers such as `X-Sandbox-Mode` are not read or mapped to configuration. Startup requires a reachable, migration-ready PostgreSQL/PostGIS target that passes Hibernate validation; constructing the Redis client still does not prove Redis connectivity.

## Verification and limits

Run the Java 25 backend checks from the repository root:

```powershell
.\backend\mvnw.cmd -f backend/pom.xml clean verify
```

At Phase 1.3, the clean Java 25 build passed 46 tests with zero failures/errors/skips. Those tests supplied fabricated credentials and constructed clients without executing queries or Redis commands. They covered default primary, explicit sandbox, valid production, forbidden production/demo combinations, shared target/credential rejection, alternate Boot settings, invalid input, redacted failures through a real Spring launch, and actual task execution on a virtual thread. Module-boundary checks remain active. A separate `java -jar` smoke check exited with code 1 and the expected production/demo rejection, confirming the packaged guard registration was effective. Phase 1.3 logs are under ignored `target/phase-1.3/`; CI was not run.

Phase 1.5 passed 52 unit/context/module and 25 database tests; Phase 1.6 passed 57 and 49. Phase 1.7's clean build passes 114 unit/context/HTTP and 49 database tests, including 50 HTTP checks. No-connection unit contexts exclude Flyway/Hibernate auto-configuration; real application-mode integration excludes neither. See [migration execution](BACKEND_MIGRATIONS.md), [persistence proof](BACKEND_PERSISTENCE.md), and [HTTP/security proof](BACKEND_HTTP_ERRORS.md).

Phase 1.8 adds a [container-only isolation suite](BACKEND_ISOLATION.md) for actual PostgreSQL/Redis reads/writes, credential rejection, header handling, production guards, and migration/dependency failures. It compiles and the 114 default tests pass. The required container gate fails setup on this machine because Docker is absent; real Redis/isolation proof remains pending. Redis still connects lazily, so unavailable Redis or a wrong password fails on use rather than necessarily preventing startup.

Deployed network rejection, restricted server privileges, session/cookie isolation, storage/cache isolation, and demo operations remain later tasks. The local Compose stores are development-only; production credentials, image architecture, runtime TLS options, and public routing require the separate [deployment plan](DEPLOYMENT_STACK.md).

Framework references: [externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html), [config-data processing order](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/context/config/ConfigDataEnvironmentPostProcessor.html), and [common application properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html). The checked backend uses Boot 4.1.1.
