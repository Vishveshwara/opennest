# OpenNest

OpenNest is a zero-brokerage platform for finding flats and PG/hostel accommodation directly from owners across India. OpenNest is an independent project, not affiliated with NoBroker.

**Status:** Phase 0 foundation. There is no application, authentication service, or deployed website yet.

The launch plan supports cities throughout India. Planned search will default to nationwide results with pagination and sorting, plus filters for city, locality, rent, bedrooms/BHK, available rooms, amenities, sharing, and nearby location. The recruiter sandbox will use fabricated listings across multiple Indian cities, with callable contact actions disabled.

Google sign-in through Spring Security is planned first; WhatsApp verification is a later option. Google account creation will not verify an owner's contact number. An authenticated owner will be able to create a PAUSED draft, upload and validate its photos, and explicitly publish it after separate contact verification and quota checks. PG prices will be displayed in INR per person per month; flat prices will be per whole property per month.

## Start here

- [Project plan and decisions](docs/PROJECT_PLAN.md)
- [Pagination, sorting, and listing filters](docs/LISTING_SEARCH.md)
- [Authentication decisions and WhatsApp review](docs/AUTHENTICATION.md)
- [Deployment plan, verified constraints, and cost assumptions](docs/DEPLOYMENT_STACK.md)
- [AI development instructions](AGENTS.md)
- [Current implementation state](docs/ai-context/ARCHITECTURE_STATE.md)
- [Proposed database](docs/ai-context/SCHEMA_INDEX.md)
- [Proposed API](docs/ai-context/API_CONTRACTS.md)
- [Contributing](CONTRIBUTING.md)

## Local infrastructure

Install Docker with Compose v2. Copy `.env.example` to `.env`, then fill both blank database password fields with different local passwords. Compose requires both values even when the sandbox profile is not enabled. Application-provider variables are reserved for later phases and are not used by the current datastore-only Compose setup.

```powershell
Copy-Item .env.example .env
docker compose config --quiet
docker compose up -d --wait
docker compose ps
```

With the default port settings and healthy containers, PostGIS will be available at `localhost:5432` and Redis at `localhost:6379`. Ports bind only to the local machine. The database has no application tables until Phase 1.

The optional recruiter infrastructure uses a separate PostgreSQL instance, credentials, volume, and Redis instance:

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

Pull requests run Compose validation, infrastructure startup, PostGIS checks, Redis checks, and a persistence smoke test in GitHub Actions. CodeRabbit receives project-specific review instructions. Application tests and GHCR builds will be added when application code exists.

Named volumes and Redis AOF support recovery but are not backups or guarantees of zero data loss. Before public launch, test off-host backups and restoration, and measure actual memory use and recovery time.

## License

OpenNest is licensed under the [GNU Affero General Public License, version 3 only](LICENSE) (`AGPL-3.0-only`). The full, unmodified license text is included in LICENSE. Third-party components retain their respective licenses.
