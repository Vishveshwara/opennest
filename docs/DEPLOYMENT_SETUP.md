# Deploy OpenNest on Render, Supabase and Vercel

This runbook starts with a gated rehearsal using an empty Supabase project and ends with the checks for promoting a production release. Use fabricated data for the rehearsal. The hosting targets are selected. A successful local build does not establish that any hosted service, domain or credential is ready.

## 1. Choose the revision and origins

1. Record the full Git commit SHA. Inspect the completed Foundation, Backend and Frontend checks for that SHA. The [backend Dockerfile](../backend/Dockerfile) packages with tests skipped, so the separate Java and real-store test results matter. The [local testing guide](LOCAL_DOCKER_TESTING.md) has the equivalent commands.
2. Pick a Render region near the Supabase database and place Render Key Value in the same Render workspace and region as the API. Check latency from the Vercel server-rendering region as well as from an Indian browser. Review recurring compute, backup, persistence, bandwidth and image-operation costs. Choose plans that can meet the application's availability requirements during idle periods and deploys. [Render free service limitations](https://render.com/docs/free) · [Supabase backups](https://supabase.com/docs/guides/platform/backups) · [Vercel plans](https://vercel.com/docs/plans)
3. Reserve a pair of same-site HTTPS origins. The examples use the planned website `https://opennest.co.in` and a proposed API origin `https://api.opennest.co.in`. Substitute your verified pair throughout. Use separate same-site hosts and credentials for an authentication rehearsal. Protect the rehearsal from general access before its first build and use a branch pinned to the approved SHA for initial service creation.
4. Record who can change DNS, read secrets, approve a release and restore the database. The public website uses PRODUCTION/PRIMARY. The recruiter environment requires independent resources and DEVELOPMENT/SANDBOX policy. Never attach preview builds or a recruiter service to the real-data stores.

## 2. Prepare Supabase PostgreSQL

1. Create a dedicated project and save the database password privately. Record the PostgreSQL version and available PostGIS version. PostgreSQL 17/PostGIS 3.5 is the repository's tested local baseline. Rehearse the migrations and spatial queries on the actual hosted versions.
2. In the project's Data API settings turn **Enable Data API** off before creating application tables. OpenNest accesses PostgreSQL through Spring. Its Flyway migrations create private account, contact and receipt tables in `public`. Verify that REST/GraphQL clients and frontend credentials have no access to OpenNest data. Keep the tables out of Realtime publications. Vercel needs no Supabase keys. [Supabase Data API settings](https://supabase.com/docs/guides/api/securing-your-api)
3. In SQL Editor inspect the target. On a *fresh, dedicated database*, if the first query returns no row, run the installation statement as a project database administrator. Run the queries again and confirm the extension schema is `public`. V1 explicitly uses that schema. If PostGIS already lives elsewhere or the schema contains unrelated application tables, use a fresh rehearsal project or plan a reviewed migration with the data owner. Preserve existing data and migration history. [OpenNest migrations](BACKEND_MIGRATIONS.md) · [Supabase PostGIS](https://supabase.com/docs/guides/database/extensions/postgis)

   ```sql
   SELECT extversion, extnamespace::regnamespace AS extension_schema
   FROM pg_extension WHERE extname = 'postgis';
   ```

   ```sql
   CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;
   SELECT current_database(), current_setting('server_version'), public.postgis_version();
   ```

4. Give the rehearsal its own password-authenticated database login. For example, on a new project with the standard database name, run the following once as an administrator, then set a generated password for the login through a trusted password prompt such as `\password opennest_rehearsal` in `psql`. The role creates and owns the OpenNest objects for this disposable rehearsal.

   ```sql
   CREATE ROLE opennest_rehearsal LOGIN;
   GRANT CONNECT ON DATABASE postgres TO opennest_rehearsal;
   GRANT USAGE, CREATE ON SCHEMA public TO opennest_rehearsal;
   ```

5. Open **Connect → Session pooler** and copy the exact hostname, port (normally 5432) and database name. For the login above, the pooled username is `opennest_rehearsal.<project-ref>`. Supply the raw password separately. This IPv4 session connection suits the persistent Spring service and Flyway's connection semantics. The transaction pooler on 6543 has different session and prepared-statement behavior. [Supabase connection methods](https://supabase.com/docs/guides/database/connecting-to-postgres)
6. In Database Settings enable SSL enforcement and download the CA certificate for the selected endpoint. Save its PEM contents for the Render secret file in step 4. Restrict database network access to the API's outbound addresses and approved administration/migration clients where supported. [Supabase TLS verification](https://supabase.com/docs/guides/platform/ssl-enforcement) · [Render outbound addresses](https://render.com/docs/outbound-ip-addresses)

### Separate the migration and application logins

The login in step 4 can create and alter the objects it owns. Use it only for the disposable rehearsal. For production, create two dedicated logins. Give the migration login ownership of the application objects. Give the runtime login `CONNECT` on the database, `USAGE` on `public`, read access to the Flyway history and owner projection, and the table privileges required by enabled operations. Keep schema creation, object ownership and membership of the migration role out of the runtime login.

1. Before starting the public service, use a private release runner to apply the exact revision's [Flyway resources](../backend/src/main/resources/db/migration), including the per-script `.sql.conf` files, with the migration login. For a fresh rehearsal you can start a separate copy of the backend on a private network with its port unpublished and provider features disabled. Startup runs Flyway and Hibernate. Check the history query in step 4 of this guide, then stop that copy. Use a separately reviewed migration job for subsequent live releases. OpenNest supplies neither a migration-only command nor separate startup credentials. It rejects direct `SPRING_DATASOURCE_*`, `SPRING_FLYWAY_*` and ORM overrides. [Backend configuration](BACKEND_CONFIGURATION.md)
2. After migration, grant the runtime login access to the application objects as their owner. For example, `GRANT SELECT ON public.flyway_schema_history, public.listing_owner_profiles TO opennest_runtime` covers history validation and the public owner view. Review application-table grants for the release, including inserts into `listing_records` by the V11 trigger and writes to the object deletion queue. Avoid schema-wide grants on Supabase-managed objects.
3. Connect as the runtime login and run the following query. Expect `false`, `true`, `false` in that order. Review inherited and PUBLIC privileges if the answer differs. On Render, use only this login's project-suffixed username and password. Verify startup, authenticated writes, search, image finalization and cleanup on the protected rehearsal before admitting real data.

   ```sql
   SELECT has_schema_privilege(current_user, 'public', 'CREATE') AS can_create_objects,
          has_table_privilege(current_user, 'public.flyway_schema_history', 'SELECT') AS can_validate,
          has_table_privilege(current_user, 'public.flyway_schema_history', 'UPDATE') AS can_edit_history;
   ```

On 19 November 2026 a local PostgreSQL 17/PostGIS 3.5 rehearsal applied V1–V11 with a migration login, then started the same image under PRODUCTION/PRIMARY with a separate runtime login. Nationwide search returned 200 and a transactional user insert succeeded and was rolled back. The runtime login lacked schema CREATE and history UPDATE privileges. Repeat these checks against the actual Supabase pooler, CA and role grants.

## 3. Create the session store on Render

In **New → Key Value**, choose the API's region, an appropriate memory/persistence plan and the `noeviction` policy. Keep external access disabled. In **Info → Connections**, enable internal authentication, wait for the restart, then copy the authenticated **internal** connection details. Assign its host, port, username and raw password to the four `REDIS_*` settings in step 4. The API uses database 0 and requires a password in production. Its client accepts discrete settings for this private TCP connection. [Render Key Value](https://render.com/docs/key-value)

New Render instances use Redis-compatible Valkey. Exercise session creation, expiry, logout, Lua-backed Telegram challenges and outage behavior on this service before launch. Monitor dataset memory, resident memory and rejected writes. Production and recruiter sessions need separate instances.

## 4. Configure the Render web service

In **New → Web Service**, connect the repository. Creating the service starts a build, so fill in the settings, CA and secrets first. Use the same configuration for a rehearsal with its own domains and disposable stores. [Render Docker builds](https://render.com/docs/docker) · [Web service port binding](https://render.com/docs/web-services#port-binding)

| Render field | Value |
| --- | --- |
| Language | Docker |
| Root Directory | Leave blank (repository root) |
| Dockerfile Path | `./backend/Dockerfile` |
| Docker Build Context Directory | `.` (repository root). The Dockerfile copies from `backend/`, `docs/` and the root license. |
| Docker Command | Leave blank to use the image entry point. The bundled native decoder requires Linux amd64. |
| Auto-Deploy | Off. Use **Manual Deploy → Deploy a specific commit** for later approved releases. |
| Health Check Path | Leave unset for Render's TCP probe. Verify application readiness with the search request below. OpenNest has no public health endpoint. |

Under **Environment → Secret Files**, add `supabase-ca.crt` with the PEM certificate. Render mounts it at `/etc/secrets/supabase-ca.crt`. The image runs as UID 10001, GID 1000. Set the following **service environment** values before building. [Render runtime secret files](https://render.com/docs/docker-secrets#accessing-secret-files-at-runtime) · [Render health checks](https://render.com/docs/health-checks)

| Variable(s) | Value for the real backend |
| --- | --- |
| `SERVER_PORT` | `10000`, matching Render's default `PORT`. Set both consistently if customizing the port. Spring reads `SERVER_PORT`. |
| `OPENNEST_ENVIRONMENT`, `OPENNEST_DATA_MODE` | `PRODUCTION`, `PRIMARY` |
| `OPENNEST_BACKEND_ORIGIN`, `OPENNEST_FRONTEND_ORIGIN` | Exact API and website HTTPS origins from step 1, without paths or trailing slash |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | The session pooler hostname, session port and actual database name from Supabase |
| `DB_USER`, `DB_PASSWORD` | The environment's pooled login including project suffix, and its password |
| `DB_SSL_ROOT_CERT` | `/etc/secrets/supabase-ca.crt` enables pgJDBC `verify-full` for runtime and migration connections |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_USER`, `REDIS_PASSWORD` | The four authenticated private Key Value fields from step 3 |
| `OPENNEST_TELEGRAM_AUTH_ENABLED`, `OPENNEST_STORAGE_ENABLED` | Start with both `false` for the browsing check. Step 6 enables each integration separately. |
| `OPENNEST_TELEGRAM_POLLING_ENABLED`, `OPENNEST_DEVELOPMENT_AUTH`, `OPENNEST_PERSONA_SWITCHING`, `OPENNEST_DEMO_RESET`, `OPENNEST_DEMO_TELEMETRY` | `false` |

Leave sandbox credentials and `OPENNEST_DEV_LAN_ORIGINS` out of the service. Use the secret fields for each service individually. Avoid copying a local `.env` or pasting connection URLs into a hostname field.

Deploy the approved rehearsal revision. Once it accepts TCP connections, run this from a client allowed to reach the rehearsal. Replace the example API origin with its actual HTTPS address.

```powershell
$apiOrigin = 'https://api.opennest.co.in'
$search = Invoke-WebRequest -UseBasicParsing "$apiOrigin/api/v1/listings/search?page=0&size=1"
$search.StatusCode
$search.Headers['Cache-Control']
$search.Content | ConvertFrom-Json | Select-Object page, size, totalElements, items
curl.exe -s -o NUL -w '%{http_code}' "$apiOrigin/api/v1/listings/mine"
```

Expect 200, `no-store` and a paginated result from the first request, and 401 from the anonymous owner request. An empty database returns empty `items`. Verify the migration history in the Supabase SQL Editor. Look for successful versioned rows through 11 for this checkout.

```sql
SELECT version, description, success
FROM public.flyway_schema_history
WHERE version IS NOT NULL
ORDER BY installed_rank;
```

## 5. Configure Vercel and connect the domains

1. Import the same repository into Vercel. Choose the **Next.js** preset, **Root Directory** `frontend`, **Node.js Version** `24.x`, **Install Command** `npm ci` and **Build Command** `npm run build`. Leave the output directory at the framework default. The build prepares browser codecs and license notices. Listing pages use server rendering. [Vercel monorepos](https://vercel.com/docs/monorepos) · [Vercel Node versions](https://vercel.com/docs/functions/runtimes/node-js/node-js-versions)
2. Set the following frontend variables in the matching Vercel environment. A preview uses its own API/site pair. Rebuild after changing the release configuration. Keep database, Telegram, session and R2 secrets exclusively on the backend.

   | Variable | Example or source |
   | --- | --- |
   | `OPENNEST_UI_MODE` | `PRIMARY` |
   | `OPENNEST_SITE_ORIGIN` | `https://opennest.co.in` |
   | `OPENNEST_API_ORIGIN` | `https://api.opennest.co.in` |
   | `OPENNEST_SOURCE_REVISION` | Exact published 40-character lowercase Git SHA for this release |
   | `OPENNEST_MAPTILER_KEY` | Optional origin-restricted browser key. Leave empty for the map fallback. |

3. For a production project, configure **Settings → Environments → Production → Branch Tracking** to disable automatic assignment of custom production domains before connecting live traffic. Stage the production build and promote the approved deployment after verification. Keep previews isolated. [Vercel deployment promotion](https://vercel.com/docs/deployments/promoting-a-deployment)
4. Add the website hostname in Vercel **Domains**, and add the API hostname in Render **Settings → Custom Domains**. Enter the records each dashboard displays at the authoritative DNS provider. Wait for both providers to verify ownership and issue certificates. Test both HTTPS hosts. After validating the API custom domain, disable its alternate `onrender.com` subdomain where appropriate and verify the API edge controls on every reachable route. [Vercel domains](https://vercel.com/docs/domains/working-with-domains/add-a-domain) · [Render custom domains](https://render.com/docs/custom-domains)
5. Open the website and run a nationwide search. In browser developer tools verify the exact API origin, allowed frontend origin and `no-store` responses. For authenticated traffic, the browser must send credentials to the API. Host-only cookies on unrelated `vercel.app` and `onrender.com` sites cannot establish the planned same-site flow. Keep shared caching off for application data and signed media.

## 6. Enable login and uploads

**Telegram.** Before enabling sessions on a publicly reachable API, enforce per-client and aggregate limits at the API edge for `GET /api/v1/auth/csrf` and `POST /api/v1/auth/telegram/challenges`. Verify excess requests receive 429 before a Redis session is allocated, including through alternate origin routes. The repository has no hosted edge configuration.

Use a dedicated deployment bot. Set `TELEGRAM_BOT_USERNAME` (without `@`), `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET` (32–256 characters from letters, digits, underscore and hyphen), and an independently generated `TELEGRAM_OTP_HMAC_KEY` (at least 32 UTF-8 bytes). Set `OPENNEST_TELEGRAM_AUTH_ENABLED=true`, keep polling false and redeploy the same revision. From a private operator client call the Bot API's `setWebhook` with these fields. Inject secrets from private configuration and keep the bot token, request URL and secret out of shell history, logs and screenshots.

| Bot API field | Value |
| --- | --- |
| `url` | `https://api.opennest.co.in/api/v1/webhooks/telegram`, adjusted to the verified API origin |
| `secret_token` | Exact backend `TELEGRAM_WEBHOOK_SECRET`. Telegram sends it as `X-Telegram-Bot-Api-Secret-Token`. |
| `allowed_updates` | `["message"]` |

Check `getWebhookInfo` for the expected URL and delivery errors. Complete a private +91 self-contact login in the original browser, refresh CSRF after rotation, check the caller's own profile, log out with CSRF and confirm anonymous access afterward. Include returning login and an invalid or expired code in the rehearsal. A TCP or listing check cannot exercise Redis-backed login. [Telegram Bot API](https://core.telegram.org/bots/api#setwebhook) · [OpenNest login and OTP steps](TELEGRAM_HANDOFF.md)

**Listing photos.** Create separate private R2 staging and final buckets and scope backend object read/write credentials to them. Configure exact frontend-origin CORS for PUT to staging and GET/HEAD where needed. Allow the signed headers including `Content-Type` and `If-None-Match`, and expose `ETag`. Set `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_KEY`, `R2_STAGING_BUCKET`, `R2_FINAL_BUCKET` and `OPENNEST_STORAGE_ENABLED=true` on Render. Redeploy and exercise draft → browser conversion → direct signed PUT → completion → explicit publication with the required image segments. Check the final signed image loads, expires and refreshes. Validate removal and eventual cleanup with rehearsal objects. [Media contract and bounds](LISTINGS_HANDOFF.md) · [R2 CORS](https://developers.cloudflare.com/r2/buckets/cors/)

## 7. Release, observe and roll back

| Before promotion | Passing evidence to record without private data |
| --- | --- |
| Browser and API | HTTPS on both origins, nationwide search, expected pagination, anonymous owner request gets 401, credentialed original-session login and CSRF/logout pass. Public responses omit private contact and precise location. |
| Isolation and providers | Production rejects sandbox secrets/flags. Preview credentials cannot reach production databases, sessions or buckets. Real R2 validation works on the chosen instance size. Check map rendering/attribution if enabled. |
| Capacity and recovery | Exercise load and alert delivery for API latency/errors, JVM heap/native memory, database pool and slow queries, Redis memory/rejected writes and storage failures. Restore a backup to a fresh protected database and time recovery. Invalidate restored sessions unless revocation and expiry continuity are proven. |
| Release record | Record the approved SHA, Render deployment ID, Vercel deployment ID, Flyway version, corresponding-source link and previous known-good releases. Serialize promotions. |

Confirm schema compatibility with both application revisions before switching traffic. On failure, hold promotion. Restore the previous compatible Render release and Vercel deployment or use a reviewed forward fix. A code rollback leaves database migrations and uploaded objects in place. Use a separately authorized data restore only after assessing writes since the backup. Re-run the smoke checks after recovery. [Render deployments](https://render.com/docs/deploys) · [Vercel rollback](https://vercel.com/docs/instant-rollback)

## Troubleshooting

| Symptom | First check |
| --- | --- |
| Docker build cannot copy files or Render sees no open port | Use the repository root as context and `backend/Dockerfile` as the path. Check `SERVER_PORT` and startup migration success. |
| Startup fails before HTTP is available | Check pooler mode, project-suffixed username, database name and password, the mounted CA's filename/readability, and the certificate hostname. Check PostGIS schema and Flyway history privately. Preserve TLS and migration validation. |
| Search works, login returns 503 | Check authenticated internal Key Value fields, region, memory/rejected writes and bot delivery. The client can connect to Redis lazily. |
| Login loops in a hosted browser | Inspect same-site HTTPS origins, exact credentialed CORS, API-host cookie, original browser session and refreshed CSRF after login. Verify webhook URL, secret and allowed updates. |
| Upload fails or map is blank | Check browser bucket CORS and signed headers, private bucket credentials, native decoder memory, or map-key origin restrictions. Keep presigned URLs and private inputs out of reports. |
