# OpenNest Phase 2 handoff

Phase 2 implements Google OIDC, backend-owned Redis sessions, CSRF/logout, private profiles, and approved trust badges. V1/V2 migrations and the six-table schema are unchanged. The backend remains Java 25, Spring Boot 4.1.1, and Spring Modulith 2.1.1. OAuth2 and Spring Session dependencies follow the Boot BOM.

## Enable Google login

Supply settings through the launching process or IDE. Spring Boot does not load the Compose `.env` file. Keep credentials out of Git, logs, and chat.

| Setting | Primary deployment | Sandbox deployment |
| --- | --- | --- |
| Enable login | `OPENNEST_GOOGLE_ENABLED=true` | `OPENNEST_GOOGLE_ENABLED=true` |
| Client ID | `GOOGLE_CLIENT_ID` | `SANDBOX_GOOGLE_CLIENT_ID` |
| Client secret | `GOOGLE_CLIENT_SECRET` | `SANDBOX_GOOGLE_CLIENT_SECRET` |
| API origin | `OPENNEST_BACKEND_ORIGIN` | `SANDBOX_BACKEND_ORIGIN` |
| Frontend origin | `OPENNEST_FRONTEND_ORIGIN` | `SANDBOX_FRONTEND_ORIGIN` |

Origins contain only a scheme, host, and optional port. Paths, trailing slashes, user information, queries, fragments, and wildcards are rejected. HTTPS is required in production. Development permits HTTP only on loopback hosts. Development defaults are `http://localhost:8080` and `http://localhost:3000`; sandbox defaults are ports 8081 and 3001. Keep frontend and API within the same browser site so SameSite=Lax cookies work. Register the exact selected API origin plus `/login/oauth2/code/google` with Google.

Primary and sandbox OAuth registrations and callback origins must differ. Production rejects sandbox OAuth secrets even when Google login is disabled. Existing datastore and demo guards still apply. Direct Spring OAuth2, session, servlet-session, and forwarded-header settings are reserved. Deployment enums and the Google enable flag are canonicalized before security conditions run.

When `OPENNEST_GOOGLE_ENABLED=false`, all identity routes are unavailable and the deny-all baseline remains active. Blank credentials never enable a substitute login. Enabled login requires a working selected PostgreSQL database and Redis store. Form login, Basic authentication, a generated password, account linking, phone verification, WhatsApp routes, and sandbox persona login remain unavailable.

## Browser and session contract

Use the five routes in [authentication decisions](AUTHENTICATION.md#implemented-routes-when-google-is-enabled). Request private APIs with credentials from the exact configured frontend origin. Fetch `/api/v1/auth/csrf` before a mutation and again after login. Logout requires POST with `X-CSRF-TOKEN` and returns 204. Unauthorized profile access returns 401. Known database or Redis outages return safe 503 Problem Details.

The session cookie is host-only, HttpOnly, SameSite=Lax, and scoped to `/`. Production uses `__Host-OPENNEST_SESSION` with Secure. Development uses `OPENNEST_SESSION`; sandbox uses `OPENNEST_SANDBOX_SESSION`. HTTPS origins also receive Secure cookies in development. Cookie names cannot select a database. Redis keys use `opennest:<environment>:<mode>:sessions`. The initiating session holds transient OAuth state, nonce, and PKCE verifier. Login replaces that session and retains only the OpenNest principal, not provider tokens.

Idle expiry is 30 minutes. Absolute expiry is 24 hours after authentication, regardless of activity. An authenticated principal from another deployment realm is rejected and its session invalidated. Logout invalidates Redis state and expires the cookie. Authenticated data, authentication responses, and sandbox responses use no-store. Fixed error responses preserve CORS headers already validated for the configured frontend.

`IdentityAccounts.hasVerifiedContact(UUID)` exposes only a boolean for future publication enforcement. Google users start without a phone. Phase 3 must require this check at publication and reactivation. Phase 2 creates no listing route or phone-verification bypass.

## Verify Phase 2

Select Java 25 and a Linux amd64 Docker engine, then run from the repository root:

```powershell
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pschema-integration,isolation-integration' clean verify
python .github/scripts/verify_backend.py --reports
python -m unittest discover --start-directory .github/scripts --pattern '*_test.py'
```

On Linux, use `bash backend/mvnw` and `python3`. Existing [Phase 1 commands](BACKEND_HANDOFF.md#local-verification) remain valid. The report verifier now requires trust/configuration suites and `IdentityAuthenticationIT`, in addition to the existing suites. It rejects missing suites, failures, errors, skips, and packaged test fixtures.

The controlled OIDC provider runs only in test sources. Tests exercise authorization-code exchange, PKCE, signed ID-token checks, browser-bound state, denial/replay, concurrent signup, email collisions, cookies, session rotation/expiry, CSRF/logout, exact CORS, database/Redis failures, and sandbox-session replay against production. Two real PostGIS/Redis pairs use fabricated accounts and credentials. Production artifacts contain neither the provider nor test configuration.

Local verification passed on 1 November 2026 under Temurin Java 25.0.4.1 and Linux amd64 Docker: 205 default tests, 51 schema/persistence tests, 23 datastore-isolation tests, and 19 identity integration tests. All 298 passed with no failures, errors, or skips. The report/artifact verifier passed, including test-fixture exclusion and unchanged V1/V2 migration contents. All six Python report-gate tests and context/wrapper checks passed.

Inspect current PR checks separately before claiming CI success. Live Google smoke testing requires an actual OAuth registration and remains unexecuted. No public hosting, merge, or deployment is part of Phase 2.
