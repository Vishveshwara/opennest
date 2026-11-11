# Phase 4 interactions and recruiter sandbox

Phase 4 implements backend interactions and the isolated recruiter sandbox. It adds V7 reset metadata while preserving V1-V6. No frontend, hosted environment, merge, or deployment is included.

## Use the interaction APIs

Authenticate through the existing Telegram flow on PRIMARY and refresh CSRF after login. Use the session cookie and X-CSRF-TOKEN for POST requests. Every response is no-store. Import [the Phase 4 Postman collection](postman/OpenNest-Interactions.postman_collection.json) into the same local cookie jar.

| Route | Request and result |
| --- | --- |
| POST /api/v1/interactions/listings/{id}/reveal-phone | No body or `{}`. Published listing, non-owner caller and verified owner contact required. Returns phoneNumber, whatsappUrl, revealedDate and remainingReveals. |
| POST /api/v1/interactions/listings/{id}/reviews | `{rating, issueTag, commentText}`. Rating integer 1..5; issueTag is GENUINE_OWNER, MAINTENANCE_ISSUE, FAKE_PHOTOS or OTHER. Optional commentText is null or nonblank text up to 2000 characters. |
| POST /api/v1/interactions/listings/{id}/report-brokerage | No body or `{}`. Inserts a one-star allegation or converts the caller's existing review in place. |
| GET /api/v1/interactions/listings/{id}/reviews | Public for published listings. Optional page and size only; defaults 0 and 20, size 1..50, page nonnegative and page*size <=10000. Standard listing pagination envelope, NEWEST then UUID ascending. |
| GET /api/v1/interactions/listings/{id}/state | Authenticated caller only. Returns hasHistoricalReveal, revealedToday, quotaDate, remainingReveals, contactActionsEnabled, canReview, canReportBrokerage and review. No phone data. |

Review responses contain id, rating, issueTag, commentText, isAutoBrokerReport, createdAt and allegationNotice. Public responses exclude reviewer/owner IDs, contact information and private listing fields. Paused public review reads return 404. Caller state and review/report writes retain historical reveal eligibility after pause; a paused listing without ownership or history returns 404.

Reveals serialize on the seeking user's row. After locking, the service captures one database clock_timestamp and uses its Kolkata date for both quota lookup and insertion. The ten-distinct-listing allowance is PostgreSQL-backed; same-day retries remain free even when exhausted or Redis is cleared. The ledger stores no contact snapshot.

Reviews require historical contact reveal for that exact reviewer/listing/owner and forbid self-interactions. Ordinary reviews are create-only; duplicates return 409. A report preserves an existing review's UUID, createdAt and commentText. Repeating it changes nothing. Review writes serialize on the owner row and recompute owner aggregates in the same transaction. NO KEY UPDATE account locks avoid reciprocal foreign-key deadlocks.

Reports remain allegations and never pause, hide or delete listings. The allegationNotice is separate from preserved user text. Existing trust thresholds remain authoritative.

Unknown fields, duplicate JSON keys, invalid values, duplicate/unknown pagination parameters and bodies above 8 KiB are rejected. Responses use the existing safe Problem Details contract: 400 invalid input, 401 unauthenticated, 403 unauthorized/contact required, 404 unavailable listing, 409 duplicate ordinary review, 413 oversized body, 429 reveal allowance and 503 recognized outages. Unexpected failures remain 500.

## Start the dedicated sandbox

Use a separate DEVELOPMENT/SANDBOX backend and the existing isolated sandbox PostgreSQL/Redis settings. Do not supply primary secrets. Set OPENNEST_TELEGRAM_AUTH_ENABLED=false and OPENNEST_PERSONA_SWITCHING=true. OPENNEST_DEMO_RESET and OPENNEST_DEMO_TELEMETRY each require explicit true to enable their operations. The public recruiter frontend remains Phase 5 work.

First startup seeds only an empty dedicated database. An unrecognized populated database is rejected instead of erased. The migration itself creates no fixture rows in PRIMARY or SANDBOX.

Seed version 1 includes nine fabricated personas and 17 listings across Delhi, Bengaluru, Mumbai, Pune and Hyderabad. It covers all rental modes/statuses, five trust badges, occupancy/owner-quota examples, historical reveal eligibility and bundled illustrative PNGs. It contains no callable phone numbers, real provider identities or Telegram receipts. These cities describe fixtures, not a city allowlist.

1. Fetch GET /api/v1/auth/csrf at the sandbox backend to establish its own cookie and CSRF token.
2. Read GET /api/v1/sandbox/personas for allowlisted keys and labels.
3. Send POST /api/v1/sandbox/personas/select with `{ "personaKey": "seeker-new" }` and CSRF. Expect AUTHENTICATED and a rotated cookie.
4. Refresh CSRF before any subsequent mutation. Use normal listing and interaction APIs with the selected persona.
5. Contact reveal always returns 403 in sandbox. Historical fixture reveals permit ordinary reviews/reports without granting callable contacts. Normal publication still requires contact, validated images and quota.

Persona keys are owner-standard, owner-top, owner-low, owner-reported, owner-critical, seeker-one, seeker-two, seeker-three and seeker-new. Anyone with the unlisted sandbox URL can select one. There is no public navigation link or production persona fallback.

Identity owns the sandbox session with its own cookie, realm, Redis store and personas:v1 namespace. Switching persona rotates the ID and clears CSRF. Existing idle/absolute limits remain. PRIMARY rejects sandbox sessions and operator commands; headers never select a database or grant authorization. Telegram remains the sole PRIMARY login provider.

Bundled illustrative images are served by GET /api/v1/sandbox/images/{segment}.png, only with sandbox personas enabled. Seeded image URLs are backend-relative. Real uploads continue directly to separately configured R2 and follow normal validation; no backend upload proxy is added.

## Run operator commands

Set isolated sandbox configuration through the process environment. Enable the relevant feature flag. Use a Java 25 packaged application with web mode disabled:

```powershell
java -jar backend/target/opennest-backend-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none --opennest.sandbox.command=telemetry
java -jar backend/target/opennest-backend-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none --opennest.sandbox.command=reset
```

Commands close their application context after completion. Reset requires OPENNEST_DEMO_RESET=true; telemetry requires OPENNEST_DEMO_TELEMETRY=true. No visitor reset or telemetry HTTP route exists. Output contains only fixture counts, initialization status, seed version, generation and reset time.

With reset enabled, scheduling runs at 00:00, 06:00, 12:00 and 18:00 Asia/Kolkata. Multiple instances use the same interval marker, so retries in one interval do nothing. A manual reset is forced and increments generation each time.

Normal sandbox transactions acquire a shared PostgreSQL advisory lock; reset acquires it exclusively, deletes children before parents, recreates fixtures and advances metadata in one transaction. Failed resets roll back completely. Repeatable-read snapshot checks lock the metadata row and return a safe 503 if reset made their snapshot obsolete. Old sessions return 401 on the next request. Reset never deletes R2 objects or connects to primary stores.

## Verification and remaining gates

The repeatable gate is:

```powershell
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pschema-integration,isolation-integration' clean verify
python -m unittest discover --start-directory .github/scripts --pattern '*_test.py'
python .github/scripts/verify_backend.py --reports
```

Phase4IT covers concurrent reveal limits/retries, post-lock midnight capture, Redis loss, review/report conversion races, reciprocal users, rollback, public/caller privacy, HTTP validation/CSRF, fixture breadth, persona rotation, publication/contact denial, reset/write exclusion and stale snapshots/sessions. TelegramContactIT exercises the interaction routes with controlled Telegram-created browser sessions. Schema checks compare V7 on both isolated targets.

Local verification on 11 November 2026 used Windows Java 25.0.5.0.2 and Linux PostGIS/Redis containers. Final validated reports contain 596 passing Java tests with no failures/errors/skips: 450 default, 55 schema, 25 isolation, 33 Telegram, 16 Phase 4 and 17 listing integration tests. All six Python checks, wrapper/context limits and packaged migration/test-fixture/removed-provider boundaries passed. Postman JSON parses, and V1-V6 match their original contents.

The combined clean run executed every suite. One remaining isolation assertion still expected V6 as the latest migration; it was corrected to V7 and the complete 25-test isolation suite reran successfully. The final report checker validated all current suite reports together. The targeted recheck command was:

```powershell
.\backend\mvnw.cmd --batch-mode --no-transfer-progress --settings docs/verification/backend-dependencies/settings.xml --global-settings docs/verification/backend-dependencies/settings.xml --file backend/pom.xml '-Pschema-integration,isolation-integration' '-Dtest=OpenNestApplicationTests' '-Dit.test=BackendIsolationIT' verify
```

Raw local results are in backend/target/backend-verification.json and the Surefire/Failsafe reports. These are generated artifacts, not committed evidence. Controlled-provider tests do not establish live Telegram, R2 or hosted readiness. CI was not run or claimed; inspect it separately before release.

Before collecting public review/contact data, approve retention/erasure and a staffed appeal policy. Review editing, deletion and moderation endpoints remain absent. For a manual appeal, privately record the affected review UUID and owner/listing, verify the requester, review the allegation and preserve the original evidence privately. Any correction requires a separately authorized operator change that locks the owner and recomputes aggregates transactionally. Do not clear reports through an ordinary review or auto-hide a listing. No public appeal contact or response-time commitment is configured yet.
