# OpenNest deployment stack

On 19 November 2026 the maintainer selected **Render for the Spring backend, Supabase for PostgreSQL/PostGIS, and Vercel for the Next.js frontend**. The planned public site is `https://opennest.co.in`. Domain control, production resources, DNS, TLS and hosted behavior still need verification.

For a hosted rehearsal and production setup, follow the [deployment guide](DEPLOYMENT_SETUP.md). For development or acceptance tests on a workstation, follow the [Docker Desktop guide](LOCAL_DOCKER_TESTING.md).

## Where each component runs

| Component | Target | Connection and ownership |
| --- | --- | --- |
| Public website | Vercel, Next.js from `frontend/`, Node 24 | Server-rendered listing pages read the Render API. Browsers call the same API for login and owner actions. Vercel receives frontend configuration only. |
| Application API | Render Docker Web Service, Java 25, Linux x86-64 | Build [the backend image](../backend/Dockerfile) with the repository root as its context. Spring enforces authorization, signup, publication and quotas. |
| Durable data | Dedicated Supabase PostgreSQL database with PostGIS in `public` | Spring connects over hostname-verified TLS. Flyway runs at startup and Hibernate validates the result. Local integration tests use PostgreSQL 17/PostGIS 3.5. Verify the hosted versions on the selected project. |
| Sessions and Telegram challenges | Dedicated Redis-compatible store | The setup guide uses Render Key Value on the API's private network, with internal authentication and `noeviction`. Exercise Spring Session, expiry and failure handling on the managed store. |
| Photos | Two private Cloudflare R2 buckets | The API signs short-lived URLs. The browser uploads to staging, the backend validates and finalizes, and readers fetch signed final images. |
| Identity and maps | Telegram Bot API and optional MapTiler | The hosted bot calls the Render webhook. The frontend gets an origin-restricted browser map key. Overpass enrichment stays disabled. |

The Dockerfile includes the Linux native AVIF decoder runtime and runs as a non-root user. The [repository workflows](../.github/workflows) verify code and local infrastructure. Hosting configuration, production promotion and recovery procedures still require operator setup. Keep source for the deployed revision available under AGPL-3.0-only and distribute the [third-party notices](THIRD_PARTY_NOTICES.md).

## Browser routing and isolation

Use HTTPS origins on the same site for sessions. The guide uses `https://opennest.co.in` on Vercel and proposes `https://api.opennest.co.in` on Render. These are distinct origins. Credentialed browser fetches require exact-origin CORS, an API-host-only Secure/HttpOnly/SameSite=Lax cookie and the synchronizer CSRF token on mutations. An unrelated Vercel preview and Render service URL can exercise public search, but cannot verify this cookie flow. The development LAN rewrite is local only. [Authentication contract](AUTHENTICATION.md)

Keep the real deployment in PRODUCTION/PRIMARY. Give any recruiter environment its own frontend configuration, backend, database, session store, credentials and media. Only the explicit development sandbox can switch personas or reset fabricated multi-city data. A public recruiter deployment still requires its own isolation and capacity checks. The main site serves listings across India regardless of hosting region. [Isolation and operator controls](PHASE4_HANDOFF.md)

## Decisions before public deployment

- Select service regions, plans and a monthly budget. Compare API-to-database latency, always-on compute, Key Value persistence, database backup retention, transfer, R2 operations and Vercel usage. Include domain renewal and configure billing alerts.
- Rehearse database migration and restore with the selected Supabase versions. Approve a restricted runtime role and a separately executed migration procedure. Today's backend has one datasource and rejects independent Flyway credentials.
- Verify DNS/TLS, origin and cookie handling, bot webhooks and direct R2 uploads. Protect session-creating requests at the API edge and verify alternate routes cannot bypass the limits.
- Measure JVM heap and native decoder RSS with real image sizes, search latency and database connections. Budget for up to five Hikari connections per running API instance, overlapping deploys and migration/admin connections. Test memory pressure, rejected Redis writes and alert delivery.
- Set recovery point and recovery time objectives. Restore into separate resources and rehearse a schema-compatible rollback. Keep private data out of previews, logs and release evidence. Complete moderation and retention decisions before collecting real user data.

The [Phase 6 acceptance criteria](PROJECT_PLAN.md#phase-6-task-boundaries) apply to launch. Each public release requires maintainer approval for the exact revision.
