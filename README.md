# OpenNest

An India-focused, zero-brokerage platform for finding flats and PG/hostel accommodation directly from owners. Chennai is the first featured city. OpenNest is an independent project, not affiliated with NoBroker.

**Status:** Phase 0 foundation. There is no application, authentication service, or deployed website yet.

## Start here

- [Project plan and decisions](docs/PROJECT_PLAN.md)
- [AI development instructions](AGENTS.md)
- [Current implementation state](docs/ai-context/ARCHITECTURE_STATE.md)
- [Proposed database](docs/ai-context/SCHEMA_INDEX.md)
- [Proposed API](docs/ai-context/API_CONTRACTS.md)
- [Contributing](CONTRIBUTING.md)

## Local infrastructure

Install Docker with Compose v2. Copy `.env.example` to `.env`, then replace both database password placeholders with different local passwords.

```powershell
Copy-Item .env.example .env
docker compose config --quiet
docker compose up -d --wait
docker compose ps
```

PostGIS is available at `localhost:5432`, and Redis at `localhost:6379`. Ports bind only to the local machine. The database has no application tables until Phase 1.

The optional recruiter infrastructure uses a separate PostgreSQL instance, credentials, volume, and Redis instance:

```powershell
docker compose --profile sandbox up -d --wait
```

Sandbox PostGIS uses `localhost:5433`; sandbox Redis uses `localhost:6380`. Future production and sandbox backends must use their respective connections and signing keys. This Compose file is for local development, not a production deployment.

```powershell
docker compose --profile sandbox stop
docker compose --profile sandbox down
```

Both commands preserve named volumes. `down --volumes` permanently deletes local data; do not use it to reset the recruiter demo. Changing passwords in `.env` does not rotate credentials in an existing PostgreSQL volume.

## Validation and delivery

Pull requests run Compose validation, infrastructure startup, PostGIS checks, Redis checks, and a persistence smoke test in GitHub Actions. CodeRabbit receives project-specific review instructions. Application tests and GHCR builds will be added when application code exists.

Named volumes and Redis AOF support recovery but are not backups or guarantees of zero data loss. Before public launch, test off-host backups and restoration, and measure actual memory use and recovery time.

## License

The project is intended to be open source. A license must be selected by the maintainer before inviting reuse; no license grant is implied yet.
