# OpenNest API verification with Telegram sessions

This record covers the implemented authentication and listing APIs after the sole-Telegram change. Contact reveals, reviews/reports, recruiter personas/resets/telemetry and the frontend remain future phases. They are not available APIs to test today.

On 10 November 2026 the maintainer completed a real local Telegram self-contact/OTP login in Postman and read the private profile with phoneVerified=true. The phone, account ID, cookies and code are deliberately absent from this record. This confirms local login/profile behavior; it does not establish hosted readiness or a live storage upload.

## Repeatable automated proof

TelegramContactIT starts separate production/development PRIMARY applications on real PostgreSQL/PostGIS and Redis containers. Its controlled bot exists only in tests. Listing lifecycle requests use cookies obtained by anonymous CSRF -> Telegram challenge -> matched self-contact -> OTP completion -> rotated session -> fresh CSRF. No prebuilt principal is used for this cross-API lifecycle.

The lifecycle covers ENTIRE_FLAT, FLAT ROOM and PG_HOSTEL with fabricated data across Indian cities. A second account independently logs in through the same flow to check ownership. A test-only storage fixture supplies bounded image bytes and immutable final objects; actual image validation and database writes still execute. R2 SDK behavior has separate local HTTP-fixture tests. Neither fixture contacts Telegram or Cloudflare.

| API | Successful behavior and checks |
| --- | --- |
| `GET /api/v1/auth/csrf` | Anonymous session/token; fresh token after login, no-store |
| `POST /api/v1/auth/telegram/challenges` | Same-session CSRF, challenge/bot link, no account or phone reservation |
| `GET /api/v1/auth/telegram/challenges/{id}` | Initiating-session-only bounded status; polling cannot authenticate |
| `POST /api/v1/auth/telegram/challenges/{id}/complete` | Contact/OTP proof, stable account, rotated cookie and invalidated old CSRF |
| `GET /api/v1/users/me` | Own verified profile, anonymous denial, safe dependency-outage response |
| `GET /api/v1/listings/search` | Nationwide public pagination, published visibility, private fields omitted |
| `POST /api/v1/listings` | Authenticated PAUSED draft, owner taken from Telegram principal, 201/id/version |
| `GET /api/v1/listings/mine` | Caller-only listings, pagination and private owner version |
| `GET /api/v1/listings/{id}` | Owner can read PAUSED; another account/anonymous receives 404; published anonymous detail excludes private fields |
| `POST /api/v1/listings/{id}/images/presign` | Owner-only reservation with signed URL/headers, missing CSRF rejected |
| `POST /api/v1/listings/{id}/images/complete` | Owner-only actual image decoding/re-encoding/finalization, idempotent retry |
| `POST /api/v1/listings/{id}/publish` | Required contact/images and quota remain enforced; current version activates mode-appropriate availability |
| `PATCH /api/v1/listings/{id}/inventory` | Mode-appropriate body, version increments, stale conflict, published zero vacancy becomes FULL_OCCUPIED, PAUSED remains PAUSED |
| `POST /api/v1/listings/{id}/pause` | Owner-only/current version, hides public listing while preserving owner access |
| `POST /api/v1/auth/logout` | Fresh CSRF required, 204, prior cookie loses owner authorization while public browsing remains available |
| `POST /api/v1/webhooks/telegram` | Webhook transport only; exact secret required, browser CSRF exemption limited to this route; fake/group/forwarded/stale/mismatched contacts rejected |

All response privacy/no-store, account ownership and CSRF expectations are checked alongside the successful lifecycle. Another account's PAUSED detail returns 404; its attempted write with valid CSRF returns 403. Existing listing tests separately prove concurrent publication/inventory limits, search snapshots/order/filter/geo behavior, migration upgrades, body limits and media deadlines. Telegram tests also cover concurrent signup/completion, expiry/restarts/attempt limits, realm/old-namespace replay, legacy recovery/refusal, provider retries and real datastore outages.

## OAuth removal audit

- Production source has no OAuth login/client registrations, provider redirects, callbacks or OAuth imports. Security uses only Telegram and server sessions.
- The POM declares no OAuth client starter. Packaged verification rejects OAuth libraries; the clean JAR is inspected rather than relying on an earlier build.
- Old provider authorization/callback/login paths are tested with anonymous and authenticated sessions, including POSTs with valid CSRF. They must be denied without redirect or authentication challenge headers.
- Old provider enablement/secrets and direct framework OAuth overrides are intentionally rejected. The setup helper intentionally removes legacy local settings. Those references are removal guards, not a usable authentication option.
- Generic auth_identities columns and existing private identity rows remain for data preservation. Telegram writes the fixed issuer/sender identity with no email. Removing OAuth does not authorize destructive schema/history edits or email/phone account merging.
- The local launcher regression script now tests Telegram settings with fake commands; it no longer depends on a provider secret prompt.

## Run the checks

Use Java 25 and a running Linux amd64 Docker engine, then run from the repository root:

```powershell
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pschema-integration,isolation-integration' clean verify
python .github/scripts/verify_backend.py --reports
python -m unittest discover --start-directory .github/scripts --pattern '*_test.py'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-start-local.ps1
git diff --check
```

The Java verification artifact is backend/target/backend-verification.json; oauthArtifactsPackaged=false is emitted only after the JAR absence check succeeds. Query-plan evidence is backend/target/listing-query-plans.txt. A completed local gate does not establish a CI result; inspect the branch's completed checks separately.

## Manual testing and remaining limits

Use [the Postman guide](POSTMAN_API_GUIDE.md) and [Telegram setup](TELEGRAM_HANDOFF.md). Local login/profile is maintainer-confirmed. The complete listing lifecycle is tested on isolated stores with controlled storage, not written into the maintainer's real account. An additional anonymous smoke against localhost:8080 could not run because no backend was listening during this audit; no account/session data was accessed.

Live R2 credentials, bucket CORS, direct signed upload/read and deployed provider behavior remain unverified. Storage-disabled upload endpoints intentionally return 503. Publication without required validated images returns 409. These responses do not indicate an authentication failure. Hosted HTTPS webhooks, live failure/logout/repeated-login checks, CI, production readiness and deployment remain separate gates.

On 10 November 2026, the clean Java 25/Linux amd64 Docker gate passed 553 tests: 427 default, 53 schema/persistence, 25 datastore isolation, 17 listing lifecycle and 31 Telegram authentication/API integration cases. No failures, errors or skips occurred. Report/artifact validation, six Python checks, 20 isolated PowerShell launcher regressions, context limits and whitespace checks passed. Both Postman collections passed JSON, embedded-script syntax and substituted-request-body validation. The packaged artifact contains neither test fixtures nor OAuth/OIDC application classes/libraries. Current-branch CI was not run by this local verification.

## Phase 4 interaction verification

The Phase4IT real-store suite adds contact quota/retry/midnight and review/report race/rollback coverage, plus isolated sandbox persona/reset/generation checks. TelegramContactIT also authenticates two controlled Telegram browser sessions and exercises reveal, review, conversion/retry, public review pagination, caller state, ownership/CSRF denial and paused historical eligibility. These are controlled-provider checks; real Telegram contact-reveal/manual sandbox workflows, live R2 and hosted deployment are not established by them. Current gate evidence and Postman instructions are in [the Phase 4 handoff](PHASE4_HANDOFF.md).
