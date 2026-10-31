# OpenNest

OpenNest is a zero-brokerage platform for finding flats and PG/hostel accommodation directly from owners across India. OpenNest is an independent project, not affiliated with NoBroker.

**Status:** Phase 0 infrastructure and Phase 1 backend foundation: Java 25, Spring Boot 4.1.1, five closed modules, guarded clients, Flyway/internal persistence, Hibernate validation, and HTTP errors with restrictive security. All 204 tests passed locally with Docker on 30 October 2026, including the review regressions recorded in the [handoff](docs/BACKEND_HANDOFF.md). Business APIs, login, repositories, and public deployment are not implemented.

**Planned website:** [opennest.co.in](https://opennest.co.in). The backend target is Java 25 with Spring Boot 4.1.1; DNS, TLS, and hosting readiness remain unverified.

The launch plan supports cities throughout India. Planned search will default to nationwide results with pagination and sorting, plus filters for city, locality, rent, bedrooms/BHK, available rooms, amenities, sharing, and nearby location. The recruiter sandbox will use fabricated listings across multiple Indian cities, with callable contact actions disabled.

Google sign-in through Spring Security is planned first; WhatsApp verification is a later option. Google account creation will not verify an owner's contact number. An authenticated owner will be able to create a PAUSED draft, upload and validate its photos, and explicitly publish it after separate contact verification and quota checks. Monthly prices will be in INR per person for PGs, per property for entire flats, and per room for individual flat rooms.

Owners will set how many people are allowed in the entire flat or each offered room. Listings will show that capacity separately from rent and current availability.

## Start here

- [Project plan and decisions](docs/PROJECT_PLAN.md)
- [Backend dependency versions and verification status](docs/BACKEND_DEPENDENCIES.md)
- [Backend deployment modes, environment inputs, and guards](docs/BACKEND_CONFIGURATION.md)
- [Phase 1.4 database schema contract and enforcement boundaries](docs/SCHEMA_CONTRACT.md)
- [Flyway migration sequence, execution, and integration checks](docs/BACKEND_MIGRATIONS.md)
- [Persistence ownership, validation, generated values, and optimistic locking](docs/BACKEND_PERSISTENCE.md)
- [HTTP Problem Details, safe logging, and security defaults](docs/BACKEND_HTTP_ERRORS.md)
- [Backend store isolation and required container verification](docs/BACKEND_ISOLATION.md)
- [Phase 1 handoff, Java 25 CI, local startup, and complete verification](docs/BACKEND_HANDOFF.md)
- [Backend modules and local build](backend/MODULE_CONTEXT.md)
- [Whole flats, individual rooms, pricing, and inventory](docs/LISTING_MODEL.md)
- [Pagination, sorting, and listing filters](docs/LISTING_SEARCH.md)
- [Authentication decisions and WhatsApp review](docs/AUTHENTICATION.md)
- [Deployment plan, verified constraints, and cost assumptions](docs/DEPLOYMENT_STACK.md)
- [AI development instructions](AGENTS.md)
- [Current implementation state](docs/ai-context/ARCHITECTURE_STATE.md)
- [Database schema and planned service invariants](docs/ai-context/SCHEMA_INDEX.md)
- [Proposed API](docs/ai-context/API_CONTRACTS.md)
- [Contributing](CONTRIBUTING.md)

## Backend bootstrap

Select a Java 25 JDK before using the supplied Maven Wrapper. From the repository root:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\backend\mvnw.cmd -version
.\backend\mvnw.cmd -f backend/pom.xml clean verify
```

The default build runs 130 tests for deployment policy, client/schema guards, safe HTTP errors/security, virtual threads, startup, and module boundaries. It produces `backend/target/opennest-backend-0.0.1-SNAPSHOT.jar` with entry point `in.opennest.OpenNestApplication`. No-connection unit contexts explicitly exclude Flyway and Hibernate. Add `-Pschema-integration` for 51 real PostgreSQL/PostGIS migration/persistence checks on two separate targets; see the [migration guide](docs/BACKEND_MIGRATIONS.md) for Docker or dedicated external test databases.

Phase 1.8 adds `-Pisolation-integration` for independently configured primary/sandbox contexts, real PostGIS/Redis reads and writes, credential rejection, header handling, and migration/dependency failures. It requires Docker and has no external-store fallback. On 30 October 2026, the review-fix Java 25 build passed 130 default, 51 migration/persistence, and 23 isolation tests with no failures, errors, or skips on Linux amd64 Docker. See [isolation verification](docs/BACKEND_ISOLATION.md).

The bootstrap now runs an HTTP server after Flyway/Hibernate validation. Every normal request is denied with safe Problem Details; login, default-password access, and business routes remain unavailable. Backend logging emits fixed HTTP status/error-ID events at INFO for 4xx and ERROR for 5xx, plus literal redacted framework ERROR diagnostics. Raw startup/framework data remains suppressed. Running it requires selected PostgreSQL/PostGIS credentials and migration privileges; production also requires a Redis password. Datastore selection is fixed at startup. Supply variables through the launching process or IDE; Spring Boot does not read the Compose `.env` file automatically. Read [configuration](docs/BACKEND_CONFIGURATION.md) and [HTTP/security defaults](docs/BACKEND_HTTP_ERRORS.md) before launching.

## Local infrastructure

Install Docker with Compose v2. Copy `.env.example` to `.env`, then fill both blank database password fields with different local passwords. Compose requires both values even when the sandbox profile is not enabled. Application-provider variables are reserved for later phases and are not used by the current datastore-only Compose setup.

The current PostGIS image targets x86-64; ARM machines need a separately tested compatible image strategy. See the [deployment compatibility notes](docs/DEPLOYMENT_STACK.md#versions-architecture-and-licenses). Local Redis is unauthenticated and loopback-published for development; do not expose these ports to an untrusted network.

```powershell
Copy-Item .env.example .env
docker compose config --quiet
docker compose up -d --wait
docker compose ps
```

With the default port settings and healthy containers, PostGIS will be available at `localhost:5432` and Redis at `localhost:6379`. Ports bind only to the local machine. Compose alone does not create application tables; backend startup applies Flyway migrations to its selected database.

The optional recruiter infrastructure uses a separate PostgreSQL instance, credentials, volume, Redis instance, and Docker bridge network:

```powershell
docker compose --profile sandbox up -d --wait
```

With default port settings, sandbox PostGIS uses `localhost:5433` and sandbox Redis uses `localhost:6380`. The command starts both primary and sandbox store pairs. Future production and sandbox backends must use separate connections, session stores, and cookies, plus separate OAuth clients if Google login is enabled in both. This Compose separation does not prove application isolation; the file is for local development, not production deployment.

To stop only the recruiter sandbox services:

```powershell
docker compose stop sandbox-postgres sandbox-redis
```

This preserves named volumes and leaves the primary services running. `down --volumes` permanently deletes local data; do not use it to reset the recruiter demo. Changing passwords in `.env` does not rotate credentials in an existing PostgreSQL volume.

## Validation and delivery

Pull requests run Compose validation, infrastructure startup, datastore network-separation checks, PostGIS checks, Redis checks, and a persistence smoke test in GitHub Actions. These infrastructure checks do not establish application authorization or crash recovery. CodeRabbit receives project-specific review instructions. The Backend workflow runs Java 25 with both real-store profiles and verifies reports and packaged boundaries. Inspect its completed PR checks before claiming CI success. GHCR builds and deployment remain unimplemented. See the [handoff](docs/BACKEND_HANDOFF.md) for local evidence and remaining work.

Named volumes and Redis AOF support recovery but are not backups or guarantees of zero data loss. Before public launch, test off-host backups and restoration, and measure actual memory use and recovery time.

## License

OpenNest is licensed under the [GNU Affero General Public License, version 3 only](LICENSE) (`AGPL-3.0-only`). The full, unmodified license text is included in LICENSE. Third-party components retain their respective licenses.
