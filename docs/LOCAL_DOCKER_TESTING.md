# Test OpenNest locally with Docker Desktop

Start here to browse OpenNest on a Windows x86-64 workstation. Docker Desktop runs PostgreSQL/PostGIS, Redis and, in step 3, the Java API. Next.js runs on the host. The first smoke test needs no cloud account, bot token, map key or image bucket. The automated browser test later in this guide exercises login and uploads with controlled providers.

## 1. Check the tools

Install Docker Desktop, enable its WSL 2 backend and select **Linux containers**. Wait until the engine is running. Install Node 24 for the UI. A host JDK is optional for the containerized API. The Maven verification suites and the Windows Telegram launcher require an x86-64 Java 25 JDK. Use Python 3 for the repository checks. The bundled native media libraries target Linux or Windows x86-64. [Docker Desktop for Windows](https://docs.docker.com/desktop/setup/install/windows-install/)

Start PowerShell in the repository root (the directory containing `docker-compose.yml`). Check the versions:

```powershell
docker version
docker compose version
docker info --format '{{.OSType}}/{{.Architecture}}'
node --version
npm.cmd --version
```

Expect a reachable Docker **Server**, Compose v2 and `linux/x86_64` or `linux/amd64`. Node should report `v24.*`. For the host tests also run:

```powershell
java --version
.\backend\mvnw.cmd --version
python --version
```

Both Java version checks must report 25. If they differ, select the installed JDK with `JAVA_HOME` and put its `bin` directory first on the current shell's `Path`.

## 2. Configure and start the local stores

Copy the template only on first use:

```powershell
if (!(Test-Path -LiteralPath .env)) { Copy-Item -LiteralPath .env.example -Destination .env }
```

In the private `.env`, set **both** `DB_PASSWORD` and `SANDBOX_DB_PASSWORD` to different locally generated passwords. Use plain single-line values that are safe for dotenv interpolation, such as random hex. Keep existing passwords when reusing volumes. Leave local Redis credentials and database CA paths empty. The checked-in Compose file publishes Redis only on loopback. Keep provider features disabled for the browsing test. If you already have bot settings, the container recipe below passes only the database settings it needs.

| Service | Default host address | Address inside its Docker network |
| --- | --- | --- |
| Primary PostGIS | `127.0.0.1:5432` | `postgres:5432` |
| Primary Redis | `127.0.0.1:6379` | `redis:6379` |
| Optional sandbox PostGIS | `127.0.0.1:5433` | `sandbox-postgres:5432` |
| Optional sandbox Redis | `127.0.0.1:6380` | `sandbox-redis:6379` |

If a host port is occupied, change `DB_PORT`, `REDIS_PORT`, `SANDBOX_DB_PORT` or `SANDBOX_REDIS_PORT` in the private file. For example, use `DB_PORT=55432` if a Windows PostgreSQL service already owns 5432. The internal service ports in the right column stay fixed. Keep primary and sandbox host ports distinct.

```powershell
docker compose config --quiet
docker compose up -d --wait --wait-timeout 180 postgres redis
docker compose ps
docker compose exec -T postgres psql -U opennest -d opennest -Atc 'SELECT public.postgis_version()'
docker compose exec -T redis redis-cli -e ping
```

Expect two healthy services, a PostGIS version string and `PONG`. Substitute your local user/database in the SQL command if you changed the template defaults. Compose creates persistent named volumes. Backend startup creates the application schema.

## 3. Start the API in Docker

The [checked-in Compose file](../docker-compose.yml) defines datastores. This PowerShell snippet supplies an additional `api` service to Compose on standard input. It uses the same [Dockerfile](../backend/Dockerfile) and repository-root build context as Render, but joins the local primary network. Keep this PowerShell terminal open for the stop commands.

```powershell
$localApi = @'
services:
  api:
    build:
      context: .
      dockerfile: backend/Dockerfile
    platform: linux/amd64
    environment:
      OPENNEST_ENVIRONMENT: DEVELOPMENT
      OPENNEST_DATA_MODE: PRIMARY
      OPENNEST_TELEGRAM_AUTH_ENABLED: "false"
      OPENNEST_STORAGE_ENABLED: "false"
      OPENNEST_BACKEND_ORIGIN: http://localhost:8080
      OPENNEST_FRONTEND_ORIGIN: http://localhost:3000
      SERVER_PORT: "8080"
      DB_HOST: postgres
      DB_PORT: "5432"
      DB_NAME: ${DB_NAME:-opennest}
      DB_USER: ${DB_USER:-opennest}
      DB_PASSWORD: ${DB_PASSWORD:?Set DB_PASSWORD}
      REDIS_HOST: redis
      REDIS_PORT: "6379"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    ports:
      - "127.0.0.1:8080:8080"
    networks: [primary]
'@

$localApi | docker compose -f docker-compose.yml -f - config --quiet
$localApi | docker compose -f docker-compose.yml -f - up --build -d --wait --wait-timeout 180 api
```

The first build downloads Java images, Maven dependencies and native libraries. Compose can report the API container running while Spring finishes startup. Retry the following read after a few seconds until it returns 200:

```powershell
$search = Invoke-WebRequest -UseBasicParsing 'http://localhost:8080/api/v1/listings/search?page=0&size=1&sort=NEWEST'
$search.StatusCode
$search.Headers['Cache-Control']
$search.Content | ConvertFrom-Json | Select-Object page, size, totalElements, totalPages
docker compose exec -T postgres psql -U opennest -d opennest -Atc 'SELECT max(version::integer) FROM public.flyway_schema_history WHERE success AND version IS NOT NULL'
curl.exe -s -o NUL -w '%{http_code}' http://localhost:8080/api/v1/listings/mine
```

Expect HTTP 200, `no-store`, page 0, size 1, latest version 11 on this checkout, and 401 on the anonymous owner request. An empty primary database returns zero listings. Omitting the city searches nationwide. The backend also validates the mapped schema at startup.

On 19 November 2026 these checks passed on Windows with Docker Desktop's Linux x86-64 engine, an isolated Compose project, fresh volumes and alternate published database ports. Both PostGIS instances answered, both Redis instances returned `PONG`, and the Next.js home page loaded against the containerized API.

## 4. Open the website

In a **second PowerShell window**, start in the repository root:

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd run dev -- --hostname localhost
```

Visit `http://localhost:3000`. Search with the city blank, then try a city and change the rent sort. Check that a fresh database shows the empty result state and that the page loads without signing in. The frontend defaults to `OPENNEST_UI_MODE=PRIMARY`, `OPENNEST_API_ORIGIN=http://localhost:8080` and `OPENNEST_SITE_ORIGIN=http://localhost:3000`. If overriding these, use a private `frontend/.env.local` based on [the frontend template](../frontend/.env.example). Use `localhost` consistently for browser URLs. The root `.env` contains backend secrets.

## 5. Test authenticated behavior

**Real Telegram on a workstation.** Use a dedicated local bot if you need to test actual Telegram contact sharing and delivery. Stop the `api` container first to release 8080, leave PostGIS and Redis running, then start the host Java launcher in the repository root:

```powershell
$localApi | docker compose -f docker-compose.yml -f - stop api
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-telegram-local.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Build
```

The setup helper reads the token privately, checks the bot and current webhook state, and enables development polling. The launcher loads the private root `.env` into its own process. Confirm `OPENNEST_ENVIRONMENT=DEVELOPMENT`, `OPENNEST_DATA_MODE=PRIMARY` and host-reachable store ports before starting it. In the browser request a challenge, share your own +91 contact in private chat and enter the OTP in the initiating browser. For new users complete the name prompt. Check your private profile, log out and verify the owner route requires login again. Keep tokens, OTPs, contacts and cookies out of test evidence. [Telegram setup](TELEGRAM_HANDOFF.md) · [Postman walkthrough](POSTMAN_API_GUIDE.md)

**Photos and maps.** The Docker browsing recipe disables storage. The browser acceptance suite below tests conversion, signed uploads and finalization using controlled storage. For a live-provider check, use the host launcher with dedicated development R2 buckets/credentials and exact localhost CORS. An optional browser-restricted MapTiler key belongs in the frontend environment. [R2 setup](LISTINGS_HANDOFF.md) · [Map and LAN verification](PHASE5_HANDOFF.md)

**Recruiter sandbox.** To bring up its separate store pair, run:

```powershell
docker compose --profile sandbox up -d --wait --wait-timeout 180
docker compose exec -T sandbox-postgres psql -U opennest_sandbox -d opennest_sandbox -Atc 'SELECT current_database(), public.postgis_version()'
docker compose exec -T sandbox-redis redis-cli -e ping
```

For manual persona tests, start a separate backend on 8081 using only its sandbox credentials, `OPENNEST_ENVIRONMENT=DEVELOPMENT`, `OPENNEST_DATA_MODE=SANDBOX`, `OPENNEST_PERSONA_SWITCHING=true` and Telegram disabled. The dedicated backend seeds fabricated multi-city records on first startup. Configure a separate frontend on 3001 for that API and open `http://localhost:3001/recruiter-sandbox`. The PRIMARY launcher accepts only DEVELOPMENT/PRIMARY. For simultaneous UIs, build once and run two `npm run start` processes with their respective configs. The automated browser suite creates both environments for you. [Sandbox startup and persona flow](PHASE4_HANDOFF.md#start-the-dedicated-sandbox) · [Frontend process settings](PHASE5_HANDOFF.md#run-locally)

## 6. Run the automated checks

Use fresh PowerShell windows from the repository root, with Java 25 and Linux Docker running. Testcontainers creates disposable PostGIS and Redis instances. Leave external schema-test overrides unset and keep real bot, R2 and production credentials out of test process environments. Stop manual frontend processes before the browser test so ports 3000/3001 are available.

**Backend and repository checks**

```powershell
python -m unittest discover --start-directory .github/scripts --pattern '*_test.py'
python .github/scripts/verify_backend.py
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pschema-integration,isolation-integration' clean verify
python .github/scripts/verify_backend.py --reports
Test-Path -LiteralPath backend/target/listing-query-plans.txt
```

Expect Maven `BUILD SUCCESS`, successful report verification and `True` for the plan file. Reports live in `backend/target/surefire-reports/` and `backend/target/failsafe-reports/`. Running only the default Maven profile omits the real-store suites. [Backend workflow](../.github/workflows/backend.yml)

**Frontend and browser acceptance**

```powershell
Push-Location frontend
npm.cmd ci
npm.cmd run typecheck
npm.cmd run lint
npm.cmd test
npm.cmd run build
npm.cmd run check:dependencies
npx.cmd playwright install chromium
Pop-Location
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pisolation-integration' '-Dtest=OpenNestApplicationTests' '-Dit.test=FrontendBrowserHarness' verify
```

Check each command's exit status before continuing. The production build supplies the frontend assets for Playwright. The Java harness starts both backends, their isolated stores and controlled providers, then invokes Playwright against the two Next.js servers. Launching Playwright alone skips that setup and the config rejects it. [Frontend workflow](../.github/workflows/frontend.yml)

## 7. Stop and diagnose

Stop the host frontend or Java launcher with Ctrl+C in its terminal. From the first window, stop the containerized API and both store pairs while retaining their data:

```powershell
$localApi | docker compose -f docker-compose.yml -f - --profile sandbox down
```

Omitting `--volumes` preserves named volumes for the next run. Do not delete a volume to resolve a password or migration mismatch.

| Symptom | Check |
| --- | --- |
| Cannot connect to Docker or Testcontainers | Start Docker Desktop, select the Linux engine, then retry `docker info`. Check the active context with `docker context show`. |
| Password required, bind error or authentication failed | Fill both database passwords. Check the host port table, other running processes and existing volume credentials. Changing the template or `.env` does not rotate the stored database password. |
| API connection refused or startup stops | Run `$localApi \| docker compose -f docker-compose.yml -f - ps -a` and inspect bounded local logs for the affected service. Verify database name, PostgreSQL readiness, migration history and the container's `postgres:5432` target. |
| Browser loads but search fails | Check the direct search URL first. Check the configured API and site origins for port or hostname differences, restart Next.js after env changes, and look for `no-store` on the API response. |
| Login or upload unavailable | The browsing recipe explicitly turns these off. Use the controlled browser suite or the development-provider setup above. |
| Browser harness cannot start a server | Free ports 3000/3001, install Chromium, then build the frontend before running the harness. |

For a hosted rehearsal, switch to the [Render, Supabase and Vercel deployment guide](DEPLOYMENT_SETUP.md).
