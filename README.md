# OpenNest

OpenNest is a zero-brokerage platform for finding flats and PG/hostel accommodation directly from owners across India. OpenNest is an independent project, not affiliated with NoBroker.

**Status:** Phase 1 foundation, Phase 2 identity, Phase 3 listings, and opt-in Phase 3A Telegram-only authentication are implemented on Java 25/Spring Boot 4.1.1. Listings include drafts, owner retrieval, validated private image uploads, publication/inventory controls, and nationwide search. The [listing handoff](docs/LISTINGS_HANDOFF.md) records contracts, limits, verification, and pending provider checks. Phase 4 interactions and the isolated recruiter backend are implemented. Phase 5 adds the public/recruiter frontend; see the [frontend handoff](docs/PHASE5_HANDOFF.md) for setup and verification. Public deployment and live-provider readiness remain outstanding.

The clean Java 25/Docker gate passed 553 backend tests and six Python checks locally on 10 November 2026, with no failures, errors or skips. [The Telegram handoff](docs/TELEGRAM_HANDOFF.md) records evidence and remaining live checks. Bot setup passed, and the maintainer confirmed real local Telegram OTP authentication plus the verified private profile on 10 November. CI and hosted readiness remain unverified. [API verification](docs/API_VERIFICATION.md) distinguishes isolated cross-API tests from live provider checks.

**Planned website:** [opennest.co.in](https://opennest.co.in). The backend target is Java 25 with Spring Boot 4.1.1; DNS, TLS, and hosting readiness remain unverified.

Search defaults to nationwide results with server-side pagination and sorting, plus filters for city, locality, rent, bedrooms/BHK, available rooms, amenities, sharing, and nearby location. The recruiter sandbox will use fabricated listings across multiple Indian cities, with callable contact actions disabled.

Telegram Bot API is the only authentication method. Start a browser-session challenge, share your own +91 contact with the bot, then enter its OTP in the initiating browser. Successful completion creates the verified account/session; it does not prove fresh SIM possession or property ownership. Follow the [Telegram setup and Postman guide](docs/TELEGRAM_HANDOFF.md). Browsing stays public; publication still requires validated images and the transactional listing quota.

Owners set how many people are allowed in the entire flat or each offered room. Listing responses return that capacity separately from rent and current availability.

## Start here

- [Project plan and decisions](docs/PROJECT_PLAN.md)
- [Phase 3 listing APIs, media configuration, limits, and verification](docs/LISTINGS_HANDOFF.md)
- [Backend dependency versions and verification status](docs/BACKEND_DEPENDENCIES.md)
- [Backend deployment modes, environment inputs, and guards](docs/BACKEND_CONFIGURATION.md)
- [Phase 1.4 database schema contract and enforcement boundaries](docs/SCHEMA_CONTRACT.md)
- [Flyway migration sequence, execution, and integration checks](docs/BACKEND_MIGRATIONS.md)
- [Persistence ownership, validation, generated values, and optimistic locking](docs/BACKEND_PERSISTENCE.md)
- [HTTP Problem Details, safe logging, and security defaults](docs/BACKEND_HTTP_ERRORS.md)
- [Backend store isolation and required container verification](docs/BACKEND_ISOLATION.md)
- [Phase 1 handoff, Java 25 CI, local startup, and complete verification](docs/BACKEND_HANDOFF.md)
- [Phase 2 identity setup, session contract, and verification](docs/IDENTITY_HANDOFF.md)
- [Phase 3A Telegram setup, Postman flow, and verification](docs/TELEGRAM_HANDOFF.md)
- [Step-by-step Postman guide for login, listings, and images](docs/POSTMAN_API_GUIDE.md)
- [Authenticated API verification matrix and OAuth removal audit](docs/API_VERIFICATION.md)
- [Backend modules and local build](backend/MODULE_CONTEXT.md)
- [Whole flats, individual rooms, pricing, and inventory](docs/LISTING_MODEL.md)
- [Pagination, sorting, and listing filters](docs/LISTING_SEARCH.md)
- [Authentication decisions and WhatsApp review](docs/AUTHENTICATION.md)
- [Deployment plan, verified constraints, and cost assumptions](docs/DEPLOYMENT_STACK.md)
- [AI development instructions](AGENTS.md)
- [Current implementation state](docs/ai-context/ARCHITECTURE_STATE.md)
- [Database schema and planned service invariants](docs/ai-context/SCHEMA_INDEX.md)
- [Implemented identity/listing and proposed interaction APIs](docs/ai-context/API_CONTRACTS.md)
- [Contributing](CONTRIBUTING.md)

## Backend bootstrap

Select a Java 25 JDK before using the supplied Maven Wrapper. From the repository root:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\backend\mvnw.cmd -version
.\backend\mvnw.cmd -f backend/pom.xml clean verify
```

The default build tests deployment policy, identity configuration/trust thresholds, client/schema guards, HTTP errors/security, virtual threads, startup, and module boundaries. It produces `backend/target/opennest-backend-0.0.1-SNAPSHOT.jar` with entry point `in.opennest.OpenNestApplication`. No-connection unit contexts explicitly exclude Flyway and Hibernate. Add `-Pschema-integration` for 51 real PostgreSQL/PostGIS migration/persistence checks on two separate targets; see the [migration guide](docs/BACKEND_MIGRATIONS.md).

Phase 1.8 adds `-Pisolation-integration` for independently configured primary/sandbox contexts, real PostGIS/Redis reads and writes, credential rejection, header handling, and migration/dependency failures. It requires Docker and has no external-store fallback. On 30 October 2026, the review-fix Java 25 build passed 130 default, 51 migration/persistence, and 23 isolation tests with no failures, errors, or skips on Linux amd64 Docker. See [isolation verification](docs/BACKEND_ISOLATION.md).

The server starts after Flyway/Hibernate validation. With authentication disabled, public listing reads remain available and owner routes stay denied. Enable Telegram through the [local helper](scripts/setup-telegram-local.ps1) and [setup guide](docs/TELEGRAM_HANDOFF.md). Exact frontend/API origins, isolated stores, migration privileges and redacted logging remain required. Spring Boot does not load Compose's .env directly; the local launcher loads it privately.

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

With default port settings, sandbox PostGIS uses `localhost:5433` and sandbox Redis uses `localhost:6380`. The command starts both primary and sandbox store pairs. Future production and sandbox backends must use separate connections, session stores, and cookies, and separate bot secrets where applicable. This Compose separation does not prove application isolation; the file is for local development, not production deployment.

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

## Frontend

Phase 5 adds the OpenNest public portal and isolated recruiter UI in `frontend/`. Use Node 24, then run `npm ci` and `npm run dev` from that directory. The default API target is http://localhost:8080; browsing is nationwide. See [frontend setup, browser tests and live-provider gates](docs/PHASE5_HANDOFF.md). No hosting or deployment is implied.
